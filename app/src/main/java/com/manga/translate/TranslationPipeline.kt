package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import kotlin.math.max

internal class TranslationPipeline(
    context: Context,
    private val store: TranslationStore = TranslationStore(),
    private val vlmClient: LocalVlmClient = LocalVlmClient(),
    private val vlmManager: VlmModelManager = VlmModelManager(context.applicationContext),
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext)
) {
    @Volatile
    private var modelInitialized = false
    @Volatile
    private var initializedTextModelPath: String? = null
    @Volatile
    private var initializedMmprojModelPath: String? = null
    @Volatile
    private var initializedThreadCount: Int? = null
    @Volatile
    private var lastModelInitError: String? = null

    suspend fun translateImage(
        imageFile: File,
        glossary: MutableMap<String, String>,
        forceOcr: Boolean,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        providerContext: PageTranslationProviderContext? = null,
        onProgress: (String) -> Unit
    ): TranslationResult? = withContext(Dispatchers.Default) {
        onProgress("正在执行文字定位...")
        val result = runStructuredImageTranslation(
            imageFile = imageFile,
            language = language,
            glossary = glossary,
            customPrompt = "",
            onProgress = onProgress
        ) ?: return@withContext null
        onProgress(
            if (result.translationResult?.bubbles?.isNotEmpty() == true) {
                "翻译完成"
            } else {
                "模型已运行，但未生成可嵌字结果"
            }
        )
        result.translationResult
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
        if (!isLocalModelReady()) {
            return FolderVlTranslateOutcome(requiresVlModel = true)
        }
        val result = translateImage(
            imageFile = imageFile,
            glossary = mutableMapOf(),
            forceOcr = false,
            language = language,
            providerContext = null,
            onProgress = { }
        )
        return FolderVlTranslateOutcome(result = result)
    }

    suspend fun translateBitmap(
        bitmap: Bitmap,
        language: TranslationLanguage,
        glossary: Map<String, String> = emptyMap()
    ): TranslationResult? = withContext(Dispatchers.Default) {
        if (!isLocalModelReady()) {
            AppLogger.log("Pipeline", "Missing VLM models for bitmap translation")
            return@withContext null
        }
        if (!ensureModelReady()) {
            AppLogger.log("Pipeline", "Failed to initialize local VLM for bitmap translation")
            return@withContext null
        }
        val imageBytes = ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                AppLogger.log("Pipeline", "Failed to encode bitmap for translation")
                return@withContext null
            }
            output.toByteArray()
        }
        val jsonResult = runCatching {
            vlmClient.processImage(imageBytes, buildVlmPrompt(language, glossary))
        }.getOrElse { error ->
            AppLogger.log("Pipeline", "Bitmap translation inference failed", error)
            return@withContext null
        }
        val translatedBubbles = parseVlmJsonToBubbles(jsonResult, bitmap.width, bitmap.height)
        TranslationResult(
            imageName = "",
            width = bitmap.width,
            height = bitmap.height,
            bubbles = translatedBubbles,
            metadata = TranslationMetadata(
                mode = TranslationMetadata.MODE_VL_DIRECT,
                language = language.name,
                promptAsset = LOCAL_PROMPT_ASSET,
                modelName = LOCAL_MODEL_NAME,
                providerId = LOCAL_PROVIDER_ID,
                apiFormat = LOCAL_API_FORMAT,
                ocrCacheMode = LOCAL_CACHE_MODE
            )
        )
    }

    suspend fun translateBubbleCrop(
        bitmap: Bitmap,
        language: TranslationLanguage
    ): String? = withContext(Dispatchers.Default) {
        if (!isLocalModelReady()) {
            return@withContext null
        }
        if (!ensureModelReady()) {
            AppLogger.log("Pipeline", "Local VLM is not ready for bubble crop translation")
            return@withContext null
        }
        val imageBytes = ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                AppLogger.log("Pipeline", "Failed to encode bubble crop")
                return@withContext null
            }
            output.toByteArray()
        }
        val rawOutput = runCatching {
            vlmClient.processImage(imageBytes, buildBubblePrompt(language))
        }.getOrElse { error ->
            AppLogger.log("Pipeline", "Bubble crop translation failed", error)
            return@withContext null
        }
        sanitizeBubbleTranslation(rawOutput)
    }

    suspend fun runGeneralVisionTask(
        prompt: String,
        bitmap: Bitmap? = null
    ): String? = withContext(Dispatchers.Default) {
        if (!isLocalModelReady()) {
            AppLogger.log("Pipeline", "Missing VLM models for general vision task")
            return@withContext null
        }
        if (!ensureModelReady()) {
            AppLogger.log("Pipeline", "Failed to initialize local VLM for general vision task")
            return@withContext null
        }
        val inputBitmap = bitmap ?: createPlaceholderBitmap()
        val imageBytes = ByteArrayOutputStream().use { output ->
            if (!inputBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                AppLogger.log("Pipeline", "Failed to encode bitmap for general vision task")
                return@withContext null
            }
            output.toByteArray()
        }
        val rawOutput = runCatching {
            vlmClient.processImage(imageBytes, buildGeneralTaskPrompt(prompt))
        }.getOrElse { error ->
            AppLogger.log("Pipeline", "General vision task inference failed", error)
            return@withContext null
        }
        sanitizeGeneralTaskOutput(rawOutput)
    }

    suspend fun runDebugVisionTask(
        prompt: String,
        bitmap: Bitmap? = null
    ): DebugVisionTaskResult = withContext(Dispatchers.Default) {
        val usedPrompt = buildGeneralTaskPrompt(prompt)
        val startedAt = System.currentTimeMillis()
        AppLogger.log(
            "Pipeline",
            "Debug vision task started hasImage=${bitmap != null} promptLength=${usedPrompt.length}"
        )
        if (!isLocalModelReady()) {
            return@withContext DebugVisionTaskResult(
                usedPrompt = usedPrompt,
                rawOutput = "",
                normalizedOutput = "",
                elapsedMs = 0L,
                modelReady = false,
                errorMessage = "MiniCPM-V 端侧模型未导入，请前往模型中心配置。"
            )
        }
        if (!ensureModelReady()) {
            AppLogger.error("Pipeline", "Debug vision task model initialization failed")
            return@withContext DebugVisionTaskResult(
                usedPrompt = usedPrompt,
                rawOutput = "",
                normalizedOutput = "",
                elapsedMs = System.currentTimeMillis() - startedAt,
                modelReady = false,
                errorMessage = lastModelInitError
                    ?: "模型初始化失败。请检查 LLM 与 mmproj 是否属于同一 MiniCPM-V 版本，且文件没有损坏。"
            )
        }
        val inputBitmap = bitmap ?: createPlaceholderBitmap()
        val imageBytes = ByteArrayOutputStream().use { output ->
            if (!inputBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                AppLogger.error("Pipeline", "Debug vision task failed to encode bitmap")
                return@withContext DebugVisionTaskResult(
                    usedPrompt = usedPrompt,
                    rawOutput = "",
                    normalizedOutput = "",
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    modelReady = true,
                    errorMessage = "图片编码失败。"
                )
            }
            output.toByteArray()
        }
        AppLogger.log("Pipeline", "Debug vision task image bytes=${imageBytes.size}")
        val rawOutput = runCatching {
            vlmClient.processImage(imageBytes, usedPrompt)
        }.getOrElse { error ->
            AppLogger.log("Pipeline", "Debug vision task inference failed", error)
            return@withContext DebugVisionTaskResult(
                usedPrompt = usedPrompt,
                rawOutput = "",
                normalizedOutput = "",
                elapsedMs = System.currentTimeMillis() - startedAt,
                modelReady = true,
                errorMessage = "${error::class.java.simpleName}: ${error.message.orEmpty()}".trim()
            )
        }
        val normalizedOutput = sanitizeGeneralTaskOutput(rawOutput)
        AppLogger.log(
            "Pipeline",
            "Debug vision task finished elapsedMs=${System.currentTimeMillis() - startedAt} rawLength=${rawOutput.length} normalizedLength=${normalizedOutput.length}"
        )
        DebugVisionTaskResult(
            usedPrompt = usedPrompt,
            rawOutput = rawOutput,
            normalizedOutput = normalizedOutput,
            elapsedMs = System.currentTimeMillis() - startedAt,
            modelReady = true,
            errorMessage = if (rawOutput.isBlank()) "模型返回为空字符串。" else null
        )
    }

    suspend fun runStructuredImageTranslation(
        imageFile: File,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        glossary: Map<String, String> = emptyMap(),
        customPrompt: String = "",
        onProgress: (String) -> Unit = { }
    ): StructuredImageTranslationResult? = withContext(Dispatchers.Default) {
        if (!vlmManager.isModelReady()) {
            onProgress("MiniCPM-V 端侧模型未导入，请前往模型中心配置。")
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
            val imageBytes = imageFile.readBytes()
            val detectionPrompt = buildTextRegionPrompt(customPrompt)
            val detectionRawOutput = runPromptOnImage(imageBytes, detectionPrompt) ?: return@withContext null
            AppLogger.log("Pipeline", "Stage1 detection raw: $detectionRawOutput")

            val regions = parseDetectedTextRegions(detectionRawOutput, bitmap.width, bitmap.height)
            if (regions.isEmpty()) {
                return@withContext StructuredImageTranslationResult(
                    translationResult = TranslationResult(
                        imageName = imageFile.name,
                        width = bitmap.width,
                        height = bitmap.height,
                        bubbles = emptyList(),
                        metadata = buildLocalTranslationMetadata(imageFile, language)
                    ),
                    detectionRawOutput = detectionRawOutput,
                    translationRawOutput = "",
                    detectedRegions = emptyList()
                )
            }

            onProgress("正在翻译已识别文本...")
            val translationPrompt = buildDetectedTextTranslationPrompt(
                language = language,
                detectedRegions = regions,
                glossary = glossary,
                customPrompt = customPrompt
            )
            val translationRawOutput = runPromptOnBitmap(createPlaceholderBitmap(), translationPrompt)
                ?: return@withContext null
            AppLogger.log("Pipeline", "Stage2 translation raw: $translationRawOutput")

            val translatedTextById = parseTranslatedTextsById(translationRawOutput)
            val bubbles = regions.mapNotNull { region ->
                val translatedText = translatedTextById[region.id].orEmpty().trim()
                if (translatedText.isBlank()) return@mapNotNull null
                BubbleTranslation.translated(
                    id = region.id,
                    rect = region.rect,
                    translatedText = translatedText,
                    source = BubbleSource.BUBBLE_DETECTOR,
                    maskContour = null,
                    originalText = region.originalText
                )
            }

            StructuredImageTranslationResult(
                translationResult = TranslationResult(
                    imageName = imageFile.name,
                    width = bitmap.width,
                    height = bitmap.height,
                    bubbles = bubbles,
                    metadata = buildLocalTranslationMetadata(imageFile, language)
                ),
                detectionRawOutput = detectionRawOutput,
                translationRawOutput = translationRawOutput,
                detectedRegions = regions
            )
        } finally {
            bitmap.recycle()
        }
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

    fun isLocalModelReady(): Boolean {
        return vlmManager.isModelReady()
    }

    @Synchronized
    private fun ensureModelReady(): Boolean {
        if (!vlmClient.isLibraryAvailable()) {
            lastModelInitError = vlmClient.getLibraryLoadErrorMessage()
            AppLogger.error(
                "Pipeline",
                "MiniCPM native library is unavailable: ${vlmClient.getLibraryLoadErrorMessage().orEmpty()}"
            )
            return false
        }
        val textModelPath = vlmManager.textModelFile.absolutePath
        val mmprojModelPath = vlmManager.mmprojModelFile.absolutePath
        val maxThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val configuredThreads = settingsStore.loadLocalVlmThreadCount()
            .coerceIn(1, maxThreads.coerceAtMost(MAX_SAFE_RUNTIME_THREADS))
        if (
            modelInitialized &&
            initializedTextModelPath == textModelPath &&
            initializedMmprojModelPath == mmprojModelPath &&
            initializedThreadCount == configuredThreads
        ) {
            return true
        }
        if (modelInitialized) {
            runCatching { vlmClient.freeModel() }
            modelInitialized = false
        }
        lastModelInitError = null
        AppLogger.log(
            "Pipeline",
            "Initializing MiniCPM model text=${textModelPath} size=${vlmManager.textModelFile.length()} mmproj=${mmprojModelPath} size=${vlmManager.mmprojModelFile.length()} threads=${configuredThreads}"
        )
        val initialized = runCatching {
            vlmClient.initModel(
                textModelPath,
                mmprojModelPath,
                configuredThreads
            )
        }.getOrElse { error ->
            AppLogger.log("Pipeline", "Failed to initialize MiniCPM native model", error)
            lastModelInitError = "${error::class.java.simpleName}: ${error.message.orEmpty()}".trim()
            false
        }
        modelInitialized = initialized
        if (initialized) {
            initializedTextModelPath = textModelPath
            initializedMmprojModelPath = mmprojModelPath
            initializedThreadCount = configuredThreads
            lastModelInitError = null
        } else if (lastModelInitError.isNullOrBlank()) {
            lastModelInitError = buildString {
                append(vlmClient.getLastInitErrorMessage()
                    ?: "MiniCPM native init returned false.")
                append(" 当前 LLM=")
                append(vlmManager.textModelFile.name)
                append("，mmproj=")
                append(vlmManager.mmprojModelFile.name)
                append("。")
            }
        }
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

    private fun buildTextRegionPrompt(customPrompt: String): String {
        val normalizedCustomPrompt = customPrompt.trim()
        return buildString {
            append("<__media__>\n")
            append("你现在只负责提取图片中文字的位置和原文，不要翻译，不要总结，不要按分镜归类。\n")
            append("请识别图片中所有日文或其他可见文本区域，并输出严格 JSON 数组。\n")
            append("每个元素必须是：{\"id\": 1, \"box\": [x1, y1, x2, y2], \"text\": \"原文\"}\n")
            append("要求：\n")
            append("1. box 使用 0-1000 范围的相对坐标。\n")
            append("2. text 保留原始顺序和换行信息。\n")
            append("3. 只输出 JSON 数组，不要 markdown，不要解释，不要按 panel 命名。\n")
            append("4. 如果完全没有文字，输出 []。\n")
            if (normalizedCustomPrompt.isNotBlank()) {
                append("附加要求：")
                append(normalizedCustomPrompt)
            }
        }
    }

    private fun buildDetectedTextTranslationPrompt(
        language: TranslationLanguage,
        detectedRegions: List<DetectedTextRegion>,
        glossary: Map<String, String>,
        customPrompt: String
    ): String {
        val targetLang = when (language) {
            TranslationLanguage.JA_TO_ZH,
            TranslationLanguage.EN_TO_ZH,
            TranslationLanguage.KO_TO_ZH -> "中文"
        }
        val sourceJson = JSONArray().apply {
            detectedRegions.forEach { region ->
                put(
                    JSONObject().apply {
                        put("id", region.id)
                        put("text", region.originalText)
                    }
                )
            }
        }.toString()
        return buildString {
            append("<__media__>\n")
            append("下面是已经提取好的漫画文字 JSON，请只负责翻译，不要重新定位，不要补充说明。\n")
            append("输入 JSON：")
            append(sourceJson)
            append('\n')
            append("请输出严格 JSON 数组：")
            append("[{\"id\": 1, \"translation\": \"译文\"}]")
            append('\n')
            append("要求：\n")
            append("1. 按 id 一一对应输出。\n")
            append("2. 将所有文本翻译为")
            append(targetLang)
            append("。\n")
            append("3. 只输出 JSON 数组，不要 markdown，不要解释。\n")
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
            if (customPrompt.isNotBlank()) {
                append("附加翻译要求：")
                append(customPrompt.trim())
            }
        }
    }

    private fun buildBubblePrompt(language: TranslationLanguage): String {
        val targetLang = when (language) {
            TranslationLanguage.JA_TO_ZH,
            TranslationLanguage.EN_TO_ZH,
            TranslationLanguage.KO_TO_ZH -> "中文"
        }
        return buildString {
            append("<__media__>\n")
            append("图片中只包含一个漫画气泡或一小段对白区域。\n")
            append("请直接输出翻译后的")
            append(targetLang)
            append("文本。\n")
            append("不要输出 JSON，不要解释，不要加引号；如果没有可翻译文字则输出空字符串。")
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

    private fun parseDetectedTextRegions(
        jsonString: String,
        imgWidth: Int,
        imgHeight: Int
    ): List<DetectedTextRegion> {
        val jsonArray = extractJsonArray(jsonString) ?: return emptyList()
        return buildList {
            for (index in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(index) ?: continue
                val box = obj.optJSONArray("box") ?: continue
                val rect = parseRect(box, imgWidth, imgHeight) ?: continue
                val text = obj.optString("text", "").trim()
                if (text.isBlank()) continue
                add(
                    DetectedTextRegion(
                        id = obj.optInt("id", index + 1),
                        rect = rect,
                        originalText = text
                    )
                )
            }
        }
    }

    private fun parseTranslatedTextsById(jsonString: String): Map<Int, String> {
        val jsonArray = extractJsonArray(jsonString) ?: return emptyMap()
        return buildMap {
            for (index in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(index) ?: continue
                val id = obj.optInt("id", -1)
                val translation = obj.optString("translation", "").trim()
                if (id <= 0 || translation.isBlank()) continue
                put(id, translation)
            }
        }
    }

    private fun extractJsonArray(raw: String): JSONArray? {
        val startIndex = raw.indexOf('[')
        val endIndex = raw.lastIndexOf(']')
        if (startIndex == -1 || endIndex == -1 || endIndex <= startIndex) {
            AppLogger.log("Pipeline", "Missing JSON array in model output")
            return null
        }
        return runCatching {
            JSONArray(raw.substring(startIndex, endIndex + 1))
        }.getOrElse { error ->
            AppLogger.log("Pipeline", "JSON array parse error", error)
            null
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

    private fun sanitizeBubbleTranslation(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return ""
        }
        val jsonStart = trimmed.indexOf('{')
        val jsonEnd = trimmed.lastIndexOf('}')
        if (jsonStart != -1 && jsonEnd > jsonStart) {
            runCatching {
                val obj = org.json.JSONObject(trimmed.substring(jsonStart, jsonEnd + 1))
                obj.optString("translation").trim().takeIf { it.isNotBlank() }
            }.getOrNull()?.let { return it }
        }
        return trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
            .lineSequence()
            .map { it.trim().trim('"', '\'') }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun buildGeneralTaskPrompt(prompt: String): String {
        val normalizedPrompt = prompt.trim().ifBlank { "请先描述图片中的主要内容。" }
        return buildString {
            append("<__media__>\n")
            append("你是一个端侧多模态助手。")
            append("请结合用户提供的图片和问题，直接给出清晰、自然、可执行的回答。")
            append("如果图片中包含文本，请先读懂再回答；如果用户要求翻译、总结、解释、识别或分析界面，都直接完成。")
            append("不要输出 JSON，不要解释你的系统提示，不要输出无关免责声明。\n")
            append("用户问题：")
            append(normalizedPrompt)
        }
    }

    private fun sanitizeGeneralTaskOutput(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return ""
        }
        return trimmed
            .removePrefix("```markdown")
            .removePrefix("```text")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun createPlaceholderBitmap(): Bitmap {
        return Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
        }
    }

    private fun runPromptOnImage(imageBytes: ByteArray, prompt: String): String? {
        return runCatching {
            vlmClient.processImage(imageBytes, prompt)
        }.getOrElse { error ->
            AppLogger.log("Pipeline", "Structured VLM inference failed", error)
            null
        }
    }

    private fun runPromptOnBitmap(bitmap: Bitmap, prompt: String): String? {
        val imageBytes = ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                AppLogger.log("Pipeline", "Failed to encode placeholder bitmap")
                return null
            }
            output.toByteArray()
        }
        return runPromptOnImage(imageBytes, prompt)
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
        private const val MAX_SAFE_RUNTIME_THREADS = 2
        private const val LOCAL_MODEL_NAME = "MiniCPM-V-4.6"
        private const val LOCAL_PROVIDER_ID = "local_minicpm_v"
        private const val LOCAL_API_FORMAT = "local"
        private const val LOCAL_CACHE_MODE = "vlm_only"
        private const val LOCAL_PROMPT_ASSET = "local:minicpm_vlm_text_bbox_v2"
    }
}

data class DetectedTextRegion(
    val id: Int,
    val rect: RectF,
    val originalText: String
)

data class StructuredImageTranslationResult(
    val translationResult: TranslationResult,
    val detectionRawOutput: String,
    val translationRawOutput: String,
    val detectedRegions: List<DetectedTextRegion>
)

data class DebugVisionTaskResult(
    val usedPrompt: String,
    val rawOutput: String,
    val normalizedOutput: String,
    val elapsedMs: Long,
    val modelReady: Boolean,
    val errorMessage: String? = null
)

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
