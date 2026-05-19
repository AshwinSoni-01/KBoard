package helium314.keyboard.latin

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.updateSoftInputWindowLayoutParameters
import helium314.keyboard.settings.SettingsActivity
import java.util.Locale

object FrostedGlassHelper {

    private const val TAG = "KBoardBlur"
    private const val SAMSUNG_EXTENSION_FLAG_BLUR = 0x10
    private const val LIGHT_TINT_WITH_BLUR = 0x33FFFFFF
    private const val DARK_TINT_WITH_BLUR = 0x33000000
    private val LIGHT_TINT_WITHOUT_BLUR = 0xCCFFFFFF.toInt()
    private val DARK_TINT_WITHOUT_BLUR = 0xCC000000.toInt()
    private val failedSamsungSemBlurModes = mutableSetOf<String>()

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

        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                applySamsungSemBlur(window, inputView, enable)
            } else {
                applyDefaultBlur(service, window, inputView, enable)
                applySamsungLegacyBlur(window, enable)
            }
            return
        }

        applyDefaultBlur(service, window, inputView, enable)
    }

    private fun applySamsungSemBlur(window: Window, inputView: View?, enable: Boolean) {
        val context = window.context
        val target = samsungBlurTarget(inputView)

        Log.d(TAG, "Samsung SDK ${Build.VERSION.SDK_INT}: using SemBlurInfo path; enable=$enable")

        window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.setBackgroundBlurRadius(0)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        inputView?.setBackgroundColor(Color.TRANSPARENT)

        if (!enable) {
            clearSamsungSemBlur(target)
            restoreSamsungTargetBackground(target)
            return
        }

        if (target == null) {
            Log.e(TAG, "SemBlurInfo target is null; falling back to window tint")
            window.setBackgroundDrawable(ColorDrawable(windowTint(context, blursEnabled = false)))
            return
        }

        if (isKnownFrostedGlassBlurUnsupportedDevice()) {
            Log.d(TAG, "Known unsupported Samsung blur device (${Build.MODEL}); using readable tint")
            target.setBackgroundColor(windowTint(context, blursEnabled = false))
            return
        }

        try {
            val semBlurInfoClass = Class.forName("android.view.SemBlurInfo")
            val builderClass = Class.forName("android.view.SemBlurInfo\$Builder")
            val modes = resolveSemBlurModes(semBlurInfoClass)
            val candidates = selectSamsungSemBlurModes(modes)
            val radius = blurRadius(context)
            val tint = windowTint(context, blursEnabled = true)

            for (mode in candidates) {
                try {
                    val builder = createSemBlurBuilder(builderClass, mode)

                    invokeBuilderMethod(builder, listOf("setRadius", "hidden_setRadius"), Int::class.javaPrimitiveType!!, radius)
                    invokeBuilderMethod(builder, listOf("setBackgroundColor", "hidden_setBackgroundColor"), Int::class.javaPrimitiveType!!, tint)
                    invokeBuilderMethod(builder, listOf("setBackgroundCornerRadius", "hidden_setBackgroundCornerRadius"), Float::class.javaPrimitiveType!!, 0f)

                    val blurInfo = builder.javaClass.getMethod("build").invoke(builder)
                    target.setBackgroundColor(Color.TRANSPARENT)
                    setSamsungBlurInfo(target, semBlurInfoClass, blurInfo)
                    Log.d(TAG, "Applied SemBlurInfo mode=${mode.name}(${mode.value}) radius=$radius target=${target.javaClass.simpleName} size=${target.width}x${target.height}")
                    return
                } catch (modeError: Throwable) {
                    failedSamsungSemBlurModes.add(mode.name)
                    Log.e(TAG, "SemBlurInfo mode ${mode.name}(${mode.value}) failed; trying next non-captured mode", modeError.cause ?: modeError)
                }
            }

            Log.e(TAG, "No Samsung SemBlurInfo non-captured mode applied; falling back to readable tint")
            target.setBackgroundColor(windowTint(context, blursEnabled = false))
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to apply Samsung SemBlurInfo blur; falling back to readable tint", e)
            target.setBackgroundColor(windowTint(context, blursEnabled = false))
        }
    }

    private fun applySamsungLegacyBlur(window: Window, enable: Boolean) {
        val params = window.attributes
        try {
            if (enable) {
                val semAddExtensionFlags = params.javaClass.getMethod("semAddExtensionFlags", Int::class.javaPrimitiveType)
                semAddExtensionFlags.invoke(params, SAMSUNG_EXTENSION_FLAG_BLUR)
                Log.d(TAG, "Applied Samsung legacy semAddExtensionFlags blur")
            } else {
                val field = params.javaClass.getField("semExtensionFlags")
                val currentFlags = field.getInt(params)
                field.setInt(params, currentFlags and SAMSUNG_EXTENSION_FLAG_BLUR.inv())
                Log.d(TAG, "Cleared Samsung legacy blur flag")
            }
            window.attributes = params
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to update Samsung legacy blur flag via reflection", e)
        }
    }

    private fun applyDefaultBlur(service: InputMethodService, window: Window, inputView: View?, enable: Boolean) {
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
                window.setBackgroundBlurRadius(blurRadius(service))
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

    private fun samsungBlurTarget(inputView: View?): View? {
        return inputView?.findViewById<View?>(R.id.main_keyboard_frame) ?: inputView
    }

    private data class SemBlurMode(val name: String, val value: Int)

    private fun resolveSemBlurModes(semBlurInfoClass: Class<*>): List<SemBlurMode> {
        val fields = semBlurInfoClass.fields.toList() + semBlurInfoClass.declaredFields.toList()
        val modes = fields
            .filter { it.name.startsWith("BLUR_MODE_") }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    SemBlurMode(field.name, field.getInt(null))
                }.getOrNull()
            }
            .distinctBy { it.name }
            .sortedBy { it.value }

        if (modes.isNotEmpty()) {
            Log.d(TAG, "Available SemBlurInfo modes: ${modes.joinToString { "${it.name}=${it.value}" }}")
            return modes
        }

        Log.d(TAG, "SemBlurInfo blur mode fields missing; falling back to BLUR_MODE_WINDOW=0")
        return listOf(SemBlurMode("BLUR_MODE_WINDOW", 0))
    }

    private fun selectSamsungSemBlurModes(modes: List<SemBlurMode>): List<SemBlurMode> {
        val unavailableCapturedModes = setOf("BLUR_MODE_WINDOW_CAPTURED", "BLUR_MODE_CAPTURED")
        val capturedModes = modes.filter { it.name in unavailableCapturedModes }
        if (capturedModes.isNotEmpty()) {
            Log.d(
                TAG,
                "Skipping SemBlurInfo captured modes; Samsung requires capturedBitmap and third-party IMEs cannot capture the app behind them: " +
                    capturedModes.joinToString { "${it.name}=${it.value}" }
            )
        }

        val preferredNames = listOf(
            "BLUR_MODE_CANVAS",
            "BLUR_MODE_BACKGROUND",
            "BLUR_MODE_WINDOW"
        )
        val preferredModes = preferredNames.mapNotNull { name -> modes.firstOrNull { it.name == name } }
        val remainingModes = modes.filter { mode ->
            mode.name !in preferredNames && mode.name !in unavailableCapturedModes
        }
        val candidates = (preferredModes + remainingModes)
            .distinctBy { it.name }
            .filter { it.name !in failedSamsungSemBlurModes }

        if (failedSamsungSemBlurModes.isNotEmpty()) {
            Log.d(TAG, "Skipping previously failed SemBlurInfo modes: ${failedSamsungSemBlurModes.joinToString()}")
        }
        Log.d(TAG, "SemBlurInfo non-captured candidates: ${candidates.joinToString { "${it.name}=${it.value}" }}")
        return candidates
    }

    private fun createSemBlurBuilder(builderClass: Class<*>, mode: SemBlurMode): Any {
        val intType = Int::class.javaPrimitiveType!!
        val constructor = runCatching { builderClass.getDeclaredConstructor(intType) }.getOrNull()
        if (constructor != null) {
            constructor.isAccessible = true
            return constructor.newInstance(mode.value)
        }

        val noArgConstructor = builderClass.getDeclaredConstructor()
        noArgConstructor.isAccessible = true
        val builder = noArgConstructor.newInstance()
        invokeBuilderMethod(builder, listOf("setBlurMode", "hidden_setBlurMode"), intType, mode.value)
        return builder
    }

    private fun invokeBuilderMethod(builder: Any, names: List<String>, parameterType: Class<*>, value: Any): Boolean {
        for (name in names) {
            val method = runCatching { builder.javaClass.getMethod(name, parameterType) }
                .recoverCatching { builder.javaClass.getDeclaredMethod(name, parameterType) }
                .getOrNull()
            if (method != null) {
                method.isAccessible = true
                method.invoke(builder, value)
                return true
            }
        }
        Log.d(TAG, "SemBlurInfo builder method missing: ${names.joinToString("/")}")
        return false
    }

    private fun setSamsungBlurInfo(target: View, semBlurInfoClass: Class<*>, blurInfo: Any?) {
        val method = runCatching { View::class.java.getMethod("semSetBlurInfo", semBlurInfoClass) }
            .recoverCatching { View::class.java.getDeclaredMethod("semSetBlurInfo", semBlurInfoClass) }
            .getOrThrow()
        method.isAccessible = true
        method.invoke(target, blurInfo)
    }

    private fun clearSamsungSemBlur(target: View?) {
        if (target == null) return
        try {
            val semBlurInfoClass = Class.forName("android.view.SemBlurInfo")
            setSamsungBlurInfo(target, semBlurInfoClass, null)
            Log.d(TAG, "Cleared Samsung SemBlurInfo blur")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to clear Samsung SemBlurInfo blur", e)
        }
    }

    private fun restoreSamsungTargetBackground(target: View?) {
        if (target == null) return
        Settings.getValues()?.mColors?.setBackground(target, ColorType.MAIN_BACKGROUND)
        target.invalidate()
    }

    private fun blurRadius(context: Context): Int {
        val isNight = isNight(context)
        return helium314.keyboard.keyboard.KeyboardTheme.livePreviewValues?.blurRadius
            ?: if (isNight) {
                context.prefs().getInt(Settings.PREF_FROSTED_BLUR_RADIUS_NIGHT, Defaults.PREF_FROSTED_BLUR_RADIUS_NIGHT)
            } else {
                context.prefs().getInt(Settings.PREF_FROSTED_BLUR_RADIUS, Defaults.PREF_FROSTED_BLUR_RADIUS)
            }
    }

    private fun windowTint(context: Context, blursEnabled: Boolean): Int {
        val isNight = isNight(context)
        return when {
            blursEnabled && isNight -> DARK_TINT_WITH_BLUR
            blursEnabled -> LIGHT_TINT_WITH_BLUR
            isNight -> DARK_TINT_WITHOUT_BLUR
            else -> LIGHT_TINT_WITHOUT_BLUR
        }
    }

    private fun isNight(context: Context): Boolean {
        var isNight = ResourceUtils.isNight(context.resources)
        if (helium314.keyboard.keyboard.KeyboardTheme.themeOverride == "light") isNight = false
        else if (helium314.keyboard.keyboard.KeyboardTheme.themeOverride == "dark") isNight = true
        return isNight
    }

    fun shouldWarnAboutFrostedGlassBlurUnsupported(themeName: String?): Boolean {
        return themeName == KeyboardTheme.THEME_FROSTED_GLASS && isKnownFrostedGlassBlurUnsupportedDevice()
    }

    fun isKnownFrostedGlassBlurUnsupportedDevice(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return false

        val deviceInfo = listOf(Build.MODEL, Build.DEVICE, Build.PRODUCT, Build.HARDWARE)
            .joinToString(" ")
            .lowercase(Locale.US)

        return listOf("sm-m315", "m31").any { deviceInfo.contains(it) }
    }
}
