package com.rtorgil.txlv_downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Xml
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

class PodcastWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val url = URL(PodcastViewModel.RSS_FEED_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val inputStream = connection.inputStream
                val parser: XmlPullParser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(inputStream, null)

                var eventType = parser.eventType
                var latestTitle = ""
                var latestUrl = ""
                var inItem = false

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    val name = parser.name ?: ""
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            if (name.equals("item", ignoreCase = true)) {
                                inItem = true
                            } else if (inItem && name.equals("title", ignoreCase = true)) {
                                latestTitle = parser.nextText()
                            } else if (inItem && name.equals("enclosure", ignoreCase = true)) {
                                latestUrl = parser.getAttributeValue(null, "url") ?: ""
                                break // We only need the first item
                            }
                        }
                    }
                    eventType = parser.next()
                }
                inputStream.close()
                
                val idMatch = """listen_mn_(\d+)_1\.mp3""".toRegex().find(latestUrl) ?: """_(?:rf|mf)_(\d+)_""".toRegex().find(latestUrl)
                val newEpisodeId = idMatch?.groupValues?.get(1) ?: latestUrl.hashCode().toString()

                val prefs = appContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val notifiedId = prefs.getString("latest_notified_episode_id", "")

                if (newEpisodeId.isNotEmpty() && newEpisodeId != notifiedId) {
                    sendNotification(latestTitle)
                    prefs.edit().putString("latest_notified_episode_id", newEpisodeId).apply()
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun sendNotification(title: String) {
        val channelId = "podcast_channel"
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Nuevos Episodios",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Make sure this exists or use another one
            .setContentTitle("¡Nuevo episodio disponible!")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
