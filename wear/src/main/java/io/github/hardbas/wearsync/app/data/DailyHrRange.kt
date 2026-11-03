package io.github.hardbas.wearsync.app.data

import java.time.Instant
import java.time.ZoneId

/**
 * The day's measured heart-rate range.
 *
 * Two facts are easy to confuse and both matter: whether the day has a range at
 * all ([isKnown]) and whether that range is wide enough to place a reading on a
 * track ([hasSpan]). A day with a single reading has a range and no span.
 *
 * A zero end means "not measured yet". Nothing here invents one.
 */
data class DailyHrRange(val min: Int = 0, val max: Int = 0) {

    /** Both ends known: the card can print "min - max". */
    val isKnown: Boolean
        get() = min > 0 && max > 0

    /** There is a span to draw a position on. One reading is not a span. */
    val hasSpan: Boolean
        get() = min > 0 && max > min

    /**
     * How far [current] sits between [min] and [max], clamped to the track.
     *
     * @return null when there is no span to plot or no reading to place.
     */
    fun positionOf(current: Int): Float? {
        if (!hasSpan || current <= 0) return null
        return ((current - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f)
    }

    companion object {

        val UNKNOWN = DailyHrRange()

        /**
         * One reading as a one-value range.
         *
         * A reading measured on another day says nothing about today's range, so
         * a payload that lands after midnight carrying yesterday's value leaves
         * the range untouched instead of seeding it with a stale number.
         */
        fun observed(
            reading: Int,
            measuredAtEpochSeconds: Long,
            todayEpochDay: Long,
            zoneId: ZoneId = ZoneId.systemDefault()
        ): DailyHrRange {
            if (reading <= 0 || measuredAtEpochSeconds <= 0L) return UNKNOWN
            val measuredDay = try {
                Instant.ofEpochSecond(measuredAtEpochSeconds)
                    .atZone(zoneId)
                    .toLocalDate()
                    .toEpochDay()
            } catch (_: Exception) {
                return UNKNOWN
            }
            return if (measuredDay == todayEpochDay) DailyHrRange(reading, reading) else UNKNOWN
        }

        /**
         * The union of the ranges in hand: the lowest low and the highest high.
         *
         * Empty sources are ignored, so a payload that carries no range (the
         * widget scrape never produces one) narrows nothing and fills nothing in.
         * Two real observations never disagree about a minimum: the smaller one
         * is the day's minimum, whichever source saw it.
         */
        fun widen(vararg ranges: DailyHrRange): DailyHrRange {
            val lows = ranges.map { it.min }.filter { it > 0 }
            val highs = ranges.map { it.max }.filter { it > 0 }
            return DailyHrRange(lows.minOrNull() ?: 0, highs.maxOrNull() ?: 0)
        }

        /**
         * Discards a stored minimum (and matching single-value maximum) that was seeded by
         * an HRV reading (e.g. 43 ms) before the widget parser anchored Latest/Average units,
         * when the phone's reported range does not confirm that minimum and live HR is > 10 bpm higher.
         */
        fun sanitizeStored(
            stored: DailyHrRange,
            reported: DailyHrRange,
            currentHr: Int,
            lastHrv: Int,
            avgHrv: Int
        ): DailyHrRange {
            val isMinHrvContaminated = stored.min > 0 &&
                (stored.min == lastHrv || stored.min == avgHrv) &&
                reported.min != stored.min &&
                currentHr > stored.min + 10
            if (!isMinHrvContaminated) return stored
            val cleanMax = if (stored.max == stored.min) 0 else stored.max
            return DailyHrRange(min = 0, max = cleanMax)
        }
    }
}
