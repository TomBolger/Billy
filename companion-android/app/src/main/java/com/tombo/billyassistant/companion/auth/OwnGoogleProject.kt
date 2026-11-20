package com.tombo.billyassistant.companion.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Google sign-in through the user's OWN Google Cloud project, so nobody has to
 * be added as a tester on the developer's project.
 *
 * Google allows only one Android sign-in registration per app package and
 * signing key, so a user's project uses a "Desktop app" OAuth client instead:
 * the consent page opens in the phone's browser and redirects to a one-time
 * listener on 127.0.0.1 inside Billy Companion (OAuth loopback flow with PKCE).
 * The refresh token stays on the phone; access tokens are renewed from it.
 */
object OwnGoogleProject {
    private const val PREFS = "billy_own_google_project"
    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo"
    private const val SIGN_IN_WAIT_MS = 10 * 60 * 1000

    sealed class Result {
        data class Ok(val email: String) : Result()
        data class Failed(val message: String) : Result()
    }

    // ---- stored settings ----------------------------------------------------------

    fun clientId(context: Context) = prefs(context).getString("client_id", "").orEmpty()
    fun clientSecret(context: Context) = prefs(context).getString("client_secret", "").orEmpty()
    fun email(context: Context) = prefs(context).getString("email", "").orEmpty()
    fun hasClient(context: Context) = clientId(context).isNotBlank() && clientSecret(context).isNotBlank()
    fun isConnected(context: Context) = prefs(context).getString("refresh_token", "").orEmpty().isNotBlank()

    /** Accepts the two values, or the whole JSON file Google offers to download. */
    fun saveClient(context: Context, idOrJson: String, secret: String): String? {
        var id = idOrJson.trim()
        var sec = secret.trim()
        if (id.startsWith("{")) {
            val json = runCatching { JSONObject(id) }.getOrNull() ?: return "That doesn't look like Google's client file."
            val block = json.optJSONObject("installed") ?: json.optJSONObject("web")
                ?: return "That file has no client in it."
            if (json.has("web")) return "That client is a \"Web application\". Create a \"Desktop app\" client instead (step 5)."
            id = block.optString("client_id")
            sec = block.optString("client_secret")
        }
        if (!id.endsWith(".apps.googleusercontent.com")) return "The Client ID should end with .apps.googleusercontent.com."
        if (sec.isBlank()) return "Paste the Client secret too."
        prefs(context).edit().putString("client_id", id).putString("client_secret", sec).apply()
        return null
    }

    fun disconnect(context: Context) {
        val refresh = prefs(context).getString("refresh_token", "").orEmpty()
        prefs(context).edit().remove("refresh_token").remove("access_token").remove("expires_at").remove("email").apply()
        GoogleAuthStore(context).clear()
        if (refresh.isNotBlank()) {
            Thread { runCatching { post("https://oauth2.googleapis.com/revoke", mapOf("token" to refresh)) } }.start()
        }
    }

    fun forgetClient(context: Context) {
        disconnect(context)
        prefs(context).edit().clear().apply()
    }

    // ---- sign-in ----------------------------------------------------------------------

    /**
     * Opens Google's consent page in the browser and waits for the redirect.
     * Blocking: run off the main thread. [scopes] are requested all at once.
     */
    fun signIn(context: Context, scopes: Collection<String>, returnTo: Class<*>? = null): Result {
        val id = clientId(context)
        val secret = clientSecret(context)
        if (id.isBlank() || secret.isBlank()) return Result.Failed("Add your Client ID and secret first.")
        val verifier = randomToken(48)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val state = randomToken(16)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = SIGN_IN_WAIT_MS
            val redirect = "http://127.0.0.1:${server.localPort}"
            val url = AUTH_URL + "?" + form(
                mapOf(
                    "client_id" to id,
                    "redirect_uri" to redirect,
                    "response_type" to "code",
                    "scope" to scopes.joinToString(" "),
                    "code_challenge" to challenge,
                    "code_challenge_method" to "S256",
                    "access_type" to "offline",
                    "prompt" to "consent",
                    "include_granted_scopes" to "true",
                    "state" to state,
                ),
            )
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

            val query = try {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val requestLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
                    val query = requestLine.substringAfter("?", "").substringBefore(" ")
                    val params = parseQuery(query)
                    val ok = params["code"] != null && params["state"] == state
                    val back = "<p><a href='billy-companion://google-setup' style='display:inline-block;padding:14px 22px;" +
                        "background:#aaffff;color:#0f172a;border-radius:10px;text-decoration:none;font-weight:bold'>Back to Billy Companion</a></p>"
                    val page = if (ok) {
                        "<h2>&#10003; Billy is connected.</h2>$back"
                    } else {
                        "<h2>Billy wasn't connected.</h2><p>${html(explain(params["error"]))}</p>$back"
                    }
                    val body = "<html><head><meta name=viewport content='width=device-width'></head>" +
                        "<body style='font-family:sans-serif;padding:24px'>$page</body></html>"
                    socket.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n" +
                            "Content-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray(),
                    )
                    params
                }
            } catch (e: SocketTimeoutException) {
                return Result.Failed("Sign-in timed out. Try again.")
            }
            bringBack(context, returnTo)
            query["error"]?.let { return Result.Failed(explain(it)) }
            if (query["state"] != state) return Result.Failed("Sign-in reply didn't match. Try again.")
            val code = query["code"] ?: return Result.Failed("Google didn't send a sign-in code. Try again.")

            val token = runCatching {
                post(
                    TOKEN_URL,
                    mapOf(
                        "client_id" to id,
                        "client_secret" to secret,
                        "code" to code,
                        "code_verifier" to verifier,
                        "redirect_uri" to redirect,
                        "grant_type" to "authorization_code",
                    ),
                )
            }.getOrElse { return Result.Failed("Couldn't reach Google: ${it.message}") }
            if (token.has("error")) return Result.Failed(explain(token.optString("error"), token.optString("error_description")))
            val refresh = token.optString("refresh_token")
            if (refresh.isBlank()) return Result.Failed("Google didn't allow staying signed in. Disconnect and sign in again.")
            val granted = token.optString("scope").split(" ").filter { it.isNotBlank() }
            val access = token.optString("access_token")
            val email = runCatching { get(USERINFO_URL, access).optString("email") }.getOrDefault("")
            prefs(context).edit()
                .putString("refresh_token", refresh)
                .putString("access_token", access)
                .putLong("expires_at", System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000)
                .putString("email", email)
                .apply()
            GoogleAuthStore(context).apply { clear(); saveGrant(granted.normalizedScopes(), access) }
            return Result.Ok(email)
        }
    }

    // ---- tokens -------------------------------------------------------------------------

    /** A current access token, renewed when needed. Blocking. */
    fun accessToken(context: Context, required: Collection<String>): GoogleAccessTokenResult {
        if (!GoogleAuthStore(context).hasScopes(required.normalizedScopes())) {
            return GoogleAccessTokenResult.NeedsUserGrant(required.toList())
        }
        val p = prefs(context)
        val cached = p.getString("access_token", "").orEmpty()
        if (cached.isNotBlank() && p.getLong("expires_at", 0) - System.currentTimeMillis() > 120_000) {
            return GoogleAccessTokenResult.Authorized(cached)
        }
        val refresh = p.getString("refresh_token", "").orEmpty()
        if (refresh.isBlank()) return GoogleAccessTokenResult.NeedsUserGrant(required.toList())
        val token = runCatching {
            post(
                TOKEN_URL,
                mapOf(
                    "client_id" to clientId(context),
                    "client_secret" to clientSecret(context),
                    "refresh_token" to refresh,
                    "grant_type" to "refresh_token",
                ),
            )
        }.getOrElse { return GoogleAccessTokenResult.Failed("Couldn't reach Google: ${it.message}") }
        if (token.optString("error") == "invalid_grant") {
            // Revoked, expired (a project left in "Testing" expires sign-ins after 7 days), or password changed.
            disconnect(context)
            return GoogleAccessTokenResult.NeedsUserGrant(required.toList())
        }
        val access = token.optString("access_token")
        if (access.isBlank()) return GoogleAccessTokenResult.Failed(explain(token.optString("error"), token.optString("error_description")))
        p.edit()
            .putString("access_token", access)
            .putLong("expires_at", System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000)
            .apply()
        return GoogleAccessTokenResult.Authorized(access)
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Google reports granted scopes with short names for identity scopes. */
    private fun Collection<String>.normalizedScopes(): List<String> = map {
        when (it) {
            "https://www.googleapis.com/auth/userinfo.email" -> GoogleApiScopes.EMAIL
            "https://www.googleapis.com/auth/userinfo.profile" -> GoogleApiScopes.PROFILE
            else -> it
        }
    }

    /** Plain-language reasons for Google's error codes. */
    fun explain(error: String?, detail: String = ""): String = when (error) {
        null, "" -> "Sign-in was cancelled."
        "access_denied" -> "Sign-in was cancelled, or Google blocked it. If Google said the app is in testing, finish step 4 (Publish app) and try again."
        "invalid_client", "unauthorized_client" -> "Google didn't accept the Client ID or secret. Check step 5: it must be a \"Desktop app\" client from your Billy project."
        "redirect_uri_mismatch" -> "That client isn't a \"Desktop app\" client. Create a Desktop app client in step 5."
        "invalid_grant" -> "The sign-in expired. Try again."
        "org_internal" -> "That project only allows accounts from one organization. In step 3 choose External."
        else -> "Google said: $error${if (detail.isNotBlank()) " ($detail)" else ""}"
    }

    private fun bringBack(context: Context, returnTo: Class<*>?) {
        val intent = if (returnTo != null) Intent(context, returnTo) else context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
    }

    private fun post(url: String, fields: Map<String, String>): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        connection.outputStream.use { it.write(form(fields).toByteArray()) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", "http_${connection.responseCode}") }
    }

    private fun get(url: String, token: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    }

    private fun form(fields: Map<String, String>) =
        fields.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun parseQuery(query: String): Map<String, String> = query.split("&")
        .filter { it.contains("=") }
        .associate { URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }

    private fun html(text: String) = text.replace("&", "&amp;").replace("<", "&lt;")

    private fun randomToken(bytes: Int): String {
        val data = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
