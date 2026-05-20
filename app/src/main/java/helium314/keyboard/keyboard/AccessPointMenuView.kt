// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.content.ClipData
import android.content.Context
import android.util.AttributeSet
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.view.View.DragShadowBuilder
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.FrameLayout
import android.content.Intent
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.getCodeForToolbarKey
import helium314.keyboard.latin.utils.getEnabledToolbarKeys
import helium314.keyboard.latin.utils.getStringResourceOrName

class AccessPointMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var keyboardActionListener: KeyboardActionListener = KeyboardActionListener.EMPTY_LISTENER
    private val grid: GridLayout

    init {
        LayoutInflater.from(context).inflate(R.layout.access_point_menu, this, true)
        grid = findViewById(R.id.access_point_grid)
    }

    fun setKeyboardActionListener(listener: KeyboardActionListener) {
        keyboardActionListener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val settings = Settings.getValues()
        val abcHeight = ResourceUtils.getKeyboardHeight(resources, settings)
        val persistentEmojiEnabled = context.prefs().getBoolean(Settings.PREF_PERSISTENT_EMOJI_ROW, helium314.keyboard.latin.settings.Defaults.PREF_PERSISTENT_EMOJI_ROW)
        val emojiRowHeight = if (persistentEmojiEnabled) (41 * resources.displayMetrics.density).toInt() else 0
        val finalHeight = abcHeight + emojiRowHeight + paddingTop + paddingBottom
        val constrainedHeightSpec = MeasureSpec.makeMeasureSpec(finalHeight, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, constrainedHeightSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), finalHeight)
    }

    fun populateMenu() {
        grid.removeAllViews()
        val prefs = context.prefs()
        val enabledKeys = getEnabledToolbarKeys(prefs)

        val inflater = LayoutInflater.from(context)
        for (key in enabledKeys) {
            val tile = inflater.inflate(R.layout.menu_tile_item, grid, false)
            tile.tag = key
            try {
                tile.setBackgroundResource(android.R.color.transparent)
                val iconView = tile.findViewById<ImageButton>(R.id.menu_tile_icon)
                val colors = Settings.getValues().mColors
                if (colors.themeStyle == helium314.keyboard.keyboard.KeyboardTheme.STYLE_ROUNDED || colors.themeStyle == helium314.keyboard.keyboard.KeyboardTheme.STYLE_CIRCLE) {
                    val drawable = android.graphics.drawable.GradientDrawable()
                    if (colors.themeStyle == helium314.keyboard.keyboard.KeyboardTheme.STYLE_CIRCLE) {
                        drawable.shape = android.graphics.drawable.GradientDrawable.OVAL
                        val lp = iconView.layoutParams
                        lp.width = lp.height
                        iconView.layoutParams = lp
                    } else {
                        drawable.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        drawable.cornerRadius = 1000f
                        val lp = iconView.layoutParams
                        lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        iconView.layoutParams = lp
                    }
                    drawable.setColor(android.graphics.Color.WHITE)
                    colors.setColor(drawable, helium314.keyboard.latin.common.ColorType.KEY_BACKGROUND)
                    iconView.background = drawable
                } else {
                    val keyboardViewAttr = context.obtainStyledAttributes(null, R.styleable.KeyboardView, R.attr.keyboardViewStyle, R.style.KeyboardView)
                    iconView.background = colors.selectAndColorDrawable(keyboardViewAttr, helium314.keyboard.latin.common.ColorType.KEY_BACKGROUND)
                    keyboardViewAttr.recycle()
                }
                val labelView = tile.findViewById<TextView>(R.id.menu_tile_label)

                var drawable: android.graphics.drawable.Drawable? = null
                try {
                    drawable = KeyboardIconsSet.instance.getNewDrawable(key.name, context)
                } catch (e: Exception) {
                    android.util.Log.e("AccessPointMenuView", "Failed to load drawable for ${key.name}", e)
                }
                if (drawable == null) {
                    drawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_settings_default)
                }

                val keyboardTextColor = Settings.getValues().mColors.get(helium314.keyboard.latin.common.ColorType.KEY_TEXT)
                val safeIcon = drawable?.mutate()
                safeIcon?.setColorFilter(keyboardTextColor, android.graphics.PorterDuff.Mode.SRC_IN)
                iconView.setImageDrawable(safeIcon)

                labelView.text = key.name.lowercase().getStringResourceOrName("", context)
                labelView.setTextColor(keyboardTextColor)

                // Icon is non-interactive; touch is handled by parent tile
                iconView.isClickable = false
                iconView.isFocusable = false
            } catch (e: android.content.res.Resources.NotFoundException) {
                android.util.Log.e("AccessPointMenuView", "Resource not found for tile ${key.name}", e)
            }

            // Whole tile is the touch target, not just the icon
            tile.setOnClickListener {
                AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.NOT_SPECIFIED, tile, HapticEvent.KEY_PRESS)
                val code = getCodeForToolbarKey(key)
                if (code == helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.GIFS ||
                    code == helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.STICKERS) {
                    val intent = Intent(context, KlipyPanelActivity::class.java).apply {
                        putExtra("defaultTab", if (code == helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.GIFS) "GIFS" else "STICKERS")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return@setOnClickListener
                }
                if (code != helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.UNSPECIFIED) {
                    keyboardActionListener.onCodeInput(code, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, false)
                }
                if (code != helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.CLIPBOARD &&
                    code != helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.AI_TOOLS &&
                    code != helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.EMOJI &&
                    code != helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.NUMPAD) {
                    KeyboardSwitcher.getInstance().setAlphabetKeyboard()
                }
            }

            tile.setOnLongClickListener { v ->
                AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(v, HapticEvent.KEY_LONG_PRESS)
                val clipData = ClipData.newPlainText("ToolbarKey", key.name)
                val shadow = DragShadowBuilder(v)
                v.visibility = View.INVISIBLE
                v.startDragAndDrop(clipData, shadow, v, 0)
                true
            }

            tile.setOnDragListener { v, event ->
                val draggedView = event.localState as? View
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> true
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        if (draggedView != null && draggedView != v) {
                            v.alpha = 0.5f
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_LOCATION -> {
                        if (draggedView != null) {
                            for (i in 0 until grid.childCount) {
                                val child = grid.getChildAt(i)
                                if (child != draggedView) {
                                    child.alpha = if (child == v) 0.5f else 1.0f
                                }
                            }
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_EXITED -> {
                        v.alpha = 1.0f
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        for (i in 0 until grid.childCount) {
                            grid.getChildAt(i).alpha = 1.0f
                        }
                        if (draggedView != null) {
                            draggedView.visibility = View.VISIBLE
                            val sourceIndex = grid.indexOfChild(draggedView)
                            val targetIndex = grid.indexOfChild(v)

                            if (sourceIndex >= 0 && targetIndex >= 0 && sourceIndex != targetIndex) {
                                // Defer view hierarchy modification to avoid ConcurrentModificationException during drag dispatch
                                grid.post {
                                    if (grid.indexOfChild(draggedView) == sourceIndex) { // Check if still in same state
                                        grid.removeView(draggedView)
                                        grid.addView(draggedView, targetIndex)
                                        saveToolbarKeyOrder(grid)
                                    }
                                }
                            }
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        for (i in 0 until grid.childCount) {
                            grid.getChildAt(i).alpha = 1.0f
                        }
                        if (draggedView != null) {
                            draggedView.visibility = View.VISIBLE
                        }
                        true
                    }
                    else -> false
                }
            }

            grid.addView(tile)
        }
    }

    private fun saveToolbarKeyOrder(grid: GridLayout) {
        val prefs = context.prefs()
        val allPrefString = prefs.getString(Settings.PREF_TOOLBAR_KEYS, helium314.keyboard.latin.utils.defaultToolbarPref) ?: return
        val allEntries = allPrefString.split(Constants.Separators.ENTRY).toMutableList()

        val reorderedEnabled = mutableListOf<ToolbarKey>()
        for (i in 0 until grid.childCount) {
            val tile = grid.getChildAt(i)
            val key = tile.tag as? ToolbarKey
            if (key != null) {
                reorderedEnabled.add(key)
            }
        }

        val newEntries = mutableListOf<String>()
        for (key in reorderedEnabled) {
            newEntries.add("${key.name}${Constants.Separators.KV}true")
        }
        for (entry in allEntries) {
            val name = entry.substringBefore(Constants.Separators.KV)
            if (reorderedEnabled.none { it.name == name }) {
                newEntries.add(entry)
            }
        }
        prefs.edit().putString(Settings.PREF_TOOLBAR_KEYS, newEntries.joinToString(Constants.Separators.ENTRY)).commit()
        Settings.getInstance().onSharedPreferenceChanged(prefs, Settings.PREF_TOOLBAR_KEYS)
    }

    fun updateThemeColors(colors: helium314.keyboard.latin.common.Colors) {
        val keyboardViewAttr = context.obtainStyledAttributes(null, R.styleable.KeyboardView, R.attr.keyboardViewStyle, R.style.KeyboardView)
        for (i in 0 until grid.childCount) {
            val tile = grid.getChildAt(i)
            val iconView = tile.findViewById<ImageButton>(R.id.menu_tile_icon)
            if (colors.themeStyle == helium314.keyboard.keyboard.KeyboardTheme.STYLE_ROUNDED || colors.themeStyle == helium314.keyboard.keyboard.KeyboardTheme.STYLE_CIRCLE) {
                val drawable = android.graphics.drawable.GradientDrawable()
                if (colors.themeStyle == helium314.keyboard.keyboard.KeyboardTheme.STYLE_CIRCLE) {
                    drawable.shape = android.graphics.drawable.GradientDrawable.OVAL
                    val lp = iconView.layoutParams
                    lp.width = lp.height
                    iconView.layoutParams = lp
                } else {
                    drawable.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    drawable.cornerRadius = 1000f
                    val lp = iconView.layoutParams
                    lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    iconView.layoutParams = lp
                }
                drawable.setColor(android.graphics.Color.WHITE)
                colors.setColor(drawable, helium314.keyboard.latin.common.ColorType.KEY_BACKGROUND)
                iconView.background = drawable
            } else {
                val lp = iconView.layoutParams
                lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                iconView.layoutParams = lp
                iconView.background = colors.selectAndColorDrawable(keyboardViewAttr, helium314.keyboard.latin.common.ColorType.KEY_BACKGROUND)
            }
            
            val labelView = tile.findViewById<TextView>(R.id.menu_tile_label)
            val keyboardTextColor = colors.get(helium314.keyboard.latin.common.ColorType.KEY_TEXT)
            labelView.setTextColor(keyboardTextColor)
            
            val drawable = iconView.drawable
            if (drawable != null) {
                val safeIcon = drawable.mutate()
                safeIcon.setColorFilter(keyboardTextColor, android.graphics.PorterDuff.Mode.SRC_IN)
                iconView.setImageDrawable(safeIcon)
            }
        }
        keyboardViewAttr.recycle()
    }
}
