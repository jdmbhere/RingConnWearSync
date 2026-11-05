package io.github.hardbas.wearsync.bridge.service

import io.github.hardbas.wearsync.bridge.R
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.hardbas.wearsync.bridge.data.RingConnVitals
import kotlinx.coroutines.*

class RingConnAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scrapeJob: Job? = null
    private var delayedHcSyncJob: Job? = null
    private var lastScrapeTimestamp = 0L

    /**
     * True while the morning peek owns the scrape loop.
     *
     * During a peek RingConn stays in the foreground for several seconds and emits a stream of
     * TYPE_WINDOW_CONTENT_CHANGED events. Without this flag each event would walk the whole Flutter
     * semantics tree and persist/push another snapshot, which is pure battery burn: the peek already
     * scrapes on its own schedule and pushes exactly once at the end.
     */
    @Volatile private var peekInProgress = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "RingConnAccessibilityService connected and active!")
        RingConnForegroundService.start(this)

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 200
            packageNames = arrayOf("com.gdjztech.ringconn")
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        if (pkg == "com.gdjztech.ringconn") {
            // The peek polls the tree itself, so event-driven scrapes are redundant while it runs.
            if (peekInProgress) return

            // Debounce rapid window changes
            val now = System.currentTimeMillis()
            if (now - lastScrapeTimestamp < 400L) return
            lastScrapeTimestamp = now

            scrapeJob?.cancel()
            scrapeJob = serviceScope.launch {
                delay(300L) // Wait for Flutter semantics tree to settle
                scrapeWindowContent()
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "RingConnAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scrapeJob?.cancel()
        delayedHcSyncJob?.cancel()
        serviceScope.cancel()
        Log.i(TAG, "RingConnAccessibilityService destroyed")
    }

    /**
     * Traverses the active Flutter semantics tree and extracts all metrics.
     *
     * @param persist when true (default) the result is written to SharedPreferences
     *   and pushed to the watch. Polling callers pass false so a single peek
     *   produces exactly one watch update instead of one per poll.
     * @param publishFollowUp when true (default) the scrape publishes its own unforced watch
     *   update and schedules a follow-up Health Connect sync, which is what the passive
     *   event-driven path wants. A caller that publishes a forced update of its own passes
     *   false: the scrape is still persisted, but it no longer fires a second Data Layer push
     *   and a second full Health Connect read (with its own wake lock) for the same user action.
     */
    fun scrapeWindowContent(persist: Boolean = true, publishFollowUp: Boolean = true): RingConnVitals? {
        val collectedTokens = mutableListOf<String>()

        try {
            // 1. Traverse root in active window (only if RingConn)
            rootInActiveWindow?.let { root ->
                if (root.packageName?.toString() == "com.gdjztech.ringconn") {
                    traverseNode(root, collectedTokens)
                }
                root.recycle()
            }

            // 2. Fall back to interactive windows only if rootInActiveWindow was empty or not RingConn
            if (collectedTokens.isEmpty()) {
                try {
                    for (w in windows) {
                        val winRoot = w.root ?: continue
                        if (winRoot.packageName?.toString() == "com.gdjztech.ringconn") {
                            traverseNode(winRoot, collectedTokens)
                        }
                        winRoot.recycle()
                    }
                } catch (e: Exception) {
                    // Ignore window enumeration errors
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error traversing accessibility tree", e)
        }

        if (collectedTokens.isEmpty()) return null

        lastScrapeTimestamp = System.currentTimeMillis()
        val currentVitals = RingConnTelemetryReader.readBestAvailableVitals(this)
        val parsedVitals = RingConnTelemetryParser.parseTokens(collectedTokens, currentVitals)

        val hasMeaningfulData = parsedVitals.lastHeartRate > 0 ||
                parsedVitals.sleepScore > 0 ||
                (parsedVitals.sleepDeepMins + parsedVitals.sleepRemMins + parsedVitals.sleepLightMins) > 0 ||
                parsedVitals.activityScore > 0 ||
                parsedVitals.liveSteps > 0 ||
                parsedVitals.lastSpo2 > 0 ||
                parsedVitals.lastHrv > 0 ||
                parsedVitals.stressScore > 0 ||
                parsedVitals.skinTempOffset != 0f

        if (hasMeaningfulData) {
            if (persist) {
                RingConnTelemetryReader.saveToPreferences(
                    context = this,
                    vitals = parsedVitals,
                    source = getString(R.string.source_ringconn_direct_short)
                )

                // The follow-up push and the delayed Health Connect sync belong to the passive
                // path, where this scrape is the whole user-visible update. A caller that
                // publishes a forced sync itself opts out, so one action stays one push.
                if (publishFollowUp) {
                    serviceScope.launch {
                        WearDataSyncService(this@RingConnAccessibilityService).syncVitalsToWatch(parsedVitals)
                    }
                    scheduleDelayedHealthConnectSync()
                }

                val followUp = if (publishFollowUp) " & synced" else " & persisted only (the caller publishes)"
                Log.i(TAG, "100% Exact RingConn telemetry scraped$followUp: HR=${parsedVitals.lastHeartRate}, Sleep=${parsedVitals.sleepScore}, Act=${parsedVitals.activityScore}, Steps=${parsedVitals.liveSteps}, Bat=${parsedVitals.ringBattery}%")
            } else {
                Log.d(TAG, "Scraped (not persisted): HR=${parsedVitals.lastHeartRate}, Sleep=${parsedVitals.sleepScore}")
            }
            return parsedVitals
        }

        return null
    }

    /**
     * Scrapes current window content, optionally performs ACTION_SCROLL_FORWARD on the first scrollable node
     * in com.gdjztech.ringconn to reveal lower cards (like Skin Temperature), waits 600ms, and scrapes again.
     */
    suspend fun performGentleScrollAndScrape(persist: Boolean = true): RingConnVitals? {
        val initialVitals = scrapeWindowContent(persist)
        try {
            var scrollPerformed = false
            fun findAndScroll(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                val nodePkg = node.packageName?.toString()
                if (nodePkg != null && nodePkg != "com.gdjztech.ringconn") return false
                if (node.isScrollable) {
                    val scrolled = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    if (scrolled) return true
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    val res = findAndScroll(child)
                    child.recycle()
                    if (res) return true
                }
                return false
            }

            rootInActiveWindow?.let { root ->
                if (root.packageName?.toString() == "com.gdjztech.ringconn") {
                    scrollPerformed = findAndScroll(root)
                }
                root.recycle()
            }

            if (!scrollPerformed) {
                for (w in windows) {
                    val winRoot = w.root ?: continue
                    if (winRoot.packageName?.toString() == "com.gdjztech.ringconn") {
                        scrollPerformed = findAndScroll(winRoot)
                    }
                    winRoot.recycle()
                    if (scrollPerformed) break
                }
            }

            if (scrollPerformed) {
                Log.d(TAG, "Performed gentle scroll forward on RingConn UI, waiting 600ms...")
                delay(600L)
                return scrapeWindowContent(persist) ?: initialVitals
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error performing gentle scroll and scrape", e)
        }
        return initialVitals
    }

    /**
     * Wakes RingConn app with Accessibility Service context (exempt from Android 14/15 BAL restrictions),
     * polls the semantics tree until the sleep record actually renders, pushes one single update to the
     * watch, and navigates back to the Home screen.
     *
     * A fixed delay was not enough: RingConn needs to resume Flutter, re-attach BLE and pull the night's
     * record before the sleep card shows a real score, so the peek used to leave before sleep arrived.
     */
    suspend fun wakeAndScrapeRingConnApp(): RingConnVitals? {
        peekInProgress = true
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage("com.gdjztech.ringconn")
            if (launchIntent == null) {
                Log.w(TAG, "RingConn launch intent was null")
                return null
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(launchIntent)
            Log.i(TAG, "Launched RingConn via AccessibilityService context (BAL exempt)")

            val vitals = awaitSleepRecord()
            returnToHome()
            return vitals
        } catch (e: Exception) {
            Log.e(TAG, "Error in wakeAndScrapeRingConnApp", e)
            returnToHome()
            return null
        } finally {
            peekInProgress = false
        }
    }

    /**
     * Polls the semantics tree until RingConn renders a real sleep score.
     *
     * Intermediate scrapes are deliberately not persisted: the watch receives exactly one
     * forced update per morning peek instead of one per poll.
     */
    private suspend fun awaitSleepRecord(): RingConnVitals? {
        val startedAt = SystemClock.elapsedRealtime()
        var attempt = 0
        var best: RingConnVitals? = null

        while (MorningSyncAwaitPolicy.shouldKeepPolling(
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                vitals = best,
                ringConnForeground = isRingConnForeground()
            )
        ) {
            delay(
                if (attempt == 0) MorningSyncAwaitPolicy.MIN_SETTLE_MS
                else MorningSyncAwaitPolicy.POLL_INTERVAL_MS
            )
            val scraped = if (MorningSyncAwaitPolicy.shouldScroll(attempt)) {
                performGentleScrollAndScrape(persist = false)
            } else {
                scrapeWindowContent(persist = false)
            }
            if (scraped != null) best = scraped
            attempt++
        }

        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val finalVitals = best
        if (finalVitals == null) {
            val leftApp = elapsedMs >= MorningSyncAwaitPolicy.LAUNCH_GRACE_MS && !isRingConnForeground()
            Log.w(
                TAG,
                if (leftApp) {
                    "Morning peek aborted after ${elapsedMs}ms and $attempt polls: RingConn was no longer in front"
                } else {
                    "Morning peek gave up after ${elapsedMs}ms and $attempt polls: RingConn rendered no sleep data"
                }
            )
            return null
        }

        RingConnTelemetryReader.saveToPreferences(
            context = this,
            vitals = finalVitals,
            source = getString(R.string.source_ringconn_direct_short)
        )
        WearDataSyncService(this).syncVitalsToWatch(finalVitals, force = true)
        scheduleDelayedHealthConnectSync()
        Log.i(
            TAG,
            "Morning peek captured sleep=${finalVitals.sleepScore} after ${elapsedMs}ms and $attempt polls " +
                "(HR=${finalVitals.lastHeartRate}, Bat=${finalVitals.ringBattery}%)"
        )
        return finalVitals
    }

    private fun isRingConnForeground(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            root.packageName?.toString() == "com.gdjztech.ringconn"
        } finally {
            root.recycle()
        }
    }

    private fun returnToHome() {
        if (performGlobalAction(GLOBAL_ACTION_HOME)) return
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Fallback home intent error", e)
        }
    }

    private fun scheduleDelayedHealthConnectSync() {
        delayedHcSyncJob?.cancel()
        delayedHcSyncJob = serviceScope.launch {
            delay(6_000L)
            RingConnForegroundService.syncTelemetryNow(applicationContext)
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        val nodePkg = node.packageName?.toString()
        if (nodePkg != null && nodePkg != "com.gdjztech.ringconn") return
        try {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverseNode(child, out)
                child.recycle()
            }
        } catch (e: Exception) {
            // Node could have become invalid during tree walk
        }
    }

    companion object {
        private const val TAG = "RingConnAccessibility"
        var instance: RingConnAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        fun triggerImmediateScrape(): RingConnVitals? {
            return instance?.scrapeWindowContent()
        }
    }
}
