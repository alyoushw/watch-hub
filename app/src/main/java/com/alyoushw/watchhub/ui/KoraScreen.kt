package com.alyoushw.watchhub.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.alyoushw.watchhub.data.StreamProbe
import com.alyoushw.watchhub.data.WatchEntry
import com.alyoushw.watchhub.player.PlayerActivity
import com.alyoushw.watchhub.player.QueueItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The public sports website, including its own watch flow; no Majed APK is required. */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KoraScreen(homeUrl: String = KORA_HOME, pageTitle: String = "كوره", onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val probe = remember { StreamProbe() }
    val attemptedStreams = remember { mutableSetOf<KoraNativeStream>() }
    val pendingStreams = remember { linkedMapOf<KoraNativeStream, WebView>() }
    val windows = remember { mutableListOf<WebView>() }
    var web by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var fullscreen by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var nativeOpening by remember { mutableStateOf(false) }
    var nativeStarted by remember { mutableStateOf(false) }
    var nativePaused by remember { mutableStateOf(false) }
    var nativeJob by remember { mutableStateOf<Job?>(null) }
    val nativeEnabled = Uri.parse(homeUrl).host == Uri.parse(KORA_HOME).host

    fun closeFullscreen() {
        val callback = fullscreenCallback
        fullscreenCallback = null
        fullscreen?.let { (it.parent as? ViewGroup)?.removeView(it) }
        fullscreen = null
        callback?.onCustomViewHidden()
    }
    fun openBrowser(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme !in listOf("https", "http")) return
        try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: android.content.ActivityNotFoundException) { error = "لا يوجد متصفح لفتح الرابط على هذا الجهاز." }
        catch (_: SecurityException) { error = "تعذر فتح الرابط على هذا الجهاز." }
    }
    fun configure(view: WebView) {
        view.setBackgroundColor(android.graphics.Color.BLACK)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.setDownloadListener { url, _, _, _, _ -> openBrowser(url) }
    }
    fun activateWindow(window: WebView) {
        if (window !in windows || web == window) return
        closeFullscreen()
        web?.apply { onPause(); visibility = View.GONE }
        web = window
        window.apply { visibility = View.VISIBLE; onResume(); requestFocus() }
        canGoBack = window.canGoBack() || windows.size > 1
        loading = false
        error = null
    }
    fun closeWindow(window: WebView?) {
        if (window == null || window !in windows || window == windows.firstOrNull()) return
        val active = window == web
        if (active) closeFullscreen()
        windows.remove(window)
        (window.parent as? ViewGroup)?.removeView(window)
        window.stopLoading()
        window.destroy()
        if (active) {
            web = null
            activateWindow(windows.last())
        }
        canGoBack = web?.canGoBack() == true || windows.size > 1
    }
    fun back() {
        when {
            web?.canGoBack() == true -> web?.goBack()
            windows.size > 1 -> {
                if (web == windows.firstOrNull()) while (windows.size > 1) closeWindow(windows.last())
                else closeWindow(web)
            }
            else -> onBack()
        }
    }
    fun openNative(view: WebView, candidate: KoraNativeStream) {
        if (!nativeEnabled || view !in windows || view != web || nativeStarted || attemptedStreams.size >= 8 ||
            candidate in attemptedStreams || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        attemptedStreams += candidate
        pendingStreams[candidate] = view
        if (nativeOpening) return
        nativeOpening = true
        nativeJob = scope.launch {
            try {
                while (pendingStreams.isNotEmpty()) {
                    val next = pendingStreams.entries.first()
                    val queued = next.key
                    val sourceView = next.value
                    pendingStreams.remove(next.key)
                    if (sourceView != web || sourceView !in windows) continue
                    val headers = queued.headers.toMutableMap()
                    if (headers.keys.none { it.equals("User-Agent", true) }) headers["User-Agent"] = sourceView.settings.userAgentString
                    if (headers.keys.none { it.equals("Cookie", true) }) {
                        CookieManager.getInstance().getCookie(queued.url)?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
                    }
                    val stream = queued.copy(headers = headers.toMap())
                    if (!probe.compatible(stream.variant())) {
                        error = "تعذر التحقق من رابط البث الحالي؛ جارٍ انتظار رابط آخر من مشغّل الموقع."
                        continue
                    }
                    if (sourceView != web || sourceView !in windows || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) continue
                    val title = suspendCancellableCoroutine<String> { continuation ->
                        sourceView.evaluateJavascript("""(function(){var h=document.getElementById('mkHomeName'),a=document.getElementById('mkAwayName');return h&&a?h.textContent+' × '+a.textContent:'كوره · بث مباشر';})()""") { encoded ->
                            val value = runCatching { org.json.JSONTokener(encoded).nextValue() as? String }.getOrNull()
                                ?.take(160)?.takeIf { it.isNotBlank() } ?: "كوره · بث مباشر"
                            if (continuation.isActive) continuation.resume(value)
                        }
                    }
                    if (sourceView != web || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) continue
                    val item = QueueItem(listOf(stream.url), WatchEntry(
                        key = "kora-session:${java.util.UUID.randomUUID()}", kind = WatchEntry.LIVE,
                        itemId = "web-live", title = title, accountId = "kora-web"),
                        mimeType = stream.mimeType, httpHeaders = stream.headers, recordHistory = false)
                    // Remove the web player before starting Media3 so two audio streams cannot overlap.
                    closeFullscreen()
                    while (windows.size > 1) closeWindow(windows.last())
                    nativePaused = false
                    nativeStarted = true
                    pendingStreams.clear()
                    error = null
                    web?.loadUrl(homeUrl)
                    PlayerActivity.start(context, listOf(item), 0)
                    return@launch
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                nativeStarted = false
                error = "تعذر فتح المشغّل الداخلي؛ أعد فتح البث للمحاولة مجدداً."
            } finally {
                if (nativeJob === coroutineContext[Job]) nativeOpening = false
            }
        }
    }
    fun observePlayer(view: WebView) {
        if (!nativeEnabled || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val origins = setOf("https://${KoraNativeStream.PLAYER_HOST}")
        WebViewCompat.addWebMessageListener(view, "watchHubKora", origins) { sourceView, message, origin, _, _ ->
            if (origin.scheme != "https" || origin.host != KoraNativeStream.PLAYER_HOST) return@addWebMessageListener
            val stream = runCatching {
                val text = message.data?.takeIf { it.length <= 8192 } ?: return@addWebMessageListener
                val data = org.json.JSONObject(text)
                KoraNativeStream.observed(data.getString("url"), data.getString("mime"), origin.toString())
            }.getOrNull() ?: return@addWebMessageListener
            openNative(sourceView, stream)
        }
        WebViewCompat.addDocumentStartJavaScript(view, KORA_MEDIA_OBSERVER, origins)
    }

    BackHandler(enabled = canGoBack && fullscreen == null) { back() }
    DisposableEffect(lifecycle, web) {
        val view = web
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    view?.onResume()
                    if (nativeStarted && nativePaused) { nativeStarted = false; nativePaused = false; nativeOpening = false }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    view?.onPause()
                    if (nativeStarted) nativePaused = true
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            nativeJob?.cancel()
            pendingStreams.clear()
            closeFullscreen()
            val remaining = windows.toList()
            windows.clear()
            remaining.asReversed().forEach { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                view.apply { stopLoading(); removeAllViews(); destroy() }
            }
        }
    }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        TopAppBar(title = { Text(pageTitle, maxLines = 1) }, navigationIcon = {
            IconButton(onClick = ::back, modifier = Modifier.focusFrame()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
            }
        }, actions = {
            if (web != windows.firstOrNull() && windows.size > 1) TextButton(onClick = { closeWindow(web) }, modifier = Modifier.focusFrame()) { Text("إغلاق النافذة") }
            TextButton(onClick = {
                while (windows.size > 1) closeWindow(windows.last())
                web?.loadUrl(homeUrl)
            }, modifier = Modifier.focusFrame()) { Text("الرئيسية") }
            TextButton(onClick = { error = null; web?.reload() }, modifier = Modifier.focusFrame()) { Text("تحديث") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loading || nativeOpening && !nativeStarted) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (nativeOpening && !nativeStarted) Text("جاري فتح مشغّل Watch Hub…", modifier = Modifier.padding(12.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { host ->
                val site = WebView(host).apply {
                    configure(this)
                    observePlayer(this)
                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                            val hostView = view.parent as? ViewGroup ?: return false
                            if (!isUserGesture || windows.size >= 4) return false
                            val popup = WebView(view.context).apply {
                                configure(this)
                                observePlayer(this)
                                webViewClient = view.webViewClient
                                webChromeClient = view.webChromeClient
                            }
                            hostView.addView(popup, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                            windows += popup
                            activateWindow(popup)
                            loading = true
                            error = null
                            (resultMsg.obj as WebView.WebViewTransport).webView = popup
                            resultMsg.sendToTarget()
                            return true
                        }
                        override fun onCloseWindow(window: WebView?) = closeWindow(window)
                        override fun onRequestFocus(view: WebView) = activateWindow(view)
                        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                            if (fullscreen != null) { callback.onCustomViewHidden(); return }
                            fullscreenCallback = callback
                            fullscreen = view
                        }
                        override fun onHideCustomView() = closeFullscreen()
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            if (view == web) {
                                loading = true
                                error = null
                                if (!nativeStarted) { nativeJob?.cancel(); nativeJob = null; nativeOpening = false }
                                attemptedStreams.clear()
                                pendingStreams.clear()
                            }
                        }
                        override fun onPageFinished(view: WebView, url: String?) {
                            if (view == web) {
                                loading = false
                                canGoBack = view.canGoBack() || windows.size > 1
                            }
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                            if (view == web && request.isForMainFrame) { loading = false; error = "تعذر تحميل الصفحة. تحقق من الاتصال ثم اضغط تحديث." }
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val scheme = request.url.scheme
                            if (scheme == "http" || scheme == "https") return false
                            if (view == web && request.isForMainFrame) error = "هذا الرابط يتطلب تطبيقاً خارجياً؛ يمكنك العودة للمباريات."
                            return true
                        }
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            KoraNativeStream.from(request)?.let { stream -> view.post { openNative(view, stream) } }
                            return null
                        }
                    }
                }
                FrameLayout(host).apply {
                    addView(site, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    windows += site
                    web = site
                    site.loadUrl(homeUrl)
                }
            })
        }
    }

    fullscreen?.let { video ->
        Dialog(onDismissRequest = ::closeFullscreen, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { host ->
                    FrameLayout(host).apply {
                        (video.parent as? ViewGroup)?.removeView(video)
                        addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                })
                TextButton(onClick = ::closeFullscreen, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).focusFrame()) {
                    Text("إغلاق ملء الشاشة", color = Gold)
                }
            }
        }
    }
}

private const val KORA_HOME = "https://majed-koora.live/"

// Observe successful media responses after the website completes its own access flow.
// Runs only in the HTTPS player origin, including its iframe; other sites/ad frames have no bridge.
private val KORA_MEDIA_OBSERVER = """
    (function(){
      if(window.__watchHubMediaObserver)return;
      window.__watchHubMediaObserver=true;
      function report(url,mime,status){
        if(status<200||status>=300||!url||!mime)return;
        if(!/^(application\/(vnd\.apple\.mpegurl|x-mpegurl)|audio\/(x-)?mpegurl|video\/(mp4|x-matroska))(;|${'$'})/i.test(mime))return;
        try{window.watchHubKora.postMessage(JSON.stringify({url:url,mime:mime}));}catch(_){}
      }
      var originalFetch=window.fetch;
      if(originalFetch)window.fetch=function(){return originalFetch.apply(this,arguments).then(function(response){
        report(response.url,response.headers.get('content-type'),response.status);return response;
      });};
      var originalSend=XMLHttpRequest.prototype.send;
      XMLHttpRequest.prototype.send=function(){
        this.addEventListener('load',function(){report(this.responseURL,this.getResponseHeader('content-type'),this.status);});
        return originalSend.apply(this,arguments);
      };
    })();
""".trimIndent()
