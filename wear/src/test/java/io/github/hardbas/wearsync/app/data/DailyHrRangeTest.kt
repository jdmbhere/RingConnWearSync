package io.github.hardbas.wearsync.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The day's range has two separate questions and neither one may be answered by
 * a default: whether the day has a range at all, and whether it is wide enough to
 * place a reading on a track. A fabricated position is indistinguishable from a
 * measured one once it is on the screen.
 */
class DailyHrRangeTest {

    private val zone: ZoneId = ZoneId.of("America/Bogota")
    private val today: LocalDate = LocalDate.of(2026, 11, 2)
    private val todayEpochDay = today.toEpochDay()

    // ---- what the card is allowed to draw -------------------------------------

    @Test
    fun `a day without readings has no range`() {
        assertFalse(DailyHrRange.UNKNOWN.isKnown)
        assertFalse(DailyHrRange.UNKNOWN.hasSpan)
        assertNull(DailyHrRange.UNKNOWN.positionOf(current = 72))
    }

    @Test
    fun `one end is not a range`() {
        assertFalse("a low end alone must not print \"52 - --\"", DailyHrRange(52, 0).isKnown)
        assertFalse("a high end alone must not print \"-- - 140\"", DailyHrRange(0, 140).isKnown)
        assertNull(DailyHrRange(52, 0).positionOf(current = 60))
    }

    @Test
    fun `one reading is a range with no span`() {
        val single = DailyHrRange(72, 72)
        assertTrue(single.isKnown)
        assertFalse("a single value has no track to place a marker on", single.hasSpan)
        assertNull(single.positionOf(current = 72))
    }

    @Test
    fun `an inverted pair is never plotted`() {
        val inverted = DailyHrRange(140, 52)
        assertFalse(inverted.hasSpan)
        assertNull(inverted.positionOf(current = 90))
    }

    @Test
    fun `the position follows the reading inside the range`() {
        val range = DailyHrRange(60, 100)
        assertEquals(0.5f, range.positionOf(80)!!, 0.0001f)
        assertEquals(0f, range.positionOf(60)!!, 0.0001f)
        assertEquals(1f, range.positionOf(100)!!, 0.0001f)
    }

    @Test
    fun `a reading outside the range stays on the track`() {
        val range = DailyHrRange(60, 100)
        assertEquals(0f, range.positionOf(41)!!, 0.0001f)
        assertEquals(1f, range.positionOf(187)!!, 0.0001f)
    }

    @Test
    fun `a day with no reading yet has no marker`() {
        assertNull(DailyHrRange(60, 100).positionOf(current = 0))
    }

    // ---- how the day's range grows --------------------------------------------

    @Test
    fun `widening nothing leaves the range unknown`() {
        assertEquals(DailyHrRange.UNKNOWN, DailyHrRange.widen(DailyHrRange.UNKNOWN))
        assertEquals(DailyHrRange.UNKNOWN, DailyHrRange.widen())
    }

    @Test
    fun `a payload without a range narrows nothing`() {
        val held = DailyHrRange(52, 140)
        assertEquals(held, DailyHrRange.widen(held, DailyHrRange.UNKNOWN))
    }

    @Test
    fun `the lowest low and the highest high win`() {
        val widened = DailyHrRange.widen(
            DailyHrRange(55, 120),
            DailyHrRange(48, 132),
            DailyHrRange(60, 90)
        )
        assertEquals(DailyHrRange(48, 132), widened)
    }

    @Test
    fun `a reading widens the range it lands in`() {
        val widened = DailyHrRange.widen(
            DailyHrRange(60, 100),
            DailyHrRange.observed(58, noon(today), todayEpochDay, zone)
        )
        assertEquals(DailyHrRange(58, 100), widened)
    }

    // ---- which readings belong to the day -------------------------------------

    @Test
    fun `a reading measured today belongs to today`() {
        assertEquals(
            DailyHrRange(72, 72),
            DailyHrRange.observed(72, noon(today), todayEpochDay, zone)
        )
    }

    @Test
    fun `a reading measured yesterday does not seed today`() {
        val yesterday = today.minusDays(1)
        assertEquals(
            DailyHrRange.UNKNOWN,
            DailyHrRange.observed(72, noon(yesterday), todayEpochDay, zone)
        )
    }

    @Test
    fun `a payload without a reading or without a timestamp is ignored`() {
        assertEquals(DailyHrRange.UNKNOWN, DailyHrRange.observed(0, noon(today), todayEpochDay, zone))
        assertEquals(DailyHrRange.UNKNOWN, DailyHrRange.observed(72, 0L, todayEpochDay, zone))
    }

    private fun noon(day: LocalDate): Long =
        day.atTime(12, 0).atZone(zone).toEpochSecond()
}
