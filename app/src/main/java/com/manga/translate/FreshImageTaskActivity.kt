package com.manga.translate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FreshImageTaskActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var promptInput: EditText
    private lateinit var messagesScroll: SafeNestedScrollView
    private lateinit var messagesContainer: LinearLayout
    private lateinit var selectedImageCard: LinearLayout
    private lateinit var selectedImageView: ImageView
    private lateinit var selectedImageLabel: TextView
    private lateinit var pipeline: TranslationPipeline
    private lateinit var settingsStore: SettingsStore
    private var selectedUri: Uri? = null
    private var selectedBitmap: Bitmap? = null
    private val turns = mutableListOf<DebugChatTurn>()

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val bitmap = decodeBitmap(uri)
        if (bitmap == null) {
            selectedUri = null
            selectedBitmap = null
            renderSelectedImage()
            statusView.text = getString(R.string.fresh_image_task_failed)
            AppLogger.error("DebugChat", "Failed to decode selected image: $uri")
            return@registerForActivityResult
        }
        selectedUri = uri
        selectedBitmap = bitmap
        renderSelectedImage()
        statusView.text = getString(R.string.debug_chat_image_selected)
        AppLogger.log(
            "DebugChat",
            "Selected image uri=$uri width=${bitmap.width} height=${bitmap.height}"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.log("DebugChat", "FreshImageTaskActivity onCreate start")
        runCatching {
            setContentView(R.layout.activity_fresh_image_task)

            pipeline = TranslationPipeline(applicationContext)
            settingsStore = SettingsStore(applicationContext)
            statusView = findViewById(R.id.fresh_image_task_status)
            promptInput = findViewById(R.id.fresh_image_task_prompt_input)
            messagesScroll = findViewById(R.id.fresh_image_task_messages_scroll)
            messagesContainer = findViewById(R.id.fresh_image_task_messages_container)
            selectedImageCard = findViewById(R.id.fresh_image_task_selected_card)
            selectedImageView = findViewById(R.id.fresh_image_task_selected_image)
            selectedImageLabel = findViewById(R.id.fresh_image_task_selected_label)

            statusView.text = getString(R.string.debug_chat_hint)
            promptInput.setText(settingsStore.loadImageTaskCustomPrompt())
            renderSelectedImage()
            appendWelcomeCard()

            findViewById<Button>(R.id.fresh_image_task_pick_button).setOnClickListener {
                pickImageLauncher.launch("image/*")
            }
            findViewById<Button>(R.id.fresh_image_task_clear_image_button).setOnClickListener {
                clearSelectedImage()
            }
            findViewById<Button>(R.id.fresh_image_task_clear_chat_button).setOnClickListener {
                turns.clear()
                messagesContainer.removeAllViews()
                appendWelcomeCard()
                statusView.text = getString(R.string.debug_chat_cleared)
            }
            findViewById<Button>(R.id.fresh_image_task_send_button).setOnClickListener {
                submitTurn()
            }
        }.onSuccess {
            AppLogger.log("DebugChat", "FreshImageTaskActivity onCreate ready")
        }.onFailure { error ->
            AppLogger.error("DebugChat", "FreshImageTaskActivity onCreate failed", error)
            Toast.makeText(this, error.message ?: "调试聊天初始化失败", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun submitTurn() {
        val userPrompt = promptInput.text?.toString()?.trim().orEmpty()
        val bitmap = selectedBitmap
        if (userPrompt.isBlank() && bitmap == null) {
            Toast.makeText(this, R.string.debug_chat_empty_request, Toast.LENGTH_SHORT).show()
            return
        }
        if (!pipeline.isLocalModelReady()) {
            statusView.text = getString(R.string.fresh_reader_model_missing)
            return
        }
        val imageLabel = selectedUri?.lastPathSegment ?: getString(R.string.debug_chat_image_attached)
        appendUserCard(userPrompt, bitmap, imageLabel)
        val composedPrompt = buildConversationPrompt(userPrompt)
        if (userPrompt.isNotBlank()) {
            settingsStore.saveImageTaskCustomPrompt(userPrompt)
        }
        promptInput.setText("")
        AppLogger.log(
            "DebugChat",
            "Submitting turn history=${turns.size} hasImage=${bitmap != null} prompt=${userPrompt.ifBlank { "(empty)" }}"
        )
        lifecycleScope.launch {
            statusView.text = getString(R.string.debug_chat_running)
            val result = withContext(Dispatchers.Default) {
                pipeline.runDebugVisionTask(composedPrompt, bitmap)
            }
            val assistantText = result.normalizedOutput.ifBlank {
                result.errorMessage ?: getString(R.string.debug_chat_empty_response)
            }
            appendAssistantCard(
                assistantText = assistantText,
                debugResult = result
            )
            turns += DebugChatTurn(
                userPrompt = userPrompt,
                assistantText = assistantText,
                usedPrompt = result.usedPrompt,
                rawOutput = result.rawOutput,
                normalizedOutput = result.normalizedOutput
            )
            statusView.text = buildStatusText(result)
            logTurnResult(userPrompt, bitmap, result, assistantText)
        }
    }

    private fun buildConversationPrompt(currentUserPrompt: String): String {
        val trimmedHistory = turns.takeLast(6)
        return buildString {
            append("你现在处于端侧多模态调试模式。")
            append("请优先直接回答用户问题；如果用户要求输出 JSON，就严格输出 JSON，不要添加解释。")
            append('\n')
            if (trimmedHistory.isNotEmpty()) {
                append("以下是最近多轮对话历史：\n")
                trimmedHistory.forEachIndexed { index, turn ->
                    append("第")
                    append(index + 1)
                    append("轮用户：")
                    append(turn.userPrompt.ifBlank { "(空文本，仅图片)" })
                    append('\n')
                    append("第")
                    append(index + 1)
                    append("轮助手：")
                    append(turn.assistantText.ifBlank { "(空回复)" })
                    append('\n')
                }
            }
            append("当前用户请求：")
            append(currentUserPrompt.ifBlank { "请直接描述这张图片中的关键信息。" })
        }
    }

    private fun appendWelcomeCard() {
        val card = createCardContainer()
        val title = createSectionTitle(getString(R.string.debug_chat_welcome_title))
        val body = createBodyText(getString(R.string.debug_chat_welcome_body))
        card.content.addView(title)
        card.content.addView(body)
        messagesContainer.addView(card.card)
        scrollToBottom()
    }

    private fun appendUserCard(text: String, bitmap: Bitmap?, imageLabel: String) {
        val card = createCardContainer()
        val title = createSectionTitle(getString(R.string.debug_chat_user_title))
        val body = createBodyText(if (text.isBlank()) getString(R.string.debug_chat_empty_text) else text)
        card.content.addView(title)
        card.content.addView(body)
        if (bitmap != null) {
            val label = createMutedText(imageLabel)
            val preview = ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bitmap)
            }
            card.content.addView(label)
            card.content.addView(preview)
        }
        messagesContainer.addView(card.card)
        scrollToBottom()
    }

    private fun appendAssistantCard(
        assistantText: String,
        debugResult: DebugVisionTaskResult
    ) {
        val card = createCardContainer()
        card.content.addView(createSectionTitle(getString(R.string.debug_chat_assistant_title)))
        card.content.addView(createBodyText(assistantText))
        card.content.addView(createSectionTitle(getString(R.string.debug_chat_used_prompt_title)))
        card.content.addView(createMonospaceText(debugResult.usedPrompt))
        card.content.addView(createSectionTitle(getString(R.string.debug_chat_raw_output_title)))
        card.content.addView(createMonospaceText(debugResult.rawOutput.ifBlank {
            getString(R.string.fresh_image_task_raw_empty)
        }))
        card.content.addView(createSectionTitle(getString(R.string.debug_chat_normalized_output_title)))
        card.content.addView(createMonospaceText(debugResult.normalizedOutput.ifBlank {
            getString(R.string.fresh_image_task_raw_empty)
        }))
        card.content.addView(createSectionTitle(getString(R.string.debug_chat_meta_title)))
        card.content.addView(
            createMonospaceText(
                buildString {
                    append("elapsed_ms=")
                    append(debugResult.elapsedMs)
                    append('\n')
                    append("model_ready=")
                    append(debugResult.modelReady)
                    if (!debugResult.errorMessage.isNullOrBlank()) {
                        append('\n')
                        append("error=")
                        append(debugResult.errorMessage)
                    }
                }
            )
        )
        messagesContainer.addView(card.card)
        scrollToBottom()
    }

    private fun buildStatusText(result: DebugVisionTaskResult): String {
        return if (result.errorMessage.isNullOrBlank()) {
            getString(R.string.debug_chat_done, result.elapsedMs)
        } else {
            getString(R.string.debug_chat_failed_with_reason, result.errorMessage)
        }
    }

    private fun renderSelectedImage() {
        val bitmap = selectedBitmap
        selectedImageCard.visibility = if (bitmap != null) View.VISIBLE else View.GONE
        if (bitmap != null) {
            selectedImageView.setImageBitmap(bitmap)
            selectedImageLabel.text = selectedUri?.lastPathSegment ?: getString(R.string.debug_chat_image_attached)
        }
    }

    private fun clearSelectedImage() {
        selectedUri = null
        selectedBitmap = null
        renderSelectedImage()
        statusView.text = getString(R.string.debug_chat_image_cleared)
        AppLogger.log("DebugChat", "Cleared selected image")
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.getOrNull()
    }

    private fun createCardContainer(): DebugCardContainer {
        val margin = (12 * resources.displayMetrics.density).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_surface_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = margin
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, margin, margin, margin)
        }
        card.addView(content)
        return DebugCardContainer(card = card, content = content)
    }

    private fun createSectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTypeface(typeface, Typeface.BOLD)
            textSize = 16f
        }
    }

    private fun createBodyText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setLineSpacing(0f, 1.15f)
            setPadding(0, 12, 0, 12)
        }
    }

    private fun createMutedText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            alpha = 0.7f
            setPadding(0, 8, 0, 8)
        }
    }

    private fun createMonospaceText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 13f
            inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.START
            setPadding(0, 8, 0, 16)
        }
    }

    private fun scrollToBottom() {
        messagesScroll.post { messagesScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun logTurnResult(
        userPrompt: String,
        bitmap: Bitmap?,
        result: DebugVisionTaskResult,
        assistantText: String
    ) {
        val rawOutput = result.rawOutput.ifBlank { "(blank)" }
        val normalizedOutput = result.normalizedOutput.ifBlank { "(blank)" }
        val error = result.errorMessage ?: "(none)"
        AppLogger.log(
            "DebugChat",
            buildString {
                append("Turn finished")
                append(" | hasImage=")
                append(bitmap != null)
                append(" | prompt=")
                append(userPrompt.ifBlank { "(empty)" })
                append(" | elapsedMs=")
                append(result.elapsedMs)
                append(" | modelReady=")
                append(result.modelReady)
                append(" | error=")
                append(error)
                append('\n')
                append("usedPrompt=")
                append(result.usedPrompt)
                append('\n')
                append("rawOutput=")
                append(rawOutput)
                append('\n')
                append("normalizedOutput=")
                append(normalizedOutput)
                append('\n')
                append("assistantText=")
                append(assistantText.ifBlank { "(blank)" })
            }
        )
    }

    private data class DebugChatTurn(
        val userPrompt: String,
        val assistantText: String,
        val usedPrompt: String,
        val rawOutput: String,
        val normalizedOutput: String
    )

    private data class DebugCardContainer(
        val card: LinearLayout,
        val content: LinearLayout
    )
}
