package com.tombo.billyassistant.companion.gemini

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Sign in to gemini.google.com once; the sign-in stays in Billy's private storage on this phone. */
class GeminiSignInActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var hint: TextView
    private lateinit var done: Button

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        CookieManager.getInstance().setAcceptCookie(true)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = GeminiAccountBridge.chromeUserAgent(this@GeminiSignInActivity)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) = check(auto = true)
            }
        }
        hint = TextView(this).apply {
            text = "Sign in with the Google account you use for Gemini."
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        done = Button(this).apply {
            text = "Done"
            isAllCaps = false
            setOnClickListener { check(auto = false) }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(32, 33, 36))
            setPadding(dp(16), dp(8), dp(8), dp(8))
            addView(hint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = android.view.Gravity.CENTER_VERTICAL })
            addView(done)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        webView.loadUrl(GeminiAccountBridge.SIGN_IN_URL)
    }

    private fun check(auto: Boolean) {
        webView.evaluateJavascript(
            "(function(){var w=window.WIZ_global_data;return !!(w&&w.SNlM0e)&&location.host==='gemini.google.com';})()",
        ) { result ->
            if (result == "true") {
                GeminiAccountBridge.markSignedIn(this)
                hint.text = "✓ Signed in. Tap Done."
                hint.setTypeface(null, Typeface.BOLD)
                if (!auto) finish()
            } else if (!auto) {
                hint.text = "Not signed in yet. Finish signing in until you see the Gemini chat screen."
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        webView.destroy()
        super.onDestroy()
    }
}
