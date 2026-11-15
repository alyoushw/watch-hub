package com.alyoushw.watchhub.data

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Chromium networking for Akwam HTML/assets only. The existing video player is unchanged. */
internal object AkwamChromium {
    data class Page(val url: String, val status: Int, val body: ByteArray, val headers: Map<String, String>, val cookies: List<String>)
    @Volatile private var engine: CronetEngine? = null
    private val callbacks = Executors.newSingleThreadExecutor { Thread(it, "akwam-http").apply { isDaemon = true } }

    private fun engine(context: Context): CronetEngine = engine ?: synchronized(this) {
        engine ?: try {
            CronetEngine.Builder(context.applicationContext).enableQuic(true).enableHttp2(true).enableBrotli(true)
                // The site advertises h3=":443" in its HTTPS Alt-Svc header.
                .addQuicHint("akw.cam", 443, 443).build().also { engine = it }
        } catch (e: RuntimeException) { throw IOException("تعذر بدء محرك Chromium", e) }
        catch (e: LinkageError) { throw IOException("محرك Chromium غير متاح على الجهاز", e) }
    }

    fun fetch(context: Context, url: String, headers: Map<String, String> = emptyMap()): Page {
        fun allowed(value: String) = value.toHttpUrlOrNull()?.let { it.isHttps && it.host == "akw.cam" } == true
        require(allowed(url))
        val finished = CountDownLatch(1)
        val outcome = AtomicReference<Result<Page>?>(null)
        fun finish(value: Result<Page>) { if (outcome.compareAndSet(null, value)) finished.countDown() }
        val bytes = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocateDirect(32 * 1024)
        var redirects = 0
        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
                if (allowed(newLocationUrl) && redirects++ < 3) request.followRedirect()
                else { finish(Result.failure(IOException("تحويل أكوام غير متوافق"))); request.cancel() }
            }
            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) { request.read(buffer) }
            override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, data: ByteBuffer) {
                data.flip()
                if (bytes.size() + data.remaining() > 2 * 1024 * 1024) {
                    finish(Result.failure(IOException("رد أكوام أكبر من الحد المسموح"))); request.cancel(); return
                }
                val chunk = ByteArray(data.remaining())
                data.get(chunk)
                bytes.write(chunk)
                data.clear()
                request.read(data)
            }
            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                val responseHeaders = info.allHeaders.mapValues { it.value.joinToString(", ") }
                val cookies = info.allHeaders.filterKeys { it.equals("Set-Cookie", true) }.values.flatten()
                finish(Result.success(Page(info.url, info.httpStatusCode, bytes.toByteArray(), responseHeaders, cookies)))
            }
            override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
                val reason = Regex("net::([A-Z_]+)").find(error.message.orEmpty())?.groupValues?.get(1) ?: error.javaClass.simpleName
                finish(Result.failure(IOException(reason, error)))
            }
            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) { finish(Result.failure(IOException("أُلغي طلب أكوام"))) }
        }
        val builder = engine(context).newUrlRequestBuilder(url, callback, callbacks).disableCache()
        val requestHeaders = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
            put("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36")
            put("Referer", "https://akw.cam/")
            putAll(headers)
        }
        requestHeaders.filterKeys { it.lowercase() !in setOf("host", "connection", "accept-encoding", "content-length", "transfer-encoding") }
            .forEach { (name, value) -> builder.addHeader(name, value) }
        val request = builder.build()
        try {
            request.start()
            if (!finished.await(20, TimeUnit.SECONDS)) throw java.net.SocketTimeoutException("انتهت مهلة Chromium")
            return (outcome.get() ?: throw IOException("رد Chromium فارغ")).getOrThrow()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("توقف طلب أكوام")
        } finally { request.cancel() }
    }
}
