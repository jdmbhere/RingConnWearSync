package io.github.hardbas.wearsync.bridge.service

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One `SkinTemperatureRecord` reduced to the fields the nightly publication needs.
 *
 * The Health Connect record type is an Android dependency, so the record is mapped into this
 * plain value at the read boundary and every selection rule below stays runnable on the JVM.
 *
 * @param baselineCelsius the record's baseline, or null when the provider wrote none. A null
 *   baseline is not a missing delta: without it there is no value to publish at all.
 * @param deltas the record's deltas, in whatever order the provider wrote them.
 */
data class SkinTemperatureSample(
    val start: Instant,
    val end: Instant,
    val lastModified: Instant,
    val baselineCelsius: Float?,
    val deltas: List<SkinTemperatureDelta> = emptyList()
)

/** One deviation inside a record, read from `SkinTemperatureRecord.Delta`. */
data class SkinTemperatureDelta(
    val time: Instant,
    val deltaCelsius: Float
)

/**
 * The single pair a night publishes.
 *
 * @param nightEndingOn the local date the night's morning falls on, which is how a night is
 *   named across midnight.
 * @param offsetCelsius the winning record's last delta, or 0.0 when the record carried none.
 *   A missing delta is unknown, never zero: consumers must read [deltaPresent] before treating
 *   0.0 as a measured value.
 */
data class NightlySkinTemperature(
    val nightEndingOn: LocalDate,
    val baselineCelsius: Float,
    val offsetCelsius: Float,
    val deltaPresent: Boolean
)

/**
 * The selection policy for published skin temperature.
 *
 * The ring writes two or three `SkinTemperatureRecord` rows per night, each restarting its own
 * delta series, so "the newest row" is not the answer and "the row with the biggest delta" is a
 * fabrication risk. Two rules decide instead:
 *
 * 1. A night is a local overnight window, not a calendar date: it starts at 18:00 the previous
 *    evening and ends at noon. A record that crosses midnight therefore belongs to one night.
 * 2. Within a night, the record with the greatest `metadata.lastModifiedTime` wins — it is the
 *    provider's own statement about which row supersedes the others, and it is idempotent, so
 *    reading the same records twice publishes the same pair.
 *
 * The published delta is the winner's delta with the greatest `time`. A winner with no delta
 * publishes its baseline and leaves the offset at 0.0 with [NightlySkinTemperature.deltaPresent]
 * false, because an absent deviation must stay unknown.
 */
object SkinTemperatureRecords {

    /** Local hour at which a night begins on the previous calendar day. */
    const val NIGHT_START_HOUR = 18

    /** Local hour at which a night ends; also the boundary that names the night. */
    const val NIGHT_END_HOUR = 12

    /**
     * The half-open interval `[previous day 18:00, night day 12:00)` in [zone].
     *
     * Returned as a pair so callers do not depend on a range type's inclusivity rules.
     */
    fun nightWindow(nightEndingOn: LocalDate, zone: ZoneId): Pair<Instant, Instant> {
        val start = nightEndingOn.minusDays(1).atTime(NIGHT_START_HOUR, 0).atZone(zone).toInstant()
        val end = nightEndingOn.atTime(NIGHT_END_HOUR, 0).atZone(zone).toInstant()
        return start to end
    }

    /**
     * The night [instant] falls into, or null when it falls into no night at all.
     *
     * An instant between 18:00 and midnight already belongs to the next morning's night.
     */
    fun nightOfInstant(instant: Instant, zone: ZoneId): LocalDate? {
        val local = instant.atZone(zone)
        return when (local.hour) {
            in NIGHT_START_HOUR..23 -> local.toLocalDate().plusDays(1)
            in 0 until NIGHT_END_HOUR -> local.toLocalDate()
            else -> null
        }
    }

    /**
     * The night [sample] belongs to, decided from its midpoint.
     *
     * The midpoint is used because the record's own edges are an interval the provider chose;
     * the middle of that interval is where the measurement actually sits. A zero or inverted
     * interval falls back to the start time so malformed provider data cannot throw.
     */
    fun nightOf(sample: SkinTemperatureSample, zone: ZoneId): LocalDate? {
        val span = Duration.between(sample.start, sample.end)
        val midpoint = if (span.isNegative || span.isZero) {
            sample.start
        } else {
            sample.start.plusMillis(span.toMillis() / 2)
        }
        return nightOfInstant(midpoint, zone)
    }

    /** True when [sample]'s interval overlaps the night that ends on [nightEndingOn]. */
    fun intersects(sample: SkinTemperatureSample, nightEndingOn: LocalDate, zone: ZoneId): Boolean {
        val (windowStart, windowEnd) = nightWindow(nightEndingOn, zone)
        return sample.start.isBefore(windowEnd) && sample.end.isAfter(windowStart)
    }

    /**
     * The record that publishes [nightEndingOn]: greatest `lastModified`, then the later start
     * and the later end, so a tie is resolved the same way on every read.
     */
    fun winningRecord(
        samples: List<SkinTemperatureSample>,
        nightEndingOn: LocalDate,
        zone: ZoneId
    ): SkinTemperatureSample? = samples
        .filter { intersects(it, nightEndingOn, zone) }
        .maxWithOrNull(
            compareBy<SkinTemperatureSample> { it.lastModified }
                .thenBy { it.start }
                .thenBy { it.end }
        )

    /** The winner's delta with the greatest time, or null when it carries none. */
    fun chosenDelta(sample: SkinTemperatureSample): SkinTemperatureDelta? =
        sample.deltas.maxWithOrNull(compareBy<SkinTemperatureDelta> { it.time })

    /**
     * The published pair of [sample] for [nightEndingOn], or null when the record has no
     * baseline — an absent baseline leaves the whole night unknown instead of inventing one.
     */
    fun nightly(sample: SkinTemperatureSample, nightEndingOn: LocalDate): NightlySkinTemperature? {
        val baseline = sample.baselineCelsius ?: return null
        if (!baseline.isFinite()) return null
        val delta = chosenDelta(sample)
        return NightlySkinTemperature(
            nightEndingOn = nightEndingOn,
            baselineCelsius = baseline,
            offsetCelsius = delta?.deltaCelsius ?: 0.0f,
            deltaPresent = delta != null
        )
    }

    /**
     * The pair the most recent night publishes, or null when no readable record has a baseline.
     *
     * "Most recent" is derived from the records themselves, never from the clock, so a stale
     * provider does not make an older night look current.
     */
    fun latestNightly(samples: List<SkinTemperatureSample>, zone: ZoneId): NightlySkinTemperature? {
        val latestNight = samples.mapNotNull { nightOf(it, zone) }.maxOrNull() ?: return null
        val winner = winningRecord(samples, latestNight, zone) ?: return null
        return nightly(winner, latestNight)
    }
}
