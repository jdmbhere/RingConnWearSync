package io.github.hardbas.wearsync.bridge.service

import io.github.hardbas.wearsync.bridge.data.RingConnVitals
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
     * In-bed minutes, the denominator of [sleepEfficiencyPct].
     *
     * RingConn reports time in bed from bed time to the final awakening, so it includes the
     * latency to fall asleep. Deriving it as asleep + awake dropped that latency and pushed
     * efficiency above what the ring itself shows for the same night. The nocturnal span is
     * the measured value; the stage sum is only a fallback for a span that is absent or that
     * cannot contain the sleep it measured.
     */
    fun inBedMinutes(nocturnalSpanMinutes: Long, asleepMinutes: Int, awakeMinutes: Int): Int {
        val stageSum = asleepMinutes.coerceAtLeast(0) + awakeMinutes.coerceAtLeast(0)
        if (nocturnalSpanMinutes <= 0L) return stageSum
        val span = nocturnalSpanMinutes.toInt()
        return if (span >= asleepMinutes) span else stageSum
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
     * The records this rule reads — the respiratory rate — are exactly the ones another
     * writer in Health Connect also produces. Taking the ring's samples and then falling
     * back to whoever else wrote the type prints a stranger's number on a card the wearer
     * reads as the ring's, which is the same substitution this object exists to remove. So
     * the filter is strict, and an empty answer is the honest one because the caller renders
     * it as unknown.
     */
    fun <T> ringSamples(records: List<T>, isRingOrigin: (T) -> Boolean): List<T> =
        records.filter(isRingOrigin)

    /**
     * When the two samples were taken, in the only form both sides can be compared in.
     *
     * A `null` on either side is not "zero": it means that source cannot say when the
     * number it holds was measured, so no precedence rule may be derived from it.
     */
    data class ScrapeSample(val scrapedAtSeconds: Long?, val publishedAtSeconds: Long?) {
        val isComparable: Boolean
            get() = scrapedAtSeconds != null && publishedAtSeconds != null

        companion object {
            /** Neither side carries a comparable sample time: nothing may be overridden. */
            fun unavailable(): ScrapeSample = ScrapeSample(scrapedAtSeconds = null, publishedAtSeconds = null)
        }
    }

    /**
     * Master gate for letting an accessibility scrape override a published measurement.
     *
     * It ships `false` and must stay `false` until the scrape-time log proves that the number
     * the scrape reads is the freshest measurement. The 12:47:53 device run is why: with
     * RingConn open, the scrape logged `HR=53` while the published payload carried `HR=63` in
     * the same second, and nothing yet says whether that 53 is the latest measurement, the
     * resting rate, or the day minimum. Turning this on before that is answered would publish
     * a number nobody measured.
     *
     * With this `false`, [scrapeSampleFor] returns [ScrapeSample.unavailable] and
     * [withMeasuredScrape] returns the published vitals untouched, byte for byte.
     */
    const val SCRAPE_OVERRIDE_ENABLED = false

    /**
     * The sample times the scrape override would compare, and only when it is enabled.
     *
     * Both sides must carry a trusted sample time for any precedence rule to exist. A null on
     * either side means that source cannot say when its number was measured, so the published
     * value stands. In particular the published side uses [RingConnVitals.sampleAtSeconds],
     * never `refreshTimestamp`: that field falls back to the wall clock, which is exactly how a
     * stale RingConn screen looks fresh.
     */
    fun scrapeSampleFor(published: RingConnVitals, scraped: RingConnVitals?): ScrapeSample =
        if (!SCRAPE_OVERRIDE_ENABLED) ScrapeSample.unavailable()
        else ScrapeSample(
            scrapedAtSeconds = scraped?.sampleAtSeconds,
            publishedAtSeconds = published.sampleAtSeconds
        )

    /**
     * Overlays an accessibility scrape on the vitals that are about to be published.
     *
     * Two rules, and no third one:
     *  - the scrape only contributes when its sample is strictly newer than the one already
     *    published, and only when both sample times exist. Without a comparison there is no
     *    reason to prefer it, so the published value stands;
     *  - a field the scrape returned as unknown (0) is never written, because a partial
     *    scrape that saw one card has not measured the rest. Copying its zeros would empty
     *    the watch, and treating them as measurements would print a value nobody measured.
     */
    fun withMeasuredScrape(
        published: RingConnVitals,
        scraped: RingConnVitals?,
        sample: ScrapeSample
    ): RingConnVitals {
        if (scraped == null) return published
        val scrapedAt = sample.scrapedAtSeconds ?: return published
        val publishedAt = sample.publishedAtSeconds ?: return published
        if (scrapedAt <= publishedAt) return published

        return published.copy(
            lastHeartRate = measured(scraped.lastHeartRate, published.lastHeartRate),
            lastSpo2 = measured(scraped.lastSpo2, published.lastSpo2),
            lastHrv = measured(scraped.lastHrv, published.lastHrv),
            sleepScore = measured(scraped.sleepScore, published.sleepScore),
            sleepDeepMins = measured(scraped.sleepDeepMins, published.sleepDeepMins),
            sleepRemMins = measured(scraped.sleepRemMins, published.sleepRemMins),
            sleepLightMins = measured(scraped.sleepLightMins, published.sleepLightMins),
            activityScore = measured(scraped.activityScore, published.activityScore),
            liveSteps = measured(scraped.liveSteps, published.liveSteps),
            stressScore = measured(scraped.stressScore, published.stressScore),
            skinTempOffset = if (scraped.skinTempOffset != 0.0f) scraped.skinTempOffset else published.skinTempOffset,
            refreshTimestamp = scrapedAt
        )
    }

    /** The scrape's value only when it saw one; a 0 on screen means "not rendered", not "measured 0". */
    private fun measured(scrapedValue: Int, publishedValue: Int): Int =
        if (scrapedValue > 0) scrapedValue else publishedValue
}
