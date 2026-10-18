package com.rtorgil.txlv_downloader

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class AudioActionHandler(private val context: Context) {

    fun getDownloadedEpisodeUri(episode: Episode, folderName: String): Uri? {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val treeUriStr = prefs.getString("download_tree_uri", null)
        val fileName = "Podcast_${episode.id}.mp3"

        if (treeUriStr != null) {
            try {
                val treeUri = Uri.parse(treeUriStr)
                val documentFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                val file = documentFile?.findFile(fileName)
                if (file != null && file.exists()) {
                    return file.uri
                }
            } catch (e: Exception) {
                Log.e("AudioActionHandler", "Error checking SAF download", e)
            }
        }
        
        // Check standard downloads folder
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = File(downloadsDir, folderName)
        val localFile = File(folder, fileName)
        if (localFile.exists()) {
            return Uri.fromFile(localFile)
        }

        return null
    }

    fun downloadEpisode(episode: Episode, folderName: String) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val treeUriStr = prefs.getString("download_tree_uri", null)

        if (treeUriStr != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val treeUri = Uri.parse(treeUriStr)
                    val documentFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                    val fileName = "Podcast_${episode.id}.mp3"
                    
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Descargando en carpeta personalizada...", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    
                    val newFile = documentFile?.createFile("audio/mpeg", fileName)
                    if (newFile != null) {
                        val request = okhttp3.Request.Builder().url(episode.audioUrl)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                            .build()
                        val client = okhttp3.OkHttpClient()
                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) {
                            val outputStream = context.contentResolver.openOutputStream(newFile.uri)
                            outputStream?.use { out ->
                                response.body?.byteStream()?.use { input ->
                                    input.copyTo(out)
                                }
                            }
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "Descarga completada", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } else {
                           Log.e("AudioActionHandler", "Download failed: ${response.code}")
                        }
                    }
                } catch(e: Exception) {
                    Log.e("AudioActionHandler", "Error downloading to SAF", e)
                }
            }
        } else {
            try {
                val fileName = "Podcast_${episode.id}.mp3"
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val request = DownloadManager.Request(Uri.parse(episode.audioUrl)).apply {
                    setTitle(episode.title)
                    setDescription("Downloading episode: ${episode.title}")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        "$folderName/$fileName"
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
    }

    fun deleteEpisode(episode: Episode, folderName: String): Boolean {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val treeUriStr = prefs.getString("download_tree_uri", null)
        val fileName = "Podcast_${episode.id}.mp3"
        var deleted = false

        if (treeUriStr != null) {
            try {
                val treeUri = Uri.parse(treeUriStr)
                val documentFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                val file = documentFile?.findFile(fileName)
                if (file != null && file.exists()) {
                    deleted = file.delete()
                }
            } catch (e: Exception) {
                Log.e("AudioActionHandler", "Error deleting SAF download", e)
            }
        }
        
        if (!deleted) {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val folder = File(downloadsDir, folderName)
            val localFile = File(folder, fileName)
            if (localFile.exists()) {
                deleted = localFile.delete()
            }
        }
        
        return deleted
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
