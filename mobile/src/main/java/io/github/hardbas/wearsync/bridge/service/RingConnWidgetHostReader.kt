package io.github.hardbas.wearsync.bridge.service

import io.github.hardbas.wearsync.bridge.R
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import io.github.hardbas.wearsync.bridge.data.RingConnVitals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object RingConnWidgetHostReader {
    private const val TAG = "RingConnWidgetHost"
    const val HOST_ID = 4488
    private const val PREFS_NAME = "ringconn_widget_bindings"
    private const val PUSH_DEBOUNCE_MS = 2_500L
    /**
     * RingConn's BaseAppWidgetReceiver enforces a 180-second cooldown (180_000 ms) between
     * internal HomeWidgetBackgroundReceiver broadcasts inside onUpdate(). Keeping our
     * system_server bind poke strictly above 180s ensures every poke triggers a fresh
     * headless Flutter background sync instead of hitting the cooldown guard.
     */
    const val BACKGROUND_POKE_COOLDOWN_MS = 185_000L

    private val RINGCONN_PROVIDERS = listOf(
        ComponentName("com.gdjztech.ringconn", "com.gdjztech.remotewidget.MediumHomeAppWidgetReceiver"),
        ComponentName("com.gdjztech.ringconn", "com.gdjztech.remotewidget.DataIndicatorsWidgetReceiver"),
        ComponentName("com.gdjztech.ringconn", "com.gdjztech.remotewidget.DataIndicatorsLargeWidgetReceiver"),
        ComponentName("com.gdjztech.ringconn", "com.gdjztech.remotewidget.SportIndicatorsWidgetReceiver"),
        ComponentName("com.gdjztech.ringconn", "com.gdjztech.remotewidget.HomeAppWidgetReceiver"),
        ComponentName("com.gdjztech.ringconn", "com.gdjztech.remotewidget.SportIndicatorsLargeWidgetReceiver")
    )

    private class PushAwareAppWidgetHostView(
        context: Context,
        private val onRemoteViewsUpdated: () -> Unit
    ) : AppWidgetHostView(context) {
        override fun updateAppWidget(remoteViews: RemoteViews?) {
            super.updateAppWidget(remoteViews)
            if (remoteViews != null) {
                onRemoteViewsUpdated()
            }
        }
    }

    private class CleanableAppWidgetHost(
        context: Context,
        hostId: Int,
        private val onRemoteViewsUpdated: () -> Unit
    ) : AppWidgetHost(context, hostId) {
        public override fun clearViews() {
            super.clearViews()
        }

        override fun onCreateView(
            context: Context,
            appWidgetId: Int,
            appWidget: AppWidgetProviderInfo?
        ): AppWidgetHostView {
            return PushAwareAppWidgetHostView(context, onRemoteViewsUpdated)
        }
    }

    private var appWidgetHost: CleanableAppWidgetHost? = null
    private var isListening = false
    @Volatile private var persistentListeningActive = false
    @Volatile private var inReadCycle = false
    @Volatile private var lastPushTriggerElapsedMs = 0L
    @Volatile private var lastBackgroundPokeElapsedMs = 0L
    @Volatile private var onPushCallback: (() -> Unit)? = null
    private val boundWidgetMap = mutableMapOf<ComponentName, Int>()
    private var pendingBindWidgetId: Int = -1
    private var pendingBindProvider: ComponentName? = null

    fun hasAnyBoundWidget(context: Context): Boolean {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        for (comp in RINGCONN_PROVIDERS) {
            val widgetId = boundWidgetMap[comp] ?: prefs.getInt("widget_id_${comp.className}", -1)
            if (widgetId != -1) {
                val info = appWidgetManager.getAppWidgetInfo(widgetId)
                if (info != null && info.provider == comp) {
                    boundWidgetMap[comp] = widgetId
                    return true
                }
            }
        }
        return false
    }

    /**
     * Starts persistent AppWidgetHost listening so Android's AppWidgetService pushes
     * RemoteViews updates to us in real time whenever RingConn refreshes a widget.
     */
    fun startPersistentListening(context: Context, onRemoteViewsPushed: () -> Unit) {
        onPushCallback = onRemoteViewsPushed
        persistentListeningActive = true
        try {
            val host = getHost(context.applicationContext)
            host.startListening()
            isListening = true
            Log.i(TAG, "Persistent AppWidgetHost ($HOST_ID) push listener active.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed starting persistent AppWidgetHost listener: ${e.message}")
        }
    }

    fun stopPersistentListening() {
        persistentListeningActive = false
        onPushCallback = null
        val host = appWidgetHost ?: return
        runCatching { host.clearViews() }
        runCatching { host.stopListening() }
        isListening = false
    }

    /**
     * Tries to bind all installed RingConn widget providers silently via bindAppWidgetIdIfAllowed.
     * If Android requires explicit user authorization (e.g. after installing APK without ADB grantbind),
     * returns an ACTION_APPWIDGET_BIND Intent for the Activity to launch the native Android permission dialog.
     */
    fun prepareNextBindIntent(context: Context): Intent? {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val host = getHost(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        for (comp in RINGCONN_PROVIDERS) {
            val providerInfo = appWidgetManager.installedProviders.firstOrNull { it.provider == comp } ?: continue
            var widgetId = boundWidgetMap[comp] ?: prefs.getInt("widget_id_${comp.className}", -1)
            if (widgetId != -1) {
                val existingInfo = appWidgetManager.getAppWidgetInfo(widgetId)
                if (existingInfo != null && existingInfo.provider == comp) {
                    boundWidgetMap[comp] = widgetId
                    continue
                } else {
                    try { host.deleteAppWidgetId(widgetId) } catch (_: Exception) {}
                    prefs.edit().remove("widget_id_${comp.className}").apply()
                    boundWidgetMap.remove(comp)
                    widgetId = -1
                }
            }

            widgetId = host.allocateAppWidgetId()
            val bound = appWidgetManager.bindAppWidgetIdIfAllowed(widgetId, providerInfo.provider)
            if (bound) {
                boundWidgetMap[comp] = widgetId
                prefs.edit().putInt("widget_id_${comp.className}", widgetId).apply()
                Log.i(TAG, "Silently bound widget ID $widgetId to $comp")
            } else {
                pendingBindWidgetId = widgetId
                pendingBindProvider = comp
                prefs.edit().putInt("widget_id_${comp.className}", widgetId).apply()
                Log.i(TAG, "Requesting native user bind dialog for widget ID $widgetId ($comp)")
                return Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, comp)
                }
            }
        }
        return null
    }

    fun onWidgetBindResult(context: Context, resultOk: Boolean) {
        val widgetId = pendingBindWidgetId
        val comp = pendingBindProvider
        pendingBindWidgetId = -1
        pendingBindProvider = null
        if (widgetId == -1 || comp == null) return

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingInfo = appWidgetManager.getAppWidgetInfo(widgetId)
        if (existingInfo != null && existingInfo.provider == comp) {
            boundWidgetMap[comp] = widgetId
            prefs.edit().putInt("widget_id_${comp.className}", widgetId).apply()
            Log.i(TAG, "User authorized widget bind for ID $widgetId ($comp)")
        } else if (!resultOk) {
            prefs.edit().remove("widget_id_${comp.className}").apply()
            boundWidgetMap.remove(comp)
            try { getHost(context).deleteAppWidgetId(widgetId) } catch (_: Exception) {}
        }
    }

    private fun getHost(context: Context): CleanableAppWidgetHost {
        var h = appWidgetHost
        if (h == null) {
            h = CleanableAppWidgetHost(context.applicationContext, HOST_ID) {
                if (persistentListeningActive && !inReadCycle) {
                    val nowElapsed = SystemClock.elapsedRealtime()
                    if (nowElapsed - lastPushTriggerElapsedMs >= PUSH_DEBOUNCE_MS) {
                        lastPushTriggerElapsedMs = nowElapsed
                        Log.i(TAG, "Reactive push: RingConn updated widget RemoteViews in background")
                        onPushCallback?.invoke()
                    }
                }
            }
            appWidgetHost = h
        }
        return h
    }

    fun hasValidTelemetry(vitals: RingConnVitals): Boolean {
        return vitals.lastHeartRate > 0 ||
                vitals.sleepScore > 0 ||
                (vitals.sleepDeepMins + vitals.sleepRemMins + vitals.sleepLightMins) > 0 ||
                vitals.activityScore > 0 ||
                vitals.liveSteps > 0 ||
                vitals.ringBattery > 0 ||
                vitals.lastSpo2 > 0 ||
                vitals.avgSpo2 > 0 ||
                vitals.lastHrv > 0 ||
                vitals.avgHrv > 0 ||
                vitals.stressScore > 0 ||
                vitals.skinTempOffset != 0f ||
                vitals.skinTempBaseline > 0f
    }

    fun shouldPokeBackgroundWorker(
        nowElapsedMs: Long,
        lastPokeElapsedMs: Long,
        cooldownMs: Long = BACKGROUND_POKE_COOLDOWN_MS
    ): Boolean {
        if (lastPokeElapsedMs <= 0L) return true
        return (nowElapsedMs - lastPokeElapsedMs) >= cooldownMs
    }

    /**
     * Triggers RingConn's BaseAppWidgetReceiver.onUpdate() via Android's system_server (uid 1000).
     *
     * Direct broadcasts to es.antonborri.home_widget.HomeWidgetBackgroundReceiver fail because
     * that receiver has android:exported="false" in RingConn's manifest, and updateAppWidgetOptions()
     * only triggers onAppWidgetOptionsChanged() without invoking HomeWidgetBackgroundReceiver.
     * By transiently binding a widget ID through AppWidgetManager, system_server dispatches
     * ACTION_APPWIDGET_UPDATE (onUpdate()) into RingConn, which internally broadcasts
     * es.antonborri.home_widget.action.BACKGROUND from RingConn's own UID.
     */
    private suspend fun pokeRingConnBackgroundWorkerIfEligible(
        appWidgetManager: AppWidgetManager,
        host: AppWidgetHost
    ) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!shouldPokeBackgroundWorker(nowElapsed, lastBackgroundPokeElapsedMs)) {
            return
        }
        val targetProvider = RINGCONN_PROVIDERS.firstOrNull { comp ->
            appWidgetManager.installedProviders.any { it.provider == comp }
        } ?: return

        var pokeWidgetId = -1
        try {
            pokeWidgetId = host.allocateAppWidgetId()
            val bound = appWidgetManager.bindAppWidgetIdIfAllowed(pokeWidgetId, targetProvider)
            if (bound) {
                lastBackgroundPokeElapsedMs = nowElapsed
                Log.i(TAG, "Poked RingConn BaseAppWidgetReceiver.onUpdate() via system_server (temp widgetId=$pokeWidgetId)")
                delay(350L)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Background worker poke error: ${e.message}")
        } finally {
            if (pokeWidgetId != -1) {
                runCatching { host.deleteAppWidgetId(pokeWidgetId) }
            }
        }
    }

    /**
     * Evaluates whether Phase 3 (toggling updateAppWidgetOptions to force Jetpack Glance
     * to recompose RemoteViews) must run.
     *
     * Critically, [collectedTokens] must be evaluated against an empty [RingConnVitals]
     * baseline rather than [baseVitals]: [baseVitals] carries cached values from earlier
     * in the day, which previously caused [hasValidTelemetry] to return true even when
     * the widget views themselves returned zero metrics.
     */
    fun shouldTriggerGlanceRecompose(
        collectedTokens: List<String>,
        baseVitals: RingConnVitals,
        hadNewAllocation: Boolean
    ): Boolean {
        if (hadNewAllocation || collectedTokens.isEmpty()) return true
        val widgetOnly = RingConnTelemetryParser.parseTokens(collectedTokens, RingConnVitals())
        return !hasValidTelemetry(widgetOnly)
    }

    /**
     * Returns responsive (widthDp, heightDp) options matching each RingConn Glance
     * receiver's size class so Large and Medium widgets render their full layouts
     * instead of emitting "unsupported size" or truncating rows.
     */
    fun widgetDimensionsDp(className: String, toggle: Boolean): Pair<Int, Int> {
        return when {
            className.contains("Large", ignoreCase = true) -> {
                360 to (if (toggle) 321 else 320)
            }
            className.contains("Medium", ignoreCase = true) ||
                className.endsWith(".HomeAppWidgetReceiver") -> {
                360 to (if (toggle) 201 else 200)
            }
            else -> {
                276 to (if (toggle) 201 else 200)
            }
        }
    }

    /**
     * Executes persistent widget host extraction.
     * Binds once, caches widget IDs in memory & SharedPreferences, triggers Jetpack Glance
     * via updateAppWidgetOptions when widget-only tokens are missing or newly allocated,
     * inflates views with provider-appropriate dimensions, and extracts live numbers from
     * RingConn's RemoteViews.
     */
    suspend fun readRingConnWidgets(context: Context): RingConnVitals? = withContext(Dispatchers.Main) {
        if (pendingBindWidgetId != -1) {
            Log.d(TAG, "Skipping background widget read while user bind dialog is open")
            return@withContext null
        }
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val host = getHost(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        inReadCycle = true
        try {
            if (!isListening) {
                host.startListening()
                isListening = true
                Log.d(TAG, "AppWidgetHost ($HOST_ID) started listening.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "AppWidgetHost startListening error: ${e.message}")
        }

        try {
            Log.i(TAG, "Starting RingConn WidgetHost extraction...")

            // Trigger RingConn Flutter background worker via system_server onUpdate() before reading RemoteViews
            pokeRingConnBackgroundWorkerIfEligible(appWidgetManager, host)

            val activeBindings = mutableListOf<Pair<ComponentName, Int>>()
            var hadNewAllocation = false

            // Phase 1: Ensure each provider is persistently allocated and bound
            for (comp in RINGCONN_PROVIDERS) {
                try {
                    val providerInfo = appWidgetManager.installedProviders.firstOrNull { it.provider == comp }
                    if (providerInfo == null) {
                        Log.w(TAG, "Provider not installed on device: $comp")
                        continue
                    }

                    var widgetId = boundWidgetMap[comp] ?: prefs.getInt("widget_id_${comp.className}", -1)

                    // Validate existing widget ID with system_server
                    if (widgetId != -1) {
                        val existingInfo = appWidgetManager.getAppWidgetInfo(widgetId)
                        if (existingInfo == null || existingInfo.provider != comp) {
                            Log.d(TAG, "Cached widget ID $widgetId invalid or mismatched for $comp; reallocating.")
                            try { host.deleteAppWidgetId(widgetId) } catch (_: Exception) {}
                            prefs.edit().remove("widget_id_${comp.className}").apply()
                            boundWidgetMap.remove(comp)
                            widgetId = -1
                        }
                    }

                    if (widgetId == -1) {
                        widgetId = host.allocateAppWidgetId()
                        val bound = appWidgetManager.bindAppWidgetIdIfAllowed(widgetId, comp)
                        if (bound) {
                            boundWidgetMap[comp] = widgetId
                            prefs.edit().putInt("widget_id_${comp.className}", widgetId).apply()
                            hadNewAllocation = true
                            Log.i(TAG, "Persistently bound new widget ID $widgetId to $comp")
                        } else {
                            Log.w(TAG, "Failed to bind widget ID $widgetId to $comp (grantbind needed?)")
                            try { host.deleteAppWidgetId(widgetId) } catch (_: Exception) {}
                            continue
                        }
                    } else {
                        boundWidgetMap[comp] = widgetId
                    }

                    activeBindings.add(comp to widgetId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error configuring widget for provider $comp", e)
                }
            }

            if (activeBindings.isEmpty()) {
                Log.w(TAG, "No active RingConn widget bindings available.")
                return@withContext null
            }

            fun scrapeActiveWidgets(): List<String> {
                val tokens = mutableListOf<String>()
                for ((comp, widgetId) in activeBindings) {
                    try {
                        val providerInfo = appWidgetManager.getAppWidgetInfo(widgetId) ?: continue
                        val hostView = host.createView(context, widgetId, providerInfo)
                        if (hostView != null) {
                            hostView.measure(
                                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                            )
                            hostView.layout(0, 0, 1080, 1080)

                            val viewTexts = mutableListOf<String>()
                            extractTextsFromView(hostView, viewTexts)
                            Log.d(TAG, "Extracted ${viewTexts.size} strings from widget ID $widgetId ($comp): $viewTexts")
                            if (viewTexts.isNotEmpty()) {
                                if (tokens.isNotEmpty()) {
                                    tokens.add(RingConnTelemetryParser.WIDGET_BOUNDARY)
                                }
                                tokens.addAll(viewTexts)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed inflating widget ID $widgetId ($comp): ${e.message}")
                    }
                }
                return tokens
            }

            val baseVitals = RingConnTelemetryReader.readBestAvailableVitals(context)

            // Phase 2: First pass - scrape directly without sending 6 widget options updates
            var collectedTokens = scrapeActiveWidgets()

            // Phase 3: Evaluate widget-only telemetry (not cached baseVitals!) to decide whether to trigger Glance recompose
            if (shouldTriggerGlanceRecompose(collectedTokens, baseVitals, hadNewAllocation)) {
                Log.d(TAG, "First pass widget-only telemetry empty or newly allocated; toggling updateAppWidgetOptions to trigger Glance compose...")
                for ((comp, widgetId) in activeBindings) {
                    try {
                        val toggleKey = "toggle_h_${comp.className}"
                        val currentToggle = prefs.getBoolean(toggleKey, false)
                        val nextToggle = !currentToggle
                        prefs.edit().putBoolean(toggleKey, nextToggle).apply()
                        val (widthDp, heightDp) = widgetDimensionsDp(comp.className, nextToggle)

                        val options = Bundle().apply {
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
                        }
                        appWidgetManager.updateAppWidgetOptions(widgetId, options)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating options for widget $comp", e)
                    }
                }

                // Wait for Jetpack Glance inside com.gdjztech.ringconn to compose & publish RemoteViews
                val retryWait = if (hadNewAllocation) 1200L else 450L
                delay(retryWait)

                collectedTokens = scrapeActiveWidgets()
            }

            val widgetOnlyVitals = if (collectedTokens.isNotEmpty()) {
                RingConnTelemetryParser.parseTokens(collectedTokens, RingConnVitals())
            } else null

            if (widgetOnlyVitals != null && hasValidTelemetry(widgetOnlyVitals)) {
                val mergedVitals = RingConnTelemetryParser.parseTokens(collectedTokens, baseVitals)
                RingConnTelemetryReader.saveToPreferences(
                    context = context,
                    vitals = mergedVitals,
                    source = context.getString(R.string.source_widgethost_short)
                )
                Log.i(TAG, "Successfully extracted RingConn vitals via WidgetHost: $mergedVitals")
                return@withContext mergedVitals
            }

            return@withContext null
        } finally {
            inReadCycle = false
            if (!persistentListeningActive) {
                runCatching {
                    host.clearViews()
                }.onFailure { Log.w(TAG, "Error clearing AppWidgetHost views: ${it.message}") }
                runCatching {
                    host.stopListening()
                }.onFailure { Log.w(TAG, "Error stopping AppWidgetHost listening: ${it.message}") }
                isListening = false
                Log.d(TAG, "AppWidgetHost ($HOST_ID) cleared views and stopped listening.")
            }
        }
    }

    private fun extractTextsFromView(view: View, out: MutableList<String>) {
        if (view is TextView && !view.text.isNullOrBlank()) {
            out.add(view.text.toString().trim())
        }
        val cd = view.contentDescription
        if (!cd.isNullOrBlank()) {
            out.add(cd.toString().trim())
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                extractTextsFromView(view.getChildAt(i), out)
            }
        }
    }
}
