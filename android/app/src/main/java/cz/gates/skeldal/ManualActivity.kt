package cz.gates.skeldal

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The walkthrough (návod + tips, from ceskehry.net with the owners' permission) shown as a page
 * from the app's assets (assets/navod/index.html, built by tools/navod/build.py).
 * Opened from the launcher and over the game (H key, NÁVOD touch button, gamepad action).
 * The reading position is remembered.
 */
class ManualActivity : Activity() {

    private lateinit var web: WebView
    private val prefs by lazy { getSharedPreferences("manual", Context.MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#FF2A2927"))
            settings.javaScriptEnabled = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.allowFileAccess = false   // assets are still allowed (file:///android_asset)
            isFocusable = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (savedInstanceState != null) return
                    // back to where the reader was (fraction of the page height)
                    val f = prefs.getFloat(KEY_POS, 0f)
                    if (f > 0f) view.postDelayed({
                        val h = (view.contentHeight * view.scale).toInt()
                        view.scrollTo(0, (h * f).toInt())
                    }, 150)
                }
            }
        }
        setContentView(web)
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(PAGE)
        web.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        val h = web.contentHeight * web.scale
        if (h > 0) prefs.edit().putFloat(KEY_POS, (web.scrollY / h).coerceIn(0f, 1f)).apply()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // links inside the page (table of contents) are anchors: back closes the manual
        finish()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val PAGE = "file:///android_asset/navod/index.html"
        private const val KEY_POS = "scroll"

        fun open(context: Context) {
            context.startActivity(Intent(context, ManualActivity::class.java))
        }
    }
}
