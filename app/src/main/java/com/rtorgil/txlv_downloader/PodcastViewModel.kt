package com.rtorgil.txlv_downloader

import android.util.Log
import android.util.Xml
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

class PodcastViewModel : ViewModel() {
    companion object {
        const val RSS_FEED_URL = "https://feeds.ivoox.com/feed_fg_f12614052_filtro_1.xml"
        private const val TAG = "PodcastViewModel"
    }

    private val _podcastTitle = MutableStateFlow("")
    val podcastTitle: StateFlow<String> = _podcastTitle.asStateFlow()

    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

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
                
                _episodes.value = parsedEpisodes
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
            var currentAudioUrl = ""
            var currentDuration = ""
            var currentGuid = ""
            var currentLink = ""
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
                            currentAudioUrl = ""
                            currentDuration = ""
                            currentGuid = ""
                            currentLink = ""
                        } else if (inItem) {
                            when {
                                name.equals("title", ignoreCase = true) -> {
                                    currentTitle = parser.nextText()
                                }
                                name.equals("pubDate", ignoreCase = true) -> {
                                    currentDate = formatDateToSpanish(parser.nextText())
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
                                    audioUrl = currentAudioUrl,
                                    duration = currentDuration.takeIf { it.isNotBlank() }
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
}