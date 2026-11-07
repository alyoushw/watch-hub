package com.alyoushw.watchhub.data

import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * English → Arabic translation for stories and reviews TMDB only has in English.
 * Uses Google's ML Kit, which runs on the device: free, no key, and after a one-time
 * download of the language pack (~30 MB) it works offline.
 */
object ArabicTranslator {
    private val translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.ARABIC)
                .build(),
        )
    }
    private val cache = ConcurrentHashMap<String, String>()

    fun cached(text: String): String? = cache[text]

    /** The Arabic translation, or null if it could not be made (e.g. no internet for the first download). */
    suspend fun translate(text: String): String? {
        if (text.isBlank()) return null
        cache[text]?.let { return it }
        return try {
            translator.downloadModelIfNeeded().await()
            translator.translate(text).await().also { cache[text] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }
}
