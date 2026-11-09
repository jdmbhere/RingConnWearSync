package io.github.hardbas.wearsync.bridge.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Health Connect is a shared store, so every read has to name the app that wrote it.
 *
 * Each type the reader queries can also hold rows from another writer: Google Fit's steps,
 * a Polar strap's HRV, Samsung Health's pulse. The reader took the ring's records and, when
 * there were none, quietly published whoever else had written the type — with nothing on the
 * watch saying whose number it was. A metric the ring did not write is unknown, and unknown is
 * the path the reader already takes when Health Connect returns nothing at all.
 *
 * One pair of tests per read that used to substitute: another app's record must never be
 * selected, and the ring's own record must be selected exactly as before.
 */
class HealthConnectOriginTest {

    @Test
    fun restingHeartRateFromAnotherAppIsNeverPublished() {
        // Site: resting heart rate in readFromHealthConnect.
        val selected = ringOnly(listOf(HcRecord(GOOGLE_FIT, 61.0), HcRecord(POLAR, 58.0)))

        assertTrue(
            "no RingConn resting heart rate must stay unknown, not become another app's reading",
            selected.isEmpty()
        )
    }

    @Test
    fun ringConnRestingHeartRateIsPublishedUnchanged() {
        val selected = ringOnly(listOf(HcRecord(RINGCONN, 58.0), HcRecord(GOOGLE_FIT, 61.0)))

        assertEquals(listOf(58.0), selected.map { it.value })
    }

    @Test
    fun spo2FromAnotherAppIsNeverPublished() {
        // Sites: last/average SpO2 in readFromHealthConnect, and the nocturnal SpO2 and
        // apnea index that are taken from the same records.
        val selected = ringOnly(listOf(HcRecord(POLAR, 97.0), HcRecord(GOOGLE_FIT, 95.0)))

        assertTrue(
            "no RingConn oxygen saturation must stay unknown, not become another app's reading",
            selected.isEmpty()
        )
    }

    @Test
    fun ringConnSpo2IsPublishedUnchanged() {
        val selected = ringOnly(listOf(HcRecord(RINGCONN, 96.0), HcRecord(POLAR, 97.0)))

        assertEquals(listOf(96.0), selected.map { it.value })
    }

    @Test
    fun hrvFromAnotherAppIsNeverPublished() {
        // Site: last/average HRV in readFromHealthConnect.
        val selected = ringOnly(listOf(HcRecord(POLAR, 42.0)))

        assertTrue(
            "no RingConn heart rate variability must stay unknown, not become another app's reading",
            selected.isEmpty()
        )
    }

    @Test
    fun ringConnHrvIsPublishedUnchanged() {
        val selected = ringOnly(listOf(HcRecord(RINGCONN, 38.0), HcRecord(POLAR, 42.0)))

        assertEquals(listOf(38.0), selected.map { it.value })
    }

    @Test
    fun stepsFromAnotherAppAreNeverCounted() {
        // Sites: today's steps and the stand hours derived from the same records.
        val selected = ringOnly(listOf(HcRecord(GOOGLE_FIT, 1200.0), HcRecord(POLAR, 340.0)))

        assertTrue(
            "no RingConn step record must stay unknown, not become another app's step count",
            selected.isEmpty()
        )
    }

    @Test
    fun ringConnStepsAreCountedUnchanged() {
        val selected = ringOnly(listOf(HcRecord(RINGCONN, 900.0), HcRecord(GOOGLE_FIT, 1200.0)))

        assertEquals(listOf(900.0), selected.map { it.value })
    }

    @Test
    fun sleepSessionsFromAnotherAppAreNeverRead() {
        // Sites: the nocturnal night in readFromHealthConnect, and the sessions behind the
        // nap minutes and the bedtime window.
        val selected = ringOnly(listOf(HcRecord(GOOGLE_FIT, 480.0), HcRecord(POLAR, 430.0)))

        assertTrue(
            "no RingConn sleep record must stay unknown, not become another app's night",
            selected.isEmpty()
        )
    }

    @Test
    fun ringConnSleepSessionsAreReadUnchanged() {
        val selected = ringOnly(listOf(HcRecord(RINGCONN, 452.0), HcRecord(GOOGLE_FIT, 480.0)))

        assertEquals(listOf(452.0), selected.map { it.value })
    }

    @Test
    fun sleepHeartRateFromAnotherAppIsNeverRead() {
        // Site: the second heart-rate path, which measures the nocturnal dip and the mean
        // heart rate per sleep stage. Watch PPG from another app is not this ring's pulse.
        val selected = ringOnly(listOf(HcRecord(GOOGLE_FIT, 71.0), HcRecord(POLAR, 69.0)))

        assertTrue(
            "no RingConn overnight heart rate must stay unknown, not become another app's pulse",
            selected.isEmpty()
        )
    }

    @Test
    fun ringConnSleepHeartRateIsReadUnchanged() {
        val selected = ringOnly(listOf(HcRecord(RINGCONN, 54.0), HcRecord(GOOGLE_FIT, 71.0)))

        assertEquals(listOf(54.0), selected.map { it.value })
    }

    @Test
    fun caloriesFromAnotherAppAreNeverCounted() {
        // Site: total calories burned today in readFromHealthConnect.
        val selected = ringOnly(listOf(HcRecord(GOOGLE_FIT, 2100.0)))

        assertTrue(
            "no RingConn calorie record must stay unknown, not become another app's total",
            selected.isEmpty()
        )
    }

    @Test
    fun ringConnCaloriesAreCountedUnchanged() {
        val selected = ringOnly(listOf(HcRecord(RINGCONN, 320.0), HcRecord(GOOGLE_FIT, 2100.0)))

        assertEquals(listOf(320.0), selected.map { it.value })
    }

    /** One Health Connect row reduced to the two fields this rule reads. */
    private data class HcRecord(val packageName: String, val value: Double)

    private fun ringOnly(records: List<HcRecord>): List<HcRecord> =
        HealthConnectOrigin.ringConnRecords(records) { it.packageName == RINGCONN }

    private companion object {
        // Taken from production so a rename or a change of the ring's package breaks these
        // tests instead of letting them keep passing against a copy of the string.
        val RINGCONN = RingConnTelemetryReader.RINGCONN_PKG

        const val GOOGLE_FIT = "com.google.android.apps.fitness"
        const val POLAR = "fi.polar.polarflow"
    }
}
