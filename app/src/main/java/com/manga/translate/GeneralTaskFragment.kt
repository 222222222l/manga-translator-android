package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manga.translate.databinding.FragmentGeneralTaskBinding
import com.manga.translate.di.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class GeneralTaskFragment : Fragment() {
    private var _binding: FragmentGeneralTaskBinding? = null
    private val binding get() = _binding!!
    private val appContainer by lazy(LazyThreadSafetyMode.NONE) { requireContext().appContainer }
    private val translationPipeline by lazy(LazyThreadSafetyMode.NONE) {
        appContainer.createTranslationPipeline()
    }
    private val conversationHistory = mutableListOf<ChatTurn>()
    private var selectedImageUri: Uri? = null
    private var isSending = false

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            selectedImageUri = uri
            updateSelectedImagePreview()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGeneralTaskBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.generalTaskPickImageButton.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        binding.generalTaskClearImageButton.setOnClickListener {
            selectedImageUri = null
            updateSelectedImagePreview()
        }
        binding.generalTaskSendButton.setOnClickListener {
            sendCurrentPrompt()
        }
        binding.generalTaskStatusText.text = getString(
            if (translationPipeline.isLocalModelReady()) {
                R.string.general_task_status_ready
            } else {
                R.string.general_task_status_missing_model
            }
        )
        appendAssistantMessage(getString(R.string.general_task_welcome_message))
        updateSelectedImagePreview()
    }

    private fun sendCurrentPrompt() {
        if (isSending) return
        val prompt = binding.generalTaskInput.text?.toString()?.trim().orEmpty()
        if (prompt.isBlank() && selectedImageUri == null) {
            Toast.makeText(requireContext(), R.string.general_task_empty_input, Toast.LENGTH_SHORT).show()
            return
        }
        if (!translationPipeline.isLocalModelReady()) {
            Toast.makeText(requireContext(), R.string.general_task_status_missing_model, Toast.LENGTH_LONG).show()
            (activity as? MainActivity)?.switchToTab(MainPagerAdapter.SETTINGS_INDEX)
            return
        }
        val imageUri = selectedImageUri
        val userPrompt = prompt.ifBlank { getString(R.string.general_task_default_prompt) }
        appendUserMessage(userPrompt, imageUri)
        conversationHistory += ChatTurn(role = "user", content = userPrompt)
        binding.generalTaskInput.setText("")
        selectedImageUri = null
        updateSelectedImagePreview()
        setSendingState(true)

        val responseText = appendAssistantMessage(getString(R.string.general_task_thinking))
        lifecycleScope.launch {
            val bitmap = imageUri?.let { loadBitmapFromUri(it) }
            val fullPrompt = buildConversationPrompt(userPrompt, imageUri != null)
            val reply = translationPipeline.runGeneralVisionTask(fullPrompt, bitmap)
                ?.takeIf { it.isNotBlank() }
                ?: getString(R.string.general_task_failed)
            if (!isAdded || _binding == null) return@launch
            responseText.text = reply
            conversationHistory += ChatTurn(role = "assistant", content = reply)
            binding.generalTaskStatusText.text = getString(R.string.general_task_status_last_done)
            scrollMessagesToBottom()
            setSendingState(false)
        }
    }

    private fun buildConversationPrompt(currentPrompt: String, hasImage: Boolean): String {
        val history = conversationHistory.takeLast(6)
        return buildString {
            append("下面是当前对话历史，请延续同一轮对话语气直接回答。\n")
            history.forEach { turn ->
                append(if (turn.role == "user") "用户：" else "助手：")
                append(turn.content.trim())
                append('\n')
            }
            append("用户：")
            append(currentPrompt)
            append('\n')
            if (hasImage) {
                append("补充：本轮附带了一张图片，请优先结合图片内容作答。")
            } else {
                append("补充：本轮没有附图，如果用户问题依赖图片请明确说明。")
            }
        }
    }

    private suspend fun loadBitmapFromUri(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            val bytes = ByteArrayOutputStream().use { output ->
                input.copyTo(output)
                output.toByteArray()
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    private fun updateSelectedImagePreview() {
        val uri = selectedImageUri
        binding.generalTaskImageCard.visibility = if (uri == null) View.GONE else View.VISIBLE
        if (uri == null) {
            binding.generalTaskPreviewImage.setImageDrawable(null)
            binding.generalTaskImageName.text = ""
            return
        }
        binding.generalTaskPreviewImage.setImageURI(uri)
        binding.generalTaskImageName.text = queryDisplayName(uri)
    }

    private fun queryDisplayName(uri: Uri): String {
        var name: String? = null
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(index)
            }
        }
        return name ?: getString(R.string.general_task_selected_image)
    }

    private fun setSendingState(sending: Boolean) {
        isSending = sending
        binding.generalTaskSendButton.isEnabled = !sending
        binding.generalTaskPickImageButton.isEnabled = !sending
        binding.generalTaskClearImageButton.isEnabled = !sending
        binding.generalTaskInput.isEnabled = !sending
        if (sending) {
            binding.generalTaskStatusText.text = getString(R.string.general_task_status_running)
        }
    }

    private fun appendUserMessage(text: String, imageUri: Uri?) {
        appendMessageBubble(
            role = "user",
            title = getString(R.string.general_task_user_label),
            text = if (imageUri == null) text else getString(R.string.general_task_user_with_image, text)
        )
    }

    private fun appendAssistantMessage(text: String): TextView {
        return appendMessageBubble(
            role = "assistant",
            title = getString(R.string.general_task_assistant_label),
            text = text
        )
    }

    private fun appendMessageBubble(role: String, title: String, text: String): TextView {
        val context = requireContext()
        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val margin = dp(context, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = margin
            }
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            background = ContextCompat.getDrawable(
                context,
                if (role == "user") R.drawable.bg_action_tile else R.drawable.bg_surface_card
            )
            gravity = if (role == "user") Gravity.END else Gravity.START
        }
        val titleView = TextView(context).apply {
            setTypeface(typeface, Typeface.BOLD)
            text = title
        }
        val contentView = TextView(context).apply {
            text = text
            setPadding(0, dp(context, 6), 0, 0)
        }
        bubble.addView(titleView)
        bubble.addView(contentView)
        binding.generalTaskMessagesContainer.addView(bubble)
        scrollMessagesToBottom()
        return contentView
    }

    private fun scrollMessagesToBottom() {
        binding.generalTaskMessagesScroll.post {
            if (_binding == null) return@post
            binding.generalTaskMessagesScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private data class ChatTurn(
        val role: String,
        val content: String
    )
}
