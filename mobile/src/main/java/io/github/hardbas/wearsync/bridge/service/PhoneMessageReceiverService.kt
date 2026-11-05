package io.github.hardbas.wearsync.bridge.service

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class PhoneMessageReceiverService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            PATH_ACTION_MEASURE_NOW, PATH_ACTION_SYNC_RINGCONN -> {
                Log.i(TAG, "Received trigger from Galaxy Watch: ${messageEvent.path}")
                triggerRingConnSync()
            }
        }
    }

    private suspend fun sendSyncStatusToWatch(status: String) {
        try {
            val nodeClient = Wearable.getNodeClient(this)
            val messageClient = Wearable.getMessageClient(this)
            val nodes = nodeClient.connectedNodes.await()
            val data = status.toByteArray(Charsets.UTF_8)
            for (node in nodes) {
                messageClient.sendMessage(node.id, PATH_SYNC_STATUS, data).await()
            }
            Log.i(TAG, "Sent sync status '$status' to ${nodes.size} watch node(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed sending sync status '$status' to watch", e)
        }
    }

    private fun triggerRingConnSync() {
        scope.launch {
            sendSyncStatusToWatch("START")
            try {
                // Passively scrape if the user already has RingConn open in the foreground,
                // never launching RingConn's Activity on screen automatically.
                //
                // publishFollowUp = false: this caller publishes one forced update below, so the
                // scrape must not fire an unforced push of its own nor schedule the follow-up
                // Health Connect sync (another full read and a 15s wake lock) for the same tap.
                if (RingConnAccessibilityService.isRunning()) {
                    RingConnAccessibilityService.instance
                        ?.scrapeWindowContent(persist = true, publishFollowUp = false)
                }

                // Read best available vitals silently (pokes RingConn's background widget worker
                // via system_server onUpdate() and merges Health Connect + WidgetHost + cache).
                val vitals = RingConnTelemetryReader.readBestAvailableVitalsSuspend(this@PhoneMessageReceiverService)

                val success = WearDataSyncService(this@PhoneMessageReceiverService).syncVitalsToWatch(vitals, force = true)
                Log.i(TAG, "Silent RingConn vitals sync to Watch: HR=${vitals.lastHeartRate}, SpO2=${vitals.lastSpo2}, success=$success")
                sendSyncStatusToWatch(if (success) "SUCCESS" else "ERROR")
            } catch (e: Exception) {
                Log.e(TAG, "Error triggering silent sync on RingConn", e)
                sendSyncStatusToWatch("ERROR")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        Log.d(TAG, "PhoneMessageReceiverService destroyed, scope cancelled")
    }

    companion object {
        private const val TAG = "PhoneMsgReceiver"
        const val PATH_ACTION_MEASURE_NOW = "/action/measure_now"
        const val PATH_ACTION_SYNC_RINGCONN = "/action/sync_ringconn"
        const val PATH_SYNC_STATUS = "/action/sync_status"
    }
}
