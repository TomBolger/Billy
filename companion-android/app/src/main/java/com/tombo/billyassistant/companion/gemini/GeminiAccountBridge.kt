package com.tombo.billyassistant.companion.gemini

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Optional link to the user's own Gemini (gemini.google.com) account.
 *
 * A hidden WebView keeps gemini.google.com open with the user's sign-in; a
 * question is sent by running the same request the website makes, from inside
 * that page, so Google's own cookies and token refresh apply. Everything here
 * is a bonus: when it is off, signed out, slow, or broken, Billy's normal
 * tools answer instead. Three failures in a row switch it off until the user
 * signs in again. The sign-in never leaves the phone.
 */
object GeminiAccountBridge {
    enum class State { OFF, NOT_SIGNED_IN, WORKING, NEEDS_ATTENTION }

    sealed class Reply {
        data class Answer(val text: String, val imageUrls: List<String>) : Reply()
        data class Failed(val reason: String) : Reply()
    }

    const val HOME_URL = "https://gemini.google.com/app"
    const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fgemini.google.com%2Fapp"
    private const val PREFS = "billy_gemini_account"
    private const val MAX_FAILURES = 3
    private const val PAGE_MAX_AGE_MS = 2 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 25_000L
    private const val TIMED_OUT = "Gemini took too long to answer."

    private val main = Handler(Looper.getMainLooper())
    private val requestIds = AtomicInteger(1)
    private val lock = Any()
    private var webView: WebView? = null
    private var pageLoadedAt = 0L
    private var pageReady = false
    private var onPageReady: (() -> Unit)? = null
    @Volatile private var pending: Pair<Int, (String) -> Unit>? = null

    // ---- status ---------------------------------------------------------------

    fun state(context: Context): State {
        val p = prefs(context)
        return when {
            !p.getBoolean("enabled", false) -> State.OFF
            p.getBoolean("needs_attention", false) -> State.NEEDS_ATTENTION
            !p.getBoolean("signed_in", false) -> State.NOT_SIGNED_IN
            else -> State.WORKING
        }
    }

    fun isUsable(context: Context) = state(context) == State.WORKING

    fun isSignedIn(context: Context) = prefs(context).getBoolean("signed_in", false)

    fun lastError(context: Context): String = prefs(context).getString("last_error", "").orEmpty()

    fun lastSuccessAt(context: Context): Long = prefs(context).getLong("last_ok", 0L)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
    }

    /** Called after a successful sign-in: turn on and clear any earlier trouble. */
    fun markSignedIn(context: Context) {
        prefs(context).edit()
            .putBoolean("signed_in", true)
            .putBoolean("enabled", true)
            .putBoolean("needs_attention", false)
            .putInt("failures", 0)
            .putString("last_error", "")
            .apply()
        CookieManager.getInstance().flush()
        main.post { pageLoadedAt = 0L }
    }

    fun signOut(context: Context) {
        prefs(context).edit().clear().apply()
        main.post {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            webView?.destroy()
            webView = null
            pageLoadedAt = 0L
            pageReady = false
        }
    }

    /** Chrome's user agent without the WebView marker, so Google treats the sign-in like Chrome. */
    fun chromeUserAgent(context: Context): String = runCatching { WebSettings.getDefaultUserAgent(context) }
        .getOrDefault("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
        .replace("; wv)", ")")
        .replace(Regex("""Version/\d+(\.\d+)* """), "")

    // ---- asking -----------------------------------------------------------------

    /** Ask the user's Gemini. Blocking; never call on the main thread. */
    fun ask(context: Context, question: String): Reply {
        if (Looper.myLooper() == Looper.getMainLooper()) return Reply.Failed("Called on the main thread.")
        if (!isUsable(context)) return Reply.Failed("The Gemini account link is off.")
        val app = context.applicationContext
        val reply = synchronized(lock) {
            val started = System.currentTimeMillis()
            val first = request(app, question, TIMEOUT_MS)
            val left = TIMEOUT_MS - (System.currentTimeMillis() - started)
            // An old page can hold expired tokens: reload once and retry if there's time.
            val pageProblem = first is Reply.Failed && (first.reason == "signed_out" || first.reason.startsWith("Gemini returned HTTP"))
            if (pageProblem && left > 8_000) request(app, question, left) else first
        }
        record(app, reply)
        return reply
    }

    /**
     * Start loading Gemini (and picking its fast model) while Billy is still
     * thinking, so ask_my_gemini doesn't wait for the page.
     */
    fun warmUp(context: Context) {
        if (!isUsable(context)) return
        val app = context.applicationContext
        main.post {
            if (pageReady && System.currentTimeMillis() - pageLoadedAt < PAGE_MAX_AGE_MS) return@post
            if (onPageReady != null) return@post
            val view = ensureWebView(app)
            onPageReady = { view.evaluateJavascript("$PICK_MODEL_JS; window.__billyModel();", null) }
            pageReady = false
            pageLoadedAt = System.currentTimeMillis()
            view.loadUrl(HOME_URL)
        }
    }

    /** Runs a script in the signed-in Gemini page and waits for it to call BillyBridge.onResult. */
    private fun runInPage(context: Context, timeoutMs: Long, script: (Int) -> String): String? {
        val id = requestIds.getAndIncrement()
        val latch = CountDownLatch(1)
        var raw: String? = null
        pending = id to { result -> raw = result; latch.countDown() }
        main.post {
            val view = ensureWebView(context)
            val run = { view.evaluateJavascript(script(id), null) }
            val fresh = System.currentTimeMillis() - pageLoadedAt < PAGE_MAX_AGE_MS
            when {
                pageReady && fresh -> run()
                onPageReady != null && fresh -> {
                    // A warm-up load is already under way: run after it.
                    val earlier = onPageReady
                    onPageReady = { earlier?.invoke(); run() }
                }
                else -> {
                    pageReady = false
                    onPageReady = run
                    pageLoadedAt = System.currentTimeMillis()
                    view.loadUrl(HOME_URL)
                }
            }
        }
        val finished = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        pending = null
        return if (finished) raw else null
    }

    private fun request(context: Context, question: String, timeoutMs: Long): Reply {
        val raw = runInPage(context, timeoutMs) { id -> script(id, question) }
        if (raw == null) {
            main.post { pageLoadedAt = 0L } // reload next time
            return Reply.Failed(TIMED_OUT)
        }
        val result = runCatching { JSONObject(raw.orEmpty()) }.getOrNull()
            ?: return Reply.Failed("Unreadable reply from the page.")
        result.optString("model").takeIf { it.isNotBlank() }?.let { lastModel = it }
        result.optString("error").takeIf { it.isNotBlank() }?.let { error ->
            main.post { pageLoadedAt = 0L }
            return if (error == "signed_out") Reply.Failed("signed_out") else Reply.Failed(error.take(200))
        }
        val body = result.optString("body")
        saveLastReply(context, body)
        runCatching { imageLogFile(context).delete() }
        val status = result.optInt("status")
        if (status != 200) {
            main.post { pageLoadedAt = 0L }
            return Reply.Failed("Gemini returned HTTP $status.")
        }
        val parsed = GeminiWebResponse.parse(body)
        if (parsed.text.isBlank() && parsed.imageUrls.isEmpty()) {
            return Reply.Failed(parsed.errorCode?.let { "Gemini error code $it." } ?: "Empty answer (Google may have changed the website).")
        }
        return Reply.Answer(parsed.text, parsed.imageUrls)
    }

    private fun record(context: Context, reply: Reply) {
        val p = prefs(context)
        when (reply) {
            is Reply.Answer -> p.edit().putInt("failures", 0).putLong("last_ok", System.currentTimeMillis()).putString("last_error", "").apply()
            is Reply.Failed -> {
                val failures = p.getInt("failures", 0) + 1
                val signedOut = reply.reason == "signed_out"
                p.edit()
                    .putInt("failures", failures)
                    .putString("last_error", if (signedOut) "Signed out of Gemini." else reply.reason)
                    .putBoolean("needs_attention", signedOut || failures >= MAX_FAILURES)
                    .apply()
            }
        }
    }

    /** Which Gemini model answered last (for the setup screen). */
    @Volatile var lastModel: String = ""
        private set

    // ---- debugging: the last raw reply, so a website change can be diagnosed --------

    private fun lastReplyFile(context: Context) = File(context.applicationContext.filesDir, "gemini-last-reply.txt")

    private fun saveLastReply(context: Context, body: String) {
        runCatching { lastReplyFile(context).writeText(body.take(400_000)) }
    }

    fun hasLastReply(context: Context) = lastReplyFile(context).exists()

    /** Copies the last raw reply into the phone's Downloads folder. Returns the file name, or null. */
    fun exportLastReply(context: Context): String? = runCatching {
        val source = lastReplyFile(context)
        if (!source.exists() || android.os.Build.VERSION.SDK_INT < 29) return null
        val name = "billy-gemini-reply-${System.currentTimeMillis() / 1000}.txt"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            imageLogFile(context).takeIf { it.exists() }?.let { log ->
                out.write("=== picture download attempts ===\n".toByteArray())
                log.inputStream().use { it.copyTo(out) }
                out.write("\n=== Gemini reply ===\n".toByteArray())
            }
            source.inputStream().use { it.copyTo(out) }
        }
        name
    }.getOrNull()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun ensureWebView(context: Context): WebView {
        webView?.let { return it }
        CookieManager.getInstance().setAcceptCookie(true)
        val view = WebView(context)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.userAgentString = chromeUserAgent(context)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.addJavascriptInterface(Callback, "BillyBridge")
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (!url.startsWith("https://gemini.google.com") && !url.startsWith("https://accounts.google.com")) return
                pageReady = true
                val next = onPageReady
                onPageReady = null
                next?.invoke()
            }
        }
        view.onResume()
        view.resumeTimers()
        webView = view
        return view
    }

    private object Callback {
        @JavascriptInterface
        fun onResult(id: Int, json: String) {
            val current = pending
            if (current != null && current.first == id) current.second(json)
        }
    }

    /**
     * Picks the account's fast model (Flash) once per page load, so answers
     * don't wait on whatever slower model the Gemini app was last set to.
     * Layout follows github.com/HanaokaYuzu/Gemini-API; on any surprise it
     * resolves to null and Gemini's default model is used.
     */
    private val PICK_MODEL_JS = """
        window.__billySession = window.__billySession || (crypto.randomUUID ? crypto.randomUUID() : String(Date.now())).toUpperCase();
        window.__billyModel = window.__billyModel || function() {
          if (window.__billyModelPromise) return window.__billyModelPromise;
          window.__billyModelPromise = (async function() {
            try {
              const w = window.WIZ_global_data;
              if (!w || !w.SNlM0e) return null;
              const params = new URLSearchParams({rpcids: 'otAQ7b', 'source-path': '/app', hl: 'en', _reqid: String(10000 + Math.floor(Math.random() * 90000)), rt: 'c'});
              if (w.cfb2h) params.set('bl', w.cfb2h);
              if (w.FdrFJe) params.set('f.sid', w.FdrFJe);
              const res = await fetch('/_/BardChatUi/data/batchexecute?' + params.toString(), {
                method: 'POST', credentials: 'include',
                headers: {
                  'Content-Type': 'application/x-www-form-urlencoded;charset=utf-8',
                  'X-Same-Domain': '1',
                  'x-goog-ext-525001261-jspb': JSON.stringify([1,null,null,null,null,null,null,null,[4,5,6,8],null,null,null,null,null,null,null,window.__billySession]),
                  'x-goog-ext-73010989-jspb': '[0]'
                },
                body: new URLSearchParams({at: w.SNlM0e, 'f.req': JSON.stringify([[['otAQ7b', '[]', null, 'generic']]])})
              });
              const text = await res.text();
              let body = null;
              text.split('\n').forEach(function(line) {
                if (body || line.charAt(0) !== '[') return;
                try {
                  JSON.parse(line).forEach(function(part) {
                    if (!body && Array.isArray(part) && part[0] === 'wrb.fr' && part[1] === 'otAQ7b' && typeof part[2] === 'string') body = JSON.parse(part[2]);
                  });
                } catch (e) {}
              });
              if (!body || !Array.isArray(body[15])) return null;
              const tiers = Array.isArray(body[16]) ? body[16] : [];
              const caps = Array.isArray(body[17]) ? body[17] : [];
              let capacity = 1, field = 12;
              if (tiers.indexOf(21) >= 0) { capacity = 1; field = 13; }
              else if (tiers.indexOf(22) >= 0) { capacity = 2; field = 13; }
              else if (caps.indexOf(115) >= 0) capacity = 4;
              else if (tiers.indexOf(16) >= 0 || caps.indexOf(106) >= 0) capacity = 3;
              else if (tiers.indexOf(8) >= 0 || caps.indexOf(19) >= 0) capacity = 2;
              let best = null;
              body[15].forEach(function(m) {
                if (best || !Array.isArray(m) || typeof m[0] !== 'string') return;
                const words = [m[1], m[10], m[11], m[19], m[12], m[2]].filter(function(v) { return typeof v === 'string'; }).join(' ').toLowerCase();
                if (/fast|flash/.test(words) && !/lite|think|pro\b|deep/.test(words)) {
                  best = {id: m[0], number: typeof m[17] === 'number' ? m[17] : (typeof m[9] === 'number' ? m[9] : 1),
                          label: String(m[11] || m[19] || m[1] || m[0])};
                }
              });
              if (!best) return null;
              best.capacity = capacity; best.field = field;
              return best;
            } catch (e) { return null; }
          })();
          return window.__billyModelPromise;
        };
    """.trimIndent()

    /** The website's own request, made from inside the page. Layout follows github.com/HanaokaYuzu/Gemini-API. */
    private fun script(id: Int, question: String): String {
        val language = Locale.getDefault().language.ifBlank { "en" }
        return PICK_MODEL_JS + "\n" + """
            (async function() {
              const send = (o) => BillyBridge.onResult($id, JSON.stringify(o));
              try {
                let w = window.WIZ_global_data;
                for (let i = 0; i < 30 && !(w && w.SNlM0e); i++) {
                  await new Promise(r => setTimeout(r, 200));
                  w = window.WIZ_global_data;
                }
                if (!w || !w.SNlM0e || location.host !== 'gemini.google.com') { send({error: 'signed_out'}); return; }
                const model = await Promise.race([window.__billyModel(), new Promise(r => setTimeout(() => r(null), 3000))]);
                const uuid = (crypto.randomUUID ? crypto.randomUUID() : String(Date.now())).toUpperCase();
                const lang = ${JSONObject.quote(language)};
                const inner = new Array(81).fill(null);
                inner[0] = [${JSONObject.quote(question)}, 0, null, null, null, null, 0];
                inner[1] = [lang];
                inner[2] = ['', '', '', null, null, null, null, null, null, ''];
                inner[6] = [1]; inner[7] = 1; inner[10] = 1; inner[11] = 0;
                inner[17] = [[0]]; inner[18] = 0; inner[27] = 1; inner[30] = [4];
                inner[41] = [1]; inner[53] = 0; inner[59] = uuid; inner[61] = [];
                inner[68] = 1; inner[79] = model ? model.number : 1; inner[80] = 1;
                const headers = {
                  'Content-Type': 'application/x-www-form-urlencoded;charset=utf-8',
                  'X-Same-Domain': '1',
                  'x-goog-ext-525005358-jspb': '["' + uuid + '",1]'
                };
                if (model) {
                  const h = [1, null, null, null, model.id, null, null, 0, [4, 5, 6, 8], null, null];
                  if (model.field === 13) h.push(null, model.capacity); else h.push(model.capacity);
                  h.push(null, null, model.number, 1, window.__billySession);
                  headers['x-goog-ext-525001261-jspb'] = JSON.stringify(h);
                  headers['x-goog-ext-73010989-jspb'] = '[0]';
                  headers['x-goog-ext-73010990-jspb'] = '[0,0,0]';
                }
                const params = new URLSearchParams({hl: lang, _reqid: String(10000 + Math.floor(Math.random() * 90000)), rt: 'c'});
                if (w.cfb2h) params.set('bl', w.cfb2h);
                if (w.FdrFJe) params.set('f.sid', w.FdrFJe);
                const body = new URLSearchParams({at: w.SNlM0e, 'f.req': JSON.stringify([null, JSON.stringify(inner)])});
                const res = await fetch('/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate?' + params.toString(), {
                  method: 'POST', credentials: 'include', headers: headers, body: body
                });
                send({status: res.status, body: await res.text(), model: model ? model.label : 'default'});
              } catch (e) {
                send({error: String((e && e.message) || e)});
              }
            })();
        """.trimIndent()
    }

    // ---- pictures -----------------------------------------------------------------

    /**
     * Download a picture Gemini showed, sized for the watch. Photos from the
     * user's library are private, so if a plain download is refused the
     * signed-in Gemini page fetches it, the way the website itself shows it.
     * Every attempt is logged for the "Save last reply" debug file.
     */
    fun downloadImage(context: Context, url: String): Bitmap? {
        val app = context.applicationContext
        val log = StringBuilder("image: ").append(url.take(160)).append('\n')
        val candidates = listOf(sizedForWatch(url), url).distinct()
        val deadline = System.currentTimeMillis() + 25_000
        for (candidate in candidates) {
            try {
                val connection = (URL(candidate).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6_000
                    readTimeout = 6_000
                    setRequestProperty("User-Agent", chromeUserAgent(app))
                    setRequestProperty("Accept", "image/webp,image/jpeg,image/png,image/*;q=0.8")
                    setRequestProperty("Referer", "https://gemini.google.com/")
                    CookieManager.getInstance().getCookie(candidate)?.let { setRequestProperty("Cookie", it) }
                }
                val code = connection.responseCode
                if (code == 200) {
                    val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) }
                    if (bitmap != null) {
                        saveImageLog(app, log.append("direct ok: ").append(candidate.takeLast(20)).toString())
                        return bitmap
                    }
                    val peek = runCatching {
                        (URL(candidate).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 6_000; readTimeout = 6_000
                            setRequestProperty("User-Agent", chromeUserAgent(app))
                            CookieManager.getInstance().getCookie(candidate)?.let { setRequestProperty("Cookie", it) }
                        }.let { c -> "final ${c.apply { inputStream.close() }.url.host}" }
                    }.getOrDefault("")
                    log.append("direct ").append(candidate.takeLast(12)).append(": 200 but not a picture (").append(connection.contentType).append(") ").append(peek)
                        .append(if (CookieManager.getInstance().getCookie(candidate).isNullOrBlank()) " [no cookies for this host]" else " [had cookies]").append("\n")
                } else {
                    log.append("direct ").append(candidate.takeLast(12)).append(": HTTP ").append(code).append('\n')
                }
            } catch (e: Exception) {
                log.append("direct ").append(candidate.takeLast(12)).append(": ").append(e.javaClass.simpleName).append(' ').append(e.message).append('\n')
            }
        }
        for (candidate in candidates) {
            val left = deadline - System.currentTimeMillis()
            if (left < 3_000) { log.append("out of time\n"); break }
            val raw = synchronized(lock) { runInPage(app, left.coerceAtMost(15_000)) { id -> imageScript(id, candidate) } }
            val result = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            val data = result?.optString("data").orEmpty()
            if (data.isNotBlank()) {
                val bytes = runCatching { android.util.Base64.decode(data, android.util.Base64.DEFAULT) }.getOrNull()
                val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (bitmap != null) {
                    saveImageLog(app, log.append("page ok via ").append(result?.optString("how")).toString())
                    return bitmap
                }
                log.append("page ").append(candidate.takeLast(12)).append(": data not decodable (").append(result?.optString("type")).append(")\n")
            } else {
                log.append("page ").append(candidate.takeLast(12)).append(": ").append(result?.optString("error") ?: "no answer").append('\n')
            }
        }
        val left = deadline - System.currentTimeMillis()
        if (left >= 3_000) {
            val rendered = renderInBrowser(app, url, left.coerceAtMost(12_000), log)
            if (rendered != null) {
                saveImageLog(app, log.append("rendered ok").toString())
                return rendered
            }
        } else {
            log.append("no time left to render\n")
        }
        saveImageLog(app, log.toString())
        return null
    }

    /**
     * Last resort: show the picture in an off-screen browser page that has the
     * user's Google sign-in, and copy the pixels. Works whenever a browser can
     * display the picture, whatever the reason a plain download can't.
     */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun renderInBrowser(context: Context, url: String, timeoutMs: Long, log: StringBuilder): Bitmap? {
        val size = 480
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        var view: WebView? = null
        main.post {
            try {
                val v = WebView(context)
                view = v
                v.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                v.settings.javaScriptEnabled = true
                v.settings.userAgentString = chromeUserAgent(context)
                CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
                v.setBackgroundColor(android.graphics.Color.BLACK)
                v.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY),
                )
                v.layout(0, 0, size, size)
                v.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun loaded(width: Int, height: Int) {
                        log.append("render: picture is ").append(width).append('x').append(height).append('\n')
                        // Off-screen pages draw lazily: try a few times and only accept a
                        // frame that actually contains a picture.
                        fun attempt(n: Int) {
                            try {
                                val full = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                                full.eraseColor(android.graphics.Color.BLACK)
                                v.invalidate()
                                v.draw(android.graphics.Canvas(full))
                                // The whole picture, centred and scaled to fit: crop exactly
                                // that rectangle, whatever its shape.
                                val fit = minOf(size.toFloat() / width.coerceAtLeast(1), size.toFloat() / height.coerceAtLeast(1))
                                val dw = (width * fit).roundToInt().coerceIn(1, size)
                                val dh = (height * fit).roundToInt().coerceIn(1, size)
                                val box = contentBox(full)?.let { android.graphics.Rect((size - dw) / 2, (size - dh) / 2, (size - dw) / 2 + dw, (size - dh) / 2 + dh) }
                                log.append("render try ").append(n).append(": ").append(box?.let { "${it.width()}x${it.height()} with detail" } ?: "blank").append('\n')
                                if (box != null) {
                                    result = Bitmap.createBitmap(full, box.left, box.top, box.width(), box.height())
                                    if (result !== full) full.recycle()
                                    latch.countDown()
                                    return
                                }
                                full.recycle()
                            } catch (e: Exception) {
                                log.append("render: ").append(e.javaClass.simpleName).append(' ').append(e.message).append('\n')
                            }
                            if (n < 5) main.postDelayed({ attempt(n + 1) }, 500L) else latch.countDown()
                        }
                        main.postDelayed({ attempt(1) }, 600L)
                    }

                    @JavascriptInterface
                    fun failed() {
                        log.append("render: the picture didn't load in the browser either\n")
                        latch.countDown()
                    }
                }, "BillyImage")
                // The page lays out in CSS pixels, which the phone scales up by its
                // display density. Size the box in CSS pixels so that it covers exactly
                // size x size real pixels, and fit the whole picture inside it.
                val css = size / context.resources.displayMetrics.density
                val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
                    "<body style='margin:0;padding:0;background:#000;overflow:hidden'>" +
                    "<img id='i' style='display:block;width:${css}px;height:${css}px;object-fit:contain' " +
                    "onload='BillyImage.loaded(this.naturalWidth,this.naturalHeight)' onerror='BillyImage.failed()' src=\"" +
                    sizedForWatch(url).replace("&", "&amp;").replace("\"", "&quot;") + "\"></body></html>"
                v.loadDataWithBaseURL("https://gemini.google.com/", html, "text/html", "utf-8", null)
            } catch (e: Exception) {
                log.append("render setup: ").append(e.message).append('\n')
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) log.append("render: timed out\n")
        main.post { view?.destroy() }
        return result
    }

    private fun imageScript(id: Int, url: String): String = """
        (async function() {
          const send = (o) => BillyBridge.onResult($id, JSON.stringify(o));
          const url = ${JSONObject.quote(url)};
          const errors = [];
          const toB64 = (blob) => new Promise((ok, fail) => {
            const r = new FileReader();
            r.onload = () => ok(String(r.result).split(',')[1]);
            r.onerror = () => fail(new Error('read failed'));
            r.readAsDataURL(blob);
          });
          for (const credentials of ['include', 'omit']) {
            try {
              const res = await fetch(url, {credentials: credentials, mode: 'cors'});
              if (res.ok) {
                const blob = await res.blob();
                send({data: await toB64(blob), type: blob.type, how: 'fetch-' + credentials});
                return;
              }
              errors.push('fetch-' + credentials + ': HTTP ' + res.status);
            } catch (e) { errors.push('fetch-' + credentials + ': ' + e.message); }
          }
          for (const mode of ['use-credentials', 'anonymous', null]) {
            try {
              const data = await new Promise((ok, fail) => {
                const img = new Image();
                if (mode) img.crossOrigin = mode;
                const timer = setTimeout(() => fail(new Error('timed out')), 8000);
                img.onload = () => {
                  clearTimeout(timer);
                  try {
                    const scale = Math.min(1, 480 / Math.max(img.naturalWidth, img.naturalHeight));
                    const c = document.createElement('canvas');
                    c.width = Math.max(1, Math.round(img.naturalWidth * scale));
                    c.height = Math.max(1, Math.round(img.naturalHeight * scale));
                    c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
                    ok(c.toDataURL('image/jpeg', 0.85).split(',')[1]);
                  } catch (e) { fail(e); }
                };
                img.onerror = () => { clearTimeout(timer); fail(new Error('load failed')); };
                img.src = url;
              });
              send({data: data, type: 'image/jpeg', how: 'img-' + (mode || 'plain')});
              return;
            } catch (e) { errors.push('img-' + (mode || 'plain') + ': ' + e.message); }
          }
          send({error: errors.join('; ')});
        })();
    """.trimIndent()

    /** The area of a frame that holds the picture, or null if the frame is (nearly) blank. */
    private fun contentBox(bitmap: Bitmap): android.graphics.Rect? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var left = w; var top = h; var right = -1; var bottom = -1
        var sum = 0.0; var sumSq = 0.0; var count = 0
        for (y in 0 until h) for (x in 0 until w) {
            val p = pixels[y * w + x]
            val lum = ((p shr 16 and 0xFF) * 3 + (p shr 8 and 0xFF) * 6 + (p and 0xFF)) / 10.0
            if (lum > 12) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
            sum += lum; sumSq += lum * lum; count++
        }
        if (right < 0) return null
        val mean = sum / count
        val spread = kotlin.math.sqrt((sumSq / count - mean * mean).coerceAtLeast(0.0))
        val box = android.graphics.Rect(left, top, right + 1, bottom + 1)
        return if (box.width() >= 64 && box.height() >= 64 && spread > 10) box else null
    }

    private fun imageLogFile(context: Context) = File(context.applicationContext.filesDir, "gemini-last-image.txt")

    private fun saveImageLog(context: Context, text: String) {
        runCatching { imageLogFile(context).appendText(text.trimEnd() + "\n\n") }
    }

    /** Google image hosts take a size suffix after "="; ask for a watch-sized copy. */
    private fun sizedForWatch(url: String): String {
        val host = runCatching { URL(url).host }.getOrDefault("")
        val fife = host.matches(Regex("""lh\d+\.googleusercontent\.com""")) || host.endsWith(".usercontent.google.com") || host.endsWith(".ggpht.com")
        if (!fife) return url
        val base = url.substringBefore('?')
        val lastSlash = base.lastIndexOf('/')
        val eq = base.indexOf('=', lastSlash.coerceAtLeast(0))
        return (if (eq > 0) base.substring(0, eq) else base) + "=s480" + url.substring(base.length)
    }

    // ---- rooted phones: reuse Chrome's sign-in --------------------------------------

    private val CHROME_PACKAGES = listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary")

    /**
     * Copies Google cookies from Chrome into Billy's WebView (needs root). For
     * phones where Google refuses to sign in inside an app. Returns an error
     * message, or null on success.
     */
    fun importFromChrome(context: Context): String? {
        val dir = File(context.cacheDir, "chrome_import").apply { mkdirs() }
        val db = File(dir, "Cookies")
        try {
            val chrome = CHROME_PACKAGES.firstOrNull { pkg ->
                runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
            } ?: return "Chrome isn't installed."
            val source = "/data/data/$chrome/app_chrome/Default/Cookies"
            val command = "cat '$source' > '${db.path}' && chmod 666 '${db.path}'; " +
                "[ -f '$source-wal' ] && cat '$source-wal' > '${db.path}-wal' && chmod 666 '${db.path}-wal'; true"
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            if (!process.waitFor(20, TimeUnit.SECONDS) || !db.exists() || db.length() == 0L) {
                return "Root access was refused or Chrome's cookies couldn't be read."
            }
            val cookies = CookieManager.getInstance()
            var copied = 0
            var encrypted = 0
            SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
                sql.rawQuery(
                    "SELECT host_key, name, value, path, is_secure, is_httponly, expires_utc, length(encrypted_value) FROM cookies " +
                        "WHERE host_key LIKE '%google.com'",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        val host = c.getString(0)
                        val value = c.getString(2).orEmpty()
                        if (value.isEmpty()) {
                            if (c.getInt(7) > 0) encrypted++
                            continue
                        }
                        val name = c.getString(1)
                        val path = c.getString(3).ifBlank { "/" }
                        val builder = StringBuilder("$name=$value; Path=$path")
                        if (host.startsWith(".")) builder.append("; Domain=$host")
                        if (c.getInt(4) == 1) builder.append("; Secure")
                        if (c.getInt(5) == 1) builder.append("; HttpOnly")
                        val expires = c.getLong(6)
                        if (expires > 0) builder.append("; Expires=").append(httpDate(expires / 1000 - 11_644_473_600_000L))
                        cookies.setCookie("https://${host.trimStart('.')}$path", builder.toString())
                        copied++
                    }
                }
            }
            cookies.flush()
            return when {
                copied > 0 -> null
                encrypted > 0 -> "Chrome's cookies are encrypted on this phone, so they can't be copied."
                else -> "No Google sign-in found in Chrome. Sign in to gemini.google.com in Chrome first."
            }
        } catch (e: Exception) {
            return "Couldn't copy from Chrome: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun httpDate(epochMs: Long): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT") }
        .format(Date(epochMs))

    /** Checks the sign-in by loading Gemini in the hidden page. Blocking; not on the main thread. */
    fun verifySignIn(context: Context): Boolean {
        val app = context.applicationContext
        val latch = CountDownLatch(1)
        var ok = false
        main.post {
            val view = ensureWebView(app)
            onPageReady = {
                view.evaluateJavascript(
                    "(function(){var w=window.WIZ_global_data;return !!(w&&w.SNlM0e)&&location.host==='gemini.google.com';})()",
                ) { result -> ok = result == "true"; latch.countDown() }
            }
            pageReady = false
            pageLoadedAt = System.currentTimeMillis()
            view.loadUrl(HOME_URL)
        }
        latch.await(20, TimeUnit.SECONDS)
        return ok
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
