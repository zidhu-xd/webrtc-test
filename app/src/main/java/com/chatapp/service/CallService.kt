package com.chatapp.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.chatapp.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Foreground Service for active calls.
 *
 * Android requires a foreground service with a persistent notification
 * to use the microphone and camera in the background.
 * This ensures the call stays alive even when the user navigates away.
 *
 * Declared in AndroidManifest.xml with:
 *   android:foregroundServiceType="microphone|camera"
 */
@AndroidEntryPoint
class CallService : Service() {

    companion object {
        const val CHANNEL_ID = "call_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_END_CALL = "com.chatapp.ACTION_END_CALL"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END_CALL) {
            stopSelf()
            return START_NOT_STICKY
        }

        val remoteName = intent?.getStringExtra("remote_name") ?: "Unknown"
        val isVideo = intent?.getBooleanExtra("is_video", false) ?: false

        startForeground(NOTIFICATION_ID, buildNotification(remoteName, isVideo))
        return START_STICKY
    }

    private fun buildNotification(remoteName: String, isVideo: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endCallIntent = PendingIntent.getService(
            this, 0,
            Intent(this, CallService::class.java).apply { action = ACTION_END_CALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("${if (isVideo) "Video" else "Voice"} call with $remoteName")
            .setContentText("Tap to return to call")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "End Call", endCallIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows during active calls"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
