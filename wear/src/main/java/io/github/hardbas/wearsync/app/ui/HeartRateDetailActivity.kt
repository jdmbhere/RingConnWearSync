package io.github.hardbas.wearsync.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.animation.core.LinearEasing
import androidx.wear.compose.material.*
import com.google.android.gms.wearable.Wearable
import io.github.hardbas.wearsync.app.R
import io.github.hardbas.wearsync.app.complication.RingConnComplicationProvider
import io.github.hardbas.wearsync.app.data.DailyHrRange
import io.github.hardbas.wearsync.app.data.RingConnHistoryStore
import io.github.hardbas.wearsync.app.data.RingConnVitals
import io.github.hardbas.wearsync.app.service.WearDataListenerService
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Specific One UI Watch 8 Heart Rate & HRV Palette
val HrBpmWarm = Color(0xFFFBCEB6)
val CardBg = Color(0xFF16191E).copy(alpha = 0.75f)
val CardBgSubtle = Color(0xFF1E232B).copy(alpha = 0.70f)
val TrackDark = Color(0x22FFFFFF)

// 5 Standard One UI Heart Rate Zones
private val ZoneRestColor = Color(0xFF47CDFF)      // Reposo (< 100 bpm)
private val ZoneWarmupColor = Color(0xFFA0F116)    // Calentamiento (100 - 120 bpm)
private val ZoneFatBurnColor = Color(0xFFFFB703)   // Quema de grasa (120 - 140 bpm)
private val ZoneCardioColor = Color(0xFFFB8500)    // Cardio (140 - 160 bpm)
private val ZonePeakColor = Color(0xFFFF5D99)      // Pico (> 160 bpm)

private val VitalsHrBrush = Brush.verticalGradient(listOf(HrGradientStart, HrGradientEnd))

/**
 * Dedicated Heart Rate & HRV Insights Detail Screen for Samsung One UI Watch 8.
 * Provides deep insights into cardiac rhythm, autonomic balance (HRV RMSSD),
 * resting heart rate baseline comparison, and 5 standard One UI HR zones.
 */
class HeartRateDetailActivity : ComponentActivity() {
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
            HeartRateDetailScreen(this, triggerMeasureOnStart)
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
        return className.contains("Measure") ||
                intent.getBooleanExtra("start_measure", false) ||
                intent.action == "io.github.hardbas.wearsync.app.ACTION_MEASURE_HR"
    }
}

@Composable
fun HeartRateDetailScreen(context: Context, startMeasureDirectly: Boolean = false) {
    val scope = rememberCoroutineScope()
    var vitals by remember { mutableStateOf(RingConnComplicationProvider.getLatestVitals(context)) }
    var isMeasuring by remember { mutableStateOf(false) }

    // Live sync with SharedPreferences cache
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

    // Auto-trigger measurement if launched via tile "Medir" button
    LaunchedEffect(startMeasureDirectly) {
        if (startMeasureDirectly && !isMeasuring) {
            isMeasuring = true
            delay(3500L)
            val current = RingConnComplicationProvider.getLatestVitals(context)
            if (current.lastHeartRate > 0) {
                // The day's range is widened by the save funnel from every reading
                // it receives, so this path only reports the reading it has.
                val updated = current.copy(
                    refreshTimestamp = System.currentTimeMillis() / 1000L
                )
                RingConnComplicationProvider.saveAndBroadcastVitals(context, updated)
            }
            isMeasuring = false
        }
    }

    HeartRateDetailContent(
        vitals = vitals,
        isMeasuring = isMeasuring,
        onMeasureClick = {
            if (!isMeasuring) {
                isMeasuring = true
                scope.launch {
                    val startSync = WearDataListenerService.lastSyncCompletedMs.value
                    try {
                        val nodes = Wearable.getNodeClient(context).connectedNodes
                        nodes.addOnSuccessListener { nodeList ->
                            nodeList.forEach { node ->
                                Wearable.getMessageClient(context).sendMessage(
                                    node.id,
                                    "/action/sync_ringconn",
                                    ByteArray(0)
                                )
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    withTimeoutOrNull(8500L) {
                        WearDataListenerService.lastSyncCompletedMs.first { it != startSync }
                    }
                    val current = RingConnComplicationProvider.getLatestVitals(context)
                    if (current.lastHeartRate > 0) {
                        // Same rule as above: the reading travels, the funnel widens
                        // the day's range with it.
                        val updated = current.copy(
                            refreshTimestamp = System.currentTimeMillis() / 1000L
                        )
                        RingConnComplicationProvider.saveAndBroadcastVitals(context, updated)
                    }
                    isMeasuring = false
                }
            }
        }
    )
}

@Composable
fun HeartRateDetailContent(
    vitals: RingConnVitals,
    isMeasuring: Boolean = false,
    onMeasureClick: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val currentHr = vitals.lastHeartRate
    val hasHr = currentHr > 0
    val animatedHr = animateIntAsState(
        targetValue = if (hasHr) currentHr else 0,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "heroHrCountUp"
    )
    val hrDisplay = if (hasHr) "${animatedHr.value}" else "--"
    val pulseState = rememberHeartbeatPulse(bpm = currentHr, isMeasuring = isMeasuring)

    val avgHr = vitals.avgHeartRate
    val minHr = vitals.minHeartRate
    val maxHr = vitals.maxHeartRate
    // The model, not three loose numbers: null is a position that was never
    // measured, so the bar has nothing to draw until the day has a range.
    val range = DailyHrRange(minHr, maxHr)
    val rangeFill = range.positionOf(currentHr)
    val minHrText = if (minHr > 0) stringResource(R.string.min_hr_format, minHr) else "--"
    val maxHrText = if (maxHr > 0) stringResource(R.string.max_hr_format, maxHr) else "--"

    val hrvVal = vitals.lastHrv
    val hasHrv = hrvVal > 0
    val hrvDisplay = if (hasHrv) "$hrvVal" else "--"

    // Status evaluation
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

    // HRV status evaluation
    val hrvStatusLabel = when {
        !hasHrv -> "--"
        hrvVal >= 60 -> stringResource(R.string.status_optimal)
        hrvVal >= 40 -> stringResource(R.string.status_balanced)
        else -> stringResource(R.string.status_fatigue)
    }
    val hrvStatusColor = when {
        !hasHrv -> LabelSilver
        hrvVal >= 60 -> LimeSteps
        hrvVal >= 40 -> CyanActive
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
            FullScreenHeartRateBackdrop(
                listState = listState,
                bpm = currentHr,
                isMeasuring = isMeasuring,
                hrStatusColor = hrStatusColor,
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
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
            state = listState,
            autoCentering = null,
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
                        text = stringResource(R.string.tile_heart_rate_title),
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
            // b) Hero: Centered Heart Dial (122dp), live BPM, status pill & Measure button
            // =========================================================================
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val auraBrush = remember(hrStatusColor) {
                        Brush.radialGradient(
                            colors = listOf(
                                hrStatusColor.copy(alpha = 0.28f),
                                hrStatusColor.copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(126.dp)
                    ) {
                        // Lifecycle-gated Biometric Reactive Aura breathing behind hero dial in sync with BPM pulse
                        Box(
                            modifier = Modifier
                                .size(126.dp)
                                .graphicsLayer {
                                    val scale = pulseState.value
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = (0.40f + (scale - 1.0f) * 2.5f).coerceIn(0f, 1f)
                                }
                                .background(auraBrush, CircleShape)
                        )

                        HeartDialGauge(
                            bpm = currentHr,
                            modifier = Modifier.size(122.dp)
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .graphicsLayer {
                                            scaleX = pulseState.value
                                            scaleY = pulseState.value
                                        }
                                        .clip(CircleShape)
                                        .background(HrMinMaxPink)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.bpm_unit).uppercase(),
                                    fontSize = TextCaption,
                                    color = HrSubtitlePink,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center
                                )
                            }
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
                                // The range is the day's min and max. When the day
                                // has none, the current reading is not a range, so
                                // it stays out of this line instead of standing in
                                // for one.
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

                            // One reading is a range with no span: print the ends,
                            // draw no marker, and claim nothing else.
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
            // c) Insights Card: Ritmo y Reposo
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
                            // Two lines: a card title can wrap, it must not read
                            // as "Rhythm and…" because a value chip took the room.
                            Text(
                                text = stringResource(R.string.rhythm_and_rest),
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
                                .background(hrStatusColor.copy(alpha = 0.18f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = hrStatusLabel.uppercase(),
                                fontSize = TextCaption,
                                style = TabularNums,
                                color = hrStatusColor,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // RHR Comparison Mini Tiles - centered & balanced
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val bpmUnit = stringResource(R.string.bpm_unit)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(ShapeChip)
                                .background(CardBgSubtle)
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    // Row label: it shares the row with a value, so it
                                    // uses the short copy instead of ellipsizing.
                                    text = stringResource(R.string.avg_resting_hr_short),
                                    fontSize = TextCaption,
                                    color = LabelSilver,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val avgHrText = if (avgHr > 0) "$avgHr $bpmUnit" else "--"
                                Text(
                                    text = avgHrText,
                                    fontSize = TextBody,
                                    style = TabularNums,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        val diff = if (hasHr && avgHr > 0) currentHr - avgHr else null
                        val diffText = if (diff != null) (if (diff >= 0) "+$diff $bpmUnit" else "$diff $bpmUnit") else "--"
                        val diffColor = if (diff != null) (if (diff <= 6) CyanActive else HrMinMaxPink) else LabelSilver
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(ShapeChip)
                                .background(CardBgSubtle)
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(R.string.variation),
                                    fontSize = TextCaption,
                                    color = LabelSilver,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = diffText,
                                    fontSize = TextBody,
                                    style = TabularNums,
                                    color = diffColor,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

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
            // d) Variabilidad Cardíaca (HRV / VFC)
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
                                    text = stringResource(R.string.hrv_title),
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
                                    text = "RMSSD",
                                    fontSize = TextCaption,
                                    color = CyanActive,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = hrvDisplay,
                                    fontSize = TextBig,
                                    style = TabularNums,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "ms",
                                    fontSize = TextBody,
                                    color = CyanActive,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(ShapePill)
                                    .background(hrvStatusColor.copy(alpha = 0.22f))
                                    .padding(horizontal = 10.dp, vertical = 3.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = hrvStatusLabel,
                                    fontSize = TextCaption,
                                    style = TabularNums,
                                    color = hrvStatusColor,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Visual HRV status gauge with current dot indicator
                        HrvStatusGauge(
                            hrvValue = hrvVal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // Gauge legends - centered under segments
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "< 40 ms",
                                fontSize = TextCaption,
                                style = TabularNums,
                                color = HrMinMaxPink,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "40 - 60 ms",
                                fontSize = TextCaption,
                                style = TabularNums,
                                color = CyanActive,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "> 60 ms",
                                fontSize = TextCaption,
                                style = TabularNums,
                                color = LimeSteps,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // e) Zonas Cardíacas (Heart Rate Zones): 5 standard One UI zones
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
                    val totalZoneMins = vitals.zone1Mins + vitals.zone2Mins + vitals.zone3Mins + vitals.zone4Mins + vitals.zone5Mins
                    val z1Pct = if (totalZoneMins > 0) vitals.zone1Mins.toFloat() / totalZoneMins.toFloat() else 0f
                    val z2Pct = if (totalZoneMins > 0) vitals.zone2Mins.toFloat() / totalZoneMins.toFloat() else 0f
                    val z3Pct = if (totalZoneMins > 0) vitals.zone3Mins.toFloat() / totalZoneMins.toFloat() else 0f
                    val z4Pct = if (totalZoneMins > 0) vitals.zone4Mins.toFloat() / totalZoneMins.toFloat() else 0f
                    val z5Pct = if (totalZoneMins > 0) vitals.zone5Mins.toFloat() / totalZoneMins.toFloat() else 0f

                    var inspectedZoneIndex by remember { mutableStateOf<Int?>(null) }
                    val haptic = LocalHapticFeedback.current

                    LaunchedEffect(inspectedZoneIndex) {
                        if (inspectedZoneIndex != null) {
                            delay(2500L)
                            inspectedZoneIndex = null
                        }
                    }

                    val zoneColors = listOf(ZoneRestColor, ZoneWarmupColor, ZoneFatBurnColor, ZoneCardioColor, ZonePeakColor)
                    val zonePcts = listOf(z1Pct, z2Pct, z3Pct, z4Pct, z5Pct)
                    val zoneMinsList = listOf(vitals.zone1Mins, vitals.zone2Mins, vitals.zone3Mins, vitals.zone4Mins, vitals.zone5Mins)
                    val zoneNames = listOf(
                        stringResource(R.string.zone_rest),
                        stringResource(R.string.zone_warmup),
                        stringResource(R.string.zone_fat_burn),
                        stringResource(R.string.zone_cardio),
                        stringResource(R.string.zone_peak)
                    )

                    fun resolveZoneFromOffset(offsetX: Float, width: Float) {
                        val fraction = (offsetX / width).coerceIn(0f, 1f)
                        var cumulative = 0f
                        var targetZone = 4
                        if (totalZoneMins > 0) {
                            for (z in 0 until 5) {
                                cumulative += zonePcts[z]
                                if (fraction <= cumulative || z == 4) {
                                    targetZone = z
                                    break
                                }
                            }
                        } else {
                            targetZone = (fraction * 5).toInt().coerceIn(0, 4)
                        }
                        if (targetZone != inspectedZoneIndex) {
                            inspectedZoneIndex = targetZone
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }

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
                                        .background(ZoneCardioColor)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.hr_zones_title),
                                    fontSize = TextBody,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    lineHeight = TextBody,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (inspectedZoneIndex != null) {
                                val selIdx = inspectedZoneIndex!!
                                val selName = zoneNames[selIdx]
                                val selMins = zoneMinsList[selIdx]
                                val selColor = zoneColors[selIdx]
                                val selDuration = formatZoneDuration(selMins, totalZoneMins)

                                Box(
                                    modifier = Modifier
                                        .clip(ShapePill)
                                        .background(selColor.copy(alpha = 0.22f))
                                        .border(BorderStroke(1.dp, selColor.copy(alpha = 0.6f)), ShapePill)
                                        .padding(horizontal = 7.dp, vertical = 2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$selName · $selDuration",
                                        color = selColor,
                                        fontSize = TextCaption,
                                        fontWeight = FontWeight.Bold,
                                        style = TabularNums
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Interactive 5-Zone Distribution Scrubber Bar with tap & drag haptics
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures { offsetPos ->
                                        resolveZoneFromOffset(offsetPos.x, size.width.toFloat())
                                    }
                                }
                                .pointerInput(Unit) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { offsetPos ->
                                            resolveZoneFromOffset(offsetPos.x, size.width.toFloat())
                                        },
                                        onHorizontalDrag = { change, _ ->
                                            change.consume()
                                            resolveZoneFromOffset(change.position.x, size.width.toFloat())
                                        }
                                    )
                                }
                        ) {
                            val totalW = size.width
                            val h = size.height
                            val corner = CornerRadius(h / 2f, h / 2f)

                            drawRoundRect(color = TrackDark, size = size, cornerRadius = corner)

                            var startX = 0f
                            val effectivePcts = if (totalZoneMins > 0) zonePcts else listOf(0.2f, 0.2f, 0.2f, 0.2f, 0.2f)

                            for (z in 0 until 5) {
                                val segW = totalW * effectivePcts[z]
                                val isInspected = (z == inspectedZoneIndex)
                                val color = zoneColors[z]

                                if (segW > 0.5f) {
                                    drawRoundRect(
                                        color = if (isInspected) color else color.copy(alpha = 0.70f),
                                        topLeft = Offset(startX, 0f),
                                        size = Size(segW, h)
                                    )
                                }
                                startX += segW
                            }

                            if (inspectedZoneIndex != null) {
                                var cursorX = 0f
                                for (z in 0..inspectedZoneIndex!!) {
                                    val segW = totalW * effectivePcts[z]
                                    if (z == inspectedZoneIndex) {
                                        cursorX += segW / 2f
                                    } else {
                                        cursorX += segW
                                    }
                                }
                                drawCircle(
                                    color = Color.White,
                                    radius = (h / 2f) + 1.dp.toPx(),
                                    center = Offset(cursorX.coerceIn(h / 2f, totalW - h / 2f), h / 2f)
                                )
                                drawCircle(
                                    color = zoneColors[inspectedZoneIndex!!],
                                    radius = (h / 2f) - 1.dp.toPx(),
                                    center = Offset(cursorX.coerceIn(h / 2f, totalW - h / 2f), h / 2f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val bpmUnit = stringResource(R.string.bpm_unit)

                        // Zone 1: Reposo (< 100 bpm)
                        ZoneItemRow(
                            zoneName = stringResource(R.string.zone_rest),
                            bpmRange = "< 100 $bpmUnit",
                            durationText = formatZoneDuration(vitals.zone1Mins, totalZoneMins),
                            zoneColor = ZoneRestColor,
                            distributionPct = z1Pct,
                            isActive = hasHr && currentHr < 100,
                            isInspected = (inspectedZoneIndex == 0),
                            onClick = {
                                inspectedZoneIndex = 0
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        )

                        // Zone 2: Calentamiento (100 - 120 bpm)
                        ZoneItemRow(
                            zoneName = stringResource(R.string.zone_warmup),
                            bpmRange = "100 - 120 $bpmUnit",
                            durationText = formatZoneDuration(vitals.zone2Mins, totalZoneMins),
                            zoneColor = ZoneWarmupColor,
                            distributionPct = z2Pct,
                            isActive = hasHr && currentHr in 100..120,
                            isInspected = (inspectedZoneIndex == 1),
                            onClick = {
                                inspectedZoneIndex = 1
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        )

                        // Zone 3: Quema de grasa (120 - 140 bpm)
                        ZoneItemRow(
                            zoneName = stringResource(R.string.zone_fat_burn),
                            bpmRange = "121 - 140 $bpmUnit",
                            durationText = formatZoneDuration(vitals.zone3Mins, totalZoneMins),
                            zoneColor = ZoneFatBurnColor,
                            distributionPct = z3Pct,
                            isActive = hasHr && currentHr in 121..140,
                            isInspected = (inspectedZoneIndex == 2),
                            onClick = {
                                inspectedZoneIndex = 2
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        )

                        // Zone 4: Cardio (140 - 160 bpm)
                        ZoneItemRow(
                            zoneName = stringResource(R.string.zone_cardio),
                            bpmRange = "141 - 160 $bpmUnit",
                            durationText = formatZoneDuration(vitals.zone4Mins, totalZoneMins),
                            zoneColor = ZoneCardioColor,
                            distributionPct = z4Pct,
                            isActive = hasHr && currentHr in 141..160,
                            isInspected = (inspectedZoneIndex == 3),
                            onClick = {
                                inspectedZoneIndex = 3
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        )

                        // Zone 5: Pico (> 160 bpm)
                        ZoneItemRow(
                            zoneName = stringResource(R.string.zone_peak),
                            bpmRange = "> 160 $bpmUnit",
                            durationText = formatZoneDuration(vitals.zone5Mins, totalZoneMins),
                            zoneColor = ZonePeakColor,
                            distributionPct = z5Pct,
                            isActive = hasHr && currentHr > 160,
                            isInspected = (inspectedZoneIndex == 4),
                            onClick = {
                                inspectedZoneIndex = 4
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        )
                    }
                }
            }

            // =========================================================================
            // f) 7-Day Trend Sparkline Card (Resting Heart Rate)
            // =========================================================================
            item {
                RingConnHistoryStore.SevenDayTrendCard(
                    metricTitle = stringResource(R.string.avg_resting_hr),
                    unit = stringResource(R.string.bpm_unit),
                    accentColor = HrMinMaxPink,
                    selector = { it.rhr.toFloat() },
                    currentFallback = (if (vitals.minHeartRate > 0) vitals.minHeartRate else if (vitals.avgHeartRate > 0) vitals.avgHeartRate else vitals.lastHeartRate).toFloat()
                )
            }
        }
        }
    }
}

private data class EmberParticle(
    val xNorm: Float,
    val yNorm: Float,
    val radiusDp: Float,
    val speed: Float,
    val phaseOffset: Float,
    val isCrimson: Boolean
)

/**
 * Full-Screen Cardiovascular Pulse Field & Crimson Embers Backdrop.
 * Placed behind ScalingLazyColumn across Modifier.fillMaxSize(), featuring
 * expanding concentric arterial sonar pulse rings synced to BPM, a flowing
 * harmonic ECG/pulse wave ribbon in the background, and rising micro-embers
 * with scroll parallax. Gated on Lifecycle.State.RESUMED (0.0% ambient drain).
 */
@Composable
private fun FullScreenHeartRateBackdrop(
    listState: ScalingLazyListState,
    bpm: Int,
    isMeasuring: Boolean,
    hrStatusColor: Color,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val isResumed = lifecycleState == Lifecycle.State.RESUMED

    val effectiveBpm = (bpm.takeIf { it > 0 } ?: 72).coerceIn(45, 180)
    val periodMs = (60_000 / effectiveBpm)

    val transition = rememberInfiniteTransition(label = "hrPulseField")

    val pulsePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sonarPulsePhase"
    )

    val emberPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "emberDriftPhase"
    )

    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (periodMs * 2).coerceIn(1400, 3200), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ecgWavePhase"
    )

    val emberParticles = remember {
        listOf(
            EmberParticle(0.15f, 0.85f, 1.4f, 0.7f, 0.1f, true),
            EmberParticle(0.82f, 0.90f, 1.8f, 0.9f, 0.35f, false),
            EmberParticle(0.28f, 0.70f, 1.2f, 0.6f, 0.6f, true),
            EmberParticle(0.74f, 0.65f, 1.6f, 0.85f, 0.85f, false),
            EmberParticle(0.48f, 0.95f, 2.0f, 1.1f, 0.2f, true),
            EmberParticle(0.10f, 0.45f, 1.3f, 0.65f, 0.45f, false),
            EmberParticle(0.90f, 0.40f, 1.5f, 0.75f, 0.7f, true),
            EmberParticle(0.35f, 0.30f, 1.1f, 0.55f, 0.15f, false),
            EmberParticle(0.65f, 0.25f, 1.7f, 0.8f, 0.9f, true),
            EmberParticle(0.22f, 0.55f, 1.4f, 0.7f, 0.5f, false),
            EmberParticle(0.80f, 0.50f, 1.2f, 0.65f, 0.3f, true),
            EmberParticle(0.42f, 0.60f, 1.6f, 0.95f, 0.75f, false),
            EmberParticle(0.58f, 0.75f, 1.3f, 0.7f, 0.4f, true),
            EmberParticle(0.08f, 0.80f, 1.5f, 0.85f, 0.05f, false),
            EmberParticle(0.92f, 0.82f, 1.2f, 0.6f, 0.65f, true),
            EmberParticle(0.50f, 0.20f, 1.8f, 1.0f, 0.25f, false),
            EmberParticle(0.30f, 0.15f, 1.0f, 0.5f, 0.8f, true),
            EmberParticle(0.70f, 0.12f, 1.3f, 0.75f, 0.55f, false)
        )
    }

    val crimsonColor = remember { Color(0xFFFF2D55) }
    val terracottaColor = remember { Color(0xFFFB8500) }
    val emberGlowColor = remember { Color(0xFFFBCEB6) }

    val scrollParallax = (listState.centerItemIndex * 40f + listState.centerItemScrollOffset * 0.25f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val parallaxY = -(scrollParallax % h)
        val heroCenter = Offset(w / 2f, (h * 0.38f + parallaxY * 0.2f).coerceIn(h * 0.2f, h * 0.7f))

        // 1. Full-Screen Concentric Arterial Sonar Pulse Rings
        val activeColor = if (bpm > 0) hrStatusColor else crimsonColor
        if (isResumed) {
            for (i in 0 until 3) {
                val ringP = (pulsePhase + i * 0.33f) % 1.0f
                val ringRadius = 55.dp.toPx() + ringP * (w * 0.55f)
                val ringAlpha = (1f - ringP).let { it * it } * 0.24f

                drawCircle(
                    color = activeColor.copy(alpha = ringAlpha),
                    radius = ringRadius,
                    center = heroCenter,
                    style = Stroke(width = 1.4.dp.toPx())
                )
            }
        }

        // 2. Glowing Harmonic ECG / Pulse Wave Ribbon across background
        val waveY = (h * 0.52f + parallaxY * 0.3f)
        val path = androidx.compose.ui.graphics.Path()
        val numPoints = 80
        val stepX = w / numPoints.toFloat()

        for (pt in 0..numPoints) {
            val x = pt * stepX
            val normX = x / w
            val waveT = if (isResumed) ((normX - wavePhase + 1f) % 1f) else normX

            val ecgOffset = when {
                waveT in 0.15f..0.22f -> {
                    val p = (waveT - 0.15f) / 0.07f
                    -kotlin.math.sin(p * Math.PI).toFloat() * 7.dp.toPx()
                }
                waveT in 0.27f..0.33f -> {
                    val qrs = (waveT - 0.27f) / 0.06f
                    when {
                        qrs < 0.25f -> 5.dp.toPx() * (qrs / 0.25f)
                        qrs < 0.65f -> -26.dp.toPx() * (1f - kotlin.math.abs((qrs - 0.45f) / 0.20f))
                        else -> 8.dp.toPx() * (1f - (qrs - 0.65f) / 0.35f)
                    }
                }
                waveT in 0.38f..0.50f -> {
                    val t = (waveT - 0.38f) / 0.12f
                    -kotlin.math.sin(t * Math.PI).toFloat() * 11.dp.toPx()
                }
                else -> {
                    kotlin.math.sin(normX * 6.0 * Math.PI).toFloat() * 1.5.dp.toPx()
                }
            }

            val y = waveY + ecgOffset
            if (pt == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    activeColor.copy(alpha = 0.12f),
                    emberGlowColor.copy(alpha = 0.30f),
                    activeColor.copy(alpha = 0.22f),
                    Color.Transparent
                )
            ),
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        )

        // 3. Rising Crimson/Terracotta Micro-Embers with scroll parallax
        for (ember in emberParticles) {
            val driftPhase = if (isResumed) emberPhase else 0.5f
            val rawCy = ember.yNorm * h - driftPhase * h * ember.speed + parallaxY * 0.35f
            val cy = ((rawCy % h) + h) % h
            val swayX = kotlin.math.sin((driftPhase + ember.phaseOffset) * 2.0 * Math.PI).toFloat() * 8.dp.toPx()
            val cx = (ember.xNorm * w + swayX).coerceIn(4.dp.toPx(), w - 4.dp.toPx())

            val alpha = if (isResumed) {
                val cycle = ((driftPhase + ember.phaseOffset) % 1.0f)
                val sinVal = kotlin.math.sin(cycle * 2.0 * Math.PI).toFloat()
                0.22f + 0.35f * (sinVal + 1f)
            } else {
                0.30f
            }

            val baseColor = if (ember.isCrimson) crimsonColor else terracottaColor
            val rPx = ember.radiusDp.dp.toPx()

            drawCircle(
                color = baseColor.copy(alpha = alpha),
                radius = rPx,
                center = Offset(cx, cy)
            )

            if (rPx >= 1.5.dp.toPx() && alpha > 0.45f) {
                drawCircle(
                    color = emberGlowColor.copy(alpha = (alpha - 0.45f) * 0.4f),
                    radius = rPx * 2.2f,
                    center = Offset(cx, cy)
                )
            }
        }
    }
}

/**
 * Dynamic BPM-synced physiological lub-dub double-beat pulse animation.
 * Cycle duration is calculated from the user's real BPM:
 * periodMs = (60_000 / (bpm.takeIf { it > 0 } ?: 72).coerceIn(45, 180))
 * Gated on Lifecycle.State.RESUMED so it pauses in Ambient Mode.
 */
@Composable
private fun rememberHeartbeatPulse(bpm: Int, isMeasuring: Boolean = false): State<Float> {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isResumed = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val periodMs = remember(bpm, isMeasuring) {
        (60_000 / (bpm.takeIf { it > 0 } ?: 72).coerceIn(45, 180))
    }

    return if (isResumed) {
        val infiniteTransition = rememberInfiniteTransition(label = "hr_lub_dub_pulse")
        val peak1 = if (isMeasuring) 1.28f else 1.20f
        val peak2 = if (isMeasuring) 1.18f else 1.10f
        val s1Peak = (periodMs * 0.15f).roundToInt()
        val s1Relax = (periodMs * 0.30f).roundToInt()
        val s2Peak = (periodMs * 0.45f).roundToInt()
        val s2Relax = (periodMs * 0.60f).roundToInt()

        infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = periodMs
                    1.0f at 0 with FastOutSlowInEasing
                    peak1 at s1Peak with FastOutSlowInEasing
                    1.03f at s1Relax with FastOutSlowInEasing
                    peak2 at s2Peak with FastOutSlowInEasing
                    1.0f at s2Relax with LinearEasing
                    1.0f at periodMs with LinearEasing
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "pulseScale"
        )
    } else {
        remember { mutableFloatStateOf(1.0f) }
    }
}

/**
 * Isolated pulsing heart icon with graphicsLayer scale animation.
 * Zero parent recomposition during animation ticks.
 */
@Composable
private fun PulsingHeartIcon(
    isMeasuring: Boolean = false,
    bpm: Int = 0,
    modifier: Modifier = Modifier
) {
    val pulseState = rememberHeartbeatPulse(bpm = bpm, isMeasuring = isMeasuring)

    Box(
        modifier = modifier
            .size(38.dp)
            .graphicsLayer {
                scaleX = pulseState.value
                scaleY = pulseState.value
            }
            .clip(CircleShape)
            .background(Color(0x22FFFFFF)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_vitals_hr),
            contentDescription = "HR Icon",
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * The day's range as a track, with a fill marking where the latest reading sits.
 *
 * Read-only on purpose: the bar reports what the ring measured, so there is
 * nothing here for a tap or a drag to change. The previous version let the
 * finger move the fill and threw it away after 2.5 s, which read as a slider
 * that answered to nothing the watch knew.
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
        drawRoundRect(color = GlassBlack50, size = size, cornerRadius = corner)
        // A rounded fill of zero width would be invisible, so the minimum mark is
        // one cap wide: the reading at the very bottom of the range still shows
        // where it is instead of looking like an empty bar.
        val barW = (size.width * anim.value.coerceIn(0f, 1f)).coerceAtLeast(size.height)
            .coerceAtMost(size.width)
        drawRoundRect(color = color, size = Size(barW, size.height), cornerRadius = corner)
    }
}

/**
 * Segmented HRV Status Gauge with indicator marker dot executed solely on Canvas.
 */
@Composable
private fun HrvStatusGauge(hrvValue: Int, modifier: Modifier = Modifier) {
    val animPos = remember { Animatable(0.5f) }
    val targetNormalized = ((hrvValue - 20f) / 70f).coerceIn(0.06f, 0.94f)

    LaunchedEffect(targetNormalized) {
        animPos.animateTo(
            targetValue = targetNormalized,
            animationSpec = tween(
                durationMillis = 400,
                easing = CubicBezierEasing(0.22f, 0.25f, 0.0f, 1.0f)
            )
        )
    }

    Canvas(modifier = modifier) {
        val corner = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = TrackDark, size = size, cornerRadius = corner)

        val segW = (size.width - 4f) / 3f
        val segH = size.height

        // Segment 1: Pink (Baja)
        drawRoundRect(
            color = HrMinMaxPink.copy(alpha = 0.85f),
            topLeft = Offset(0f, 0f),
            size = Size(segW, segH),
            cornerRadius = CornerRadius(segH / 2f, segH / 2f)
        )

        // Segment 2: Cyan (Equilibrada)
        drawRoundRect(
            color = CyanActive.copy(alpha = 0.85f),
            topLeft = Offset(segW + 2f, 0f),
            size = Size(segW, segH),
            cornerRadius = CornerRadius(segH / 2f, segH / 2f)
        )

        // Segment 3: Lime (Óptima)
        drawRoundRect(
            color = LimeSteps.copy(alpha = 0.85f),
            topLeft = Offset((segW * 2f) + 4f, 0f),
            size = Size(segW, segH),
            cornerRadius = CornerRadius(segH / 2f, segH / 2f)
        )

        // Current Indicator Needle / Dot
        val indicatorX = size.width * animPos.value
        drawCircle(
            color = Color.Black,
            radius = segH / 2f + 1f,
            center = Offset(indicatorX, segH / 2f)
        )
        drawCircle(
            color = Color.White,
            radius = segH / 2f - 1f,
            center = Offset(indicatorX, segH / 2f)
        )
    }
}

private fun formatZoneDuration(mins: Int, totalMins: Int): String {
    if (totalMins <= 0) return "--"
    if (mins <= 0) return "0m · 0%"
    val pct = (mins.toDouble() / totalMins.toDouble() * 100.0).roundToInt()
    val hours = mins / 60
    val remMins = mins % 60
    val timeStr = when {
        hours > 0 && remMins > 0 -> "${hours}h ${remMins}m"
        hours > 0 -> "${hours}h"
        else -> "${remMins}m"
    }
    return "$timeStr · $pct%"
}

/**
 * Individual Heart Rate Zone Row with visual distribution bar, duration, and active indicator.
 */
@Composable
private fun ZoneItemRow(
    zoneName: String,
    bpmRange: String,
    durationText: String,
    zoneColor: Color,
    distributionPct: Float,
    isActive: Boolean,
    isInspected: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val textColor by animateColorAsState(
        targetValue = if (isActive || isInspected) Color.White else LabelSilver,
        animationSpec = tween(350),
        label = "zoneTextColor"
    )
    val rangeColor by animateColorAsState(
        targetValue = if (isActive || isInspected) zoneColor else PipeGray,
        animationSpec = tween(350),
        label = "zoneRangeColor"
    )
    val rowBgColor by animateColorAsState(
        targetValue = when {
            isInspected -> zoneColor.copy(alpha = 0.28f)
            isActive -> zoneColor.copy(alpha = 0.14f)
            else -> Color.Transparent
        },
        animationSpec = tween(350),
        label = "zoneRowBgColor"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeChip)
            .background(rowBgColor)
            .then(
                if (isInspected) Modifier.border(BorderStroke(1.dp, zoneColor.copy(alpha = 0.65f)), ShapeChip)
                else Modifier
            )
            .then(
                if (onClick != null) Modifier.clickable { onClick() }
                else Modifier
            )
            .padding(horizontal = 6.dp, vertical = 3.5.dp)
    ) {
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
                        .background(zoneColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = zoneName,
                    fontSize = TextLabel,
                    color = textColor,
                    fontWeight = if (isActive || isInspected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = durationText,
                fontSize = TextCaption,
                style = TabularNums,
                color = textColor,
                fontWeight = if (isActive || isInspected) FontWeight.Bold else FontWeight.Normal
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = bpmRange,
                fontSize = TextCaption,
                style = TabularNums,
                color = rangeColor,
                fontWeight = if (isActive || isInspected) FontWeight.SemiBold else FontWeight.Normal
            )
            if (isActive) {
                Box(
                    modifier = Modifier
                        .clip(ShapePill)
                        .background(zoneColor)
                        .padding(horizontal = 6.dp, vertical = 1.5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.current_caps),
                        fontSize = TextCaption,
                        color = Color.Black,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(3.dp))

        // Distribution progress bar
        val animBar = remember { Animatable(0f) }
        LaunchedEffect(distributionPct) {
            animBar.animateTo(
                targetValue = distributionPct,
                animationSpec = tween(
                    durationMillis = 350,
                    easing = CubicBezierEasing(0.22f, 0.25f, 0.0f, 1.0f)
                )
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
        ) {
            val corner = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(color = TrackDark, size = size, cornerRadius = corner)
            val w = size.width * animBar.value
            if (w > 0f) {
                drawRoundRect(
                    color = if (isActive || isInspected) zoneColor else zoneColor.copy(alpha = 0.5f),
                    size = Size(w, size.height),
                    cornerRadius = corner
                )
            }
        }
    }
}

/**
 * 270-degree animated circular gauge for Heart Rate in One UI Watch style.
 */
@Composable
private fun HeartDialGauge(
    bpm: Int,
    modifier: Modifier = Modifier
) {
    val animProgress = remember { Animatable(0f) }
    // Map 40..180 bpm to 0..1f
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

    val strokeWidth = with(LocalDensity.current) { 7.5.dp.toPx() }
    val dialStroke = remember(strokeWidth) {
        Stroke(width = strokeWidth, cap = StrokeCap.Round)
    }
    val sweepBrush = remember {
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
        val diameter = size.minDimension - strokeWidth
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
            style = dialStroke
        )

        // Progress Arc
        if (animProgress.value > 0f) {
            val sweep = 270f * animProgress.value
            drawArc(
                brush = sweepBrush,
                startAngle = 135f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = dialStroke
            )
        }
    }
}

@Preview(
    device = "id:wearos_small_round",
    showSystemUi = true
)
@Composable
fun HeartRateDetailPreview() {
    HeartRateDetailContent(
        vitals = RingConnVitals(
            lastHeartRate = 74,
            minHeartRate = 58,
            maxHeartRate = 124,
            lastHrv = 48,
            lastSpo2 = 98,
            isConnected = true
        ),
        isMeasuring = false,
        onMeasureClick = {}
    )
}

