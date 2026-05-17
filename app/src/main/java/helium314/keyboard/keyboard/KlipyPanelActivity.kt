package helium314.keyboard.keyboard

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import helium314.keyboard.latin.FrostedGlassHelper
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.cloud.CloudManager
import helium314.keyboard.latin.database.KlipyHistoryDao
import helium314.keyboard.latin.database.KlipyHistoryDao.KlipyItem
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class KlipyPanelActivity : ComponentActivity() {

    private lateinit var gifsRecyclerView: RecyclerView
    private lateinit var stickersRecyclerView: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var loadingIndicator: CircularProgressIndicator
    private lateinit var gifsAdapter: KlipyAdapter
    private lateinit var stickersAdapter: KlipyAdapter
    private lateinit var historyDao: KlipyHistoryDao
    private var currentTab = KlipyHistoryDao.TYPE_GIF
    private var isSearchActive = false
    private var lastGifSearch: List<KlipyItem>? = null
    private var lastStickerSearch: List<KlipyItem>? = null
    private var searchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.klipy_panel)

        historyDao = KlipyHistoryDao.getInstance(this)
        gifsRecyclerView = findViewById(R.id.gifsRecyclerView)
        stickersRecyclerView = findViewById(R.id.stickersRecyclerView)
        emptyState = findViewById(R.id.emptyState)
        loadingIndicator = findViewById(R.id.loadingIndicator)

        val heightInPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 400f, resources.displayMetrics
        ).toInt()
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, heightInPx)
        window.setGravity(Gravity.BOTTOM)

        applyTheme()

        val defaultTab = intent.getStringExtra("defaultTab") ?: KlipyHistoryDao.TYPE_GIF
        currentTab = defaultTab

        setupRecyclerViews()
        setupClickListeners()
        setupSearchBar()

        // Use post to ensure the view hierarchy is ready for interactions
        window.decorView.post {
            if (currentTab == KlipyHistoryDao.TYPE_STICKER) {
                selectStickersTab()
            } else {
                selectGifsTab()
            }
        }
    }

    private fun setupRecyclerViews() {
        gifsAdapter = KlipyAdapter(emptyList()) { item -> onItemUsed(item) }
        gifsRecyclerView.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        gifsRecyclerView.adapter = gifsAdapter

        stickersAdapter = KlipyAdapter(emptyList()) { item -> onItemUsed(item) }
        stickersRecyclerView.layoutManager = GridLayoutManager(this, 4)
        stickersRecyclerView.adapter = stickersAdapter
    }

    private fun onItemUsed(item: KlipyItem) {
        // Update history timestamp/order
        historyDao.addHistory(item.id, item.url, currentTab, item.width, item.height)

        // Share content
        lifecycleScope.launch {
            val isSticker = currentTab == KlipyHistoryDao.TYPE_STICKER
            val file = withContext(Dispatchers.IO) {
                downloadAndPrepareFile(item.url, item.id, isSticker)
            }
            if (file != null) {
                val mimeType = if (isSticker) "image/webp.wasticker" else "image/gif"
                val label = if (isSticker) "Sticker" else "GIF"

                try {
                    val contentUri = FileProvider.getUriForFile(
                        this@KlipyPanelActivity,
                        "${packageName}.provider",
                        file
                    )

                    // Send intent to LatinIME to commit content
                    val intent = Intent(this@KlipyPanelActivity, LatinIME::class.java).apply {
                        action = KLIPY_DONE_ACTION
                        putExtra(KLIPY_URI_KEY, contentUri)
                        putExtra(KLIPY_MIME_KEY, mimeType)
                        putExtra(KLIPY_LABEL_KEY, label)
                    }
                    startService(intent)
                    finish()
                } catch (e: Exception) {
                    Log.e("KlipyPanel", "Failed to get URI for file", e)
                    Toast.makeText(this@KlipyPanelActivity, "Error sharing file", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this@KlipyPanelActivity, "Download failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun downloadAndPrepareFile(url: String, id: String, isSticker: Boolean): File? {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()
        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val cacheDir = File(cacheDir, "klipy").apply { mkdirs() }
            val suffix = if (isSticker) ".webp" else ".gif"
            val file = File(cacheDir, "klipy_${id}${suffix}")

            if (isSticker) {
                val bytes = response.body?.bytes() ?: return null
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

                // Resize to 512x512
                val scaledBitmap = if (bitmap.width != 512 || bitmap.height != 512) {
                    Bitmap.createScaledBitmap(bitmap, 512, 512, true)
                } else {
                    bitmap
                }

                // Try to keep file size under 100KB as required by WhatsApp
                var quality = 100
                var currentFileSize: Long
                do {
                    FileOutputStream(file).use { output ->
                        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            if (quality == 100) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
                        } else {
                            @Suppress("DEPRECATION")
                            Bitmap.CompressFormat.WEBP
                        }
                        scaledBitmap.compress(format, quality, output)
                    }
                    currentFileSize = file.length()
                    quality -= 10
                } while (currentFileSize > 100 * 1024 && quality > 0)

                if (scaledBitmap != bitmap) scaledBitmap.recycle()
                bitmap.recycle()
            } else {
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            file
        } catch (e: Exception) {
            Log.e("KlipyPanel", "Failed to download/prepare Klipy item", e)
            null
        }
    }

    private fun loadHistory() {
        val activeRecyclerView = if (currentTab == KlipyHistoryDao.TYPE_GIF) gifsRecyclerView else stickersRecyclerView
        val activeAdapter = if (currentTab == KlipyHistoryDao.TYPE_GIF) gifsAdapter else stickersAdapter

        gifsRecyclerView.visibility = if (currentTab == KlipyHistoryDao.TYPE_GIF) View.VISIBLE else View.GONE
        stickersRecyclerView.visibility = if (currentTab == KlipyHistoryDao.TYPE_STICKER) View.VISIBLE else View.GONE

        if (isSearchActive) {
            val searchResults = if (currentTab == KlipyHistoryDao.TYPE_GIF) lastGifSearch else lastStickerSearch
            if (searchResults != null) {
                activeAdapter.updateItems(searchResults)
                emptyState.text = getString(R.string.no_results_found)
                emptyState.visibility = if (searchResults.isEmpty()) View.VISIBLE else View.GONE
                return
            } else if (searchQuery.isNotEmpty()) {
                // If we have a query but no results for this tab yet, trigger search
                performSearch(searchQuery)
                return
            }
        }

        val history = historyDao.getHistory(currentTab)
        activeAdapter.updateItems(history)
        emptyState.text = getString(R.string.no_recent_items)
        emptyState.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setupSearchBar() {
        val searchBar = findViewById<EditText>(R.id.klipySearchBar)
        searchBar?.setOnFocusChangeListener { _, hasFocus ->
            val targetHeight = if (hasFocus) 600f else 400f
            window.decorView.post {
                updatePanelHeight(targetHeight)
            }
        }

        searchBar?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrBlank()) {
                    isSearchActive = false
                    searchQuery = ""
                    lastGifSearch = null
                    lastStickerSearch = null
                    loadHistory()
                }
            }
        })

        searchBar?.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val query = v.text.toString()
                if (query.isNotEmpty()) {
                    performSearch(query)
                }
                true
            } else {
                false
            }
        }
    }

    private fun performSearch(query: String) {
        if (query.isBlank()) {
            isSearchActive = false
            searchQuery = ""
            lastGifSearch = null
            lastStickerSearch = null
            loadHistory()
            return
        }

        isSearchActive = true
        searchQuery = query
        hideKeyboard()

        // Show loading indicator and hide previous results
        loadingIndicator.visibility = View.VISIBLE
        gifsAdapter.updateItems(emptyList())
        stickersAdapter.updateItems(emptyList())
        emptyState.visibility = View.GONE

        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                fetchSearchResults(query)
            }

            if (currentTab == KlipyHistoryDao.TYPE_GIF) {
                lastGifSearch = results
            } else {
                lastStickerSearch = results
            }

            loadingIndicator.visibility = View.GONE
            val activeAdapter = if (currentTab == KlipyHistoryDao.TYPE_GIF) gifsAdapter else stickersAdapter
            activeAdapter.updateItems(results)

            if (results.isEmpty()) {
                emptyState.text = getString(R.string.no_results_found)
                emptyState.visibility = View.VISIBLE
            } else {
                emptyState.visibility = View.GONE
            }
        }
    }

    private fun fetchSearchResults(query: String): List<KlipyItem> {
        val apiKey = CloudManager.getKlipyApiKey(this)
        val endpoint = if (currentTab == KlipyHistoryDao.TYPE_GIF) "gifs" else "stickers"

        // Manual encoding to ensure spaces are %20 as requested by the API
        val encodedQuery = try {
            java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            query
        }

        val customerId = prefs().getString("klipy_customer_id", null) ?: run {
            val newId = UUID.randomUUID().toString()
            prefs().edit { putString("klipy_customer_id", newId) }
            newId
        }

        val locale = ConfigurationCompat.getLocales(resources.configuration)[0]?.country?.lowercase() ?: "us"

        val url = "https://api.klipy.com/api/v1/$apiKey/$endpoint/search".toHttpUrlOrNull()?.newBuilder()
            ?.addEncodedQueryParameter("q", encodedQuery)
            ?.addQueryParameter("page", "1")
            ?.addQueryParameter("per_page", "24")
            ?.addQueryParameter("customer_id", customerId)
            ?.addQueryParameter("locale", locale)
            ?.addQueryParameter("content_filter", "medium")
            ?.addQueryParameter("format_filter", if (currentTab == KlipyHistoryDao.TYPE_GIF) "gif" else "webp,png")
            ?.build() ?: return emptyList()

        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .build()

        val client = OkHttpClient()
        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("KlipyPanel", "Search request failed with code: ${response.code}")
                return emptyList()
            }

            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                Log.e("KlipyPanel", "Empty response body")
                return emptyList()
            }

            val json = Json { ignoreUnknownKeys = true }
            val apiResponse = try {
                json.decodeFromString<KlipySearchResponse>(body)
            } catch (e: Exception) {
                Log.e("KlipyPanel", "Failed to parse Klipy response: ${e.message}")
                return emptyList()
            }

            if (!apiResponse.result) {
                Log.w("KlipyPanel", "API returned result=false")
                return emptyList()
            }

            if (apiResponse.data.data.isEmpty()) {
                Log.i("KlipyPanel", "API returned no results for query: $query")
                return emptyList()
            }

            apiResponse.data.data.map { item ->
                KlipyItem(
                    id = item.id.toString(),
                    url = item.file.hd.gif.url,
                    width = item.width ?: 200,
                    height = item.height ?: 200
                )
            }
        } catch (e: Exception) {
            Log.e("KlipyPanel", "Search request failed: ${e.message}", e)
            emptyList()
        }
    }

    @Serializable
    private data class KlipySearchResponse(val result: Boolean, val data: KlipyDataContainer)

    @Serializable
    private data class KlipyDataContainer(val data: List<KlipyApiItem>)

    @Serializable
    private data class KlipyApiItem(
        val id: Long,
        val slug: String? = null,
        val title: String? = null,
        val file: KlipyFileInfo,
        val width: Int? = null,
        val height: Int? = null
    )

    @Serializable
    private data class KlipyFileInfo(val hd: KlipyHdInfo)

    @Serializable
    private data class KlipyHdInfo(val gif: KlipyUrlInfo)

    @Serializable
    private data class KlipyUrlInfo(val url: String)

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        findViewById<View>(R.id.klipySearchBar)?.clearFocus()
    }

    private fun updatePanelHeight(heightDp: Float) {
        val heightInPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, heightDp, resources.displayMetrics
        ).toInt()
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, heightInPx)

        // Also update root layout params to match window if necessary
        val root = findViewById<ViewGroup>(R.id.klipyPanelRoot)
        root?.layoutParams?.height = heightInPx
        root?.requestLayout()
    }

    private fun setupClickListeners() {
        findViewById<MaterialButton>(R.id.tabGifs)?.setOnClickListener {
            selectGifsTab()
        }
        findViewById<MaterialButton>(R.id.tabStickers)?.setOnClickListener {
            selectStickersTab()
        }
    }

    private fun selectGifsTab() {
        currentTab = KlipyHistoryDao.TYPE_GIF
        findViewById<MaterialButton>(R.id.tabGifs)?.isSelected = true
        findViewById<MaterialButton>(R.id.tabStickers)?.isSelected = false
        loadHistory()
    }

    private fun selectStickersTab() {
        currentTab = KlipyHistoryDao.TYPE_STICKER
        findViewById<MaterialButton>(R.id.tabGifs)?.isSelected = false
        findViewById<MaterialButton>(R.id.tabStickers)?.isSelected = true
        loadHistory()
    }

    private fun applyTheme() {
        val root = findViewById<ViewGroup>(R.id.klipyPanelRoot)
        val isNight = ResourceUtils.isNight(resources)

        if (FrostedGlassHelper.isFrostedTheme(this) && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val blurRadius = if (isNight) {
                prefs().getInt(Settings.PREF_FROSTED_BLUR_RADIUS_NIGHT, Defaults.PREF_FROSTED_BLUR_RADIUS_NIGHT)
            } else {
                prefs().getInt(Settings.PREF_FROSTED_BLUR_RADIUS, Defaults.PREF_FROSTED_BLUR_RADIUS)
            }

            window.setBackgroundBlurRadius(blurRadius)
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)

            // Apply Material You tint based on system theme
            val tintColor = if (isNight) {
                ContextCompat.getColor(this, android.R.color.system_neutral1_800)
            } else {
                ContextCompat.getColor(this, android.R.color.system_neutral1_100)
            }
            // Set background with alpha for frosted effect
            val alpha = 0.7f
            val colorWithAlpha = (alpha * 255).toInt() shl 24 or (tintColor and 0x00FFFFFF)
            root?.setBackgroundColor(colorWithAlpha)
        } else {
            // Standard theme: follow system night mode
            root?.setBackgroundResource(R.color.panelBackground)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                window.setBackgroundBlurRadius(0)
            }
        }
    }

    companion object {
        const val KLIPY_DONE_ACTION = "KLIPY_DONE"
        const val KLIPY_URI_KEY = "klipy_uri"
        const val KLIPY_MIME_KEY = "klipy_mime"
        const val KLIPY_LABEL_KEY = "klipy_label"
    }
}
