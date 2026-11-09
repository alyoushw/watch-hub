package com.alyoushw.watchhub.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.alyoushw.watchhub.ui.Gold
import com.alyoushw.watchhub.ui.WatchHubTheme
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * YouTube itself (its TV interface, youtube.com/tv): the user signs in there with their own
 * account (a code at youtube.com/activate), so subscriptions, history and Premium are all
 * YouTube's. On the left, Watch Hub's picture tools change how the video fills the screen
 * (handy on wide car screens where pinching does not work). Nothing here touches the stream:
 * it only scales the picture YouTube's own player shows.
 */
class YouTubeTvActivity : ComponentActivity() {
    private var web: WebView? = null
    private var railToggle: (() -> Unit)? = null
    private var railOpen: () -> Boolean = { false }
    private var closeRail: () -> Unit = {}
    private var lastBack = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val prefs = getSharedPreferences("youtube", Context.MODE_PRIVATE)

        val view = WebView(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = Interfaces[prefs.getInt("ui", 0).coerceIn(0, Interfaces.lastIndex)].second
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                // YouTube's own "exit" closes the window: leave with it.
                override fun onCloseWindow(window: WebView?) = finish()
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript(PictureScript, null)
                }
            }
            isFocusable = true
            isFocusableInTouchMode = true
        }
        web = view
        view.loadUrl(Interfaces[prefs.getInt("ui", 0).coerceIn(0, Interfaces.lastIndex)].third)

        setContent {
            WatchHubTheme(forceDark = true) {
                YouTubeTvScreen(
                    web = view,
                    onExit = ::finish,
                    onNextInterface = {
                        val next = (prefs.getInt("ui", 0) + 1) % Interfaces.size
                        prefs.edit().putInt("ui", next).apply()
                        view.settings.userAgentString = Interfaces[next].second
                        view.loadUrl(Interfaces[next].third)
                        Interfaces[next].first
                    },
                    bind = { toggle, isOpen, close ->
                        railToggle = toggle
                        railOpen = isOpen
                        closeRail = close
                    },
                )
            }
        }
        view.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val w = web ?: return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            // Picture tools: Menu (or the info / settings / red button) opens the rail.
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_GUIDE -> {
                if (event.action == KeyEvent.ACTION_DOWN) railToggle?.invoke()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                if (railOpen()) {
                    closeRail()
                    w.requestFocus()
                    return true
                }
                // Twice quickly leaves; once is YouTube's own "back" (Escape).
                val now = System.currentTimeMillis()
                if (now - lastBack < 600) {
                    finish()
                    return true
                }
                lastBack = now
                w.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
                w.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        web?.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onDestroy() {
        web?.apply {
            loadUrl("about:blank")
            destroy()
        }
        web = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, YouTubeTvActivity::class.java))

        /** YouTube interfaces to try, as (name, browser id, address): the TV one first. */
        val Interfaces = listOf(
            Triple(
                "واجهة التلفاز",
                "Mozilla/5.0 (Linux; Tizen 6.5) AppleWebKit/537.36 (KHTML, like Gecko) 85.0.4183.93/6.5 TV Safari/537.36 SMART-TV",
                "https://www.youtube.com/tv",
            ),
            Triple(
                "واجهة التلفاز ٢",
                "Mozilla/5.0 (PS4; Leanback Shell) Gecko/20100101 Firefox/65.0 LeanbackShell/01.00.01.75 Sony PS4/ (PS4, , no, CH)",
                "https://www.youtube.com/tv",
            ),
            Triple(
                "واجهة الجوال",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                "https://m.youtube.com",
            ),
        )

        /**
         * Installed in the page: `__wh.set({mode, ratio, zoom, widen})` scales the playing
         * <video> (mode: orig | cover | stretch | ratio), re-applied while YouTube swaps videos.
         */
        private val PictureScript = """
            (function(){
              if (window.__wh) { window.__wh.apply(); return; }
              var s = {mode:'orig', ratio:0, zoom:1, widen:1};
              function apply(){
                var vs = document.querySelectorAll('video');
                for (var i = 0; i < vs.length; i++) {
                  var v = vs[i], cw = v.clientWidth, ch = v.clientHeight;
                  if (!cw || !ch) continue;
                  var W = innerWidth, H = innerHeight, sx = 1, sy = 1;
                  if (s.mode === 'cover') { sx = sy = Math.max(W / cw, H / ch); }
                  else if (s.mode === 'stretch') { sx = W / cw; sy = H / ch; }
                  else if (s.mode === 'ratio' && s.ratio > 0) { var h = Math.min(H, W / s.ratio); sx = h * s.ratio / cw; sy = h / ch; }
                  sx *= s.zoom * s.widen; sy *= s.zoom;
                  var t = (sx === 1 && sy === 1) ? '' : 'scale(' + sx + ',' + sy + ')';
                  if (v.style.transform !== t) { v.style.transformOrigin = 'center center'; v.style.transform = t; }
                }
              }
              window.__wh = { set: function(o){ for (var k in o) s[k] = o[k]; apply(); }, apply: apply };
              setInterval(apply, 800);
            })();
        """.trimIndent()
    }
}

/** A picture choice on the rail. */
private data class Fit(val label: String, val mode: String, val ratio: Float = 0f)

private val Fits = listOf(
    Fit("الأصل", "orig"),
    Fit("ملء الشاشة", "cover"),
    Fit("تمديد", "stretch"),
    Fit("21:9", "ratio", 21f / 9f),
    Fit("16:9", "ratio", 16f / 9f),
    Fit("4:3", "ratio", 4f / 3f),
)

@Composable
private fun YouTubeTvScreen(
    web: WebView,
    onExit: () -> Unit,
    onNextInterface: () -> String,
    bind: (toggle: () -> Unit, isOpen: () -> Boolean, close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var fit by remember { mutableStateOf(Fits.first()) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var widen by remember { mutableFloatStateOf(1f) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    var note by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }

    fun push() {
        web.evaluateJavascript(
            "window.__wh && __wh.set({mode:'${fit.mode}',ratio:${fit.ratio},zoom:$zoom,widen:$widen})",
            null,
        )
        touchedAt = System.currentTimeMillis()
    }
    bind({ open = !open; touchedAt = System.currentTimeMillis() }, { open }, { open = false })

    // The rail hides by itself after a few seconds untouched.
    LaunchedEffect(open, touchedAt) {
        if (open) {
            runCatching { firstFocus.requestFocus() }
            delay(6_000)
            open = false
            web.requestFocus()
        }
    }
    LaunchedEffect(note) {
        if (note != null) {
            delay(2_500)
            note = null
        }
    }
    val changed = fit != Fits.first() || zoom != 1f || widen != 1f

    // Laid out left to right: the rail sits on the left whatever the language.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())

            // A small handle on the left edge opens the tools (touch screens).
            if (!open) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp)
                        .size(width = 34.dp, height = 64.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = if (changed) 0.6f else 0.35f))
                        .border(1.dp, if (changed) Gold else Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                        .clickable { open = true; touchedAt = System.currentTimeMillis() },
                    contentAlignment = Alignment.Center,
                ) { Text("⛶", color = if (changed) Gold else Color.White, fontSize = 18.sp) }
            }

            AnimatedVisibility(
                open,
                enter = slideInHorizontally { -it } + fadeIn(),
                exit = slideOutHorizontally { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .width(132.dp)
                        .background(Color(0xE6141210))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        listOf(fit.label, if (zoom != 1f) "${Math.round(zoom * 100)}%" else null, if (widen != 1f) "عرض ${Math.round(widen * 100)}%" else null)
                            .filterNotNull().joinToString(" · "),
                        color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    RailButton("↺ الأصل", selected = !changed, modifier = Modifier.focusRequester(firstFocus)) {
                        fit = Fits.first()
                        zoom = 1f
                        widen = 1f
                        push()
                    }
                    Fits.drop(1).forEach { f ->
                        RailButton(f.label, selected = fit == f) {
                            fit = f
                            push()
                        }
                    }
                    RailButton("تكبير +") { zoom = (zoom + 0.05f).coerceAtMost(3f).round(); push() }
                    RailButton("تصغير −") { zoom = (zoom - 0.05f).coerceAtLeast(0.5f).round(); push() }
                    RailButton("أعرض ↔") { widen = (widen + 0.05f).coerceAtMost(2f).round(); push() }
                    RailButton("أضيق →←") { widen = (widen - 0.05f).coerceAtLeast(0.5f).round(); push() }
                    RailButton("واجهة ثانية") { note = "فتحنا ${onNextInterface()}" }
                    RailButton("خروج", danger = true, onClick = onExit)
                }
            }

            note?.let {
                Text(
                    it,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp).clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun Float.round(): Float = String.format(Locale.US, "%.2f", this).toFloat()

@Composable
private fun RailButton(text: String, selected: Boolean = false, danger: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text,
        color = when {
            focused -> Color.Black
            selected -> Gold
            danger -> Color(0xFFFF6B6B)
            else -> Color.White
        },
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Gold else if (selected) Gold.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    )
}
