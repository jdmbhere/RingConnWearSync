package io.github.hardbas.wearsync.bridge.service

import android.content.SharedPreferences
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What may be restored from the continuity cache.
 *
 * The cache is what the watch reads between Health Connect reads, so a value written there
 * is presented as a reading until something replaces it. Schema 1 wrote arithmetic results
 * into the same keys as measurements — `hrPhaseDeep=66` from `avgHeartRate 73`, `stressDawn
 * =32` from `stressScore 47`, `sleepEfficiencyPct=80` from a sleep score — and those
 * values outranked the honest `0` on every later read. A stored number that cannot be
 * proven to be a measurement is therefore dropped once instead of being restored.
 */
class LegacyVitalsCacheTest {

    // The reader rolls the cache over when the stored day is not today, which would zero the
    // daily fields for a reason unrelated to this rule.
    private val todayEpochDay: Long = ZonedDateTime.now().toLocalDate().toEpochDay()

    private fun legacyCache(): FakePreferences = FakePreferences(
        mapOf(
            "keyCacheEpochDay" to todayEpochDay,
            "hrPhaseDeep" to 66,
            "hrPhaseRem" to 71,
            "hrPhaseLight" to 69,
            "sleepEfficiencyPct" to 80,
            "stressDawn" to 32,
            "stressMorning" to 44,
            "stressAfternoon" to 51,
            "stressNight" to 40,
            "basalCalories" to 970,
            "distanceKm" to 1.21f,
            "respiratoryRate" to 15.1f,
            // A widget value, measured, and unaffected by the migration.
            "sleepScore" to 78
        )
    )

    @Test
    fun aValueWrittenBeforeTheVerifiedOnlyRuleIsNotRestoredAsMeasuredData() {
        val vitals = RingConnTelemetryReader.readFromPreferences(legacyCache())

        assertEquals(0, vitals.hrPhaseDeep)
        assertEquals(0, vitals.hrPhaseRem)
        assertEquals(0, vitals.hrPhaseLight)
        assertEquals(0, vitals.sleepEfficiencyPct)
        assertEquals(0, vitals.stressDawn)
        assertEquals(0, vitals.stressMorning)
        assertEquals(0, vitals.stressAfternoon)
        assertEquals(0, vitals.stressNight)
        assertEquals(0, vitals.basalCalories)
        assertEquals(0.0f, vitals.distanceKm, 0.01f)
        assertEquals(0.0f, vitals.respiratoryRate, 0.01f)

        // The widget-owned score is a real measurement and keeps its value.
        assertEquals(78, vitals.sleepScore)
    }

    @Test
    fun aMeasuredValueWrittenUnderTheCurrentSchemaIsRestored() {
        val prefs = FakePreferences(
            mapOf(
                "keyCacheEpochDay" to todayEpochDay,
                "keyVitalsSchema" to 2,
                "hrPhaseDeep" to 54,
                "sleepEfficiencyPct" to 91,
                "respiratoryRate" to 15.1f
            )
        )

        val vitals = RingConnTelemetryReader.readFromPreferences(prefs)

        assertEquals(54, vitals.hrPhaseDeep)
        assertEquals(91, vitals.sleepEfficiencyPct)
        assertEquals(15.1f, vitals.respiratoryRate, 0.01f)
    }

    @Test
    fun theMigrationRunsOnce() {
        val prefs = legacyCache()
        assertTrue(RingConnTelemetryReader.dropLegacyInventedVitals(prefs))
        assertEquals(
            "a second read must not keep clearing the cache",
            false,
            RingConnTelemetryReader.dropLegacyInventedVitals(prefs)
        )
    }
}

/**
 * An in-memory [SharedPreferences], so the cache rules can be exercised as plain JVM code
 * instead of through a device.
 */
private class FakePreferences(initial: Map<String, Any>) : SharedPreferences {

    private val values: MutableMap<String, Any?> = initial.toMutableMap()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private class Editor(private val values: MutableMap<String, Any?>) : SharedPreferences.Editor {

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            values[key] = value
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?
        ): SharedPreferences.Editor = apply { this@Editor.values[key] = values }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
            values[key] = value
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
            values[key] = value
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
            values[key] = value
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
            values[key] = value
        }

        override fun remove(key: String): SharedPreferences.Editor = apply {
            values.remove(key)
        }

        override fun clear(): SharedPreferences.Editor = apply { values.clear() }

        override fun commit(): Boolean = true

        override fun apply() = Unit
    }
}
