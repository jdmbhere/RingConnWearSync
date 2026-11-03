package io.github.hardbas.wearsync.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executable contract for the circadian stress series.
 *
 * RingConn records four stress blocks per day — before dawn, morning, afternoon
 * and night — and nothing finer. The detail screen used to draw twenty-four bars
 * out of an hour formula and three fixed percentages, which meant every user saw
 * the same invented curve. This contract pins the only honest alternatives: the
 * four measured blocks, or no series at all.
 */
class StressPhasesTest {

    private fun vitals(dawn: Int, morning: Int, afternoon: Int, night: Int) =
        RingConnVitals(
            stressDawn = dawn,
            stressMorning = morning,
            stressAfternoon = afternoon,
            stressNight = night
        )

    @Test
    fun `returns the four measured blocks in day order`() {
        val readings = StressPhases.readings(vitals(18, 32, 47, 21))

        assertEquals(4, readings.size)
        assertEquals(StressPhase.DAWN, readings[0].phase)
        assertEquals(18, readings[0].score)
        assertEquals(StressPhase.MORNING, readings[1].phase)
        assertEquals(32, readings[1].score)
        assertEquals(StressPhase.AFTERNOON, readings[2].phase)
        assertEquals(47, readings[2].score)
        assertEquals(StressPhase.NIGHT, readings[3].phase)
        assertEquals(21, readings[3].score)
    }

    @Test
    fun `never interpolates a block the ring did not record`() {
        val readings = StressPhases.readings(vitals(0, 40, 0, 25))

        assertEquals(4, readings.size)
        assertEquals(0, readings[0].score)
        assertEquals(40, readings[1].score)
        assertEquals(0, readings[2].score)
        assertEquals(25, readings[3].score)
    }

    @Test
    fun `no measured block means no series, so the screen can show its empty state`() {
        assertTrue(StressPhases.readings(vitals(0, 0, 0, 0)).isEmpty())
    }

    @Test
    fun `measured drops the blocks the ring did not record`() {
        val measured = StressPhases.measured(vitals(0, 40, 0, 25))

        assertEquals(2, measured.size)
        assertEquals(StressPhase.MORNING, measured[0].phase)
        assertEquals(40, measured[0].score)
        assertEquals(StressPhase.NIGHT, measured[1].phase)
        assertEquals(25, measured[1].score)
    }

    @Test
    fun `measured is empty when the day has no measured block`() {
        assertTrue(StressPhases.measured(vitals(0, 0, 0, 0)).isEmpty())
    }

    @Test
    fun `peak is the highest measured block and null when nothing was measured`() {
        assertEquals(47, StressPhases.peak(vitals(18, 32, 47, 21)))
        assertNull(StressPhases.peak(vitals(0, 0, 0, 0)))
    }
}
