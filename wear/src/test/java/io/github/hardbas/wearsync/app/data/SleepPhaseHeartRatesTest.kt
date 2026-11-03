package io.github.hardbas.wearsync.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executable contract for the stage heart rates.
 *
 * The phase card used to print `--` for every stage the ring had not measured yet,
 * which is a placeholder for a state that resolves itself as soon as the first
 * measured night arrives. A row is only worth a line when it carries a reading, so
 * an unmeasured stage is left out of the list the card draws.
 */
class SleepPhaseHeartRatesTest {

    @Test
    fun `returns the measured stages deep to rem`() {
        val readings = SleepPhaseHeartRates.measured(
            RingConnVitals(hrPhaseDeep = 58, hrPhaseLight = 64, hrPhaseRem = 69)
        )

        assertEquals(3, readings.size)
        assertEquals(SleepStage.DEEP, readings[0].phase)
        assertEquals(58, readings[0].bpm)
        assertEquals(SleepStage.LIGHT, readings[1].phase)
        assertEquals(64, readings[1].bpm)
        assertEquals(SleepStage.REM, readings[2].phase)
        assertEquals(69, readings[2].bpm)
    }

    @Test
    fun `omits a stage without a measurement instead of placeholder it`() {
        val readings = SleepPhaseHeartRates.measured(
            RingConnVitals(hrPhaseDeep = 0, hrPhaseLight = 64, hrPhaseRem = 0)
        )

        assertEquals(1, readings.size)
        assertEquals(SleepStage.LIGHT, readings[0].phase)
        assertEquals(64, readings[0].bpm)
    }

    @Test
    fun `a night with no measured stage yields no rows`() {
        assertTrue(SleepPhaseHeartRates.measured(RingConnVitals()).isEmpty())
    }
}
