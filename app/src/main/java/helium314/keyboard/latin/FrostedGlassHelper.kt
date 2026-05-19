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
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Locale

object FrostedGlassHelper {

    private const val TAG = "KBoardBlur"
    private const val SAMSUNG_EXTENSION_FLAG_BLUR = 0x10
    private const val LIGHT_TINT_WITH_BLUR = 0x33FFFFFF
    private const val DARK_TINT_WITH_BLUR = 0x33000000
    private val LIGHT_TINT_WITHOUT_BLUR = 0xCCFFFFFF.toInt()
    private val DARK_TINT_WITHOUT_BLUR = 0xCC000000.toInt()
    private val failedSamsungSemBlurModes = mutableSetOf<String>()

    // =========================================================================
    // LAZY STATICS: Pre-resolve reflection references ONCE and cache them forever
    // =========================================================================
    private val samsungSemBlurSupported: Boolean by lazy {
        Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !isKnownFrostedGlassBlurUnsupportedDevice() &&
                SemBlurInfoReflect.initialized
    }

    private object SemBlurInfoReflect {
        var initialized = false
            private set

        var semBlurInfoClass: Class<*>? = null
        var builderClass: Class<*>? = null
        var builderIntConstructor: Constructor<*>? = null
        var builderNoArgConstructor: Constructor<*>? = null
        
        // Cached Methods
        var setRadiusMethod: Method? = null
        var setBackgroundColorMethod: Method? = null
        var setBackgroundCornerRadiusMethod: Method? = null
        var setBlurModeMethod: Method? = null
        var buildMethod: Method? = null
        var semSetBlurInfoMethod: Method? = null

        // Pre-resolved non-captured modes sorted by preference
        var cachedCandidates = listOf<SemBlurMode>()

        init {
            try {
                val sbi = Class.forName("android.view.SemBlurInfo")
                val bc = Class.forName("android.view.SemBlurInfo\$Builder")
                semBlurInfoClass = sbi
                builderClass = bc

                // Resolve constructors
                builderIntConstructor = runCatching { 
                    bc.getDeclaredConstructor(Int::class.javaPrimitiveType!!).apply { isAccessible = true } 
                }.getOrNull()
                builderNoArgConstructor = runCatching { 
                    bc.getDeclaredConstructor().apply { isAccessible = true } 
                }.getOrNull()

                // Cache builder methods
                setRadiusMethod = findMethod(bc, listOf("setRadius", "hidden_setRadius"), Int::class.javaPrimitiveType!!)
                setBackgroundColorMethod = findMethod(bc, listOf("setBackgroundColor", "hidden_setBackgroundColor"), Int::class.javaPrimitiveType!!)
                setBackgroundCornerRadiusMethod = findMethod(bc, listOf("setBackgroundCornerRadius", "hidden_setBackgroundCornerRadius"), Float::class.javaPrimitiveType!!)
                setBlurModeMethod = findMethod(bc, listOf("setBlurMode", "hidden_setBlurMode"), Int::class.javaPrimitiveType!!)
                buildMethod = bc.getMethod("build").apply { isAccessible = true }

                // Cache View method
                semSetBlurInfoMethod = runCatching { 
                    View::class.java.getMethod("semSetBlurInfo", sbi) 
                }.recoverCatching { 
                    View::class.java.getDeclaredMethod("semSetBlurInfo", sbi) 
                }.getOrNull()?.apply { isAccessible = true }

                // Parse and pre-cache modes
                val modes = mutableListOf<SemBlurMode>()
                val allFields = sbi.fields + sbi.declaredFields
                for (field in allFields) {
                    if (field.name.startsWith("BLUR_MODE_")) {
                        runCatching {
                            field.isAccessible = true
                            modes.add(SemBlurMode(field.name, field.getInt(null)))
                        }
                    }
                }
                val distinctModes = modes.distinctBy { it.name }.sortedBy { it.value }
                
                // Select candidates
                val unavailable = setOf("BLUR_MODE_WINDOW_CAPTURED", "BLUR_MODE_CAPTURED")
                val preferred = listOf("BLUR_MODE_CANVAS", "BLUR_MODE_BACKGROUND", "BLUR_MODE_WINDOW")
                
                val preferredModes = preferred.mapNotNull { name -> distinctModes.firstOrNull { it.name == name } }
                val remainingModes = distinctModes.filter { it.name !in preferred && it.name !in unavailable }
                
                cachedCandidates = (preferredModes + remainingModes).distinctBy { it.name }
                if (cachedCandidates.isEmpty()) {
                    cachedCandidates = listOf(SemBlurMode("BLUR_MODE_WINDOW", 0))
                }
                
                initialized = semSetBlurInfoMethod != null && (builderIntConstructor != null || builderNoArgConstructor != null)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize Samsung SemBlurInfo reflection framework", e)
            }
        }

        private fun findMethod(clazz: Class<*>, names: List<String>, paramType: Class<*>): Method? {
            for (name in names) {
                val method = runCatching { clazz.getMethod(name, paramType) }
                    .recoverCatching { clazz.getDeclaredMethod(name, paramType) }
                    .getOrNull()
                if (method != null) {
                    method.isAccessible = true
                    return method
                }
            }
            return null
        }
    }

    private data class SemBlurMode(val name: String, val value: Int)

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

        if (!samsungSemBlurSupported) {
            Log.e(TAG, "Samsung SemBlurInfo is unsupported on this device environment; falling back to window tint")
            target.setBackgroundColor(windowTint(context, blursEnabled = false))
            return
        }

        try {
            val radius = blurRadius(context)
            val tint = windowTint(context, blursEnabled = true)
            val candidates = SemBlurInfoReflect.cachedCandidates

            for (mode in candidates) {
                if (mode.name in failedSamsungSemBlurModes) continue
                try {
                    val builder = if (SemBlurInfoReflect.builderIntConstructor != null) {
                        SemBlurInfoReflect.builderIntConstructor!!.newInstance(mode.value)
                    } else {
                        val b = SemBlurInfoReflect.builderNoArgConstructor!!.newInstance()
                        SemBlurInfoReflect.setBlurModeMethod?.invoke(b, mode.value)
                        b
                    }

                    SemBlurInfoReflect.setRadiusMethod?.invoke(builder, radius)
                    SemBlurInfoReflect.setBackgroundColorMethod?.invoke(builder, tint)
                    SemBlurInfoReflect.setBackgroundCornerRadiusMethod?.invoke(builder, 0f)

                    val blurInfo = SemBlurInfoReflect.buildMethod!!.invoke(builder)
                    target.setBackgroundColor(Color.TRANSPARENT)
                    SemBlurInfoReflect.semSetBlurInfoMethod!!.invoke(target, blurInfo)
                    Log.d(TAG, "Applied SemBlurInfo mode=${mode.name}(${mode.value}) radius=$radius target=${target.javaClass.simpleName} size=${target.width}x${target.height}")
                    return
                } catch (modeError: Throwable) {
                    failedSamsungSemBlurModes.add(mode.name)
                    Log.e(TAG, "SemBlurInfo mode ${mode.name}(${mode.value}) failed; trying next candidate", modeError)
                }
            }

            Log.e(TAG, "No Samsung SemBlurInfo mode applied; falling back to readable tint")
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
        var changed = false

        if (params.width != WindowManager.LayoutParams.MATCH_PARENT) {
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            changed = true
        }
        if (params.height != WindowManager.LayoutParams.WRAP_CONTENT) {
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            changed = true
        }
        if (params.gravity != Gravity.BOTTOM) {
            params.gravity = Gravity.BOTTOM
            changed = true
        }

        // 3. Fix Background Targeting: Set root window to transparent.
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val targetRadius = if (enable) blurRadius(service) else 0
            window.setBackgroundBlurRadius(targetRadius)

            val blurFlag = WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            val hasBlurFlag = (params.flags and blurFlag) != 0
            if (enable && !hasBlurFlag) {
                params.flags = params.flags or blurFlag
                changed = true
            } else if (!enable && hasBlurFlag) {
                params.flags = params.flags and blurFlag.inv()
                changed = true
            }
        }

        if (changed) {
            window.attributes = params // Applied in a single layout pass!
        }
    }

    private fun samsungBlurTarget(inputView: View?): View? {
        return inputView?.findViewById<View?>(R.id.main_keyboard_frame) ?: inputView
    }

    private fun clearSamsungSemBlur(target: View?) {
        if (target == null || !samsungSemBlurSupported) return
        try {
            SemBlurInfoReflect.semSetBlurInfoMethod!!.invoke(target, null)
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
