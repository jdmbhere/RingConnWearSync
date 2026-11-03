package io.github.hardbas.wearsync.app.tile

import android.content.Context
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.EventBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.hardbas.wearsync.app.R
import io.github.hardbas.wearsync.app.complication.RingConnComplicationProvider
import io.github.hardbas.wearsync.app.data.DailyHistoryEntry
import io.github.hardbas.wearsync.app.data.RingConnHistoryStore
import io.github.hardbas.wearsync.app.data.RingConnVitals
import io.github.hardbas.wearsync.app.tile.design.AndroidTileStrings
import io.github.hardbas.wearsync.app.tile.design.Ring
import io.github.hardbas.wearsync.app.tile.design.TileAccents
import io.github.hardbas.wearsync.app.tile.design.TileAmbient
import io.github.hardbas.wearsync.app.tile.design.TileDial
import io.github.hardbas.wearsync.app.tile.design.TileGeometry
import io.github.hardbas.wearsync.app.tile.design.TileInk
import io.github.hardbas.wearsync.app.tile.design.TileMotion
import io.github.hardbas.wearsync.app.tile.design.TileResources
import io.github.hardbas.wearsync.app.tile.design.TileShell
import io.github.hardbas.wearsync.app.tile.design.TileSparkline
import io.github.hardbas.wearsync.app.tile.design.TileStrings
import io.github.hardbas.wearsync.app.tile.design.TileType
import java.text.NumberFormat
import java.util.Locale

/**
 * Activity tile: sage steps ring, ochre calorie ring and the three daytime
 * readings in one pill.
 *
 * The file only supplies meaning. Every colour, size, radius and animation comes
 * from the warm editorial design system, so the carousel reads as one watch face
 * family instead of five hand-painted gauges.
 */
class DailySummaryTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        return Futures.immediateFuture(buildTile(this, requestParams.deviceConfiguration))
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(buildResources())
    }

    override fun onTileEnterEvent(tileEnterEvent: EventBuilders.TileEnterEvent) {
        super.onTileEnterEvent(tileEnterEvent)
    }

    companion object {
        const val RESOURCES_VERSION = "warm-editorial-steps-v1"

        /** Face refresh period. */
        private const val DEFAULT_FRESHNESS_MS = 900_000L

        private const val DETAIL_ACTIVITY = "io.github.hardbas.wearsync.app.ui.DailySummaryDetailActivity"

        /** The design renders the rings against a 10 000 step / 500 kcal goal. */
        private const val DEFAULT_TARGET_STEPS = 10_000
        private const val DEFAULT_TARGET_KCAL = 500

        fun buildResources(): ResourceBuilders.Resources =
            TileResources.addTo(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION)).build()

        fun buildTile(
            context: Context,
            deviceParams: DeviceParameters,
            vitals: RingConnVitals = RingConnComplicationProvider.getLatestVitals(context),
            history: List<DailyHistoryEntry> = RingConnHistoryStore.getHistory(context)
        ): TileBuilders.Tile = buildTile(
            AndroidTileStrings(context),
            context.packageName,
            deviceParams,
            vitals,
            history
        )

        /**
         * @param history the seven-day cache the trend row comes from. Passed in
         *   so the contract tests can build entries directly; production reads it
         *   from [RingConnHistoryStore].
         */
        fun buildTile(
            strings: TileStrings,
            packageName: String,
            deviceParams: DeviceParameters,
            vitals: RingConnVitals,
            history: List<DailyHistoryEntry> = emptyList()
        ): TileBuilders.Tile {
            val hasData = vitals.liveSteps > 0
            val stale = vitals.isStale

            val stepsTarget = if (vitals.targetSteps > 0) vitals.targetSteps else DEFAULT_TARGET_STEPS
            val kcalTarget = if (vitals.targetCalorie > 0) vitals.targetCalorie else DEFAULT_TARGET_KCAL

            // Warm ramps when the reading is live, the palette's grey when it is not:
            // the design's "Viejo" state desaturates the whole face, glow included.
            val stepsRamp = if (stale) TileAccents.dim(TileAccents.steps) else TileAccents.steps
            val kcalRamp = if (stale) TileAccents.dim(TileAccents.kcal) else TileAccents.kcal
            val labelColor = if (hasData && !stale) TileAccents.steps.first() else TileInk.DIM

            // Floored at 5 % so a short walk still shows a ring instead of a dot,
            // and capped at the goal: the design's rings are goal progress.
            val stepsFraction = (vitals.liveSteps.toFloat() / stepsTarget).coerceIn(0.05f, 1f)
            val kcalFraction = (vitals.liveCalorie.toFloat() / kcalTarget).coerceIn(0.05f, 1f)

            // Units survive a missing reading, the number does not: the design
            // writes "--m", not a bare dash.
            val activeText = if (vitals.activeMinutes > 0) "${vitals.activeMinutes}m" else "--m"
            val kcalText = if (vitals.liveCalorie > 0) "${vitals.liveCalorie}" else "--"

            val clickable = TileShell.launchDetail(packageName, DETAIL_ACTIVITY)

            val layers = buildList {
                // The measured expressive layer: a light streak sweeping the rim,
                // drawn first so it sits behind both rings. Rotation only, pivot on
                // the tile centre; the track keeps its own breath.
                if (hasData && !stale) {
                    add(
                        TileAmbient.sweep(
                            resourceId = TileResources.SWEEP,
                            accent = stepsRamp.last(),
                            durationMs = TileMotion.SWEEP_MS_RINGS,
                            opacity = 0.40f
                        )
                    )
                }
                add(TileShell.glow(TileResources.GLOW, stepsRamp.last()))
                add(
                    TileDial.track(
                        stepsRamp.last(),
                        TileGeometry.RING_RADIUS,
                        TileGeometry.RING_THICKNESS_ACTIVITY,
                        breathing = hasData && !stale
                    )
                )
                add(
                    TileDial.track(
                        kcalRamp.last(),
                        TileGeometry.RING_INNER_RADIUS,
                        TileGeometry.RING_THICKNESS_ACTIVITY,
                        breathing = false
                    )
                )
                add(TileShell.overlay(TileResources.TICKS_R78))
                if (hasData) {
                    // ONE call for both rings: it shares the entrance rotation and
                    // spends the tile's only two animators. A stale tile asks for a
                    // still frame instead — no halo, no entrance, no animator.
                    add(
                        TileDial.rings(
                            outer = Ring(
                                accent = stepsRamp,
                                fraction = stepsFraction,
                                radius = TileGeometry.RING_RADIUS,
                                thickness = TileGeometry.RING_THICKNESS_ACTIVITY
                            ),
                            inner = Ring(
                                accent = kcalRamp,
                                fraction = kcalFraction,
                                radius = TileGeometry.RING_INNER_RADIUS,
                                thickness = TileGeometry.RING_THICKNESS_ACTIVITY
                            )
                        )
                    )
                }
            }

            // The trend row: the steps of the last seven days, oldest first.
            val series = stepsSeries(history)
            val hasTrend = series.isNotEmpty()
            val sparkline = if (hasTrend) {
                TileSparkline.row(
                    values = series,
                    // A stale face desaturates every accent it owns (S7).
                    accent = stepsRamp.last(),
                    heightDp = TileGeometry.SPARK_HEIGHT,
                    barWidthDp = TileGeometry.SPARK_BAR_WIDTH,
                    gapDp = TileGeometry.SPARK_BAR_GAP
                )
            } else {
                null
            }

            // The design gives this tile label + hero + pill only. A trend row
            // makes room for the three daytime readings as the subtitle, so the
            // face keeps them without repeating them in the pill.
            val stack = TileShell.stack(
                label = TileShell.text(
                    value = strings.text(R.string.steps_short).uppercase(Locale.getDefault()),
                    style = TileType.label(labelColor)
                ),
                hero = TileShell.text(
                    value = if (hasData) stepsText(vitals.liveSteps) else "--",
                    style = TileType.hero(TileType.HERO_COMPACT),
                    opacity = if (stale) TileInk.STALE_HERO_ALPHA else null
                ),
                sub = if (hasTrend) {
                    TileShell.text(
                        value = String.format(
                            Locale.getDefault(),
                            strings.text(R.string.tile_steps_sub),
                            activeText,
                            kcalText
                        ),
                        style = TileType.sub()
                    )
                } else {
                    null
                },
                trend = sparkline
            )

            val pill = if (hasTrend) {
                TileShell.pill(
                    content = TileShell.pillText(strings.text(R.string.details), TileInk.PRIMARY),
                    clickable = clickable
                )
            } else {
                TileShell.pill(
                    content = TileShell.pillRow(
                        listOf(
                            TileShell.text(activeText, TileType.pill(TileAccents.steps.first())),
                            TileShell.pillSeparator(),
                            TileShell.text(kcalText, TileType.pill(TileAccents.kcal.first()))
                        )
                    ),
                    clickable = clickable
                )
            }

            val entry = TimelineBuilders.TimelineEntry.Builder()
                .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(TileShell.root(clickable, layers, stack, pill)).build())
                .build()

            return tile(entry)
        }

        /**
         * The step count of the last seven days, oldest first. A day with no
         * reading is left out instead of drawn as a dip.
         */
        private fun stepsSeries(history: List<DailyHistoryEntry>): List<Float> =
            history.sortedBy { it.epochDay }
                .takeLast(TileGeometry.SPARK_DAYS)
                .map { it.steps.toFloat() }
                .filter { it > 0f }

        private fun tile(
            entry: TimelineBuilders.TimelineEntry,
            freshnessMs: Long = DEFAULT_FRESHNESS_MS
        ): TileBuilders.Tile =
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(TimelineBuilders.Timeline.Builder().addTimelineEntry(entry).build())
                .setFreshnessIntervalMillis(freshnessMs)
                .build()

        /**
         * Steps as the design prints them: `12.4k` from ten thousand up (always a
         * dot, so the tile never depends on the watch locale for a decimal mark),
         * the locale's grouped integer below that.
         */
        fun stepsText(steps: Int): String = when {
            steps <= 0 -> "--"
            steps >= 10_000 -> String.format(Locale.US, "%.1fk", steps / 1000f)
            else -> NumberFormat.getIntegerInstance(Locale.getDefault()).format(steps)
        }
    }
}
