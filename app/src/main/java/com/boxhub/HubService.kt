package com.boxhub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps the LAN server alive when the dashboard Activity goes away.
 *
 * Without this, BoxHub's port only exists while its Activity is alive. HOME
 * does not destroy an Activity, so the port survives that — but on a 2 GB box
 * an empty background process is exactly what the low-memory killer reaches
 * for once a video player starts. A foreground service is orders of magnitude
 * less killable, which is the whole point of the "保持运行" switch.
 *
 * Deliberately START_NOT_STICKY: the pairing code is regenerated on every
 * start, so silently resurrecting the service would invalidate the phone's
 * stored code with no visible cause. Better to let the interruption be real
 * and let the dashboard say so.
 */
class HubService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        App.acquire(OWNER)
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "foreground service up, port ${App.PORT}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == ACTION_STOP) {
            Log.i(TAG, "stop requested from the notification")
            App.setKeepAlive(false)
            stopSelf()
            return START_NOT_STICKY
        }
        // Keep the notification current if the box gained or lost an address.
        if (App.isRunning()) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification())
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        App.release(OWNER)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0)

        val content = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), pendingFlags
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HubService::class.java).setAction(ACTION_STOP), pendingFlags
        )

        val text = App.lanAddresses().firstOrNull()
            ?.let { "http://$it:${App.PORT}   ·   配对码 ${App.pin}" }
            ?: "服务已启动"

        val b = Notification.Builder(this, CHANNEL_ID)
            // Deliberately not the launcher icon: Android masks a notification
            // icon to its alpha channel and tints it white, so a full-bleed
            // opaque launcher tile comes out as a solid white square. This is a
            // dedicated alpha-only silhouette.
            .setSmallIcon(R.drawable.ic_stat_pin)
            .setContentTitle("BoxHub 运行中")
            .setContentText(text)
            .setContentIntent(content)
            .setOngoing(true)
            .setShowWhen(false)
            // The pairing code is shown on the TV itself; repeating it on a
            // potentially lock-screen-visible notification is needless.
            .setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())

        return b.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "BoxHub 运行状态", NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            description = "局域网遥控服务运行时常驻通知"
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "BoxHub/Service"
        private const val CHANNEL_ID = "boxhub_running"
        private const val NOTIFICATION_ID = 0x4258
        private const val ACTION_STOP = "com.boxhub.action.STOP"
        private const val OWNER = "service"

        @Suppress("unused")
        val FOREGROUND_TYPE: Int
            get() = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
    }
}