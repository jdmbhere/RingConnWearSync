package io.github.hardbas.wearsync.bridge.service

import io.github.hardbas.wearsync.bridge.data.RingConnVitals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingConnWidgetHostReaderTest {

    @Test
    fun firstPokeAlwaysRunsWhenNoPriorPokeExists() {
        assertTrue(
            "Initial background poke must run when lastPokeElapsedMs is 0",
            RingConnWidgetHostReader.shouldPokeBackgroundWorker(
                nowElapsedMs = 50_000L,
                lastPokeElapsedMs = 0L
            )
        )
    }

    @Test
    fun pokeIsSuppressedInsideRingConn180SecondCooldown() {
        assertTrue(
            "Cooldown constant must exceed RingConn BaseAppWidgetReceiver 180s guard",
            RingConnWidgetHostReader.BACKGROUND_POKE_COOLDOWN_MS > 180_000L
        )
        val lastPoke = 100_000L
        assertFalse(
            "Poke at +169s must be suppressed to avoid hitting RingConn 180s guard",
            RingConnWidgetHostReader.shouldPokeBackgroundWorker(
                nowElapsedMs = lastPoke + 169_000L,
                lastPokeElapsedMs = lastPoke
            )
        )
    }

    @Test
    fun pokeRunsAgainOnceCooldownElapses() {
        val lastPoke = 100_000L
        assertTrue(
            "Poke must run once BACKGROUND_POKE_COOLDOWN_MS has elapsed",
            RingConnWidgetHostReader.shouldPokeBackgroundWorker(
                nowElapsedMs = lastPoke + RingConnWidgetHostReader.BACKGROUND_POKE_COOLDOWN_MS,
                lastPokeElapsedMs = lastPoke
            )
        )
    }

    @Test
    fun preserveShouldTriggerGlanceRecomposeBehaviour() {
        val cachedVitals = RingConnVitals(lastHeartRate = 75, sleepScore = 85)
        assertTrue(
            "Empty widget tokens must trigger Glance recompose even when cached baseVitals has data",
            RingConnWidgetHostReader.shouldTriggerGlanceRecompose(
                collectedTokens = emptyList(),
                baseVitals = cachedVitals,
                hadNewAllocation = false
            )
        )
        assertFalse(
            "Valid widget tokens should skip Glance recompose when no new allocation occurred",
            RingConnWidgetHostReader.shouldTriggerGlanceRecompose(
                collectedTokens = listOf("HR", "75", "bpm", "Latest"),
                baseVitals = cachedVitals,
                hadNewAllocation = false
            )
        )
    }

    @Test
    fun preserveWidgetDimensionsDpMapping() {
        assertEquals(
            360 to 320,
            RingConnWidgetHostReader.widgetDimensionsDp(
                "com.gdjztech.remotewidget.DataIndicatorsLargeWidgetReceiver",
                toggle = false
            )
        )
        assertEquals(
            360 to 321,
            RingConnWidgetHostReader.widgetDimensionsDp(
                "com.gdjztech.remotewidget.DataIndicatorsLargeWidgetReceiver",
                toggle = true
            )
        )
    }
}
