package com.manga.translate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class FreshImageTaskActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var resultView: ImageView
    private lateinit var pipeline: TranslationPipeline
    private lateinit var renderer: BubbleRenderer
    private lateinit var settingsStore: SettingsStore
    private var selectedUri: Uri? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        selectedUri = uri
        statusView.text = if (uri == null) {
            getString(R.string.fresh_image_task_no_image)
        } else {
            getString(R.string.fresh_image_task_selected)
        }
        if (uri != null) {
            resultView.setImageURI(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fresh_image_task)

        pipeline = TranslationPipeline(applicationContext)
        renderer = BubbleRenderer(applicationContext)
        settingsStore = SettingsStore(applicationContext)
        statusView = findViewById(R.id.fresh_image_task_status)
        resultView = findViewById(R.id.fresh_image_task_result)

        statusView.text = getString(R.string.fresh_image_task_hint)

        findViewById<Button>(R.id.fresh_image_task_pick_button).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        findViewById<Button>(R.id.fresh_image_task_translate_button).setOnClickListener {
            val uri = selectedUri
            if (uri == null) {
                Toast.makeText(this, R.string.fresh_image_task_no_image, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!pipeline.isLocalModelReady()) {
                statusView.text = getString(R.string.fresh_reader_model_missing)
                return@setOnClickListener
            }
            lifecycleScope.launch {
                statusView.text = getString(R.string.fresh_image_task_running)
                val bitmap = withContext(Dispatchers.IO) { runImageTask(uri) }
                if (bitmap == null) {
                    statusView.text = getString(R.string.fresh_image_task_failed)
                } else {
                    resultView.setImageBitmap(bitmap)
                    statusView.text = getString(R.string.fresh_image_task_done)
                }
            }
        }
    }

    private suspend fun runImageTask(uri: Uri): Bitmap? {
        val tempFile = File(cacheDir, "fresh_image_task_input.png")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
        } ?: return null
        val original = BitmapFactory.decodeFile(tempFile.absolutePath) ?: return null
        val result = pipeline.translateImage(
            imageFile = tempFile,
            glossary = mutableMapOf(),
            forceOcr = false,
            language = TranslationLanguage.JA_TO_ZH,
            providerContext = null,
            onProgress = { }
        ) ?: return null
        return renderer.render(
            source = original,
            translation = result,
            verticalLayoutEnabled = !settingsStore.loadNormalBubbleRenderSettings().useHorizontalText
        )
    }
}
