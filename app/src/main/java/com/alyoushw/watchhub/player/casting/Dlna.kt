package com.alyoushw.watchhub.player.casting

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal data class CastLan(val network: Network, val address: Inet4Address) {
    companion object {
        fun find(context: Context): CastLan {
            val cm = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
                val address = cm.getLinkProperties(network)?.linkAddresses?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress } ?: continue
                return CastLan(network, address)
            }
            error("اتصل بشبكة Wi-Fi نفسها المتصل بها التلفاز ثم أعد المحاولة.")
        }
    }
}

internal const val DLNA_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

internal data class DlnaDevice(val id: String, val name: String, val controlUrl: String, val serviceType: String)
internal data class DlnaStatus(val playing: Boolean, val positionMs: Long?, val state: String)

/** SSDP MediaRenderer discovery and the standard UPnP AVTransport SOAP actions. */
internal class Dlna(private val context: Context) {
    private val clients = java.util.concurrent.ConcurrentHashMap<Network, OkHttpClient>()
    private fun client(lan: CastLan) = clients.getOrPut(lan.network) { OkHttpClient.Builder().socketFactory(lan.network.socketFactory)
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> = lan.network.getAllByName(hostname).toList()
        }).followRedirects(false).followSslRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build() }

    suspend fun discover(): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val lan = CastLan.find(context)
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("WatchHub-DLNA")?.apply { setReferenceCounted(false); acquire() }
        val locations = linkedMapOf<String, InetAddress>()
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(lan.address, 0))
                lan.network.bindSocket(socket)
                socket.soTimeout = 600
                for (target in listOf("urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-upnp-org:service:AVTransport:1")) {
                    val text = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\n\r\n"
                    val bytes = text.toByteArray(Charsets.US_ASCII)
                    socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("239.255.255.250"), 1900))
                }
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (System.nanoTime() < until && locations.size < 20) {
                    coroutineContext.ensureActive()
                    val packet = DatagramPacket(ByteArray(8192), 8192)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    val response = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    if (!response.startsWith("HTTP/1.1 200", true)) continue
                    val location = response.lineSequence().firstOrNull { it.startsWith("LOCATION:", true) }
                        ?.substringAfter(':')?.trim() ?: continue
                    val url = location.toHttpUrlOrNull() ?: continue
                    if (url.username.isNotEmpty() || url.password.isNotEmpty()) continue
                    if (lan.network.getAllByName(url.host).none { it == packet.address }) continue
                    locations[location] = packet.address
                }
            }
        } finally { if (lock?.isHeld == true) lock.release() }
        val http = client(lan)
        val gate = Semaphore(4)
        coroutineScope {
            locations.map { (url, peer) -> async {
                gate.withPermit {
                    try { describe(http, lan, url, peer) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { null }
                }
            } }.awaitAll().filterNotNull().distinctBy { it.id }.sortedBy { it.name }
        }
    }

    private fun describe(http: OkHttpClient, lan: CastLan, location: String, peer: InetAddress): DlnaDevice? {
        val xml = http.newCall(Request.Builder().url(location).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val source = response.body?.source() ?: return null
            if (source.request(262145)) return null
            source.readUtf8()
        }
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val device = doc.getElementsByTag("device").firstOrNull { node ->
            node.children().any { it.tagName() == "deviceType" && ":MediaRenderer:" in it.text() }
        } ?: return null
        val service = device.getElementsByTag("service").firstOrNull {
            ":AVTransport:" in it.getElementsByTag("serviceType").text()
        } ?: return null
        val base = doc.getElementsByTag("URLBase").text().toHttpUrlOrNull() ?: location.toHttpUrlOrNull() ?: return null
        val control = base.resolve(service.getElementsByTag("controlURL").text()) ?: return null
        if (control.username.isNotEmpty() || control.password.isNotEmpty() || lan.network.getAllByName(control.host).none { it == peer }) return null
        return DlnaDevice(device.getElementsByTag("UDN").first()?.text().orEmpty().ifBlank { control.toString() },
            device.children().firstOrNull { it.tagName() == "friendlyName" }?.text()?.take(120) ?: "جهاز DLNA",
            control.toString(), service.getElementsByTag("serviceType").text())
    }

    suspend fun load(device: DlnaDevice, url: String, title: String, mime: String, positionMs: Long, durationMs: Long = 0): Boolean {
        // Strict renderers (Sony, LG) refuse a bare "*" profile; advertise a plain streamable HTTP resource.
        val info = "http-get:*:$mime:" + if (mime == "video/mp2t") DLNA_FEATURES.replace("OP=01", "OP=00") else DLNA_FEATURES
        val length = if (durationMs > 0) (durationMs / 1000).let { " duration=\"" + String.format(java.util.Locale.US, "%d:%02d:%02d.000", it / 3600, it / 60 % 60, it % 60) + "\"" } else ""
        val metadata = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"0\" restricted=\"1\"><dc:title>${escape(title)}</dc:title><upnp:class>object.item.videoItem.movie</upnp:class><res protocolInfo=\"${escape(info)}\"$length>${escape(url)}</res></item></DIDL-Lite>"
        action(device, "SetAVTransportURI", mapOf("CurrentURI" to url, "CurrentURIMetaData" to metadata))
        action(device, "Play", mapOf("Speed" to "1"))
        if (positionMs <= 0) return true
        // Most TVs reject Seek while still loading; wait until playback has really started.
        repeat(15) {
            kotlinx.coroutines.delay(1000)
            val state = try { status(device).state } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "" }
            if (state == "PLAYING" || state == "PAUSED_PLAYBACK") {
                return try { seek(device, positionMs); true }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { false }
            }
        }
        return try { seek(device, positionMs); true }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    suspend fun play(device: DlnaDevice) { action(device, "Play", mapOf("Speed" to "1")) }
    suspend fun pause(device: DlnaDevice) { action(device, "Pause") }
    suspend fun stop(device: DlnaDevice) { action(device, "Stop") }
    suspend fun seek(device: DlnaDevice, at: Long) {
        val seconds = (at / 1000).coerceAtLeast(0)
        action(device, "Seek", mapOf("Unit" to "REL_TIME", "Target" to String.format(java.util.Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)))
    }
    suspend fun status(device: DlnaDevice): DlnaStatus {
        val state = action(device, "GetTransportInfo").getElementsByTag("CurrentTransportState").text()
        val position = try { action(device, "GetPositionInfo").getElementsByTag("RelTime").text().split(':')
            .mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 3 }?.let { ((it[0] * 3600 + it[1] * 60 + it[2]) * 1000).toLong() } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
        return DlnaStatus(state == "PLAYING", position, state)
    }
    private suspend fun action(device: DlnaDevice, name: String, args: Map<String, String> = emptyMap()) = withContext(Dispatchers.IO) {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$name xmlns:u=\"${escape(device.serviceType)}\"><InstanceID>0</InstanceID>" +
            args.entries.joinToString("") { (key, value) -> "<$key>${escape(value)}</$key>" } + "</u:$name></s:Body></s:Envelope>"
        val request = Request.Builder().url(device.controlUrl).header("SOAPAction", "\"${device.serviceType}#$name\"")
            .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType())).build()
        client(CastLan.find(context)).newCall(request).execute().use { response ->
            val source = response.body?.source() ?: error("لم يرد جهاز DLNA.")
            if (source.request(65537)) error("رد جهاز DLNA غير صالح.")
            val doc = Jsoup.parse(source.readUtf8(), "", Parser.xmlParser())
            if (!response.isSuccessful || doc.getElementsByTag("errorCode").isNotEmpty()) {
                val code = doc.getElementsByTag("errorCode").text().take(12)
                error("رفض جهاز DLNA الأمر $name ($code). قد لا يدعم صيغة الفيديو أو هذا التحكم.")
            }
            doc
        }
    }
    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
