// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.LinearLayout
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings

class RoundedKeyboardFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val clipPath = Path()
    private val clipRect = RectF()
    private var lastWidth = -1
    private var lastHeight = -1
    private var lastRadiusPx = -1f

    override fun draw(canvas: Canvas) {
        val radiusPx = keyboardCornerRadiusPx()
        if (radiusPx <= 0f || width <= 0 || height <= 0) {
            super.draw(canvas)
            return
        }

        updateClipPath(radiusPx)
        val saveCount = canvas.save()
        canvas.clipPath(clipPath)
        super.draw(canvas)
        canvas.restoreToCount(saveCount)
    }

    private fun keyboardCornerRadiusPx(): Float {
        val radiusDp = (Settings.getValues()?.mKeyboardCornerRadiusDp
            ?: Defaults.PREF_KEYBOARD_CORNER_RADIUS).coerceIn(
                Settings.KEYBOARD_CORNER_RADIUS_MIN_DP,
                Settings.KEYBOARD_CORNER_RADIUS_MAX_DP
            )
        return radiusDp * resources.displayMetrics.density
    }

    private fun updateClipPath(radiusPx: Float) {
        if (lastWidth == width && lastHeight == height && lastRadiusPx == radiusPx) {
            return
        }

        lastWidth = width
        lastHeight = height
        lastRadiusPx = radiusPx
        clipRect.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(
            clipRect,
            floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f),
            Path.Direction.CW
        )
    }
}
