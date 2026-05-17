package com.manga.translate

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.manga.translate.databinding.FragmentReadingHostBinding

class ReadingHostFragment : Fragment() {
    private var _binding: FragmentReadingHostBinding? = null
    private val binding get() = _binding!!
    private val readingSessionViewModel: ReadingSessionViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReadingHostBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.readingHostGoLibraryButton.setOnClickListener {
            (activity as? MainActivity)?.switchToTab(MainPagerAdapter.LIBRARY_INDEX)
        }
        readingSessionViewModel.images.observe(viewLifecycleOwner) {
            renderSafely()
        }
        readingSessionViewModel.currentFolder.observe(viewLifecycleOwner) {
            renderSafely()
        }
    }

    private fun renderContent() {
        val hasSession = readingSessionViewModel.currentFolder.value != null &&
            readingSessionViewModel.images.value.orEmpty().isNotEmpty()
        binding.readingHostPlaceholder.isVisible = !hasSession
        binding.readingHostContainer.isVisible = hasSession
        binding.readingHostGoLibraryButton.isGone = hasSession
        if (hasSession) {
            attachReaderIfNeeded()
        }
    }

    private fun attachReaderIfNeeded() {
        val tag = "reading_content"
        val existing = childFragmentManager.findFragmentByTag(tag)
        if (existing != null) return
        if (!isAdded || childFragmentManager.isStateSaved) return
        childFragmentManager.beginTransaction()
            .replace(R.id.reading_host_container, ReadingFragment(), tag)
            .commitAllowingStateLoss()
    }

    private fun renderSafely() {
        runCatching {
            renderContent()
        }.onFailure { error ->
            AppLogger.log("ReadingHostFragment", "Failed to render reading host", error)
            if (_binding != null) {
                binding.readingHostPlaceholder.isVisible = true
                binding.readingHostContainer.isGone = true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
