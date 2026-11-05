package io.github.hardbas.wearsync.bridge.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permissions the phone actually asks Health Connect for.
 *
 * A record type can be queried all it likes, but if its read permission is not in the
 * requested set the grant screen never shows it, `getGrantedPermissions()` never returns it,
 * and the query silently answers "no data" forever. That is how `RespiratoryRateRecord` went
 * unused while the reader substituted a formula for it.
 *
 * The opposite mistake is asking for a source nobody consumes: `DistanceRecord` and
 * `BasalMetabolicRateRecord` have no measured consumer on this watch — the ring writes no
 * distance at all and its basal record carries an interval amount in a per-day field — so
 * they were withdrawn instead of leaving an unused grant on the screen.
 */
class HealthPermissionRequestTest {

    @Test
    fun theRingRespiratoryPermissionIsRequested() {
        val requested = RingConnTelemetryReader.REQUESTED_HEALTH_PERMISSIONS

        assertTrue(
            "respiratory rate must be requested, or its record is unreadable",
            "android.permission.health.READ_RESPIRATORY_RATE" in requested
        )
    }

    @Test
    fun theTwoWithdrawnSourcesAreNoLongerRequested() {
        val requested = RingConnTelemetryReader.REQUESTED_HEALTH_PERMISSIONS

        assertFalse(
            "the ring writes no distance record, so its grant must not be requested",
            "android.permission.health.READ_DISTANCE" in requested
        )
        assertFalse(
            "the ring's basal amount is not a rate, so its grant must not be requested",
            "android.permission.health.READ_BASAL_METABOLIC_RATE" in requested
        )
    }

    @Test
    fun theSkinTemperaturePermissionIsRequestedAndBodyTemperatureIsNot() {
        val requested = RingConnTelemetryReader.REQUESTED_HEALTH_PERMISSIONS

        assertTrue(
            "skin temperature must be requested, or its record is unreadable",
            "android.permission.health.READ_SKIN_TEMPERATURE" in requested
        )
        assertFalse(
            "no code consumes body temperature, so the consent screen must not ask for it",
            "android.permission.health.READ_BODY_TEMPERATURE" in requested
        )
    }

    @Test
    fun theRequestedSetIsExactlyTheKnownSet() {
        // Pinned in full so an accidental removal of any grant fails here instead of
        // silently turning the corresponding query into a permanent "no data" answer.
        val expected = setOf(
            "android.permission.health.READ_HEART_RATE",
            "android.permission.health.READ_STEPS",
            "android.permission.health.READ_OXYGEN_SATURATION",
            "android.permission.health.READ_SLEEP",
            "android.permission.health.READ_RESTING_HEART_RATE",
            "android.permission.health.READ_HEART_RATE_VARIABILITY",
            "android.permission.health.READ_TOTAL_CALORIES_BURNED",
            "android.permission.health.READ_RESPIRATORY_RATE",
            "android.permission.health.READ_SKIN_TEMPERATURE",
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        )

        assertEquals(expected, RingConnTelemetryReader.REQUESTED_HEALTH_PERMISSIONS)
    }

    @Test
    fun backgroundReadIsStillRequestedAlongsideTheCoreSet() {
        val requested = RingConnTelemetryReader.REQUESTED_HEALTH_PERMISSIONS

        assertTrue(requested.containsAll(RingConnTelemetryReader.CORE_HEALTH_PERMISSIONS))
        assertTrue(
            RingConnTelemetryReader.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in requested
        )
    }
}
