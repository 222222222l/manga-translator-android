package com.manga.translate

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        binding.settingsHubImportTextButton.setOnClickListener {
            importTextModelLauncher.launch("*/*")
        }
        binding.settingsHubImportMmprojButton.setOnClickListener {
            importMmprojModelLauncher.launch("*/*")
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
        updateModelStatus()
    }

    override fun onPause() {
        super.onPause()
        if (_binding != null) {
            saveThreadCount(showToast = false)
        }
    }

    private fun handleModelImport(uri: Uri, isMmproj: Boolean) {
        lifecycleScope.launch {
            val success = vlmModelManager.importModelFromUri(uri, isMmproj)
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
        val textReady = vlmModelManager.textModelFile.exists()
        val mmprojReady = vlmModelManager.mmprojModelFile.exists()
        binding.settingsHubStatusText.text = getString(
            if (textReady && mmprojReady) {
                R.string.model_status_ready
            } else {
                R.string.model_status_missing
            }
        )
        binding.settingsHubTextModelStatus.text = if (textReady) {
            getString(
                R.string.model_status_file_ready,
                vlmModelManager.textModelFile.name,
                vlmModelManager.textModelFile.length() / 1024 / 1024
            )
        } else {
            getString(R.string.model_status_text_missing)
        }
        binding.settingsHubMmprojModelStatus.text = if (mmprojReady) {
            getString(
                R.string.model_status_file_ready,
                vlmModelManager.mmprojModelFile.name,
                vlmModelManager.mmprojModelFile.length() / 1024 / 1024
            )
        } else {
            getString(R.string.model_status_mmproj_missing)
        }
        binding.settingsHubThreadsInput.setText(settingsStore.loadLocalVlmThreadCount().toString())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
