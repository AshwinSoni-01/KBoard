package helium314.keyboard.keyboard

import android.content.Intent
import android.net.Uri
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
import androidx.core.net.toUri
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
import helium314.keyboard.latin.stickers.AnimatedStickerProcessor
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
import java.security.MessageDigest
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
    private var lastGifSearch: MutableList<KlipyItem> = mutableListOf()
    private var lastStickerSearch: MutableList<KlipyItem> = mutableListOf()
    private var searchQuery = ""
    private var isFallbackMode = false
    private var searchJob: kotlinx.coroutines.Job? = null
    private var cachedCustomerId: String? = null
    private var currentPage = 1
    private var isLoadingMore = false
    private var hasMorePages = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.klipy_panel)
        } catch (e: Exception) {
            Log.e("KlipyPanel", "Failed to inflate layout", e)
            try {
                setContentView(R.layout.klipy_panel_fallback)
            } catch (fallbackEx: Exception) {
                Log.e("KlipyPanel", "Failed to inflate fallback layout", fallbackEx)
            }
            isFallbackMode = true
            return
        }

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
            if (isFallbackMode) return@post
            if (currentTab == KlipyHistoryDao.TYPE_STICKER) {
                selectStickersTab()
            } else {
                selectGifsTab()
            }
        }
    }

    private fun setupRecyclerViews() {
        gifsAdapter = KlipyAdapter(emptyList()) { item -> onItemUsed(item) }
        val staggeredLayoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL).apply {
            gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS
        }
        gifsRecyclerView.layoutManager = staggeredLayoutManager
        gifsRecyclerView.adapter = gifsAdapter
        gifsRecyclerView.setItemViewCacheSize(10)
        gifsRecyclerView.addOnScrollListener(createPaginationScrollListener())

        stickersAdapter = KlipyAdapter(emptyList()) { item -> onItemUsed(item) }
        stickersRecyclerView.layoutManager = GridLayoutManager(this, 4)
        stickersRecyclerView.adapter = stickersAdapter
        stickersRecyclerView.setItemViewCacheSize(10)
        stickersRecyclerView.addOnScrollListener(createPaginationScrollListener())
    }

    private fun createPaginationScrollListener(): RecyclerView.OnScrollListener {
        return object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy <= 0 || !isSearchActive || isLoadingMore || !hasMorePages) return

                val layoutManager = recyclerView.layoutManager ?: return
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItem = when (layoutManager) {
                    is StaggeredGridLayoutManager -> {
                        val positions = layoutManager.findLastVisibleItemPositions(null)
                        positions.maxOrNull() ?: 0
                    }
                    is GridLayoutManager -> layoutManager.findLastVisibleItemPosition()
                    else -> return
                }

                if (lastVisibleItem >= totalItemCount - 4) {
                    loadMoreResults()
                }
            }
        }
    }

    private fun loadMoreResults() {
        if (isLoadingMore || !hasMorePages || searchQuery.isBlank()) return
        isLoadingMore = true
        currentPage++

        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                fetchSearchResults(searchQuery, currentPage)
            }

            if (results.isEmpty()) {
                hasMorePages = false
            } else {
                val currentList = if (currentTab == KlipyHistoryDao.TYPE_GIF) lastGifSearch else lastStickerSearch
                currentList.addAll(results)
                val activeAdapter = if (currentTab == KlipyHistoryDao.TYPE_GIF) gifsAdapter else stickersAdapter
                activeAdapter.updateItems(currentList.toList())
            }
            isLoadingMore = false
        }
    }

    private fun onItemUsed(item: KlipyItem) {
        // Update history timestamp/order
        historyDao.addHistory(item.id, item.url, currentTab, item.width, item.height)

        // Share content
        lifecycleScope.launch {
            loadingIndicator.visibility = View.VISIBLE
            try {
                val isSticker = currentTab == KlipyHistoryDao.TYPE_STICKER
                val sendGifAsSticker = currentTab == KlipyHistoryDao.TYPE_GIF && shouldSendGifsAsStickers()
                val sendAsSticker = isSticker || sendGifAsSticker
                val rawFile = withContext(Dispatchers.IO) {
                    downloadAndPrepareFile(item.url, item.id, isSticker)
                }

                if (rawFile != null) {
                    if (sendAsSticker) {
                        val processedFile = withContext(Dispatchers.Default) {
                            val processor = AnimatedStickerProcessor(this@KlipyPanelActivity)
                            processor.createWhatsAppAnimatedSticker(rawFile)
                        }

                        if (processedFile != null) {
                            val mimeType = "image/webp.wasticker"
                            val label = if (isSticker) "Sticker" else "GIF"

                            try {
                                val contentUri = "content://${packageName}.stickercontentprovider/stickers/klipy/${processedFile.name}".toUri()

                                val intent = Intent(applicationContext, LatinIME::class.java).apply {
                                    action = KLIPY_DONE_ACTION
                                    putExtra(KLIPY_URI_KEY, contentUri)
                                    putExtra(KLIPY_MIME_KEY, mimeType)
                                    putExtra(KLIPY_LABEL_KEY, label)
                                }

                                // 1. Close the activity immediately to trigger focus return to WhatsApp
                                finish()

                                // 2. Fire the intent with a slight delay using the Application context.
                                // 350ms gives Android enough time to re-establish the InputConnection.
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    applicationContext.startService(intent)
                                }, 1000) // reduce the delay later

                            } catch (e: Exception) {
                                Log.e("KlipyPanel", "Failed to get URI for file", e)
                                Toast.makeText(applicationContext, "Error sharing file", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(this@KlipyPanelActivity, "Processing failed", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        sendNormalGif(rawFile)
                    }
                } else {
                    Toast.makeText(this@KlipyPanelActivity, "Download failed", Toast.LENGTH_SHORT).show()
                }
            } finally {
                loadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun sendNormalGif(gifFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", gifFile)
            val intent = Intent(applicationContext, LatinIME::class.java).apply {
                action = KLIPY_DONE_ACTION
                putExtra(KLIPY_URI_KEY, contentUri)
                putExtra(KLIPY_MIME_KEY, "image/gif")
                putExtra(KLIPY_LABEL_KEY, "GIF")
            }

            finish()

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                applicationContext.startService(intent)
            }, 1000)
        } catch (e: Exception) {
            Log.e("KlipyPanel", "Failed to share GIF normally", e)
            Toast.makeText(applicationContext, "Error sharing GIF", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun downloadAndPrepareFile(url: String, id: String, isSticker: Boolean): File? {
        val client = CloudManager.client
        val request = Request.Builder().url(url).build()
        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val storageDir = if (isSticker) {
                File(filesDir, "stickers/klipy").apply { mkdirs() }
            } else {
                File(cacheDir, "klipy").apply { mkdirs() }
            }
            val suffix = if (isSticker) ".webp" else ".gif"
            val file = File(storageDir, "klipy_${id}${suffix}")

            response.body?.byteStream()?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }

            // Verify binary integrity
            val sha256 = sha256Hex(file)
            Log.d("KlipyPanel", "Downloaded ${if (isSticker) "sticker" else "GIF"} to ${file.absolutePath}, SHA-256: $sha256")

            file
        } catch (e: Exception) {
            Log.e("KlipyPanel", "Failed to download/prepare Klipy item", e)
            null
        }
    }

    private fun sha256Hex(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead = input.read(buffer)
                while (bytesRead != -1) {
                    digest.update(buffer, 0, bytesRead)
                    bytesRead = input.read(buffer)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "error"
        }
    }

    private fun loadHistory() {
        val activeAdapter = if (currentTab == KlipyHistoryDao.TYPE_GIF) gifsAdapter else stickersAdapter

        gifsRecyclerView.visibility = if (currentTab == KlipyHistoryDao.TYPE_GIF) View.VISIBLE else View.GONE
        stickersRecyclerView.visibility = if (currentTab == KlipyHistoryDao.TYPE_STICKER) View.VISIBLE else View.GONE

        if (isSearchActive) {
            val searchResults = if (currentTab == KlipyHistoryDao.TYPE_GIF) lastGifSearch else lastStickerSearch
            if (searchResults.isNotEmpty()) {
                activeAdapter.updateItems(searchResults.toList())
                emptyState.visibility = View.GONE
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
                    lastGifSearch.clear()
                    lastStickerSearch.clear()
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
            lastGifSearch.clear()
            lastStickerSearch.clear()
            loadHistory()
            return
        }

        isSearchActive = true
        searchQuery = query
        currentPage = 1
        hasMorePages = true
        hideKeyboard()

        // Show loading indicator but keep current items visible until results arrive
        loadingIndicator.visibility = View.VISIBLE
        emptyState.visibility = View.GONE

        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                fetchSearchResults(query, 1)
            }

            if (currentTab == KlipyHistoryDao.TYPE_GIF) {
                lastGifSearch.clear()
                lastGifSearch.addAll(results)
            } else {
                lastStickerSearch.clear()
                lastStickerSearch.addAll(results)
            }

            hasMorePages = results.size >= 24

            loadingIndicator.visibility = View.GONE
            val activeAdapter = if (currentTab == KlipyHistoryDao.TYPE_GIF) gifsAdapter else stickersAdapter
            activeAdapter.updateItems(results)

            // Scroll to top for new searches
            val activeRecyclerView = if (currentTab == KlipyHistoryDao.TYPE_GIF) gifsRecyclerView else stickersRecyclerView
            activeRecyclerView.scrollToPosition(0)

            if (results.isEmpty()) {
                emptyState.text = getString(R.string.no_results_found)
                emptyState.visibility = View.VISIBLE
            } else {
                emptyState.visibility = View.GONE
            }
        }
    }

    private fun fetchSearchResults(query: String, page: Int = 1): List<KlipyItem> {
        val apiKey = CloudManager.getKlipyApiKey(this)
        val endpoint = if (currentTab == KlipyHistoryDao.TYPE_GIF) "gifs" else "stickers"

        // Manual encoding to ensure spaces are %20 as requested by the API
        val encodedQuery = try {
            java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            query
        }

        val customerId = cachedCustomerId ?: run {
            val id = prefs().getString("klipy_customer_id", null) ?: run {
                val newId = UUID.randomUUID().toString()
                prefs().edit { putString("klipy_customer_id", newId) }
                newId
            }
            cachedCustomerId = id
            id
        }

        val locale = ConfigurationCompat.getLocales(resources.configuration)[0]?.country?.lowercase() ?: "us"

        val url = "https://api.klipy.com/api/v1/$apiKey/$endpoint/search".toHttpUrlOrNull()?.newBuilder()
            ?.addEncodedQueryParameter("q", encodedQuery)
            ?.addQueryParameter("page", page.toString())
            ?.addQueryParameter("per_page", "24")
            ?.addQueryParameter("customer_id", customerId)
            ?.addQueryParameter("locale", locale)
            ?.addQueryParameter("content_filter", "medium")
            ?.addQueryParameter("format_filter", "webp,gif,png")
            ?.build() ?: return emptyList()

        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .build()

        val client = CloudManager.client
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

        val useGifUrl = currentTab == KlipyHistoryDao.TYPE_GIF && !shouldSendGifsAsStickers()

        apiResponse.data.data.mapNotNull { item ->
            val mediaUrl = if (useGifUrl) {
                item.file.hd.gif.url
            } else {
                item.file.hd.webp?.url
            }

            if (mediaUrl == null) {
                Log.d("KlipyPanel", "Skipped item ${item.id} - No WebP available for sticker send mode")
                return@mapNotNull null
            }

            KlipyItem(
                id = item.id.toString(),
                url = mediaUrl,
                width = item.width ?: 200,
                height = item.height ?: 200
            )
        }
        } catch (e: Exception) {
            Log.e("KlipyPanel", "Search request failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun shouldSendGifsAsStickers(): Boolean {
        return prefs().getBoolean(Settings.PREF_SEND_GIFS_AS_STICKERS, Defaults.PREF_SEND_GIFS_AS_STICKERS)
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
    private data class KlipyHdInfo(val gif: KlipyUrlInfo, val webp: KlipyUrlInfo? = null)

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
        findViewById<View>(R.id.klipyBackButton)?.setOnClickListener {
            finish()
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
        try {
            val root = findViewById<ViewGroup>(R.id.klipyPanelRoot)
            val isNight = ResourceUtils.isNight(resources)

            if (FrostedGlassHelper.isFrostedTheme(this) && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val blurRadius = if (isNight) {
                    prefs().getInt(Settings.PREF_FROSTED_BLUR_RADIUS_NIGHT, Defaults.PREF_FROSTED_BLUR_RADIUS_NIGHT)
                } else {
                    prefs().getInt(Settings.PREF_FROSTED_BLUR_RADIUS, Defaults.PREF_FROSTED_BLUR_RADIUS)
                }

                try {
                    window.setBackgroundBlurRadius(blurRadius)
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                } catch (e: Exception) {
                    Log.e("KlipyPanel", "Failed to set background blur radius", e)
                }

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
                    try {
                        window.setBackgroundBlurRadius(0)
                    } catch (e: Exception) {
                        Log.e("KlipyPanel", "Failed to reset background blur radius", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("KlipyPanel", "Failed to apply theme", e)
        }
    }

    companion object {
        const val KLIPY_DONE_ACTION = "KLIPY_DONE"
        const val KLIPY_URI_KEY = "klipy_uri"
        const val KLIPY_MIME_KEY = "klipy_mime"
        const val KLIPY_LABEL_KEY = "klipy_label"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
