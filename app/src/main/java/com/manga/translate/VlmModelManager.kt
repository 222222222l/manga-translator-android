package com.manga.translate

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class VlmModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val settingsStore = SettingsStore(appContext)
    private val modelDir: File = File(
        appContext.getExternalFilesDir(null) ?: appContext.filesDir,
        MODEL_DIR_NAME
    ).apply {
        if (!exists()) {
            mkdirs()
        }
    }

    enum class ModelPrecision(
        val prefValue: String,
        val displayName: String,
        val fileName: String,
        val downloadUrl: String
    ) {
        F16(
            prefValue = "f16",
            displayName = "F16",
            fileName = "MiniCPM-V-4_6-F16.gguf",
            downloadUrl = "https://www.modelscope.cn/models/OpenBMB/MiniCPM-V-4.6-gguf/resolve/master/MiniCPM-V-4_6-F16.gguf"
        ),
        Q4_0(
            prefValue = "q4_0",
            displayName = "Q4_0",
            fileName = "MiniCPM-V-4_6-Q4_0.gguf",
            downloadUrl = "https://www.modelscope.cn/models/OpenBMB/MiniCPM-V-4.6-gguf/resolve/master/MiniCPM-V-4_6-Q4_0.gguf"
        ),
        Q6_K(
            prefValue = "q6_k",
            displayName = "Q6_K",
            fileName = "MiniCPM-V-4_6-Q6_K.gguf",
            downloadUrl = "https://www.modelscope.cn/models/OpenBMB/MiniCPM-V-4.6-gguf/resolve/master/MiniCPM-V-4_6-Q6_K.gguf"
        ),
        Q8_0(
            prefValue = "q8_0",
            displayName = "Q8_0",
            fileName = "MiniCPM-V-4_6-Q8_0.gguf",
            downloadUrl = "https://www.modelscope.cn/models/OpenBMB/MiniCPM-V-4.6-gguf/resolve/master/MiniCPM-V-4_6-Q8_0.gguf"
        );

        companion object {
            fun fromPref(value: String?): ModelPrecision {
                return entries.firstOrNull { it.prefValue == value } ?: Q4_0
            }
        }
    }

    val textModelFile: File
        get() = getManagedTextModelFile(getSelectedPrecision())

    val mmprojModelFile: File
        get() = File(modelDir, MMPROJ_FILE_NAME)

    fun getModelDirectoryPath(): String = modelDir.absolutePath

    fun getSelectedPrecision(): ModelPrecision {
        return ModelPrecision.fromPref(settingsStore.loadLocalVlmModelPrecision())
    }

    fun setSelectedPrecision(precision: ModelPrecision) {
        settingsStore.saveLocalVlmModelPrecision(precision.prefValue)
    }

    fun getManagedTextModelFile(precision: ModelPrecision): File {
        return File(modelDir, precision.fileName)
    }

    fun listAvailablePrecisions(): List<ModelPrecision> {
        return ModelPrecision.entries.filter { getManagedTextModelFile(it).exists() }
    }

    fun isModelReady(): Boolean {
        return textModelFile.exists() && mmprojModelFile.exists()
    }

    suspend fun importModelFromUri(uri: Uri, isMmproj: Boolean): Boolean = withContext(Dispatchers.IO) {
        val fileName = getFileName(uri)
        val inferredPrecision = inferPrecisionFromName(fileName)
        val targetFile = when {
            isMmproj -> mmprojModelFile
            inferredPrecision != null -> getManagedTextModelFile(inferredPrecision)
            else -> textModelFile
        }
        val imported = copyUriToFile(uri, targetFile)
        if (imported && !isMmproj && inferredPrecision != null) {
            setSelectedPrecision(inferredPrecision)
        }
        imported
    }

    suspend fun downloadTextModel(
        precision: ModelPrecision,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        val downloaded = downloadToFile(
            url = precision.downloadUrl,
            targetFile = getManagedTextModelFile(precision),
            onProgress = onProgress
        )
        if (downloaded) {
            setSelectedPrecision(precision)
        }
        downloaded
    }

    suspend fun downloadMmprojModel(
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext downloadToFile(
            url = MMPROJ_DOWNLOAD_URL,
            targetFile = mmprojModelFile,
            onProgress = onProgress
        )
    }

    private fun copyUriToFile(uri: Uri, targetFile: File): Boolean {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
        return try {
            appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    copyStream(inputStream, outputStream, totalBytes = -1L) { _, _ -> }
                }
            } ?: return false
            if (targetFile.exists()) {
                targetFile.delete()
            }
            tempFile.renameTo(targetFile)
        } catch (error: Exception) {
            AppLogger.log("VlmModelManager", "Failed to import model from $uri", error)
            tempFile.delete()
            false
        }
    }

    private fun downloadToFile(
        url: String,
        targetFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Boolean {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.download")
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
            connection.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.connect()
            if (connection.responseCode !in 200..299) {
                AppLogger.log(
                    "VlmModelManager",
                    "Model download failed: HTTP ${connection.responseCode} for $url"
                )
                connection.disconnect()
                tempFile.delete()
                return false
            }
            val totalBytes = connection.contentLengthLong
            connection.inputStream.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    copyStream(inputStream, outputStream, totalBytes, onProgress)
                }
            }
            connection.disconnect()
            if (targetFile.exists()) {
                targetFile.delete()
            }
            tempFile.renameTo(targetFile)
        } catch (error: Exception) {
            AppLogger.log("VlmModelManager", "Model download failed for $url", error)
            tempFile.delete()
            false
        }
    }

    private fun copyStream(
        input: InputStream,
        output: FileOutputStream,
        totalBytes: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var length: Int
        var downloadedBytes = 0L
        while (input.read(buffer).also { length = it } > 0) {
            output.write(buffer, 0, length)
            downloadedBytes += length
            onProgress(downloadedBytes, totalBytes)
        }
        output.flush()
    }

    private fun inferPrecisionFromName(fileName: String): ModelPrecision? {
        val normalized = fileName.lowercase()
        return when {
            "q4_0" in normalized -> ModelPrecision.Q4_0
            "q6_k" in normalized -> ModelPrecision.Q6_K
            "q8_0" in normalized -> ModelPrecision.Q8_0
            "f16" in normalized -> ModelPrecision.F16
            else -> null
        }
    }

    fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "unknown.gguf"
    }

    companion object {
        private const val MODEL_DIR_NAME = "Minicpm-model"
        private const val MMPROJ_FILE_NAME = "mmproj-model-f16.gguf"
        private const val MMPROJ_DOWNLOAD_URL =
            "https://www.modelscope.cn/models/OpenBMB/MiniCPM-V-4.6-gguf/resolve/master/mmproj-model-f16.gguf"
        private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 30_000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 120_000
    }
}
