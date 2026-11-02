package io.github.hardbas.wearsync.app.ui

import io.github.hardbas.wearsync.app.WearSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source audit: the daily-range bar reports the day's measured range and nothing
 * else.
 *
 * It used to accept taps and drags, move its own fill under the finger and throw
 * that position away after 2.5 s. Nothing about a min-max range is an input, so
 * the gesture was a control with no effect, and it read as a slider that answered
 * to the watch. A layout test cannot see an installed pointer handler, which is
 * why this is pinned at the source level.
 */
class DailyRangeBarIsReadOnlyTest {

    private val source = WearSources.file(
        "src/main/java/io/github/hardbas/wearsync/app/ui/HeartRateDetailActivity.kt"
    )
    private val body: String = run {
        assertTrue("cannot locate $source; the audit must run from the module", source.isFile)
        source.readText()
    }

    @Test
    fun `the bar installs no pointer handler`() {
        val bar = functionBody("private fun AnimatedMinMaxBar(")
        for (gesture in listOf("pointerInput", "detectTapGestures", "detectHorizontalDragGestures")) {
            assertTrue(
                "$source installs `$gesture` on the daily-range bar; the range is a reading, not a control",
                !bar.contains(gesture)
            )
        }
    }

    @Test
    fun `the bar keeps no position of its own`() {
        val bar = functionBody("private fun AnimatedMinMaxBar(")
        assertTrue(
            "$source keeps a scrubbed position ($SCRUB_STATE); the fill must follow the data",
            !body.contains(SCRUB_STATE)
        )
        assertTrue("$source lost the animated fill", bar.contains("anim.animateTo"))
    }

    @Test
    fun `the card plots the range the ring reported`() {
        assertTrue(
            "the card must ask the range model instead of computing a position inline",
            body.contains("positionOf(")
        )
        // A default fill is a fabricated reading: with no range it would draw a
        // position nobody measured.
        assertTrue(
            "$source still fills the track with an invented fraction (`$INVENTED_FILL`)",
            !body.contains(INVENTED_FILL)
        )
    }

    @Test
    fun `the card says the range is pending instead of hiding the gap`() {
        assertTrue(
            "an empty card is indistinguishable from a broken one",
            body.contains("R.string.daily_range_pending")
        )
    }

    /**
     * The body of one function, matched by its signature and closed by counting
     * braces. Slicing to the next declaration instead would silently start
     * auditing whichever function happens to follow.
     */
    private fun functionBody(signature: String): String {
        val start = body.indexOf(signature)
        assertTrue("cannot find `$signature` in $source", start >= 0)
        val open = body.indexOf('{', start)
        assertTrue("`$signature` has no body in $source", open >= 0)
        var depth = 0
        for (index in open until body.length) {
            when (body[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return body.substring(open, index + 1)
                }
            }
        }
        throw AssertionError("`$signature` is not closed in $source")
    }

    private companion object {
        const val SCRUB_STATE = "scrubPos"
        const val INVENTED_FILL = "else if (hasHr) 0.65f"
    }
}
