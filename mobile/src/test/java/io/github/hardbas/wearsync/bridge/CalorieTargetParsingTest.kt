package io.github.hardbas.wearsync.bridge

import io.github.hardbas.wearsync.bridge.data.RingConnVitals
import io.github.hardbas.wearsync.bridge.service.RingConnTelemetryParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The calorie goal the watch shows must be the one RingConn prints.
 *
 * RingConn's sport widget puts the unit between the amount and its goal:
 * `Calories, 288, kcal, /300`. The parser only looked for the goal directly
 * after the amount, found the unit there, and kept whatever goal was cached, so
 * the watch reported a 500 kcal goal while the ring's app said 300.
 */
class CalorieTargetParsingTest {

    private val staleGoal = RingConnVitals(targetSteps = 10000, targetCalorie = 500)

    @Test
    fun `reads the calorie goal printed after the unit`() {
        // The exact strings the bound SportIndicators widget produced on a device.
        val widget = listOf(
            "Steps and Calories", "Steps", "1448", "/6000", "Calories", "288", "kcal", "/300"
        )

        val parsed = RingConnTelemetryParser.parseTokens(widget, staleGoal)

        assertEquals(1448, parsed.liveSteps)
        assertEquals(6000, parsed.targetSteps)
        assertEquals(288, parsed.liveCalorie)
        assertEquals(300, parsed.targetCalorie)
    }

    @Test
    fun `still reads a calorie goal printed directly after the amount`() {
        val parsed = RingConnTelemetryParser.parseTokens(listOf("Calories", "288", "/300"), staleGoal)

        assertEquals(288, parsed.liveCalorie)
        assertEquals(300, parsed.targetCalorie)
    }
}
