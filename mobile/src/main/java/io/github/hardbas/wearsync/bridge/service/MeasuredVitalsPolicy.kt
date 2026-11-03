package io.github.hardbas.wearsync.bridge.service

import kotlin.math.roundToInt

/**
 * Decides every vital the reader used to fill with arithmetic.
 *
 * A value on the watch is either a measurement — a RingConn Glance widget token or a
 * Health Connect record — or it is unknown. The reader violated that in four places:
 * it added fixed offsets to a nightly anchor for the phase heart rates, multiplied the
 * stress score by a circadian factor for each block, added five points to the sleep
 * score to obtain an "efficiency", and granted free points plus a 45 floor to a night
 * with no stage records. All four produce numbers whose magnitude looks measured, which
 * is worse than an empty card: the wearer cannot tell a guess from a reading.
 *
 * This object is therefore a gate rather than a formula. Where the value is already
 * measured it passes it through unchanged; where the measurement is missing it returns
 * 0 so the watch renders "unknown". The only arithmetic left is on measurements
 * themselves (asleep over in bed, and the stage-weighted score of a real night).
 */
object MeasuredVitalsPolicy {

    /** Duration target for a full night, used only to scale a measured duration. */
    private const val DURATION_TARGET_MINS = 450.0
    private const val DURATION_POINTS = 55.0

    private const val DEEP_TARGET_MINS = 75.0
    private const val DEEP_POINTS = 23.0

    private const val REM_TARGET_MINS = 85.0
    private const val REM_POINTS = 22.0

    private const val AWAKE_PENALTY_DIVISOR = 12.0

    /** Highest score a computed night may report; a cap, never a floor. */
    private const val MAX_COMPUTED_SCORE = 98

    /**
     * The mean heart rate measured inside one sleep stage, unchanged.
     *
     * There is deliberately no anchor parameter: an offset from a nightly reference is
     * not a stage measurement, it is the reference wearing a disguise.
     */
    fun hrPhase(measuredBpm: Int): Int = measuredBpm.coerceAtLeast(0)

    /**
     * The stress derived from a block's measured mean heart rate, unchanged.
     *
     * The standalone multiplier that fabricated a value for a block with no heart rate
     * samples is gone, and so is the `34` it fell back to. What survives is a 0.35 weight
     * inside the measured branch, where the block's own mean heart rate is what leads. Those
     * weights still shape a curve that was measured; they no longer invent one.
     */
    fun blockStress(measuredStress: Int): Int = measuredStress.coerceAtLeast(0)

    /**
     * Asleep minutes over in-bed minutes, both from real stages.
     *
     * No floor: a night that barely slept must report the low number it measured instead
     * of being lifted to a plausible 50 %. A sleep score is not an efficiency, so there
     * is no score input here to add five points to.
     */
    fun sleepEfficiencyPct(asleepMinutes: Int, inBedMinutes: Int): Int {
        if (asleepMinutes <= 0 || inBedMinutes <= 0) return 0
        return ((asleepMinutes * 100.0) / inBedMinutes).roundToInt().coerceIn(0, 100)
    }

    /**
     * The score of a night that has stage records, and 0 for one that has none.
     *
     * Each stage contributes in proportion to the minutes actually recorded, so an absent
     * stage contributes nothing. The previous version handed out 18 and 17 points for the
     * stages it could not see and clamped the total to at least 45, which is how a night
     * with no stage data produced a believable score.
     */
    fun computedSleepScore(
        deepMinutes: Long,
        remMinutes: Long,
        lightMinutes: Long,
        awakeMinutes: Long
    ): Int {
        val hasStages = deepMinutes > 0L || remMinutes > 0L || lightMinutes > 0L
        if (!hasStages) return 0

        val asleepMinutes = deepMinutes + remMinutes + lightMinutes
        val durationScore = (asleepMinutes.toDouble() / DURATION_TARGET_MINS).coerceAtMost(1.0) * DURATION_POINTS
        val deepScore = if (deepMinutes > 0L) {
            (deepMinutes.toDouble() / DEEP_TARGET_MINS).coerceAtMost(1.0) * DEEP_POINTS
        } else 0.0
        val remScore = if (remMinutes > 0L) {
            (remMinutes.toDouble() / REM_TARGET_MINS).coerceAtMost(1.0) * REM_POINTS
        } else 0.0
        val awakePenalty = (awakeMinutes.toDouble() / AWAKE_PENALTY_DIVISOR).coerceAtMost(AWAKE_PENALTY_DIVISOR)

        return (durationScore + deepScore + remScore - awakePenalty)
            .roundToInt()
            .coerceIn(0, MAX_COMPUTED_SCORE)
    }

    /**
     * Only the samples the ring itself wrote, and nothing when it wrote none.
     *
     * The records this rule added — distance, basal metabolic rate, respiratory rate — are
     * exactly the ones another writer in Health Connect also produces: the handset counts
     * distance on its own, and a competing health app estimates a basal rate. Taking the
     * ring's samples and then falling back to whoever else wrote the type prints a
     * stranger's number on a card the wearer reads as the ring's, which is the same
     * substitution this object exists to remove. So the filter is strict, and an empty
     * answer is the honest one because the caller renders it as unknown.
     */
    fun <T> ringSamples(records: List<T>, isRingOrigin: (T) -> Boolean): List<T> =
        records.filter(isRingOrigin)
}
