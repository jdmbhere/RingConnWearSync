package io.github.hardbas.wearsync.bridge.service

import io.github.hardbas.wearsync.bridge.R
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.hardbas.wearsync.bridge.PhoneBridgeMainActivity
import io.github.hardbas.wearsync.bridge.data.RingConnVitals
import io.github.hardbas.wearsync.bridge.receiver.WatchdogReceiver
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

class RingConnForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null
    private var lastUserPresentSyncMs: Long = 0L

    private val userPresentReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                val ctx = context ?: this@RingConnForegroundService
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastUserPresentSyncMs > 60_000L) {
                    lastUserPresentSyncMs = nowMs
                    Log.i(TAG, "User present (unlock) detected: triggering silent background sync")
                    syncTelemetryNow(ctx)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildForegroundNotification(getString(R.string.phone_connected_watch))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed startForeground with connectedDevice, falling back to dataSync", e)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Failed startForeground fallback to dataSync", e2)
                try {
                    startForeground(NOTIFICATION_ID, notification)
                } catch (e3: Exception) {
                    Log.e(TAG, "Failed default startForeground", e3)
                }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(userPresentReceiver, android.content.IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(userPresentReceiver, android.content.IntentFilter(Intent.ACTION_USER_PRESENT))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not register userPresentReceiver", e)
        }

        RingConnWidgetHostReader.startPersistentListening(this) {
            syncTelemetryNow(this@RingConnForegroundService)
        }

        scheduleWatchdog(this)
        startPeriodicSync()
    }

    private var lastNotificationText: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scheduleWatchdog(this)
        if (syncJob?.isActive != true) {
            startPeriodicSync()
        }
        val isForce = intent?.action == ACTION_SYNC_NOW
        scope.launch {
            triggerSync(force = isForce)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            unregisterReceiver(userPresentReceiver)
        } catch (_: Exception) {}
        RingConnWidgetHostReader.stopPersistentListening()
        syncJob?.cancel()
        scope.cancel()
        scheduleWatchdog(this)
        super.onDestroy()
    }

    private fun startPeriodicSync() {
        syncJob = scope.launch {
            while (isActive) {
                triggerSync(force = false)
                // The widget this reads refreshes every 15-30 min, so polling faster than 5 min
                // only holds wake locks without producing fresher data. Overnight the ring
                // consolidates sleep instead of producing readings, so the poll backs off to the
                // night interval: three wake cycles for the whole night instead of ninety.
                val hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                delay(SyncBatteryPolicy.pollIntervalMs(hourOfDay))
            }
        }
    }

    private suspend fun triggerSync(force: Boolean = false) {
        if (!syncMutex.tryLock()) {
            Log.d(TAG, "Sync already in progress, skipping overlapping trigger")
            return
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RingConnWearSync:SyncWakeLock")
        wakeLock?.acquire(15_000L)
        try {
            // Poke RingConn Flutter background worker
            try {
                val bgIntent = Intent("es.antonborri.home_widget.action.BACKGROUND").apply {
                    setPackage("com.gdjztech.ringconn")
                }
                sendBroadcast(bgIntent)
            } catch (e: Exception) {
                Log.d(TAG, "HomeWidget background broadcast error: ${e.message}")
            }

            val vitals = RingConnTelemetryReader.readBestAvailableVitalsSuspend(this@RingConnForegroundService)
            val success = WearDataSyncService(this@RingConnForegroundService).syncVitalsToWatch(vitals, force = force)
            val noteText = if (success) {
                getString(R.string.phone_sync_note, vitals.lastHeartRate, vitals.lastSpo2, vitals.ringBattery)
            } else {
                getString(R.string.phone_searching_watch)
            }
            updateNotification(noteText)
            scheduleWatchdog(this@RingConnForegroundService)
            Log.d(TAG, "Periodic sync completed: success=$success")
        } catch (e: Exception) {
            Log.e(TAG, "Error in periodic sync", e)
        } finally {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
            } catch (_: Exception) {}
            syncMutex.unlock()
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val launchIntent = Intent(this, PhoneBridgeMainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RingConn Wear Sync")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun updateNotification(contentText: String) {
        if (contentText == lastNotificationText) return
        lastNotificationText = contentText
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildForegroundNotification(contentText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RingConn Wear Sync Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.phone_channel_desc)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "RingConnFgs"
        const val CHANNEL_ID = "ringconn_wear_sync_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_SYNC_NOW = "io.github.hardbas.wearsync.bridge.SYNC_NOW"
        const val ACTION_WATCHDOG_TICK = "io.github.hardbas.wearsync.bridge.WATCHDOG_TICK"
        private const val WATCHDOG_REQUEST_CODE = 9001
        private val syncMutex = Mutex()

        fun syncTelemetryNow(context: Context, force: Boolean = false) {
            val appCtx = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                if (!syncMutex.tryLock()) {
                    Log.d(TAG, "Sync already in progress, skipping overlapping trigger")
                    return@launch
                }
                val powerManager = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RingConnWearSync:SyncWakeLock")
                wakeLock?.acquire(15_000L)
                try {
                    try {
                        val bgIntent = Intent("es.antonborri.home_widget.action.BACKGROUND").apply {
                            setPackage("com.gdjztech.ringconn")
                        }
                        appCtx.sendBroadcast(bgIntent)
                    } catch (e: Exception) {
                        Log.d(TAG, "HomeWidget background broadcast error: ${e.message}")
                    }

                    val vitals = RingConnTelemetryReader.readBestAvailableVitalsSuspend(appCtx)
                    val success = WearDataSyncService(appCtx).syncVitalsToWatch(vitals, force = force)
                    Log.d(TAG, "syncTelemetryNow completed: success=$success")
                    scheduleWatchdog(appCtx)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in syncTelemetryNow", e)
                } finally {
                    try {
                        if (wakeLock?.isHeld == true) {
                            wakeLock.release()
                        }
                    } catch (_: Exception) {}
                    syncMutex.unlock()
                }
            }
        }

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, RingConnForegroundService::class.java).apply {
                if (action != null) setAction(action)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            scheduleWatchdog(context)
        }

        fun scheduleWatchdog(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(context, WatchdogReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    WATCHDOG_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val intervalMs = SyncBatteryPolicy.watchdogIntervalMs(hourOfDay)
                val triggerAtMs = SystemClock.elapsedRealtime() + intervalMs
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
                Log.d(TAG, "Watchdog scheduled in ${intervalMs / 60_000} minutes (hour=$hourOfDay)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule watchdog", e)
            }
        }
    }
}
