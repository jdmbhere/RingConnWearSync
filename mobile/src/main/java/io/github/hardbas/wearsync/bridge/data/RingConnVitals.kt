package io.github.hardbas.wearsync.bridge.data

data class RingConnVitals(
    val lastHeartRate: Int = 0,
    val avgHeartRate: Int = 0,
    val lastSpo2: Int = 0,
    val avgSpo2: Int = 0,
    val lastHrv: Int = 0,
    val avgHrv: Int = 0,
    val sleepScore: Int = 0,
    val activityScore: Int = 0,
    val activeMinutes: Int = 0,
    val stressScore: Int = 0,
    val liveSteps: Int = 0,
    val targetSteps: Int = 10000,
    val liveCalorie: Int = 0,
    val targetCalorie: Int = 500,
    val ringBattery: Int = 0,
    val isCharging: Boolean = false,
    val isConnected: Boolean = false,
    val refreshTimestamp: Long = 0L,
    val minHeartRate: Int = 0,
    val maxHeartRate: Int = 0,
    val zone1Mins: Int = 0,
    val zone2Mins: Int = 0,
    val zone3Mins: Int = 0,
    val zone4Mins: Int = 0,
    val zone5Mins: Int = 0,
    val sleepDeepMins: Int = 0,
    val sleepRemMins: Int = 0,
    val sleepLightMins: Int = 0,
    val sleepAwakeMins: Int = 0,
    val skinTempOffset: Float = 0.0f,
    val skinTempBaseline: Float = 0.0f,
    val sleepInsight: String = "",
    val stressInsight: String = "",
    val activityInsight: String = "",
    val vitalsInsight: String = "",
    val odiDropsPerHour: Float = 0.0f,
    val minSleepingSpo2: Int = 0,
    val sleepingHrDipPct: Float = 0.0f,
    val napMinutes: Int = 0,
    val bedtimeWindow: String = "",
    val standHours: Int = 0,
    val hrPhaseDeep: Int = 0,
    val hrPhaseRem: Int = 0,
    val hrPhaseLight: Int = 0,
    val sleepEfficiencyPct: Int = 0,
    val stressDawn: Int = 0,
    val stressMorning: Int = 0,
    val stressAfternoon: Int = 0,
    val stressNight: Int = 0,
    val basalCalories: Int = 0,
    val distanceKm: Float = 0.0f,
    val respiratoryRate: Float = 0.0f,
    /**
     * When the numbers above were actually measured, or `null` when no source can say.
     *
     * This is deliberately not [refreshTimestamp]. That field keeps its old meaning: it is set
     * to the printed time when a token parsed and to the wall clock otherwise, so a RingConn
     * screen left open for an hour reports "now" and nothing may derive precedence from it.
     *
     * A source fills this field only from a time it genuinely read, and only when the reading
     * places the sample unambiguously in time. RingConn prints a bare clock time (`8:30 AM`)
     * with no date, so the date must be inferred; the inference is accepted only while the
     * resulting instant is a few hours from now, where "today" is the only plausible day.
     * A wider window is a guess between two days and is therefore reported as `null` rather
     * than as a confident wrong number. `null` means "unknown": no comparison may treat it
     * as zero, and no default may fill it in.
     */
    val sampleAtSeconds: Long? = null
) {
    val isStale: Boolean
        get() = (System.currentTimeMillis() / 1000L) - refreshTimestamp > 1800L // > 30 mins
}
