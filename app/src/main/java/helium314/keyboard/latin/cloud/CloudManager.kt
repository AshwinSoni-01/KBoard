// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cloud

import android.content.Context
import helium314.keyboard.latin.utils.prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

object CloudManager {
    const val PREF_ENABLE_CLOUD_FEATURES = "pref_enable_cloud_features"
    const val PREF_TEST_CONNECTION = "pref_test_connection"
    const val PREF_GEMINI_API_KEY = "pref_gemini_api_key"
    const val PREF_CACHED_GEMINI_MODELS = "pref_cached_gemini_models"
    const val PREF_GEMINI_MODELS_LAST_FETCH = "pref_gemini_models_last_fetch"

    val client = OkHttpClient()

    enum class CloudFeature {
        TEST_CONNECTION,
        AI_WRITING_TOOLS
    }

    fun isFeatureAllowed(context: Context, feature: CloudFeature): Boolean {
        return context.prefs().getBoolean(PREF_ENABLE_CLOUD_FEATURES, false)
    }

    fun executeRequest(context: Context, feature: CloudFeature, request: Request): Response? {
        if (!isFeatureAllowed(context, feature)) {
            throw SecurityException("Gatekeeper intercepted and blocked request for $feature")
        }
        return client.newCall(request).execute()
    }
}
