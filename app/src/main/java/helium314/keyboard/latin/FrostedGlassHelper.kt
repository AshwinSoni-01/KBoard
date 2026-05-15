package helium314.keyboard.latin

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.updateSoftInputWindowLayoutParameters
import helium314.keyboard.settings.SettingsActivity

object FrostedGlassHelper {

    @JvmStatic
    fun isFrostedTheme(context: Context): Boolean {
        val prefs = context.prefs()
        var isNight = SettingsActivity.forceNight
            ?: (ResourceUtils.isNight(context.resources) && prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT))
        
        // Respect theme override for live preview
        if (helium314.keyboard.keyboard.KeyboardTheme.themeOverride == "light") isNight = false
        else if (helium314.keyboard.keyboard.KeyboardTheme.themeOverride == "dark") isNight = true
        val themeName = SettingsActivity.forceTheme ?: if (isNight)
            prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT)
        else
            prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS)
        return themeName?.contains("frosted", ignoreCase = true) == true
    }

    @JvmStatic
    fun configureFrostedGlass(service: InputMethodService, inputView: View?, enable: Boolean) {
        val window = service.window?.window ?: return

        // --- GLOBAL LAYOUT FIX (Applies to ALL themes) ---
        // 1. Force the IME window to wrap to keyboard content height
        service.updateSoftInputWindowLayoutParameters(inputView, true)

        // 2. Target the specific Window attributes to anchor at bottom
        val params = window.attributes
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.gravity = Gravity.BOTTOM
        window.attributes = params

        // 3. Fix Background Targeting: Set root window to transparent.
        // The actual theme background will be applied to R.id.main_keyboard_frame in InputView.java
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enable) {
                var isNight = ResourceUtils.isNight(service.resources)
                if (helium314.keyboard.keyboard.KeyboardTheme.themeOverride == "light") isNight = false
                else if (helium314.keyboard.keyboard.KeyboardTheme.themeOverride == "dark") isNight = true

                val blurRadius = helium314.keyboard.keyboard.KeyboardTheme.livePreviewValues?.blurRadius
                    ?: (if (isNight) service.prefs().getInt(Settings.PREF_FROSTED_BLUR_RADIUS_NIGHT, Defaults.PREF_FROSTED_BLUR_RADIUS_NIGHT)
                        else service.prefs().getInt(Settings.PREF_FROSTED_BLUR_RADIUS, Defaults.PREF_FROSTED_BLUR_RADIUS))
                window.setBackgroundBlurRadius(blurRadius)
                window.attributes.flags = window.attributes.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                window.attributes = window.attributes
            } else {
                // Reset blur for standard themes
                window.setBackgroundBlurRadius(0)
                window.attributes.flags = window.attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                window.attributes = window.attributes
            }
        }
    }
}
