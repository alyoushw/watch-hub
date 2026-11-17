package com.alyoushw.watchhub.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.alyoushw.watchhub.data.MediaType
import com.alyoushw.watchhub.data.SourceTransport
import com.alyoushw.watchhub.data.StreamProbe
import com.alyoushw.watchhub.data.WatchEntry
import com.alyoushw.watchhub.player.PlayerActivity
import com.alyoushw.watchhub.player.QueueItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private data class FoundVideo(val server: String, val stream: KoraNativeStream)

/**
 * TukTuk sits behind Cloudflare, so it is read by a hidden in-app browser (it passes the site like a
 * normal browser). In the background it searches the title, opens the matching film/episode, reads
 * the watch and download servers from every frame (the player's server list lives in an iframe),
 * opens each server and keeps the videos that really play, then lists them like EgyBest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TukTukFinderScreen(lookup: SourceLookup, home: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val probe = remember { StreamProbe() }
    val query = lookup.query
    val series = query.type == MediaType.TV
    val title = query.searchTitle?.takeIf { it.isNotBlank() } ?: query.originalTitle
    val searchUrl = home + "?s=" + Uri.encode(title)
    var status by remember { mutableStateOf("نبحث في توك توك…") }
    var done by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val videos = remember { mutableStateListOf<FoundVideo>() }
    val downloads = remember { mutableStateListOf<WebLinkItem>() }
    val log = remember { mutableListOf<String>() }
    var web by remember { mutableStateOf<WebView?>(null) }

    // Shared between the browser callbacks and the search below.
    val frameLinks = remember { linkedMapOf<String, WebLinkItem>() }
    val media = remember { java.util.concurrent.ConcurrentHashMap<String, KoraNativeStream>() }
    var pageDone by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }

    manual?.let { url ->
        BackHandler { manual = null }
        KoraScreen(onBack = { manual = null }, homeUrl = url, pageTitle = "TukTuk · ${lookup.title}", detectVideos = true, siteKeyword = "tuktuk")
        return
    }

    suspend fun load(view: WebView, url: String, settleMs: Long = 2500): Boolean {
        val wait = CompletableDeferred<Unit>()
        pageDone = wait
        view.loadUrl(url)
        val ok = withTimeoutOrNull(30_000) { wait.await() } != null
        delay(settleMs)
        return ok
    }
    suspend fun js(view: WebView, script: String): String? = suspendCancellableCoroutine { c ->
        view.evaluateJavascript(script) { encoded ->
            if (c.isActive) c.resume(runCatching { org.json.JSONTokener(encoded).nextValue() as? String }.getOrNull())
        }
    }
    fun note(line: String) { log += line; if (log.size > 200) log.removeAt(0) }

    LaunchedEffect(web, retry) {
        val view = web ?: return@LaunchedEffect
        videos.clear(); downloads.clear(); done = false; log.clear()
        try {
            // 1. Search and pick the matching film / series page.
            status = "نبحث عن «$title» في توك توك…"
            load(view, searchUrl)
            note("search ${view.url}")
            val wanted = SearchKey(title, query.year, if (series) query.season else null, if (series) query.episode else null)
            var page = pickLink(js(view, LINKS_SCRIPT), wanted, view.url, note = ::note)
                ?: run { status = "ما لقينا «$title» في نتائج توك توك."; done = true; return@LaunchedEffect }
            status = "فتحنا صفحة العمل…"
            frameLinks.clear(); media.clear() // nothing from the search page counts
            load(view, page)
            note("title ${view.url}")
            // A series page lists episodes: go to the episode itself.
            if (series) pickLink(js(view, LINKS_SCRIPT), wanted, view.url, episodeOnly = true, note = ::note)?.let {
                page = it; frameLinks.clear(); media.clear(); load(view, it); note("episode ${view.url}")
            }
            // A separate "watch" page, when the title page links to one.
            js(view, WATCH_LINK_SCRIPT)?.takeIf { it.startsWith("http") && it != view.url }?.let {
                status = "فتحنا صفحة المشاهدة…"; frameLinks.clear(); media.clear(); load(view, it); note("watch ${view.url}")
            }
            // 2. Servers from every frame (the player's list is inside an iframe).
            status = "وصلنا: ${view.title ?: view.url} · نقرأ السيرفرات…"
            delay(5000)
            val servers = frameLinks.values.filter { !it.download && !(Uri.parse(it.url).host ?: "").contains("tuktuk", true) }
                .distinctBy { it.url }.take(12)
            downloads += frameLinks.values.filter { it.download }.distinctBy { it.url }.take(12)
            note("servers ${servers.size}: " + servers.joinToString { it.label + " " + it.url })
            // Videos the page itself already set up.
            media.values.toList().forEach { s -> if (videos.none { it.stream.url == s.url } && probe.compatible(s.variant())) videos += FoundVideo("TukTuk", s) }
            // 3. Open each server and keep the videos that play.
            for ((i, server) in servers.withIndex()) {
                status = "نجرّب السيرفر ${i + 1} من ${servers.size}: ${server.label}"
                media.clear()
                load(view, server.url, settleMs = 1500)
                var waited = 0
                while (waited < 9000 && media.isEmpty()) { delay(1500); waited += 1500 }
                val candidates = media.values.toList().sortedBy { if (it.transport == SourceTransport.HLS) 0 else 1 }.take(3)
                note("server ${server.label} -> ${candidates.size} media")
                for (c in candidates) {
                    val withSession = c.copy(headers = c.headers + listOfNotNull(
                        CookieManager.getInstance().getCookie(c.url)?.takeIf { it.isNotBlank() }?.let { "Cookie" to it },
                        ("User-Agent" to view.settings.userAgentString).takeIf { c.headers.keys.none { k -> k.equals("User-Agent", true) } }))
                    if (videos.none { it.stream.url == withSession.url } && probe.compatible(withSession.variant())) {
                        videos += FoundVideo(server.label.ifBlank { Uri.parse(server.url).host ?: "سيرفر" }, withSession)
                        break
                    }
                }
            }
            status = if (videos.isEmpty()) "ما لقينا فيديو يشتغل تلقائياً. جرّب «تصفح الموقع» تحت." else "جاهز: ${videos.size} سيرفر يشتغل"
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { status = "صار خطأ أثناء البحث: ${e.javaClass.simpleName}"; note("error $e") }
        finally { done = true; view.stopLoading(); view.loadUrl("about:blank") }
    }

    DisposableEffect(Unit) { onDispose { web?.apply { stopLoading(); destroy() } } }

    Scaffold(topBar = {
        TopAppBar(title = { Text("TukTuk · ${lookup.title}", maxLines = 1) }, navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.focusFrame()) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") }
        }, actions = { if (done) TextButton(onClick = { retry++ }, modifier = Modifier.focusFrame()) { Text("إعادة البحث") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(status, color = Gold)
                    if (!done) LinearProgressIndicator(Modifier.fillMaxWidth())
                    // The hidden browser that does the work (it must be attached to run).
                    AndroidView(modifier = Modifier.size(1.dp), factory = { host ->
                        WebView(host).apply {
                            layoutParams = ViewGroup.LayoutParams(1, 1)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = true
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.setSupportMultipleWindows(false)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                                WebViewCompat.addWebMessageListener(this, "watchHubScan", setOf("*")) { _, message, _, _, _ ->
                                    val data = runCatching { org.json.JSONObject(message.data?.take(65_536) ?: return@addWebMessageListener) }.getOrNull()
                                        ?: return@addWebMessageListener
                                    val pageUrl = data.optString("page")
                                    data.optJSONArray("links")?.let { arr ->
                                        for (i in 0 until arr.length()) {
                                            val o = arr.getJSONObject(i)
                                            val u = o.optString("u")
                                            if (u.startsWith("http") && u !in frameLinks && frameLinks.size < 80)
                                                frameLinks[u] = WebLinkItem(o.optString("l").ifBlank { Uri.parse(u).host ?: "سيرفر" }, u, o.optString("k") == "download")
                                        }
                                    }
                                    data.optJSONArray("media")?.let { arr ->
                                        for (i in 0 until arr.length()) KoraNativeStream.fromPage(arr.getString(i), pageUrl)?.let { media.putIfAbsent(it.url, it) }
                                    }
                                }
                                WebViewCompat.addDocumentStartJavaScript(this, FRAME_SCAN, setOf("*"))
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) { pageDone?.complete(Unit) }
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val scheme = request.url.scheme
                                    return scheme != "http" && scheme != "https" // no app links / intents
                                }
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    KoraNativeStream.anySite(request)?.let { media.putIfAbsent(it.url, it) }
                                    return null
                                }
                            }
                            web = this
                        }
                    })
                }
            }
            if (videos.isNotEmpty()) item { Text("سيرفرات المشاهدة الشغالة", style = MaterialTheme.typography.titleMedium) }
            items(videos, key = { it.stream.url }) { v ->
                Button(onClick = {
                    val item = QueueItem(listOf(v.stream.url), WatchEntry(
                        key = "web-session:${java.util.UUID.randomUUID()}", kind = if (series) WatchEntry.EPISODE else WatchEntry.MOVIE,
                        itemId = "tuktuk", title = lookup.title, accountId = "web"),
                        mimeType = v.stream.mimeType, httpHeaders = v.stream.headers, recordHistory = false)
                    PlayerActivity.start(context, listOf(item), 0)
                }, modifier = Modifier.fillMaxWidth().focusFrame()) {
                    Text("TukTuk · ${v.server} · ${v.stream.transport.name}")
                }
            }
            if (downloads.isNotEmpty()) item { Text("روابط التحميل", style = MaterialTheme.typography.titleMedium) }
            items(downloads, key = { "dl-" + it.url }) { d ->
                OutlinedButton(onClick = { manual = d.url }, modifier = Modifier.fillMaxWidth().focusFrame()) {
                    Text("⬇ ${d.label} · ${Uri.parse(d.url).host ?: ""}", maxLines = 2)
                }
            }
            // Always visible: the report helps even while the search is still running or stuck.
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { manual = searchUrl }, modifier = Modifier.fillMaxWidth().focusFrame()) { Text("تصفح الموقع بنفسك") }
                    TextButton(onClick = {
                        val report = "TukTuk ${lookup.title} (${query.year}) S${query.season} E${query.episode}\n" + log.joinToString("\n")
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("TukTuk", report))
                        Toast.makeText(context, "نسخنا التقرير؛ ألصقه للمطوّر", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.focusFrame()) { Text("انسخ تقرير البحث (للمطوّر)") }
                }
            }
        }
    }
}

private data class SearchKey(val title: String, val year: Int, val season: Int?, val episode: Int?)

private val ORDINALS_AR = listOf("الاول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن", "التاسع", "العاشر")
private fun norm(s: String) = s.lowercase().replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')
private fun key(s: String) = norm(s).filter { it.isLetterOrDigit() }

/** The best same-site link for the title (and season/episode) among the page's links. */
private fun pickLink(json: String?, wanted: SearchKey, pageUrl: String?, episodeOnly: Boolean = false, note: (String) -> Unit): String? {
    val arr = runCatching { org.json.JSONArray(json ?: return null) }.getOrNull() ?: return null
    val host = Uri.parse(pageUrl ?: "").host
    val titleKey = key(wanted.title)
    var best: Pair<String, Int>? = null
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val url = o.optString("u")
        val text = norm(o.optString("t") + " " + Uri.decode(url))
        if (Uri.parse(url).host != host || url == pageUrl) continue
        if (Regex("/(category|genre|tag|page|author|actor|cast)/").containsMatchIn(url)) continue
        if (titleKey.isNotEmpty() && !key(text).contains(titleKey)) continue
        var score = 10
        if (wanted.year.toString() in text) score += 5
        if (wanted.episode != null) {
            val ep = Regex("(?:الحلقه|episode|ep)\\s*-?\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            if (ep != null && ep != wanted.episode) continue
            if (ep == wanted.episode) score += 20
            val season = Regex("(?:الموسم|season)\\s*-?\\s*([^\\s-]+)").find(text)?.groupValues?.get(1)?.let { s ->
                s.toIntOrNull() ?: ORDINALS_AR.indexOf(s).takeIf { it >= 0 }?.plus(1)
            }
            if (season != null && season != wanted.season) continue
            if (season == wanted.season) score += 5
            if (episodeOnly && ep == null) continue
        }
        if (best == null || score > best.second) best = url to score
    }
    note("pick ${best?.first} (${arr.length()} links)")
    return best?.first
}

/** Every link on the page with its visible text (or image title). */
private const val LINKS_SCRIPT = """(function(){var o=[];document.querySelectorAll('a[href]').forEach(function(a){
var t=(a.textContent||'')+' '+(a.title||'');var i=a.querySelector('img');if(i)t+=' '+(i.alt||'')+' '+(i.title||'');
o.push({u:a.href,t:t.replace(/\s+/g,' ').trim().slice(0,200)});});return JSON.stringify(o.slice(0,400));})()"""

/** A link to the page's separate "watch" page, if it has one. */
private const val WATCH_LINK_SCRIPT = """(function(){var a=[].slice.call(document.querySelectorAll('a[href]')).find(function(a){
var t=(a.textContent||'')+' '+(a.className||'');return /(^|\s)(مشاهدة|شاهد|watch)(\s|$)|watch/i.test(t)&&a.host===location.host&&a.href!==location.href;});
return a?a.href:'';})()"""

/**
 * Runs in every frame (the player's server list is usually an iframe of another host): reports
 * server addresses, download links and media the frame's player holds, a few times while it loads.
 */
private val FRAME_SCAN = """
(function(){
  if(window.__whScan)return;window.__whScan=true;
  var skip=/(t\.me|telegram|twitter\.com|x\.com\/|facebook|instagram|tiktok|play\.google|apps\.apple|\.apk(\?|$)|whatsapp|doubleclick|googlesyndication)/i;
  function abs(u){try{return new URL(u,location.href).href}catch(e){return null}}
  function scan(){
    var links=[],media=[],seen={};
    function add(u,l,k){u=abs(u);if(!u||!/^https?:/.test(u)||skip.test(u)||seen[u])return;if(u.split('#')[0]===location.href.split('#')[0])return;
      seen[u]=1;links.push({u:u,l:(l||'').replace(/\s+/g,' ').trim().slice(0,50),k:k});}
    function m(u){u=abs(u);if(u&&/^https:/.test(u)&&media.indexOf(u)<0)media.push(u);}
    var attrs=['data-src','data-url','data-link','data-embed','data-embed-url','data-server','data-watch','data-iframe','data-frame','data-href','data-video'];
    document.querySelectorAll('['+attrs.join('],[')+']').forEach(function(e){attrs.forEach(function(a){var v=e.getAttribute(a);
      if(v&&/^(https?:)?\/\//.test(v)&&!/\.(jpe?g|png|webp|gif|svg|css|js)(\?|$)/i.test(v))add(v,e.textContent||e.title,'watch');});});
    function chrome(e){return e.closest&&e.closest('header,nav,footer,aside,.menu,.navbar,.footer,.header,[class*="menu"],[class*="nav"],[id*="menu"]');}
    document.querySelectorAll('iframe[src]').forEach(function(f){var r=f.getBoundingClientRect();if(r.width>200&&r.height>100)add(f.src,'','watch');});
    document.querySelectorAll('a[href]').forEach(function(a){if(chrome(a))return;var t=(a.textContent||'')+' '+(a.className||'')+' '+((a.parentElement&&a.parentElement.className)||'');
      if(/\.(mp4|mkv|m3u8)(\?|$)/i.test(a.href))m(a.href);
      if(/تحميل|download/i.test(t))add(a.href,a.textContent,'download');});
    try{if(window.jwplayer){(jwplayer().getPlaylist()||[]).forEach(function(i){(i.sources||[{file:i.file}]).forEach(function(s){if(s.file)m(s.file);});});}}catch(e){}
    document.querySelectorAll('video,source').forEach(function(v){var s=v.currentSrc||v.src;if(s&&!/^blob:/.test(s))m(s);});
    var html=document.documentElement?document.documentElement.innerHTML:'',re=/https?:\/\/[^"'\s<>\\]+?\.(m3u8|mp4|mkv)(\?[^"'\s<>\\]*)?/gi,x,n=0;
    while((x=re.exec(html))&&n<20){m(x[0]);n++;}
    try{window.watchHubScan.postMessage(JSON.stringify({page:location.href,links:links.slice(0,60),media:media.slice(0,10)}));}catch(e){}
  }
  var runs=0;var t=setInterval(function(){scan();if(++runs>=8)clearInterval(t);},1500);
  document.addEventListener('DOMContentLoaded',scan);
})();
""".trimIndent()
