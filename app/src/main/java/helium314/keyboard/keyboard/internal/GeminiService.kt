package helium314.keyboard.keyboard.internal

import android.content.Context
import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.R
import helium314.keyboard.latin.cloud.CloudManager
import helium314.keyboard.latin.utils.prefs
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object GeminiService {
    private const val TAG = "GeminiService"
    private val fallbackModels = arrayOf(
        "gemini-3.0-flash-lite", // Primary
        "gemini-3.0-flash",      // First Fallback
        "gemini-2.5-flash"       // Second Fallback
    )

    fun generateText(context: Context, prompt: String, text: String, callback: (String?, Exception?) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        if (!CloudManager.isFeatureAllowed(context, CloudManager.CloudFeature.AI_WRITING_TOOLS)) {
            mainHandler.post {
                callback(null, SecurityException("AI Writing Tools are disabled by Gatekeeper"))
            }
            return
        }

        val prefs = context.prefs()
        val apiKey = prefs.getString(CloudManager.PREF_GEMINI_API_KEY, "") ?: ""
        if (apiKey.isBlank()) {
            mainHandler.post {
                callback(null, Exception("Gemini API key is missing. Please enter it in settings."))
            }
            return
        }

        executeWithFallback(context, apiKey, prompt, text, 0, callback)
    }

    private fun executeWithFallback(
        context: Context,
        apiKey: String,
        prompt: String,
        text: String,
        modelIndex: Int,
        callback: (String?, Exception?) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        if (modelIndex >= fallbackModels.size) {
            mainHandler.post {
                callback(null, Exception("All Gemini models failed to generate a response."))
            }
            return
        }

        val model = fallbackModels[modelIndex]
        android.util.Log.d(TAG, "Attempting generation with model: $model (index $modelIndex)")
        
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val fullPrompt = "$prompt\n\n$text"

        val payload = try {
            JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", fullPrompt)
                    }))
                }))
            }
        } catch (e: Exception) {
            mainHandler.post { callback(null, e) }
            return
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = payload.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        CloudManager.client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                android.util.Log.e(TAG, "Model $model failed: ${e.message}")
                executeWithFallback(context, apiKey, prompt, text, modelIndex + 1, callback)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    android.util.Log.e(TAG, "Model $model returned error ${response.code}: $responseBody")
                    executeWithFallback(context, apiKey, prompt, text, modelIndex + 1, callback)
                    return
                }

                try {
                    val jsonResponse = JSONObject(responseBody ?: "")
                    val candidates = jsonResponse.getJSONArray("candidates")
                    if (candidates.length() > 0) {
                        val candidate = candidates.getJSONObject(0)
                        val content = candidate.getJSONObject("content")
                        val parts = content.getJSONArray("parts")
                        if (parts.length() > 0) {
                            val generatedText = parts.getJSONObject(0).getString("text")
                            mainHandler.post {
                                callback(generatedText, null)
                            }
                            return
                        }
                    }
                    android.util.Log.w(TAG, "Model $model returned empty candidates")
                    executeWithFallback(context, apiKey, prompt, text, modelIndex + 1, callback)
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to parse response from $model: ${e.message}")
                    executeWithFallback(context, apiKey, prompt, text, modelIndex + 1, callback)
                }
            }
        })
    }
}
