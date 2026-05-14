package helium314.keyboard.keyboard.internal

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.*
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs

@SuppressLint("ViewConstructor")
class AiWritingToolsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    lateinit var keyboardActionListener: KeyboardActionListener
    private var inputConnection: InputConnection? = null
    private var userText: String = ""
    private var aiResultText: String = ""

    private lateinit var resultTextView: TextView
    private lateinit var replaceButton: Button
    private lateinit var copyButton: Button
    private var glowAnimator: ValueAnimator? = null
    private lateinit var glowBorder: View

    private val gradientMatrix = Matrix()
    private var gradientShader: Shader? = null
    private val aiColors = intArrayOf(0xFF4285F4.toInt(), 0xFFA142F4.toInt(), 0xFFFBBC05.toInt(), 0xFF4285F4.toInt())

    private val liquidGradientDrawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            if (gradientShader == null) {
                gradientShader = LinearGradient(
                    0f, 0f, bounds.width().toFloat(), 0f,
                    aiColors, null, Shader.TileMode.REPEAT
                )
                paint.shader = gradientShader
            }
            gradientShader?.setLocalMatrix(gradientMatrix)
            rect.set(bounds)
            rect.inset(paint.strokeWidth / 2, paint.strokeWidth / 2)
            val radius = 16f * context.resources.displayMetrics.density
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    companion object {
        private val AI_PROMPTS = mapOf(
            "Proofread" to "Proofread the following text for grammar, spelling, and clarity. Return only the corrected text.",
            "Rewrite" to "Rewrite the following text to make it more engaging and concise. Return only the rewritten text.",
            "Professional" to "Rewrite the following text in a professional, formal business tone. Return only the rewritten text.",
            "Friendly" to "Rewrite the following text in a warm, friendly, and informal tone. Return only the rewritten text.",
            "Smart Reply" to "Based on the following received message, suggest three short, appropriate replies. Return only the suggested replies separated by newlines."
        )
    }

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.ai_writing_tools_view, this, true)
        setupUI()
    }

    private fun setupUI() {
        resultTextView = findViewById(R.id.tv_ai_output)
        replaceButton = findViewById(R.id.btn_commit_text)
        copyButton = findViewById(R.id.btn_copy_text)
        glowBorder = findViewById(R.id.ai_glow_border)

        // Bind Tool Buttons
        findViewById<Button>(R.id.btn_tool_proofread).setOnClickListener {
            onToolClicked("Proofread", AI_PROMPTS["Proofread"] ?: "")
        }
        findViewById<Button>(R.id.btn_tool_rewrite).setOnClickListener {
            onToolClicked("Rewrite", AI_PROMPTS["Rewrite"] ?: "")
        }
        findViewById<Button>(R.id.btn_tone_professional).setOnClickListener {
            onToolClicked("Professional", AI_PROMPTS["Professional"] ?: "")
        }
        findViewById<Button>(R.id.btn_tone_friendly).setOnClickListener {
            onToolClicked("Friendly", AI_PROMPTS["Friendly"] ?: "")
        }
        findViewById<Button>(R.id.btn_close_ai).setOnClickListener {
            onCloseClicked()
        }
 
        replaceButton.setOnClickListener { onReplaceClicked() }
        copyButton.setOnClickListener { onCopyClicked() }
    }
 
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setupAnimation()
    }
 
    private fun setupAnimation() {
        if (glowAnimator != null) return
        val border = findViewById<View>(R.id.ai_glow_border) ?: return
        border.background = liquidGradientDrawable
 
        // Initialize Liquid Animator safely
        glowAnimator = ValueAnimator.ofFloat(0f, 1000f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animator ->
                val shift = animator.animatedValue as Float
                gradientMatrix.setTranslate(shift, 0f)
                handler?.post { border.invalidate() }
            }
        }
    }

    fun onOpen(connection: InputConnection?) {
        this.inputConnection = connection
        val selectedText = connection?.getSelectedText(0)?.toString()
        if (!selectedText.isNullOrBlank()) {
            this.userText = selectedText
        } else {
            val extracted = connection?.getExtractedText(ExtractedTextRequest(), 0)
            this.userText = extracted?.text?.toString() ?: ""
        }
        aiResultText = ""
        resultTextView.text = ""
        resultTextView.hint = "AI output will appear here..."
        replaceButton.isEnabled = false
        copyButton.isEnabled = false
    }

    fun onClose() {
        inputConnection = null
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun onCloseClicked() {
        if (::keyboardActionListener.isInitialized) {
            keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        }
    }

    private fun onToolClicked(toolName: String, prompt: String) {
        if (userText.isBlank()) {
            Toast.makeText(context, "No text to process", Toast.LENGTH_SHORT).show()
            return
        }

        resultTextView.text = ""
        resultTextView.hint = "Thinking..."
        glowBorder.alpha = 1f
        glowAnimator?.start()
        replaceButton.isEnabled = false
        copyButton.isEnabled = false

        GeminiService.generateText(context, prompt, userText) { result, exception ->
            glowAnimator?.cancel()
            glowBorder.alpha = 0f
            if (exception != null) {
                resultTextView.text = "Error: ${exception.message}"
            } else if (result != null) {
                aiResultText = result.trim()
                resultTextView.text = aiResultText
                replaceButton.isEnabled = true
                copyButton.isEnabled = true
            }
        }
    }

    private fun onReplaceClicked() {
        if (aiResultText.isNotBlank()) {
            inputConnection?.commitText(aiResultText, 1)
            Toast.makeText(context, "Text replaced", Toast.LENGTH_SHORT).show()
            onCloseClicked()
        }
    }

    private fun onCopyClicked() {
        if (aiResultText.isNotBlank()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("AI Result", aiResultText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val settings = Settings.getValues()
        // Use the standard keyboard height logic to prevent recursion
        val abcHeight = ResourceUtils.getKeyboardHeight(resources, settings)
        val persistentEmojiEnabled = context.prefs().getBoolean(Settings.PREF_PERSISTENT_EMOJI_ROW, helium314.keyboard.latin.settings.Defaults.PREF_PERSISTENT_EMOJI_ROW)
        val emojiRowHeight = if (persistentEmojiEnabled) (41 * resources.displayMetrics.density).toInt() else 0
        val suggestionStripHeight = resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height)
        
        val finalHeight = abcHeight + emojiRowHeight + suggestionStripHeight + paddingTop + paddingBottom
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), finalHeight)
    }
}
