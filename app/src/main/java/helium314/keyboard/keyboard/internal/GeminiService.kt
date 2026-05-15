package helium314.keyboard.keyboard.internal

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import helium314.keyboard.latin.cloud.CloudManager
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object GeminiService : SharedPreferences.OnSharedPreferenceChangeListener {
    private const val TAG = "GeminiService"
    private lateinit var appContext: Context
    private var isInitialized = false
    private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
    private var lastReactiveHealTime = 0L

    fun init(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        val prefs = appContext.prefs()
        prefs.registerOnSharedPreferenceChangeListener(this)
        val apiKey = prefs.getString(CloudManager.PREF_GEMINI_API_KEY, "") ?: ""
        if (apiKey.isNotBlank()) {
            fetchAndCacheModels(appContext, apiKey)
        }
        isInitialized = true
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == CloudManager.PREF_GEMINI_API_KEY) {
            val apiKey = sharedPreferences?.getString(key, "") ?: ""
            if (apiKey.isNotBlank() && ::appContext.isInitialized) {
                fetchAndCacheModels(appContext, apiKey, forceRefresh = true)
            }
        }
    }

    fun fetchAndCacheModels(context: Context, apiKey: String, forceRefresh: Boolean = false) {
        val prefs = context.prefs()
        val lastFetch = prefs.getLong(CloudManager.PREF_GEMINI_MODELS_LAST_FETCH, 0L)
        val now = System.currentTimeMillis()

        if (!forceRefresh && (now - lastFetch < CACHE_TTL_MS) && prefs.contains(CloudManager.PREF_CACHED_GEMINI_MODELS)) {
            Log.d(TAG, "Using cached model list (TTL not expired)")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Fetching available Gemini models (forceRefresh=$forceRefresh)...")
            val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
            val request = Request.Builder().url(url).build()

            try {
                val response = CloudManager.client.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    val json = JSONObject(body)
                    val modelsArray = json.getJSONArray("models")
                    val flashModels = mutableListOf<String>()

                    for (i in 0 until modelsArray.length()) {
                        val modelObj = modelsArray.getJSONObject(i)
                        val name = modelObj.getString("name").removePrefix("models/")
                        val supportedMethods = modelObj.getJSONArray("supportedGenerationMethods")
                        
                        var supportsGenerateContent = false
                        for (j in 0 until supportedMethods.length()) {
                            if (supportedMethods.getString(j) == "generateContent") {
                                supportsGenerateContent = true
                                break
                            }
                        }

                        if (supportsGenerateContent && name.contains("flash", ignoreCase = true)) {
                            flashModels.add(name)
                        }
                    }

                    flashModels.sortDescending()

                    val cachedString = flashModels.joinToString(",")
                    // Synchronous commit to prevent race conditions
                    prefs.edit().apply {
                        putString(CloudManager.PREF_CACHED_GEMINI_MODELS, cachedString)
                        putLong(CloudManager.PREF_GEMINI_MODELS_LAST_FETCH, now)
                    }.commit() 
                    Log.d(TAG, "Cached Gemini models: $cachedString")
                } else {
                    Log.e(TAG, "Failed to fetch models: ${response.code} ${response.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching Gemini models: ${e.message}")
            }
        }
    }

    fun generateText(context: Context, prompt: String, text: String, callback: (String?, Exception?) -> Unit) {
        init(context)
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

        val cachedModels = prefs.getString(CloudManager.PREF_CACHED_GEMINI_MODELS, "") ?: ""
        if (cachedModels.isBlank()) {
            fetchAndCacheModels(context, apiKey, forceRefresh = true)
            mainHandler.post {
                callback(null, Exception("Initializing AI Models. Please try again in a few seconds."))
            }
            return
        }

        val modelList = cachedModels.split(",")
        executeWithFallback(context, apiKey, prompt, text, modelList, 0, callback)
    }

    private fun executeWithFallback(
        context: Context,
        apiKey: String,
        prompt: String,
        text: String,
        models: List<String>,
        modelIndex: Int,
        callback: (String?, Exception?) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        if (modelIndex >= models.size) {
            // REACTIVE HEALING with 5-minute cooldown
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastReactiveHealTime < 300000) { // 5 minutes
                mainHandler.post {
                    callback(null, Exception("Google AI is currently unavailable. Please try again later."))
                }
                return
            }
            lastReactiveHealTime = currentTime

            Log.w(TAG, "All cached models failed. Triggering reactive cache invalidation.")
            fetchAndCacheModels(context, apiKey, forceRefresh = true)
            
            mainHandler.post {
                callback(null, Exception("AI model list refreshed. Please tap the button again."))
            }
            return
        }

        val model = models[modelIndex]
        Log.d(TAG, "Attempting generation with model: $model (index $modelIndex)")
        
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
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Model $model failed: ${e.message}")
                executeWithFallback(context, apiKey, prompt, text, models, modelIndex + 1, callback)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Model $model returned error ${response.code}: $responseBody")
                    
                    // Immediately break for dead keys (400 or 403)
                    if (response.code == 400 || response.code == 403) {
                        mainHandler.post {
                            callback(null, Exception("Invalid API Key. Please check your settings."))
                        }
                        return
                    }

                    executeWithFallback(context, apiKey, prompt, text, models, modelIndex + 1, callback)
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
                    Log.w(TAG, "Model $model returned empty candidates")
                    executeWithFallback(context, apiKey, prompt, text, models, modelIndex + 1, callback)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse response from $model: ${e.message}")
                    executeWithFallback(context, apiKey, prompt, text, models, modelIndex + 1, callback)
                }
            }
        })
    }
}
