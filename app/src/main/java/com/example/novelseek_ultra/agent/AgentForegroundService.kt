package com.example.novelseek_ultra.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the process alive (and shows an ongoing notification with a Stop action) while the agent is
 * running, so a long autonomous run continues with the screen off / app backgrounded. The agent
 * loop itself stays in the ViewModel scope; this service's job is to mark the work as foreground
 * and surface controls.
 */
class AgentForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var holdRunLocks = false
    private var lastStartId = 0
    private var preserveRecoveryNotification = false

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!holdRunLocks) return

            val controller = AgentController.active
            if (controller == null) {
                leaveRecoveryNotificationAndStop(
                    startId = lastStartId,
                    text = "智能体进程已中断，打开应用后将自动诊断恢复",
                )
                return
            }

            // A bounded lock is renewed by a live service heartbeat. If the service itself stalls,
            // Android releases the CPU lock shortly afterwards instead of leaving it held forever.
            acquireLocks()
            runCatching {
                controller.diagnoseAndRecoverIfStalled(AgentRunDiagnosisTrigger.BACKGROUND_HEARTBEAT)
            }
            if (holdRunLocks) heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_STOP -> {
                runCatching { AgentController.active?.stop() }
                cancelHeartbeat()
                releaseLocks()
                stopForegroundCompat()
                cancelNotifications(includeRecovery = true)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (flags and START_FLAG_REDELIVERY != 0 && AgentController.active == null) {
                    // A redelivered Service intent does not recreate the Compose ViewModel or its
                    // coroutine. Avoid displaying a false "running" state: leave an actionable
                    // notification and let the recreated controller diagnose the checkpoint.
                    leaveRecoveryNotificationAndStop(
                        startId = startId,
                        text = "智能体进程已中断，打开应用后将自动诊断恢复",
                    )
                    return START_REDELIVER_INTENT
                }

                val text = intent?.getStringExtra(EXTRA_TEXT) ?: "智能体执行中"
                preserveRecoveryNotification = false
                startForeground(NOTIF_ID, buildNotification(text))
                if (intent?.getBooleanExtra(EXTRA_HOLD_LOCKS, true) == true) {
                    holdRunLocks = true
                    acquireLocks()   // keep CPU + Wi-Fi awake only while work is actively running
                    scheduleHeartbeat()
                } else {
                    cancelHeartbeat()
                    releaseLocks()   // awaiting input/confirmation needs a notification, not wake locks
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    /** Android 15 limits the cumulative duration of a dataSync foreground service. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        runCatching { AgentController.active?.handleForegroundServiceTimeout() }
        leaveRecoveryNotificationAndStop(
            startId = startId,
            text = "后台运行时限已到，打开应用后将自动诊断恢复",
            promoteBeforeStop = false,
        )
    }

    override fun onDestroy() {
        cancelHeartbeat()
        releaseLocks()
        if (!preserveRecoveryNotification) stopForegroundCompat()
        super.onDestroy()
    }

    /** Partial wake lock (CPU) + high-perf Wi-Fi lock so SSE streaming isn't dropped on screen-off. */
    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NovelSeek:agent")
                .also { it.setReferenceCounted(false); wakeLock = it }
            // Releasing first guarantees that acquire(timeout) starts a fresh timeout window even
            // on vendors whose non-reference-counted WakeLock does not extend an existing timer.
            if (wl.isHeld) wl.release()
            wl.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifiLock ?: wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NovelSeek:agent")
                .also { it.setReferenceCounted(false); wifiLock = it }
            if (!lock.isHeld) lock.acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        wifiLock = null
    }

    private fun scheduleHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeat)
        heartbeatHandler.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MS)
    }

    private fun cancelHeartbeat() {
        holdRunLocks = false
        heartbeatHandler.removeCallbacks(heartbeat)
    }

    private fun leaveRecoveryNotificationAndStop(
        startId: Int,
        text: String,
        promoteBeforeStop: Boolean = true,
    ) {
        preserveRecoveryNotification = true
        cancelHeartbeat()
        releaseLocks()
        try {
            val notification = runCatching { buildRecoveryNotification(text) }.getOrNull()
            if (notification != null) {
                if (promoteBeforeStop) {
                    // Redelivered starts still have the normal five-second foreground obligation.
                    runCatching { startForeground(NOTIF_ID, notification) }
                    runCatching { stopForegroundDetached() }
                    runCatching { notificationManager().cancel(NOTIF_ID) }
                } else {
                    // Android already revoked the dataSync foreground allowance in onTimeout().
                    // Calling startForeground again here can itself throw and prevent timely stop.
                    runCatching { stopForegroundCompat() }
                }
                runCatching { notificationManager().notify(RECOVERY_NOTIF_ID, notification) }
            }
        } finally {
            // Android requires timeout callbacks to stop within a few seconds. This must run even
            // if channel creation or notification APIs fail on a vendor build.
            if (startId > 0) stopSelf(startId) else stopSelf()
        }
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

    private fun buildRecoveryNotification(text: String): android.app.Notification {
        ensureChannel(this)
        val openPi = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NovelSeek 智能体需要恢复")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .apply { openPi?.let { setContentIntent(it) } }
            .build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun stopForegroundDetached() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_DETACH)
        else @Suppress("DEPRECATION") stopForeground(false)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun cancelNotifications(includeRecovery: Boolean) {
        notificationManager().cancel(NOTIF_ID)
        notificationManager().cancel(WAITING_NOTIF_ID)
        if (includeRecovery) notificationManager().cancel(RECOVERY_NOTIF_ID)
    }

    companion object {
        private const val CHANNEL_ID = "agent_run"
        private const val NOTIF_ID = 4242
        private const val WAITING_NOTIF_ID = 4243
        private const val RECOVERY_NOTIF_ID = 4244
        private const val ACTION_STOP = "com.example.novelseek_ultra.agent.STOP"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_HOLD_LOCKS = "holdLocks"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 75_000L

        fun start(ctx: Context, text: String, holdLocks: Boolean = true) {
            runCatching {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).apply {
                    cancel(WAITING_NOTIF_ID)
                    cancel(RECOVERY_NOTIF_ID)
                }
            }
            val i = Intent(ctx, AgentForegroundService::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_HOLD_LOCKS, holdLocks)
            runCatching { ContextCompat.startForegroundService(ctx, i) }
        }

        /** Re-deliver to refresh the notification text (no-op if not started). */
        fun update(ctx: Context, text: String) = start(ctx, text)

        /**
         * Waiting for a reply/confirmation is not active data synchronization. Stop the foreground
         * service and leave only a dismissible status notification, so it consumes neither locks
         * nor Android 15's dataSync foreground-service time allowance.
         */
        fun showWaiting(ctx: Context, text: String) {
            runCatching { ctx.stopService(Intent(ctx, AgentForegroundService::class.java)) }
            runCatching {
                ensureChannel(ctx)
                val openPi = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
                    PendingIntent.getActivity(
                        ctx,
                        2,
                        it,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                }
                val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setContentTitle("NovelSeek 智能体")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setSmallIcon(ctx.applicationInfo.icon)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .apply { openPi?.let { setContentIntent(it) } }
                    .build()
                val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(NOTIF_ID)
                manager.notify(WAITING_NOTIF_ID, notification)
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, AgentForegroundService::class.java)) }
            runCatching {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).apply {
                    cancel(NOTIF_ID)
                    cancel(WAITING_NOTIF_ID)
                }
            }
        }

        /** The foreground UI now owns recovery feedback, so its detached notice is no longer needed. */
        fun dismissRecoveryNotice(ctx: Context) {
            runCatching {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(RECOVERY_NOTIF_ID)
            }
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
