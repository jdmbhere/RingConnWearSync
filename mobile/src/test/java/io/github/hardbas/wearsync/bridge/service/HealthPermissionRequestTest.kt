package io.github.hardbas.wearsync.bridge.service

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permissions the phone actually asks Health Connect for.
 *
 * A record type can be queried all it likes, but if its read permission is not in the
 * requested set the grant screen never shows it, `getGrantedPermissions()` never returns it,
 * and the query silently answers "no data" forever. That is how `RespiratoryRateRecord`,
 * `DistanceRecord` and `BasalMetabolicRateRecord` went unused while the reader substituted
 * formulas for them, so the wiring is pinned here.
 */
class HealthPermissionRequestTest {

    @Test
    fun theThreeRingRecordPermissionsAreRequested() {
        val requested = RingConnTelemetryReader.REQUESTED_HEALTH_PERMISSIONS

        assertTrue(
            "respiratory rate must be requested, or its record is unreadable",
            "android.permission.health.READ_RESPIRATORY_RATE" in requested
        )
        assertTrue(
            "distance must be requested, or its record is unreadable",
            "android.permission.health.READ_DISTANCE" in requested
        )
        assertTrue(
            "basal metabolic rate must be requested, or its record is unreadable",
            "android.permission.health.READ_BASAL_METABOLIC_RATE" in requested
        )
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
