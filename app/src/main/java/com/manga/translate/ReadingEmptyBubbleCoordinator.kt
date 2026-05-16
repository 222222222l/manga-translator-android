package com.manga.translate

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class ReadingEmptyBubbleCoordinator(
    context: Context,
    private val translationStore: TranslationStore,
    private val repository: LibraryRepository,
    private val libraryPrefs: SharedPreferences,
    private val translationPipeline: TranslationPipeline,
    private val languageKeyPrefix: String = "translation_language_"
) {
    suspend fun process(
        imageFile: File,
        folder: File,
        baseTranslation: TranslationResult
    ): EmptyBubbleProcessOutcome? = withContext(Dispatchers.Default) {
        val targets = baseTranslation.bubbles.filter { it.needsTranslationRetry() }
        if (targets.isEmpty()) return@withContext null

        if (!translationPipeline.isLocalModelReady()) {
            AppLogger.log("Reading", "Missing local VLM models for empty bubble translation")
            throw LlmRequestException(
                "VL_MODEL_REQUIRED",
                "MiniCPM-V 端侧模型未导入"
            )
        }

        val language = getTranslationLanguage(folder)
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return@withContext null

        try {
            val translatedMap = LinkedHashMap<Int, BubbleTranslation>(targets.size)
            for (bubble in targets) {
                val crop = cropBitmap(bitmap, bubble.rect)
                val translatedText = try {
                    crop?.let { translationPipeline.translateBubbleCrop(it, language) }.orEmpty()
                } catch (e: Exception) {
                    AppLogger.log("Reading", "Local bubble translation failed id=${bubble.id}", e)
                    ""
                } finally {
                    crop?.recycle()
                }
                if (translatedText.isNotBlank()) {
                    translatedMap[bubble.id] = bubble.withTranslationResult(translatedText)
                }
            }
            if (translatedMap.isEmpty()) {
                return@withContext null
            }
            val merged = baseTranslation.bubbles.map { bubble ->
                translatedMap[bubble.id]?.let { bubble.withContentFrom(it) } ?: bubble
            }
            val updated = baseTranslation.copy(bubbles = merged)
            withContext(Dispatchers.IO) {
                translationStore.save(imageFile, updated)
            }
            EmptyBubbleProcessOutcome(updated, translatedByLlm = true)
        } finally {
            bitmap.recycleSafely()
        }
    }

    private fun getTranslationLanguage(folder: File): TranslationLanguage {
        val settingsFolder = repository.resolveSettingsFolder(folder)
        val value = libraryPrefs.getString(languageKeyPrefix + settingsFolder.absolutePath, null)
        return TranslationLanguage.fromString(value)
    }
}

private fun LlmResponseException.withPageName(context: Context, pageName: String): LlmResponseException {
    val pagePrefix = context.getString(R.string.error_page_prefix)
    if (responseContent.startsWith(pagePrefix)) return this
    return LlmResponseException(
        errorCode = errorCode,
        responseContent = "$pagePrefix$pageName\n$responseContent",
        cause = this
    )
}

data class EmptyBubbleProcessOutcome(
    val updatedTranslation: TranslationResult,
    val translatedByLlm: Boolean
)
