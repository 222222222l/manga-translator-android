package com.manga.translate

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class FreshModelCenterActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var dirView: TextView
    private lateinit var threadsInput: EditText
    private lateinit var precisionSpinner: Spinner
    private lateinit var manager: VlmModelManager
    private lateinit var settingsStore: SettingsStore

    private val importLlmLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            importModel(uri, false)
        }
    }

    private val importMmprojLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            importModel(uri, true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fresh_model_center)

        manager = VlmModelManager(applicationContext)
        settingsStore = SettingsStore(applicationContext)
        statusView = findViewById(R.id.fresh_model_center_status)
        dirView = findViewById(R.id.fresh_model_center_dir)
        threadsInput = findViewById(R.id.fresh_model_center_threads)
        precisionSpinner = findViewById(R.id.fresh_model_center_precision)

        val precisions = VlmModelManager.ModelPrecision.entries
        precisionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            precisions.map { it.displayName }
        )
        precisionSpinner.setSelection(precisions.indexOf(manager.getSelectedPrecision()).coerceAtLeast(0))

        findViewById<Button>(R.id.fresh_model_center_download_llm).setOnClickListener {
            val precision = precisions[precisionSpinner.selectedItemPosition]
            manager.setSelectedPrecision(precision)
            lifecycleScope.launch {
                statusView.text = getString(R.string.fresh_model_center_downloading, precision.displayName)
                val ok = manager.downloadTextModel(precision)
                statusView.text = if (ok) {
                    getString(R.string.fresh_model_center_llm_ready, precision.displayName)
                } else {
                    getString(R.string.fresh_model_center_download_failed)
                }
            }
        }
        findViewById<Button>(R.id.fresh_model_center_import_llm).setOnClickListener {
            importLlmLauncher.launch("*/*")
        }
        findViewById<Button>(R.id.fresh_model_center_download_mmproj).setOnClickListener {
            lifecycleScope.launch {
                statusView.text = getString(R.string.fresh_model_center_downloading_mmproj)
                val ok = manager.downloadMmprojModel()
                statusView.text = if (ok) {
                    getString(R.string.fresh_model_center_mmproj_ready)
                } else {
                    getString(R.string.fresh_model_center_download_failed)
                }
            }
        }
        findViewById<Button>(R.id.fresh_model_center_import_mmproj).setOnClickListener {
            importMmprojLauncher.launch("*/*")
        }
        findViewById<Button>(R.id.fresh_model_center_save_threads).setOnClickListener {
            val value = threadsInput.text?.toString()?.toIntOrNull()
            if (value == null) {
                Toast.makeText(this, R.string.fresh_model_center_threads_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            settingsStore.saveLocalVlmThreadCount(value)
            val savedValue = settingsStore.loadLocalVlmThreadCount()
            statusView.text = getString(R.string.fresh_model_center_threads_saved, savedValue)
            threadsInput.setText(savedValue.toString())
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun importModel(uri: android.net.Uri, isMmproj: Boolean) {
        lifecycleScope.launch {
            statusView.text = getString(R.string.fresh_model_center_importing)
            val ok = manager.importModelFromUri(uri, isMmproj)
            statusView.text = if (ok) {
                getString(R.string.fresh_model_center_import_done)
            } else {
                getString(R.string.fresh_model_center_import_failed)
            }
            refreshUi()
        }
    }

    private fun refreshUi() {
        dirView.text = getString(R.string.fresh_model_center_dir, manager.getModelDirectoryPath())
        threadsInput.setText(settingsStore.loadLocalVlmThreadCount().toString())
        val precision = manager.getSelectedPrecision()
        val llmState = if (manager.textModelFile.exists()) {
            getString(R.string.fresh_model_center_llm_ready, precision.displayName)
        } else {
            getString(R.string.fresh_model_center_llm_missing, precision.displayName)
        }
        val mmprojState = if (manager.mmprojModelFile.exists()) {
            getString(R.string.fresh_model_center_mmproj_ready)
        } else {
            getString(R.string.fresh_model_center_mmproj_missing)
        }
        statusView.text = "$llmState\n$mmprojState"
    }
}
