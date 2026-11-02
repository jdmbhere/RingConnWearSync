package io.github.hardbas.wearsync.app.ui

import io.github.hardbas.wearsync.app.WearSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source audit: the vitals screen reads the same day's range as the heart-rate
 * screen, and it does not fill a missing end with the current reading.
 *
 * It used to answer "no range today" with `min = max = current`, which prints a
 * real-looking pair that no measurement supports, and with a 65%-filled track.
 * Both screens now go through [io.github.hardbas.wearsync.app.data.DailyHrRange];
 * a layout test cannot see a substituted variable, so this is pinned at the
 * source level.
 */
class VitalsDailyRangeTest {

    private val path = "src/main/java/io/github/hardbas/wearsync/app/ui/VitalsDetailActivity.kt"
    private val source = WearSources.file(path)
    private val body: String = run {
        assertTrue("cannot locate $source; the audit must run from the module", source.isFile)
        source.readText()
    }

    @Test
    fun `no end of the range is borrowed from the current reading`() {
        assertTrue(
            "$source still substitutes the current reading for a missing range end",
            !body.contains("else (if (currentHr > 0) currentHr else 0)")
        )
        assertTrue(
            "$source still prints the current reading where the range belongs",
            !body.contains("else hrDisplay,")
        )
    }

    @Test
    fun `the track is never filled with an invented fraction`() {
        assertTrue(
            "$source still falls back to a fixed fill",
            !body.contains("else if (hasHr) 0.65f")
        )
    }

    @Test
    fun `the card goes through the shared range model`() {
        assertTrue(
            "both screens must answer the same question from the same rule",
            body.contains("DailyHrRange(")
        )
        assertTrue(body.contains("positionOf("))
        assertTrue(
            "an empty card is indistinguishable from a broken one",
            body.contains("R.string.daily_range_pending")
        )
    }
}
