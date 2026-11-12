package com.alyoushw.watchhub.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext

interface PlaybackEngine {
    fun supports(transport: SourceTransport): Boolean
    suspend fun prepare(variant: SourceVariant, query: SourceQuery, progress: (Float) -> Unit): String
}

class DirectPlaybackEngine(private val probe: SourceProbe = StreamProbe()) : PlaybackEngine {
    override fun supports(transport: SourceTransport) = transport in setOf(SourceTransport.MP4, SourceTransport.HLS, SourceTransport.MKV)
    override suspend fun prepare(variant: SourceVariant, query: SourceQuery, progress: (Float) -> Unit): String {
        if (!supports(variant.transport) || !probe.compatible(variant)) throw SourceException("رابط الفيديو غير متاح أو صيغته غير متوافقة")
        return variant.url
    }
}

class PlaybackEngines(private val engines: List<PlaybackEngine>) {
    suspend fun prepare(variant: SourceVariant, query: SourceQuery, progress: (Float) -> Unit = {}): String =
        (engines.firstOrNull { it.supports(variant.transport) } ?: throw SourceException("لا يوجد محرك يدعم هذا المصدر"))
            .prepare(variant, query, progress)
}

/** BitTorrent prepares and verifies the selected file; a Magnet is never sent to Media3. */
class TorrentPlaybackEngine(context: Context) : PlaybackEngine {
    private val root = File(context.filesDir, "source-torrents")
    private val lock = Mutex()
    override fun supports(transport: SourceTransport) = transport in setOf(SourceTransport.TORRENT, SourceTransport.MAGNET)

    override suspend fun prepare(variant: SourceVariant, query: SourceQuery, progress: (Float) -> Unit): String = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!supports(variant.transport)) throw SourceException("مصدر تورنت غير صالح")
            root.mkdirs()
            val session = SessionManager()
            try {
                session.start()
                val bytes = if (variant.transport == SourceTransport.MAGNET) {
                    if (magnetHash(variant.url) == null) throw SourceException("رابط Magnet غير صالح")
                    val exact = magnetExactSource(variant.url)
                    if (exact != null) SourceHttp.bytes(SourceHttp.client, exact, 8 * 1024 * 1024)
                    else runInterruptible { session.fetchMagnet(variant.url, 45, root) } ?: throw SourceException("تعذّر جلب بيانات التورنت من الأقران")
                } else SourceHttp.bytes(SourceHttp.client, variant.url, 8 * 1024 * 1024)
                coroutineContext.ensureActive()
                val info = try { TorrentInfo(bytes) } catch (_: Exception) { throw SourceException("ملف Torrent غير صالح") }
                if (info.infoHash().isAllZeros) throw SourceException("يلزم تورنت v1 أو تورنت هجين لهذا المحرك")
                val hash = info.infoHash().toHex()
                if (variant.transport == SourceTransport.MAGNET && hash != magnetHash(variant.url)) throw SourceException("بيانات التورنت لا تطابق الرابط")
                val directory = File(root, hash).apply { mkdirs() }
                val storage = info.files()
                val paths = (0 until storage.numFiles()).map { storage.filePath(it) }
                paths.forEachIndexed { index, path ->
                    if (File(path).isAbsolute || storage.fileFlags(index).and_(org.libtorrent4j.FileStorage.FLAG_SYMLINK).non_zero()) throw SourceException("مسار ملف غير آمن في التورنت")
                    val file = File(directory, path).canonicalFile
                    if (!file.path.startsWith(directory.canonicalPath + File.separator)) throw SourceException("مسار ملف غير آمن في التورنت")
                }
                val selected = chooseVideoFile(paths, (0 until storage.numFiles()).map { storage.fileSize(it) }, query)
                    ?: throw SourceException("لا يوجد ملف فيديو يطابق الموسم والحلقة المطلوبين")
                if (storage.fileSize(selected) > directory.usableSpace) throw SourceException("المساحة المتوفرة لا تكفي لملف الفيديو")
                session.download(info, directory)
                val handle = withTimeout(15_000) {
                    var found = session.find(info.infoHash())
                    while (found == null || !found.isValid) { delay(100); found = session.find(info.infoHash()) }
                    found
                }
                handle.prioritizeFiles(Array(storage.numFiles()) { if (it == selected) Priority.DEFAULT else Priority.IGNORE })
                withTimeout(30 * 60_000L) {
                    val wanted = storage.fileSize(selected)
                    while (true) {
                        coroutineContext.ensureActive()
                        val status = handle.status()
                        if (status.errorCode().isError) throw SourceException("تعذّر تجهيز ملف التورنت")
                        val ready = handle.fileProgress().getOrElse(selected) { 0L }
                        progress(if (wanted > 0) (ready.toFloat() / wanted).coerceIn(0f, 1f) else 0f)
                        if (wanted > 0 && ready >= wanted) break
                        delay(300)
                    }
                }
                // Close disk handles before handing the completed, piece-verified file to the player.
                handle.pause()
                val file = File(directory, paths[selected])
                if (!file.isFile || file.length() != storage.fileSize(selected)) throw SourceException("ملف التورنت لم يكتمل")
                file.toURI().toString()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: SourceException) { throw e }
            catch (_: Exception) { throw SourceException("فشل محرك التورنت؛ جرّب مصدراً آخر") }
            finally { withContext(NonCancellable + Dispatchers.IO) { session.stop() } }
        }
    }

    companion object {
        /** Never choose another episode from a season pack just because it is the largest file. */
        fun chooseVideoFile(paths: List<String>, sizes: List<Long>, query: SourceQuery): Int? {
            val candidates = paths.indices.filter { index ->
                val path = paths[index].lowercase(Locale.ROOT)
                path.substringAfterLast('.') in setOf("mp4", "mkv", "m4v", "webm") &&
                    !Regex("(?:^|[/\\\\ ._-])(sample|trailer|extras?)(?:[/\\\\ ._-]|$)").containsMatchIn(path) &&
                    sizes.getOrElse(index) { 0 } > 0
            }
            if (query.type == MediaType.TV) {
                val mark = Regex("(?i)(?:s0*${query.season}e0*${query.episode}(?![0-9])|(?<![0-9])0*${query.season}x0*${query.episode}(?![0-9]))")
                return candidates.filter { mark.containsMatchIn(paths[it]) }.maxByOrNull { sizes[it] }
            }
            if (candidates.size == 1) return candidates.first()
            val title = SourceQuery.titleKey(query.originalTitle)
            return candidates.filter {
                val name = SourceQuery.titleKey(File(paths[it]).nameWithoutExtension)
                name.startsWith("$title ") && Regex("(?<![0-9])${query.year}(?![0-9])").containsMatchIn(name)
            }.maxByOrNull { sizes[it] }
        }
    }
}
