package com.alyoushw.watchhub.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

/**
 * What is playing, in words: short badges for the controls ("4K", "HDR10", "HEVC", "5.1",
 * "Dolby Atmos") and the full list for the "معلومات التشغيل" panel.
 */
data class MediaInfo(val badges: List<String>, val rows: List<Pair<String, String>>) {
    /** The picture line, e.g. "1080p · 50fps · HEVC". */
    val quality: String? get() = badges.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@OptIn(UnstableApi::class)
fun mediaInfo(player: ExoPlayer, measuredFps: Float, audioTracks: Int, textTracks: Int): MediaInfo? {
    val v = player.videoFormat
    val a = player.audioFormat
    if (v == null && a == null) return null
    val badges = mutableListOf<String>()
    val rows = mutableListOf<Pair<String, String>>()

    if (v != null && v.height > 0) {
        val w = v.width.takeIf { it > 0 } ?: 0
        val lines = if (w > 0) minOf(w, v.height) else v.height
        val res = when {
            w >= 3800 || lines >= 2100 -> "4K"
            lines >= 1400 -> "1440p"
            else -> "${lines}p"
        }
        val fps = v.frameRate.takeIf { it > 1f } ?: measuredFps.takeIf { it > 1f }
        val hdr = hdrName(v)
        val codec = videoCodec(v)
        badges += res
        fps?.let { badges += "${Math.round(it)}fps" }
        hdr?.let { badges += it }
        codec?.let { badges += it }

        rows += "الدقة" to "${if (w > 0) "$w×${v.height}" else "${v.height}p"} ($res)"
        fps?.let { rows += "الفريمات" to String.format(Locale.US, "%.2f fps", it).replace(".00", "") }
        rows += "الصورة" to (hdr ?: "SDR")
        codec?.let { rows += "ترميز الصورة" to it }
        bitrate(v)?.let { rows += "معدل الصورة" to it }
    }

    if (a != null) {
        val codec = audioCodec(a)
        val channels = channelsName(a.channelCount)
        channels?.let { if (a.channelCount > 2) badges += it }
        if (codec != null && (codec.startsWith("Dolby") || codec.startsWith("DTS") || codec == "TrueHD")) badges += codec
        rows += "الصوت" to listOfNotNull(codec, channels, a.sampleRate.takeIf { it > 0 }?.let { "${it / 1000} kHz" }).joinToString(" · ").ifEmpty { "—" }
        languageName(a.language)?.let { rows += "لغة الصوت" to it }
        bitrate(a)?.let { rows += "معدل الصوت" to it }
    }

    rows += "أصوات معلّقة" to "$audioTracks"
    rows += "ترجمات" to "$textTracks"
    player.currentMediaItem?.localConfiguration?.uri?.lastPathSegment?.substringAfterLast('.', "")
        ?.takeIf { it.isNotBlank() && it.length <= 5 }?.let { rows += "صيغة الملف" to it.uppercase(Locale.ROOT) }
    rows += "المحمّل مسبقاً" to "${player.totalBufferedDuration / 1000} ث"
    player.videoDecoderCounters?.let { c ->
        c.ensureUpdated()
        rows += "فريمات ضايعة" to "${c.droppedBufferCount}"
    }
    return MediaInfo(badges, rows)
}

@OptIn(UnstableApi::class)
private fun hdrName(f: Format): String? {
    if (f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || f.codecs?.startsWith("dv") == true) return "Dolby Vision"
    return when (f.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> "HDR10"
        C.COLOR_TRANSFER_HLG -> "HLG"
        else -> null
    }
}

@OptIn(UnstableApi::class)
private fun videoCodec(f: Format): String? {
    val c = f.codecs.orEmpty()
    return when {
        f.sampleMimeType == MimeTypes.VIDEO_H265 || c.startsWith("hvc1") || c.startsWith("hev1") -> "HEVC"
        f.sampleMimeType == MimeTypes.VIDEO_H264 || c.startsWith("avc") -> "H.264"
        f.sampleMimeType == MimeTypes.VIDEO_AV1 || c.startsWith("av01") -> "AV1"
        f.sampleMimeType == MimeTypes.VIDEO_VP9 || c.startsWith("vp09") -> "VP9"
        f.sampleMimeType == MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "HEVC"
        else -> null
    }
}

@OptIn(UnstableApi::class)
private fun audioCodec(f: Format): String? = when (f.sampleMimeType) {
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_AC3 -> "Dolby Digital"
    MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
    MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_AC4 -> "Dolby Atmos"
    MimeTypes.AUDIO_TRUEHD -> "TrueHD"
    MimeTypes.AUDIO_DTS -> "DTS"
    MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
    MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_VORBIS -> "Vorbis"
    MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP3"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    else -> f.codecs?.let { c -> if (c.startsWith("mp4a")) "AAC" else null }
}

private fun channelsName(n: Int): String? = when (n) {
    1 -> "أحادي"
    2 -> "ستيريو"
    6 -> "5.1"
    8 -> "7.1"
    else -> if (n > 2) "$n قنوات" else null
}

@OptIn(UnstableApi::class)
private fun bitrate(f: Format): String? {
    val b = listOf(f.averageBitrate, f.bitrate, f.peakBitrate).firstOrNull { it > 0 } ?: return null
    return if (b >= 1_000_000) String.format(Locale.US, "%.1f Mbps", b / 1_000_000f) else "${b / 1000} kbps"
}

private fun languageName(code: String?): String? {
    val c = code?.takeIf { it.isNotBlank() && it != "und" } ?: return null
    return Locale.forLanguageTag(c).getDisplayLanguage(Locale("ar")).takeIf { it.isNotBlank() && it != c } ?: c
}
