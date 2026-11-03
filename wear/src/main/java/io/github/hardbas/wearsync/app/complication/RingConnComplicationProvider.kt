package io.github.hardbas.wearsync.app.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import io.github.hardbas.wearsync.app.R
import io.github.hardbas.wearsync.app.data.DailyHrRange
import io.github.hardbas.wearsync.app.data.RingConnHistoryStore
import io.github.hardbas.wearsync.app.data.RingConnVitals
import io.github.hardbas.wearsync.app.tile.DailySummaryTileService
import io.github.hardbas.wearsync.app.tile.HeartRateTileService
import io.github.hardbas.wearsync.app.tile.SleepTileService
import io.github.hardbas.wearsync.app.tile.StressTileService
import io.github.hardbas.wearsync.app.tile.VitalsTileService
import io.github.hardbas.wearsync.app.ui.DailySummaryDetailActivity
import io.github.hardbas.wearsync.app.ui.HeartRateDetailActivity
import io.github.hardbas.wearsync.app.ui.SleepDetailActivity
import io.github.hardbas.wearsync.app.ui.StressDetailActivity
import io.github.hardbas.wearsync.app.ui.TemperatureDetailActivity
import io.github.hardbas.wearsync.app.ui.VitalsDetailActivity
import io.github.hardbas.wearsync.app.ui.WearMainActivity

/**
 * Universal Complication Data Builder supporting:
 * - SHORT_TEXT
 * - RANGED_VALUE
 * - GOAL_PROGRESS
 * - LONG_TEXT
 * - MONOCHROMATIC_IMAGE
 * - SMALL_IMAGE
 *
 * Ensures full compatibility with Samsung Watch Face Format (WFF) slots and Galaxy Wearable.
 */
object ComplicationDataFactory {
    fun build(
        context: Context,
        type: ComplicationType,
        shortText: String,
        title: String,
        longText: String,
        contentDesc: String,
        iconRes: Int,
        currentValue: Float,
        minValue: Float,
        maxValue: Float,
        targetValue: Float = maxValue,
        tapIntent: PendingIntent? = null
    ): ComplicationData? {
        val icon = MonochromaticImage.Builder(
            Icon.createWithResource(context, iconRes)
        ).build()
        val descText = PlainComplicationText.Builder(contentDesc).build()
        val titleText = PlainComplicationText.Builder(title).build()
        val shortValText = PlainComplicationText.Builder(shortText).build()
        val longValText = PlainComplicationText.Builder(longText).build()

        // ComplicationType.GOAL_PROGRESS and GoalProgressComplicationData only exist
        // from Android 13 (API 33), while the watch app supports API 30. Referencing
        // them needs an explicit version gate; below 33 the request falls through to
        // the types that do exist, and on 33+ the data is built exactly as before.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            type == ComplicationType.GOAL_PROGRESS
        ) {
            val target = if (targetValue > 0f) targetValue else 100f
            val builder = GoalProgressComplicationData.Builder(currentValue, target, descText)
                .setText(shortValText)
                .setTitle(titleText)
                .setMonochromaticImage(icon)
            if (tapIntent != null) builder.setTapAction(tapIntent)
            return builder.build()
        }

        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                val builder = ShortTextComplicationData.Builder(shortValText, descText)
                    .setTitle(titleText)
                    .setMonochromaticImage(icon)
                if (tapIntent != null) builder.setTapAction(tapIntent)
                builder.build()
            }
            ComplicationType.RANGED_VALUE -> {
                val safeMax = if (maxValue > minValue) maxValue else minValue + 1f
                val clamped = currentValue.coerceIn(minValue, safeMax)
                val builder = RangedValueComplicationData.Builder(clamped, minValue, safeMax, descText)
                    .setText(shortValText)
                    .setTitle(titleText)
                    .setMonochromaticImage(icon)
                if (tapIntent != null) builder.setTapAction(tapIntent)
                builder.build()
            }
            ComplicationType.LONG_TEXT -> {
                val builder = LongTextComplicationData.Builder(longValText, descText)
                    .setTitle(titleText)
                    .setMonochromaticImage(icon)
                if (tapIntent != null) builder.setTapAction(tapIntent)
                builder.build()
            }
            ComplicationType.MONOCHROMATIC_IMAGE -> {
                val builder = MonochromaticImageComplicationData.Builder(icon, descText)
                if (tapIntent != null) builder.setTapAction(tapIntent)
                builder.build()
            }
            ComplicationType.SMALL_IMAGE -> {
                val smallImg = SmallImage.Builder(
                    Icon.createWithResource(context, iconRes),
                    SmallImageType.ICON
                ).build()
                val builder = SmallImageComplicationData.Builder(smallImg, descText)
                if (tapIntent != null) builder.setTapAction(tapIntent)
                builder.build()
            }
            else -> null
        }
    }
}

/**
 * 1. Heart Rate Complication Provider (BPM)
 */
class RingConnComplicationProvider : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return ComplicationDataFactory.build(
            context = this,
            type = type,
            shortText = "74",
            title = getString(R.string.bpm_unit),
            longText = "74 ${getString(R.string.bpm_unit)}",
            contentDesc = getString(R.string.complication_desc, "74"),
            iconRes = R.drawable.ic_comp_heart,
            currentValue = 74f,
            minValue = 40f,
            maxValue = 180f
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val vitals = getLatestVitals(this)
        val hr = vitals.lastHeartRate
        val textValue = if (hr > 0) "$hr" else "--"
        val minHr = if (vitals.minHeartRate > 0) vitals.minHeartRate.toFloat() else 40f
        val maxHr = if (vitals.maxHeartRate > 0) vitals.maxHeartRate.toFloat() else 180f
        val tapIntent = PendingIntent.getActivity(
            this, 202, Intent(this, HeartRateDetailActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return ComplicationDataFactory.build(
            context = this,
            type = request.complicationType,
            shortText = textValue,
            title = getString(R.string.bpm_unit),
            longText = if (hr > 0) "$hr ${getString(R.string.bpm_unit)}" else "-- ${getString(R.string.bpm_unit)}",
            contentDesc = getString(R.string.complication_desc, textValue),
            iconRes = R.drawable.ic_comp_heart,
            currentValue = if (hr > 0) hr.toFloat() else minHr,
            minValue = minHr,
            maxValue = maxHr,
            tapIntent = tapIntent
        )
    }

    companion object {
        private const val PREFS_NAME = "RingConnWearCache"
        const val KEY_CACHE_EPOCH_DAY = "cache_epoch_day"

        fun ensureDailyRollover(
            prefs: android.content.SharedPreferences,
            todayEpochDay: Long = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toEpochDay()
        ): Boolean {
            val savedEpochDay = prefs.getLong(KEY_CACHE_EPOCH_DAY, -1L)
            if (savedEpochDay == todayEpochDay) {
                return false
            }
            Log.i("RingConnComplication", "Watch Midnight Rollover (saved=$savedEpochDay -> today=$todayEpochDay): resetting daily counters.")
            prefs.edit().apply {
                putInt("steps_live", 0)
                putInt("cal_live", 0)
                putInt("activity_score", 0)
                putInt("active_mins", 0)
                putInt("sleep_score", 0)
                putInt("sleep_deep_mins", 0)
                putInt("sleep_rem_mins", 0)
                putInt("sleep_light_mins", 0)
                putInt("sleep_awake_mins", 0)
                putInt("sleep_efficiency_pct", 0)
                putInt("hr_phase_deep", 0)
                putInt("hr_phase_rem", 0)
                putInt("hr_phase_light", 0)
                putFloat("odi_drops_per_hour", 0.0f)
                putInt("min_sleeping_spo2", 0)
                putFloat("sleeping_hr_dip_pct", 0.0f)
                putInt("nap_minutes", 0)
                putInt("stand_hours", 0)
                putInt("min_hr", 0)
                putInt("max_hr", 0)
                putInt("zone1_mins", 0)
                putInt("zone2_mins", 0)
                putInt("zone3_mins", 0)
                putInt("zone4_mins", 0)
                putInt("zone5_mins", 0)
                putInt("stress_dawn", 0)
                putInt("stress_morning", 0)
                putInt("stress_afternoon", 0)
                putInt("stress_night", 0)
                putInt("basal_calories", 0)
                putFloat("distance_km", 0.0f)
                putString("sleep_insight", "")
                putString("activity_insight", "")
                putString("stress_insight", "")
                putString("vitals_insight", "")
                putLong(KEY_CACHE_EPOCH_DAY, todayEpochDay)
                apply()
            }
            RingConnHistoryStore.purgeDaySnapshot(prefs, todayEpochDay)
            return true
        }

        fun rolloverVitalsForNewDay(vitals: RingConnVitals): RingConnVitals {
            return vitals.copy(
                sleepScore = 0,
                activityScore = 0,
                activeMinutes = 0,
                liveSteps = 0,
                liveCalorie = 0,
                minHeartRate = 0,
                maxHeartRate = 0,
                zone1Mins = 0,
                zone2Mins = 0,
                zone3Mins = 0,
                zone4Mins = 0,
                zone5Mins = 0,
                sleepDeepMins = 0,
                sleepRemMins = 0,
                sleepLightMins = 0,
                sleepAwakeMins = 0,
                sleepInsight = "",
                activityInsight = "",
                stressInsight = "",
                vitalsInsight = "",
                odiDropsPerHour = 0.0f,
                minSleepingSpo2 = 0,
                sleepingHrDipPct = 0.0f,
                napMinutes = 0,
                standHours = 0,
                hrPhaseDeep = 0,
                hrPhaseRem = 0,
                hrPhaseLight = 0,
                sleepEfficiencyPct = 0,
                stressDawn = 0,
                stressMorning = 0,
                stressAfternoon = 0,
                stressNight = 0,
                basalCalories = 0,
                distanceKm = 0.0f
            )
        }

        fun getLatestVitals(context: Context): RingConnVitals {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ensureDailyRollover(prefs)
            val hr = prefs.getInt("hr_last", 0)
            val battery = prefs.getInt("ring_battery", 0)
            val sleep = prefs.getInt("sleep_score", 0)
            val spo2 = prefs.getInt("spo2_last", 0)
            val sleepInsight = prefs.getString("sleep_insight", "") ?: ""
            val stressInsight = prefs.getString("stress_insight", "") ?: ""
            val activityInsight = prefs.getString("activity_insight", "") ?: ""
            val vitalsInsight = prefs.getString("vitals_insight", "") ?: ""
            val odiDropsPerHour = prefs.getFloat("odi_drops_per_hour", 0.0f)
            val minSleepingSpo2 = prefs.getInt("min_sleeping_spo2", 0)
            val sleepingHrDipPct = prefs.getFloat("sleeping_hr_dip_pct", 0.0f)
            val napMinutes = prefs.getInt("nap_minutes", 0)
            val bedtimeWindow = prefs.getString("bedtime_window", "") ?: ""
            val standHours = prefs.getInt("stand_hours", 0)
            val hrPhaseDeep = prefs.getInt("hr_phase_deep", 0)
            val hrPhaseRem = prefs.getInt("hr_phase_rem", 0)
            val hrPhaseLight = prefs.getInt("hr_phase_light", 0)
            val sleepEfficiencyPct = prefs.getInt("sleep_efficiency_pct", 0)
            val stressDawn = prefs.getInt("stress_dawn", 0)
            val stressMorning = prefs.getInt("stress_morning", 0)
            val stressAfternoon = prefs.getInt("stress_afternoon", 0)
            val stressNight = prefs.getInt("stress_night", 0)
            val basalCalories = prefs.getInt("basal_calories", 0)
            val distanceKm = prefs.getFloat("distance_km", 0.0f)
            val respiratoryRate = prefs.getFloat("respiratory_rate", 0.0f)

            return RingConnVitals(
                lastHeartRate = hr,
                avgHeartRate = prefs.getInt("hr_avg", hr),
                lastSpo2 = spo2,
                avgSpo2 = prefs.getInt("spo2_avg", spo2),
                lastHrv = prefs.getInt("hrv_last", 0),
                avgHrv = prefs.getInt("hrv_avg", 0),
                ringBattery = battery,
                sleepScore = sleep,
                activityScore = prefs.getInt("activity_score", 0),
                activeMinutes = prefs.getInt("active_mins", 0),
                stressScore = prefs.getInt("stress_score", 0),
                liveSteps = prefs.getInt("steps_live", 0),
                targetSteps = prefs.getInt("steps_target", 10000),
                liveCalorie = prefs.getInt("cal_live", 0),
                targetCalorie = prefs.getInt("cal_target", 500),
                isCharging = prefs.getBoolean("is_charging", false),
                isConnected = prefs.getBoolean("isConnected", true),
                refreshTimestamp = prefs.getLong("timestamp", System.currentTimeMillis() / 1000L),
                minHeartRate = prefs.getInt("min_hr", 0),
                maxHeartRate = prefs.getInt("max_hr", 0),
                zone1Mins = prefs.getInt("zone1_mins", 0),
                zone2Mins = prefs.getInt("zone2_mins", 0),
                zone3Mins = prefs.getInt("zone3_mins", 0),
                zone4Mins = prefs.getInt("zone4_mins", 0),
                zone5Mins = prefs.getInt("zone5_mins", 0),
                sleepDeepMins = prefs.getInt("sleep_deep_mins", 0),
                sleepRemMins = prefs.getInt("sleep_rem_mins", 0),
                sleepLightMins = prefs.getInt("sleep_light_mins", 0),
                sleepAwakeMins = prefs.getInt("sleep_awake_mins", 0),
                skinTempOffset = prefs.getFloat("skin_temp_offset", 0.0f),
                skinTempBaseline = prefs.getFloat("skin_temp_baseline", 0.0f),
                sleepInsight = sleepInsight,
                stressInsight = stressInsight,
                activityInsight = activityInsight,
                vitalsInsight = vitalsInsight,
                odiDropsPerHour = odiDropsPerHour,
                minSleepingSpo2 = minSleepingSpo2,
                sleepingHrDipPct = sleepingHrDipPct,
                napMinutes = napMinutes,
                bedtimeWindow = bedtimeWindow,
                standHours = standHours,
                hrPhaseDeep = hrPhaseDeep,
                hrPhaseRem = hrPhaseRem,
                hrPhaseLight = hrPhaseLight,
                sleepEfficiencyPct = sleepEfficiencyPct,
                stressDawn = stressDawn,
                stressMorning = stressMorning,
                stressAfternoon = stressAfternoon,
                stressNight = stressNight,
                basalCalories = basalCalories,
                distanceKm = distanceKm,
                respiratoryRate = respiratoryRate
            )
        }

        @Volatile private var lastBroadcastSignature: String? = null

        fun computeContentSignature(vitals: RingConnVitals): String {
            return "${vitals.lastHeartRate}_${vitals.avgHeartRate}_${vitals.lastSpo2}_${vitals.avgSpo2}_${vitals.lastHrv}_${vitals.avgHrv}_" +
                "${vitals.sleepScore}_${vitals.activityScore}_${vitals.activeMinutes}_${vitals.stressScore}_${vitals.liveSteps}_" +
                "${vitals.targetSteps}_${vitals.liveCalorie}_${vitals.targetCalorie}_${vitals.ringBattery}_${vitals.isCharging}_${vitals.isConnected}_" +
                "${vitals.minHeartRate}_${vitals.maxHeartRate}_${vitals.zone1Mins}_${vitals.zone2Mins}_${vitals.zone3Mins}_${vitals.zone4Mins}_${vitals.zone5Mins}_" +
                "${vitals.sleepDeepMins}_${vitals.sleepRemMins}_${vitals.sleepLightMins}_${vitals.sleepAwakeMins}_" +
                "${vitals.skinTempOffset}_${vitals.skinTempBaseline}_" +
                "${vitals.sleepInsight}_${vitals.stressInsight}_${vitals.activityInsight}_${vitals.vitalsInsight}_" +
                "${vitals.odiDropsPerHour}_${vitals.minSleepingSpo2}_${vitals.sleepingHrDipPct}_" +
                "${vitals.napMinutes}_${vitals.bedtimeWindow}_${vitals.standHours}_" +
                "${vitals.hrPhaseDeep}_${vitals.hrPhaseRem}_${vitals.hrPhaseLight}_${vitals.sleepEfficiencyPct}_" +
                "${vitals.stressDawn}_${vitals.stressMorning}_${vitals.stressAfternoon}_${vitals.stressNight}_" +
                "${vitals.distanceKm}_${vitals.respiratoryRate}"
        }

        fun getVitals(context: Context): RingConnVitals = getLatestVitals(context)

        fun parseVitalsFromDataMap(dataMap: com.google.android.gms.wearable.DataMap): RingConnVitals =
            io.github.hardbas.wearsync.app.service.WearDataListenerService.parseVitalsFromDataMap(dataMap)

        fun parseVitalsFromJson(json: org.json.JSONObject): RingConnVitals =
            io.github.hardbas.wearsync.app.service.WearDataListenerService.parseVitalsFromJson(json)

        fun saveAndBroadcastVitals(context: Context, vitals: RingConnVitals) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val todayEpochDay = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toEpochDay()
            ensureDailyRollover(prefs, todayEpochDay)

            // Today's range is the union of what the phone reported, what the day
            // already holds and the reading in hand. The widget scrape carries no
            // range of its own, so without this the card loses its range for as
            // long as Health Connect stays silent. Rollover has already emptied
            // the stored values, which is what keeps yesterday out of today.
            val storedRange = DailyHrRange.sanitizeStored(
                stored = DailyHrRange(prefs.getInt("min_hr", 0), prefs.getInt("max_hr", 0)),
                reported = DailyHrRange(vitals.minHeartRate, vitals.maxHeartRate),
                currentHr = vitals.lastHeartRate,
                lastHrv = vitals.lastHrv,
                avgHrv = vitals.avgHrv
            )
            val dayRange = DailyHrRange.widen(
                storedRange,
                DailyHrRange(vitals.minHeartRate, vitals.maxHeartRate),
                DailyHrRange.observed(vitals.lastHeartRate, vitals.refreshTimestamp, todayEpochDay),
                DailyHrRange.observed(vitals.avgHeartRate, vitals.refreshTimestamp, todayEpochDay)
            )
            val merged = vitals.copy(minHeartRate = dayRange.min, maxHeartRate = dayRange.max)

            val contentSignature = computeContentSignature(merged)
            val isDuplicate = (lastBroadcastSignature != null && lastBroadcastSignature == contentSignature)
            lastBroadcastSignature = contentSignature

            prefs.edit().apply {
                putLong(KEY_CACHE_EPOCH_DAY, todayEpochDay)
                putInt("hr_last", vitals.lastHeartRate)
                putInt("hr_avg", vitals.avgHeartRate)
                putInt("spo2_last", vitals.lastSpo2)
                putInt("spo2_avg", vitals.avgSpo2)
                putInt("hrv_last", vitals.lastHrv)
                putInt("hrv_avg", vitals.avgHrv)
                putInt("sleep_score", vitals.sleepScore)
                putInt("activity_score", vitals.activityScore)
                putInt("active_mins", vitals.activeMinutes)
                putInt("stress_score", vitals.stressScore)
                putInt("steps_live", vitals.liveSteps)
                putInt("steps_target", vitals.targetSteps)
                putInt("cal_live", vitals.liveCalorie)
                putInt("cal_target", vitals.targetCalorie)
                putInt("ring_battery", vitals.ringBattery)
                putBoolean("is_charging", vitals.isCharging)
                putBoolean("is_connected", vitals.isConnected)
                putLong("timestamp", vitals.refreshTimestamp)
                putInt("min_hr", merged.minHeartRate)
                putInt("max_hr", merged.maxHeartRate)
                putInt("zone1_mins", vitals.zone1Mins)
                putInt("zone2_mins", vitals.zone2Mins)
                putInt("zone3_mins", vitals.zone3Mins)
                putInt("zone4_mins", vitals.zone4Mins)
                putInt("zone5_mins", vitals.zone5Mins)
                putInt("sleep_deep_mins", vitals.sleepDeepMins)
                putInt("sleep_rem_mins", vitals.sleepRemMins)
                putInt("sleep_light_mins", vitals.sleepLightMins)
                putInt("sleep_awake_mins", vitals.sleepAwakeMins)
                if (vitals.skinTempOffset != 0.0f || vitals.skinTempBaseline > 0.0f) {
                    putFloat("skin_temp_offset", vitals.skinTempOffset)
                    if (vitals.skinTempBaseline > 0.0f) {
                        putFloat("skin_temp_baseline", vitals.skinTempBaseline)
                    }
                }
                putString("sleep_insight", vitals.sleepInsight)
                putString("stress_insight", vitals.stressInsight)
                putString("activity_insight", vitals.activityInsight)
                putString("vitals_insight", vitals.vitalsInsight)
                putFloat("odi_drops_per_hour", vitals.odiDropsPerHour)
                putInt("min_sleeping_spo2", vitals.minSleepingSpo2)
                putFloat("sleeping_hr_dip_pct", vitals.sleepingHrDipPct)
                putInt("nap_minutes", vitals.napMinutes)
                putString("bedtime_window", vitals.bedtimeWindow)
                putInt("stand_hours", vitals.standHours)
                putInt("hr_phase_deep", vitals.hrPhaseDeep)
                putInt("hr_phase_rem", vitals.hrPhaseRem)
                putInt("hr_phase_light", vitals.hrPhaseLight)
                putInt("sleep_efficiency_pct", vitals.sleepEfficiencyPct)
                putInt("stress_dawn", vitals.stressDawn)
                putInt("stress_morning", vitals.stressMorning)
                putInt("stress_afternoon", vitals.stressAfternoon)
                putInt("stress_night", vitals.stressNight)
                putInt("basal_calories", vitals.basalCalories)
                putFloat("distance_km", vitals.distanceKm)
                putFloat("respiratory_rate", vitals.respiratoryRate)
                apply()
            }

            if (isDuplicate) {
                Log.d("RingConnComplication", "Skipping redundant complication and tile broadcast updates (content signature unchanged)")
                return
            }

            try {
                RingConnHistoryStore.recordDailySnapshot(context, merged)
            } catch (e: Exception) {
                Log.e("RingConnComplication", "Failed to record daily history snapshot", e)
            }

            val complicationProviders = listOf(
                RingConnComplicationProvider::class.java,
                RingBatteryComplicationProvider::class.java,
                Spo2ComplicationProvider::class.java,
                SleepScoreComplicationProvider::class.java,
                StepsComplicationProvider::class.java,
                StressComplicationProvider::class.java,
                TemperatureComplicationProvider::class.java,
                ReadinessComplicationService::class.java
            )
            for (provider in complicationProviders) {
                try {
                    ComplicationDataSourceUpdateRequester.create(
                        context,
                        ComponentName(context, provider)
                    ).requestUpdateAll()
                } catch (e: Exception) {
                    Log.e("RingConnComplication", "Failed to update complication: ${provider.simpleName}", e)
                }
            }

            TileService.getUpdater(context).requestUpdate(VitalsTileService::class.java)
            TileService.getUpdater(context).requestUpdate(DailySummaryTileService::class.java)
            TileService.getUpdater(context).requestUpdate(HeartRateTileService::class.java)
            TileService.getUpdater(context).requestUpdate(StressTileService::class.java)
            TileService.getUpdater(context).requestUpdate(SleepTileService::class.java)
        }
    }
}

/**
 * 2. Ring Battery Complication Provider (%)
 */
class RingBatteryComplicationProvider : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return ComplicationDataFactory.build(
            context = this,
            type = type,
            shortText = "85%",
            title = getString(R.string.ring_short),
            longText = getString(R.string.ring_battery, 85),
            contentDesc = getString(R.string.complication_battery_desc, 85),
            iconRes = R.drawable.ic_ringconn_ring,
            currentValue = 85f,
            minValue = 0f,
            maxValue = 100f
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val vitals = RingConnComplicationProvider.getLatestVitals(this)
        val battery = vitals.ringBattery
        val textValue = if (battery > 0) "$battery%" else "--%"
        val tapIntent = PendingIntent.getActivity(
            this, 201, Intent(this, WearMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return ComplicationDataFactory.build(
            context = this,
            type = request.complicationType,
            shortText = textValue,
            title = getString(R.string.ring_short),
            longText = if (battery > 0) getString(R.string.ring_battery, battery) else getString(R.string.ring_short),
            contentDesc = getString(R.string.complication_battery_desc, battery),
            iconRes = R.drawable.ic_ringconn_ring,
            currentValue = battery.coerceIn(0, 100).toFloat(),
            minValue = 0f,
            maxValue = 100f,
            tapIntent = tapIntent
        )
    }
}

/**
 * 3. Blood Oxygen (SpO2) Complication Provider (%)
 */
class Spo2ComplicationProvider : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return ComplicationDataFactory.build(
            context = this,
            type = type,
            shortText = "98%",
            title = "SpO2",
            longText = "98% SpO2",
            contentDesc = getString(R.string.complication_spo2_desc, 98),
            iconRes = R.drawable.ic_comp_spo2,
            currentValue = 98f,
            minValue = 80f,
            maxValue = 100f
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val vitals = RingConnComplicationProvider.getLatestVitals(this)
        val spo2 = vitals.lastSpo2
        val textValue = if (spo2 > 0) "$spo2%" else "--%"
        val tapIntent = PendingIntent.getActivity(
            this, 203, Intent(this, VitalsDetailActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return ComplicationDataFactory.build(
            context = this,
            type = request.complicationType,
            shortText = textValue,
            title = "SpO2",
            longText = if (spo2 > 0) "$spo2% SpO2" else "--% SpO2",
            contentDesc = getString(R.string.complication_spo2_desc, spo2),
            iconRes = R.drawable.ic_comp_spo2,
            currentValue = if (spo2 > 0) spo2.toFloat().coerceIn(80f, 100f) else 80f,
            minValue = 80f,
            maxValue = 100f,
            tapIntent = tapIntent
        )
    }
}

/**
 * 4. Sleep Score Complication Provider
 */
class SleepScoreComplicationProvider : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return ComplicationDataFactory.build(
            context = this,
            type = type,
            shortText = "82",
            title = getString(R.string.sleep_short),
            longText = "Sleep: 82",
            contentDesc = getString(R.string.complication_sleep_desc, "82"),
            iconRes = R.drawable.ic_comp_sleep,
            currentValue = 82f,
            minValue = 0f,
            maxValue = 100f
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val vitals = RingConnComplicationProvider.getLatestVitals(this)
        val score = vitals.sleepScore
        val totalSleepMins = vitals.sleepDeepMins + vitals.sleepRemMins + vitals.sleepLightMins
        val textValue = if (score > 0) {
            "$score"
        } else if (totalSleepMins > 0) {
            val h = totalSleepMins / 60
            val m = totalSleepMins % 60
            "${h}h${m}"
        } else {
            "--"
        }

        val tapIntent = PendingIntent.getActivity(
            this, 204, Intent(this, SleepDetailActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return ComplicationDataFactory.build(
            context = this,
            type = request.complicationType,
            shortText = textValue,
            title = getString(R.string.sleep_short),
            longText = if (score > 0) "${getString(R.string.sleep_short)}: $score" else getString(R.string.sleep_short),
            contentDesc = getString(R.string.complication_sleep_desc, textValue),
            iconRes = R.drawable.ic_comp_sleep,
            currentValue = if (score > 0) score.toFloat().coerceIn(0f, 100f) else 0f,
            minValue = 0f,
            maxValue = 100f,
            tapIntent = tapIntent
        )
    }
}

/**
 * 5. Daily Steps Complication Provider
 */
class StepsComplicationProvider : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return ComplicationDataFactory.build(
            context = this,
            type = type,
            shortText = "6.5k",
            title = getString(R.string.steps_short),
            longText = "6,500 ${getString(R.string.steps_short)}",
            contentDesc = getString(R.string.complication_steps_desc, 6500),
            iconRes = R.drawable.ic_comp_steps,
            currentValue = 6500f,
            minValue = 0f,
            maxValue = 10000f,
            targetValue = 10000f
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val vitals = RingConnComplicationProvider.getLatestVitals(this)
        val steps = vitals.liveSteps
        val target = if (vitals.targetSteps > 0) vitals.targetSteps else 10000
        val textValue = if (steps >= 10000) {
            String.format("%.1fk", steps / 1000.0)
        } else if (steps > 0) {
            "$steps"
        } else {
            "0"
        }

        val tapIntent = PendingIntent.getActivity(
            this, 205, Intent(this, DailySummaryDetailActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return ComplicationDataFactory.build(
            context = this,
            type = request.complicationType,
            shortText = textValue,
            title = getString(R.string.steps_short),
            longText = "$steps / $target ${getString(R.string.steps_short)}",
            contentDesc = getString(R.string.complication_steps_desc, steps),
            iconRes = R.drawable.ic_comp_steps,
            currentValue = steps.toFloat().coerceIn(0f, target.toFloat()),
            minValue = 0f,
            maxValue = target.toFloat(),
            targetValue = target.toFloat(),
            tapIntent = tapIntent
        )
    }
}

/**
 * 6. Stress Index Complication Provider
 */
class StressComplicationProvider : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return ComplicationDataFactory.build(
            context = this,
            type = type,
            shortText = "33",
            title = getString(R.string.stress_title),
            longText = "Stress: 33 · ${getString(R.string.stress_balanced)}",
            contentDesc = getString(R.string.complication_stress_desc, 33),
            iconRes = R.drawable.ic_comp_stress,
            currentValue = 33f,
            minValue = 0f,
            maxValue = 100f
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val vitals = RingConnComplicationProvider.getLatestVitals(this)
        val stress = vitals.stressScore
        val textValue = if (stress > 0) "$stress" else "--"
        val zone = when {
            stress == 0 -> "--"
            stress < 30 -> getString(R.string.stress_relaxed)
            stress < 60 -> getString(R.string.stress_balanced)
            stress < 80 -> getString(R.string.stress_moderate)
            else -> getString(R.string.stress_high)
        }
        val tapIntent = PendingIntent.getActivity(
            this, 206, Intent(this, StressDetailActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return ComplicationDataFactory.build(
            context = this,
            type = request.complicationType,
            shortText = textValue,
            title = getString(R.string.stress_title),
            longText = if (stress > 0) "Stress: $stress · $zone" else getString(R.string.stress_title),
            contentDesc = getString(R.string.complication_stress_desc, stress),
            iconRes = R.drawable.ic_comp_stress,
            currentValue = if (stress > 0) stress.toFloat().coerceIn(0f, 100f) else 0f,
            minValue = 0f,
            maxValue = 100f,
            tapIntent = tapIntent
        )
    }
}

/**
 * 7. Skin Temperature Complication Provider
 */
class TemperatureComplicationProvider : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return ComplicationDataFactory.build(
            context = this,
            type = type,
            shortText = "+0.2°",
            title = getString(R.string.skin_temp),
            longText = "+0.2 °C · ${getString(R.string.temp_in_range)}",
            contentDesc = getString(R.string.complication_temp_desc, 0.2f),
            iconRes = R.drawable.ic_comp_temp,
            currentValue = 0.2f,
            minValue = -2.0f,
            maxValue = 2.0f
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val vitals = RingConnComplicationProvider.getLatestVitals(this)
        val offset = vitals.skinTempOffset
        val hasData = vitals.skinTempBaseline > 0f || vitals.skinTempOffset != 0f
        val textValue = if (hasData) String.format("%+.1f°", offset) else "--°"
        val status = when {
            !hasData -> getString(R.string.no_data)
            offset > 0.5f -> getString(R.string.temp_warm)
            offset < -0.5f -> getString(R.string.temp_cool)
            else -> getString(R.string.temp_in_range)
        }
        val tapIntent = PendingIntent.getActivity(
            this, 207, Intent(this, TemperatureDetailActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return ComplicationDataFactory.build(
            context = this,
            type = request.complicationType,
            shortText = textValue,
            title = getString(R.string.skin_temp),
            longText = if (hasData) String.format("%+.1f °C · %s", offset, status) else getString(R.string.skin_temp),
            contentDesc = getString(R.string.complication_temp_desc, offset),
            iconRes = R.drawable.ic_comp_temp,
            currentValue = offset.coerceIn(-2.0f, 2.0f),
            minValue = -2.0f,
            maxValue = 2.0f,
            tapIntent = tapIntent
        )
    }
}

/**
 * 8. Readiness Complication Provider (0..100)
 */
class ReadinessComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val title = try {
            getString(R.string.readiness_title)
        } catch (e: Exception) {
            "Readiness"
        }
        return ComplicationDataFactory.build(
            context = this,
            type = type,
            shortText = "85",
            title = title,
            longText = "$title: 85",
            contentDesc = "$title: 85",
            iconRes = R.drawable.ic_vitals_hr,
            currentValue = 85f,
            minValue = 0f,
            maxValue = 100f
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val vitals = RingConnComplicationProvider.getLatestVitals(this)
        val readiness = RingConnHistoryStore.computeReadinessScore(vitals)
        val textValue = if (readiness > 0) "$readiness" else "--"
        val title = try {
            getString(R.string.readiness_title)
        } catch (e: Exception) {
            "Readiness"
        }
        val tapIntent = PendingIntent.getActivity(
            this, 208, Intent(this, VitalsDetailActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return ComplicationDataFactory.build(
            context = this,
            type = request.complicationType,
            shortText = textValue,
            title = title,
            longText = if (readiness > 0) "$title: $readiness" else title,
            contentDesc = "$title: $textValue",
            iconRes = R.drawable.ic_vitals_hr,
            currentValue = if (readiness > 0) readiness.toFloat().coerceIn(0f, 100f) else 0f,
            minValue = 0f,
            maxValue = 100f,
            tapIntent = tapIntent
        )
    }
}
