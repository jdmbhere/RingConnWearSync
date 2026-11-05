package io.github.hardbas.wearsync.bridge.service

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The published nightly skin-temperature pair, pinned without a device.
 *
 * Health Connect holds several `SkinTemperatureRecord` rows for one night (device evidence:
 * two to three per night over three nights), each carrying its own baseline and one delta.
 * A night must publish exactly one pair, so the selection rule — greatest
 * `metadata.lastModifiedTime` among the records whose interval intersects that night, then the
 * delta with the greatest `time` inside the winner — has to be provable here instead of being
 * observed on one phone once.
 *
 * A missing delta is not zero: the baseline is published and the offset stays unknown at 0.0,
 * because inventing a deviation is exactly the fabrication this project forbids.
 */
class SkinTemperatureRecordsTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun at(text: String): Instant = Instant.parse(text)

    private fun sample(
        start: String,
        end: String,
        lastModified: String,
        baseline: Float?,
        deltas: List<Pair<String, Float>> = emptyList()
    ) = SkinTemperatureSample(
        start = at(start),
        end = at(end),
        lastModified = at(lastModified),
        baselineCelsius = baseline,
        deltas = deltas.map { (time, value) -> SkinTemperatureDelta(at(time), value) }
    )

    /** The night that ended on 2026-11-05, i.e. 2026-11-04 18:00Z .. 2026-11-05 12:00Z. */
    private val nightEndingNov5 = LocalDate.of(2026, 11, 5)

    private val nov5Record = sample(
        start = "2026-11-05T05:23:09Z",
        end = "2026-11-05T12:50:44Z",
        lastModified = "2026-11-05T05:31:00Z",
        baseline = 35.92f,
        deltas = listOf("2026-11-05T11:58:00Z" to -0.20f)
    )

    @Test
    fun `the night window runs from the previous evening to the following noon`() {
        val (start, end) = SkinTemperatureRecords.nightWindow(nightEndingNov5, zone)

        assertEquals(at("2026-11-04T18:00:00Z"), start)
        assertEquals(at("2026-11-05T12:00:00Z"), end)
    }

    @Test
    fun `a record that reaches across midnight belongs to the night it ends on`() {
        assertEquals(nightEndingNov5, SkinTemperatureRecords.nightOf(nov5Record, zone))
    }

    @Test
    fun `an afternoon record belongs to no night at all`() {
        val afternoon = sample(
            start = "2026-11-05T13:00:00Z",
            end = "2026-11-05T15:00:00Z",
            lastModified = "2026-11-05T15:00:00Z",
            baseline = 36.10f
        )

        assertNull(SkinTemperatureRecords.nightOf(afternoon, zone))
        assertNull(SkinTemperatureRecords.latestNightly(listOf(afternoon), zone))
    }

    @Test
    fun `the record with the greatest last modified time wins for the night`() {
        val older = sample(
            start = "2026-11-05T05:23:00Z",
            end = "2026-11-05T12:50:00Z",
            lastModified = "2026-11-05T05:31:00Z",
            baseline = 35.91f,
            deltas = listOf("2026-11-05T11:58:00Z" to -0.16f)
        )
        val newer = sample(
            start = "2026-11-05T05:23:09Z",
            end = "2026-11-05T12:50:44Z",
            lastModified = "2026-11-05T07:10:00Z",
            baseline = 35.92f,
            deltas = listOf("2026-11-05T11:58:00Z" to -0.20f)
        )

        val winner = SkinTemperatureRecords.winningRecord(listOf(older, newer), nightEndingNov5, zone)

        assertEquals(35.92f, winner!!.baselineCelsius!!, 0.001f)
        assertEquals(35.92f, SkinTemperatureRecords.latestNightly(listOf(older, newer), zone)!!.baselineCelsius, 0.001f)
    }

    @Test
    fun `a record from a different night is never chosen`() {
        val previousNight = sample(
            start = "2026-11-03T23:10:00Z",
            end = "2026-11-04T06:40:00Z",
            // Newer than the Nov 5 record on purpose: freshness alone must never let a
            // record jump into a night it does not intersect.
            lastModified = "2026-11-06T09:00:00Z",
            baseline = 35.91f,
            deltas = listOf("2026-11-04T06:30:00Z" to -0.16f)
        )

        assertNull(SkinTemperatureRecords.winningRecord(listOf(previousNight), nightEndingNov5, zone))
        assertEquals(
            LocalDate.of(2026, 11, 4),
            SkinTemperatureRecords.latestNightly(listOf(previousNight), zone)!!.nightEndingOn
        )

        val winner = SkinTemperatureRecords.winningRecord(
            listOf(previousNight, nov5Record),
            nightEndingNov5,
            zone
        )
        assertEquals(35.92f, winner!!.baselineCelsius!!, 0.001f)
    }

    @Test
    fun `the chosen delta is the one with the greatest time inside the winner`() {
        val record = sample(
            start = "2026-11-05T05:23:09Z",
            end = "2026-11-05T12:50:44Z",
            lastModified = "2026-11-05T05:31:00Z",
            baseline = 35.92f,
            deltas = listOf(
                "2026-11-05T06:35:00Z" to -0.16f,
                "2026-11-05T11:58:00Z" to -0.20f,
                "2026-11-05T05:35:00Z" to 0.14f
            )
        )

        assertEquals(-0.20f, SkinTemperatureRecords.chosenDelta(record)!!.deltaCelsius, 0.001f)
        assertEquals(-0.20f, SkinTemperatureRecords.latestNightly(listOf(record), zone)!!.offsetCelsius, 0.001f)
    }

    @Test
    fun `a record without a delta publishes the baseline and an unknown offset`() {
        val record = sample(
            start = "2026-11-05T05:23:09Z",
            end = "2026-11-05T12:50:44Z",
            lastModified = "2026-11-05T05:31:00Z",
            baseline = 35.92f,
            deltas = emptyList()
        )

        val nightly = SkinTemperatureRecords.latestNightly(listOf(record), zone)!!

        assertEquals(35.92f, nightly.baselineCelsius, 0.001f)
        assertEquals(0.0f, nightly.offsetCelsius, 0.001f)
        assertFalse("a missing delta must be reported as absent, not as a zero reading", nightly.deltaPresent)
    }

    @Test
    fun `a record without a baseline stays unknown`() {
        // `baseline` is nullable in the Health Connect API: no baseline means no publishable
        // value, and a delta alone must not become a fabricated baseline.
        val record = sample(
            start = "2026-11-05T05:23:09Z",
            end = "2026-11-05T12:50:44Z",
            lastModified = "2026-11-05T05:31:00Z",
            baseline = null,
            deltas = listOf("2026-11-05T11:58:00Z" to -0.20f)
        )

        assertNull(SkinTemperatureRecords.latestNightly(listOf(record), zone))
        assertNull(SkinTemperatureRecords.nightly(record, nightEndingNov5))
    }

    @Test
    fun `empty input yields no nightly pair and does not throw`() {
        assertNull(SkinTemperatureRecords.winningRecord(emptyList(), nightEndingNov5, zone))
        assertNull(SkinTemperatureRecords.latestNightly(emptyList(), zone))
    }

    @Test
    fun `a last modified tie resolves to one deterministic record`() {
        val a = sample(
            start = "2026-11-05T05:23:00Z",
            end = "2026-11-05T12:50:00Z",
            lastModified = "2026-11-05T07:10:00Z",
            baseline = 35.91f,
            deltas = listOf("2026-11-05T11:58:00Z" to -0.16f)
        )
        val b = sample(
            start = "2026-11-05T05:23:09Z",
            end = "2026-11-05T12:50:44Z",
            lastModified = "2026-11-05T07:10:00Z",
            baseline = 35.92f,
            deltas = listOf("2026-11-05T11:58:00Z" to -0.20f)
        )

        val first = SkinTemperatureRecords.winningRecord(listOf(a, b), nightEndingNov5, zone)
        val second = SkinTemperatureRecords.winningRecord(listOf(a, b), nightEndingNov5, zone)

        assertNotNull(first)
        assertEquals(first, second)
        assertNotEquals("two records must not be published as if they were one", null, first)
    }

    @Test
    fun `the most recent night wins over older nights`() {
        val nov3 = sample(
            start = "2026-11-02T22:40:00Z",
            end = "2026-11-03T06:10:00Z",
            lastModified = "2026-11-03T06:20:00Z",
            baseline = 35.91f,
            deltas = listOf("2026-11-03T06:00:00Z" to -0.16f)
        )
        val nov4 = sample(
            start = "2026-11-03T22:10:00Z",
            end = "2026-11-04T06:30:00Z",
            lastModified = "2026-11-04T06:40:00Z",
            baseline = 35.91f,
            deltas = listOf("2026-11-04T06:20:00Z" to 0.21f)
        )

        val nightly = SkinTemperatureRecords.latestNightly(listOf(nov3, nov4, nov5Record), zone)!!

        assertEquals(nightEndingNov5, nightly.nightEndingOn)
        assertEquals(35.92f, nightly.baselineCelsius, 0.001f)
        assertEquals(-0.20f, nightly.offsetCelsius, 0.001f)
        assertTrue(nightly.deltaPresent)
    }

    @Test
    fun `a single record is published unchanged for its own night`() {
        val nightly = SkinTemperatureRecords.latestNightly(listOf(nov5Record), zone)!!

        assertEquals(nightEndingNov5, nightly.nightEndingOn)
        assertEquals(35.92f, nightly.baselineCelsius, 0.001f)
        assertEquals(-0.20f, nightly.offsetCelsius, 0.001f)
        assertTrue(nightly.deltaPresent)
    }

    @Test
    fun `a record whose end precedes its start does not throw`() {
        val broken = sample(
            start = "2026-11-05T05:23:09Z",
            end = "2026-11-05T04:50:44Z",
            lastModified = "2026-11-05T05:31:00Z",
            baseline = 35.92f
        )

        assertNotNull(SkinTemperatureRecords.nightOf(broken, zone))
    }
}
