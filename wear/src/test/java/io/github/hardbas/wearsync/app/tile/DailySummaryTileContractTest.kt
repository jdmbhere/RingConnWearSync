package io.github.hardbas.wearsync.app.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import io.github.hardbas.wearsync.app.R
import io.github.hardbas.wearsync.app.data.DailyHistoryEntry
import io.github.hardbas.wearsync.app.data.RingConnVitals
import io.github.hardbas.wearsync.app.tile.design.TileAccents
import io.github.hardbas.wearsync.app.tile.design.TileGeometry
import io.github.hardbas.wearsync.app.tile.design.TileInk
import io.github.hardbas.wearsync.app.tile.design.TileMotion
import io.github.hardbas.wearsync.app.tile.design.TileResources
import io.github.hardbas.wearsync.app.tile.design.TileShell
import io.github.hardbas.wearsync.app.tile.design.TileStrings
import io.github.hardbas.wearsync.app.tile.design.TileTestKit
import io.github.hardbas.wearsync.app.tile.design.TileType
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executable contract for the activity tile: the two rings, the three-reading
 * pill, the steps abbreviation, the measured expressive layer (a light streak
 * sweeping the rim) and the chord budget. Built through the public text seam, so
 * the whole layout is inspectable on the JVM.
 */
class DailySummaryTileContractTest {

    private val strings = TileStrings { resId ->
        when (resId) {
            R.string.steps_short -> "Steps"
            R.string.details -> "Details"
            R.string.tile_steps_sub -> "%1\$s · %2\$s"
            else -> "s$resId"
        }
    }

    private val deviceParams = DeviceParameters.Builder()
        .setScreenWidthDp(192)
        .setScreenHeightDp(192)
        .build()

    private val packageName = "io.github.hardbas.wearsync.test"

    private fun now(): Long = System.currentTimeMillis() / 1000L

    private fun rootFor(
        vitals: RingConnVitals,
        history: List<DailyHistoryEntry> = emptyList()
    ): LayoutElementBuilders.LayoutElement =
        DailySummaryTileService.buildTile(strings, packageName, deviceParams, vitals, history)
            .tileTimeline!!.timelineEntries[0].layout!!.root!!

    /** Seven real-looking days, oldest first; the newest day is today's 8 420. */
    private fun sevenDays(): List<DailyHistoryEntry> =
        listOf(7200, 8400, 6100, 9800, 7600, 8800, 8420).mapIndexed { i, steps ->
            DailyHistoryEntry(epochDay = 20_000L + i, steps = steps)
        }

    private fun clickables(root: LayoutElementBuilders.LayoutElement): List<ModifiersBuilders.Clickable> =
        TileTestKit.flatten(root).mapNotNull { element ->
            when (element) {
                is LayoutElementBuilders.Box -> element.modifiers?.clickable
                is LayoutElementBuilders.Row -> element.modifiers?.clickable
                is LayoutElementBuilders.Column -> element.modifiers?.clickable
                is LayoutElementBuilders.Text -> element.modifiers?.clickable
                is LayoutElementBuilders.Image -> element.modifiers?.clickable
                is LayoutElementBuilders.Arc -> element.modifiers?.clickable
                else -> null
            }
        }

    private fun bars(root: LayoutElementBuilders.LayoutElement): List<LayoutElementBuilders.Box> =
        TileTestKit.boxes(root)
            .filter { TileTestKit.dpOf(it.width) == TileGeometry.SPARK_BAR_WIDTH }
            .filter { it.modifiers?.background?.color != null }

    /** 8 420 steps against the 10 000 default goal, 320 kcal of 500. */
    private val live = RingConnVitals(
        liveSteps = 8420,
        targetSteps = 10_000,
        liveCalorie = 320,
        targetCalorie = 500,
        activeMinutes = 42,
        refreshTimestamp = now()
    )

    private val liveRoot by lazy { rootFor(live) }

    // ---- the reading ----------------------------------------------------

    @Test
    fun `hero is the grouped step count at the compact hero size`() {
        val hero = TileTestKit.texts(liveRoot).first { it.text?.value == "8,420" }
        assertEquals(TileType.HERO_COMPACT, hero.fontStyle?.size?.value)
        assertEquals(LayoutElementBuilders.FONT_WEIGHT_MEDIUM, hero.fontStyle?.weight?.value)
    }

    @Test
    fun `label is the uppercase metric word in the steps accent`() {
        val label = TileTestKit.texts(liveRoot).first { it.text?.value == "STEPS" }
        assertTrue(TileTestKit.isUppercase(label.text!!.value))
        assertEquals(TileType.LABEL, label.fontStyle?.size?.value)
        assertEquals(TileAccents.steps.first(), label.fontStyle?.color?.argb)
    }

    @Test
    fun `steps abbreviate from ten thousand with a dot`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("8,420", DailySummaryTileService.stepsText(8420))
            assertEquals("12.4k", DailySummaryTileService.stepsText(12400))
            assertEquals("--", DailySummaryTileService.stepsText(0))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `pill carries active minutes and calories in their own accents`() {
        // Without history the pill is the only line these readings have.
        val texts = TileTestKit.texts(liveRoot)
        val active = texts.first { it.text?.value == "42m" }
        val kcal = texts.first { it.text?.value == "320" }

        assertEquals(TileType.PILL, active.fontStyle?.size?.value)
        assertEquals(TileAccents.steps.first(), active.fontStyle?.color?.argb)
        assertEquals(TileAccents.kcal.first(), kcal.fontStyle?.color?.argb)
        assertTrue(
            "distance can never be measured on this watch, so the face must not draw it",
            texts.none { it.text?.value?.contains("km") == true }
        )
    }

    @Test
    fun `the dial is two tracks and two concentric rings`() {
        val lines = TileTestKit.arcLines(liveRoot)
        assertEquals("track, track, halo, outer ring, inner ring", 5, lines.size)
        val rings = lines.filter { it.brush != null }
        assertEquals("two rings", 2, rings.size)
        assertEquals(TileGeometry.RING_THICKNESS_ACTIVITY, TileTestKit.dpOf(rings[0].thickness), 0.001f)
        assertEquals(TileAccents.steps.last(), rings[0].color?.argb)
        assertEquals(TileGeometry.RING_THICKNESS_ACTIVITY, TileTestKit.dpOf(rings[1].thickness), 0.001f)
        assertEquals(TileAccents.kcal.last(), rings[1].color?.argb)
    }

    // ---- motion ---------------------------------------------------------

    @Test
    fun `the expressive layer is a light streak sweeping the rim`() {
        val streak = TileTestKit.imagesOf(liveRoot, TileResources.SWEEP).single()
        assertEquals(TileGeometry.TILE_DP, TileTestKit.dpOf(streak.width), 0.001f)
        assertEquals(0.40f, streak.modifiers?.opacity?.value ?: 0f, 0.001f)
        assertEquals(
            "a renderer that ignores the animation shows the streak at the top",
            360f,
            streak.modifiers?.transformation?.rotation?.value ?: 0f,
            0.001f
        )
        val spin = TileTestKit.specOf(streak.modifiers?.transformation?.rotation?.dynamicValue)
        assertEquals(TileMotion.SWEEP_MS_RINGS, TileTestKit.durationMs(spin))
        assertTrue(TileTestKit.loopsForever(spin))
        assertEquals(
            "drawn first, so it sits behind both rings",
            TileResources.SWEEP,
            TileTestKit.images(liveRoot).first().resourceId?.value
        )
    }

    @Test
    fun `live tile spends two loops on the streak and the bezel breath`() {
        val specs = TileTestKit.animators(liveRoot)
        assertEquals(2, specs.size)
        assertTrue("both animators loop for ever", specs.all(TileTestKit::loopsForever))
        assertEquals(
            "one is the bezel breath at its measured period",
            1,
            specs.count { TileTestKit.durationMs(it) == TileMotion.AMBIENT_MS }
        )
        assertEquals(
            "the other is one turn of the streak",
            1,
            specs.count { TileTestKit.durationMs(it) == TileMotion.SWEEP_MS_RINGS }
        )
    }

    @Test
    fun `no brush carries a dynamic value`() {
        listOf(live, RingConnVitals(refreshTimestamp = 0L), live.copy(refreshTimestamp = now() - 3_600L))
            .map(::rootFor)
            .forEach { root ->
                assertTrue(
                    "the two rings must stay static; a brush is snapshotted when the tile is built",
                    TileTestKit.brushDynamics(root).isEmpty()
                )
            }
    }

    // ---- degraded states ------------------------------------------------

    @Test
    fun `empty tile keeps both tracks and runs no animator`() {
        val root = rootFor(RingConnVitals(refreshTimestamp = 0L))
        val lines = TileTestKit.arcLines(root)
        assertEquals("the tracks survive, the rings do not", 2, lines.size)
        assertTrue(TileTestKit.animators(root).isEmpty())
        assertTrue(TileTestKit.imagesOf(root, TileResources.SWEEP).isEmpty())
        assertTrue(TileTestKit.texts(root).any { it.text?.value == "--" })
        assertTrue(TileTestKit.texts(root).any { it.text?.value == "STEPS" })

        val pill = TileTestKit.boxes(root)
            .first { it.modifiers?.background?.color?.argb == TileShell.PILL_GLASS }
        val pillTexts = pill.contents
            .flatMap(TileTestKit::flatten)
            .filterIsInstance<LayoutElementBuilders.Text>()
            .mapNotNull { it.text?.value }
        assertEquals(
            "the pill falls back to dashes but keeps its unit letters and its dot separator",
            listOf("--m", "·", "--"),
            pillTexts
        )
    }

    @Test
    fun `stale tile desaturates instead of animating`() {
        val root = rootFor(live.copy(refreshTimestamp = now() - 3_600L))
        val hero = TileTestKit.texts(root).first { it.text?.value == "8,420" }
        assertEquals(TileInk.STALE_HERO_ALPHA, hero.modifiers?.opacity?.value ?: 0f, 0.001f)
        val label = TileTestKit.texts(root).first { it.text?.value == "STEPS" }
        assertEquals(TileInk.DIM, label.fontStyle?.color?.argb)

        val rings = TileTestKit.arcLines(root)
            .filter { TileTestKit.dpOf(it.thickness) == TileGeometry.RING_THICKNESS_ACTIVITY }
        assertEquals("both tracks and both rings stay readable", 4, rings.size)
        assertEquals(TileAccents.dim(TileAccents.steps).last(), rings[2].color?.argb)
        assertEquals(TileAccents.dim(TileAccents.kcal).last(), rings[3].color?.argb)

        assertTrue(
            "a stale tile is a still frame: no streak and no animator",
            TileTestKit.animators(root).isEmpty()
        )
        assertTrue(TileTestKit.imagesOf(root, TileResources.SWEEP).isEmpty())
    }

    // ---- fit ------------------------------------------------------------

    @Test
    fun `label and pill fit the chord at their own height`() {
        val label = TileTestKit.texts(liveRoot).first { it.text?.value == "STEPS" }
        assertTrue(
            "label ${TileTestKit.estimatedWidthDp(label)}dp must stay under " +
                "${TileGeometry.safeWidthDp(TileGeometry.LABEL_Y)}dp",
            TileTestKit.estimatedWidthDp(label) <= TileGeometry.safeWidthDp(TileGeometry.LABEL_Y)
        )

        val pill = TileTestKit.boxes(liveRoot)
            .first { it.modifiers?.background?.color?.argb == TileShell.PILL_GLASS }
        assertEquals(
            "the face pill is the render's fixed width",
            TileGeometry.PILL_WIDTH,
            TileTestKit.dpOf(pill.width),
            0.001f
        )
        assertTrue(
            "pill ${TileGeometry.PILL_WIDTH}dp must stay under " +
                "${TileGeometry.safeWidthDp(TileGeometry.PILL_CENTER_Y)}dp",
            TileGeometry.PILL_WIDTH <= TileGeometry.safeWidthDp(TileGeometry.PILL_CENTER_Y)
        )
    }

    // ---- the trend row (S14) --------------------------------------------

    @Test
    fun `the trend row draws the last seven days of steps`() {
        val row = bars(rootFor(live, sevenDays()))
        assertEquals("one bar per day of history", 7, row.size)
        row.forEach {
            assertEquals(
                "seven bars of 7dp",
                TileGeometry.SPARK_BAR_WIDTH,
                TileTestKit.dpOf(it.width),
                0.001f
            )
        }
    }

    @Test
    fun `the bars are proportional to the series maximum with a two dp floor`() {
        // 9 800 is the series maximum; today's 8 420 is a share of it.
        val row = bars(rootFor(live, sevenDays()))
        assertEquals(TileGeometry.SPARK_HEIGHT * (8420f / 9800f), TileTestKit.dpOf(row.last().height), 0.01f)
        listOf(7200, 8400, 6100, 9800, 7600, 8800).forEachIndexed { index, steps ->
            assertEquals(
                "bar $index is $steps of 9800",
                TileGeometry.SPARK_HEIGHT * (steps / 9800f),
                TileTestKit.dpOf(row[index].height),
                0.01f
            )
        }

        val quiet = listOf(7200, 8400, 6100, 9800, 7600, 8800, 12).mapIndexed { i, steps ->
            DailyHistoryEntry(epochDay = 30_000L + i, steps = steps)
        }
        assertEquals(
            TileGeometry.SPARK_MIN_HEIGHT,
            TileTestKit.dpOf(bars(rootFor(live, quiet)).last().height),
            0.001f
        )
    }

    @Test
    fun `today keeps the full accent and the other days fade to forty five`() {
        val accent = TileAccents.steps.last()
        val row = bars(rootFor(live, sevenDays()))
        assertEquals(accent, row.last().modifiers?.background?.color?.argb)
        row.dropLast(1).forEach {
            assertEquals(
                TileInk.withAlpha(accent, TileGeometry.SPARK_PAST_ALPHA),
                it.modifiers?.background?.color?.argb
            )
        }
    }

    @Test
    fun `with a trend row the daytime readings move to the sub and the pill is the action`() {
        val root = rootFor(live, sevenDays())
        val texts = TileTestKit.texts(root).mapNotNull { it.text?.value }
        assertTrue("the sub carries the two measured readings, had $texts", texts.any { it == "42m · 320" })
        assertTrue("the pill is the one action, had $texts", texts.any { it == "Details" })
        assertTrue(
            "the pill launches the detail activity",
            clickables(root).any { it.onClick is ActionBuilders.LaunchAction }
        )
        assertTrue(
            "no load action survives the drawer",
            clickables(root).none { it.onClick is ActionBuilders.LoadAction }
        )
    }

    @Test
    fun `the row sits under the hero on the S14 rhythm`() {
        val stack = TileTestKit.flatten(rootFor(live, sevenDays()))
            .filterIsInstance<LayoutElementBuilders.Box>()
            .first { TileTestKit.dpOf(it.height) == TileGeometry.TILE_DP }
        val centres = stack.contents.mapNotNull {
            (it as? LayoutElementBuilders.Box)?.modifiers?.transformation?.translationY?.value
        }
        assertEquals(
            listOf(
                TileGeometry.TREND_LABEL_Y,
                TileGeometry.TREND_HERO_Y,
                TileGeometry.TREND_ROW_Y,
                TileGeometry.TREND_SUB_Y
            ),
            centres
        )
    }

    @Test
    fun `no history means no row and the face it had before`() {
        val root = rootFor(live)
        assertTrue("nothing is invented", bars(root).isEmpty())
        assertTrue(
            "the stack keeps the column of a face with no trend",
            TileTestKit.flatten(root).any { it is LayoutElementBuilders.Column }
        )
        assertTrue(
            "and the pill keeps the readings",
            TileTestKit.texts(root).any { it.text?.value == "42m" }
        )
    }

    // ---- previous behaviour ---------------------------------------------

    @Test
    fun `the tile source carries no colour literal and no legacy design import`() {
        val source = java.io.File("src/main/java/io/github/hardbas/wearsync/app/tile/DailySummaryTileService.kt")
        assertTrue("source not found at ${source.absolutePath}", source.isFile)
        val body = source.readText()
        assertTrue(
            "every colour must come from TileInk or TileAccents",
            !Regex("0x[0-9A-Fa-f]{2,}").containsMatchIn(body)
        )
        listOf("TileDesign", "TileGauge", "TileChrome", "TileMeter", "TileMetric", "TileBackdrop")
            .forEach { legacy ->
                assertTrue("legacy $legacy reference left behind", !body.contains(legacy))
            }
    }

    @Test
    fun `previous freshness and resource version still hold`() {
        val tile = DailySummaryTileService.buildTile(strings, packageName, deviceParams, live)
        assertEquals(900_000L, tile.freshnessIntervalMillis)
        assertEquals(DailySummaryTileService.RESOURCES_VERSION, DailySummaryTileService.buildResources().version)
    }
}
