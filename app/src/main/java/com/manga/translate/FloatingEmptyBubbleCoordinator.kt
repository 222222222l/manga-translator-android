package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FloatingEmptyBubbleCoordinator(
    context: Context,
    private val settingsStore: SettingsStore,
    private val translationPipeline: TranslationPipeline
) {
    suspend fun process(
        bitmap: Bitmap,
        baseTranslation: TranslationResult,
        timeoutMs: Int,
        retryCount: Int,
        floatPromptAsset: String,
        floatVlPromptAsset: String,
        maxVlConcurrency: Int
    ): FloatingEmptyBubbleOutcome = withContext(Dispatchers.Default) {
        val targets = baseTranslation.bubbles.filter { it.needsTranslationRetry() }
        if (targets.isEmpty()) {
            return@withContext FloatingEmptyBubbleOutcome(baseTranslation)
        }

        if (!translationPipeline.isLocalModelReady()) {
            return@withContext FloatingEmptyBubbleOutcome(
                translation = baseTranslation,
                requiresVlModel = true
            )
        }

        val floatingLanguage = settingsStore.loadFloatingTranslateApiSettings().language
        val translatedMap = LinkedHashMap<Int, BubbleTranslation>(targets.size)
        targets.forEach { bubble ->
            val crop = cropBitmap(bitmap, bubble.rect)
            val translatedText = try {
                crop?.let { translationPipeline.translateBubbleCrop(it, floatingLanguage) }.orEmpty()
            } catch (e: Exception) {
                AppLogger.log("FloatingOCR", "Local floating bubble translation failed id=${bubble.id}", e)
                ""
            } finally {
                crop?.recycle()
            }
            if (translatedText.isNotBlank()) {
                translatedMap[bubble.id] = bubble.withTranslationResult(translatedText)
            }
        }
        if (translatedMap.isEmpty()) {
            return@withContext FloatingEmptyBubbleOutcome(translation = baseTranslation)
        }
        val updatedBubbles = baseTranslation.bubbles.map { bubble ->
            translatedMap[bubble.id]?.let { bubble.withContentFrom(it) } ?: bubble
        }
        FloatingEmptyBubbleOutcome(
            translation = baseTranslation.copy(bubbles = updatedBubbles)
        )
    }
}

data class FloatingEmptyBubbleOutcome(
    val translation: TranslationResult,
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false
)

