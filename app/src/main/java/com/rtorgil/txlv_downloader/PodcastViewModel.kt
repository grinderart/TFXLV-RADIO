package com.rtorgil.txlv_downloader

import android.app.Application
import android.content.Context
import android.util.Log
import android.util.Xml
import android.text.Html
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

data class MetadataObj(
    val invitados: String,
    val tema: String,
    val tags: List<String>
)

class PodcastViewModel(private val application: Application) : AndroidViewModel(application) {
    var isSplashFinished by mutableStateOf(false)
    
    companion object {
        const val RSS_FEED_URL = "https://feeds.ivoox.com/feed_fg_f12614052_filtro_1.xml"
        private const val TAG = "PodcastViewModel"
        private const val PREFS_NAME = "txlv_prefs"
        private const val FAVORITES_KEY = "favorites_key"
        private const val DOWNLOADS_KEY = "downloads_key"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()

    private val _downloadedIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedIds: StateFlow<Set<String>> = _downloadedIds.asStateFlow()

    private val cleanedUpIds = mutableSetOf<String>()

    fun isCleanupAllowed(id: String): Boolean = !cleanedUpIds.contains(id)

    fun markCleanupDone(id: String) {
        cleanedUpIds.add(id)
    }

    private val POSITION_PREFIX = "pos_"

    init {
        val savedFavorites = prefs.getString(FAVORITES_KEY, "") ?: ""
        val initialSet = if (savedFavorites.isNotEmpty()) savedFavorites.split(",").toSet() else emptySet()
        _favoriteIds.value = initialSet

        val savedDownloads = prefs.getString(DOWNLOADS_KEY, "") ?: ""
        val initialDownloads = if (savedDownloads.isNotEmpty()) savedDownloads.split(",").toSet() else emptySet()
        _downloadedIds.value = initialDownloads
    }

    fun toggleFavorite(id: String) {
        val currentSet = _favoriteIds.value
        val newSet = if (currentSet.contains(id)) {
            currentSet - id
        } else {
            currentSet + id
        }
        _favoriteIds.value = newSet
        prefs.edit().putString(FAVORITES_KEY, newSet.joinToString(",")).apply()
    }

    fun markAsDownloaded(id: String) {
        val currentSet = _downloadedIds.value
        if (!currentSet.contains(id)) {
            val newSet = currentSet + id
            _downloadedIds.value = newSet
            prefs.edit().putString(DOWNLOADS_KEY, newSet.joinToString(",")).apply()
        }
    }

    fun removeDownload(id: String) {
        val currentSet = _downloadedIds.value
        if (currentSet.contains(id)) {
            val newSet = currentSet - id
            _downloadedIds.value = newSet
            prefs.edit().putString(DOWNLOADS_KEY, newSet.joinToString(",")).apply()
        }
    }

    fun savePosition(id: String, position: Long) {
        prefs.edit().putLong(POSITION_PREFIX + id, position).apply()
    }

    fun getSavedPosition(id: String): Long {
        return prefs.getLong(POSITION_PREFIX + id, 0L)
    }

    private val _podcastTitle = MutableStateFlow("")
    val podcastTitle: StateFlow<String> = _podcastTitle.asStateFlow()

    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    var activeEpisode by mutableStateOf<Episode?>(null)

    private val _sleepTimerEvent = MutableSharedFlow<Unit>()
    val sleepTimerEvent = _sleepTimerEvent.asSharedFlow()

    private var sleepTimerJob: Job? = null
    var isSleepTimerActive by mutableStateOf(false)

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes > 0) {
            isSleepTimerActive = true
            sleepTimerJob = viewModelScope.launch {
                delay(minutes * 60 * 1000L)
                isSleepTimerActive = false
                _sleepTimerEvent.emit(Unit)
            }
        } else {
            isSleepTimerActive = false
        }
    }

    fun fetchEpisodes(urlStr: String = RSS_FEED_URL) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null
            try {
                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("Failed to load: ${connection.responseCode}")
                }

                val inputStream = connection.inputStream
                val parsedEpisodes = parseRss(inputStream)
                
                // Enrich with metadata
                val metadataMap = loadMetadata(application)
                val enrichedEpisodes = parsedEpisodes.map { episode ->
                    val metadataDate = formatDateForMetadata(episode.rawDate)
                    val meta = metadataMap[metadataDate]
                    if (meta != null) {
                        episode.copy(
                            invitados = meta.invitados,
                            tema = meta.tema,
                            tags = meta.tags
                        )
                    } else {
                        episode
                    }
                }
                
                _episodes.value = enrichedEpisodes
                inputStream.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching episodes", e)
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun parseRss(inputStream: InputStream): List<Episode> {
        val episodes = mutableListOf<Episode>()
        try {
            val parser: XmlPullParser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, null)

            var eventType = parser.eventType
            var currentTitle = ""
            var currentDate = ""
            var currentRawDate = ""
            var currentAudioUrl = ""
            var currentDuration = ""
            var currentGuid = ""
            var currentLink = ""
            var currentDescription = ""
            var inItem = false
            var inChannel = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name ?: ""
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (name.equals("channel", ignoreCase = true)) {
                            inChannel = true
                        } else if (name.equals("item", ignoreCase = true)) {
                            inItem = true
                            currentTitle = ""
                            currentDate = ""
                            currentRawDate = ""
                            currentAudioUrl = ""
                            currentDuration = ""
                            currentGuid = ""
                            currentLink = ""
                            currentDescription = ""
                        } else if (inItem) {
                            when {
                                name.equals("title", ignoreCase = true) -> {
                                    currentTitle = parser.nextText()
                                }
                                name.equals("pubDate", ignoreCase = true) -> {
                                    val rawPubDate = parser.nextText()
                                    currentRawDate = rawPubDate
                                    currentDate = formatDateToSpanish(rawPubDate)
                                }
                                name.equals("duration", ignoreCase = true) || name.equals("itunes:duration", ignoreCase = true) -> {
                                    currentDuration = parser.nextText()
                                }
                                name.equals("enclosure", ignoreCase = true) -> {
                                    currentAudioUrl = parser.getAttributeValue(null, "url") ?: ""
                                }
                                name.equals("guid", ignoreCase = true) -> {
                                    currentGuid = parser.nextText()
                                }
                                name.equals("link", ignoreCase = true) -> {
                                    currentLink = parser.nextText()
                                }
                                name.equals("description", ignoreCase = true) || name.equals("content:encoded", ignoreCase = true) -> {
                                    val rawDesc = parser.nextText()
                                    currentDescription = Html.fromHtml(rawDesc, Html.FROM_HTML_MODE_COMPACT).toString().trim()
                                }
                            }
                        } else if (inChannel && !inItem) {
                            if (name.equals("title", ignoreCase = true)) {
                                _podcastTitle.value = parser.nextText()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (name.equals("item", ignoreCase = true)) {
                            inItem = false
                            
                            if (currentAudioUrl.isEmpty()) {
                                val urlToTest = if (currentGuid.isNotEmpty()) currentGuid else currentLink
                                val idMatch = """_(?:rf|mf)_(\d+)_""".toRegex().find(urlToTest)
                                if (idMatch != null) {
                                    val id = idMatch.groupValues[1]
                                    currentAudioUrl = "https://www.ivoox.com/listen_mn_${id}_1.mp3"
                                }
                            }

                            val id = extractIdFromUrl(currentAudioUrl)
                            episodes.add(
                                Episode(
                                    id = id, 
                                    title = currentTitle.trim(), 
                                    date = currentDate.trim(), 
                                    rawDate = currentRawDate.trim(),
                                    audioUrl = currentAudioUrl,
                                    duration = currentDuration.takeIf { it.isNotBlank() },
                                    description = currentDescription.takeIf { it.isNotBlank() }
                                )
                            )
                        } else if (name.equals("channel", ignoreCase = true)) {
                            inChannel = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing RSS", e)
            throw e
        }
        return episodes
    }

    private fun formatDateToSpanish(dateString: String): String {
        try {
            val inputFormats = listOf(
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
                SimpleDateFormat("dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
            )
            
            for (format in inputFormats) {
                try {
                    val parsed = format.parse(dateString.trim())
                    if (parsed != null) {
                        val outputFormat = SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale("es", "ES"))
                        return outputFormat.format(parsed)
                    }
                } catch (e: Exception) {
                    // Try next format
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error formatting date: $dateString", e)
        }
        return dateString 
    }

    private fun extractIdFromUrl(url: String): String {
        val idMatch = """listen_mn_(\d+)_1\.mp3""".toRegex().find(url) ?: """_(?:rf|mf)_(\d+)_""".toRegex().find(url)
        return idMatch?.groupValues?.get(1) ?: url.hashCode().toString()
    }

    private fun formatDateForMetadata(rawDate: String): String {
        try {
            val inputFormats = listOf(
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
                SimpleDateFormat("dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
            )
            for (format in inputFormats) {
                try {
                    val parsed = format.parse(rawDate.trim())
                    if (parsed != null) {
                        val metadataFormat = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)
                        return metadataFormat.format(parsed)
                    }
                } catch (e: Exception) {
                    // Try next format
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error formatting date for metadata: $rawDate", e)
        }
        return ""
    }

    private fun loadMetadata(context: Context): Map<String, MetadataObj> {
        val map = mutableMapOf<String, MetadataObj>()
        try {
            val jsonString = try {
                val url = URL("https://raw.githubusercontent.com/grinderart/TFXLV-RADIO/main/app/src/main/assets/metadata.json")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.useCaches = false
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.connect()
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    context.assets.open("metadata.json").bufferedReader().use { it.readText() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching remote metadata, falling back to local", e)
                context.assets.open("metadata.json").bufferedReader().use { it.readText() }
            }

            val jsonObject = JSONObject(jsonString)

            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = jsonObject.getJSONObject(key)
                val invitados = obj.optString("invitados", "")
                val tema = obj.optString("tema", "")
                val tagsArray = obj.optJSONArray("tags")
                val tags = mutableListOf<String>()
                if (tagsArray != null) {
                    for (i in 0 until tagsArray.length()) {
                        tags.add(tagsArray.getString(i))
                    }
                }
                map[key] = MetadataObj(invitados, tema, tags)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading metadata", e)
        }
        return map
    }
}