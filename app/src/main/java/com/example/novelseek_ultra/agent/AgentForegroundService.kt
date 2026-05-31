package com.example.novelseek_ultra.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the process alive (and shows an ongoing notification with a Stop action) while the agent is
 * running, so a long autonomous run continues with the screen off / app backgrounded. The agent
 * loop itself stays in the ViewModel scope; this service's job is to mark the work as foreground
 * and surface controls.
 */
class AgentForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                runCatching { AgentController.active?.stop() }
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val text = intent?.getStringExtra(EXTRA_TEXT) ?: "智能体执行中"
                startForeground(NOTIF_ID, buildNotification(text))
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String): android.app.Notification {
        ensureChannel(this)
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, AgentForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openPi = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NovelSeek 智能体")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { openPi?.let { setContentIntent(it) } }
            .addAction(0, "停止", stopPi)
            .build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    companion object {
        private const val CHANNEL_ID = "agent_run"
        private const val NOTIF_ID = 4242
        private const val ACTION_STOP = "com.example.novelseek_ultra.agent.STOP"
        private const val EXTRA_TEXT = "text"

        fun start(ctx: Context, text: String) {
            val i = Intent(ctx, AgentForegroundService::class.java).putExtra(EXTRA_TEXT, text)
            runCatching { ContextCompat.startForegroundService(ctx, i) }
        }

        /** Re-deliver to refresh the notification text (no-op if not started). */
        fun update(ctx: Context, text: String) = start(ctx, text)

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, AgentForegroundService::class.java)) }
        }

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "智能体运行", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "智能体后台执行时的状态通知"
                    },
                )
            }
        }
    }
}
