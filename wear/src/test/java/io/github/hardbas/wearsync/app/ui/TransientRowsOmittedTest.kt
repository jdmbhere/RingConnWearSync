package io.github.hardbas.wearsync.app.ui

import io.github.hardbas.wearsync.app.WearSources

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source audit: a field that can still be measured must never render a placeholder.
 *
 * The eight transient fields — the three stage heart rates, the bed efficiency and
 * the four circadian stress blocks — are unknown only until the first measurement
 * arrives. A `--` row for them says "the phone has not synced", which is not their
 * state, and it leaves a line on the card that carries nothing. They are omitted
 * instead, and the two dead sources (distance and basal calories) are gone from
 * every watch surface entirely. A Compose screen cannot be laid out on the JVM, so
 * the absence is pinned at the source level.
 */
class TransientRowsOmittedTest {

    private fun bodyOf(relativePath: String): String {
        val file = WearSources.file(relativePath)
        assertTrue("cannot locate $file; the audit must run from the module", file.isFile)
        return file.readText()
    }

    private val sleep = bodyOf("src/main/java/io/github/hardbas/wearsync/app/ui/SleepDetailActivity.kt")
    private val stress = bodyOf("src/main/java/io/github/hardbas/wearsync/app/ui/StressDetailActivity.kt")
    private val daily = bodyOf("src/main/java/io/github/hardbas/wearsync/app/ui/DailySummaryDetailActivity.kt")
    private val tile = bodyOf("src/main/java/io/github/hardbas/wearsync/app/tile/DailySummaryTileService.kt")

    @Test
    fun `phase heart rates come only from a measurement`() {
        assertTrue(
            "the phase card must draw the measured stages",
            sleep.contains("SleepPhaseHeartRates.measured(")
        )
        assertAbsent(sleep, "a deep-phase placeholder", "text = if (deepHr > 0) \"\$deepHr\" else \"--\"")
        assertAbsent(sleep, "a light-phase placeholder", "text = if (lightHr > 0) \"\$lightHr\" else \"--\"")
        assertAbsent(sleep, "a rem-phase placeholder", "text = if (remHr > 0) \"\$remHr\" else \"--\"")
        assertAbsent(sleep, "an efficiency placeholder", "Eff. \$effPct% · EST\") else \"--\"")
    }

    @Test
    fun `circadian stress blocks come only from a measurement`() {
        assertTrue(
            "the blocks card must draw the measured blocks",
            stress.contains("StressPhases.measured(")
        )
        assertAbsent(stress, "a block score placeholder", "text = if (score > 0) \"\$score\" else \"--\"")
        assertAbsent(stress, "a block status placeholder", "score <= 0 -> \"--\"")
    }

    @Test
    fun `the two dead sources are gone from every watch surface`() {
        listOf(sleep to "SleepDetailActivity", stress to "StressDetailActivity", daily to "DailySummaryDetailActivity", tile to "DailySummaryTileService")
            .forEach { (body, name) ->
                assertFalse("$name still reads the dead distance field", body.contains("distanceKm"))
                assertFalse("$name still reads the dead basal field", body.contains("basalCalories"))
            }
    }

    private fun assertAbsent(body: String, what: String, needle: String) {
        assertFalse(
            "a transient row still renders $what (`$needle`); omit the row instead",
            body.contains(needle)
        )
    }
}
