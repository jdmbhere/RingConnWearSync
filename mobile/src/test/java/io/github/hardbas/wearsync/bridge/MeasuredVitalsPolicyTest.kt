package io.github.hardbas.wearsync.bridge

import io.github.hardbas.wearsync.bridge.service.MeasuredVitalsPolicy
import io.github.hardbas.wearsync.bridge.service.RingConnTelemetryReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A vital may only carry a value that descends from a measurement.
 *
 * The reader used to fill the fields below with arithmetic whenever a measurement was
 * missing: a nightly anchor plus a fixed offset for the phase heart rates, the stress
 * score times a circadian multiplier for the stress blocks, the sleep score plus five
 * for the efficiency, free points for absent sleep stages and a 45 floor under the
 * computed score. On the 2026-11-03 device run those formulas produced
 * `hrPhaseDeep=66, hrPhaseLight=69, hrPhaseRem=71, sleepEfficiencyPct=80,
 * stressDawn=32, stressMorning=44, stressAfternoon=51` out of `avgHeartRate=73` and
 * `stressScore=47` alone — numbers that look measured and are not.
 *
 * The rule these tests pin: the policy is a gate, not a formula. It returns what the
 * measurement says and 0 otherwise, and it can never manufacture the values above.
 */
class MeasuredVitalsPolicyTest {

    // --- S4: hrPhaseDeep / hrPhaseLight / hrPhaseRem -------------------------------------

    @Test
    fun aPhaseHeartRateIsOnlyEverTheMeasuredStageBoundedValue() {
        for (bpm in 40..100) {
            assertEquals(bpm, MeasuredVitalsPolicy.hrPhase(bpm))
        }
    }

    @Test
    fun absentStageSamplesLeaveThePhaseHeartRateUnknown() {
        // The fabricated triple was 65+1, 65+4 and 65+6 from avgHeartRate 73. With no
        // samples inside the stage there is no value, not a plausible one.
        assertEquals(0, MeasuredVitalsPolicy.hrPhase(0))
        assertEquals(0, MeasuredVitalsPolicy.hrPhase(-1))
    }

    // --- S4: stressDawn / stressMorning / stressAfternoon / stressNight ------------------

    @Test
    fun aBlockStressIsOnlyEverTheMeasuredValue() {
        for (stress in 0..100) {
            assertEquals(stress, MeasuredVitalsPolicy.blockStress(stress))
        }
    }

    @Test
    fun aBlockWithNoHrSamplesHasNoStress() {
        // 47 x 0.68 / 0.94 / 1.08 / 0.86 gave 32 / 44 / 51 / 40. No samples, no value.
        assertEquals(0, MeasuredVitalsPolicy.blockStress(0))
    }

    // --- S4 / S8: sleepEfficiencyPct -----------------------------------------------------

    @Test
    fun sleepEfficiencyIsTheMeasuredAsleepOverInBedRatio() {
        assertEquals(80, MeasuredVitalsPolicy.sleepEfficiencyPct(asleepMinutes = 360, inBedMinutes = 450))
        assertEquals(50, MeasuredVitalsPolicy.sleepEfficiencyPct(asleepMinutes = 240, inBedMinutes = 480))
    }

    @Test
    fun aPoorNightIsNotLiftedToAPlausibleEfficiency() {
        // The old ratio was clamped to 50..99, so a real 5 % night was reported as 50 %.
        assertEquals(5, MeasuredVitalsPolicy.sleepEfficiencyPct(asleepMinutes = 5, inBedMinutes = 100))
    }

    @Test
    fun withoutStagesThereIsNoSleepEfficiency() {
        // A sleep score of 75 produced 80 through `score + 5`.
        assertEquals(0, MeasuredVitalsPolicy.sleepEfficiencyPct(asleepMinutes = 0, inBedMinutes = 0))
        assertEquals(0, MeasuredVitalsPolicy.sleepEfficiencyPct(asleepMinutes = 0, inBedMinutes = 480))
    }

    // --- S4 / S8: inBedMinutes, the efficiency denominator -------------------------------

    @Test
    fun inBedSpansBedTimeToFinalAwakeningSoItIncludesSleepLatency() {
        // A real night the ring itself scored as 92 %: asleep 412 (deep 107 + rem 60 +
        // light 245), awake 20, bed time 00:23 to the final awakening 07:50 -> 447 minutes
        // in bed. RingConn shows 18 minutes awake and still divides by the full span, so
        // the latency to fall asleep stays in the denominator.
        val inBed = MeasuredVitalsPolicy.inBedMinutes(
            nocturnalSpanMinutes = 447L,
            asleepMinutes = 412,
            awakeMinutes = 20
        )
        assertEquals(447, inBed)
        assertEquals(92, MeasuredVitalsPolicy.sleepEfficiencyPct(asleepMinutes = 412, inBedMinutes = inBed))

        // Dropping the latency, asleep + awake = 432, inflated that same night to 95 %.
        assertEquals(95, MeasuredVitalsPolicy.sleepEfficiencyPct(asleepMinutes = 412, inBedMinutes = 432))
    }

    @Test
    fun inBedFallsBackToTheStageSumWhenTheSpanCannotContainTheSleep() {
        // No span at all, and a span shorter than the sleep it measured, both fall back
        // instead of publishing an efficiency above 100 %.
        assertEquals(
            432,
            MeasuredVitalsPolicy.inBedMinutes(nocturnalSpanMinutes = 0L, asleepMinutes = 412, awakeMinutes = 20)
        )
        assertEquals(
            432,
            MeasuredVitalsPolicy.inBedMinutes(nocturnalSpanMinutes = 400L, asleepMinutes = 412, awakeMinutes = 20)
        )
    }

    // --- S5: computed sleep score --------------------------------------------------------

    @Test
    fun aNightWithoutStagesHasNoComputedScore() {
        assertEquals(0, MeasuredVitalsPolicy.computedSleepScore(0L, 0L, 0L, 45L))
        assertEquals(0, MeasuredVitalsPolicy.computedSleepScore(0L, 0L, 0L, 0L))
    }

    @Test
    fun aShortNightIsNotFlooredToAPlausibleScore() {
        // 5 deep + 10 light minutes: duration 1.8 + deep 1.5 points. The old floor said 45.
        assertEquals(3, MeasuredVitalsPolicy.computedSleepScore(5L, 0L, 10L, 0L))
    }

    @Test
    fun anAbsentStageContributesNoPoints() {
        // 55 deep / 60 rem / 249 light / 40 awake: 44.5 + 16.9 + 15.5 - 3.3 = 73.5.
        assertEquals(74, MeasuredVitalsPolicy.computedSleepScore(55L, 60L, 249L, 40L))

        // Without deep minutes the 16.9 points are gone instead of becoming the free 18
        // the old code granted for the absent stage: 37.8 + 15.5 = 53.3.
        val withoutDeep = MeasuredVitalsPolicy.computedSleepScore(0L, 60L, 249L, 0L)
        assertEquals(53, withoutDeep)
        assertTrue(
            "an absent deep stage must contribute nothing, not a flat 18 points",
            withoutDeep < MeasuredVitalsPolicy.computedSleepScore(55L, 60L, 249L, 0L)
        )
    }

    @Test
    fun aNightWithOnlyLightStagesScoresFromDurationAlone() {
        // Light stages are a real record, so the night has a measured duration and no
        // deep or rem contribution: 300 / 450 x 55 = 36.7.
        assertEquals(37, MeasuredVitalsPolicy.computedSleepScore(0L, 0L, 300L, 0L))
    }

    @Test
    fun onlyTheRingSampleSurvivesEvenWhenAnotherWriterAlsoRecordedTheType() {
        // Respiratory rate is a type a competing health app also writes, so this is where a
        // fallback would print a stranger's number on the ring's card without anyone noticing.
        val records = listOf("com.gdjztech.ringconn" to 16.2, "android" to 12.8)

        val ringOnly = MeasuredVitalsPolicy.ringSamples(records) { it.first == RINGCONN }

        assertEquals(listOf(RINGCONN to 16.2), ringOnly)
    }

    @Test
    fun aTypeThatOnlyAnotherAppWroteStaysUnknown() {
        val records = listOf("android" to 9.8, "com.sec.android.app.shealth" to 7.1)

        val ringOnly = MeasuredVitalsPolicy.ringSamples(records) { it.first == RINGCONN }

        assertTrue(
            "no ring sample must mean no value, never another writer's number",
            ringOnly.isEmpty()
        )
    }

    @Test
    fun noRecordsAtAllStaysUnknown() {
        assertTrue(MeasuredVitalsPolicy.ringSamples(emptyList<String>()) { it == RINGCONN }.isEmpty())
    }

    private companion object {
        // Taken from production so a rename or a change of the ring's package breaks this test
        // instead of letting it keep passing against a copy of the string.
        val RINGCONN = RingConnTelemetryReader.RINGCONN_PKG
    }
}
