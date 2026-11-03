package io.github.hardbas.wearsync.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import io.github.hardbas.wearsync.app.R
import io.github.hardbas.wearsync.app.complication.RingConnComplicationProvider
import io.github.hardbas.wearsync.app.data.DailyHrRange
import io.github.hardbas.wearsync.app.data.MeasureOutcome
import io.github.hardbas.wearsync.app.data.RingConnVitals
import io.github.hardbas.wearsync.app.service.WearDataListenerService
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import io.github.hardbas.wearsync.app.data.RingConnHistoryStore
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// 5 Standard One UI Heart Rate Zone Colors
private val ZoneRestColor = Color(0xFF47CDFF)      // Reposo (< 100 bpm)
private val ZoneWarmupColor = Color(0xFFA0F116)    // Calentamiento (100 - 120 bpm)
private val ZoneFatBurnColor = Color(0xFFFFB703)   // Quema de grasa (121 - 140 bpm)
private val ZoneCardioColor = Color(0xFFFB8500)    // Cardio (141 - 160 bpm)
private val ZonePeakColor = Color(0xFFFF5D99)      // Pico (> 160 bpm)

class VitalsDetailActivity : ComponentActivity() {
    private var triggerMeasureOnStart by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        triggerMeasureOnStart = isMeasureIntent(intent)
        setContent {
            VitalsDetailScreen(this, triggerMeasureOnStart)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        if (isMeasureIntent(intent)) {
            triggerMeasureOnStart = true
        }
    }

    private fun isMeasureIntent(intent: android.content.Intent?): Boolean {
        if (intent == null) return false
        val className = intent.component?.className ?: ""
        return className.contains("HeartRateMeasureActivity") ||
                intent.getBooleanExtra("start_measure", false) ||
                intent.action == "io.github.hardbas.wearsync.app.ACTION_MEASURE_HR"
    }
}

/** How long to wait for the node list before treating the phone as unreachable. */
private const val NODE_DISCOVERY_TIMEOUT_MS = 3_000L

/**
 * Asks the phone for a fresh RingConn sync and reports whether the reading on the
 * watch is demonstrably newer than it was when the request started.
 *
 * A completion signal alone does not prove that, because an unrelated sync can
 * land while we wait; see [MeasureOutcome]. Returns false when no phone is
 * reachable, when nothing completes before the timeout, or when the reading did
 * not advance, so no caller can present or persist an unrefreshed value as new.
 */
private suspend fun requestRingSync(context: Context, timeoutMs: Long = 8500L): Boolean {
    val before = RingConnComplicationProvider.getLatestVitals(context).refreshTimestamp
    val startSync = WearDataListenerService.lastSyncCompletedMs.value

    // No reachable phone means no round trip to wait for: fail now instead of
    // spending the whole timeout to learn the same thing.
    val nodes = withTimeoutOrNull(NODE_DISCOVERY_TIMEOUT_MS) { awaitNodes(context) } ?: emptyList()
    if (nodes.isEmpty()) return false

    nodes.forEach { node ->
        try {
            Wearable.getMessageClient(context).sendMessage(
                node.id,
                "/action/sync_ringconn",
                ByteArray(0)
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val signalSeen = withTimeoutOrNull(timeoutMs) {
        WearDataListenerService.lastSyncCompletedMs.first { it != startSync }
    } != null

    val after = RingConnComplicationProvider.getLatestVitals(context).refreshTimestamp
    return MeasureOutcome.refreshed(before = before, after = after, syncSignalSeen = signalSeen)
}

private suspend fun awaitNodes(context: Context): List<Node> =
    suspendCancellableCoroutine { continuation ->
        Wearable.getNodeClient(context).connectedNodes
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resume(emptyList()) }
    }

@Composable
fun VitalsDetailScreen(context: Context, startMeasureDirectly: Boolean = false) {
    val scope = rememberCoroutineScope()
    var vitals by remember { mutableStateOf(RingConnComplicationProvider.getLatestVitals(context)) }
    var isMeasuring by remember { mutableStateOf(false) }
    var measureFailed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("RingConnWearCache", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            vitals = RingConnComplicationProvider.getLatestVitals(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    // Auto-trigger measurement if launched via "Medir Ahora" pill (HeartRateMeasureActivity).
    // The pill asks the phone for a real reading and waits for it. It never derives
    // a number from the previous one: a fabricated value here is indistinguishable
    // from a measured one once it is persisted.
    LaunchedEffect(startMeasureDirectly) {
        if (startMeasureDirectly && !isMeasuring) {
            isMeasuring = true
            val refreshed = requestRingSync(context)
            if (refreshed) {
                vitals = RingConnComplicationProvider.getLatestVitals(context)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            measureFailed = !refreshed
            isMeasuring = false
        }
    }

    VitalsDetailContent(
        vitals = vitals,
        isMeasuring = isMeasuring,
        measureFailed = measureFailed,
        onMeasureClick = {
            if (!isMeasuring) {
                isMeasuring = true
                measureFailed = false
                scope.launch {
                    val refreshed = requestRingSync(context)
                    if (refreshed) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    measureFailed = !refreshed
                    isMeasuring = false
                }
            }
        }
    )
}

@Composable
fun VitalsDetailContent(
    vitals: RingConnVitals,
    isMeasuring: Boolean = false,
    measureFailed: Boolean = false,
    onMeasureClick: () -> Unit = {}
) {
    val listState = rememberScalingLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val currentHr = vitals.lastHeartRate
    val hasHr = currentHr > 0
    val animatedHr by animateIntAsState(
        targetValue = if (hasHr) currentHr else 0,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "hrAnim"
    )
    val hrDisplay = if (hasHr) "$animatedHr" else "--"
    // The same model the heart-rate screen uses: a missing end stays missing
    // instead of borrowing the current reading and calling it a range.
    val range = DailyHrRange(vitals.minHeartRate, vitals.maxHeartRate)
    val rangeFill = range.positionOf(currentHr)
    val minHr = range.min
    val maxHr = range.max

    val minHrText = if (range.min > 0) stringResource(R.string.min_hr_format, range.min) else "--"
    val maxHrText = if (range.max > 0) stringResource(R.string.max_hr_format, range.max) else "--"

    val hrStatusLabel = when {
        !hasHr -> "--"
        currentHr < 60 -> stringResource(R.string.status_resting)
        currentHr <= 100 -> stringResource(R.string.status_normal)
        else -> stringResource(R.string.status_elevated)
    }
    val hrStatusColor = when {
        !hasHr -> LabelSilver
        currentHr < 60 -> CyanActive
        currentHr <= 100 -> LimeSteps
        else -> HrMinMaxPink
    }

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(OneUiBlack)
        ) {
            FullScreenVitalsBackdrop(
                listState = listState,
                spo2 = vitals.lastSpo2,
                readinessScore = RingConnHistoryStore.computeReadinessScore(vitals),
                modifier = Modifier.fillMaxSize()
            )

            ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .onRotaryScrollEvent {
                    coroutineScope.launch {
                        listState.scrollBy(it.verticalScrollPixels * 0.8f)
                    }
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
            state = listState,
            autoCentering = null,
            scalingParams = ScalingLazyColumnDefaults.scalingParams(
                edgeScale = 0.65f,
                edgeAlpha = 0.40f,
                minElementHeight = 0.2f,
                maxElementHeight = 0.65f,
                minTransitionArea = 0.35f,
                maxTransitionArea = 0.55f,
                scaleInterpolator = CubicBezierEasing(0.20f, 0.00f, 0.25f, 1.00f)
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 34.dp, bottom = 32.dp, start = 14.dp, end = 14.dp)
        ) {
            // =========================================================================
            // a) Header: Clean title with circular clearance
            // =========================================================================
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.tile_vitals_title),
                        fontSize = TextBody,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.ring_model_subtitle),
                        fontSize = TextCaption,
                        color = HrSubtitlePink,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // =========================================================================
            // a2) Readiness / Body Battery Hero Card
            // =========================================================================
            item {
                val readiness = RingConnHistoryStore.computeReadinessScore(vitals)
                val readinessClamped = readiness.coerceIn(0, 100)
                val hasReadiness = readiness > 0

                val (readinessLabel, readinessColor, cardGradient) = when {
                    !hasReadiness -> Triple(
                        "Syncing Telemetry",
                        LabelSilver,
                        Brush.verticalGradient(listOf(Color(0xFF22262C), Color(0xFF16191D)))
                    )
                    readinessClamped >= 75 -> Triple(
                        "Optimal Recovery",
                        Color(0xFF00E676),
                        Brush.verticalGradient(listOf(Color(0xFF103327), Color(0xFF0A1F18)))
                    )
                    readinessClamped in 45..74 -> Triple(
                        "Balanced Charge",
                        Color(0xFFFFB703),
                        Brush.verticalGradient(listOf(Color(0xFF33260A), Color(0xFF1E1706)))
                    )
                    else -> Triple(
                        "Recovery Needed",
                        Color(0xFFFF5D99),
                        Brush.verticalGradient(listOf(Color(0xFF331420), Color(0xFF1E0C13)))
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapeCard)
                        .background(cardGradient)
                        .border(BorderStroke(1.dp, readinessColor.copy(alpha = 0.35f)), ShapeCard)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LiquidReadinessOrb(
                            score = readinessClamped,
                            sizeDp = 58.dp,
                            showScoreText = true
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "BODY BATTERY",
                                fontSize = TextCaption,
                                fontWeight = FontWeight.Bold,
                                color = readinessColor
                            )
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                text = readinessLabel,
                                fontSize = TextBody,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            val hrvStr = if (vitals.lastHrv > 0) "${vitals.lastHrv}ms" else "--"
                            val sleepStr = if (vitals.sleepScore > 0) "${vitals.sleepScore}pts" else "--"
                            Text(
                                text = "HRV $hrvStr  ·  Sleep $sleepStr",
                                fontSize = TextCaption,
                                style = TabularNums,
                                color = LabelSilver
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // b) Hero: Centered Heart Dial (122dp), live BPM, status pill & Measure button
            // =========================================================================
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(122.dp)
                    ) {
                        HeartDialGauge(
                            bpm = currentHr,
                            modifier = Modifier.fillMaxSize()
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = hrDisplay,
                                fontSize = TextHero,
                                style = TabularNums,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = stringResource(R.string.bpm_unit).uppercase(),
                                fontSize = TextCaption,
                                color = HrSubtitlePink,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .clip(ShapePill)
                            .background(hrStatusColor.copy(alpha = 0.18f))
                            .padding(horizontal = 14.dp, vertical = 3.5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = hrStatusLabel.uppercase(),
                            fontSize = TextCaption,
                            fontWeight = FontWeight.Bold,
                            color = hrStatusColor,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // "Medir Ahora" pill button
                    Box(
                        modifier = Modifier
                            .clip(ShapePill)
                            .background(Color(0x2BFFFFFF))
                            .clickable {
                                onMeasureClick()
                            }
                            .padding(horizontal = 20.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isMeasuring) stringResource(R.string.measuring) else stringResource(R.string.measure_now),
                            fontSize = TextCaption,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // A measurement that produced nothing has to say so: silence
                    // looks like the button did not register the tap.
                    if (measureFailed) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.measure_failed_no_new_reading),
                            fontSize = TextCaption,
                            color = Color(0xFFFFB4AB),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            lineHeight = TextCaption
                        )
                    }
                }
            }

            // =========================================================================
            // c) Card: Rango Diario (Min - Max BPM)
            // =========================================================================
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapeCard)
                        .background(CardBg)
                        .border(SpecularBorderCard, ShapeCard)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(HrMinMaxPink)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.daily_range),
                                    fontSize = TextBody,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    lineHeight = TextBody,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(ShapeChip)
                                    .background(HrMinMaxPink.copy(alpha = 0.18f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (range.isKnown) "$minHr - $maxHr" else "--",
                                    fontSize = TextCaption,
                                    style = TabularNums,
                                    color = HrMinMaxPink,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = if (range.isKnown) "$minHr - $maxHr" else "--",
                                fontSize = TextBig,
                                style = TabularNums,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.bpm_unit),
                                fontSize = TextLabel,
                                style = TabularNums,
                                color = LabelSilver,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (range.isKnown) {
                            if (rangeFill != null) {
                                AnimatedMinMaxBar(
                                    progress = rangeFill,
                                    color = HrMinMaxPink,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.5.dp)
                                )

                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(minHrText, fontSize = TextCaption, style = TabularNums, color = LabelSilver)
                                Text(maxHrText, fontSize = TextCaption, style = TabularNums, color = LabelSilver)
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.daily_range_pending),
                                fontSize = TextCaption,
                                color = LabelSilver
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // d) Blood Oxygen (SpO2) Card
            // =========================================================================
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapeCard)
                        .background(CardBg)
                        .border(SpecularBorderCard, ShapeCard)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(CyanActive)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.blood_oxygen),
                                    fontSize = TextBody,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    lineHeight = TextBody,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(ShapeChip)
                                    .background(CyanActive.copy(alpha = 0.18f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.optimal).uppercase(),
                                    fontSize = TextCaption,
                                    color = CyanActive,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        val animatedSpo2 by animateIntAsState(
                            targetValue = if (vitals.lastSpo2 > 0) vitals.lastSpo2 else 0,
                            animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
                            label = "spo2Anim"
                        )
                        val spo2Val = if (vitals.lastSpo2 > 0) "$animatedSpo2" else "--"
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = spo2Val,
                                fontSize = TextBig,
                                style = TabularNums,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            if (vitals.lastSpo2 > 0) {
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "%",
                                    fontSize = TextLabel,
                                    color = LabelSilver,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // d2) Physiological Biomarker Strain Radar Card
            // =========================================================================
            item {
                PhysiologicalStrainRadarCard(vitals = vitals)
            }

            // =========================================================================
            // d3) Early Illness & Immune Strain Radar vs 7-Day Baseline (VitalSignsAbnormal)
            // =========================================================================
            item {
                ImmuneEarlyWarningCard(vitals = vitals)
            }

            // =========================================================================
            // e) Telemetry Mini Cards: HRV & Battery (Centered)
            // =========================================================================
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val animatedHrv by animateIntAsState(
                        targetValue = if (vitals.lastHrv > 0) vitals.lastHrv else 0,
                        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
                        label = "hrvAnim"
                    )
                    val hrvVal = if (vitals.lastHrv > 0) "$animatedHrv ms" else "--"
                    val batVal = if (vitals.ringBattery > 0) "${vitals.ringBattery}%" else "--"

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(ShapeCard)
                            .background(CardBg)
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.hrv_short),
                                fontSize = TextCaption,
                                color = LabelSilver,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = hrvVal,
                                fontSize = TextBody,
                                style = TabularNums,
                                color = CyanActive,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(ShapeCard)
                            .background(CardBg)
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.ring_short),
                                fontSize = TextCaption,
                                color = LabelSilver,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = batVal,
                                fontSize = TextBody,
                                style = TabularNums,
                                color = LimeSteps,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // e) Heart Rate Zones: 5 standard One UI zones
            // =========================================================================
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapeCard)
                        .background(CardBg)
                        .border(SpecularBorderCard, ShapeCard)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.hr_zones_title),
                            fontSize = TextBody,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ZoneRow(stringResource(R.string.zone_rest_range), hasHr && currentHr < 100, ZoneRestColor)
                        ZoneRow(stringResource(R.string.zone_warmup_range), hasHr && currentHr in 100..120, ZoneWarmupColor)
                        ZoneRow(stringResource(R.string.zone_fat_burn_range), hasHr && currentHr in 121..140, ZoneFatBurnColor)
                        ZoneRow(stringResource(R.string.zone_cardio_range), hasHr && currentHr in 141..160, ZoneCardioColor)
                        ZoneRow(stringResource(R.string.zone_peak_range), hasHr && currentHr > 160, ZonePeakColor)
                    }
                }
            }

            // =========================================================================
            // f) RingConn Insights Card
            // =========================================================================
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapeCard)
                        .background(CardBg)
                        .border(SpecularBorderCard, ShapeCard)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF47CDFF))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.ringconn_insights),
                                fontSize = TextBody,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        val computedInsight = when {
                            !hasHr -> stringResource(R.string.vitals_insight_no_data)
                            currentHr > 100 -> stringResource(R.string.vitals_insight_elevated)
                            else -> stringResource(R.string.vitals_insight_stable)
                        }
                        Text(
                            text = if (vitals.vitalsInsight.isNotBlank()) vitals.vitalsInsight else computedInsight,
                            fontSize = TextCaption,
                            color = Color(0xFFCFD8DC),
                            lineHeight = 14.sp
                        )
                    }
                }
            }

            // =========================================================================
            // g) 7-Day Trend Cards: Readiness & HRV Recovery
            // =========================================================================
            item {
                RingConnHistoryStore.SevenDayTrendCard(
                    metricTitle = "Readiness Trend",
                    unit = "pts",
                    accentColor = Color(0xFF00E676),
                    selector = { it.readinessScore.toFloat() },
                    currentFallback = RingConnHistoryStore.computeReadinessScore(vitals).toFloat()
                )
            }

            item {
                RingConnHistoryStore.SevenDayTrendCard(
                    metricTitle = "HRV Recovery Trend",
                    unit = "ms",
                    accentColor = CyanActive,
                    selector = { it.hrv.toFloat() },
                    currentFallback = vitals.lastHrv.toFloat()
                )
            }
        }
        }
    }
}

private data class OxygenBubble(
    val xNorm: Float,
    val yNorm: Float,
    val radiusDp: Float,
    val speed: Float,
    val phaseOffset: Float,
    val isAqua: Boolean
)

/**
 * Full-Screen Bioluminescent Oxygen & Readiness Field Backdrop.
 * Placed behind ScalingLazyColumn across Modifier.fillMaxSize(), featuring
 * rising cyan/aqua oxygen micro-bubbles, counter-rotating orbital energy rings
 * with glowing nodes near the bezel, and a breathing cyan-emerald aurora
 * across the full screen with scroll parallax. Gated on Lifecycle.State.RESUMED.
 */
@Composable
private fun FullScreenVitalsBackdrop(
    listState: ScalingLazyListState,
    spo2: Int,
    readinessScore: Int,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val isResumed = lifecycleState == Lifecycle.State.RESUMED

    val transition = rememberInfiniteTransition(label = "vitalsField")

    val bubblePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "bubblePhase"
    )

    val orbitAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbitAngle"
    )

    val auroraBreath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "auroraBreath"
    )

    val bubbles = remember {
        listOf(
            OxygenBubble(0.12f, 0.88f, 1.4f, 0.75f, 0.1f, true),
            OxygenBubble(0.85f, 0.92f, 1.8f, 0.95f, 0.35f, false),
            OxygenBubble(0.24f, 0.75f, 1.2f, 0.65f, 0.6f, true),
            OxygenBubble(0.78f, 0.70f, 1.6f, 0.85f, 0.85f, false),
            OxygenBubble(0.46f, 0.95f, 2.2f, 1.15f, 0.2f, true),
            OxygenBubble(0.18f, 0.50f, 1.3f, 0.7f, 0.45f, false),
            OxygenBubble(0.90f, 0.45f, 1.5f, 0.8f, 0.7f, true),
            OxygenBubble(0.32f, 0.35f, 1.1f, 0.6f, 0.15f, false),
            OxygenBubble(0.68f, 0.30f, 1.7f, 0.85f, 0.9f, true),
            OxygenBubble(0.20f, 0.60f, 1.4f, 0.75f, 0.5f, false),
            OxygenBubble(0.82f, 0.55f, 1.2f, 0.65f, 0.3f, true),
            OxygenBubble(0.40f, 0.65f, 1.9f, 1.0f, 0.75f, false),
            OxygenBubble(0.60f, 0.80f, 1.3f, 0.7f, 0.4f, true),
            OxygenBubble(0.08f, 0.82f, 1.5f, 0.85f, 0.05f, false),
            OxygenBubble(0.92f, 0.85f, 1.2f, 0.65f, 0.65f, true),
            OxygenBubble(0.52f, 0.22f, 2.0f, 1.1f, 0.25f, false),
            OxygenBubble(0.28f, 0.18f, 1.0f, 0.55f, 0.8f, true),
            OxygenBubble(0.72f, 0.15f, 1.4f, 0.75f, 0.55f, false),
            OxygenBubble(0.05f, 0.40f, 1.1f, 0.6f, 0.38f, true),
            OxygenBubble(0.95f, 0.38f, 1.3f, 0.7f, 0.88f, false)
        )
    }

    val cyanColor = remember { Color(0xFF00E5FF) }
    val aquaColor = remember { Color(0xFF38BDF8) }
    val emeraldColor = remember { Color(0xFF00E676) }

    val scrollParallax = (listState.centerItemIndex * 40f + listState.centerItemScrollOffset * 0.25f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val parallaxY = -(scrollParallax % h)
        val center = Offset(w / 2f, h / 2f)

        // 1. Breathing Cyan-Emerald Bioluminescent Aurora
        val breathAlpha = if (isResumed) {
            0.10f + 0.07f * kotlin.math.sin(auroraBreath * 2.0 * Math.PI).toFloat()
        } else {
            0.12f
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    cyanColor.copy(alpha = breathAlpha * 1.2f),
                    emeraldColor.copy(alpha = breathAlpha * 0.7f),
                    Color.Transparent
                ),
                center = Offset(w * 0.48f, h * 0.32f + parallaxY * 0.25f),
                radius = w * 0.68f
            ),
            radius = w * 0.68f,
            center = Offset(w * 0.48f, h * 0.32f + parallaxY * 0.25f)
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    emeraldColor.copy(alpha = breathAlpha * 0.8f),
                    Color(0xFF0F3E38).copy(alpha = 0.05f),
                    Color.Transparent
                ),
                center = Offset(w * 0.65f, h * 0.72f + parallaxY * 0.25f),
                radius = w * 0.55f
            ),
            radius = w * 0.55f,
            center = Offset(w * 0.65f, h * 0.72f + parallaxY * 0.25f)
        )

        // 2. Counter-Rotating Orbital Energy Rings near Bezel
        val outerRadius = (w / 2f) * 0.94f
        val innerRadius = (w / 2f) * 0.88f

        drawCircle(
            color = cyanColor.copy(alpha = 0.12f),
            radius = outerRadius,
            center = center,
            style = Stroke(width = 0.8.dp.toPx())
        )

        drawCircle(
            color = emeraldColor.copy(alpha = 0.10f),
            radius = innerRadius,
            center = center,
            style = Stroke(width = 0.8.dp.toPx())
        )

        val curOrbit = if (isResumed) orbitAngle else 45f

        for (i in 0 until 3) {
            val angleDeg = -curOrbit + i * 120f
            val rad = Math.toRadians(angleDeg.toDouble())
            val nx = center.x + outerRadius * kotlin.math.cos(rad).toFloat()
            val ny = center.y + outerRadius * kotlin.math.sin(rad).toFloat()

            drawCircle(
                color = cyanColor.copy(alpha = 0.20f),
                radius = 3.5.dp.toPx(),
                center = Offset(nx, ny)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = 1.3.dp.toPx(),
                center = Offset(nx, ny)
            )
        }

        for (i in 0 until 3) {
            val angleDeg = (curOrbit * 0.75f) + i * 120f + 60f
            val rad = Math.toRadians(angleDeg.toDouble())
            val nx = center.x + innerRadius * kotlin.math.cos(rad).toFloat()
            val ny = center.y + innerRadius * kotlin.math.sin(rad).toFloat()

            drawCircle(
                color = emeraldColor.copy(alpha = 0.18f),
                radius = 3.2.dp.toPx(),
                center = Offset(nx, ny)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.80f),
                radius = 1.2.dp.toPx(),
                center = Offset(nx, ny)
            )
        }

        // 3. Rising Cyan/Aqua Oxygen Micro-Bubbles with scroll parallax
        for (bubble in bubbles) {
            val driftPhase = if (isResumed) bubblePhase else 0.5f
            val rawCy = bubble.yNorm * h - driftPhase * h * bubble.speed + parallaxY * 0.35f
            val cy = ((rawCy % h) + h) % h
            val swayX = kotlin.math.sin((driftPhase + bubble.phaseOffset) * 2.0 * Math.PI).toFloat() * 7.dp.toPx()
            val cx = (bubble.xNorm * w + swayX).coerceIn(4.dp.toPx(), w - 4.dp.toPx())

            val alpha = if (isResumed) {
                val cycle = ((driftPhase + bubble.phaseOffset) % 1.0f)
                val sinVal = kotlin.math.sin(cycle * 2.0 * Math.PI).toFloat()
                0.25f + 0.35f * (sinVal + 1f)
            } else {
                0.35f
            }

            val baseColor = if (bubble.isAqua) aquaColor else cyanColor
            val rPx = bubble.radiusDp.dp.toPx()

            drawCircle(
                color = baseColor.copy(alpha = alpha),
                radius = rPx,
                center = Offset(cx, cy),
                style = Stroke(width = 0.9.dp.toPx())
            )

            drawCircle(
                color = Color.White.copy(alpha = (alpha * 0.9f).coerceIn(0f, 1f)),
                radius = rPx * 0.35f,
                center = Offset(cx - rPx * 0.3f, cy - rPx * 0.3f)
            )
        }
    }
}

/**
 * Isolated pulsing heart badge with graphicsLayer scale animation and physiological lub-dub pulse.
 */
@Composable
private fun PulsingHeartBadge(isMeasuring: Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val isResumed = lifecycleState == Lifecycle.State.RESUMED

    val cycleDurationMs = if (isMeasuring) 600 else 1000
    val infiniteTransition = rememberInfiniteTransition(label = "hr_pulse")
    val pulseScale = infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = cycleDurationMs
                1.0f at 0
                1.20f at (cycleDurationMs * 0.18f).toInt()
                0.96f at (cycleDurationMs * 0.32f).toInt()
                1.12f at (cycleDurationMs * 0.48f).toInt()
                1.0f at (cycleDurationMs * 0.65f).toInt()
                1.0f at cycleDurationMs
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )

    Box(
        modifier = Modifier
            .size(32.dp)
            .graphicsLayer {
                val scale = if (isResumed) pulseScale.value else 1.0f
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(Color(0x33FF5D99)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_vitals_hr),
            contentDescription = "HR Icon",
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun PulsingHeartIcon(isMeasuring: Boolean) = PulsingHeartBadge(isMeasuring)

/**
 * Min-Max progress bar animated via Canvas draw phase.
 */
@Composable
private fun AnimatedMinMaxBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        anim.animateTo(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = tween(
                durationMillis = 350,
                easing = CubicBezierEasing(0.22f, 0.25f, 0.0f, 1.0f)
            )
        )
    }

    Canvas(modifier = modifier) {
        val corner = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = Color(0x33FFFFFF), size = size, cornerRadius = corner)
        // A fill of zero width would be invisible, so the smallest mark is one cap
        // wide: a reading at the very bottom of the range still shows where it is.
        val barW = (size.width * anim.value.coerceIn(0f, 1f)).coerceAtLeast(size.height)
            .coerceAtMost(size.width)
        drawRoundRect(color = color, size = Size(barW, size.height), cornerRadius = corner)
    }
}

@Composable
private fun ZoneRow(label: String, isActive: Boolean, activeColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (isActive) activeColor else Color(0x33FFFFFF))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = TextCaption,
            color = if (isActive) activeColor else LabelSilver,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * 270-degree animated circular gauge for Heart Rate in One UI Watch style.
 * Hoists Brush and Stroke outside Canvas to prevent per-frame allocations during animation.
 */
@Composable
private fun HeartDialGauge(
    bpm: Int,
    modifier: Modifier = Modifier
) {
    val animProgress = remember { Animatable(0f) }
    val targetProgress = if (bpm > 0) ((bpm - 40f) / 140f).coerceIn(0.05f, 1f) else 0f

    LaunchedEffect(targetProgress) {
        animProgress.animateTo(
            targetValue = targetProgress,
            animationSpec = tween(
                durationMillis = 650,
                easing = CubicBezierEasing(0.22f, 0.25f, 0f, 1f)
            )
        )
    }

    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 7.5.dp.toPx() } }
    val arcStroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
    }
    val gradientBrush = remember {
        Brush.sweepGradient(
            listOf(
                Color(0xFFFF2D55),
                Color(0xFFFF6482),
                Color(0xFFFF9500),
                Color(0xFFFF2D55)
            )
        )
    }

    Canvas(modifier = modifier) {
        val diameter = size.minDimension - strokeWidthPx
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)

        // Background track (270 degrees from 135 to 405)
        drawArc(
            color = Color(0x22FF2D55),
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = arcStroke
        )

        // Progress Arc
        if (animProgress.value > 0f) {
            val sweep = 270f * animProgress.value
            drawArc(
                brush = gradientBrush,
                startAngle = 135f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = arcStroke
            )
        }
    }
}

/**
 * Physiological Biomarker Strain Radar Card.
 * Cross-checks 4 real biomarkers:
 * 1) HRV (lastHrv vs avgHrv or >= 40ms)
 * 2) Heart Rate (lastHeartRate vs avgHeartRate)
 * 3) SpO2 (lastSpo2 >= 95%)
 * 4) Skin Temp (abs(skinTempOffset) <= 0.5f)
 * Shows a 4-node status grid + overall status (Optimal Recovery / Moderate Strain / Recovery Alert)
 * and an animated 4-point diamond radar visual.
 */
@Composable
fun PhysiologicalStrainRadarCard(
    vitals: RingConnVitals,
    modifier: Modifier = Modifier
) {
    val hrvOptimal = if (vitals.avgHrv > 0) vitals.lastHrv >= vitals.avgHrv else vitals.lastHrv >= 40
    val hrOptimal = if (vitals.avgHeartRate > 0) {
        vitals.lastHeartRate <= (vitals.avgHeartRate + 5)
    } else {
        vitals.lastHeartRate in 45..85 || vitals.lastHeartRate == 0
    }
    val spo2Optimal = vitals.lastSpo2 >= 95 || vitals.lastSpo2 == 0
    val tempOptimal = abs(vitals.skinTempOffset) <= 0.5f

    val optimalScore = (if (hrvOptimal) 1 else 0) +
                       (if (hrOptimal) 1 else 0) +
                       (if (spo2Optimal) 1 else 0) +
                       (if (tempOptimal) 1 else 0)

    val isSpanish = remember { Locale.getDefault().language.startsWith("es") }
    val (radarStatus, radarColor, radarBg) = when {
        optimalScore >= 3 -> Triple(if (isSpanish) "Óptimo" else "Optimal", Color(0xFF00E676), Color(0x2200E676))
        optimalScore == 2 -> Triple(if (isSpanish) "Moderado" else "Moderate", Color(0xFFFFB703), Color(0x22FFB703))
        else -> Triple(if (isSpanish) "Alerta" else "Alert", Color(0xFFFF5D99), Color(0x22FF5D99))
    }

    val density = LocalDensity.current
    val radarStroke = remember(density) { Stroke(width = with(density) { 1.5.dp.toPx() }) }
    val axisStroke = remember(density) { Stroke(width = with(density) { 0.75.dp.toPx() }) }
    val radarBrush = remember(radarColor) { SolidColor(radarColor) }
    val radarFillBrush = remember(radarColor) { SolidColor(radarColor.copy(alpha = 0.25f)) }
    val radarPath = remember { androidx.compose.ui.graphics.Path() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShapeCard)
            .background(CardBg)
            .border(BorderStroke(1.dp, radarColor.copy(alpha = 0.25f)), ShapeCard)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column {
            // Header Row: Dot + Title & Overall Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(radarColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isSpanish) "Radar Biométrico" else "Strain Radar",
                        fontSize = TextBody,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        lineHeight = TextBody,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(ShapeChip)
                        .background(radarBg)
                        .border(BorderStroke(1.dp, radarColor.copy(alpha = 0.35f)), ShapeChip)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = radarStatus.uppercase(),
                        fontSize = TextCaption,
                        color = radarColor,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Center: Visual 4-Point Radar Polygon + 4-Node Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mini 4-Axis Diamond Radar Visual
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color(0x14FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val maxR = (size.minDimension / 2f) - 4.dp.toPx()

                        // Background Cross Axes
                        drawLine(Color(0x22FFFFFF), Offset(cx, cy - maxR), Offset(cx, cy + maxR), axisStroke.width)
                        drawLine(Color(0x22FFFFFF), Offset(cx - maxR, cy), Offset(cx + maxR, cy), axisStroke.width)
                        drawCircle(Color(0x18FFFFFF), radius = maxR * 0.5f, style = axisStroke)

                        // 4 Normalized Coordinates (Top: HRV, Right: HR, Bottom: SpO2, Left: Temp)
                        val rHrv = if (hrvOptimal) maxR * 0.90f else maxR * 0.40f
                        val rHr = if (hrOptimal) maxR * 0.88f else maxR * 0.38f
                        val rSpo2 = if (spo2Optimal) maxR * 0.92f else maxR * 0.42f
                        val rTemp = if (tempOptimal) maxR * 0.86f else maxR * 0.36f

                        val pTop = Offset(cx, cy - rHrv)
                        val pRight = Offset(cx + rHr, cy)
                        val pBottom = Offset(cx, cy + rSpo2)
                        val pLeft = Offset(cx - rTemp, cy)

                        radarPath.reset()
                        radarPath.moveTo(pTop.x, pTop.y)
                        radarPath.lineTo(pRight.x, pRight.y)
                        radarPath.lineTo(pBottom.x, pBottom.y)
                        radarPath.lineTo(pLeft.x, pLeft.y)
                        radarPath.close()

                        drawPath(path = radarPath, brush = radarFillBrush)
                        drawPath(path = radarPath, brush = radarBrush, style = radarStroke)

                        // Vertex dots
                        drawCircle(radarColor, radius = 2.dp.toPx(), center = pTop)
                        drawCircle(radarColor, radius = 2.dp.toPx(), center = pRight)
                        drawCircle(radarColor, radius = 2.dp.toPx(), center = pBottom)
                        drawCircle(radarColor, radius = 2.dp.toPx(), center = pLeft)
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // 4-Node Status Grid (2x2)
                Column(modifier = Modifier.weight(1f)) {
                    // Row 1: HRV & Heart Rate
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        RadarNodeCell(
                            modifier = Modifier.weight(1f),
                            label = "HRV",
                            value = if (vitals.lastHrv > 0) "${vitals.lastHrv}ms" else "--",
                            status = if (hrvOptimal) (if (isSpanish) "Óptimo" else "Optimal") else (if (isSpanish) "Tenso" else "Strain"),
                            color = if (hrvOptimal) LimeSteps else HrMinMaxPink
                        )
                        RadarNodeCell(
                            modifier = Modifier.weight(1f),
                            label = if (isSpanish) "PULSO" else "HEART",
                            value = if (vitals.lastHeartRate > 0) "${vitals.lastHeartRate}bpm" else "--",
                            status = if (hrOptimal) (if (isSpanish) "Reposo" else "Resting") else (if (isSpanish) "Alto" else "High"),
                            color = if (hrOptimal) ZoneRestColor else HrMinMaxPink
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Row 2: SpO2 & Skin Temp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        RadarNodeCell(
                            modifier = Modifier.weight(1f),
                            label = "SpO2",
                            value = if (vitals.lastSpo2 > 0) "${vitals.lastSpo2}%" else "--",
                            status = if (spo2Optimal) "Normal" else (if (isSpanish) "Bajo" else "Low"),
                            color = if (spo2Optimal) CyanActive else HrMinMaxPink
                        )
                        val hasTemp = vitals.skinTempBaseline > 0f || vitals.skinTempOffset != 0f
                        val signStr = if (vitals.skinTempOffset > 0f) "+" else ""
                        val tempVal = if (hasTemp) String.format(Locale.US, "%s%.1f°", signStr, vitals.skinTempOffset) else "--"
                        RadarNodeCell(
                            modifier = Modifier.weight(1f),
                            label = "TEMP",
                            value = tempVal,
                            status = if (!hasTemp) "--" else if (tempOptimal) "Normal" else (if (isSpanish) "Desvío" else "Offset"),
                            color = if (tempOptimal) Color(0xFF68D391) else ZoneFatBurnColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RadarNodeCell(
    label: String,
    value: String,
    status: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(ShapeChip)
            .background(Color(0xFF22262C))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = label,
                    fontSize = 9.sp,
                    color = LabelSilver,
                    fontWeight = FontWeight.Medium
                )
                Box(
                    modifier = Modifier
                        .size(4.5.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
            Text(
                text = value,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                style = TabularNums,
                maxLines = 1
            )
            Text(
                text = status,
                fontSize = 8.sp,
                color = color,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ImmuneEarlyWarningCard(vitals: RingConnVitals) {
    val context = LocalContext.current
    val isSpanish = remember { Locale.getDefault().language.startsWith("es") }
    val history = remember(vitals) { RingConnHistoryStore.getHistory(context, vitals) }

    val avg7dHrv = remember(history, vitals) {
        val valid = history.map { it.hrv }.filter { it > 0 }
        if (valid.isNotEmpty()) valid.average().toInt() else vitals.avgHrv
    }
    val avg7dRhr = remember(history, vitals) {
        val valid = history.map { it.rhr }.filter { it > 0 }
        if (valid.isNotEmpty()) valid.average().toInt() else vitals.minHeartRate
    }

    val curHrv = if (vitals.lastHrv > 0) vitals.lastHrv else vitals.avgHrv
    val curRhr = if (vitals.minHeartRate > 0) vitals.minHeartRate else vitals.avgHeartRate
    val respRate = vitals.respiratoryRate
    val tempOff = vitals.skinTempOffset

    val tempAlert = tempOff >= 0.4f
    val hrvAlert = curHrv > 0 && avg7dHrv > 0 && curHrv < (avg7dHrv * 0.85f).toInt()
    val rhrAlert = curRhr > 0 && avg7dRhr > 0 && (curRhr - avg7dRhr) >= 5
    val rrAlert = respRate > 0f && respRate >= 17.5f

    val alertCount = (if (tempAlert) 1 else 0) + (if (hrvAlert) 1 else 0) + (if (rhrAlert) 1 else 0) + (if (rrAlert) 1 else 0)

    val (badgeText, accentColor) = when {
        alertCount >= 2 -> Pair(if (isSpanish) "Alerta $alertCount/4" else "Alert $alertCount/4", HrMinMaxPink)
        alertCount == 1 -> Pair(if (isSpanish) "Vigilancia" else "Watch 1/4", ZoneFatBurnColor)
        else -> Pair(if (isSpanish) "Estable 0/4" else "Stable 0/4", LimeSteps)
    }

    val hrvDiff = if (curHrv > 0 && avg7dHrv > 0) curHrv - avg7dHrv else 0
    val rhrDiff = if (curRhr > 0 && avg7dRhr > 0) curRhr - avg7dRhr else 0

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeCard)
            .background(CardBg)
            .border(BorderStroke(1.dp, accentColor.copy(alpha = 0.25f)), ShapeCard)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isSpanish) "Alerta Inmune (7d)" else "Immune Radar (7d)",
                        fontSize = TextBody,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        lineHeight = TextBody,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(ShapeChip)
                        .background(accentColor.copy(alpha = 0.18f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 9.sp,
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RadarNodeCell(
                    modifier = Modifier.weight(1f),
                    label = if (isSpanish) "Δ TEMP" else "Δ TEMP",
                    value = String.format(Locale.US, "%s%.1f°C", if (tempOff >= 0f) "+" else "", tempOff),
                    status = if (tempAlert) (if (isSpanish) "Alza" else "High") else "Normal",
                    color = if (tempAlert) HrMinMaxPink else LimeSteps
                )
                RadarNodeCell(
                    modifier = Modifier.weight(1f),
                    label = "HRV vs 7d",
                    value = if (curHrv > 0 && avg7dHrv > 0) "${if (hrvDiff >= 0) "+" else ""}${hrvDiff}ms" else "--",
                    status = if (hrvAlert) (if (isSpanish) "Caída" else "Drop") else (if (isSpanish) "Estable" else "Stable"),
                    color = if (hrvAlert) HrMinMaxPink else LimeSteps
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RadarNodeCell(
                    modifier = Modifier.weight(1f),
                    label = if (isSpanish) "FC vs 7d" else "RHR vs 7d",
                    value = if (curRhr > 0 && avg7dRhr > 0) "${if (rhrDiff >= 0) "+" else ""}${rhrDiff}lpm" else "--",
                    status = if (rhrAlert) (if (isSpanish) "Subida" else "Elevated") else (if (isSpanish) "Estable" else "Stable"),
                    color = if (rhrAlert) HrMinMaxPink else CyanActive
                )
                RadarNodeCell(
                    modifier = Modifier.weight(1f),
                    label = if (isSpanish) "RESPIRACIÓN" else "BREATH",
                    value = if (respRate > 0f) String.format(Locale.US, "%.1f rpm", respRate) else "--",
                    status = if (respRate <= 0f) "--" else if (rrAlert) (if (isSpanish) "Alta" else "High") else "Normal",
                    color = if (rrAlert) HrMinMaxPink else CyanActive
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isSpanish) {
                    "Cruza temperatura, HRV, pulso basal y respiración contra tu base de 7 días para anticipar fiebre o fatiga. La respiración es la que registra el anillo; si aún no ha enviado ninguna, se muestra --."
                } else {
                    "Cross-checks temp, HRV, RHR, and breath rate vs your 7-day baseline to spot early illness or strain. Breath rate is the one the ring recorded; until it sends one, it shows --."
                },
                fontSize = 9.sp,
                color = LabelSilver,
                lineHeight = 12.sp
            )
        }
    }
}

@Preview(
    device = "id:wearos_small_round",
    showSystemUi = true
)
@Composable
fun VitalsDetailPreview() {
    VitalsDetailContent(
        vitals = RingConnVitals(
            lastHeartRate = 74,
            minHeartRate = 58,
            maxHeartRate = 124,
            lastSpo2 = 98,
            lastHrv = 46,
            stressScore = 26,
            isConnected = true
        ),
        isMeasuring = false,
        onMeasureClick = {}
    )
}
