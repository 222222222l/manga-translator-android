package com.manga.translate

class LocalVlmClient {

    companion object {
        @Volatile
        private var libraryLoaded = false

        @Volatile
        private var libraryLoadAttempted = false

        @Volatile
        private var libraryLoadError: Throwable? = null
    }

    fun isLibraryAvailable(): Boolean {
        loadLibraryIfNeeded()
        return libraryLoaded
    }

    fun getLibraryLoadErrorMessage(): String? {
        val error = libraryLoadError ?: return null
        return "${error::class.java.simpleName}: ${error.message.orEmpty()}".trim()
    }

    fun initModel(modelPath: String, mmprojPath: String, numThreads: Int): Boolean {
        ensureLibraryLoaded()
        return nativeInitModel(modelPath, mmprojPath, numThreads)
    }

    fun getLastInitErrorMessage(): String? {
        if (!libraryLoaded) return getLibraryLoadErrorMessage()
        return nativeGetLastErrorMessage()
    }

    fun freeModel() {
        if (!libraryLoaded) return
        nativeFreeModel()
    }

    fun processImage(imageBytes: ByteArray, prompt: String): String {
        ensureLibraryLoaded()
        return nativeProcessImage(imageBytes, prompt)
    }

    @Synchronized
    private fun loadLibraryIfNeeded() {
        if (libraryLoaded || libraryLoadAttempted) return
        libraryLoadAttempted = true
        runCatching {
            System.loadLibrary("minicpm_v_jni")
        }.onSuccess {
            libraryLoaded = true
        }.onFailure { error ->
            libraryLoadError = error
            AppLogger.error("LocalVlmClient", "Failed to load minicpm_v_jni", error)
        }
    }

    private fun ensureLibraryLoaded() {
        loadLibraryIfNeeded()
        if (libraryLoaded) return
        throw IllegalStateException(
            getLibraryLoadErrorMessage()
                ?: "Native library minicpm_v_jni is not available."
        )
    }

    private external fun nativeInitModel(modelPath: String, mmprojPath: String, numThreads: Int): Boolean

    private external fun nativeGetLastErrorMessage(): String?

    private external fun nativeFreeModel()

    private external fun nativeProcessImage(imageBytes: ByteArray, prompt: String): String
}
