package com.rtorgil.txlv_downloader

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class AudioActionHandler(private val context: Context) {

    fun downloadEpisode(episode: Episode) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(episode.audioUrl)).apply {
                setTitle(episode.title)
                setDescription("Downloading episode: ${episode.title}")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "Tenerife por la Verdad/Podcast_${episode.id}.mp3"
                )
                addRequestHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            downloadManager.enqueue(request)
        } catch (e: Exception) {
            Log.e("AudioActionHandler", "Failed to enqueue download", e)
        }
    }

    suspend fun shareEpisode(episode: Episode) {
        withContext(Dispatchers.IO) {
            try {
                // Download file to cache
                val cacheDir = File(context.cacheDir, "audio_cache")
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }
                
                // Clear old files to save space
                cacheDir.listFiles()?.forEach { if(it.isFile && it.name.endsWith(".mp3")) it.delete() }

                val file = File(cacheDir, "Shared_Podcast_${episode.id}.mp3")

                val url = URL(episode.audioUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStream = connection.inputStream
                    val outputStream = FileOutputStream(file)
                    
                    inputStream.use { input ->
                        outputStream.use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "audio/mpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    
                    context.startActivity(Intent.createChooser(shareIntent, "Share Episode").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } else {
                    Log.e("AudioActionHandler", "Download for share failed: ${connection.responseCode}")
                }
            } catch (e: Exception) {
                Log.e("AudioActionHandler", "Error sharing episode", e)
            }
        }
    }
}