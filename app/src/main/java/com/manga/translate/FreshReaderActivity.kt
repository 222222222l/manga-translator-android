package com.manga.translate

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FreshReaderActivity : AppCompatActivity() {
    private lateinit var repository: LibraryRepository
    private lateinit var pipeline: TranslationPipeline
    private lateinit var renderer: BubbleRenderer
    private lateinit var settingsStore: SettingsStore
    private lateinit var imageView: ImageView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var toggleButton: Button
    private var pageFiles: List<File> = emptyList()
    private var currentIndex = 0
    private var currentOriginalBitmap: Bitmap? = null
    private var currentRenderedBitmap: Bitmap? = null
    private var showTranslated = true
    private var renderJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fresh_reader)

        repository = LibraryRepository(applicationContext)
        pipeline = TranslationPipeline(applicationContext)
        renderer = BubbleRenderer(applicationContext)
        settingsStore = SettingsStore(applicationContext)
        imageView = findViewById(R.id.fresh_reader_image)
        titleView = findViewById(R.id.fresh_reader_title)
        statusView = findViewById(R.id.fresh_reader_status)
        toggleButton = findViewById(R.id.fresh_reader_toggle_button)

        val folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH)
        if (folderPath.isNullOrBlank()) {
            finish()
            return
        }
        val folder = File(folderPath)
        pageFiles = repository.listImages(folder)
        if (pageFiles.isEmpty()) {
            finish()
            return
        }
        currentIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, pageFiles.lastIndex)

        findViewById<Button>(R.id.fresh_reader_prev_button).setOnClickListener {
            if (currentIndex > 0) {
                currentIndex -= 1
                loadCurrentPage()
            }
        }
        findViewById<Button>(R.id.fresh_reader_next_button).setOnClickListener {
            if (currentIndex < pageFiles.lastIndex) {
                currentIndex += 1
                loadCurrentPage()
            }
        }
        toggleButton.setOnClickListener {
            showTranslated = !showTranslated
            updateDisplayedBitmap()
        }

        loadCurrentPage()
    }

    override fun onDestroy() {
        renderJob?.cancel()
        super.onDestroy()
    }

    private fun loadCurrentPage() {
        renderJob?.cancel()
        val imageFile = pageFiles[currentIndex]
        titleView.text = getString(R.string.fresh_reader_title_format, imageFile.parentFile?.name ?: "", currentIndex + 1, pageFiles.size)
        statusView.text = getString(R.string.fresh_reader_loading)
        renderJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { buildRenderedPage(imageFile) }
            currentOriginalBitmap = result.original
            currentRenderedBitmap = result.rendered
            showTranslated = result.rendered != null
            statusView.text = result.status
            updateDisplayedBitmap()
        }
    }

    private fun updateDisplayedBitmap() {
        val translatedAvailable = currentRenderedBitmap != null
        toggleButton.text = if (showTranslated) {
            getString(R.string.fresh_reader_show_original)
        } else {
            getString(R.string.fresh_reader_show_translated)
        }
        toggleButton.isEnabled = translatedAvailable
        imageView.setImageBitmap(
            if (showTranslated && translatedAvailable) currentRenderedBitmap else currentOriginalBitmap
        )
    }

    private suspend fun buildRenderedPage(imageFile: File): RenderedPage {
        val original = BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: return RenderedPage(null, null, "图片加载失败")
        val language = TranslationLanguage.JA_TO_ZH
        val cached = pipeline.loadValidTranslation(
            imageFile = imageFile,
            fullTranslate = false,
            useVlDirectTranslate = false,
            language = language
        )
        val translation = cached ?: if (pipeline.isLocalModelReady()) {
            pipeline.translateImage(
                imageFile = imageFile,
                glossary = mutableMapOf(),
                forceOcr = false,
                language = language,
                providerContext = null,
                onProgress = { }
            )?.also { pipeline.saveResult(imageFile, it) }
        } else {
            null
        }
        val rendered = translation?.let {
            renderer.render(
                source = original,
                translation = it,
                verticalLayoutEnabled = !settingsStore.loadNormalBubbleRenderSettings().useHorizontalText
            )
        }
        val status = when {
            rendered != null -> getString(R.string.fresh_reader_done, currentIndex + 1, pageFiles.size)
            !pipeline.isLocalModelReady() -> getString(R.string.fresh_reader_model_missing)
            else -> getString(R.string.fresh_reader_failed)
        }
        return RenderedPage(original, rendered, status)
    }

    private data class RenderedPage(
        val original: Bitmap?,
        val rendered: Bitmap?,
        val status: String
    )

    companion object {
        private const val EXTRA_FOLDER_PATH = "extra_folder_path"
        private const val EXTRA_START_INDEX = "extra_start_index"

        fun createIntent(context: Context, folder: File, startIndex: Int = 0): Intent {
            return Intent(context, FreshReaderActivity::class.java)
                .putExtra(EXTRA_FOLDER_PATH, folder.absolutePath)
                .putExtra(EXTRA_START_INDEX, startIndex)
        }
    }
}
