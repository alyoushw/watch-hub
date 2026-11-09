package com.alyoushw.watchhub.player

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.alyoushw.watchhub.WatchHubApp

/**
 * YouTube's official player, full screen, with Watch Hub's picture controls (zoom, width,
 * shapes): the user's site page /yt.html in a WebView. Remote keys: OK play/pause, left/right
 * ±10 s, up/down the buttons, Menu the picture panel, Back closes the panel or the player.
 */
class YouTubePlayerActivity : ComponentActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        if (id.isNullOrBlank()) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val site = (application as WatchHubApp).container.settings.webUrl.value.trimEnd('/')
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                // Links out of the page (YouTube logo, channel name) open in the YouTube app.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.host?.endsWith(Uri.parse(site).host.orEmpty()) == true) return false
                    openExternal(url)
                    return true
                }
            }
            addJavascriptInterface(Bridge(), "Android")
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(web)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        web.loadUrl("$site/yt.html?v=${Uri.encode(id)}&title=${Uri.encode(title)}")
        web.requestFocus()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun close() = runOnUiThread { finish() }

        @JavascriptInterface
        fun openInYouTube(id: String, seconds: Int) = runOnUiThread {
            openExternal(Uri.parse("https://www.youtube.com/watch?v=${Uri.encode(id)}&t=${seconds}s"))
        }

        @JavascriptInterface
        fun ended() = Unit
    }

    private fun openExternal(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(intent.setPackage("com.google.android.youtube"))
        } catch (_: ActivityNotFoundException) {
            try {
                // Android TV's YouTube app has its own package.
                startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.youtube.tv"))
            } catch (_: ActivityNotFoundException) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::web.isInitialized) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    // The page closes its picture panel first; otherwise the player closes.
                    web.evaluateJavascript("window.whKey ? whKey('back') : false") { handled ->
                        if (handled != "true") finish()
                    }
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    web.evaluateJavascript("window.whKey && whKey('menu')", null)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    web.evaluateJavascript("window.dispatchEvent(new KeyboardEvent('keydown',{key:' '}))", null)
                    return true
                }
            }
        } else if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        if (::web.isInitialized) web.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.loadUrl("about:blank")
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, videoId: String, title: String?) {
            context.startActivity(
                Intent(context, YouTubePlayerActivity::class.java).putExtra(EXTRA_ID, videoId).putExtra(EXTRA_TITLE, title),
            )
        }
    }
}
