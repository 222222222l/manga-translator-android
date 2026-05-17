package com.manga.translate

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.manga.translate.databinding.FragmentSettingsHubBinding
import com.manga.translate.di.appContainer
import kotlinx.coroutines.launch

class SettingsHubFragment : Fragment() {
    private var _binding: FragmentSettingsHubBinding? = null
    private val binding get() = _binding!!
    private val appContainer by lazy(LazyThreadSafetyMode.NONE) { requireContext().appContainer }
    private val settingsStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.settingsStore }
    private val vlmModelManager by lazy(LazyThreadSafetyMode.NONE) { VlmModelManager(requireContext()) }

    private val importTextModelLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleModelImport(it, false) }
        }

    private val importMmprojModelLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleModelImport(it, true) }
        }
    private var actionsEnabled = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsHubBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configurePrecisionSelector()
        binding.settingsHubImportTextButton.setOnClickListener {
            importTextModelLauncher.launch("*/*")
        }
        binding.settingsHubImportMmprojButton.setOnClickListener {
            importMmprojModelLauncher.launch("*/*")
        }
        binding.settingsHubDownloadTextButton.setOnClickListener {
            downloadSelectedTextModel()
        }
        binding.settingsHubDownloadMmprojButton.setOnClickListener {
            downloadMmprojModel()
        }
        binding.settingsHubSaveThreadsButton.setOnClickListener {
            saveThreadCount()
        }
        binding.settingsHubOpenGeneralTaskButton.setOnClickListener {
            (activity as? MainActivity)?.switchToTab(MainPagerAdapter.GENERAL_TASK_INDEX)
        }
        binding.settingsHubOpenLibraryButton.setOnClickListener {
            (activity as? MainActivity)?.switchToTab(MainPagerAdapter.LIBRARY_INDEX)
        }
        renderSafely()
    }

    override fun onPause() {
        super.onPause()
        if (_binding != null) {
            runCatching {
                saveThreadCount(showToast = false)
            }.onFailure { error ->
                AppLogger.log("SettingsHubFragment", "Failed to persist settings hub state", error)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            renderSafely()
        }
    }

    private fun handleModelImport(uri: Uri, isMmproj: Boolean) {
        lifecycleScope.launch {
            setActionsEnabled(false)
            val success = vlmModelManager.importModelFromUri(uri, isMmproj)
            setActionsEnabled(true)
            if (!isAdded || _binding == null) return@launch
            if (success) {
                Snackbar.make(
                    binding.root,
                    getString(
                        if (isMmproj) {
                            R.string.model_import_mmproj_success
                        } else {
                            R.string.model_import_text_success
                        },
                        vlmModelManager.getFileName(uri)
                    ),
                    Snackbar.LENGTH_SHORT
                ).show()
                updateModelStatus()
            } else {
                Snackbar.make(binding.root, R.string.model_import_failed, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun downloadSelectedTextModel() {
        val precision = vlmModelManager.getSelectedPrecision()
        lifecycleScope.launch {
            setActionsEnabled(false)
            binding.settingsHubStatusText.text = getString(
                R.string.model_download_running,
                precision.displayName
            )
            val success = vlmModelManager.downloadTextModel(precision)
            if (!isAdded || _binding == null) return@launch
            setActionsEnabled(true)
            if (success) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.model_download_text_success, precision.displayName),
                    Snackbar.LENGTH_LONG
                ).show()
                updateModelStatus()
            } else {
                binding.settingsHubStatusText.text = getString(R.string.model_download_failed)
                Snackbar.make(binding.root, R.string.model_download_failed, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadMmprojModel() {
        lifecycleScope.launch {
            setActionsEnabled(false)
            binding.settingsHubStatusText.text = getString(R.string.model_download_mmproj_running)
            val success = vlmModelManager.downloadMmprojModel()
            if (!isAdded || _binding == null) return@launch
            setActionsEnabled(true)
            if (success) {
                Snackbar.make(binding.root, R.string.model_download_mmproj_success, Snackbar.LENGTH_LONG)
                    .show()
                updateModelStatus()
            } else {
                binding.settingsHubStatusText.text = getString(R.string.model_download_failed)
                Snackbar.make(binding.root, R.string.model_download_failed, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun configurePrecisionSelector() {
        val labels = VlmModelManager.ModelPrecision.entries.map { it.displayName }
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            labels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.settingsHubPrecisionSpinner.adapter = adapter
        val selectedIndex = VlmModelManager.ModelPrecision.entries.indexOf(
            vlmModelManager.getSelectedPrecision()
        ).coerceAtLeast(0)
        binding.settingsHubPrecisionSpinner.setSelection(selectedIndex, false)
        binding.settingsHubPrecisionSpinner.setOnItemSelectedListener(
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val precision = VlmModelManager.ModelPrecision.entries[position]
                    vlmModelManager.setSelectedPrecision(precision)
                    if (_binding != null) {
                        updateModelStatus()
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        )
    }

    private fun saveThreadCount(showToast: Boolean = true) {
        val parsed = binding.settingsHubThreadsInput.text?.toString()?.trim()?.toIntOrNull()
        val maxThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val normalized = (parsed ?: settingsStore.loadLocalVlmThreadCount()).coerceIn(1, maxThreads)
        settingsStore.saveLocalVlmThreadCount(normalized)
        binding.settingsHubThreadsInput.setText(normalized.toString())
        if (showToast) {
            Toast.makeText(
                requireContext(),
                getString(R.string.model_threads_saved, normalized),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateModelStatus() {
        val selectedPrecision = vlmModelManager.getSelectedPrecision()
        val textFile = vlmModelManager.textModelFile
        val mmprojFile = vlmModelManager.mmprojModelFile
        val textReady = textFile.exists()
        val mmprojReady = mmprojFile.exists()
        binding.settingsHubStatusText.text = getString(
            if (textReady && mmprojReady) {
                R.string.model_status_ready
            } else {
                R.string.model_status_missing
            }
        )
        binding.settingsHubModelDirValue.text = vlmModelManager.getModelDirectoryPath()
        binding.settingsHubPrecisionHint.text = getString(
            R.string.settings_hub_precision_current,
            selectedPrecision.displayName
        )
        binding.settingsHubTextModelStatus.text = if (textReady) {
            getString(
                R.string.model_status_file_ready,
                textFile.name,
                textFile.length() / 1024 / 1024
            )
        } else {
            getString(R.string.model_status_text_missing_with_precision, selectedPrecision.displayName)
        }
        binding.settingsHubMmprojModelStatus.text = if (mmprojReady) {
            getString(
                R.string.model_status_file_ready,
                mmprojFile.name,
                mmprojFile.length() / 1024 / 1024
            )
        } else {
            getString(R.string.model_status_mmproj_missing)
        }
        binding.settingsHubThreadsInput.setText(settingsStore.loadLocalVlmThreadCount().toString())
    }

    private fun renderSafely() {
        runCatching {
            updateModelStatus()
        }.onFailure { error ->
            AppLogger.log("SettingsHubFragment", "Failed to render settings hub", error)
            binding.settingsHubStatusText.text = getString(R.string.settings_hub_runtime_fallback)
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        actionsEnabled = enabled
        if (_binding == null) return
        binding.settingsHubImportTextButton.isEnabled = enabled
        binding.settingsHubImportMmprojButton.isEnabled = enabled
        binding.settingsHubDownloadTextButton.isEnabled = enabled
        binding.settingsHubDownloadMmprojButton.isEnabled = enabled
        binding.settingsHubPrecisionSpinner.isEnabled = enabled
        binding.settingsHubSaveThreadsButton.isEnabled = enabled
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
