package io.github.hardbas.wearsync.bridge.service

import io.github.hardbas.wearsync.bridge.R
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.github.hardbas.wearsync.bridge.data.RingConnVitals
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.reflect.KClass
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private data class RawStageInterval(
    val start: Instant,
    val end: Instant,
    val stageType: Int,
    val lastModified: Instant = Instant.EPOCH
)

object RingConnTelemetryReader {
    private const val TAG = "RingConnReader"
    const val PREFS_NAME = "HomeWidgetPreferences"
    const val RINGCONN_PKG = "com.gdjztech.ringconn"

    // Preference keys
    const val KEY_LASTLY_HR = "keyLastlyHr"
    const val KEY_AVG_HR = "keyAvgHr"
    const val KEY_LASTLY_SPO = "keyLastlySpo"
    const val KEY_AVG_SPO = "keyLAvgSpo"
    const val KEY_LASTLY_HRV = "keyLastlyHrv"
    const val KEY_AVG_HRV = "keyAvgHrv"
    const val KEY_SLEEP_SCORE = "sleepScore"
    const val KEY_ACTIVITY_SCORE = "activityScore"
    const val KEY_ACTIVE_MINUTES = "activeMinutes"
    const val KEY_STRESS_SCORE = "stressScore"
    const val KEY_LIVE_STEPS = "liveSteps"
    const val KEY_TARGET_STEPS = "targetSteps"
    const val KEY_LIVE_CALORIE = "liveCalorie"
    const val KEY_TARGET_CALORIE = "targetCalorie"
    const val KEY_ELECTRIC = "electric"
    const val KEY_IS_CHARGE = "isCharge"
    const val KEY_IS_CONNECTED = "isConnected"
    const val KEY_REFRESH_TIMESTAMP = "keyRefreshTimestamp"
    const val KEY_LAST_SYNC_SOURCE = "keyLastSyncSource"
    const val KEY_LAST_SYNC_TIME_STR = "keyLastSyncTimeStr"
    const val KEY_MIN_HR = "keyMinHr"
    const val KEY_MAX_HR = "keyMaxHr"
    const val KEY_ZONE1_MINS = "keyZone1Mins"
    const val KEY_ZONE2_MINS = "keyZone2Mins"
    const val KEY_ZONE3_MINS = "keyZone3Mins"
    const val KEY_ZONE4_MINS = "keyZone4Mins"
    const val KEY_ZONE5_MINS = "keyZone5Mins"
    const val KEY_SLEEP_DEEP_MINS = "keySleepDeepMins"
    const val KEY_SLEEP_REM_MINS = "keySleepRemMins"
    const val KEY_SLEEP_LIGHT_MINS = "keySleepLightMins"
    const val KEY_SLEEP_AWAKE_MINS = "keySleepAwakeMins"
    const val KEY_SKIN_TEMP_OFFSET = "keySkinTempOffset"
    const val KEY_SKIN_TEMP_BASELINE = "keySkinTempBaseline"
    const val KEY_SLEEP_INSIGHT = "sleep_insight"
    const val KEY_STRESS_INSIGHT = "stress_insight"
    const val KEY_ACTIVITY_INSIGHT = "activity_insight"
    const val KEY_VITALS_INSIGHT = "vitals_insight"
    const val KEY_LAST_MORNING_PEEK_EPOCH_DAY = "last_morning_peek_epoch_day"
    const val KEY_ODI_DROPS_PER_HOUR = "odiDropsPerHour"
    const val KEY_MIN_SLEEPING_SPO2 = "minSleepingSpo2"
    const val KEY_SLEEPING_HR_DIP_PCT = "sleepingHrDipPct"
    const val KEY_NAP_MINUTES = "napMinutes"
    const val KEY_BEDTIME_WINDOW = "bedtimeWindow"
    const val KEY_STAND_HOURS = "standHours"
    const val KEY_HR_PHASE_DEEP = "hrPhaseDeep"
    const val KEY_HR_PHASE_REM = "hrPhaseRem"
    const val KEY_HR_PHASE_LIGHT = "hrPhaseLight"
    const val KEY_SLEEP_EFFICIENCY_PCT = "sleepEfficiencyPct"
    const val KEY_STRESS_DAWN = "stressDawn"
    const val KEY_STRESS_MORNING = "stressMorning"
    const val KEY_STRESS_AFTERNOON = "stressAfternoon"
    const val KEY_STRESS_NIGHT = "stressNight"
    const val KEY_BASAL_CALORIES = "basalCalories"
    const val KEY_DISTANCE_KM = "distanceKm"
    const val KEY_RESPIRATORY_RATE = "respiratoryRate"
    const val KEY_CACHE_EPOCH_DAY = "keyCacheEpochDay"
    const val KEY_PREV_DAY_STEPS = "keyPrevDaySteps"
    const val KEY_PREV_DAY_CALORIE = "keyPrevDayCalorie"

    private const val KEY_VITALS_SCHEMA = "keyVitalsSchema"
    // Bumped when a persisted value stops being trustworthy. Schema 1 wrote arithmetic
    // results — nightly anchor offsets, circadian stress multipliers, a sleep score plus
    // five, minute-of-day calories — into the same keys as measurements, so a stored
    // value could not be told apart from a reading. Those values are dropped once instead
    // of being restored as data.
    private const val VITALS_SCHEMA_VERSION = 2

    const val PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    val CORE_HEALTH_PERMISSIONS = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        // The ring publishes a respiratory rate, so the field no longer needs arithmetic.
        // Distance and basal rate were withdrawn: the ring writes no distance record at all,
        // and its basal record carries a half-hour amount in a per-day field, so no record
        // of either type could ever be published as a measurement on this watch.
        HealthPermission.getReadPermission(RespiratoryRateRecord::class)
    )

    /**
     * The skin-temperature grant, kept out of [CORE_HEALTH_PERMISSIONS] on purpose.
     *
     * It is a real consumer now: [readSkinTemperature] feeds `skinTempBaseline` and
     * `skinTempOffset`. It still must not mark the app as unconfigured, because a night without
     * this grant is served by the widget scrape fallback instead of by nothing at all.
     *
     * Body temperature is deliberately absent: nothing in either module reads
     * `BodyTemperatureRecord` (the ring publishes zero rows there), so asking for it only put an
     * unused permission on the consent screen.
     */
    val SKIN_TEMPERATURE_PERMISSIONS = setOf(
        HealthPermission.getReadPermission(SkinTemperatureRecord::class)
    )

    val REQUESTED_HEALTH_PERMISSIONS: Set<String> =
        CORE_HEALTH_PERMISSIONS + SKIN_TEMPERATURE_PERMISSIONS + PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    fun isHealthConnectAvailable(context: Context): Boolean {
        return try {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        } catch (e: Exception) {
            false
        }
    }

    suspend fun hasRequiredPermissions(context: Context): Boolean {
        return try {
            if (!isHealthConnectAvailable(context)) return false
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            granted.containsAll(CORE_HEALTH_PERMISSIONS)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Health Connect permissions", e)
            false
        }
    }

    suspend fun hasBackgroundReadPermission(context: Context): Boolean {
        return try {
            if (!isHealthConnectAvailable(context)) return false
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            granted.contains(PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Health Connect background read permission", e)
            false
        }
    }

    /** How far back the nightly skin-temperature read looks. */
    private const val SKIN_TEMPERATURE_LOOKBACK_DAYS = 14L

    /**
     * The published nightly skin temperature, read from Health Connect.
     *
     * The ring writes two or three `SkinTemperatureRecord` rows per night, each with its own
     * baseline and a single delta, so this function does not pick a row itself: it maps the
     * records into [SkinTemperatureSample] and lets [SkinTemperatureRecords] decide. That policy
     * is pinned by unit tests instead of by one phone observed once.
     *
     * Everything is read-only and defensive. A missing grant, an unsupported record type or a
     * provider error is logged and answered with null, so the caller falls back to the widget
     * scrape and the sync path never sees an exception.
     *
     * Exactly one line is logged per call: the chosen night with its baseline and delta, or the
     * reason no pair was published. There is no per-record dump — six rows and twenty deltas per
     * sync told nobody anything the chosen night does not.
     *
     * @return the sanitised pair, or null when no night has a plausible published baseline.
     */
    suspend fun readSkinTemperature(context: Context): NightlySkinTemperature? {
        return try {
            if (!isHealthConnectAvailable(context)) {
                Log.i(TAG, "Skin temperature: Health Connect unavailable, keeping the widget fallback")
                return null
            }
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            if (!granted.contains(HealthPermission.getReadPermission(SkinTemperatureRecord::class))) {
                Log.i(TAG, "Skin temperature: READ_SKIN_TEMPERATURE not granted, keeping the widget fallback")
                return null
            }

            val end = Instant.now()
            val start = end.minus(Duration.ofDays(SKIN_TEMPERATURE_LOOKBACK_DAYS))
            val records = client.readAllPages(
                recordType = SkinTemperatureRecord::class,
                timeRange = TimeRangeFilter.between(start, end)
            )
            val samples = records.mapNotNull { record ->
                // One unreadable record must not hide the others.
                try {
                    toSkinTemperatureSample(record)
                } catch (e: Exception) {
                    Log.w(TAG, "Skin temperature: record unreadable (${e.javaClass.simpleName}: ${e.message})")
                    null
                }
            }
            val nightly = SkinTemperatureRecords.latestNightly(samples, ZoneId.systemDefault())
            if (nightly == null) {
                Log.i(TAG, "Skin temperature: no published pair in ${SKIN_TEMPERATURE_LOOKBACK_DAYS}d (records=${records.size}), keeping the widget fallback")
                return null
            }

            val baseline = SkinTemperatureGuard.sanitizeBaseline(nightly.baselineCelsius)
            val deltaText = when {
                !nightly.deltaPresent -> "absent"
                !SkinTemperatureGuard.isPlausibleOffset(nightly.offsetCelsius) ->
                    "rejected(${celsiusText(nightly.offsetCelsius)})"
                else -> celsiusText(nightly.offsetCelsius)
            }
            if (baseline <= 0f) {
                Log.i(TAG, "Skin temperature: night=${nightly.nightEndingOn} baseline rejected(${celsiusText(nightly.baselineCelsius)}), keeping the widget fallback")
                return null
            }
            Log.i(
                TAG,
                "Skin temperature: night=${nightly.nightEndingOn} baseline=${celsiusText(baseline)} delta=$deltaText"
            )
            return nightly.copy(
                baselineCelsius = baseline,
                offsetCelsius = SkinTemperatureGuard.sanitizeOffset(nightly.offsetCelsius)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Skin temperature: read failed (${e.javaClass.simpleName}: ${e.message}), keeping the widget fallback")
            null
        }
    }

    /**
     * Maps one Health Connect record into the plain value the selection policy works on.
     *
     * Field names follow `androidx.health.connect:connect-client:1.1.0-alpha11`: the record holds
     * `baseline: Temperature?` (read via `inCelsius`, there is no `baseline.temperature`) and
     * `deltas: List<Delta>` with `Delta.time: Instant` and `Delta.delta.inCelsius`.
     */
    private fun toSkinTemperatureSample(record: SkinTemperatureRecord): SkinTemperatureSample =
        SkinTemperatureSample(
            start = record.startTime,
            end = record.endTime,
            lastModified = record.metadata.lastModifiedTime,
            baselineCelsius = record.baseline?.inCelsius?.toFloat(),
            deltas = record.deltas.map { delta ->
                SkinTemperatureDelta(time = delta.time, deltaCelsius = delta.delta.inCelsius.toFloat())
            }
        )

    /** Locale-pinned so a logcat line never carries a comma decimal separator. */
    private fun celsiusText(value: Float): String = String.format(Locale.US, "%.3f", value)

    suspend fun readFromHealthConnect(context: Context): RingConnVitals? = coroutineScope {
        if (!isHealthConnectAvailable(context)) {
            Log.w(TAG, "Health Connect SDK not available")
            return@coroutineScope null
        }

        val client = try {
            HealthConnectClient.getOrCreate(context)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to get HealthConnectClient", e)
            return@coroutineScope null
        }

        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading granted permissions", e)
            emptySet()
        }

        if (granted.isEmpty()) {
            Log.w(TAG, "No Health Connect permissions granted")
            return@coroutineScope null
        }

        val now = Instant.now()
        // Two days, not six hours: a night that began before midnight has to be
        // inside the window, or every nocturnal metric loses its head.
        val queryStart = now.minus(Duration.ofHours(48))
        val todayStart = ZonedDateTime.now(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()

        // Existing saved values for fallback continuity
        val existing = parseFromPreferences(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

        // Parallelize Health Connect readRecords queries
        val hrDeferred = async {
            if (granted.contains(HealthPermission.getReadPermission(HeartRateRecord::class))) {
                try {
                    client.readAllPages(
                        recordType = HeartRateRecord::class,
                        timeRange = TimeRangeFilter.between(queryStart, now)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed querying HeartRateRecord: ${e.message}")
                    null
                }
            } else null
        }

        val rhrDeferred = async {
            if (granted.contains(HealthPermission.getReadPermission(RestingHeartRateRecord::class))) {
                try {
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = RestingHeartRateRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(queryStart, now)
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed querying RestingHeartRateRecord: ${e.message}")
                    null
                }
            } else null
        }

        val spo2Deferred = async {
            if (granted.contains(HealthPermission.getReadPermission(OxygenSaturationRecord::class))) {
                try {
                    client.readAllPages(
                        recordType = OxygenSaturationRecord::class,
                        timeRange = TimeRangeFilter.between(queryStart, now)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed querying OxygenSaturationRecord: ${e.message}")
                    null
                }
            } else null
        }

        val hrvDeferred = async {
            if (granted.contains(HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class))) {
                try {
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = HeartRateVariabilityRmssdRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(queryStart, now)
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed querying HRV: ${e.message}")
                    null
                }
            } else null
        }

        val stepsDeferred = async {
            if (granted.contains(HealthPermission.getReadPermission(StepsRecord::class))) {
                try {
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = StepsRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(todayStart, now)
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed querying StepsRecord: ${e.message}")
                    null
                }
            } else null
        }

        val calDeferred = async {
            if (granted.contains(HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class))) {
                try {
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = TotalCaloriesBurnedRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(todayStart, now)
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed querying Calories: ${e.message}")
                    null
                }
            } else null
        }

        val sleepDeferred = async {
            if (granted.contains(HealthPermission.getReadPermission(SleepSessionRecord::class))) {
                try {
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = SleepSessionRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(queryStart, now)
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed querying SleepSessionRecord: ${e.message}")
                    null
                }
            } else null
        }

        val respiratoryDeferred = async {
            if (granted.contains(HealthPermission.getReadPermission(RespiratoryRateRecord::class))) {
                try {
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = RespiratoryRateRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(queryStart, now)
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed querying RespiratoryRateRecord: ${e.message}")
                    null
                }
            } else null
        }

        val hrResponse = hrDeferred.await()
        val rhrResponse = rhrDeferred.await()
        val spo2Response = spo2Deferred.await()
        val hrvResponse = hrvDeferred.await()
        val stepsResponse = stepsDeferred.await()
        val calResponse = calDeferred.await()
        val sleepResponse = sleepDeferred.await()
        val respiratoryResponse = respiratoryDeferred.await()

        var hasRingConnOrigin = false
        var lastBpm = 0
        var avgBpm = 0
        var restingBpm = 0
        var minBpm = 0
        var maxBpm = 0
        var z1 = 0
        var z2 = 0
        var z3 = 0
        var z4 = 0
        var z5 = 0

        var latestHrTimestamp = 0L
        var latestSpo2Timestamp = 0L
        var latestHrvTimestamp = 0L

        // 1. Heart Rate
        if (!hrResponse.isNullOrEmpty()) {
            // Strictly require RingConn smart ring origin so Watch PPG (Samsung Health)
            // on a tattooed wrist never pollutes or overrides RingConn heart rate.
            val ringConnHr = hrResponse.filter {
                it.metadata.dataOrigin.packageName == RINGCONN_PKG
            }
            val effectiveHrRecords = ringConnHr
            if (ringConnHr.isNotEmpty()) {
                hasRingConnOrigin = true
            }

            val allSamples = effectiveHrRecords.flatMap { record ->
                record.samples.map { sample -> sample to record.metadata.dataOrigin.packageName }
            }

            if (allSamples.isNotEmpty()) {
                val latestSample = allSamples.maxByOrNull { it.first.time }?.first
                if (latestSample != null) {
                    lastBpm = latestSample.beatsPerMinute.toInt()
                    latestHrTimestamp = latestSample.time.epochSecond
                }

                val todaySamples = allSamples.filter { it.first.time >= todayStart }
                if (todaySamples.isNotEmpty()) {
                    avgBpm = todaySamples.map { it.first.beatsPerMinute.toInt() }.average().roundToInt()
                    minBpm = todaySamples.minOf { it.first.beatsPerMinute.toInt() }
                    maxBpm = todaySamples.maxOf { it.first.beatsPerMinute.toInt() }
                    // A count of samples is not a duration; the spacing belongs to
                    // the source. Measure it, so a denser or sparser ring cannot
                    // inflate or collapse the zone minutes.
                    val sampleWindowMins = HealthSampleCadence.windowMinutes(todaySamples.map { it.first.time })
                    z1 = todaySamples.count { it.first.beatsPerMinute < 100 } * sampleWindowMins
                    z2 = todaySamples.count { it.first.beatsPerMinute in 100..120 } * sampleWindowMins
                    z3 = todaySamples.count { it.first.beatsPerMinute in 121..140 } * sampleWindowMins
                    z4 = todaySamples.count { it.first.beatsPerMinute in 141..160 } * sampleWindowMins
                    z5 = todaySamples.count { it.first.beatsPerMinute > 160 } * sampleWindowMins
                } else {
                    avgBpm = lastBpm
                    minBpm = 0
                    maxBpm = 0
                }
            }
        }

        // Resting Heart Rate (Prioritize RingConn origin)
        if (rhrResponse != null) {
            val ringConnRhr = rhrResponse.records.filter {
                it.metadata.dataOrigin.packageName == RINGCONN_PKG
            }
            val effectiveRhr = ringConnRhr.ifEmpty { rhrResponse.records }
            val latestRhr = effectiveRhr.maxByOrNull { it.time }
            if (latestRhr != null) {
                restingBpm = latestRhr.beatsPerMinute.toInt()
                if (avgBpm == 0) avgBpm = restingBpm
            }
        }

        // 2. Oxygen Saturation (SpO2 - Prioritize RingConn origin)
        var lastSpo2 = 0
        var avgSpo2 = 0
        if (!spo2Response.isNullOrEmpty()) {
            val ringConnSpo2 = spo2Response.filter {
                it.metadata.dataOrigin.packageName == RINGCONN_PKG
            }
            val effectiveSpo2 = ringConnSpo2.ifEmpty { spo2Response }
            val latestSpo2Record = effectiveSpo2.maxByOrNull { it.time }
            if (latestSpo2Record != null) {
                val rawVal = latestSpo2Record.percentage.value
                lastSpo2 = if (rawVal <= 1.0) (rawVal * 100.0).roundToInt() else rawVal.roundToInt()
                latestSpo2Timestamp = latestSpo2Record.time.epochSecond
            }

            val todaySpo2 = effectiveSpo2.filter { it.time >= todayStart }
            if (todaySpo2.isNotEmpty()) {
                avgSpo2 = todaySpo2.map {
                    val v = it.percentage.value
                    if (v <= 1.0) v * 100.0 else v
                }.average().roundToInt()
            } else {
                avgSpo2 = lastSpo2
            }
        }

        // 3. Heart Rate Variability (HRV RMSSD - Prioritize RingConn origin)
        var lastHrv = 0
        var avgHrv = 0
        if (hrvResponse != null) {
            val ringConnHrv = hrvResponse.records.filter {
                it.metadata.dataOrigin.packageName == RINGCONN_PKG
            }
            val effectiveHrv = ringConnHrv.ifEmpty { hrvResponse.records }
            val latestHrvRecord = effectiveHrv.maxByOrNull { it.time }
            if (latestHrvRecord != null) {
                lastHrv = latestHrvRecord.heartRateVariabilityMillis.roundToInt()
                latestHrvTimestamp = latestHrvRecord.time.epochSecond
            }

            val todayHrv = effectiveHrv.filter { it.time >= todayStart }
            if (todayHrv.isNotEmpty()) {
                avgHrv = todayHrv.map { it.heartRateVariabilityMillis }.average().roundToInt()
            } else {
                avgHrv = lastHrv
            }
        }

        // 4. Steps Today (Prioritize RingConn data origin)
        var todaySteps = 0
        var activeMinutes = 0
        if (stepsResponse != null) {
            val ringConnSteps = stepsResponse.records.filter {
                it.metadata.dataOrigin.packageName == RINGCONN_PKG
            }
            val effectiveSteps = ringConnSteps.ifEmpty { stepsResponse.records }
            if (ringConnSteps.isNotEmpty()) {
                hasRingConnOrigin = true
            }
            if (effectiveSteps.isNotEmpty()) {
                todaySteps = effectiveSteps.sumOf { it.count }.toInt()
                var computedActiveMins = 0L
                for (rec in effectiveSteps) {
                    val durMins = Duration.between(rec.startTime, rec.endTime).toMinutes()
                    if (durMins >= 1L && (rec.count.toDouble() / durMins) in 20.0..220.0) {
                        computedActiveMins += durMins
                    } else if (rec.count > 0) {
                        computedActiveMins += (rec.count / 100L).coerceAtLeast(1L)
                    }
                }
                val stepCadenceMinutes = (todaySteps / 100).coerceAtLeast(if (todaySteps > 0) 1 else 0)
                activeMinutes = maxOf(computedActiveMins.toInt(), stepCadenceMinutes)
            }
        }

        // 5. Total Calories Burned Today
        var todayCalories = 0
        if (calResponse != null) {
            val ringConnCal = calResponse.records.filter {
                it.metadata.dataOrigin.packageName == RINGCONN_PKG
            }
            todayCalories = if (ringConnCal.isNotEmpty()) {
                hasRingConnOrigin = true
                ringConnCal.sumOf { it.energy.inKilocalories }.roundToInt()
            } else {
                calResponse.records.sumOf { it.energy.inKilocalories }.roundToInt()
            }
        }

        // 5b. Respiratory Rate (ring origin only)
        // This used to be arithmetic — a pulse/HRV formula — while the ring publishes the
        // record itself. Ring origin only, with no fallback: an absent record stays unknown.
        // Distance and basal calories are not read at all: the ring writes no distance, and
        // its basal record carries a half-hour amount in a per-day field, so both stay 0.
        var measuredRespiratoryRate = 0f
        if (respiratoryResponse != null) {
            val ringConnResp = MeasuredVitalsPolicy.ringSamples(respiratoryResponse.records) {
                it.metadata.dataOrigin.packageName == RINGCONN_PKG
            }
            if (ringConnResp.isNotEmpty()) {
                hasRingConnOrigin = true
            }
            ringConnResp.maxByOrNull { it.time }?.let {
                measuredRespiratoryRate = it.rate.toFloat()
            }
        }

        // 6. Sleep Session & Stages (RingConn writes multiple SleepSessionRecord segments per night)
        var sleepScore = 0
        var deepMins = 0L
        var remMins = 0L
        var lightMins = 0L
        var awakeMins = 0L
        var nocturnalSessions: List<SleepSessionRecord> = emptyList()
        var nocturnalLatestEnd: Instant? = null
        if (sleepResponse != null) {
            val ringConnSleeps = sleepResponse.records.filter {
                it.metadata.dataOrigin.packageName == RINGCONN_PKG
            }.ifEmpty { sleepResponse.records }

                // Only consider sleep sessions concluding today (endTime >= todayStart)
                val todaySleeps = ringConnSleeps.filter { it.endTime >= todayStart }
                val latestEnd = todaySleeps.maxOfOrNull { it.endTime }
                if (latestEnd != null) {
                    val nightWindowStart = latestEnd.minus(Duration.ofHours(14))
                    val nightSessions = ringConnSleeps.filter { it.endTime >= nightWindowStart && it.endTime <= latestEnd }
                    nocturnalSessions = nightSessions
                    nocturnalLatestEnd = latestEnd
                    if (nightSessions.any { it.metadata.dataOrigin.packageName == RINGCONN_PKG }) {
                        hasRingConnOrigin = true
                    }

                    Log.i(TAG, "Health Connect: found ${nightSessions.size} nightSessions (todaySleeps=${todaySleeps.size})")
                    for ((idx, s) in nightSessions.withIndex()) {
                        val dur = Duration.between(s.startTime, s.endTime).toMinutes()
                        Log.i(TAG, "Session #$idx id=${s.metadata.id} mod=${s.metadata.lastModifiedTime} cid=${s.metadata.clientRecordId}: ${s.startTime} -> ${s.endTime} ($dur mins), stages=${s.stages.size}")
                        for (st in s.stages) {
                            val stDur = Duration.between(st.startTime, st.endTime).toMinutes()
                            Log.i(TAG, "   Stage type=${st.stage}: ${st.startTime} -> ${st.endTime} ($stDur mins)")
                        }
                    }

                    var totalDurationMins = 0L
                    deepMins = 0L
                    remMins = 0L
                    lightMins = 0L
                    awakeMins = 0L

                    val discreteStages = mutableListOf<RawStageInterval>()
                    var umbrellaMins = 0L
                    var umbrellaStart: Instant? = null
                    var umbrellaEnd: Instant? = null

                    for (session in nightSessions) {
                        val sessionDur = ((Duration.between(session.startTime, session.endTime).seconds + 30L) / 60L).coerceAtLeast(0L)
                        if (session.stages.isEmpty()) {
                            if (sessionDur > umbrellaMins) {
                                umbrellaMins = sessionDur
                                umbrellaStart = session.startTime
                                umbrellaEnd = session.endTime
                            }
                        } else {
                            for (st in session.stages) {
                                when (st.stage) {
                                    SleepSessionRecord.STAGE_TYPE_DEEP,
                                    SleepSessionRecord.STAGE_TYPE_REM,
                                    SleepSessionRecord.STAGE_TYPE_LIGHT,
                                    SleepSessionRecord.STAGE_TYPE_AWAKE,
                                    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> {
                                        discreteStages.add(RawStageInterval(st.startTime, st.endTime, st.stage, session.metadata.lastModifiedTime))
                                    }
                                    SleepSessionRecord.STAGE_TYPE_SLEEPING -> {
                                        val dur = ((Duration.between(st.startTime, st.endTime).seconds + 30L) / 60L).coerceAtLeast(0L)
                                        if (dur > umbrellaMins) {
                                            umbrellaMins = dur
                                            umbrellaStart = st.startTime
                                            umbrellaEnd = st.endTime
                                        }
                                    }
                                    else -> {
                                        // Ignore unknown stage containers
                                    }
                                }
                            }
                        }
                    }

                    if (discreteStages.isNotEmpty()) {
                        // Exclude pre-bed awakening records that concluded before bed session onset
                        val validStages = if (umbrellaStart != null) {
                            discreteStages.filter { it.end > umbrellaStart }
                        } else {
                            discreteStages
                        }.ifEmpty { discreteStages }

                        val minStageStart = validStages.minOf { it.start }
                        val maxStageEnd = validStages.maxOf { it.end }
                        val tStart = if (umbrellaStart != null && umbrellaStart < minStageStart) umbrellaStart else minStageStart
                        val tEnd = if (umbrellaEnd != null && umbrellaEnd > maxStageEnd) umbrellaEnd else maxStageEnd
                        val totalSeconds = Duration.between(tStart, tEnd).seconds.coerceIn(30L, 86400L)
                        val epochCount = ((totalSeconds + 15L) / 30L).toInt().coerceAtLeast(1)
                        val epochTimeline = SleepStageMerger.merge(
                            // Specificity decides which segment owns an epoch; arrival order
                            // cannot change the night.
                            intervals = validStages.map {
                                SleepStageMerger.Interval(
                                    startMs = it.start.toEpochMilli(),
                                    endMs = it.end.toEpochMilli(),
                                    stageType = it.stageType,
                                    lastModifiedMs = it.lastModified.toEpochMilli()
                                )
                            },
                            timelineStartMs = tStart.toEpochMilli(),
                            epochCount = epochCount
                        )

                        deepMins = SleepStageMerger.minutesOf(epochTimeline, SleepSessionRecord.STAGE_TYPE_DEEP)
                        remMins = SleepStageMerger.minutesOf(epochTimeline, SleepSessionRecord.STAGE_TYPE_REM)
                        lightMins = SleepStageMerger.minutesOf(epochTimeline, SleepSessionRecord.STAGE_TYPE_LIGHT)

                        val mergedAwakeMins =
                            SleepStageMerger.minutesOf(epochTimeline, SleepSessionRecord.STAGE_TYPE_AWAKE) +
                                SleepStageMerger.minutesOf(epochTimeline, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED)
                        val totalAsleep = deepMins + remMins + lightMins

                        awakeMins = if (mergedAwakeMins > 0L) {
                            mergedAwakeMins
                        } else if (umbrellaMins > totalAsleep) {
                            umbrellaMins - totalAsleep
                        } else {
                            0L
                        }
                    }

                    totalDurationMins = if (deepMins + remMins + lightMins > 0) {
                        deepMins + remMins + lightMins
                    } else {
                        umbrellaMins
                    }

                    Log.i(TAG, "Health Connect sleep metrics: totalDur=$totalDurationMins m, deep=$deepMins m, rem=$remMins m, light=$lightMins m, awake=$awakeMins m")

                    // A stage nobody recorded contributes nothing, and a night with no stage
                    // records has no computed score at all. The old free 18 and 17 points plus
                    // the 45 floor manufactured a believable score out of an absent night.
                    sleepScore = MeasuredVitalsPolicy.computedSleepScore(
                        deepMinutes = deepMins,
                        remMinutes = remMins,
                        lightMinutes = lightMins,
                        awakeMinutes = awakeMins
                    )
                }
            }

        // Check if we retrieved any real telemetry from Health Connect
        val hasRealTelemetry = lastBpm > 0 || todaySteps > 0 || lastSpo2 > 0 || lastHrv > 0 || sleepScore > 0 ||
            measuredRespiratoryRate > 0f
        if (!hasRealTelemetry) {
            Log.d(TAG, "Health Connect permissions granted but 0 records found in range")
            return@coroutineScope null
        }

        // Fill remaining missing metrics from continuity cache, but keep lastHeartRate = 0 when
        // Health Connect has no direct RingConn HR sample so Smart Merge never mistakes a cached
        // fallback for a fresh Health Connect HR reading.
        val finalHr = lastBpm
        val finalAvgHr = if (avgBpm > 0) avgBpm else (if (restingBpm > 0) restingBpm else finalHr)
        val finalSpo2 = if (lastSpo2 > 0) lastSpo2 else existing.lastSpo2
        val finalAvgSpo2 = if (avgSpo2 > 0) avgSpo2 else finalSpo2
        val finalHrv = if (lastHrv > 0) lastHrv else existing.lastHrv
        val finalAvgHrv = if (avgHrv > 0) avgHrv else finalHrv
        val finalSteps = if (todaySteps > 0) todaySteps else existing.liveSteps
        val stepBasedCalories = (finalSteps * 0.045).roundToInt()
        val finalCalories = maxOf(todayCalories, stepBasedCalories)

        // Dynamically compute Activity Score
        val actScore = if (finalSteps > 0 || finalCalories > 0) {
            ((finalSteps.toDouble() / 8000.0 * 55.0) + (finalCalories.toDouble() / 350.0 * 45.0)).roundToInt().coerceIn(0, 100)
        } else {
            existing.activityScore
        }

        // Preserve exact RingConn stress score if already present, otherwise estimate from HRV
        val stressScore = if (existing.stressScore > 0) {
            existing.stressScore
        } else if (finalHrv > 0) {
            (100 - (finalHrv * 1.05).roundToInt()).coerceIn(1, 99)
        } else {
            0
        }

        val ringBattery = existing.ringBattery

        val sourceLabel = if (hasRingConnOrigin) {
            "Health Connect (com.gdjztech.ringconn)"
        } else {
            context.getString(R.string.source_health_connect)
        }

        val finalMinHr = if (minBpm > 0) minBpm else existing.minHeartRate
        val finalMaxHr = if (maxBpm > 0) maxBpm else existing.maxHeartRate
        val hasNewZones = (z1 + z2 + z3 + z4 + z5) > 0
        val finalZ1 = if (hasNewZones) z1 else existing.zone1Mins
        val finalZ2 = if (hasNewZones) z2 else existing.zone2Mins
        val finalZ3 = if (hasNewZones) z3 else existing.zone3Mins
        val finalZ4 = if (hasNewZones) z4 else existing.zone4Mins
        val finalZ5 = if (hasNewZones) z5 else existing.zone5Mins

        // A valid sleep breakdown requires effective sleep (deep, rem, or light).
        // Never allow awake-only intervals to overwrite and zero out real nocturnal sleep stages.
        val hcAsleep = deepMins + remMins + lightMins
        val existingAsleep = existing.sleepDeepMins + existing.sleepRemMins + existing.sleepLightMins
        // Health Connect owns the night; the cache only covers a night it does not have. The
        // old rule kept whichever source declared MORE sleep, so an inflated cache outbid the
        // correct record forever — and because this read is the authoritative writer, the bad
        // value stayed persisted no matter how many times the user tapped sync.
        val night = SleepSourcePolicy.stages(
            hc = SleepSourcePolicy.StageMinutes(
                deep = deepMins.toInt(),
                rem = remMins.toInt(),
                light = lightMins.toInt(),
                awake = awakeMins.toInt()
            ),
            cached = SleepSourcePolicy.StageMinutes(
                deep = existing.sleepDeepMins,
                rem = existing.sleepRemMins,
                light = existing.sleepLightMins,
                awake = existing.sleepAwakeMins
            )
        )
        val finalDeep = night.deep
        val finalRem = night.rem
        val finalLight = night.light
        val finalAwake = night.awake
        val finalSleep = if (existingAsleep > hcAsleep && existingAsleep > 0 && existing.sleepScore > 0) existing.sleepScore else (if (sleepScore > 0) sleepScore else existing.sleepScore)

        // --- Physiological Biomarkers Calculation (AHISpo2Data, CalSleepHeartRateData, SleepNapModel, BedScheduleModel, StandSyncModel) ---
        // 1. Sleep Sessions (SleepNapModel & BedScheduleModel)
        val allSleepRecords = if (sleepResponse != null) {
            val ringSleep = sleepResponse.records.filter { it.metadata.dataOrigin.packageName == RINGCONN_PKG }
            ringSleep.ifEmpty { sleepResponse.records }
        } else emptyList()

        val longSleepSessions = allSleepRecords.filter {
            Duration.between(it.startTime, it.endTime).toMinutes() >= 180L
        }
        val primarySleepSession: SleepSessionRecord? = if (longSleepSessions.isNotEmpty()) {
            longSleepSessions.filter { it.endTime >= todayStart }.maxByOrNull { it.endTime }
        } else {
            allSleepRecords.filter { it.endTime >= todayStart }.maxByOrNull { Duration.between(it.startTime, it.endTime).toMinutes() }
        }
        val habitualSleepSession: SleepSessionRecord? = primarySleepSession
            ?: longSleepSessions.maxByOrNull { it.endTime }
            ?: allSleepRecords.maxByOrNull { Duration.between(it.startTime, it.endTime).toMinutes() }

        val nocturnalStart: Instant? = nocturnalSessions.minOfOrNull { it.startTime } ?: primarySleepSession?.startTime
        val nocturnalEnd: Instant? = nocturnalSessions.maxOfOrNull { it.endTime } ?: primarySleepSession?.endTime

        val napSessions = allSleepRecords.filter { session ->
            session != primarySleepSession &&
            !nocturnalSessions.contains(session) &&
            session.startTime >= todayStart &&
            session.endTime >= todayStart &&
            (nocturnalLatestEnd == null || session.startTime >= nocturnalLatestEnd.plus(Duration.ofMinutes(90))) &&
            Duration.between(session.startTime, session.endTime).toMinutes() in 15..179
        }
        val computedNapMinutes = napSessions.sumOf {
            Duration.between(it.startTime, it.endTime).toMinutes().toInt()
        }
        val finalNapMinutes = if (computedNapMinutes > 0) computedNapMinutes else existing.napMinutes

        val localZone = ZoneId.systemDefault()
        var computedBedtimeWindow = ""
        val bedtimeOnsetInstant = nocturnalStart ?: habitualSleepSession?.startTime
        if (bedtimeOnsetInstant != null) {
            val sleepOnsetLocal = bedtimeOnsetInstant.atZone(localZone)
            val windowStart = sleepOnsetLocal.minusMinutes(15)
            val windowEnd = sleepOnsetLocal.plusMinutes(30)
            val bedFmt = DateTimeFormatter.ofPattern("HH:mm")
            computedBedtimeWindow = "${windowStart.format(bedFmt)} - ${windowEnd.format(bedFmt)}"
        }
        val finalBedtimeWindow = computedBedtimeWindow.ifBlank { existing.bedtimeWindow }

        // 2. Nocturnal SpO2 & Apnea ODI (AHISpo2Data) — only when sleep concluded today
        val effectiveSpo2Records = if (!spo2Response.isNullOrEmpty()) {
            val ringSpo2 = spo2Response.filter { it.metadata.dataOrigin.packageName == RINGCONN_PKG }
            ringSpo2.ifEmpty { spo2Response }
        } else emptyList()

        val hasTodaySleep = nocturnalStart != null || finalSleep > 0 || (finalDeep + finalRem + finalLight) > 0
        val nocturnalSpo2Records = if (nocturnalStart != null && nocturnalEnd != null) {
            effectiveSpo2Records.filter { rec ->
                rec.time >= nocturnalStart && rec.time <= nocturnalEnd
            }
        } else emptyList()

        val sleepDurationHours: Float = if (nocturnalStart != null && nocturnalEnd != null) {
            val mins = Duration.between(nocturnalStart, nocturnalEnd).toMinutes().toFloat()
            mins / 60.0f
        } else {
            7.0f
        }

        var computedMinSleepingSpo2 = 0
        var computedOdi = 0.0f
        if (nocturnalSpo2Records.isNotEmpty()) {
            val spo2Values = nocturnalSpo2Records.map { rec ->
                val v = rec.percentage.value
                if (v <= 1.0) v * 100.0 else v
            }
            computedMinSleepingSpo2 = spo2Values.minOrNull()?.roundToInt() ?: 0
            val meanSleepingSpo2 = spo2Values.average()
            val dropThreshold = meanSleepingSpo2 - 3.0
            val dropCount = spo2Values.count { it <= dropThreshold || it < 92.0 }
            val effectiveHours = maxOf(1.0f, sleepDurationHours)
            val rawOdi = dropCount.toFloat() / effectiveHours
            computedOdi = (rawOdi * 10f).roundToInt() / 10f
        }
        // No nocturnal samples means no nocturnal minimum. A daytime reading is
        // not a substitute: the card shows "--" instead of a plausible lie.
        val finalMinSleepingSpo2 = if (computedMinSleepingSpo2 > 0) computedMinSleepingSpo2 else existing.minSleepingSpo2
        val finalOdi = if (computedOdi > 0f) computedOdi else existing.odiDropsPerHour

        // 3. Nocturnal Heart Rate Dip (CalSleepHeartRateData) — only when sleep concluded today
        var computedSleepingHrDipPct = 0.0f
        val effectiveHrRecords = if (!hrResponse.isNullOrEmpty()) {
            val ringHr = hrResponse.filter { it.metadata.dataOrigin.packageName == RINGCONN_PKG }
            ringHr.ifEmpty { hrResponse }
        } else emptyList()

        val allHrSamples = effectiveHrRecords.flatMap { it.samples }
        if (nocturnalStart != null && nocturnalEnd != null && allHrSamples.isNotEmpty()) {
            val sleepHrSamples = allHrSamples.filter {
                it.time >= nocturnalStart && it.time <= nocturnalEnd
            }
            // Compare the evening before bed and the morning after it, not every
            // sample the two-day window can see: an unbounded "awake" mean would
            // be yesterday's whole day and flatten the dip.
            val dipWindowStart = nocturnalStart.minus(Duration.ofHours(3))
            val dipWindowEnd = nocturnalEnd.plus(Duration.ofHours(3))
            val awakeHrSamples = allHrSamples.filter {
                (it.time >= dipWindowStart && it.time < nocturnalStart) ||
                    (it.time > nocturnalEnd && it.time <= dipWindowEnd)
            }
            if (sleepHrSamples.isNotEmpty() && awakeHrSamples.isNotEmpty()) {
                val sleepMeanHr = sleepHrSamples.map { it.beatsPerMinute.toDouble() }.average()
                val awakeMeanHr = awakeHrSamples.map { it.beatsPerMinute.toDouble() }.average()
                if (awakeMeanHr > 0.0) {
                    val dip = (((awakeMeanHr - sleepMeanHr) / awakeMeanHr) * 100.0).toFloat().coerceIn(0f, 35f)
                    computedSleepingHrDipPct = (dip * 10f).roundToInt() / 10f
                }
            }
        }
        if (computedSleepingHrDipPct == 0.0f && hasTodaySleep) {
            val refAvg = if (finalAvgHr > 0) finalAvgHr else existing.avgHeartRate
            val refMin = if (finalMinHr > 0) finalMinHr else existing.minHeartRate
            if (refAvg > 0 && refMin > 0 && refAvg > refMin) {
                val dip = (((refAvg - refMin) * 0.65f / refAvg.toFloat()) * 100f).coerceIn(0f, 30f)
                computedSleepingHrDipPct = (dip * 10f).roundToInt() / 10f
            }
        }
        val finalSleepingHrDipPct = if (computedSleepingHrDipPct > 0f) computedSleepingHrDipPct else existing.sleepingHrDipPct

        // 4. Stand Hours (StandSyncModel)
        var computedStandHours = 0
        if (stepsResponse != null) {
            val ringSteps = stepsResponse.records.filter { it.metadata.dataOrigin.packageName == RINGCONN_PKG }
            val effectiveSteps = ringSteps.ifEmpty { stepsResponse.records }
            val distinctHours = effectiveSteps.filter { it.count >= 25L && it.startTime >= todayStart }
                .map { it.startTime.atZone(localZone).hour }
                .distinct()
            computedStandHours = distinctHours.size.coerceIn(0, 12)
        }
        val refSteps = if (finalSteps > 0) finalSteps else existing.liveSteps
        if (refSteps > 0 && computedStandHours == 0) {
            computedStandHours = (refSteps / 650).coerceIn(1, 12)
        }
        val finalStandHours = if (computedStandHours > 0) computedStandHours else existing.standHours

        // 5. Heart Rate per Sleep Stage & Bed Efficiency (SleepSyncModel)
        // RingConn writes one SleepSessionRecord per stage interval alongside a coarse SLEEPING
        // umbrella session, so collect stages across all nocturnalSessions rather than only
        // primarySleepSession (whose single stage is STAGE_TYPE_SLEEPING).
        var computedHrDeep = 0
        var computedHrRem = 0
        var computedHrLight = 0
        val allNocturnalStages = if (nocturnalSessions.isNotEmpty()) {
            nocturnalSessions.flatMap { it.stages }
        } else {
            primarySleepSession?.stages ?: emptyList()
        }
        if (allNocturnalStages.isNotEmpty() && allHrSamples.isNotEmpty()) {
            val deepStages = allNocturnalStages.filter { it.stage == SleepSessionRecord.STAGE_TYPE_DEEP }
            val remStages = allNocturnalStages.filter { it.stage == SleepSessionRecord.STAGE_TYPE_REM }
            val lightStages = allNocturnalStages.filter { it.stage == SleepSessionRecord.STAGE_TYPE_LIGHT }

            val deepHrList = allHrSamples.filter { s -> deepStages.any { s.time >= it.startTime && s.time <= it.endTime } }
            val remHrList = allHrSamples.filter { s -> remStages.any { s.time >= it.startTime && s.time <= it.endTime } }
            val lightHrList = allHrSamples.filter { s -> lightStages.any { s.time >= it.startTime && s.time <= it.endTime } }

            if (deepHrList.isNotEmpty()) computedHrDeep = deepHrList.map { it.beatsPerMinute }.average().roundToInt()
            if (remHrList.isNotEmpty()) computedHrRem = remHrList.map { it.beatsPerMinute }.average().roundToInt()
            if (lightHrList.isNotEmpty()) computedHrLight = lightHrList.map { it.beatsPerMinute }.average().roundToInt()
        }
        // A phase heart rate exists only when samples fell inside that stage. The nightly
        // anchor plus a fixed offset — and the anchor's own `avg - 8` and literal 56 — were
        // arithmetic dressed as measurements, so they are gone. The cached values are not
        // consulted either: a stored number cannot outrank what the stages measured.
        val finalHrPhaseDeep = MeasuredVitalsPolicy.hrPhase(computedHrDeep)
        val finalHrPhaseLight = MeasuredVitalsPolicy.hrPhase(computedHrLight)
        val finalHrPhaseRem = MeasuredVitalsPolicy.hrPhase(computedHrRem)

        val totalAsleepM = finalDeep + finalRem + finalLight
        // RingConn's time in bed runs from bed time to the final awakening, so the latency to
        // fall asleep belongs in the efficiency denominator even though the ring does not
        // count it as Awake. Anchoring it on the nocturnal span is what makes the number we
        // publish agree with the one the ring shows for the same night.
        val nocturnalSpanM = if (nocturnalStart != null && nocturnalEnd != null) {
            Duration.between(nocturnalStart, nocturnalEnd).toMinutes()
        } else 0L
        val totalInBedM = MeasuredVitalsPolicy.inBedMinutes(nocturnalSpanM, totalAsleepM, finalAwake)
        // Efficiency is asleep over in bed, from real stages. A sleep score is not an
        // efficiency and adding five points to it was arithmetic pretending to be one.
        val finalSleepEfficiencyPct = MeasuredVitalsPolicy.sleepEfficiencyPct(totalAsleepM, totalInBedM)

        // 6. 4-Block Circadian Stress (DailyModel: stressBeforeDawn, stressMorning, stressAfternoon, stressNight)
        val todayHrSamples = allHrSamples.filter { it.time >= todayStart }
        val refStress = if (stressScore > 0) stressScore else 0
        // The only measured reference for a block is the nightly minimum; without it there
        // is no anchor to subtract and "+18" alone would be a guess.
        val nightAnchorHr = finalMinHr

        fun measuredBlockStress(startHour: Int, endHour: Int, stressWeight: Double): Int {
            val blockSamples = todayHrSamples.filter {
                val h = it.time.atZone(localZone).hour
                h in startHour..endHour
            }
            if (blockSamples.isEmpty() || nightAnchorHr <= 0) return MeasuredVitalsPolicy.blockStress(0)

            val meanHr = blockSamples.map { it.beatsPerMinute.toDouble() }.average()
            val hrDerived = ((meanHr - nightAnchorHr) * 1.45 + 18.0).roundToInt().coerceIn(8, 92)
            val blended = if (refStress > 0) {
                ((hrDerived * 0.65) + (refStress * stressWeight * 0.35)).roundToInt()
            } else {
                hrDerived
            }
            return MeasuredVitalsPolicy.blockStress(blended.coerceIn(8, 95))
        }

        val finalStressDawn = measuredBlockStress(0, 5, 0.68)
        val finalStressMorning = measuredBlockStress(6, 11, 0.94)
        val finalStressAfternoon = measuredBlockStress(12, 17, 1.08)
        val finalStressNight = measuredBlockStress(18, 23, 0.86)

        // 7. Nocturnal Respiratory Rate (SleepSyncModel)
        // It comes from its own record; no substitution fills a missing one. The cache only
        // carries a value an earlier read measured, and never outranks a fresh record.
        val finalRespiratoryRate = if (measuredRespiratoryRate > 0f) measuredRespiratoryRate else existing.respiratoryRate

        val vitals = RingConnVitals(
            lastHeartRate = finalHr,
            avgHeartRate = finalAvgHr,
            lastSpo2 = finalSpo2,
            avgSpo2 = finalAvgSpo2,
            lastHrv = finalHrv,
            avgHrv = finalAvgHrv,
            sleepScore = finalSleep,
            activityScore = actScore,
            activeMinutes = if (activeMinutes > 0) activeMinutes else (if (finalSteps > 0) (finalSteps / 100).coerceAtLeast(1) else existing.activeMinutes),
            stressScore = stressScore,
            liveSteps = finalSteps,
            targetSteps = 10000,
            liveCalorie = finalCalories,
            targetCalorie = 500,
            ringBattery = ringBattery,
            isCharging = existing.isCharging,
            isConnected = true,
            refreshTimestamp = resolveBiometricRefreshTimestamp(latestHrTimestamp, latestSpo2Timestamp, latestHrvTimestamp),
            minHeartRate = finalMinHr,
            maxHeartRate = finalMaxHr,
            zone1Mins = finalZ1,
            zone2Mins = finalZ2,
            zone3Mins = finalZ3,
            zone4Mins = finalZ4,
            zone5Mins = finalZ5,
            sleepDeepMins = finalDeep,
            sleepRemMins = finalRem,
            sleepLightMins = finalLight,
            sleepAwakeMins = finalAwake,
            skinTempOffset = SkinTemperatureGuard.sanitizeOffset(existing.skinTempOffset),
            skinTempBaseline = SkinTemperatureGuard.sanitizeBaseline(existing.skinTempBaseline),
            sleepInsight = existing.sleepInsight,
            stressInsight = existing.stressInsight,
            activityInsight = existing.activityInsight,
            vitalsInsight = existing.vitalsInsight,
            odiDropsPerHour = finalOdi,
            minSleepingSpo2 = finalMinSleepingSpo2,
            sleepingHrDipPct = finalSleepingHrDipPct,
            napMinutes = finalNapMinutes,
            bedtimeWindow = finalBedtimeWindow,
            standHours = finalStandHours,
            hrPhaseDeep = finalHrPhaseDeep,
            hrPhaseRem = finalHrPhaseRem,
            hrPhaseLight = finalHrPhaseLight,
            sleepEfficiencyPct = finalSleepEfficiencyPct,
            stressDawn = finalStressDawn,
            stressMorning = finalStressMorning,
            stressAfternoon = finalStressAfternoon,
            stressNight = finalStressNight,
            // Withdrawn sources: the field stays on the wire and always stays 0, because
            // neither can carry a measurement on this watch.
            basalCalories = 0,
            distanceKm = 0.0f,
            respiratoryRate = finalRespiratoryRate
        )

        saveToPreferences(context, vitals, source = sourceLabel, stagesAuthoritative = true)
        Log.i(TAG, "[$sourceLabel] Fresh vitals loaded: HR=$finalHr, Min=$finalMinHr, Max=$finalMaxHr, SpO2=$finalSpo2, Steps=$finalSteps, Sleep=$finalSleep, HRV=$finalHrv, ODI=$finalOdi, MinSleepSpO2=$finalMinSleepingSpo2, HrDip=$finalSleepingHrDipPct%, Naps=${finalNapMinutes}m, BedWindow=$finalBedtimeWindow, Stand=${finalStandHours}h")
        return@coroutineScope vitals
    }

    fun ensureDailyRollover(
        prefs: SharedPreferences,
        todayEpochDay: Long = ZonedDateTime.now(ZoneId.systemDefault()).toLocalDate().toEpochDay()
    ): Boolean {
        val savedEpochDay = prefs.getLong(KEY_CACHE_EPOCH_DAY, -1L)
        if (savedEpochDay == todayEpochDay) {
            return false
        }
        val prevSteps = prefs.getInt(KEY_LIVE_STEPS, 0)
        val prevCal = prefs.getInt(KEY_LIVE_CALORIE, 0)
        Log.i(TAG, "Midnight Day Rollover detected (savedEpochDay=$savedEpochDay -> todayEpochDay=$todayEpochDay). Resetting yesterday's daily counters (prevSteps=$prevSteps, prevCal=$prevCal).")

        prefs.edit().apply {
            if (prevSteps > 0) putInt(KEY_PREV_DAY_STEPS, prevSteps)
            if (prevCal > 0) putInt(KEY_PREV_DAY_CALORIE, prevCal)
            putInt(KEY_LIVE_STEPS, 0)
            putInt(KEY_LIVE_CALORIE, 0)
            putInt(KEY_ACTIVITY_SCORE, 0)
            putInt(KEY_ACTIVE_MINUTES, 0)
            putInt(KEY_SLEEP_SCORE, 0)
            putInt(KEY_SLEEP_DEEP_MINS, 0)
            putInt(KEY_SLEEP_REM_MINS, 0)
            putInt(KEY_SLEEP_LIGHT_MINS, 0)
            putInt(KEY_SLEEP_AWAKE_MINS, 0)
            putInt(KEY_SLEEP_EFFICIENCY_PCT, 0)
            putInt(KEY_HR_PHASE_DEEP, 0)
            putInt(KEY_HR_PHASE_REM, 0)
            putInt(KEY_HR_PHASE_LIGHT, 0)
            putFloat(KEY_ODI_DROPS_PER_HOUR, 0.0f)
            putInt(KEY_MIN_SLEEPING_SPO2, 0)
            putFloat(KEY_SLEEPING_HR_DIP_PCT, 0.0f)
            putInt(KEY_NAP_MINUTES, 0)
            putInt(KEY_STAND_HOURS, 0)
            putInt(KEY_MIN_HR, 0)
            putInt(KEY_MAX_HR, 0)
            putInt(KEY_ZONE1_MINS, 0)
            putInt(KEY_ZONE2_MINS, 0)
            putInt(KEY_ZONE3_MINS, 0)
            putInt(KEY_ZONE4_MINS, 0)
            putInt(KEY_ZONE5_MINS, 0)
            putInt(KEY_STRESS_DAWN, 0)
            putInt(KEY_STRESS_MORNING, 0)
            putInt(KEY_STRESS_AFTERNOON, 0)
            putInt(KEY_STRESS_NIGHT, 0)
            putInt(KEY_BASAL_CALORIES, 0)
            putFloat(KEY_DISTANCE_KM, 0.0f)
            putString(KEY_SLEEP_INSIGHT, "")
            putString(KEY_ACTIVITY_INSIGHT, "")
            putString(KEY_STRESS_INSIGHT, "")
            putString(KEY_VITALS_INSIGHT, "")
            putLong(KEY_CACHE_EPOCH_DAY, todayEpochDay)
            apply()
        }
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
            // Basal calories are a record the ring writes, not clock arithmetic: a new day
            // starts with none until the ring publishes today's rate.
            basalCalories = 0,
            distanceKm = 0.0f
        )
    }

    suspend fun readBestAvailableVitalsSuspend(context: Context): RingConnVitals = coroutineScope {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ensureDailyRollover(prefs)

        // 1. Direct AppWidgetHost extraction and Health Connect concurrently
        val widgetDeferred = async {
            try {
                RingConnWidgetHostReader.readRingConnWidgets(context)
            } catch (e: Exception) {
                Log.d(TAG, "WidgetHost read attempt: ${e.message}")
                null
            }
        }
        val hcDeferred = async {
            try {
                readFromHealthConnect(context)
            } catch (e: Exception) {
                Log.e(TAG, "Health Connect read exception: ${e.message}")
                null
            }
        }
        val skinTemperatureDeferred = async {
            try {
                readSkinTemperature(context)
            } catch (e: Exception) {
                Log.w(TAG, "Skin temperature read exception: ${e.message}")
                null
            }
        }

        val widgetVitals = widgetDeferred.await()
        val hcVitals = hcDeferred.await()
        val hcSkinTemperature = skinTemperatureDeferred.await()

        if (widgetVitals != null && (
                widgetVitals.lastHeartRate > 0 ||
                widgetVitals.sleepScore > 0 ||
                widgetVitals.liveSteps > 0 ||
                widgetVitals.activityScore > 0 ||
                widgetVitals.ringBattery > 0 ||
                widgetVitals.lastSpo2 > 0 ||
                widgetVitals.lastHrv > 0 ||
                widgetVitals.stressScore > 0 ||
                widgetVitals.skinTempBaseline > 0f ||
                widgetVitals.skinTempOffset != 0f
            )) {
            val cached = parseFromPreferences(prefs)
            val nowLocal = ZonedDateTime.now(ZoneId.systemDefault())
            val curHour = nowLocal.hour
            val prevDaySteps = prefs.getInt(KEY_PREV_DAY_STEPS, 0)
            val hcTodaySteps = hcVitals?.liveSteps ?: 0

            // Guard against unrefreshed RingConn Steps/Activity widget right after midnight still showing yesterday's totals
            val isWidgetStepsStaleFromYesterday = (prevDaySteps > 0 && widgetVitals.liveSteps == prevDaySteps && hcTodaySteps < prevDaySteps) ||
                (curHour in 0..4 && widgetVitals.liveSteps >= 500 && hcTodaySteps < (widgetVitals.liveSteps / 4))
            val effectiveWidgetSteps = if (isWidgetStepsStaleFromYesterday) {
                Log.i(TAG, "Midnight Guard: Suppressing stale yesterday widget steps (${widgetVitals.liveSteps}, prevDay=$prevDaySteps, hcToday=$hcTodaySteps)")
                0
            } else {
                widgetVitals.liveSteps
            }
            val effectiveWidgetCalorie = if (isWidgetStepsStaleFromYesterday) 0 else widgetVitals.liveCalorie
            val effectiveWidgetActScore = if (isWidgetStepsStaleFromYesterday) 0 else widgetVitals.activityScore

            // Guard against unrefreshed Sleep widget between 00:00 and 03:59 AM before today's sleep concludes
            val hcHasSleepToday = (hcVitals?.sleepScore ?: 0) > 0 || ((hcVitals?.sleepDeepMins ?: 0) + (hcVitals?.sleepRemMins ?: 0) + (hcVitals?.sleepLightMins ?: 0)) > 0
            val isWidgetSleepStaleFromYesterday = curHour in 0..3 && widgetVitals.sleepScore > 0 && !hcHasSleepToday
            val effectiveWidgetSleepScore = if (isWidgetSleepStaleFromYesterday) {
                Log.i(TAG, "Midnight Guard: Suppressing stale yesterday widget sleepScore (${widgetVitals.sleepScore}) at hour=$curHour before today's sleep concludes")
                0
            } else {
                widgetVitals.sleepScore
            }

            // Smart Timestamp Merge: Compare widget sample time vs Health Connect sample time
            val hcHrIsFresher = shouldPrioritizeHealthConnectHr(hcVitals, widgetVitals)
            if (hcHrIsFresher) {
                Log.i(TAG, "Smart Merge: Health Connect HR (${hcVitals!!.lastHeartRate} bpm @ ${hcVitals.refreshTimestamp}) is FRESHER than widget (${widgetVitals.lastHeartRate} bpm @ ${widgetVitals.refreshTimestamp}) -> prioritizing Health Connect HR")
            } else {
                Log.i(TAG, "Smart Merge: Widget vitals (${widgetVitals.lastHeartRate} bpm @ ${widgetVitals.refreshTimestamp}) are >= Health Connect (${hcVitals?.refreshTimestamp ?: 0}) -> prioritizing Widget vitals")
            }

            val finalHr = if (hcHrIsFresher) {
                hcVitals!!.lastHeartRate
            } else if (widgetVitals.lastHeartRate > 0) {
                widgetVitals.lastHeartRate
            } else {
                hcVitals?.lastHeartRate?.takeIf { it > 0 } ?: cached.lastHeartRate
            }

            val finalAvgHr = if (hcHrIsFresher && hcVitals!!.avgHeartRate > 0) {
                hcVitals.avgHeartRate
            } else if (widgetVitals.avgHeartRate > 0) {
                widgetVitals.avgHeartRate
            } else {
                hcVitals?.avgHeartRate?.takeIf { it > 0 } ?: cached.avgHeartRate
            }

            val finalSpo2 = if (hcHrIsFresher && hcVitals!!.lastSpo2 > 0) {
                hcVitals.lastSpo2
            } else if (widgetVitals.lastSpo2 > 0) {
                widgetVitals.lastSpo2
            } else {
                hcVitals?.lastSpo2?.takeIf { it > 0 } ?: cached.lastSpo2
            }

            val finalAvgSpo2 = if (hcHrIsFresher && hcVitals!!.avgSpo2 > 0) {
                hcVitals.avgSpo2
            } else if (widgetVitals.avgSpo2 > 0) {
                widgetVitals.avgSpo2
            } else {
                hcVitals?.avgSpo2?.takeIf { it > 0 } ?: cached.avgSpo2
            }

            val finalHrv = if (hcHrIsFresher && hcVitals!!.lastHrv > 0) {
                hcVitals.lastHrv
            } else if (widgetVitals.lastHrv > 0) {
                widgetVitals.lastHrv
            } else {
                hcVitals?.lastHrv?.takeIf { it > 0 } ?: cached.lastHrv
            }

            val finalAvgHrv = if (hcHrIsFresher && hcVitals!!.avgHrv > 0) {
                hcVitals.avgHrv
            } else if (widgetVitals.avgHrv > 0) {
                widgetVitals.avgHrv
            } else {
                hcVitals?.avgHrv?.takeIf { it > 0 } ?: cached.avgHrv
            }

            val cachedMinHrSanitized = cached.minHeartRate.takeIf { cMin ->
                cMin > 0 && !( (cMin == finalHrv || cMin == finalAvgHrv) && finalHr > cMin + 10 )
            } ?: 0
            val minHr = hcVitals?.minHeartRate?.takeIf { it > 0 } ?: cachedMinHrSanitized
            val maxHr = hcVitals?.maxHeartRate?.takeIf { it > 0 } ?: cached.maxHeartRate
            val z1 = hcVitals?.zone1Mins?.takeIf { it > 0 } ?: cached.zone1Mins
            val z2 = hcVitals?.zone2Mins?.takeIf { it > 0 } ?: cached.zone2Mins
            val z3 = hcVitals?.zone3Mins?.takeIf { it > 0 } ?: cached.zone3Mins
            val z4 = hcVitals?.zone4Mins?.takeIf { it > 0 } ?: cached.zone4Mins
            val z5 = hcVitals?.zone5Mins?.takeIf { it > 0 } ?: cached.zone5Mins

            val finalSteps = maxOf(effectiveWidgetSteps, hcTodaySteps, cached.liveSteps)
            val finalCalorie = maxOf(effectiveWidgetCalorie, hcVitals?.liveCalorie ?: 0, cached.liveCalorie)
            val finalActScore = if (effectiveWidgetActScore > 0) {
                effectiveWidgetActScore
            } else if ((hcVitals?.activityScore ?: 0) > 0) {
                hcVitals!!.activityScore
            } else if (finalSteps > 0 || finalCalorie > 0) {
                ((finalSteps.toDouble() / 8000.0 * 55.0) + (finalCalorie.toDouble() / 350.0 * 45.0)).roundToInt().coerceIn(0, 100)
            } else {
                cached.activityScore
            }
            val widgetAsleep = if (!isWidgetSleepStaleFromYesterday) (widgetVitals.sleepDeepMins + widgetVitals.sleepRemMins + widgetVitals.sleepLightMins) else 0
            val cachedAsleep = cached.sleepDeepMins + cached.sleepRemMins + cached.sleepLightMins
            val hcAsleep = (hcVitals?.sleepDeepMins ?: 0) + (hcVitals?.sleepRemMins ?: 0) + (hcVitals?.sleepLightMins ?: 0)
            val finalBattery = if (widgetVitals.ringBattery > 0) widgetVitals.ringBattery else cached.ringBattery
            val finalSleepScore = if (effectiveWidgetSleepScore > 0) {
                effectiveWidgetSleepScore
            } else if (cachedAsleep > hcAsleep && cached.sleepScore > 0) {
                cached.sleepScore
            } else {
                hcVitals?.sleepScore?.takeIf { it > 0 } ?: cached.sleepScore
            }
            val finalStressScore = if (widgetVitals.stressScore > 0) widgetVitals.stressScore else (hcVitals?.stressScore?.takeIf { it > 0 } ?: cached.stressScore)

            // Health Connect owns the night it has a record for; the widget scrape stays as the
            // fallback for a night it does not. A stale or misparsed reading must not outlive the
            // guard, and the baseline is never invented: unknown stays unknown. When Health
            // Connect wins with a record that carries no delta, the offset is 0.0 because the
            // deviation is unknown, not because a measured zero was read.
            val hcTempBaseline = hcSkinTemperature?.baselineCelsius
                ?.takeIf { SkinTemperatureGuard.isPlausibleBaseline(it) }
            val mergedTempBaseline = if (hcTempBaseline != null) {
                hcTempBaseline
            } else {
                SkinTemperatureGuard.sanitizeBaseline(
                    if (widgetVitals.skinTempBaseline > 0f) widgetVitals.skinTempBaseline else cached.skinTempBaseline
                )
            }
            val mergedTempOffset = if (hcSkinTemperature != null) {
                SkinTemperatureGuard.sanitizeOffset(hcSkinTemperature.offsetCelsius)
            } else {
                SkinTemperatureGuard.sanitizeOffset(
                    if (widgetVitals.skinTempOffset != 0.0f) widgetVitals.skinTempOffset else cached.skinTempOffset
                )
            }

            val mergedSleepInsight = if (isWidgetSleepStaleFromYesterday) cached.sleepInsight else widgetVitals.sleepInsight.ifBlank { cached.sleepInsight }
            val mergedStressInsight = widgetVitals.stressInsight.ifBlank { cached.stressInsight }
            val mergedActivityInsight = if (isWidgetStepsStaleFromYesterday) cached.activityInsight else widgetVitals.activityInsight.ifBlank { cached.activityInsight }
            val mergedVitalsInsight = widgetVitals.vitalsInsight.ifBlank { cached.vitalsInsight }

            // One source owns the night. Health Connect is the only one with per-stage
            // records; the cache covers a night it has not received yet. The widget is not
            // consulted: it carries a score, not stage minutes, and picking whichever source
            // claimed the most sleep is what kept an inflated cache ahead of a correct record.
            val hcNight = hcVitals?.let {
                SleepSourcePolicy.StageMinutes(
                    deep = it.sleepDeepMins,
                    rem = it.sleepRemMins,
                    light = it.sleepLightMins,
                    awake = it.sleepAwakeMins
                )
            }
            val cachedNight = SleepSourcePolicy.StageMinutes(
                deep = cached.sleepDeepMins,
                rem = cached.sleepRemMins,
                light = cached.sleepLightMins,
                awake = cached.sleepAwakeMins
            )
            val night = SleepSourcePolicy.stages(hc = hcNight, cached = cachedNight)
            val mergedDeep = night.deep
            val mergedRem = night.rem
            val mergedLight = night.light
            val mergedAwake = night.awake

            // A phase heart rate the Health Connect read measured survives; nothing is
            // derived from an anchor here, so a night without stage samples has none.
            val mergedHrDeep = MeasuredVitalsPolicy.hrPhase(hcVitals?.hrPhaseDeep?.takeIf { it > 0 } ?: cached.hrPhaseDeep)
            val mergedHrLight = MeasuredVitalsPolicy.hrPhase(hcVitals?.hrPhaseLight?.takeIf { it > 0 } ?: cached.hrPhaseLight)
            val mergedHrRem = MeasuredVitalsPolicy.hrPhase(hcVitals?.hrPhaseRem?.takeIf { it > 0 } ?: cached.hrPhaseRem)

            val totalAsleepM = mergedDeep + mergedRem + mergedLight
            // No nocturnal span survives into this merge, so the denominator falls back to
            // the stage sum; a real efficiency from the reader is preferred just below.
            val totalInBedM = MeasuredVitalsPolicy.inBedMinutes(0L, totalAsleepM, mergedAwake)
            val mergedSleepEff = hcVitals?.sleepEfficiencyPct?.takeIf { it > 0 }
                ?: MeasuredVitalsPolicy.sleepEfficiencyPct(totalAsleepM, totalInBedM)

            // Blocks the ring recorded measurements for survive; blocks it did not record stay
            // unknown instead of being filled from the stress score times a circadian factor.
            val mergedStressDawn = MeasuredVitalsPolicy.blockStress(hcVitals?.stressDawn?.takeIf { it > 0 } ?: cached.stressDawn)
            val mergedStressMorning = if (curHour >= 6) MeasuredVitalsPolicy.blockStress(hcVitals?.stressMorning?.takeIf { it > 0 } ?: cached.stressMorning) else 0
            val mergedStressAfternoon = if (curHour >= 12) MeasuredVitalsPolicy.blockStress(hcVitals?.stressAfternoon?.takeIf { it > 0 } ?: cached.stressAfternoon) else 0
            val mergedStressNight = if (curHour >= 18) MeasuredVitalsPolicy.blockStress(hcVitals?.stressNight?.takeIf { it > 0 } ?: cached.stressNight) else 0

            val mergedRespRate = hcVitals?.respiratoryRate?.takeIf { it > 0f } ?: cached.respiratoryRate

            val mergedOdi = hcVitals?.odiDropsPerHour?.takeIf { it > 0f } ?: (if (!isWidgetSleepStaleFromYesterday && widgetVitals.odiDropsPerHour > 0f) widgetVitals.odiDropsPerHour else cached.odiDropsPerHour)

            val merged = widgetVitals.copy(
                lastHeartRate = finalHr,
                avgHeartRate = finalAvgHr,
                lastSpo2 = finalSpo2,
                avgSpo2 = finalAvgSpo2,
                lastHrv = finalHrv,
                avgHrv = finalAvgHrv,
                liveSteps = finalSteps,
                liveCalorie = finalCalorie,
                activityScore = finalActScore,
                activeMinutes = if (finalSteps > 0) maxOf(hcVitals?.activeMinutes ?: 0, (finalSteps / 100).coerceAtLeast(1)) else 0,
                ringBattery = finalBattery,
                sleepScore = finalSleepScore,
                stressScore = finalStressScore,
                refreshTimestamp = if (hcHrIsFresher) {
                    maxOf(widgetVitals.refreshTimestamp, hcVitals?.refreshTimestamp ?: 0L)
                } else {
                    widgetVitals.refreshTimestamp
                },
                minHeartRate = minHr,
                maxHeartRate = maxHr,
                zone1Mins = z1,
                zone2Mins = z2,
                zone3Mins = z3,
                zone4Mins = z4,
                zone5Mins = z5,
                sleepDeepMins = mergedDeep,
                sleepRemMins = mergedRem,
                sleepLightMins = mergedLight,
                sleepAwakeMins = mergedAwake,
                skinTempOffset = mergedTempOffset,
                skinTempBaseline = mergedTempBaseline,
                sleepInsight = mergedSleepInsight,
                stressInsight = mergedStressInsight,
                activityInsight = mergedActivityInsight,
                vitalsInsight = mergedVitalsInsight,
                odiDropsPerHour = mergedOdi,
                minSleepingSpo2 = hcVitals?.minSleepingSpo2?.takeIf { it > 0 } ?: (if (!isWidgetSleepStaleFromYesterday && widgetVitals.minSleepingSpo2 > 0) widgetVitals.minSleepingSpo2 else cached.minSleepingSpo2),
                sleepingHrDipPct = hcVitals?.sleepingHrDipPct?.takeIf { it > 0f } ?: (if (!isWidgetSleepStaleFromYesterday && widgetVitals.sleepingHrDipPct > 0f) widgetVitals.sleepingHrDipPct else cached.sleepingHrDipPct),
                napMinutes = hcVitals?.napMinutes?.takeIf { it > 0 } ?: (if (widgetVitals.napMinutes > 0) widgetVitals.napMinutes else cached.napMinutes),
                bedtimeWindow = hcVitals?.bedtimeWindow?.takeIf { it.isNotBlank() } ?: (if (widgetVitals.bedtimeWindow.isNotBlank()) widgetVitals.bedtimeWindow else cached.bedtimeWindow),
                standHours = hcVitals?.standHours?.takeIf { it > 0 } ?: (if (!isWidgetStepsStaleFromYesterday && widgetVitals.standHours > 0) widgetVitals.standHours else cached.standHours),
                hrPhaseDeep = mergedHrDeep,
                hrPhaseRem = mergedHrRem,
                hrPhaseLight = mergedHrLight,
                sleepEfficiencyPct = mergedSleepEff,
                stressDawn = mergedStressDawn,
                stressMorning = mergedStressMorning,
                stressAfternoon = mergedStressAfternoon,
                stressNight = mergedStressNight,
                basalCalories = 0,
                distanceKm = 0.0f,
                respiratoryRate = mergedRespRate
            )
            val sourceLabel = if (hcHrIsFresher) "Health Connect (Fresher)" else "Glance Widgets + HC"
            saveToPreferences(context, merged, source = sourceLabel, stagesAuthoritative = true)
            return@coroutineScope merged
        }

        // 2. Health Connect API
        if (hcVitals != null && (hcVitals.lastHeartRate > 0 || hcVitals.liveSteps > 0 || hcVitals.lastSpo2 > 0)) {
            val cached = parseFromPreferences(prefs)
            val hcAsleep = hcVitals.sleepDeepMins + hcVitals.sleepRemMins + hcVitals.sleepLightMins
            val cachedAsleep = cached.sleepDeepMins + cached.sleepRemMins + cached.sleepLightMins
            // Same rule as the Health Connect read: capability, not size. The score keeps its
            // own logic below because it legitimately comes from the widget.
            val keepCachedSleep = cachedAsleep > hcAsleep && cachedAsleep > 0
            val mergedNight = SleepSourcePolicy.stages(
                hc = SleepSourcePolicy.StageMinutes(
                    deep = hcVitals.sleepDeepMins,
                    rem = hcVitals.sleepRemMins,
                    light = hcVitals.sleepLightMins,
                    awake = hcVitals.sleepAwakeMins
                ),
                cached = SleepSourcePolicy.StageMinutes(
                    deep = cached.sleepDeepMins,
                    rem = cached.sleepRemMins,
                    light = cached.sleepLightMins,
                    awake = cached.sleepAwakeMins
                )
            )
            val finalDeep = mergedNight.deep
            val finalRem = mergedNight.rem
            val finalLight = mergedNight.light
            val finalAwake = mergedNight.awake
            val finalSleepScore = if (keepCachedSleep && cached.sleepScore > 0) cached.sleepScore else hcVitals.sleepScore

            val finalHcVitals = hcVitals.copy(
                lastHeartRate = if (hcVitals.lastHeartRate > 0) hcVitals.lastHeartRate else cached.lastHeartRate,
                avgHeartRate = if (hcVitals.avgHeartRate > 0) hcVitals.avgHeartRate else cached.avgHeartRate,
                refreshTimestamp = if (hcVitals.refreshTimestamp > 0L) hcVitals.refreshTimestamp else cached.refreshTimestamp,
                sleepScore = finalSleepScore,
                sleepDeepMins = finalDeep,
                sleepRemMins = finalRem,
                sleepLightMins = finalLight,
                sleepAwakeMins = finalAwake,
                sleepInsight = hcVitals.sleepInsight.ifBlank { cached.sleepInsight },
                stressInsight = hcVitals.stressInsight.ifBlank { cached.stressInsight },
                activityInsight = hcVitals.activityInsight.ifBlank { cached.activityInsight },
                vitalsInsight = hcVitals.vitalsInsight.ifBlank { cached.vitalsInsight },
                odiDropsPerHour = if (hcVitals.odiDropsPerHour > 0f) hcVitals.odiDropsPerHour else cached.odiDropsPerHour,
                minSleepingSpo2 = if (hcVitals.minSleepingSpo2 > 0) hcVitals.minSleepingSpo2 else cached.minSleepingSpo2,
                sleepingHrDipPct = if (hcVitals.sleepingHrDipPct > 0f) hcVitals.sleepingHrDipPct else cached.sleepingHrDipPct,
                napMinutes = if (hcVitals.napMinutes > 0) hcVitals.napMinutes else cached.napMinutes,
                bedtimeWindow = hcVitals.bedtimeWindow.ifBlank { cached.bedtimeWindow },
                standHours = if (hcVitals.standHours > 0) hcVitals.standHours else cached.standHours,
                // The Health Connect skin-temperature read feeds this path too, or a sync without
                // widget data would fall back to a baseline that predates the record.
                skinTempBaseline = hcSkinTemperature?.baselineCelsius
                    ?.takeIf { SkinTemperatureGuard.isPlausibleBaseline(it) }
                    ?: SkinTemperatureGuard.sanitizeBaseline(hcVitals.skinTempBaseline),
                skinTempOffset = if (hcSkinTemperature != null) {
                    SkinTemperatureGuard.sanitizeOffset(hcSkinTemperature.offsetCelsius)
                } else {
                    SkinTemperatureGuard.sanitizeOffset(hcVitals.skinTempOffset)
                },
                hrPhaseDeep = if (hcVitals.hrPhaseDeep > 0) hcVitals.hrPhaseDeep else cached.hrPhaseDeep,
                hrPhaseRem = if (hcVitals.hrPhaseRem > 0) hcVitals.hrPhaseRem else cached.hrPhaseRem,
                hrPhaseLight = if (hcVitals.hrPhaseLight > 0) hcVitals.hrPhaseLight else cached.hrPhaseLight,
                sleepEfficiencyPct = if (hcVitals.sleepEfficiencyPct > 0) hcVitals.sleepEfficiencyPct else cached.sleepEfficiencyPct,
                stressDawn = if (hcVitals.stressDawn > 0) hcVitals.stressDawn else cached.stressDawn,
                stressMorning = if (hcVitals.stressMorning > 0) hcVitals.stressMorning else cached.stressMorning,
                stressAfternoon = if (hcVitals.stressAfternoon > 0) hcVitals.stressAfternoon else cached.stressAfternoon,
                stressNight = if (hcVitals.stressNight > 0) hcVitals.stressNight else cached.stressNight,
                basalCalories = 0,
                distanceKm = 0.0f,
                respiratoryRate = if (hcVitals.respiratoryRate > 0f) hcVitals.respiratoryRate else cached.respiratoryRate
            )
            return@coroutineScope finalHcVitals
        }

        // 3. Local cache or user calibration
        if (prefs.contains(KEY_LASTLY_HR)) {
            return@coroutineScope parseFromPreferences(prefs)
        }

        // Initial default if cache is completely fresh/empty
        return@coroutineScope RingConnVitals(
            lastHeartRate = 0,
            avgHeartRate = 0,
            lastSpo2 = 0,
            avgSpo2 = 0,
            lastHrv = 0,
            avgHrv = 0,
            sleepScore = 0,
            activityScore = 0,
            stressScore = 0,
            liveSteps = 0,
            targetSteps = 10000,
            liveCalorie = 0,
            targetCalorie = 500,
            ringBattery = 0,
            isCharging = false,
            isConnected = false,
            refreshTimestamp = System.currentTimeMillis() / 1000L,
            skinTempOffset = 0.0f,
            skinTempBaseline = 0.0f
        )
    }

    fun readBestAvailableVitals(context: Context): RingConnVitals {
        val localPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ensureDailyRollover(localPrefs)
        if (localPrefs.contains(KEY_LASTLY_HR)) {
            return parseFromPreferences(localPrefs)
        }
        return RingConnVitals(
            lastHeartRate = 0,
            avgHeartRate = 0,
            lastSpo2 = 0,
            avgSpo2 = 0,
            lastHrv = 0,
            avgHrv = 0,
            sleepScore = 0,
            activityScore = 0,
            stressScore = 0,
            liveSteps = 0,
            targetSteps = 10000,
            liveCalorie = 0,
            targetCalorie = 500,
            ringBattery = 0,
            isCharging = false,
            isConnected = false,
            refreshTimestamp = System.currentTimeMillis() / 1000L
        )
    }

    /**
     * @param stagesAuthoritative true only for the two paths that resolve stage minutes properly:
     *   the Health Connect read and the policy-merged write. Scrapes and widget reads pass false so
     *   they cannot overwrite a night another source already resolved.
     */
    fun saveToPreferences(
        context: Context,
        vitals: RingConnVitals,
        source: String = "Manual",
        stagesAuthoritative: Boolean = false
    ) {
        val nowZoned = ZonedDateTime.now(ZoneId.systemDefault())
        val todayEpochDay = nowZoned.toLocalDate().toEpochDay()
        val timeStr = nowZoned.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ensureDailyRollover(prefs, todayEpochDay)

        val stages = SleepSourcePolicy.stagesForWrite(
            incoming = SleepSourcePolicy.StageMinutes(
                deep = vitals.sleepDeepMins,
                rem = vitals.sleepRemMins,
                light = vitals.sleepLightMins,
                awake = vitals.sleepAwakeMins
            ),
            persisted = SleepSourcePolicy.StageMinutes(
                deep = prefs.getInt(KEY_SLEEP_DEEP_MINS, 0),
                rem = prefs.getInt(KEY_SLEEP_REM_MINS, 0),
                light = prefs.getInt(KEY_SLEEP_LIGHT_MINS, 0),
                awake = prefs.getInt(KEY_SLEEP_AWAKE_MINS, 0)
            ),
            authoritative = stagesAuthoritative
        )

        prefs.edit().apply {
            putLong(KEY_CACHE_EPOCH_DAY, todayEpochDay)
            if (vitals.lastHeartRate > 0) putInt(KEY_LASTLY_HR, vitals.lastHeartRate)
            if (vitals.avgHeartRate > 0) putInt(KEY_AVG_HR, vitals.avgHeartRate)
            if (vitals.lastSpo2 > 0) putInt(KEY_LASTLY_SPO, vitals.lastSpo2)
            if (vitals.avgSpo2 > 0) putInt(KEY_AVG_SPO, vitals.avgSpo2)
            if (vitals.lastHrv > 0) putInt(KEY_LASTLY_HRV, vitals.lastHrv)
            if (vitals.avgHrv > 0) putInt(KEY_AVG_HRV, vitals.avgHrv)
            putInt(KEY_SLEEP_SCORE, vitals.sleepScore)
            putInt(KEY_ACTIVITY_SCORE, vitals.activityScore)
            putInt(KEY_ACTIVE_MINUTES, vitals.activeMinutes)
            putInt(KEY_STRESS_SCORE, vitals.stressScore)
            putInt(KEY_LIVE_STEPS, vitals.liveSteps)
            putInt(KEY_TARGET_STEPS, vitals.targetSteps)
            putInt(KEY_LIVE_CALORIE, vitals.liveCalorie)
            putInt(KEY_TARGET_CALORIE, vitals.targetCalorie)
            putInt(KEY_ELECTRIC, vitals.ringBattery)
            putBoolean(KEY_IS_CHARGE, vitals.isCharging)
            putBoolean(KEY_IS_CONNECTED, vitals.isConnected)
            if (vitals.refreshTimestamp > 0L) putInt(KEY_REFRESH_TIMESTAMP, vitals.refreshTimestamp.toInt())
            putInt(KEY_MIN_HR, vitals.minHeartRate)
            putInt(KEY_MAX_HR, vitals.maxHeartRate)
            putInt(KEY_ZONE1_MINS, vitals.zone1Mins)
            putInt(KEY_ZONE2_MINS, vitals.zone2Mins)
            putInt(KEY_ZONE3_MINS, vitals.zone3Mins)
            putInt(KEY_ZONE4_MINS, vitals.zone4Mins)
            putInt(KEY_ZONE5_MINS, vitals.zone5Mins)
            putInt(KEY_SLEEP_DEEP_MINS, stages.deep)
            putInt(KEY_SLEEP_REM_MINS, stages.rem)
            putInt(KEY_SLEEP_LIGHT_MINS, stages.light)
            putInt(KEY_SLEEP_AWAKE_MINS, stages.awake)
            // A bad scrape must not become a stored reading.
            putFloat(KEY_SKIN_TEMP_OFFSET, SkinTemperatureGuard.sanitizeOffset(vitals.skinTempOffset))
            putFloat(KEY_SKIN_TEMP_BASELINE, SkinTemperatureGuard.sanitizeBaseline(vitals.skinTempBaseline))
            putString(KEY_SLEEP_INSIGHT, vitals.sleepInsight.ifBlank { prefs.getString(KEY_SLEEP_INSIGHT, "") ?: "" })
            putString(KEY_STRESS_INSIGHT, vitals.stressInsight.ifBlank { prefs.getString(KEY_STRESS_INSIGHT, "") ?: "" })
            putString(KEY_ACTIVITY_INSIGHT, vitals.activityInsight.ifBlank { prefs.getString(KEY_ACTIVITY_INSIGHT, "") ?: "" })
            putString(KEY_VITALS_INSIGHT, vitals.vitalsInsight.ifBlank { prefs.getString(KEY_VITALS_INSIGHT, "") ?: "" })
            putFloat(KEY_ODI_DROPS_PER_HOUR, vitals.odiDropsPerHour)
            putInt(KEY_MIN_SLEEPING_SPO2, vitals.minSleepingSpo2)
            putFloat(KEY_SLEEPING_HR_DIP_PCT, vitals.sleepingHrDipPct)
            putInt(KEY_NAP_MINUTES, vitals.napMinutes)
            putString(KEY_BEDTIME_WINDOW, vitals.bedtimeWindow.ifBlank { prefs.getString(KEY_BEDTIME_WINDOW, "") ?: "" })
            putInt(KEY_STAND_HOURS, vitals.standHours)
            // These keys carry measurement-derived values only. The reader no longer derives
            // them, so a zero means "not measured" and must not be back-filled from the cache.
            putInt(KEY_VITALS_SCHEMA, VITALS_SCHEMA_VERSION)
            putInt(KEY_HR_PHASE_DEEP, vitals.hrPhaseDeep)
            putInt(KEY_HR_PHASE_REM, vitals.hrPhaseRem)
            putInt(KEY_HR_PHASE_LIGHT, vitals.hrPhaseLight)
            putInt(KEY_SLEEP_EFFICIENCY_PCT, vitals.sleepEfficiencyPct)
            putInt(KEY_STRESS_DAWN, vitals.stressDawn)
            putInt(KEY_STRESS_MORNING, vitals.stressMorning)
            putInt(KEY_STRESS_AFTERNOON, vitals.stressAfternoon)
            putInt(KEY_STRESS_NIGHT, vitals.stressNight)
            putInt(KEY_BASAL_CALORIES, vitals.basalCalories)
            putFloat(KEY_DISTANCE_KM, vitals.distanceKm)
            putFloat(KEY_RESPIRATORY_RATE, if (vitals.respiratoryRate > 0f) vitals.respiratoryRate else prefs.getFloat(KEY_RESPIRATORY_RATE, 0.0f))
            putString(KEY_LAST_SYNC_SOURCE, source)
            putString(KEY_LAST_SYNC_TIME_STR, timeStr)
            apply()
        }
    }

    /**
     * Forgets derived values persisted before the verified-only rule.
     *
     * Those keys hold both measurements and the arithmetic that used to fill them, so a
     * restored number cannot be proven to be a reading. Dropping them once means the watch
     * shows "unknown" until the next measured sample, instead of showing an invention that
     * looks like data forever.
     */
    fun dropLegacyInventedVitals(prefs: SharedPreferences): Boolean {
        if (prefs.getInt(KEY_VITALS_SCHEMA, 1) >= VITALS_SCHEMA_VERSION) return false
        prefs.edit().apply {
            putInt(KEY_HR_PHASE_DEEP, 0)
            putInt(KEY_HR_PHASE_REM, 0)
            putInt(KEY_HR_PHASE_LIGHT, 0)
            putInt(KEY_SLEEP_EFFICIENCY_PCT, 0)
            // The score shares one key with the widget's own score, so a value stored by the
            // old stage arithmetic is indistinguishable from a reading. One cycle of unknown
            // until the next widget sync is cheaper than trusting a number that might be ours.
            putInt(KEY_SLEEP_SCORE, 0)
            putInt(KEY_STRESS_DAWN, 0)
            putInt(KEY_STRESS_MORNING, 0)
            putInt(KEY_STRESS_AFTERNOON, 0)
            putInt(KEY_STRESS_NIGHT, 0)
            putInt(KEY_BASAL_CALORIES, 0)
            putFloat(KEY_DISTANCE_KM, 0.0f)
            putFloat(KEY_RESPIRATORY_RATE, 0.0f)
            putInt(KEY_VITALS_SCHEMA, VITALS_SCHEMA_VERSION)
            apply()
        }
        return true
    }

    fun parseFromPreferences(prefs: SharedPreferences): RingConnVitals {
        dropLegacyInventedVitals(prefs)
        ensureDailyRollover(prefs)
        return RingConnVitals(
            lastHeartRate = prefs.getInt(KEY_LASTLY_HR, 0),
            avgHeartRate = prefs.getInt(KEY_AVG_HR, 0),
            lastSpo2 = prefs.getInt(KEY_LASTLY_SPO, 0),
            avgSpo2 = prefs.getInt(KEY_AVG_SPO, 0),
            lastHrv = prefs.getInt(KEY_LASTLY_HRV, 0),
            avgHrv = prefs.getInt(KEY_AVG_HRV, 0),
            sleepScore = prefs.getInt(KEY_SLEEP_SCORE, 0),
            activityScore = prefs.getInt(KEY_ACTIVITY_SCORE, 0),
            activeMinutes = prefs.getInt(KEY_ACTIVE_MINUTES, 0),
            stressScore = prefs.getInt(KEY_STRESS_SCORE, 0),
            liveSteps = prefs.getInt(KEY_LIVE_STEPS, 0),
            targetSteps = prefs.getInt(KEY_TARGET_STEPS, 10000),
            liveCalorie = prefs.getInt(KEY_LIVE_CALORIE, 0),
            targetCalorie = prefs.getInt(KEY_TARGET_CALORIE, 500),
            ringBattery = prefs.getInt(KEY_ELECTRIC, 80),
            isCharging = prefs.getBoolean(KEY_IS_CHARGE, false),
            isConnected = prefs.getBoolean(KEY_IS_CONNECTED, true),
            refreshTimestamp = prefs.getInt(KEY_REFRESH_TIMESTAMP, (System.currentTimeMillis() / 1000L).toInt()).toLong(),
            minHeartRate = prefs.getInt(KEY_MIN_HR, 0),
            maxHeartRate = prefs.getInt(KEY_MAX_HR, 0),
            zone1Mins = prefs.getInt(KEY_ZONE1_MINS, 0),
            zone2Mins = prefs.getInt(KEY_ZONE2_MINS, 0),
            zone3Mins = prefs.getInt(KEY_ZONE3_MINS, 0),
            zone4Mins = prefs.getInt(KEY_ZONE4_MINS, 0),
            zone5Mins = prefs.getInt(KEY_ZONE5_MINS, 0),
            sleepDeepMins = prefs.getInt(KEY_SLEEP_DEEP_MINS, 0),
            sleepRemMins = prefs.getInt(KEY_SLEEP_REM_MINS, 0),
            sleepLightMins = prefs.getInt(KEY_SLEEP_LIGHT_MINS, 0),
            sleepAwakeMins = prefs.getInt(KEY_SLEEP_AWAKE_MINS, 0),
            skinTempOffset = SkinTemperatureGuard.sanitizeOffset(prefs.getFloat(KEY_SKIN_TEMP_OFFSET, 0.0f)),
            skinTempBaseline = SkinTemperatureGuard.sanitizeBaseline(prefs.getFloat(KEY_SKIN_TEMP_BASELINE, 0.0f)),
            sleepInsight = prefs.getString(KEY_SLEEP_INSIGHT, "") ?: "",
            stressInsight = prefs.getString(KEY_STRESS_INSIGHT, "") ?: "",
            activityInsight = prefs.getString(KEY_ACTIVITY_INSIGHT, "") ?: "",
            vitalsInsight = prefs.getString(KEY_VITALS_INSIGHT, "") ?: "",
            odiDropsPerHour = prefs.getFloat(KEY_ODI_DROPS_PER_HOUR, 0.0f),
            minSleepingSpo2 = prefs.getInt(KEY_MIN_SLEEPING_SPO2, 0),
            sleepingHrDipPct = prefs.getFloat(KEY_SLEEPING_HR_DIP_PCT, 0.0f),
            napMinutes = prefs.getInt(KEY_NAP_MINUTES, 0),
            bedtimeWindow = prefs.getString(KEY_BEDTIME_WINDOW, "") ?: "",
            standHours = prefs.getInt(KEY_STAND_HOURS, 0),
            hrPhaseDeep = prefs.getInt(KEY_HR_PHASE_DEEP, 0),
            hrPhaseRem = prefs.getInt(KEY_HR_PHASE_REM, 0),
            hrPhaseLight = prefs.getInt(KEY_HR_PHASE_LIGHT, 0),
            sleepEfficiencyPct = prefs.getInt(KEY_SLEEP_EFFICIENCY_PCT, 0),
            stressDawn = prefs.getInt(KEY_STRESS_DAWN, 0),
            stressMorning = prefs.getInt(KEY_STRESS_MORNING, 0),
            stressAfternoon = prefs.getInt(KEY_STRESS_AFTERNOON, 0),
            stressNight = prefs.getInt(KEY_STRESS_NIGHT, 0),
            basalCalories = prefs.getInt(KEY_BASAL_CALORIES, 0),
            distanceKm = prefs.getFloat(KEY_DISTANCE_KM, 0.0f),
            respiratoryRate = prefs.getFloat(KEY_RESPIRATORY_RATE, 0.0f)
        )
    }

    fun readFromPreferences(prefs: SharedPreferences): RingConnVitals = parseFromPreferences(prefs)
    fun readFromPreferences(context: Context): RingConnVitals = parseFromPreferences(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    /**
     * Returns the latest biometric sample epoch second from Health Connect, or 0L when
     * Health Connect returned no HR/SpO2/HRV samples (never fabricating wall-clock now).
     */
    fun resolveBiometricRefreshTimestamp(
        latestHrTimestamp: Long,
        latestSpo2Timestamp: Long,
        latestHrvTimestamp: Long
    ): Long = maxOf(latestHrTimestamp, latestSpo2Timestamp, latestHrvTimestamp)

    /**
     * Determines whether Health Connect HR should override Widget HR during Smart Merge.
     * Requires a direct Health Connect HR reading (> 0) and a real biometric sample timestamp (> 0L).
     */
    fun shouldPrioritizeHealthConnectHr(
        hcVitals: RingConnVitals?,
        widgetVitals: RingConnVitals
    ): Boolean {
        if (hcVitals == null || hcVitals.lastHeartRate <= 0 || hcVitals.refreshTimestamp <= 0L) {
            return false
        }
        if (widgetVitals.lastHeartRate <= 0) {
            return true
        }
        return hcVitals.refreshTimestamp > widgetVitals.refreshTimestamp
    }
}

/**
 * Every page of a Health Connect read.
 *
 * The platform answers with a cursor once its page size is exhausted, so
 * following the cursor is what makes a two-day window complete instead of
 * silently truncated — a lost page and a quiet night look identical downstream.
 */
private suspend fun <T : Record> HealthConnectClient.readAllPages(
    recordType: KClass<T>,
    timeRange: TimeRangeFilter
): List<T> = PagedRead.collect { pageToken ->
    val page = readRecords(
        ReadRecordsRequest(
            recordType = recordType,
            timeRangeFilter = timeRange,
            pageToken = pageToken
        )
    )
    Page(page.records, page.pageToken)
}
