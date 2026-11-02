package io.github.hardbas.wearsync.app.complication

import io.github.hardbas.wearsync.app.WearSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source audit: every payload the watch receives feeds the day's range through
 * one funnel.
 *
 * The range used to arrive only as `min_hr`/`max_hr` from the phone, which the
 * widget scrape never produces, so a watch fed by the widget alone had a current
 * heart rate and no range at all — for hours. The rule now runs where the values
 * are persisted, which is the only place that sees every payload. A layout test
 * cannot see that wiring, so it is pinned at the source level.
 */
class DailyRangeFunnelTest {

    private val path = "src/main/java/io/github/hardbas/wearsync/app/complication/RingConnComplicationProvider.kt"
    private val source = WearSources.file(path)
    private val body: String = run {
        assertTrue("cannot locate $source; the audit must run from the module", source.isFile)
        source.readText()
    }

    @Test
    fun `the save funnel widens the day's range`() {
        assertTrue(
            "the funnel must union the stored range, the reported range and the reading",
            body.contains("DailyHrRange.widen(")
        )
        assertTrue(
            "a reading is folded in only when it belongs to the current day",
            body.contains("DailyHrRange.observed(")
        )
    }

    @Test
    fun `the widened range is what gets persisted and recorded`() {
        assertTrue(
            "persisting the reported values would drop the reading that arrived with them",
            body.contains("putInt(\"min_hr\", merged.minHeartRate)")
        )
        assertTrue(body.contains("putInt(\"max_hr\", merged.maxHeartRate)"))
        assertTrue(
            "the seven-day trend reads the same day's minimum",
            body.contains("recordDailySnapshot(context, merged)")
        )
    }
}
