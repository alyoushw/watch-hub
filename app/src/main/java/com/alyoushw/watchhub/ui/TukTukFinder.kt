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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
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
import com.alyoushw.watchhub.data.TukTukMirrors
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
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
    /** Mirrors the app could not play itself; they open in the in-app browser. */
    val others = remember { mutableStateListOf<WebLinkItem>() }
    val log = remember { mutableListOf<String>() }
    var web by remember { mutableStateOf<WebView?>(null) }

    // Shared between the browser callbacks and the search below.
    val frameLinks = remember { linkedMapOf<String, WebLinkItem>() }
    val media = remember { java.util.concurrent.ConcurrentHashMap<String, KoraNativeStream>() }
    var pageDone by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    /** While pressing buttons, top-level pages must stay on this host (advert redirects are blocked). */
    var lockHost by remember { mutableStateOf<String?>(null) }
    var titlePageRef by remember { mutableStateOf<String?>(null) }

    manual?.let { url ->
        BackHandler { manual = null }
        KoraScreen(onBack = { manual = null }, homeUrl = url, pageTitle = "TukTuk · ${lookup.title}", detectVideos = true, siteKeyword = "tuktuk")
        return
    }

    suspend fun load(view: WebView, url: String, settleMs: Long = 2500, referer: String? = null): Boolean {
        val wait = CompletableDeferred<Unit>()
        pageDone = wait
        // Embedded players often check that they were opened from the site's page.
        if (referer != null) view.loadUrl(url, mapOf("Referer" to referer)) else view.loadUrl(url)
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
        videos.clear(); downloads.clear(); others.clear(); done = false; log.clear()
        try {
            // 1. Search and pick the matching film / series page.
            status = "نبحث عن «$title» في توك توك…"
            lockHost = null
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
            // 2. The page only loads its player after a press: press the watch buttons ourselves.
            status = "وصلنا: ${view.title ?: view.url}"
            lockHost = Uri.parse(view.url).host
            delay(3000)
            note("dump " + (js(view, DUMP_SCRIPT) ?: "").take(2500))
            downloads += frameLinks.values.filter { it.download }.distinctBy { it.url }.take(12)

            suspend fun harvest(label: String, waitMs: Long) {
                var waited = 0L
                while (waited < waitMs) { delay(1000); waited += 1000; if (media.isNotEmpty() && waited >= 3000) break }
                val candidates = media.values.sortedBy { if (it.transport == SourceTransport.HLS) 0 else 1 }.take(3)
                note("  $label -> ${candidates.size} media ${candidates.joinToString { it.url.take(80) }}")
                for (c in candidates) {
                    media.remove(c.url)
                    val withSession = c.copy(headers = c.headers + listOfNotNull(
                        CookieManager.getInstance().getCookie(c.url)?.takeIf { it.isNotBlank() }?.let { "Cookie" to it },
                        ("User-Agent" to view.settings.userAgentString).takeIf { c.headers.keys.none { k -> k.equals("User-Agent", true) } }))
                    if (videos.none { it.stream.url == withSession.url } && probe.compatible(withSession.variant())) {
                        videos += FoundVideo(label.ifBlank { Uri.parse(c.url).host ?: "سيرفر" }, withSession)
                        break
                    }
                }
            }
            /** Presses every server button on the open page in turn and keeps what plays. */
            suspend fun pressAll(pageUrl: String, depth: String) {
                val count = js(view, "(function(){$CANDIDATES;return String(cands().length);})()")?.toIntOrNull() ?: 0
                note("$depth buttons $count: " + (js(view, "(function(){$CANDIDATES;return cands().map(function(e){return (e.textContent||'').trim().slice(0,25)}).join(' | ');})()") ?: ""))
                for (i in 0 until minOf(count, 10)) {
                    if (view.url != pageUrl) { load(view, pageUrl, settleMs = 2000, referer = if (depth == "player") titlePageRef else null) }
                    val label = js(view, "(function(){$CANDIDATES;var e=cands()[$i];if(!e)return '';var t=(e.textContent||'').trim().slice(0,40);try{e.scrollIntoView();e.click();}catch(x){}return t;})()").orEmpty()
                    if (label.isBlank()) continue
                    status = "نضغط «$label» (${i + 1} من $count)…"
                    harvest(label, 8000)
                }
            }
            media.clear()
            val titlePage = view.url ?: page
            titlePageRef = titlePage
            // Fast path: the server buttons carry the Megamax mirror page, whose server list is read directly.
            val info = runCatching { org.json.JSONObject(js(view, SERVER_LINKS_SCRIPT) ?: "{}") }.getOrNull()
            val mirrorPages = buildList {
                info?.optJSONArray("l")?.let { arr -> for (i in 0 until arr.length()) TukTukMirrors.decodeLink(arr.getString(i))?.let(::add) }
                info?.optJSONArray("f")?.let { arr -> for (i in 0 until arr.length()) arr.getString(i).takeIf { "/iframe/" in it }?.let(::add) }
            }.distinct()
            info?.optJSONArray("dl")?.let { arr ->
                val list = (0 until arr.length()).map { arr.getJSONObject(it) }
                    .map { WebLinkItem(it.optString("t").ifBlank { "تحميل" }, TukTukMirrors.decodeGo(it.optString("u")), true) }
                    .filter { it.url.startsWith("http") }
                if (list.isNotEmpty()) { downloads.clear(); downloads += list.distinctBy { it.url } }
            }
            note("mirror pages ${mirrorPages.joinToString()}")
            for (mp in mirrorPages.take(3)) {
                status = "نجيب قائمة السيرفرات…"
                val r = try { TukTukMirrors.resolve(mp, titlePage) { status = it } }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { note("mirrors error $e"); null }
                note("mirrors $mp -> ${r?.variants?.size} ok, ${r?.others?.size} other")
                r?.variants?.forEach { v ->
                    val mime = when (v.transport) {
                        SourceTransport.HLS -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
                        SourceTransport.MKV -> androidx.media3.common.MimeTypes.VIDEO_MATROSKA
                        else -> androidx.media3.common.MimeTypes.VIDEO_MP4
                    }
                    if (videos.none { it.stream.url == v.url }) videos += FoundVideo(v.label, KoraNativeStream(v.url, v.transport, mime, v.httpHeaders))
                }
                r?.others?.forEach { o -> if (others.none { it.url == o.url }) others += WebLinkItem(o.label, o.url, false) }
            }
            if (videos.isEmpty()) {
            pressAll(titlePage, "title")
            // 3. Player pages (iframes) now on the page: open each and press its server list too.
            if (view.url != titlePage) load(view, titlePage, settleMs = 3000)
            val players = (frameLinks.values.filter { !it.download }.map { it.url } +
                (js(view, "(function(){return [].slice.call(document.querySelectorAll('iframe')).map(function(f){return f.src}).filter(function(u){return /^https?:/.test(u)}).join('\\n');})()") ?: "").split("\n"))
                .filter { it.startsWith("http") && it != titlePage && !it.contains("about:") }.distinct().take(5)
            note("players ${players.size}: ${players.joinToString()}")
            for ((i, player) in players.withIndex()) {
                status = "نفتح المشغّل ${i + 1} من ${players.size}…"
                lockHost = Uri.parse(player).host
                media.clear()
                load(view, player, settleMs = 2500, referer = titlePage)
                note("player ${view.url} dump " + (js(view, DUMP_SCRIPT) ?: "").take(1500))
                harvest(Uri.parse(player).host ?: "مشغّل", 4000)
                pressAll(player, "player")
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
                    androidx.compose.foundation.layout.Box(Modifier.size(1.dp)) {
                    AndroidView(modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(400.dp, 760.dp).alpha(0f), factory = { host ->
                        WebView(host).apply {
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
                                    if (scheme != "http" && scheme != "https") return true // no app links / intents
                                    val host = request.url.host.orEmpty()
                                    val locked = lockHost
                                    return request.isForMainFrame && locked != null && host != locked && !host.contains("tuktuk", true)
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
            if (others.isNotEmpty()) item { Text("سيرفرات تفتح في المتصفح (قد تحتاج تضغط تشغيل)", style = MaterialTheme.typography.titleMedium) }
            items(others, key = { "o-" + it.url }) { o ->
                OutlinedButton(onClick = { manual = o.url }, modifier = Modifier.fillMaxWidth().focusFrame()) {
                    Text("▶ ${o.label}", maxLines = 2)
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

/**
 * Clickable server entries: inside the block titled like "سيرفرات المشاهدة" when there is one,
 * else anywhere on the page (player pages); menus, downloads and social buttons are left out.
 */
private const val CANDIDATES = """function cands(){
var heads=[].slice.call(document.querySelectorAll('h1,h2,h3,h4,h5,h6,div,span,p,strong')).filter(function(e){var t=(e.textContent||'').trim();
return e.children.length<3&&t.length<40&&/سيرفرات المشاهده|سيرفرات المشاهدة|المشاهدة|watch servers|servers/i.test(t);});
var root=null;for(var h=0;h<heads.length&&!root;h++){var p=heads[h].parentElement;for(var k=0;k<4&&p;k++){
if(p.querySelectorAll('button,a,li,[onclick],[data-id],[data-server],[data-url],[data-link],[role=button]').length>0){root=p;break;}p=p.parentElement;}}
if(!root)root=document.body;if(!root)return [];
var els=[].slice.call(root.querySelectorAll('button,li,a,[onclick],[data-id],[data-server],[data-url],[data-link],[role=button]')).filter(function(e){
var t=(e.textContent||'').replace(/\s+/g,' ').trim();if(!t||t.length>60)return false;
if(/تحميل|download|تليجرام|telegram|عودة|back|skip|تخطي|facebook|twitter|الرئيسية|home|بحث|search|حمل/i.test(t))return false;
if(e.closest('header,nav,footer,[class*=menu],[class*=nav]'))return false;
var r=e.getBoundingClientRect();if(r.width<20||r.height<10)return false;
if(e.tagName==='A'){var href=e.getAttribute('href')||'';if(href&&!/^(#|javascript)/i.test(href)){try{if(new URL(e.href).host!==location.host)return false;}catch(x){return false;}}}
return true;});
return els.filter(function(e){return !els.some(function(o){return o!==e&&e.contains(o);});}).slice(0,15);}"""

/** A short picture of the server area and frames, for the report (no scripts or images). */
private const val DUMP_SCRIPT = """(function(){var b=document.body?document.body.innerHTML:'';var i=b.search(/سيرفرات|المشاهدة|servers/i);
var s=(i>=0?b.substr(Math.max(0,i-200),2500):b.substr(0,1500)).replace(/<svg[\s\S]*?<\/svg>/g,'').replace(/<script[\s\S]*?<\/script>/g,'').replace(/<img[^>]*>/g,'').replace(/\s+/g,' ');
var f=[].slice.call(document.querySelectorAll('iframe')).map(function(x){return x.src}).join(' | ');return 'iframes: '+f+' || '+s;})()"""

/** The page's server buttons (their encoded data-link), player frames and download links. */
private const val SERVER_LINKS_SCRIPT = """(function(){
var l=[].slice.call(document.querySelectorAll('[data-link]')).map(function(e){return e.getAttribute('data-link')||''});
var f=[].slice.call(document.querySelectorAll('iframe[src]')).map(function(e){return e.src});
var dl=[].slice.call(document.querySelectorAll('.downloads a[href], a[href*="go.php"]')).map(function(a){return {u:a.href,t:(a.textContent||'').replace(/\s+/g,' ').trim()}});
return JSON.stringify({l:l,f:f,dl:dl});})()"""

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
