package com.alyoushw.watchhub.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

data class TimedCaption(val startMs: Long, val endMs: Long, val text: String)

object SubtitleFiles {
    private const val MAX_BYTES = 4 * 1024 * 1024

    fun parse(text: String): List<TimedCaption> {
        require(text.length <= MAX_BYTES) { "ملف الترجمة كبير جداً" }
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val cues = normalized.split(Regex("\n[\\t ]*\n+")).mapNotNull { block ->
            val lines = block.lines()
            if (lines.firstOrNull()?.trim()?.let { it.startsWith("NOTE") || it == "STYLE" || it == "REGION" } == true) return@mapNotNull null
            val index = lines.indexOfFirst { "-->" in it }
            if (index < 0) return@mapNotNull null
            val times = lines[index].trim().split(Regex("\\s+-->\\s+"), limit = 2)
            if (times.size != 2) return@mapNotNull null
            val start = timestamp(times[0]) ?: return@mapNotNull null
            val end = timestamp(times[1].substringBefore(' ')) ?: return@mapNotNull null
            val body = lines.drop(index + 1).joinToString("\n").trim()
            if (end <= start || body.isBlank()) null else TimedCaption(start, end, body)
        }.sortedBy { it.startMs }
        require(cues.isNotEmpty() && cues.size <= 50_000) { "الملف لا يحتوي على ترجمة SRT أو WebVTT صالحة" }
        return cues
    }

    private fun timestamp(value: String): Long? {
        val match = Regex("(?:(\\d{1,3}):)?([0-5]?\\d):([0-5]\\d)[.,](\\d{1,3})").matchEntire(value.trim()) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: 0
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        val millis = match.groupValues[4].padEnd(3, '0').toLong()
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
    }

    fun webVtt(captions: List<TimedCaption>): String {
        fun time(ms: Long): String = "%02d:%02d:%02d.%03d".format(java.util.Locale.ROOT,
            ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        return "WEBVTT\n\n" + captions.joinToString("\n\n") { "${time(it.startMs)} --> ${time(it.endMs)}\n${it.text}" } + "\n"
    }

    suspend fun fromUri(context: Context, uri: android.net.Uri, language: String): SourceSubtitle = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BYTES) throw SourceException("ملف الترجمة كبير جداً")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw SourceException("تعذّر فتح ملف الترجمة")
        cache(context, bytes.toString(Charsets.UTF_8), language, "ترجمة مضافة")
    }

    private fun cache(context: Context, text: String, language: String, label: String): SourceSubtitle {
        val captions = try { parse(text) } catch (e: IllegalArgumentException) { throw SourceException(e.message ?: "ملف ترجمة غير صالح") }
        val file = File(context.cacheDir, "subtitle-${java.util.UUID.randomUUID()}.vtt")
        file.writeText(webVtt(captions))
        return SourceSubtitle(file.nameWithoutExtension, label, language.ifBlank { "und" }, file.toURI().toString(), "vtt")
    }

    suspend fun fetch(context: Context, url: String, language: String, label: String): SourceSubtitle = withContext(Dispatchers.IO) {
        if (url.toHttpUrlOrNull() == null) throw SourceException("أدخل رابط HTTP أو HTTPS لملف ترجمة نصية")
        val text = SourceHttp.read(SourceHttp.client, url, MAX_BYTES)
        cache(context, text, language, label)
    }
}
