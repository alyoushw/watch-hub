package com.alyoushw.watchhub.data

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** Translates text cues on-device; never records audio or claims speech translation. */
class CaptionTranslator : AutoCloseable {
    private val languageId = LanguageIdentification.getClient()
    private val clients = linkedMapOf<String, Translator>()
    private val ready = mutableSetOf<String>()
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 1500
    }
    private val lock = Mutex()

    suspend fun translate(text: String, hint: String? = null, onPreparing: () -> Unit = {}): String = lock.withLock {
        if (text.isBlank()) return@withLock text
        if (text.length > 4000) throw SourceException("نص الترجمة طويل جداً")
        val language = hint?.takeIf { it != "und" }?.let(TranslateLanguage::fromLanguageTag)
            ?: languageId.identifyLanguage(text).await().let(TranslateLanguage::fromLanguageTag)
            ?: throw SourceException("تعذّر تحديد لغة الترجمة النصية")
        if (language == TranslateLanguage.ARABIC) return@withLock text
        val key = "$language:$text"
        cache[key]?.let { return@withLock it }
        val translator = clients.getOrPut(language) {
            if (clients.size >= 3) {
                val oldest = clients.keys.first()
                clients.remove(oldest)?.close()
                ready.remove(oldest)
            }
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(language)
                .setTargetLanguage(TranslateLanguage.ARABIC).build())
        }
        if (language !in ready) {
            onPreparing()
            translator.downloadModelIfNeeded().await()
            ready += language
        }
        translator.translate(text).await().also { cache[key] = it }
    }

    override fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
        ready.clear()
        languageId.close()
    }
}
