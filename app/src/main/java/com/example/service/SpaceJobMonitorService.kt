package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

class SpaceJobMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "spacedeck_jobs_channel"
        const val NOTIFICATION_ID = 4201

        const val ACTION_UPDATE = "com.example.service.ACTION_UPDATE"
        const val ACTION_COMPLETE = "com.example.service.ACTION_COMPLETE"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_BODY = "extra_body"

        fun updateProgress(context: Context, title: String, body: String) {
            val intent = Intent(context, SpaceJobMonitorService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // Gracefully degrade if background FGS start is restricted
            }
        }

        fun notifyComplete(context: Context, title: String, body: String) {
            val intent = Intent(context, SpaceJobMonitorService::class.java).apply {
                action = ACTION_COMPLETE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                stop(context)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, SpaceJobMonitorService::class.java))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_UPDATE
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "SpaceDeck Job Running"
        val body = intent?.getStringExtra(EXTRA_BODY) ?: "Monitoring remote Hugging Face Space…"

        when (action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_COMPLETE -> {
                stopForeground(STOP_FOREGROUND_DETACH)
                val nm = getSystemService(NotificationManager::class.java)
                val notif = buildNotification(title, body, ongoing = false)
                nm.notify(NOTIFICATION_ID + 1, notif)
                stopSelf()
            }
            else -> {
                val notif = buildNotification(title, body, ongoing = true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFICATION_ID, notif)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(title: String, body: String, ongoing: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_jobs_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_jobs_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
