package io.github.hardbas.wearsync.app.data

/**
 * The four stress blocks RingConn records for a day.
 */
enum class StressPhase {
    DAWN,
    MORNING,
    AFTERNOON,
    NIGHT
}

/** One measured block: the phase and the score the ring reported for it. */
data class StressPhaseReading(
    val phase: StressPhase,
    val score: Int
)

/**
 * The circadian stress series, exactly as the ring measured it.
 *
 * RingConn stores four blocks per day — `stressBeforeDawn`, `stressMorning`,
 * `stressAfternoon` and `stressNight` — and nothing finer. There is no hourly
 * series to draw, so this object never invents one: a block the ring did not
 * measure keeps a score of 0 and is rendered as missing, and a day with no
 * measured block yields an empty series so the screen can show its empty state
 * instead of a curve derived from something else.
 */
object StressPhases {

    fun readings(vitals: RingConnVitals): List<StressPhaseReading> {
        val readings = listOf(
            StressPhaseReading(StressPhase.DAWN, vitals.stressDawn),
            StressPhaseReading(StressPhase.MORNING, vitals.stressMorning),
            StressPhaseReading(StressPhase.AFTERNOON, vitals.stressAfternoon),
            StressPhaseReading(StressPhase.NIGHT, vitals.stressNight)
        )
        return if (readings.any { it.score > 0 }) readings else emptyList()
    }

    /** The highest measured block, or null when the day has no measured block. */
    fun peak(vitals: RingConnVitals): Int? =
        readings(vitals).maxOfOrNull { it.score }?.takeIf { it > 0 }

    /**
     * Only the blocks the ring measured, in day order.
     *
     * A block with no measurement can still gain one later that day, so it is omitted
     * from the card rather than drawn as a placeholder: the row reappears by itself when
     * a reading exists. An empty list means the day has no measured block at all.
     */
    fun measured(vitals: RingConnVitals): List<StressPhaseReading> =
        readings(vitals).filter { it.score > 0 }
}
