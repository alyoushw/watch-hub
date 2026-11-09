package com.alyoushw.watchhub.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
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
 * YouTube itself inside the app, with Watch Hub's tools on the left: back, home, and picture
 * size. The default is YouTube's regular site (touch, seeking, the list beside the video); the
 * TV interface is there too, for signing in with a code. The picture tools scale only the playing
 * video (in the TV interface, which is drawn for 16:9, they scale its 16:9 frame).
 */
class YouTubeTvActivity : ComponentActivity() {
    private var web: WebView? = null
    private var host: FrameLayout? = null
    private var fullscreenView: View? = null
    private var fullscreenDone: WebChromeClient.CustomViewCallback? = null
    private val ui: MutableIntState = mutableIntStateOf(0)
    private var railToggle: (() -> Unit)? = null
    private var railOpen: () -> Boolean = { false }
    private var closeRail: () -> Unit = {}
    private var lastBack = 0L

    private val tvInterface get() = Interfaces[ui.intValue].url.endsWith("/tv")

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
        ui.intValue = prefs.getInt("ui2", 0).coerceIn(0, Interfaces.lastIndex)

        val frame = FrameLayout(this).apply {
            clipChildren = false
            setBackgroundColor(AndroidColor.BLACK)
        }
        host = frame
        val view = WebView(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(false)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                // YouTube's own "exit" closes the window: leave with it.
                override fun onCloseWindow(window: WebView?) = finish()

                // YouTube's full-screen button: its player fills the screen, over everything.
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    fullscreenView?.let { frame.removeView(it) }
                    fullscreenView = view
                    fullscreenDone = callback
                    frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }

                override fun onHideCustomView() = leaveFullscreen()
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
        frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        load(view)

        setContent {
            WatchHubTheme(forceDark = true) {
                YouTubeScreen(
                    web = view,
                    frame = frame,
                    tvInterface = tvInterface,
                    interfaceName = Interfaces[ui.intValue].name,
                    onBack = ::goBack,
                    onHome = ::finish,
                    onInterface = {
                        val next = (ui.intValue + 1) % Interfaces.size
                        prefs.edit().putInt("ui2", next).apply()
                        ui.intValue = next
                        load(view)
                        Interfaces[next].name
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

    private fun load(view: WebView) {
        val i = Interfaces[ui.intValue]
        view.settings.userAgentString = i.agent
        view.loadUrl(i.url)
    }

    private fun leaveFullscreen() {
        fullscreenView?.let { host?.removeView(it) }
        fullscreenView = null
        fullscreenDone?.onCustomViewHidden()
        fullscreenDone = null
    }

    /** One step back: out of full screen, then back in YouTube, then out of YouTube. */
    private fun goBack() {
        val w = web ?: return finish()
        when {
            fullscreenView != null -> {
                w.evaluateJavascript("document.exitFullscreen && document.exitFullscreen()", null)
                leaveFullscreen()
            }
            tvInterface -> {
                w.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
                w.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
            }
            w.canGoBack() -> w.goBack()
            else -> finish()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (web == null) return super.dispatchKeyEvent(event)
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
                    web?.requestFocus()
                    return true
                }
                // Twice quickly leaves YouTube.
                val now = System.currentTimeMillis()
                if (now - lastBack < 500) {
                    finish()
                    return true
                }
                lastBack = now
                goBack()
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

    private class Interface(val name: String, val agent: String, val url: String)

    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, YouTubeTvActivity::class.java))

        /** YouTube's interfaces, as (name, browser id, address): the regular site first. */
        private val Interfaces = listOf(
            Interface(
                "واجهة يوتيوب العادية",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "https://www.youtube.com",
            ),
            Interface(
                "واجهة التلفاز",
                "Mozilla/5.0 (Linux; Tizen 6.5) AppleWebKit/537.36 (KHTML, like Gecko) 85.0.4183.93/6.5 TV Safari/537.36 SMART-TV",
                "https://www.youtube.com/tv",
            ),
            Interface(
                "واجهة الجوال",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                "https://m.youtube.com",
            ),
        )

        /**
         * Installed in the page: `__wh.set({mode, ratio, zoom, widen})` scales only the playing
         * <video> inside YouTube's player (mode: orig | cover | stretch | ratio), re-applied while
         * YouTube swaps videos. The player box clips it, so in full screen it fills the screen.
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
                  var box = v.closest('.html5-video-player') || v.parentElement || document.body;
                  var W = box.clientWidth || innerWidth, H = box.clientHeight || innerHeight;
                  var vw = v.videoWidth || cw, vh = v.videoHeight || ch;
                  // What YouTube shows now: the video fitted inside its element.
                  var f = Math.min(cw / vw, ch / vh), pw = vw * f, ph = vh * f, sx = 1, sy = 1;
                  if (s.mode === 'cover') { sx = sy = Math.max(W / pw, H / ph); }
                  else if (s.mode === 'stretch') { sx = W / pw; sy = H / ph; }
                  else if (s.mode === 'ratio' && s.ratio > 0) { var h = Math.min(H, W / s.ratio); sx = h * s.ratio / pw; sy = h / ph; }
                  sx *= s.zoom * s.widen; sy *= s.zoom;
                  var t = (Math.abs(sx - 1) < 0.001 && Math.abs(sy - 1) < 0.001) ? '' : 'scale(' + sx + ',' + sy + ')';
                  if (v.style.transform !== t) { v.style.transformOrigin = 'center center'; v.style.transform = t; }
                }
              }
              window.__wh = { set: function(o){ for (var k in o) s[k] = o[k]; apply(); }, apply: apply };
              setInterval(apply, 700);
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
private fun YouTubeScreen(
    web: WebView,
    frame: FrameLayout,
    tvInterface: Boolean,
    interfaceName: String,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onInterface: () -> String,
    bind: (toggle: () -> Unit, isOpen: () -> Boolean, close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var fit by remember { mutableStateOf(Fits.first()) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var widen by remember { mutableFloatStateOf(1f) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    var note by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf(IntSize.Zero) }
    val firstFocus = remember { FocusRequester() }
    bind({ open = !open; touchedAt = System.currentTimeMillis() }, { open }, { open = false })

    // TV interface: a 16:9 frame (it is drawn for 16:9), scaled by the tools. Other interfaces:
    // the whole screen, and the tools scale only the video inside YouTube's player.
    LaunchedEffect(screen, fit, zoom, widen, tvInterface) {
        val w = screen.width.toFloat()
        val h = screen.height.toFloat()
        if (w <= 0 || h <= 0) return@LaunchedEffect
        val lp = web.layoutParams as FrameLayout.LayoutParams
        if (tvInterface) {
            val bw = minOf(w, h * 16f / 9f)
            val bh = bw * 9f / 16f
            var sx = 1f
            var sy = 1f
            when (fit.mode) {
                "cover" -> { sx = maxOf(w / bw, h / bh); sy = sx }
                "stretch" -> { sx = w / bw; sy = h / bh }
                "ratio" -> {
                    val th = minOf(h, w / fit.ratio)
                    sx = th * fit.ratio / bw
                    sy = th / bh
                }
            }
            if (lp.width != bw.toInt() || lp.height != bh.toInt()) {
                lp.width = bw.toInt()
                lp.height = bh.toInt()
                web.layoutParams = lp
            }
            web.pivotX = bw / 2f
            web.pivotY = bh / 2f
            web.scaleX = sx * zoom * widen
            web.scaleY = sy * zoom
        } else {
            if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT || lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                web.layoutParams = lp
            }
            web.scaleX = 1f
            web.scaleY = 1f
            web.evaluateJavascript(
                "window.__wh && __wh.set({mode:'${fit.mode}',ratio:${fit.ratio},zoom:$zoom,widen:$widen})",
                null,
            )
        }
    }

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
    fun touched() {
        touchedAt = System.currentTimeMillis()
    }

    // Laid out left to right: the tools sit on the left whatever the language.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { frame }, modifier = Modifier.fillMaxSize().onSizeChanged { screen = it })

            // Always on the left edge: back, home and the picture tools (screens without buttons).
            if (!open) {
                Column(
                    Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EdgeButton("→", "رجوع", onClick = onBack)
                    EdgeButton("⌂", "الرئيسية", onClick = onHome)
                    EdgeButton("⛶", "حجم الصورة", highlight = changed) {
                        open = true
                        touched()
                    }
                }
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
                        .width(136.dp)
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
                        touched()
                    }
                    Fits.drop(1).forEach { f ->
                        RailButton(f.label, selected = fit == f) {
                            fit = f
                            touched()
                        }
                    }
                    RailButton("تكبير +") { zoom = (zoom + 0.05f).coerceAtMost(3f).round(); touched() }
                    RailButton("تصغير −") { zoom = (zoom - 0.05f).coerceAtLeast(0.5f).round(); touched() }
                    RailButton("أعرض ↔") { widen = (widen + 0.05f).coerceAtMost(2f).round(); touched() }
                    RailButton("أضيق →←") { widen = (widen - 0.05f).coerceAtLeast(0.5f).round(); touched() }
                    Text("الواجهة: $interfaceName", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    RailButton("واجهة ثانية") { note = "فتحنا ${onInterface()}" }
                    RailButton("إغلاق الأدوات") { open = false }
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

/** A small see-through button on the left edge. */
@Composable
private fun EdgeButton(symbol: String, description: String, highlight: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(width = 40.dp, height = 46.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Gold else Color.Black.copy(alpha = if (highlight) 0.65f else 0.45f))
            .border(1.dp, if (highlight) Gold else Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = if (focused) Color.Black else if (highlight) Gold else Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RailButton(text: String, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text,
        color = when {
            focused -> Color.Black
            selected -> Gold
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
