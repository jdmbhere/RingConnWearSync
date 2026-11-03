package io.github.hardbas.wearsync.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
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
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.google.android.gms.wearable.Wearable
import io.github.hardbas.wearsync.app.R
import io.github.hardbas.wearsync.app.complication.RingConnComplicationProvider
import io.github.hardbas.wearsync.app.data.RingConnHistoryStore
import io.github.hardbas.wearsync.app.data.SleepPhaseHeartRates
import io.github.hardbas.wearsync.app.data.SleepStage
import io.github.hardbas.wearsync.app.data.RingConnVitals
import io.github.hardbas.wearsync.app.service.WearDataListenerService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.roundToInt

// Dark Obsidian Sleep Stage Accent Palette
private val StageAwake = Color(0xFFFF6B6B)     // Despierto
private val StageRem = Color(0xFF47CDFF)       // REM
private val StageLight = Color(0xFFA5AEDE)     // Ligero
private val StageDeep = Color(0xFF5E72E4)      // Profundo
private val SleepTrackBg = Color(0x33000000)

// Clinical & Biometric Status Accents
private val ColorEmerald = Color(0xFF00E676)
private val ColorAmber = Color(0xFFFFB703)
private val ColorCoral = Color(0xFFFF6B6B)
private val ColorCyanRadar = Color(0xFF00E5FF)

class SleepDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        setContent {
            SleepDetailScreen(this)
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
    }
}

@Composable
fun SleepDetailScreen(context: Context) {
    var vitals by remember { mutableStateOf(RingConnComplicationProvider.getLatestVitals(context)) }

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

    SleepDetailContent(
        vitals = vitals,
        onSyncClick = {
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
        }
    )
}

@Composable
fun SleepDetailContent(
    vitals: RingConnVitals,
    onSyncClick: () -> Unit = {}
) {
    val listState = rememberScalingLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val hasSleepData = vitals.sleepScore > 0
    val sleepScore = vitals.sleepScore
    val animatedSleepScore by animateIntAsState(
        targetValue = if (hasSleepData) sleepScore else 0,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "sleepScoreAnim"
    )
    val sleepScoreText = if (hasSleepData) "$animatedSleepScore" else "--"
    val qualityBadge = when {
        !hasSleepData -> stringResource(R.string.no_data)
        sleepScore >= 85 -> stringResource(R.string.excellent)
        sleepScore >= 70 -> stringResource(R.string.good)
        else -> stringResource(R.string.fair)
    }
    val qualityColor = when {
        !hasSleepData -> LabelSilver
        sleepScore >= 85 -> LimeSteps
        sleepScore >= 70 -> StageRem
        else -> Color(0xFFFFD600)
    }

    val totalStagesMins = vitals.sleepDeepMins + vitals.sleepRemMins + vitals.sleepLightMins + vitals.sleepAwakeMins
    val hasStages = totalStagesMins > 0
    val effectiveSleepMins = vitals.sleepDeepMins + vitals.sleepRemMins + vitals.sleepLightMins

    val durationText = if (hasStages) {
        val h = effectiveSleepMins / 60
        val m = effectiveSleepMins % 60
        if (h > 0 && m > 0) "${h}h ${m}m" else if (h > 0) "${h}h" else "${m}m"
    } else if (hasSleepData) {
        "--"
    } else {
        stringResource(R.string.no_sleep_records)
    }

    val bpmUnit = stringResource(R.string.bpm_unit)
    val spo2Val = if (vitals.lastSpo2 > 0) "${vitals.lastSpo2}%" else "--"
    val hrvVal = if (vitals.lastHrv > 0) "${vitals.lastHrv} ms" else "--"
    val minHr = if (vitals.minHeartRate > 0) "${vitals.minHeartRate} $bpmUnit" else if (vitals.lastHeartRate > 0) "${vitals.lastHeartRate} $bpmUnit" else "--"
    val stressVal = if (vitals.stressScore > 0) "${vitals.stressScore}" else "--"

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var selectedStageIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(selectedStageIndex) {
        if (selectedStageIndex != null) {
            delay(4000L)
            selectedStageIndex = null
        }
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
            FullScreenCelestialBackdrop(
                listState = listState,
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
            scalingParams = ScalingLazyColumnDefaults.scalingParams(
                edgeScale = 0.65f,
                edgeAlpha = 0.40f,
                minTransitionArea = 0.20f,
                maxTransitionArea = 0.80f
            ),
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
                        text = stringResource(R.string.sleep_analysis_title),
                        fontSize = TextBody,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.ring_model_subtitle),
                        fontSize = TextCaption,
                        color = SleepPeriwinkle,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // =========================================================================
            // b) Hero Dial: Concentric Sleep Score Gauge with Biometric Reactive Aura and 3D depth
            // =========================================================================
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(134.dp)
                            .graphicsLayer {
                                cameraDistance = 12f * density.density
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        BiometricHeroAura(
                            color = StageDeep,
                            modifier = Modifier.size(134.dp)
                        )
                        SleepDialGauge(
                            score = if (hasSleepData) sleepScore else 0,
                            modifier = Modifier.size(122.dp)
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = sleepScoreText,
                                fontSize = TextHero,
                                style = TabularNums,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = stringResource(R.string.pts_unit).uppercase(),
                                fontSize = TextCaption,
                                color = SleepPeriwinkle,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Quality Badge Chip
                    Box(
                        modifier = Modifier
                            .clip(ShapePill)
                            .background(qualityColor.copy(alpha = 0.18f))
                            .padding(horizontal = 14.dp, vertical = 3.5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = qualityBadge.uppercase(),
                            fontSize = TextCaption,
                            fontWeight = FontWeight.Bold,
                            color = qualityColor,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Total sleep duration pill
                    Box(
                        modifier = Modifier
                            .clip(ShapePill)
                            .background(Color(0x221E2638))
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_vitals_sleep),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.total_sleep_time) + ": ",
                                fontSize = TextCaption,
                                color = SleepPeriwinkle
                            )
                            Text(
                                text = durationText,
                                fontSize = TextCaption,
                                style = TabularNums,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // c) Card 1: Fases del Sueño (Sleep Stages)
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
                                        .background(StageDeep)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.sleep_stages_title),
                                    fontSize = TextBody,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (!hasStages) {
                                Text(
                                    text = stringResource(R.string.no_data),
                                    fontSize = TextCaption,
                                    style = TabularNums,
                                    color = LabelSilver
                                )
                            }
                        }

                        if (hasStages) {
                            Spacer(modifier = Modifier.height(8.dp))

                            val awakePct = vitals.sleepAwakeMins.toFloat() / totalStagesMins.toFloat()
                            val remPct = vitals.sleepRemMins.toFloat() / totalStagesMins.toFloat()
                            val lightPct = vitals.sleepLightMins.toFloat() / totalStagesMins.toFloat()
                            val deepPct = vitals.sleepDeepMins.toFloat() / totalStagesMins.toFloat()

                            val awakeName = stringResource(R.string.stage_awake)
                            val remName = stringResource(R.string.stage_rem)
                            val lightName = stringResource(R.string.stage_light)
                            val deepName = stringResource(R.string.stage_deep)

                            val stages = remember(vitals, totalStagesMins, awakeName, remName, lightName, deepName) {
                                listOf(
                                    SleepStageSegment(0, awakeName, vitals.sleepAwakeMins, awakePct, StageAwake),
                                    SleepStageSegment(1, remName, vitals.sleepRemMins, remPct, StageRem),
                                    SleepStageSegment(2, lightName, vitals.sleepLightMins, lightPct, StageLight),
                                    SleepStageSegment(3, deepName, vitals.sleepDeepMins, deepPct, StageDeep)
                                )
                            }

                            val resolveStageIndex: (Float, Float) -> Int? = { touchX, width ->
                                if (width <= 0f) null
                                else {
                                    val fraction = (touchX / width).coerceIn(0f, 0.999f)
                                    var cumulative = 0f
                                    var resolved: Int? = null
                                    for (stage in stages) {
                                        if (stage.pct <= 0f) continue
                                        if (fraction < cumulative + stage.pct) {
                                            resolved = stage.index
                                            break
                                        }
                                        cumulative += stage.pct
                                    }
                                    resolved ?: stages.lastOrNull { it.pct > 0f }?.index
                                }
                            }

                            val selectedStage = stages.find { it.index == selectedStageIndex }

                            // Crisp inline pill badge showing selected stage name, duration, and percentage
                            if (selectedStage != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(ShapePill)
                                        .background(selectedStage.color.copy(alpha = 0.22f))
                                        .border(BorderStroke(1.dp, selectedStage.color.copy(alpha = 0.65f)), ShapePill)
                                        .padding(horizontal = 10.dp, vertical = 3.5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(selectedStage.color)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = selectedStage.name.uppercase(),
                                            fontSize = TextCaption,
                                            fontWeight = FontWeight.Bold,
                                            color = selectedStage.color
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "·  ${formatMins(selectedStage.durationMins)} (${(selectedStage.pct * 100).roundToInt()}%)",
                                            fontSize = TextCaption,
                                            style = TabularNums,
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                            }

                            // Interactive multi-stage scrubber bar with haptics
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pointerInput(stages) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val hit = resolveStageIndex(down.position.x, size.width.toFloat())
                                            if (hit != null && hit != selectedStageIndex) {
                                                selectedStageIndex = hit
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            val pointerId = down.id
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                                if (!change.pressed) break
                                                val dragHit = resolveStageIndex(change.position.x, size.width.toFloat())
                                                if (dragHit != null && dragHit != selectedStageIndex) {
                                                    selectedStageIndex = dragHit
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                                change.consume()
                                            }
                                        }
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                SleepStagesBar(
                                    awakePct = awakePct,
                                    remPct = remPct,
                                    lightPct = lightPct,
                                    deepPct = deepPct,
                                    selectedStageIndex = selectedStageIndex,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(10.dp)
                                        .clip(ShapeBar)
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Detailed stage breakdowns with interactive row tap selection
                            SleepStageRow(
                                name = awakeName,
                                duration = formatMins(vitals.sleepAwakeMins),
                                percentage = "${(awakePct * 100).roundToInt()}%",
                                dotColor = StageAwake,
                                isSelected = (selectedStageIndex == 0),
                                onClick = {
                                    selectedStageIndex = if (selectedStageIndex == 0) null else 0
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            )
                            SleepStageRow(
                                name = remName,
                                duration = formatMins(vitals.sleepRemMins),
                                percentage = "${(remPct * 100).roundToInt()}%",
                                dotColor = StageRem,
                                isSelected = (selectedStageIndex == 1),
                                onClick = {
                                    selectedStageIndex = if (selectedStageIndex == 1) null else 1
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            )
                            SleepStageRow(
                                name = lightName,
                                duration = formatMins(vitals.sleepLightMins),
                                percentage = "${(lightPct * 100).roundToInt()}%",
                                dotColor = StageLight,
                                isSelected = (selectedStageIndex == 2),
                                onClick = {
                                    selectedStageIndex = if (selectedStageIndex == 2) null else 2
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            )
                            SleepStageRow(
                                name = deepName,
                                duration = formatMins(vitals.sleepDeepMins),
                                percentage = "${(deepPct * 100).roundToInt()}%",
                                dotColor = StageDeep,
                                isSelected = (selectedStageIndex == 3),
                                onClick = {
                                    selectedStageIndex = if (selectedStageIndex == 3) null else 3
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            )
                        } else {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.no_stages_detected),
                                fontSize = TextLabel,
                                color = LabelSilver,
                                lineHeight = TextBody
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // c2) Card: Heart Rate per Sleep Stage & Bed Efficiency (SleepSyncModel)
            // =========================================================================
            item {
                val isSpanish = remember { Locale.getDefault().language.startsWith("es") }
                val effPct = vitals.sleepEfficiencyPct
                // A stage without a measured heart rate is unknown only until the first
                // measured night, so it is left out instead of printed as a placeholder.
                val measuredPhases = remember(vitals) { SleepPhaseHeartRates.measured(vitals) }
                val deepHr = vitals.hrPhaseDeep
                val lightHr = vitals.hrPhaseLight
                val deepIsLowest = deepHr > 0 && (lightHr == 0 || deepHr <= lightHr)
                val effColor = if (effPct >= 85) ColorEmerald else ColorAmber
                val hasPhases = measuredPhases.isNotEmpty()
                // Efficiency describes a real night's stages; without one there is no chip to
                // show, because a "--%" there would read as an unsynced phone.
                val showsEfficiency = effPct > 0

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
                                        .background(StageDeep)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isSpanish) "Pulso por Fase" else "Phase Heart Rate",
                                    fontSize = TextBody,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    lineHeight = TextBody,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (showsEfficiency) {
                                Box(
                                    modifier = Modifier
                                        .clip(ShapeChip)
                                        .background(effColor.copy(alpha = 0.18f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (isSpanish) "Efic. $effPct% · EST" else "Eff. $effPct% · EST",
                                        fontSize = 9.sp,
                                        style = TabularNums,
                                        color = effColor,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        if (hasPhases) {
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                measuredPhases.forEach { reading ->
                                    PhaseHeartRateCell(
                                        phase = reading.phase,
                                        bpm = reading.bpm,
                                        isSpanish = isSpanish,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                        } else {
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        Text(
                            text = if (!hasPhases && !showsEfficiency) {
                                stringResource(R.string.no_data)
                            } else if (isSpanish) {
                                if (deepIsLowest) {
                                    if (showsEfficiency) "Óptimo: Tu pulso mínimo ocurrió en Sueño Profundo. Eficiencia real en cama: $effPct%."
                                    else "Óptimo: Tu pulso mínimo ocurrió en Sueño Profundo."
                                } else {
                                    "El pulso ideal debe ser más bajo en Profundo que en Ligero y REM."
                                }
                            } else {
                                if (deepIsLowest) {
                                    if (showsEfficiency) "Optimal: Lowest HR reached during Deep Sleep. Bed efficiency: $effPct%."
                                    else "Optimal: Lowest HR reached during Deep Sleep."
                                } else {
                                    "Ideal nocturnal HR should reach its minimum in Deep Sleep."
                                }
                            },
                            fontSize = 9.sp,
                            color = LabelSilver,
                            lineHeight = 12.sp
                        )
                    }
                }
            }

            // =========================================================================
            // d) Card: Nocturnal Respiratory & Apnea ODI Radar (AHISpo2Data & CalSleepHeartRateData)
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
                                    .background(ColorCyanRadar)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.nocturnal_spo2_title),
                                fontSize = TextBody,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val minSpo2Text = if (vitals.minSleepingSpo2 > 0) "${vitals.minSleepingSpo2}%" else "--"
                        val minSpo2Status = when {
                            vitals.minSleepingSpo2 <= 0 -> stringResource(R.string.no_data)
                            vitals.minSleepingSpo2 >= 94 -> stringResource(R.string.spo2_stable)
                            else -> stringResource(R.string.spo2_unstable)
                        }
                        val minSpo2Color = when {
                            vitals.minSleepingSpo2 <= 0 -> LabelSilver
                            vitals.minSleepingSpo2 >= 94 -> ColorEmerald
                            else -> ColorCoral
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Min Sleeping SpO2 Box
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(ShapeChip)
                                    .background(CardBgSubtle)
                                    .padding(horizontal = 6.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = stringResource(R.string.min_sleeping_spo2),
                                        fontSize = TextCaption,
                                        color = LabelSilver,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = minSpo2Text,
                                        fontSize = TextBody,
                                        style = TabularNums,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(ShapePill)
                                            .background(minSpo2Color.copy(alpha = 0.16f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = minSpo2Status,
                                            fontSize = 9.sp,
                                            color = minSpo2Color,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Nocturnal HR Dip Section
                        val hrDipHasValue = hasSleepData && vitals.sleepingHrDipPct != 0f
                        val hrDipStatus = when {
                            !hrDipHasValue -> stringResource(R.string.no_data)
                            vitals.sleepingHrDipPct < 10.0f -> stringResource(R.string.hr_dip_reduced)
                            vitals.sleepingHrDipPct <= 20.0f -> stringResource(R.string.hr_dip_optimal)
                            else -> stringResource(R.string.hr_dip_extreme)
                        }
                        val hrDipColor = when {
                            !hrDipHasValue -> LabelSilver
                            vitals.sleepingHrDipPct < 10.0f -> ColorAmber
                            vitals.sleepingHrDipPct <= 20.0f -> ColorEmerald
                            else -> Color(0xFFB388FF)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.sleeping_hr_dip),
                                fontSize = TextCaption,
                                color = LabelSilver,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (hrDipHasValue) String.format(Locale.US, "%.1f%%", vitals.sleepingHrDipPct) else "--",
                                fontSize = TextCaption,
                                style = TabularNums,
                                color = hrDipColor,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        NocturnalDipGauge(
                            dipPct = if (hrDipHasValue) vitals.sleepingHrDipPct else 0f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(ShapeBar)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = hrDipStatus,
                                fontSize = TextCaption,
                                color = hrDipColor,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.hr_dip_optimal_range),
                                fontSize = TextCaption,
                                color = LabelSilver
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = stringResource(R.string.nocturnal_spo2_note),
                            fontSize = 9.sp,
                            color = LabelSilver,
                            lineHeight = 12.sp
                        )
                    }
                }
            }

            // =========================================================================
            // e) Card: Sleep Debt, Daytime Naps & Circadian Bedtime Coach (BedScheduleModel & SleepNapModel)
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
                                    .background(SleepPeriwinkle)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.sleep_coach_title),
                                fontSize = TextBody,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.bedtime_window),
                            fontSize = TextCaption,
                            color = LabelSilver
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        val bedtimeWindow = vitals.bedtimeWindow.ifBlank { "22:45 - 23:30" }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ShapePill)
                                .background(SleepPeriwinkle.copy(alpha = 0.18f))
                                .border(BorderStroke(1.dp, SleepPeriwinkle.copy(alpha = 0.55f)), ShapePill)
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(SleepPeriwinkle)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = bedtimeWindow,
                                    fontSize = TextBody,
                                    style = TabularNums,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        val totalSleepMinutes = vitals.sleepDeepMins + vitals.sleepRemMins + vitals.sleepLightMins
                        val sleepBalanceMins = totalSleepMinutes - 480
                        val sleepBalanceText = when {
                            !hasSleepData && totalSleepMinutes == 0 -> "--"
                            sleepBalanceMins > 0 -> stringResource(R.string.sleep_debt_surplus, sleepBalanceMins)
                            sleepBalanceMins < 0 -> stringResource(R.string.sleep_debt_deficit, kotlin.math.abs(sleepBalanceMins))
                            else -> stringResource(R.string.sleep_target_balanced)
                        }
                        val sleepBalanceColor = when {
                            !hasSleepData && totalSleepMinutes == 0 -> LabelSilver
                            sleepBalanceMins >= 0 -> LimeSteps
                            else -> ColorAmber
                        }

                        val napsText = when {
                            vitals.napMinutes > 0 -> stringResource(R.string.nap_minutes_format, vitals.napMinutes)
                            else -> stringResource(R.string.no_naps_recorded)
                        }
                        val napsColor = if (vitals.napMinutes > 0) StageRem else LabelSilver

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Sleep Balance Box
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(ShapeChip)
                                    .background(CardBgSubtle)
                                    .padding(horizontal = 6.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = stringResource(R.string.sleep_balance_label),
                                        fontSize = TextCaption,
                                        color = LabelSilver,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = sleepBalanceText,
                                        fontSize = TextLabel,
                                        style = TabularNums,
                                        color = sleepBalanceColor,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            // Daytime Naps Box
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(ShapeChip)
                                    .background(CardBgSubtle)
                                    .padding(horizontal = 6.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = stringResource(R.string.daytime_naps),
                                        fontSize = TextCaption,
                                        color = LabelSilver,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = napsText,
                                        fontSize = TextLabel,
                                        style = TabularNums,
                                        color = napsColor,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // f) Card: Biometría Nocturna (Nocturnal Biometrics)
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
                                    .background(StageRem)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.nocturnal_biometrics),
                                fontSize = TextBody,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NocturnalMetricBox(
                                label = stringResource(R.string.blood_oxygen_short),
                                value = spo2Val,
                                valueColor = StageRem,
                                modifier = Modifier.weight(1f)
                            )
                            NocturnalMetricBox(
                                label = stringResource(R.string.hrv_short),
                                value = hrvVal,
                                valueColor = StageLight,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NocturnalMetricBox(
                                label = stringResource(R.string.resting_hr),
                                value = minHr,
                                valueColor = Color(0xFFFF85A1),
                                modifier = Modifier.weight(1f)
                            )
                            NocturnalMetricBox(
                                label = stringResource(R.string.stress),
                                value = stressVal,
                                valueColor = Color(0xFF70E000),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // g) Card: Nocturnal Skin Temperature Integration
            // =========================================================================
            item {
                NocturnalTemperatureCard(vitals = vitals)
            }

            // =========================================================================
            // e) Card: RingConn Coaching & Insights
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
                                    .background(SleepPeriwinkle)
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
                            !hasSleepData -> stringResource(R.string.sleep_insight_no_data)
                            sleepScore >= 85 -> stringResource(R.string.sleep_insight_optimal)
                            sleepScore >= 70 -> stringResource(R.string.sleep_insight_good)
                            else -> stringResource(R.string.sleep_insight_fair)
                        }
                        Text(
                            text = if (vitals.sleepInsight.isNotBlank()) vitals.sleepInsight else computedInsight,
                            fontSize = TextCaption,
                            color = Color(0xFFCFD8DC),
                            lineHeight = 14.sp
                        )
                    }
                }
            }

            // =========================================================================
            // f) Card: 7-Day Trend Sparkline for Sleep Score
            // =========================================================================
            item {
                RingConnHistoryStore.SevenDayTrendCard(
                    metricTitle = stringResource(R.string.sleep_score),
                    unit = stringResource(R.string.pts_unit),
                    accentColor = StageDeep,
                    selector = { it.sleepScore.toFloat() },
                    currentFallback = sleepScore.toFloat()
                )
            }

            // =========================================================================
            // g) Card: Sincronizar Anillo via Mobile RPC
            // =========================================================================
            item {
                var isSyncing by remember { mutableStateOf(false) }
                val syncScope = rememberCoroutineScope()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapePill)
                        .background(Color(0x2BFFFFFF))
                        .clickable {
                            if (!isSyncing) {
                                isSyncing = true
                                syncScope.launch {
                                    val startSync = WearDataListenerService.lastSyncCompletedMs.value
                                    onSyncClick()
                                    withTimeoutOrNull(8500L) {
                                        WearDataListenerService.lastSyncCompletedMs.first { it != startSync }
                                    }
                                    isSyncing = false
                                }
                            }
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isSyncing) stringResource(R.string.syncing_ring) else stringResource(R.string.sync_ring),
                        fontSize = TextLabel,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSyncing) LimeSteps else Color.White
                    )
                }
            }
        }
        }
    }
}

/**
 * 270-degree animated circular gauge for Sleep Score in One UI Watch style.
 * Hoists Brush and Stroke outside Canvas to prevent per-frame allocations during animation.
 */
@Composable
private fun SleepDialGauge(
    score: Int,
    modifier: Modifier = Modifier
) {
    val animProgress = remember { Animatable(0f) }
    val targetProgress = (score.coerceIn(0, 100) / 100f)

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
                Color(0xFF5E72E4),
                Color(0xFF8E99F3),
                Color(0xFF47CDFF),
                Color(0xFF5E72E4)
            )
        )
    }

    Canvas(modifier = modifier) {
        val diameter = size.minDimension - strokeWidthPx
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)

        // Background track (270 degrees from 135 to 405)
        drawArc(
            color = Color(0x225E72E4),
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
 * Multi-stage sleep bar drawn in Canvas with zero weight layout recomputations.
 * Features sequential reveal animation with subtle glowing divider ticks and
 * segment highlight when inspected.
 */
@Composable
private fun SleepStagesBar(
    awakePct: Float,
    remPct: Float,
    lightPct: Float,
    deepPct: Float,
    selectedStageIndex: Int? = null,
    modifier: Modifier = Modifier
) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(deepPct, remPct, lightPct, awakePct) {
        anim.snapTo(0f)
        anim.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 650,
                easing = CubicBezierEasing(0.22f, 0.25f, 0.0f, 1.0f)
            )
        )
    }

    val density = LocalDensity.current
    val tickWidthPx = remember(density) { with(density) { 1.5.dp.toPx() } }
    val dividerColor = remember { Color(0x80FFFFFF) }

    Canvas(modifier = modifier) {
        val corner = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = SleepTrackBg, size = size, cornerRadius = corner)

        val totalAnimW = size.width * anim.value
        var curX = 0f

        val stagesWithIndex = listOf(
            Triple(0, StageAwake, awakePct),
            Triple(1, StageRem, remPct),
            Triple(2, StageLight, lightPct),
            Triple(3, StageDeep, deepPct)
        )

        val isAnySelected = selectedStageIndex != null

        for (i in stagesWithIndex.indices) {
            val (stageIdx, color, pct) = stagesWithIndex[i]
            val w = totalAnimW * pct
            if (w > 0f) {
                val isSelected = (selectedStageIndex == stageIdx)
                val alpha = if (!isAnySelected || isSelected) 1.0f else 0.35f

                drawRect(
                    color = color.copy(alpha = alpha),
                    topLeft = Offset(curX, 0f),
                    size = Size(w, size.height)
                )

                if (isSelected) {
                    drawRect(
                        color = Color.White.copy(alpha = 0.32f),
                        topLeft = Offset(curX, 0f),
                        size = Size(w, size.height)
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(curX, 0f),
                        end = Offset(curX + w, 0f),
                        strokeWidth = 2.dp.toPx()
                    )
                }

                curX += w

                // Subtle glowing divider tick between stages if reached by animation sweep
                if (i < stagesWithIndex.size - 1 && curX < totalAnimW && curX > 0f) {
                    drawLine(
                        color = dividerColor,
                        start = Offset(curX, 0f),
                        end = Offset(curX, size.height),
                        strokeWidth = tickWidthPx
                    )
                }
            }
        }
    }
}

@Composable
private fun SleepStageRow(
    name: String,
    duration: String,
    percentage: String,
    dotColor: Color,
    isSelected: Boolean = false,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeChip)
            .then(
                if (isSelected) Modifier.background(dotColor.copy(alpha = 0.20f))
                else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = if (isSelected) 8.dp else 2.dp, vertical = 2.5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = name,
                fontSize = TextLabel,
                color = if (isSelected) dotColor else Color.White,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
        Row {
            Text(
                text = duration,
                fontSize = TextLabel,
                style = TabularNums,
                color = if (isSelected) Color.White else SleepPeriwinkle
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = percentage,
                fontSize = TextLabel,
                style = TabularNums,
                color = if (isSelected) dotColor else Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Isolated breathing radial aura rendered behind the hero sleep dial.
 * Evaluated in Modifier.graphicsLayer draw phase strictly gated on Lifecycle.State.RESUMED
 * for 0 recompositions and 0 AOD battery drain.
 */
@Composable
private fun BiometricHeroAura(
    color: Color,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val isResumed = lifecycleState == Lifecycle.State.RESUMED

    val infiniteTransition = rememberInfiniteTransition(label = "sleepHeroAura")
    val auraScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sleepAuraScale"
    )
    val auraAlpha by infiniteTransition.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.38f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sleepAuraAlpha"
    )

    val auraBrush = remember(color) {
        Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = 0.50f),
                color.copy(alpha = 0.16f),
                Color.Transparent
            )
        )
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                val scale = if (isResumed) auraScale else 1.0f
                val alpha = if (isResumed) auraAlpha else 0.12f
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .background(auraBrush, CircleShape)
    )
}

private data class SleepStageSegment(
    val index: Int,
    val name: String,
    val durationMins: Int,
    val pct: Float,
    val color: Color
)

@Composable
private fun NocturnalMetricBox(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(ShapeChip)
            .background(CardBgSubtle)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = label,
                fontSize = TextCaption,
                color = LabelSilver,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = TextBody,
                style = TabularNums,
                color = valueColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun formatMins(mins: Int): String {
    if (mins <= 0) return "0m"
    val h = mins / 60
    val m = mins % 60
    return if (h > 0 && m > 0) "${h}h ${m}m" else if (h > 0) "${h}h" else "${m}m"
}

/**
 * Sleek animated horizontal dip gauge for Nocturnal HR Dip.
 * Visualizes 0% to 30% sleeping HR drop compared to daytime baseline,
 * highlighting the 10% to 20% optimal restorative dip zone.
 * Hoists Brush and Stroke outside Canvas to prevent per-frame allocations during animation.
 */
@Composable
private fun NocturnalDipGauge(
    dipPct: Float,
    modifier: Modifier = Modifier
) {
    val targetFraction = (dipPct.coerceIn(0f, 30f) / 30f)
    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(targetFraction) {
        animProgress.animateTo(
            targetValue = targetFraction,
            animationSpec = tween(
                durationMillis = 650,
                easing = CubicBezierEasing(0.22f, 0.25f, 0f, 1f)
            )
        )
    }

    val density = LocalDensity.current
    val trackBgColor = remember { Color(0x22FFFFFF) }
    val zoneBgColor = remember { Color(0x3300E676) }
    val zoneBorderColor = remember { Color(0x6600E676) }
    val pointerColor = remember { Color.White }
    val tickStrokeWidth = remember(density) { with(density) { 1.5.dp.toPx() } }

    val dipGradientBrush = remember {
        Brush.horizontalGradient(
            listOf(
                Color(0xFF47CDFF),
                Color(0xFF5E72E4),
                Color(0xFF00E676)
            )
        )
    }

    Canvas(modifier = modifier) {
        val corner = CornerRadius(size.height / 2f, size.height / 2f)
        // 1. Draw base track
        drawRoundRect(
            color = trackBgColor,
            size = size,
            cornerRadius = corner
        )

        // 2. Highlight restorative dip zone (10% to 20% on 0%..30% scale)
        val zoneStartX = size.width * (10f / 30f)
        val zoneWidth = size.width * (10f / 30f)
        drawRect(
            color = zoneBgColor,
            topLeft = Offset(zoneStartX, 0f),
            size = Size(zoneWidth, size.height)
        )
        drawLine(
            color = zoneBorderColor,
            start = Offset(zoneStartX, 0f),
            end = Offset(zoneStartX, size.height),
            strokeWidth = tickStrokeWidth
        )
        drawLine(
            color = zoneBorderColor,
            start = Offset(zoneStartX + zoneWidth, 0f),
            end = Offset(zoneStartX + zoneWidth, size.height),
            strokeWidth = tickStrokeWidth
        )

        // 3. Draw animated progress fill
        val progressW = size.width * animProgress.value
        if (progressW > 0f) {
            drawRoundRect(
                brush = dipGradientBrush,
                topLeft = Offset(0f, 0f),
                size = Size(progressW, size.height),
                cornerRadius = corner
            )
            drawLine(
                color = pointerColor,
                start = Offset(progressW, 0f),
                end = Offset(progressW, size.height),
                strokeWidth = tickStrokeWidth * 1.5f
            )
        }
    }
}

/**
 * Dedicated Nocturnal Skin Temperature Card integrating nocturnal thermoregulation
 * and euthermia recovery metrics into the Sleep ecosystem.
 */
@Composable
private fun NocturnalTemperatureCard(vitals: RingConnVitals) {
    val offset = vitals.skinTempOffset
    val hasData = vitals.skinTempBaseline > 0f || vitals.skinTempOffset != 0f
    val baseline = if (vitals.skinTempBaseline > 0f) vitals.skinTempBaseline else 36.1f

    val offsetStr = if (hasData) {
        when {
            offset in -0.05f..0.05f -> "±0.0"
            offset > 0.05f -> String.format(Locale.US, "+%.1f", offset)
            else -> String.format(Locale.US, "%.1f", offset)
        }
    } else {
        "--"
    }

    val statusColor = when {
        !hasData -> LabelSilver
        offset in -0.5f..0.5f -> ColorEmerald
        offset > 0.5f -> ColorCoral
        else -> ColorCyanRadar
    }

    val statusText = when {
        !hasData -> stringResource(R.string.no_data)
        offset in -0.5f..0.5f -> stringResource(R.string.temp_in_range)
        offset > 0.5f -> stringResource(R.string.temp_warm)
        else -> stringResource(R.string.temp_cool)
    }

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
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.nocturnal_temperature_title),
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
                        .background(statusColor.copy(alpha = 0.18f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = statusText.uppercase(),
                        fontSize = 9.sp,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Deviation Box
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(ShapeChip)
                        .background(CardBgSubtle)
                        .padding(horizontal = 6.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.temp_deviation_stat),
                            fontSize = TextCaption,
                            color = LabelSilver,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = offsetStr,
                                fontSize = TextBody,
                                style = TabularNums,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            if (hasData) {
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "°C",
                                    fontSize = TextCaption,
                                    color = statusColor,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // Baseline Box
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(ShapeChip)
                        .background(CardBgSubtle)
                        .padding(horizontal = 6.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.baseline_stat),
                            fontSize = TextCaption,
                            color = LabelSilver,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (hasData) String.format(Locale.US, "%.1f °C", baseline) else "--",
                            fontSize = TextBody,
                            style = TabularNums,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            NocturnalTempDeviationGauge(
                offset = if (hasData) offset else 0f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(ShapeBar)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "-1.5°C",
                    fontSize = 8.sp,
                    color = LabelSilver
                )
                Text(
                    text = stringResource(R.string.optimal_euthermia),
                    fontSize = 8.sp,
                    color = ColorEmerald,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "+1.5°C",
                    fontSize = 8.sp,
                    color = LabelSilver
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.nocturnal_temp_explanation),
                fontSize = 9.sp,
                color = LabelSilver,
                lineHeight = 12.sp
            )
        }
    }
}

/**
 * Bi-directional nocturnal temperature deviation gauge from -1.5°C to +1.5°C.
 * Highlights the optimal ±0.5°C euthermia recovery band with animated marker.
 */
@Composable
private fun NocturnalTempDeviationGauge(
    offset: Float,
    modifier: Modifier = Modifier
) {
    val clampedOffset = offset.coerceIn(-1.5f, 1.5f)
    val targetFraction = ((clampedOffset + 1.5f) / 3.0f)
    val animProgress = remember { Animatable(0.5f) }

    LaunchedEffect(targetFraction) {
        animProgress.animateTo(
            targetValue = targetFraction,
            animationSpec = tween(
                durationMillis = 650,
                easing = CubicBezierEasing(0.22f, 0.25f, 0f, 1f)
            )
        )
    }

    val density = LocalDensity.current
    val trackBgColor = remember { Color(0x22FFFFFF) }
    val euthermiaZoneColor = remember { Color(0x3300E676) }
    val euthermiaBorderColor = remember { Color(0x6600E676) }
    val centerLineColor = remember { Color(0x55FFFFFF) }
    val pointerColor = remember { Color.White }
    val tickStrokeWidth = remember(density) { with(density) { 1.5.dp.toPx() } }

    Canvas(modifier = modifier) {
        val corner = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = trackBgColor, size = size, cornerRadius = corner)

        val zoneStartX = size.width * (1.0f / 3.0f)
        val zoneWidth = size.width * (1.0f / 3.0f)
        drawRect(
            color = euthermiaZoneColor,
            topLeft = Offset(zoneStartX, 0f),
            size = Size(zoneWidth, size.height)
        )
        drawLine(
            color = euthermiaBorderColor,
            start = Offset(zoneStartX, 0f),
            end = Offset(zoneStartX, size.height),
            strokeWidth = tickStrokeWidth
        )
        drawLine(
            color = euthermiaBorderColor,
            start = Offset(zoneStartX + zoneWidth, 0f),
            end = Offset(zoneStartX + zoneWidth, size.height),
            strokeWidth = tickStrokeWidth
        )

        val midX = size.width * 0.5f
        drawLine(
            color = centerLineColor,
            start = Offset(midX, 0f),
            end = Offset(midX, size.height),
            strokeWidth = tickStrokeWidth
        )

        val markerX = size.width * animProgress.value
        val markerColor = when {
            offset in -0.5f..0.5f -> Color(0xFF00E676)
            offset > 0.5f -> Color(0xFFFF6B6B)
            else -> Color(0xFF00E5FF)
        }
        drawCircle(
            color = markerColor,
            radius = size.height / 2f,
            center = Offset(markerX.coerceIn(size.height / 2f, size.width - size.height / 2f), size.height / 2f)
        )
        drawCircle(
            color = pointerColor,
            radius = size.height / 4f,
            center = Offset(markerX.coerceIn(size.height / 2f, size.width - size.height / 2f), size.height / 2f)
        )
    }
}

/**
 * Full-Screen Celestial Night Sky, Nebula & Meteor Shower Backdrop.
 * Expands across the entire watch screen (Modifier.fillMaxSize()) behind the
 * ScalingLazyColumn, featuring 32 multi-spectral twinkling stars (with 4-point
 * diffraction flares), deep indigo/cyan/violet atmospheric nebula glow, subtle
 * scroll parallax derived from listState, and 4 staggered diagonal shooting
 * meteors with dual-layer glowing gradient tails.
 * Strictly gated on Lifecycle.State.RESUMED for 0.0% ambient/AOD battery consumption.
 */
@Composable
private fun FullScreenCelestialBackdrop(
    listState: ScalingLazyListState,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val isResumed = lifecycleState == Lifecycle.State.RESUMED

    val transition = rememberInfiniteTransition(label = "starrySkyShimmer")
    val shimmerPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerPhase"
    )

    val meteorPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "meteorPhase"
    )

    val starNodes = remember {
        listOf(
            StarNode(0.18f, 0.12f, 1.8f, 0.0f, true),
            StarNode(0.82f, 0.15f, 1.6f, 0.25f, false),
            StarNode(0.12f, 0.28f, 1.3f, 0.5f, false),
            StarNode(0.88f, 0.25f, 1.7f, 0.75f, true),
            StarNode(0.24f, 0.42f, 1.4f, 0.35f, true),
            StarNode(0.76f, 0.45f, 1.5f, 0.65f, false),
            StarNode(0.35f, 0.08f, 1.1f, 0.15f, false),
            StarNode(0.65f, 0.09f, 1.2f, 0.85f, true),
            StarNode(0.50f, 0.05f, 1.5f, 0.4f, true),
            StarNode(0.08f, 0.52f, 1.0f, 0.6f, false),
            StarNode(0.92f, 0.50f, 1.1f, 0.1f, false),
            StarNode(0.32f, 0.68f, 1.2f, 0.9f, true),
            StarNode(0.68f, 0.66f, 1.3f, 0.45f, false),
            StarNode(0.50f, 0.75f, 1.4f, 0.2f, true),
            StarNode(0.28f, 0.22f, 1.0f, 0.55f, false),
            StarNode(0.72f, 0.21f, 1.1f, 0.05f, true),
            StarNode(0.15f, 0.62f, 0.9f, 0.80f, false),
            StarNode(0.85f, 0.60f, 1.0f, 0.30f, true),
            StarNode(0.06f, 0.38f, 1.2f, 0.18f, true),
            StarNode(0.94f, 0.36f, 1.3f, 0.68f, false),
            StarNode(0.42f, 0.18f, 1.6f, 0.42f, false),
            StarNode(0.58f, 0.19f, 1.4f, 0.92f, true),
            StarNode(0.20f, 0.82f, 1.5f, 0.12f, false),
            StarNode(0.80f, 0.84f, 1.6f, 0.62f, true),
            StarNode(0.45f, 0.88f, 1.2f, 0.32f, false),
            StarNode(0.14f, 0.92f, 1.0f, 0.82f, false),
            StarNode(0.86f, 0.94f, 1.1f, 0.22f, true),
            StarNode(0.62f, 0.92f, 1.3f, 0.52f, false),
            StarNode(0.38f, 0.95f, 1.0f, 0.72f, true),
            StarNode(0.04f, 0.72f, 0.9f, 0.38f, false),
            StarNode(0.96f, 0.70f, 1.1f, 0.88f, false),
            StarNode(0.50f, 0.38f, 1.2f, 0.48f, true)
        )
    }

    val meteorNodes = remember {
        listOf(
            // Meteor 1: Upper-left to center-right dome
            MeteorNode(
                startXNorm = 0.02f,
                startYNorm = 0.08f,
                endXNorm = 0.68f,
                endYNorm = 0.48f,
                phaseOffset = 0.05f,
                dutyCycle = 0.26f,
                tailLengthDp = 34f,
                headRadiusDp = 1.45f,
                isCyanAccent = false
            ),
            // Meteor 2: High celestial dome to eastern horizon
            MeteorNode(
                startXNorm = 0.32f,
                startYNorm = 0.02f,
                endXNorm = 0.96f,
                endYNorm = 0.42f,
                phaseOffset = 0.38f,
                dutyCycle = 0.26f,
                tailLengthDp = 30f,
                headRadiusDp = 1.35f,
                isCyanAccent = true
            ),
            // Meteor 3: Mid-west to lower-east sky
            MeteorNode(
                startXNorm = 0.08f,
                startYNorm = 0.36f,
                endXNorm = 0.78f,
                endYNorm = 0.78f,
                phaseOffset = 0.65f,
                dutyCycle = 0.26f,
                tailLengthDp = 32f,
                headRadiusDp = 1.40f,
                isCyanAccent = false
            ),
            // Meteor 4: Lower-west through southern sky
            MeteorNode(
                startXNorm = 0.18f,
                startYNorm = 0.55f,
                endXNorm = 0.88f,
                endYNorm = 0.96f,
                phaseOffset = 0.88f,
                dutyCycle = 0.26f,
                tailLengthDp = 28f,
                headRadiusDp = 1.30f,
                isCyanAccent = true
            )
        )
    }

    val goldColor = remember { Color(0xFFFFEAA7) }
    val cyanColor = remember { Color(0xFFA5C9FF) }
    val whiteColor = remember { Color(0xFFFFFFFF) }
    val periwinkleColor = remember { Color(0xFFA5AEDE) }

    val scrollParallax = (listState.centerItemIndex * 40f + listState.centerItemScrollOffset * 0.25f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val parallaxY = -(scrollParallax % h)

        // 1. Deep Celestial Atmospheric Nebula Glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF22144D).copy(alpha = 0.30f),
                    Color(0xFF140D30).copy(alpha = 0.14f),
                    Color.Transparent
                ),
                center = Offset(w * 0.32f, (h * 0.25f + parallaxY * 0.3f)),
                radius = w * 0.65f
            ),
            radius = w * 0.65f,
            center = Offset(w * 0.32f, (h * 0.25f + parallaxY * 0.3f))
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF00E5FF).copy(alpha = 0.09f),
                    Color(0xFF0F3254).copy(alpha = 0.04f),
                    Color.Transparent
                ),
                center = Offset(w * 0.74f, (h * 0.58f + parallaxY * 0.25f)),
                radius = w * 0.55f
            ),
            radius = w * 0.55f,
            center = Offset(w * 0.74f, (h * 0.58f + parallaxY * 0.25f))
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF38154D).copy(alpha = 0.22f),
                    Color.Transparent
                ),
                center = Offset(w * 0.50f, (h * 0.85f + parallaxY * 0.2f)),
                radius = w * 0.50f
            ),
            radius = w * 0.50f,
            center = Offset(w * 0.50f, (h * 0.85f + parallaxY * 0.2f))
        )

        // 2. Celestial Starfield (twinkling background)
        for (star in starNodes) {
            val cx = star.xNorm * w
            val rawCy = star.yNorm * h + parallaxY * 0.4f
            val cy = ((rawCy % h) + h) % h

            val alpha = if (isResumed) {
                val cycle = ((shimmerPhase + star.phaseOffset) % 1.0f)
                val sinVal = kotlin.math.sin(cycle * 2.0 * Math.PI).toFloat()
                0.25f + 0.35f * (sinVal + 1f)
            } else {
                0.35f
            }

            val baseColor = when {
                star.isGold -> goldColor
                star.radiusDp > 1.4f -> cyanColor
                star.phaseOffset > 0.6f -> periwinkleColor
                else -> whiteColor
            }

            val rPx = star.radiusDp.dp.toPx()

            drawCircle(
                color = baseColor.copy(alpha = alpha),
                radius = rPx,
                center = Offset(cx, cy)
            )

            if (star.radiusDp >= 1.5f && alpha > 0.55f) {
                val rayLen = rPx * 2.5f
                val rayAlpha = (alpha - 0.55f) * 0.85f
                drawLine(
                    color = baseColor.copy(alpha = rayAlpha),
                    start = Offset(cx - rayLen, cy),
                    end = Offset(cx + rayLen, cy),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = baseColor.copy(alpha = rayAlpha),
                    start = Offset(cx, cy - rayLen),
                    end = Offset(cx, cy + rayLen),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }

        // 3. Realistic Shooting Meteor Shower
        if (isResumed) {
            for (meteor in meteorNodes) {
                val t = ((meteorPhase + meteor.phaseOffset) % 1.0f)
                if (t < meteor.dutyCycle) {
                    val p = (t / meteor.dutyCycle).coerceIn(0f, 1f)
                    val easeP = p * p * (3f - 2f * p)

                    val headX = (meteor.startXNorm + (meteor.endXNorm - meteor.startXNorm) * easeP) * w
                    val headY = (meteor.startYNorm + (meteor.endYNorm - meteor.startYNorm) * easeP) * h

                    val intensity = when {
                        p < 0.20f -> p / 0.20f
                        p > 0.70f -> (1.0f - p) / 0.30f
                        else -> 1.0f
                    }.coerceIn(0f, 1f)

                    val dxNorm = meteor.endXNorm - meteor.startXNorm
                    val dyNorm = meteor.endYNorm - meteor.startYNorm
                    val distNorm = kotlin.math.sqrt(dxNorm * dxNorm + dyNorm * dyNorm).coerceAtLeast(0.001f)
                    val uX = dxNorm / distNorm
                    val uY = dyNorm / distNorm

                    val tailLenPx = meteor.tailLengthDp.dp.toPx() * (0.35f + 0.65f * intensity)
                    val tailStartX = headX - uX * tailLenPx
                    val tailStartY = headY - uY * tailLenPx

                    val tailGlowColor = if (meteor.isCyanAccent) Color(0xFF00E5FF) else Color(0xFF5E72E4)
                    val tailMidColor = if (meteor.isCyanAccent) Color(0xFFA5C9FF) else Color(0xFFFFEAA7)

                    drawLine(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                tailGlowColor.copy(alpha = 0.22f * intensity),
                                tailMidColor.copy(alpha = 0.50f * intensity)
                            ),
                            start = Offset(tailStartX, tailStartY),
                            end = Offset(headX, headY)
                        ),
                        start = Offset(tailStartX, tailStartY),
                        end = Offset(headX, headY),
                        strokeWidth = 3.2.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    drawLine(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                tailMidColor.copy(alpha = 0.45f * intensity),
                                Color.White.copy(alpha = 0.95f * intensity)
                            ),
                            start = Offset(tailStartX, tailStartY),
                            end = Offset(headX, headY)
                        ),
                        start = Offset(tailStartX, tailStartY),
                        end = Offset(headX, headY),
                        strokeWidth = 1.4.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    val headRadius = meteor.headRadiusDp.dp.toPx() * (0.85f + 0.35f * intensity)
                    drawCircle(
                        color = Color.White.copy(alpha = intensity),
                        radius = headRadius,
                        center = Offset(headX, headY)
                    )

                    if (intensity > 0.65f) {
                        val flareLen = 4.0.dp.toPx() * intensity
                        val flareAlpha = ((intensity - 0.65f) / 0.35f) * 0.85f
                        val flareColor = if (meteor.isCyanAccent) Color(0xFFD6F6FF) else Color(0xFFFFF7DC)
                        drawLine(
                            color = flareColor.copy(alpha = flareAlpha),
                            start = Offset(headX - flareLen, headY),
                            end = Offset(headX + flareLen, headY),
                            strokeWidth = 0.9.dp.toPx()
                        )
                        drawLine(
                            color = flareColor.copy(alpha = flareAlpha),
                            start = Offset(headX, headY - flareLen),
                            end = Offset(headX, headY + flareLen),
                            strokeWidth = 0.9.dp.toPx()
                        )
                    }
                }
            }
        }
    }
}

private data class StarNode(
    val xNorm: Float,
    val yNorm: Float,
    val radiusDp: Float,
    val phaseOffset: Float,
    val isGold: Boolean
)

private data class MeteorNode(
    val startXNorm: Float,
    val startYNorm: Float,
    val endXNorm: Float,
    val endYNorm: Float,
    val phaseOffset: Float,
    val dutyCycle: Float = 0.28f,
    val tailLengthDp: Float = 24f,
    val headRadiusDp: Float = 1.30f,
    val isCyanAccent: Boolean = false
)

@Preview(
    device = "id:wearos_small_round",
    showSystemUi = true
)
@Composable
fun SleepDetailPreview() {
    SleepDetailContent(
        vitals = RingConnVitals(
            sleepScore = 86,
            sleepDeepMins = 85,
            sleepRemMins = 110,
            sleepLightMins = 230,
            sleepAwakeMins = 25,
            lastHeartRate = 56,
            lastSpo2 = 98,
            isConnected = true,
            odiDropsPerHour = 2.4f,
            minSleepingSpo2 = 95,
            sleepingHrDipPct = 14.8f,
            napMinutes = 25,
            bedtimeWindow = "23:00 - 23:45",
            skinTempOffset = 0.2f,
            skinTempBaseline = 36.1f
        )
    )
}

/**
 * One measured stage: its label, its mean heart rate and the unit.
 *
 * The cell only exists when a reading does, so it never needs a placeholder branch.
 */
@Composable
private fun PhaseHeartRateCell(
    phase: SleepStage,
    bpm: Int,
    isSpanish: Boolean,
    modifier: Modifier = Modifier
) {
    val label = when (phase) {
        SleepStage.DEEP -> if (isSpanish) "Profundo" else "Deep"
        SleepStage.LIGHT -> if (isSpanish) "Ligero" else "Light"
        SleepStage.REM -> "REM"
    }
    val accent = when (phase) {
        SleepStage.DEEP -> StageDeep
        SleepStage.LIGHT -> StageLight
        SleepStage.REM -> StageRem
    }
    Box(
        modifier = modifier
            .clip(ShapeChip)
            .background(CardBgSubtle)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                fontSize = 9.sp,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$bpm",
                fontSize = TextBody,
                style = TabularNums,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isSpanish) "lpm" else "bpm",
                fontSize = 8.sp,
                color = LabelSilver
            )
        }
    }
}
