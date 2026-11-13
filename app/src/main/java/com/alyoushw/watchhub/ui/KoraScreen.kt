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

/** The public sports website, including its own watch flow; no Majed APK is required. */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KoraScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var web by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var fullscreen by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

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

    BackHandler(enabled = canGoBack && fullscreen == null) { web?.goBack() }
    DisposableEffect(lifecycle, web) {
        val view = web
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            closeFullscreen()
            web?.apply { stopLoading(); loadUrl("about:blank"); removeAllViews(); destroy() }
        }
    }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        TopAppBar(title = { Text("كوره") }, navigationIcon = {
            IconButton(onClick = { if (web?.canGoBack() == true) web?.goBack() else onBack() }, modifier = Modifier.focusFrame()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
            }
        }, actions = {
            TextButton(onClick = { web?.loadUrl(KORA_HOME) }, modifier = Modifier.focusFrame()) { Text("الرئيسية") }
            TextButton(onClick = { error = null; web?.reload() }, modifier = Modifier.focusFrame()) { Text("تحديث") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { host ->
                WebView(host).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.setSupportMultipleWindows(true)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                            if (!isUserGesture) return false
                            val popup = WebView(view.context)
                            popup.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(child: WebView, request: WebResourceRequest): Boolean {
                                    if (request.url.scheme in listOf("https", "http")) {
                                        view.loadUrl(request.url.toString(), mapOf("Referer" to view.url.orEmpty()))
                                    }
                                    child.post { child.destroy() }
                                    return true
                                }
                            }
                            (resultMsg.obj as WebView.WebViewTransport).webView = popup
                            resultMsg.sendToTarget()
                            return true
                        }
                        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                            if (fullscreen != null) { callback.onCustomViewHidden(); return }
                            fullscreenCallback = callback
                            fullscreen = view
                        }
                        override fun onHideCustomView() = closeFullscreen()
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            loading = true
                            error = null
                        }
                        override fun onPageFinished(view: WebView, url: String?) {
                            loading = false
                            canGoBack = view.canGoBack()
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                            if (request.isForMainFrame) { loading = false; error = "تعذر تحميل الصفحة. تحقق من الاتصال ثم اضغط تحديث." }
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val scheme = request.url.scheme
                            if (scheme == "http" || scheme == "https") return false
                            if (request.isForMainFrame) error = "هذا الرابط يتطلب تطبيقاً خارجياً؛ يمكنك العودة للمباريات."
                            return true
                        }
                    }
                    setDownloadListener { url, _, _, _, _ -> openBrowser(url) }
                    web = this
                    loadUrl(KORA_HOME)
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
