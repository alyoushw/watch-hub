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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
fun KoraScreen(
    homeUrl: String = KORA_HOME, pageTitle: String = "كوره", onBack: () -> Unit,
    /** The «كوره» tab itself (its address can be changed in Settings). */
    kora: Boolean = false,
    /** Movie sites (TukTuk): collect every video the page plays and offer it to the app's player. */
    detectVideos: Boolean = false,
    /** With [detectVideos]: top-level pages must stay on hosts containing this (advert redirects are blocked). */
    siteKeyword: String? = null,
) {
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
    val nativeEnabled = kora || Uri.parse(homeUrl).host == Uri.parse(KORA_HOME).host
    val found = remember { androidx.compose.runtime.mutableStateListOf<KoraNativeStream>() }
    /** Watch servers and download pages read from the site's own page (label to address). */
    val servers = remember { androidx.compose.runtime.mutableStateListOf<WebLinkItem>() }
    var showServers by remember { mutableStateOf(false) }
    var trying by remember { mutableStateOf<String?>(null) }
    var tryJob by remember { mutableStateOf<Job?>(null) }
    /** Hosts the user (or the auto-try) chose to open; top-level pages may go there. */
    val chosenHosts = remember { mutableSetOf<String>() }
    var openedAt by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }

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
        // Every frame: the site moves its player to new addresses; KoraNativeStream.observed filters.
        val origins = setOf("*")
        WebViewCompat.addWebMessageListener(view, "watchHubKora", origins) { sourceView, message, origin, _, _ ->
            if (origin.scheme != "https") return@addWebMessageListener
            val stream = runCatching {
                val text = message.data?.takeIf { it.length <= 8192 } ?: return@addWebMessageListener
                val data = org.json.JSONObject(text)
                KoraNativeStream.observed(data.getString("url"), data.getString("mime"), origin.toString())
            }.getOrNull() ?: return@addWebMessageListener
            openNative(sourceView, stream)
        }
        WebViewCompat.addDocumentStartJavaScript(view, KORA_MEDIA_OBSERVER, origins)
    }

    /** Plays the best video the page has loaded so far (HLS first) in Watch Hub's own player. */
    fun playFound() {
        val view = web ?: return
        val pick = found.lastOrNull { it.transport == com.alyoushw.watchhub.data.SourceTransport.HLS } ?: found.lastOrNull() ?: return
        val headers = pick.headers.toMutableMap()
        if (headers.keys.none { it.equals("User-Agent", true) }) headers["User-Agent"] = view.settings.userAgentString
        if (headers.keys.none { it.equals("Cookie", true) }) {
            CookieManager.getInstance().getCookie(pick.url)?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        }
        val stream = pick.copy(headers = headers.toMap())
        val title = view.title?.takeIf { it.isNotBlank() && !it.startsWith("http") }?.take(160) ?: pageTitle
        nativeOpening = true
        scope.launch {
            try {
                if (!probe.compatible(stream.variant())) {
                    found.remove(pick)
                    error = "هذا الرابط ما اشتغل (قد يكون إعلاناً أو انتهت صلاحيته). شغّل الفيديو في الموقع ثم جرّب مرة ثانية."
                    return@launch
                }
                error = null
                val item = QueueItem(listOf(stream.url), WatchEntry(
                    key = "web-session:${java.util.UUID.randomUUID()}", kind = WatchEntry.MOVIE,
                    itemId = "web-video", title = title, accountId = "web"),
                    mimeType = stream.mimeType, httpHeaders = stream.headers, recordHistory = false)
                closeFullscreen()
                view.onPause()
                PlayerActivity.start(context, listOf(item), 0)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "تعذر فتح المشغّل الداخلي." }
            finally { nativeOpening = false }
        }
    }

    fun addFound(stream: KoraNativeStream) {
        if (found.none { it.url == stream.url }) {
            found += stream
            if (found.size > 12) found.removeAt(0)
        }
    }

    /** Reads the open page: its server list and download links, plus any video its player has set up. */
    fun scan(view: WebView, keepList: Boolean) {
        view.evaluateJavascript(WEB_SCAN) { encoded ->
            val text = runCatching { org.json.JSONTokener(encoded).nextValue() as? String }.getOrNull() ?: return@evaluateJavascript
            val data = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return@evaluateJavascript
            val page = data.optString("page")
            val media = data.optJSONArray("media")
            for (i in 0 until (media?.length() ?: 0)) KoraNativeStream.fromPage(media!!.getString(i), page)?.let(::addFound)
            val links = data.optJSONArray("links") ?: return@evaluateJavascript
            val list = (0 until links.length()).mapNotNull { i ->
                val o = links.getJSONObject(i)
                WebLinkItem(o.optString("l").ifBlank { Uri.parse(o.optString("u")).host ?: "سيرفر" }, o.optString("u"), o.optString("k") == "download")
            }.filter { it.url.startsWith("http") }
            // Keep the title page's list while a server page is open.
            if (list.any { !it.download } || !keepList && list.isNotEmpty()) {
                servers.clear(); servers += list.distinctBy { it.url }.take(40)
            }
        }
    }

    fun openServer(link: WebLinkItem) {
        val view = web ?: return
        Uri.parse(link.url).host?.let { chosenHosts += it }
        openedAt = android.os.SystemClock.elapsedRealtime()
        error = null
        view.loadUrl(link.url)
    }

    /** Opens each watch server in turn and plays the first one whose video really works. */
    fun autoTry() {
        val list = servers.filter { !it.download }
        if (list.isEmpty()) { error = "ما لقينا سيرفرات في هذي الصفحة. افتح صفحة الفيلم أو الحلقة أول."; return }
        tryJob?.cancel()
        tryJob = scope.launch {
            try {
                for ((i, link) in list.withIndex()) {
                    trying = "نجرّب السيرفر ${i + 1} من ${list.size}: ${link.label}"
                    found.clear()
                    openServer(link)
                    var waited = 0
                    while (waited < 15_000 && found.isEmpty()) {
                        kotlinx.coroutines.delay(1500); waited += 1500
                        web?.let { scan(it, keepList = true) }
                    }
                    val pick = found.lastOrNull { it.transport == com.alyoushw.watchhub.data.SourceTransport.HLS } ?: found.lastOrNull() ?: continue
                    if (probe.compatible(pick.variant())) {
                        trying = null
                        playFound()
                        return@launch
                    }
                }
                error = "ولا سيرفر اشتغل تلقائياً. افتح القائمة واختر سيرفر، واضغط تشغيل داخل الموقع ثم زر مشغّل Watch Hub."
            } finally { trying = null }
        }
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
            if (detectVideos) {
                trying?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Gold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
                if (servers.isNotEmpty()) androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = { if (trying == null) autoTry() else { tryJob?.cancel(); trying = null } },
                        modifier = Modifier.weight(1f).focusFrame()) { Text(if (trying == null) "🔁 جرّب السيرفرات تلقائياً" else "إيقاف التجربة") }
                    androidx.compose.material3.OutlinedButton(onClick = { showServers = true }, modifier = Modifier.weight(1f).focusFrame()) {
                        Text("السيرفرات والتحميل (${servers.size})")
                    }
                }
                if (found.isEmpty()) Text("افتح الفيلم أو الحلقة واختر سيرفراً وشغّله؛ أول ما يبدأ الفيديو يطلع لك زر التشغيل بمشغّل التطبيق.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                else androidx.compose.material3.Button(onClick = ::playFound, enabled = !nativeOpening,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).focusFrame()) {
                    Text("▶ شغّل الفيديو بمشغّل Watch Hub")
                }
            }
            AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { host ->
                val site = WebView(host).apply {
                    configure(this)
                    observePlayer(this)
                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                            val hostView = view.parent as? ViewGroup ?: return false
                            // Movie sites open advert tabs on almost every tap: no new windows there.
                            if (detectVideos || !isUserGesture || windows.size >= 4) return false
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
                            if (detectVideos && view == web) scan(view, keepList = Uri.parse(url ?: "").host?.contains(siteKeyword ?: "\u0000", true) != true)
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
                            val host = request.url.host.orEmpty()
                            val chosen = host in chosenHosts ||
                                request.isRedirect && android.os.SystemClock.elapsedRealtime() - openedAt < 8_000
                            if (chosen) request.url.host?.let { chosenHosts += it }
                            if (detectVideos && request.isForMainFrame && (scheme == "http" || scheme == "https") && siteKeyword != null &&
                                !chosen && !host.contains(siteKeyword, ignoreCase = true)) {
                                error = "منعنا تحويلاً لصفحة إعلان."
                                return true
                            }
                            if (scheme == "http" || scheme == "https") return false
                            if (view == web && request.isForMainFrame) error = "هذا الرابط يتطلب تطبيقاً خارجياً؛ يمكنك العودة للمباريات."
                            return true
                        }
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            // Kora tab: advert networks get an empty answer, so their videos never show.
                            if (kora && KoraNativeStream.isAd(request)) {
                                return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                            }
                            (KoraNativeStream.from(request) ?: if (kora) KoraNativeStream.liveFromAnyPlayer(request) else null)
                                ?.let { stream -> view.post { openNative(view, stream) } }
                            if (detectVideos) KoraNativeStream.anySite(request)?.let { stream ->
                                view.post {
                                    if (found.none { it.url == stream.url }) {
                                        found += stream
                                        if (found.size > 12) found.removeAt(0)
                                    }
                                }
                            }
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

    if (showServers) androidx.compose.material3.AlertDialog(onDismissRequest = { showServers = false },
        title = { Text("سيرفرات المشاهدة والتحميل") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                servers.forEachIndexed { i, link ->
                    androidx.compose.material3.OutlinedButton(onClick = { showServers = false; found.clear(); openServer(link) },
                        modifier = Modifier.fillMaxWidth().focusFrame()) {
                        Text((if (link.download) "⬇ تحميل · " else "▶ ${i + 1} · ") + link.label + " · " + (Uri.parse(link.url).host ?: ""), maxLines = 2)
                    }
                }
                Text("بعد ما يفتح السيرفر اضغط تشغيل داخل الصفحة إذا لزم، وأول ما يبدأ يطلع زر «شغّل الفيديو بمشغّل Watch Hub».",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { showServers = false }, modifier = Modifier.focusFrame()) { Text("إغلاق") } })

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

internal const val KORA_HOME = "https://majed-kooora.com/"

internal data class WebLinkItem(val label: String, val url: String, val download: Boolean)

/**
 * Reads the open page without running anything of its own: watch-server addresses (data-* links and
 * iframes), download links, and media the page's player already holds (jwplayer/video tags/HTML).
 * App-store, social and advert links are left out.
 */
private val WEB_SCAN = """
    (function(){
      var links=[],media=[],seen={},mseen={};
      var skip=/(t\.me|telegram|twitter\.com|x\.com|facebook|instagram|tiktok|play\.google|apps\.apple|\.apk(\?|$)|whatsapp|doubleclick|googlesyndication)/i;
      function abs(u){try{return new URL(u,location.href).href}catch(e){return null}}
      function add(u,l,k){u=abs(u);if(!u||!/^https?:/.test(u)||skip.test(u)||seen[u])return;
        if(u.split('#')[0]===location.href.split('#')[0])return;seen[u]=1;links.push({u:u,l:(l||'').replace(/\s+/g,' ').trim().slice(0,50),k:k});}
      function addMedia(u){u=abs(u);if(u&&/^https:/.test(u)&&!mseen[u]){mseen[u]=1;media.push(u);}}
      var attrs=['data-src','data-url','data-link','data-embed','data-embed-url','data-server','data-watch','data-iframe','data-frame','data-href'];
      document.querySelectorAll('['+attrs.join('],[')+']').forEach(function(e){attrs.forEach(function(a){var v=e.getAttribute(a);
        if(v&&/^(https?:)?\/\//.test(v)&&!/\.(jpe?g|png|webp|gif|svg|css|js)(\?|$)/i.test(v))add(v,e.textContent,'watch');});});
      document.querySelectorAll('iframe[src]').forEach(function(f){if(f.offsetWidth>150)add(f.src,'السيرفر الحالي','watch');});
      document.querySelectorAll('a[href]').forEach(function(a){var t=(a.textContent||'')+' '+(a.className||'')+' '+((a.parentElement&&a.parentElement.className)||'');
        if(/\.(mp4|mkv|m3u8)(\?|$)/i.test(a.href))addMedia(a.href);
        if(/تحميل|download/i.test(t))add(a.href,a.textContent,'download');});
      try{if(window.jwplayer){var p=jwplayer().getPlaylist()||[];p.forEach(function(i){(i.sources||[{file:i.file}]).forEach(function(s){if(s.file)addMedia(s.file);});});}}catch(e){}
      document.querySelectorAll('video,video source,source').forEach(function(v){var s=v.currentSrc||v.src;if(s&&!/^blob:/.test(s))addMedia(s);});
      var html=document.documentElement.innerHTML,re=/https?:\/\/[^"'\s<>\\]+?\.(m3u8|mp4|mkv)(\?[^"'\s<>\\]*)?/gi,m,n=0;
      while((m=re.exec(html))&&n<20){addMedia(m[0]);n++;}
      return JSON.stringify({page:location.href,links:links.slice(0,40),media:media.slice(0,10)});
    })();
""".trimIndent()

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
