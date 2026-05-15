package helium314.keyboard.keyboard.internal

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.*
import androidx.viewpager2.widget.ViewPager2
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.latin.R
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.event.HapticEvent

@SuppressLint("ViewConstructor")
class AiWritingToolsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    lateinit var keyboardActionListener: KeyboardActionListener
    private var inputConnection: InputConnection? = null
    private var userText: String = ""
    private var aiVariations: List<String> = emptyList()

    private lateinit var viewPager: ViewPager2
    private lateinit var indicatorContainer: LinearLayout
    private lateinit var copyButton: Button
    private var glowAnimator: ObjectAnimator? = null
    private lateinit var glowBorder: View

    companion object {
        private const val DELIMITER = "---VAR---"
        private val AI_PROMPTS = mapOf(
            "Proofread" to "Proofread the following text for grammar, spelling, and clarity. Give me 3 distinct variations of the corrected text, separated by '---VAR---'. Return only the variations and delimiters.",
            "Rewrite" to "Rewrite the following text to make it more engaging and concise. Give me 3 distinct variations, separated by '---VAR---'. Return only the variations and delimiters.",
            "Professional" to "Rewrite the following text in a professional, formal business tone. Give me 3 distinct variations, separated by '---VAR---'. Return only the variations and delimiters.",
            "Friendly" to "Rewrite the following text in a warm, friendly, and informal tone. Give me 3 distinct variations, separated by '---VAR---'. Return only the variations and delimiters.",
            "Smart Reply" to "Based on the following received message, suggest three short, appropriate replies. Give me 3 distinct variations, separated by '---VAR---'. Return only the variations and delimiters."
        )
    }

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.ai_writing_tools_view, this, true)
        setupUI()
    }

    private inner class AiOutputAdapter : RecyclerView.Adapter<AiOutputAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(R.id.tv_ai_output)
            val useButton: Button = view.findViewById(R.id.btn_use_this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ai_output, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            var text = aiVariations.getOrNull(position) ?: ""
            
            // Empty state restoration logic - now applies to all pages
            val isInitial = aiVariations.all { it.isBlank() }
            if (isInitial) {
                text = if (userText.isBlank()) {
                    "Please write something first to use AI writing tools."
                } else {
                    "Select a tool above to begin..."
                }
            }

            holder.textView.text = text
            val isThinking = text == "Thinking..." || text.isBlank()
            val isError = text.startsWith("Error:")
            val isPlaceholder = text.contains("Please write something first") || 
                               text.contains("Select a tool above")
            
            // Dynamic theme-aware coloring using attribute resolution
            val colorAttr = if (isPlaceholder || isThinking) android.R.attr.textColorSecondary else android.R.attr.textColorPrimary
            val typedValue = TypedValue()
            context.theme.resolveAttribute(colorAttr, typedValue, true)
            holder.textView.setTextColor(typedValue.data)
            holder.textView.setTypeface(null, if (isPlaceholder) android.graphics.Typeface.ITALIC else android.graphics.Typeface.NORMAL)

            holder.useButton.visibility = if (!isThinking && !isError && !isPlaceholder && text.isNotBlank()) View.VISIBLE else View.GONE
            holder.useButton.setOnClickListener { view ->
                val currentPos = holder.adapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    val textToUse = aiVariations.getOrNull(currentPos) ?: ""
                    if (textToUse.isNotBlank() && textToUse != "Thinking..." && !isPlaceholder) {
                        // VIRTUAL_KEY haptic feedback
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onReplaceClicked(textToUse)
                    }
                }
            }
        }

        override fun getItemCount(): Int = 3
    }

    private inner class RepeatListener(
        private val initialInterval: Int,
        private val normalInterval: Int,
        private val clickListener: OnClickListener
    ) : OnTouchListener {
        private val handler = Handler(Looper.getMainLooper())
        private var touchedView: View? = null
        private var repeatCount = 0

        private val runnable = object : Runnable {
            override fun run() {
                touchedView?.let {
                    if (it.isAttachedToWindow && it.isEnabled) {
                        repeatCount++
                        it.tag = repeatCount
                        clickListener.onClick(it)
                        handler.postDelayed(this, normalInterval.toLong())
                    } else {
                        handler.removeCallbacks(this)
                    }
                }
            }
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacks(runnable)
                    touchedView = v
                    v.isPressed = true
                    repeatCount = 0
                    v.tag = repeatCount
                    clickListener.onClick(v)
                    handler.postDelayed(runnable, initialInterval.toLong())
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touchedView != null && !isPointInView(v, event.x, event.y)) {
                        handler.removeCallbacks(runnable)
                        v.isPressed = false
                        touchedView = null
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(runnable)
                    v.isPressed = false
                    touchedView = null
                    return true
                }
            }
            return false
        }

        private fun isPointInView(view: View, x: Float, y: Float): Boolean {
            return x >= 0 && x <= view.width && y >= 0 && y <= view.height
        }
    }

    private fun updateButtonStates(ic: InputConnection? = null) {
        val connection = ic ?: getLatinIME()?.currentInputConnection
        val extractedText = connection?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.text?.toString()
        val hasText = !extractedText.isNullOrBlank()

        val toolButtonIds = listOf(
            R.id.btn_tool_proofread, 
            R.id.btn_tool_rewrite, 
            R.id.btn_tone_professional, 
            R.id.btn_tone_friendly
        )

        for (id in toolButtonIds) {
            findViewById<View>(id)?.apply {
                isEnabled = hasText
                alpha = if (hasText) 1.0f else 0.5f
                isClickable = hasText
                isFocusable = hasText
            }
        }
    }

    private fun getLatinIME(): helium314.keyboard.latin.LatinIME? {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is helium314.keyboard.latin.LatinIME) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? helium314.keyboard.latin.LatinIME
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateButtonStates()
        viewPager.adapter?.notifyDataSetChanged()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        glowAnimator?.cancel()
    }

    private fun setupUI() {
        viewPager = findViewById(R.id.vp_ai_carousel)
        indicatorContainer = findViewById(R.id.ll_page_indicators)
        copyButton = findViewById(R.id.btn_copy_text)
        
        viewPager.adapter = AiOutputAdapter()
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicators(position)
            }
        })

        // Bind Tool Buttons
        findViewById<Button>(R.id.btn_tool_proofread).setOnClickListener { view ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
            onToolClicked("Proofread", AI_PROMPTS["Proofread"] ?: "")
        }
        findViewById<Button>(R.id.btn_tool_rewrite).setOnClickListener { view ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
            onToolClicked("Rewrite", AI_PROMPTS["Rewrite"] ?: "")
        }
        findViewById<Button>(R.id.btn_tone_professional).setOnClickListener { view ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
            onToolClicked("Professional", AI_PROMPTS["Professional"] ?: "")
        }
        findViewById<Button>(R.id.btn_tone_friendly).setOnClickListener { view ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
            onToolClicked("Friendly", AI_PROMPTS["Friendly"] ?: "")
        }
        findViewById<ImageButton>(R.id.btn_back_ai).setOnClickListener { view ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
            onCloseClicked()
        }

        val deleteBtn = findViewById<ImageButton>(R.id.btn_delete_ai)
        val deleteClickListener = OnClickListener { view ->
            val isRepeat = (view.tag as? Int ?: 0) > 0
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
                KeyCode.DELETE, 
                view, 
                if (isRepeat) HapticEvent.KEY_REPEAT else HapticEvent.KEY_PRESS
            )
            if (::keyboardActionListener.isInitialized) {
                keyboardActionListener.onCodeInput(KeyCode.DELETE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, isRepeat)
            }
            updateButtonStates()
        }
        deleteBtn.setOnTouchListener(RepeatListener(400, 50, deleteClickListener))
 
        copyButton.setOnClickListener { view ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
            onCopyClicked()
        }
    }

    private fun updateIndicators(position: Int) {
        indicatorContainer.removeAllViews()
        val density = resources.displayMetrics.density
        // Always show 3 indicators as per Master Fix
        for (i in 0 until 3) {
            val isActive = i == position
            val dot = View(context).apply {
                val dotHeight = (8 * density).toInt()
                val dotWidth = if (isActive) (24 * density).toInt() else (8 * density).toInt()
                val hasText = aiVariations.getOrNull(i)?.isNotBlank() == true
                
                layoutParams = LinearLayout.LayoutParams(dotWidth, dotHeight).apply {
                    setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                }
                
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 100f * density
                    
                    val typedValue = TypedValue()
                    val colorAttr = if (isActive) android.R.attr.colorAccent else android.R.attr.textColorSecondary
                    context.theme.resolveAttribute(colorAttr, typedValue, true)
                    setColor(typedValue.data)
                }

                // Add subtle alpha to inactive empty dots (View alpha)
                if (!isActive && !hasText) {
                    alpha = 0.3f
                }
            }
            indicatorContainer.addView(dot)
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
        // Pad with empty variations on open
        aiVariations = listOf("", "", "")
        viewPager.adapter?.notifyDataSetChanged()
        updateIndicators(0)
        updateButtonStates(connection)
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

        // Show thinking in all 3 pages or just first? User wants strictly 3 pages.
        aiVariations = listOf("Thinking...", "", "")
        viewPager.adapter?.notifyDataSetChanged()
        updateIndicators(0)
        copyButton.isEnabled = false

        GeminiService.generateText(context, prompt, userText) { result, exception ->
            if (exception != null) {
                aiVariations = listOf("Error: ${exception.message}", "", "")
            } else if (result != null) {
                val rawVariations = result.split(DELIMITER)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                
                // Pad to exactly 3
                val padded = mutableListOf<String>()
                padded.addAll(rawVariations.take(3))
                while (padded.size < 3) {
                    padded.add(if (padded.isEmpty()) "No variations returned." else "")
                }
                aiVariations = padded
                copyButton.isEnabled = true
            }
            viewPager.adapter?.notifyDataSetChanged()
            viewPager.setCurrentItem(0, false)
            updateIndicators(0)
        }
    }

    private fun onReplaceClicked(text: String) {
        if (text.isNotBlank() && text != "Thinking...") {
            val ic = inputConnection ?: getLatinIME()?.currentInputConnection
            if (ic != null) {
                // 1. Select all text in the input field
                ic.performContextMenuAction(android.R.id.selectAll)
                
                // 2. Overwrite the selection with AI text
                ic.commitText(text, 1)
                
                Toast.makeText(context, "Text replaced", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onCopyClicked() {
        val currentPosition = viewPager.currentItem
        val text = aiVariations.getOrNull(currentPosition)
        if (!text.isNullOrBlank() && text != "Thinking...") {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("AI Result", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }
}
