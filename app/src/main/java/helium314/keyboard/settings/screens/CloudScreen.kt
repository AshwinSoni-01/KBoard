// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.cloud.CloudManager
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.TextInputPreference
import okhttp3.Request

@Composable
fun CloudScreen(onClickBack: () -> Unit) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.cloud_features),
        settings = listOf(
            CloudManager.PREF_ENABLE_CLOUD_FEATURES,
            CloudManager.PREF_GEMINI_API_KEY,
            CloudManager.PREF_TEST_CONNECTION
        ),
    )
}

fun createCloudSettings(context: Context) = listOf(
    Setting(
        context,
        CloudManager.PREF_ENABLE_CLOUD_FEATURES,
        R.string.cloud_features,
        R.string.cloud_features_summary,
    ) {
        SwitchPreference(it, false)
    },
    Setting(
        context,
        CloudManager.PREF_GEMINI_API_KEY,
        R.string.gemini_api_key,
        R.string.gemini_api_key_summary,
    ) {
        TextInputPreference(it, "", isPassword = true)
    },
    Setting(
        context,
        CloudManager.PREF_TEST_CONNECTION,
        R.string.test_connection,
        R.string.test_connection_summary,
    ) { setting ->
        Preference(
            name = setting.title,
            description = setting.description,
            onClick = {
                Toast.makeText(context, "Testing...", Toast.LENGTH_SHORT).show()

                Thread {
                    try {
                        val request = Request.Builder()
                            .url("https://httpbin.org/get")
                            .build()

                        val response = CloudManager.executeRequest(
                            context,
                            CloudManager.CloudFeature.TEST_CONNECTION,
                            request
                        )

                        val message = if (response != null && response.isSuccessful) {
                            "SUCCESS: Connected to the internet!"
                        } else {
                            "ERROR: Request failed."
                        }

                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: SecurityException) {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "BLOCKED: Gatekeeper intercepted request.", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "FAILED: No network available.", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        )
    }
)
