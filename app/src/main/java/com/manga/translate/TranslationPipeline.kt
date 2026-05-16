package com.manga.translate

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import kotlin.math.max

internal class TranslationPipeline(
    context: Context,
    private val store: TranslationStore = TranslationStore(),
    private val vlmClient: LocalVlmClient = LocalVlmClient(),
    private val vlmManager: VlmModelManager = VlmModelManager(context.applicationContext)
) {
    @Volatile
    private var modelInitialized = false

    suspend fun translateImage(
        imageFile: File,
        glossary: MutableMap<String, String>,
        forceOcr: Boolean,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        providerContext: PageTranslationProviderContext? = null,
        onProgress: (String) -> Unit
    ): TranslationResult? = withContext(Dispatchers.Default) {
        if (!vlmManager.isModelReady()) {
            onProgress("MiniCPM-V 端侧模型未导入，请前往设置配置。")
            AppLogger.log("Pipeline", "Missing VLM models")
            return@withContext null
        }

        if (!ensureModelReady()) {
            onProgress("模型加载失败，请检查模型文件是否损坏。")
            AppLogger.log("Pipeline", "Failed to initialize local VLM")
            return@withContext null
        }

        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: run {
            AppLogger.log("Pipeline", "Failed to decode ${imageFile.name}")
            return@withContext null
        }

        try {
            onProgress("正在分析图片并翻译...")
            val prompt = buildVlmPrompt(language, glossary)
            val jsonResult = vlmClient.processImage(imageFile.readBytes(), prompt)
            AppLogger.log("Pipeline", "VLM Result: $jsonResult")
            val translatedBubbles = parseVlmJsonToBubbles(jsonResult, bitmap.width, bitmap.height)
            onProgress("翻译完成")
            TranslationResult(
                imageName = imageFile.name,
                width = bitmap.width,
                height = bitmap.height,
                bubbles = translatedBubbles,
                metadata = buildLocalTranslationMetadata(imageFile, language)
            )
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun ocrImage(
        imageFile: File,
        forceOcr: Boolean,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        onProgress: (String) -> Unit
    ): PageOcrResult? = withContext(Dispatchers.Default) {
        val cached = loadValidTranslation(
            imageFile = imageFile,
            fullTranslate = false,
            useVlDirectTranslate = false,
            language = language
        )
        if (cached != null) {
            return@withContext PageOcrResult(
                imageFile = imageFile,
                width = cached.width,
                height = cached.height,
                bubbles = cached.bubbles.map { bubble ->
                    OcrBubble(
                        id = bubble.id,
                        rect = bubble.rect,
                        text = bubble.sourceText.ifBlank { bubble.text },
                        source = bubble.source,
                        maskContour = bubble.maskContour
                    )
                },
                cacheMode = LOCAL_CACHE_MODE,
                metadata = buildLocalOcrMetadata(imageFile, language)
            )
        }

        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return@withContext null
        try {
            onProgress("端侧模式已跳过独立 OCR 预处理")
            PageOcrResult(
                imageFile = imageFile,
                width = bitmap.width,
                height = bitmap.height,
                bubbles = emptyList(),
                cacheMode = LOCAL_CACHE_MODE,
                metadata = buildLocalOcrMetadata(imageFile, language)
            )
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun translateFullPage(
        page: PageOcrResult,
        glossary: Map<String, String>,
        promptAsset: String,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        providerContext: PageTranslationProviderContext? = null,
        onProgress: (String) -> Unit
    ): TranslationResult? {
        return translateImage(
            imageFile = page.imageFile,
            glossary = glossary.toMutableMap(),
            forceOcr = false,
            language = language,
            providerContext = providerContext,
            onProgress = onProgress
        )
    }

    suspend fun translateImageWithVl(
        imageFile: File,
        language: TranslationLanguage
    ): FolderVlTranslateOutcome {
        return FolderVlTranslateOutcome(
            result = translateImage(
                imageFile = imageFile,
                glossary = mutableMapOf(),
                forceOcr = false,
                language = language,
                providerContext = null,
                onProgress = { }
            )
        )
    }

    fun hasValidTranslation(
        imageFile: File,
        fullTranslate: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage
    ): Boolean {
        return loadValidTranslation(
            imageFile = imageFile,
            fullTranslate = fullTranslate,
            useVlDirectTranslate = useVlDirectTranslate,
            language = language
        ) != null
    }

    fun loadValidTranslation(
        imageFile: File,
        fullTranslate: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage
    ): TranslationResult? {
        return store.load(
            imageFile,
            expectedMetadata = buildExpectedTranslationMetadata(
                imageFile = imageFile,
                fullTranslate = fullTranslate,
                useVlDirectTranslate = useVlDirectTranslate,
                language = language
            )
        )
    }

    fun saveResult(imageFile: File, result: TranslationResult): File {
        return store.save(imageFile, result)
    }

    suspend fun buildBlankTranslationResult(
        imageFile: File,
        forceOcr: Boolean,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH
    ): TranslationResult? = withContext(Dispatchers.Default) {
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return@withContext null
        try {
            TranslationResult(
                imageName = imageFile.name,
                width = bitmap.width,
                height = bitmap.height,
                bubbles = emptyList(),
                metadata = buildLocalTranslationMetadata(imageFile, language)
            )
        } finally {
            bitmap.recycle()
        }
    }

    fun buildBlankTranslationResult(
        page: PageOcrResult,
        mode: String,
        promptAsset: String,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH
    ): TranslationResult {
        return TranslationResult(
            imageName = page.imageFile.name,
            width = page.width,
            height = page.height,
            bubbles = page.bubbles.map { bubble ->
                BubbleTranslation.pending(
                    id = bubble.id,
                    rect = bubble.rect,
                    originalText = bubble.text,
                    source = bubble.source,
                    maskContour = bubble.maskContour
                )
            },
            metadata = buildLocalTranslationMetadata(page.imageFile, language)
        )
    }

    fun translationFileFor(imageFile: File): File {
        return store.translationFileFor(imageFile)
    }

    @Synchronized
    private fun ensureModelReady(): Boolean {
        if (modelInitialized) {
            return true
        }
        val initialized = vlmClient.initModel(
            vlmManager.textModelFile.absolutePath,
            vlmManager.mmprojModelFile.absolutePath,
            Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        )
        modelInitialized = initialized
        return initialized
    }

    private fun buildVlmPrompt(language: TranslationLanguage, glossary: Map<String, String>): String {
        val targetLang = when (language) {
            TranslationLanguage.JA_TO_ZH,
            TranslationLanguage.EN_TO_ZH,
            TranslationLanguage.KO_TO_ZH -> "中文"
        }
        return buildString {
            append("<__media__>\n")
            append("你是一个专业的漫画翻译专家。请识别图片中所有漫画气泡框内的文字，并翻译为")
            append(targetLang)
            append("。\n")
            append("输出必须是严格 JSON 数组：\n")
            append("[{\"box\": [x_min, y_min, x_max, y_max], \"original\": \"原文\", \"translation\": \"译文\"}]\n")
            append("不要输出 JSON 之外的解释。\n")
            if (glossary.isNotEmpty()) {
                append("术语表：\n")
                glossary.forEach { (source, target) ->
                    append("- ")
                    append(source)
                    append(": ")
                    append(target)
                    append('\n')
                }
            }
        }
    }

    private fun parseVlmJsonToBubbles(
        jsonString: String,
        imgWidth: Int,
        imgHeight: Int
    ): List<BubbleTranslation> {
        val startIndex = jsonString.indexOf('[')
        val endIndex = jsonString.lastIndexOf(']')
        if (startIndex == -1 || endIndex == -1 || endIndex <= startIndex) {
            AppLogger.log("Pipeline", "Missing JSON array in model output")
            return emptyList()
        }

        return try {
            val jsonArray = JSONArray(jsonString.substring(startIndex, endIndex + 1))
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(index) ?: continue
                    val box = obj.optJSONArray("box") ?: continue
                    val rect = parseRect(box, imgWidth, imgHeight) ?: continue
                    val original = obj.optString("original", "").trim()
                    val translation = obj.optString("translation", "").trim()
                    if (original.isBlank() && translation.isBlank()) {
                        continue
                    }
                    add(
                        BubbleTranslation.translated(
                            id = obj.optInt("id", index + 1),
                            rect = rect,
                            translatedText = translation,
                            source = BubbleSource.BUBBLE_DETECTOR,
                            maskContour = null,
                            originalText = original
                        )
                    )
                }
            }
        } catch (e: Exception) {
            AppLogger.log("Pipeline", "JSON parse error", e)
            emptyList()
        }
    }

    private fun parseRect(box: JSONArray, imgWidth: Int, imgHeight: Int): RectF? {
        if (box.length() < 4) return null

        var left = box.optDouble(0, Double.NaN).toFloat()
        var top = box.optDouble(1, Double.NaN).toFloat()
        var right = box.optDouble(2, Double.NaN).toFloat()
        var bottom = box.optDouble(3, Double.NaN).toFloat()
        if (left.isNaN() || top.isNaN() || right.isNaN() || bottom.isNaN()) {
            return null
        }

        val maxCoord = max(max(left, right), max(top, bottom))
        when {
            maxCoord <= 1.5f -> {
                left *= imgWidth
                right *= imgWidth
                top *= imgHeight
                bottom *= imgHeight
            }
            maxCoord <= 1000f -> {
                left = left / 1000f * imgWidth
                right = right / 1000f * imgWidth
                top = top / 1000f * imgHeight
                bottom = bottom / 1000f * imgHeight
            }
        }

        val clampedLeft = left.coerceIn(0f, imgWidth.toFloat())
        val clampedTop = top.coerceIn(0f, imgHeight.toFloat())
        val clampedRight = right.coerceIn(0f, imgWidth.toFloat())
        val clampedBottom = bottom.coerceIn(0f, imgHeight.toFloat())
        if (clampedRight <= clampedLeft || clampedBottom <= clampedTop) {
            return null
        }
        return RectF(clampedLeft, clampedTop, clampedRight, clampedBottom)
    }

    private fun buildExpectedTranslationMetadata(
        imageFile: File,
        fullTranslate: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage
    ): TranslationMetadata {
        return buildLocalTranslationMetadata(imageFile, language)
    }

    private fun buildLocalTranslationMetadata(
        imageFile: File,
        language: TranslationLanguage
    ): TranslationMetadata {
        return TranslationMetadata(
            sourceLastModified = imageFile.lastModified(),
            sourceFileSize = imageFile.length(),
            mode = TranslationMetadata.MODE_VL_DIRECT,
            language = language.name,
            promptAsset = LOCAL_PROMPT_ASSET,
            modelName = LOCAL_MODEL_NAME,
            providerId = LOCAL_PROVIDER_ID,
            apiFormat = LOCAL_API_FORMAT,
            ocrCacheMode = LOCAL_CACHE_MODE
        )
    }

    private fun buildLocalOcrMetadata(
        imageFile: File,
        language: TranslationLanguage
    ): OcrMetadata {
        return OcrMetadata(
            sourceLastModified = imageFile.lastModified(),
            sourceFileSize = imageFile.length(),
            cacheMode = LOCAL_CACHE_MODE,
            language = language.name,
            engineModel = LOCAL_MODEL_NAME
        )
    }

    companion object {
        private const val LOCAL_MODEL_NAME = "MiniCPM-V-4.6"
        private const val LOCAL_PROVIDER_ID = "local_minicpm_v"
        private const val LOCAL_API_FORMAT = "local"
        private const val LOCAL_CACHE_MODE = "vlm_only"
        private const val LOCAL_PROMPT_ASSET = "local:minicpm_vlm"
    }
}

data class OcrBubble(
    val id: Int,
    val rect: RectF,
    val text: String,
    val source: BubbleSource = BubbleSource.UNKNOWN,
    val maskContour: FloatArray? = null
)

data class PageOcrResult(
    val imageFile: File,
    val width: Int,
    val height: Int,
    val bubbles: List<OcrBubble>,
    val cacheMode: String = "",
    val metadata: OcrMetadata = OcrMetadata()
)

data class FolderVlTranslateOutcome(
    val result: TranslationResult? = null,
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false
)
