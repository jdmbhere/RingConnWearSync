package io.github.hardbas.wearsync.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.material.*
import io.github.hardbas.wearsync.app.R
import io.github.hardbas.wearsync.app.complication.RingConnComplicationProvider
import io.github.hardbas.wearsync.app.data.RingConnHistoryStore
import io.github.hardbas.wearsync.app.data.StressPhase
import io.github.hardbas.wearsync.app.data.StressPhases
import io.github.hardbas.wearsync.app.data.RingConnVitals
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val StressRelaxed = Color(0xFF00E676)
private val StressBalanced = Color(0xFF00E5FF)
private val StressModerate = Color(0xFFFFD600)
private val StressHigh = Color(0xFFFF3D00)

class StressDetailActivity : ComponentActivity() {
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
            StressDetailScreen(this)
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
fun StressDetailScreen(context: Context) {
    var vitals by remember { mutableStateOf(RingConnComplicationProvider.getLatestVitals(context)) }

    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("RingConnWearCache", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            vitals = RingConnComplicationProvider.getLatestVitals(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    StressDetailContent(vitals = vitals)
}

@Composable
fun StressDetailContent(vitals: RingConnVitals) {
    val listState = rememberScalingLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val score = vitals.stressScore.coerceIn(0, 100)
    val animatedScore = animateIntAsState(
        targetValue = if (score > 0) score else 0,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "heroStressCountUp"
    )
    val scoreDisplay = if (score > 0) "${animatedScore.value}" else "--"
    val statusLabel = when {
        score < 30 -> stringResource(R.string.stress_relaxed)
        score < 60 -> stringResource(R.string.stress_balanced)
        score < 80 -> stringResource(R.string.stress_moderate)
        else -> stringResource(R.string.stress_high)
    }

    val statusColor = when {
        score < 30 -> StressRelaxed
        score < 60 -> StressBalanced
        score < 80 -> StressModerate
        else -> StressHigh
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
            FullScreenStressBackdrop(
                listState = listState,
                stressScore = score,
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
                        text = stringResource(R.string.stress_title),
                        fontSize = TextBody,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.ring_model_subtitle),
                        fontSize = TextCaption,
                        color = Color(0xFFD8B4FE),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // =========================================================================
            // b) Hero: Centered Stress Dial (122dp), live Score & Status Pill
            // =========================================================================
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val auraCycleMs = remember(score) {
                        (3200 - (score.coerceIn(0, 100) * 18)).coerceIn(1200, 3200)
                    }
                    val auraPulse = rememberBiometricAuraPulse(cycleDurationMs = auraCycleMs)
                    val auraBrush = remember(statusColor) {
                        Brush.radialGradient(
                            colors = listOf(
                                statusColor.copy(alpha = 0.28f),
                                statusColor.copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(126.dp)
                    ) {
                        // Lifecycle-gated Biometric Reactive Aura breathing behind hero dial
                        Box(
                            modifier = Modifier
                                .size(126.dp)
                                .graphicsLayer {
                                    val scale = auraPulse.value
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = (0.40f + (scale - 0.92f) * 1.5f).coerceIn(0f, 1f)
                                }
                                .background(auraBrush, CircleShape)
                        )

                        StressDialGauge(
                            score = score,
                            modifier = Modifier.size(122.dp)
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = scoreDisplay,
                                fontSize = TextHero,
                                style = TabularNums,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = stringResource(R.string.pts_unit).uppercase(),
                                fontSize = TextCaption,
                                color = Color(0xFFD8B4FE),
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .clip(ShapePill)
                            .background(statusColor.copy(alpha = 0.18f))
                            .padding(horizontal = 14.dp, vertical = 3.5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = statusLabel.uppercase(),
                            color = statusColor,
                            fontSize = TextCaption,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // =========================================================================
            // c) 24h Stress Timeline Chart with centered stats grid
            // =========================================================================
            item {
                StressTimelineCard(vitals = vitals)
            }

            // =========================================================================
            // d) Autonomic Balance Card (HRV Sympathetic vs Parasympathetic)
            // =========================================================================
            item {
                AutonomicBalanceCard(hrv = vitals.lastHrv, score = score, stressInsight = vitals.stressInsight)
            }

            // =========================================================================
            // e) Card: Zonas Fisiológicas de Estrés
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
                                    .background(Color(0xFF8B5CF6))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.stress_zones_title),
                                color = Color.White,
                                fontSize = TextBody,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        StressZoneRow(stringResource(R.string.stress_relaxed), stringResource(R.string.stress_zone_relaxed_desc), StressRelaxed, score < 30)
                        Spacer(modifier = Modifier.height(5.dp))
                        StressZoneRow(stringResource(R.string.stress_balanced), stringResource(R.string.stress_zone_balanced_desc), StressBalanced, score in 30..59)
                        Spacer(modifier = Modifier.height(5.dp))
                        StressZoneRow(stringResource(R.string.stress_moderate), stringResource(R.string.stress_zone_moderate_desc), StressModerate, score in 60..79)
                        Spacer(modifier = Modifier.height(5.dp))
                        StressZoneRow(stringResource(R.string.stress_high), stringResource(R.string.stress_zone_high_desc), StressHigh, score >= 80)
                    }
                }
            }

            // =========================================================================
            // e2) 4-Block Circadian Stress Map (DailyModel: Madrugada, Mañana, Tarde, Noche)
            // =========================================================================
            item {
                CircadianStressBlocksCard(vitals = vitals)
            }

            // =========================================================================
            // f) Interactive Haptic Cardiac Coherence Breathwork Coach
            // =========================================================================
            item {
                CardiacCoherenceBreathworkCard(vitals = vitals)
            }

            // =========================================================================
            // g) 7-Day Trend Sparkline Card
            // =========================================================================
            item {
                RingConnHistoryStore.SevenDayTrendCard(
                    metricTitle = stringResource(R.string.stress_title),
                    unit = stringResource(R.string.pts_unit),
                    accentColor = Color(0xFFD8B4FE),
                    selector = { it.stressScore.toFloat() },
                    currentFallback = score.toFloat()
                )
            }
        }
        }
    }
}

/**
 * Full-Screen Autonomic Resonance & Coherent Wavefield Backdrop.
 * Placed behind ScalingLazyColumn across Modifier.fillMaxSize(), featuring
 * flowing multi-layered harmonic sine/aurora ribbons and breathing concentric
 * coherence halos across the entire viewport. Color palette and wave speed
 * adapt dynamically to stress level (calm emerald/teal vs balanced cyan vs
 * elevated amber/violet) with scroll parallax. Gated on Lifecycle.State.RESUMED.
 */
@Composable
private fun FullScreenStressBackdrop(
    listState: ScalingLazyListState,
    stressScore: Int,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val isResumed = lifecycleState == Lifecycle.State.RESUMED

    // Dynamic wave duration and color palette based on stress level
    val (wavePeriodMs, primaryColor, secondaryColor) = remember(stressScore) {
        when {
            stressScore <= 30 -> Triple(5200, Color(0xFF00E676), Color(0xFF00B4D8)) // Calm Emerald / Teal slow waves
            stressScore <= 60 -> Triple(3800, Color(0xFF00E5FF), Color(0xFF47CDFF)) // Balanced Cyan waves
            stressScore <= 80 -> Triple(2800, Color(0xFFFFD600), Color(0xFFFF9E00)) // Moderate Amber waves
            else -> Triple(2000, Color(0xFFFF3D00), Color(0xFFD8B4FE))              // Elevated Coral / Violet fast waves
        }
    }

    val transition = rememberInfiniteTransition(label = "stressWavefield")

    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = wavePeriodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    val haloPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (wavePeriodMs * 1.3f).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "haloPhase"
    )

    val scrollParallax = (listState.centerItemIndex * 40f + listState.centerItemScrollOffset * 0.25f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val parallaxY = -(scrollParallax % h)
        val center = Offset(w / 2f, (h * 0.38f + parallaxY * 0.2f).coerceIn(h * 0.2f, h * 0.7f))

        // 1. Breathing Concentric Coherence Halos across entire viewport
        if (isResumed) {
            for (i in 0 until 3) {
                val haloP = (haloPhase + i * 0.33f) % 1.0f
                val haloRadius = 45.dp.toPx() + haloP * (w * 0.60f)
                val haloAlpha = (1f - haloP).let { it * it } * 0.20f

                drawCircle(
                    color = primaryColor.copy(alpha = haloAlpha),
                    radius = haloRadius,
                    center = center,
                    style = Stroke(width = 1.2.dp.toPx())
                )
            }
        }

        // 2. Breathing Autonomic Aurora Field
        val breathAlpha = if (isResumed) {
            0.12f + 0.08f * kotlin.math.sin(haloPhase * 2.0 * Math.PI).toFloat()
        } else {
            0.14f
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryColor.copy(alpha = breathAlpha * 1.1f),
                    secondaryColor.copy(alpha = breathAlpha * 0.6f),
                    Color.Transparent
                ),
                center = center,
                radius = w * 0.70f
            ),
            radius = w * 0.70f,
            center = center
        )

        // 3. Multi-Layered Flowing Harmonic Sine / Aurora Ribbons
        val numPts = 75
        val stepX = w / numPts.toFloat()

        // Ribbon 1: Upper harmonic resonance
        val path1 = androidx.compose.ui.graphics.Path()
        val baseY1 = h * 0.35f + parallaxY * 0.25f
        for (i in 0..numPts) {
            val x = i * stepX
            val normX = x / w
            val phase = if (isResumed) (wavePhase * 2.0 * Math.PI) else 0.0
            val y = baseY1 + (kotlin.math.sin(normX * 2.5 * Math.PI + phase) * 16.dp.toPx()).toFloat() +
                    (kotlin.math.cos(normX * 4.0 * Math.PI + phase * 0.5) * 6.dp.toPx()).toFloat()
            if (i == 0) path1.moveTo(x, y) else path1.lineTo(x, y)
        }
        drawPath(
            path = path1,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    primaryColor.copy(alpha = 0.25f),
                    secondaryColor.copy(alpha = 0.35f),
                    primaryColor.copy(alpha = 0.20f),
                    Color.Transparent
                )
            ),
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        )

        // Ribbon 2: Center/Lower deep harmonic resonance
        val path2 = androidx.compose.ui.graphics.Path()
        val baseY2 = h * 0.62f + parallaxY * 0.35f
        for (i in 0..numPts) {
            val x = i * stepX
            val normX = x / w
            val phase = if (isResumed) (-wavePhase * 1.8 * Math.PI) else 0.0
            val y = baseY2 + (kotlin.math.sin(normX * 3.0 * Math.PI + phase) * 20.dp.toPx()).toFloat() +
                    (kotlin.math.sin(normX * 5.5 * Math.PI - phase * 0.7) * 7.dp.toPx()).toFloat()
            if (i == 0) path2.moveTo(x, y) else path2.lineTo(x, y)
        }
        drawPath(
            path = path2,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    secondaryColor.copy(alpha = 0.22f),
                    primaryColor.copy(alpha = 0.30f),
                    secondaryColor.copy(alpha = 0.18f),
                    Color.Transparent
                )
            ),
            style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)
        )

        // Ribbon 3: Deep foundational wave near southern dome
        val path3 = androidx.compose.ui.graphics.Path()
        val baseY3 = h * 0.82f + parallaxY * 0.45f
        for (i in 0..numPts) {
            val x = i * stepX
            val normX = x / w
            val phase = if (isResumed) (wavePhase * 2.4 * Math.PI) else 0.0
            val y = baseY3 + (kotlin.math.cos(normX * 2.0 * Math.PI + phase) * 12.dp.toPx()).toFloat()
            if (i == 0) path3.moveTo(x, y) else path3.lineTo(x, y)
        }
        drawPath(
            path = path3,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    primaryColor.copy(alpha = 0.15f),
                    secondaryColor.copy(alpha = 0.22f),
                    Color.Transparent
                )
            ),
            style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}


@Composable
private fun StressTimelineCard(vitals: RingConnVitals) {
    var selectedBarIndex by remember { mutableStateOf<Int?>(null) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(selectedBarIndex) {
        if (selectedBarIndex != null) {
            delay(2500L)
            selectedBarIndex = null
        }
    }

    // RingConn records four circadian stress blocks a day and nothing finer, so
    // the chart draws exactly those four. A block the ring did not measure stays
    // at zero, and a day with no measured block says so instead of drawing a
    // curve derived from the daily score.
    val readings = remember(vitals) { StressPhases.readings(vitals) }
    if (readings.isEmpty()) {
        StressTimelineEmptyCard()
        return
    }
    val epochValues = readings.map { it.score }
    // Blocks the ring did not record stay in the chart as gaps, but they must not
    // drag the summary numbers down as if they were measured zeros.
    val measuredBlocks = readings.filter { it.score > 0 }

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
                            .background(Color(0xFFD8B4FE))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.stress_timeline_title),
                        color = Color.White,
                        fontSize = TextBody,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        lineHeight = TextBody,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(ShapeChip)
                        .background(Color(0x22D8B4FE))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.stress_blocks_chip),
                        color = Color(0xFFD8B4FE),
                        fontSize = TextCaption,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Sleek floating pill HUD above the chart showing exact hour + stress value
            val inspected = selectedBarIndex
            if (inspected != null) {
                val phaseLabel = when (readings[inspected].phase) {
                    StressPhase.DAWN -> stringResource(R.string.stress_phase_dawn)
                    StressPhase.MORNING -> stringResource(R.string.stress_phase_morning)
                    StressPhase.AFTERNOON -> stringResource(R.string.stress_phase_afternoon)
                    StressPhase.NIGHT -> stringResource(R.string.stress_phase_night)
                }
                val valScore = epochValues[inspected]
                val statusAtIdx = when {
                    valScore < 30 -> stringResource(R.string.stress_relaxed)
                    valScore < 60 -> stringResource(R.string.stress_balanced)
                    valScore < 80 -> stringResource(R.string.stress_moderate)
                    else -> stringResource(R.string.stress_high)
                }
                val colorAtIdx = when {
                    valScore < 30 -> StressRelaxed
                    valScore < 60 -> StressBalanced
                    valScore < 80 -> StressModerate
                    else -> StressHigh
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .clip(ShapePill)
                            .background(colorAtIdx.copy(alpha = 0.22f))
                            .border(BorderStroke(1.dp, colorAtIdx.copy(alpha = 0.5f)), ShapePill)
                            .padding(horizontal = 10.dp, vertical = 2.5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(colorAtIdx)
                        )
                        Text(
                            text = "$phaseLabel · $valScore pts · $statusAtIdx",
                            color = Color.White,
                            fontSize = TextCaption,
                            fontWeight = FontWeight.Bold,
                            style = TabularNums
                        )
                    }
                }
            }

            // 24-Hour Timeline Bar Chart with interactive Touch/Bezel Time-Scrubber
            val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f) }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val idx = ((offset.x / size.width.toFloat()) * 4).toInt().coerceIn(0, 3)
                            if (idx != selectedBarIndex) {
                                selectedBarIndex = idx
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                val idx = ((offset.x / size.width.toFloat()) * 4).toInt().coerceIn(0, 3)
                                if (idx != selectedBarIndex) {
                                    selectedBarIndex = idx
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                val idx = ((change.position.x / size.width.toFloat()) * 4).toInt().coerceIn(0, 3)
                                if (idx != selectedBarIndex) {
                                    selectedBarIndex = idx
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        )
                    }
            ) {
                val totalW = size.width
                val totalH = size.height - 12.dp.toPx()
                val barCount = epochValues.size
                val barSlotWidth = totalW / barCount
                val barWidth = barSlotWidth * 0.65f

                // Horizontal threshold guides (30, 60, 80)
                val y30 = totalH * (1f - 30f / 100f)
                val y60 = totalH * (1f - 60f / 100f)
                val y80 = totalH * (1f - 80f / 100f)

                drawLine(
                    color = StressRelaxed.copy(alpha = 0.22f),
                    start = Offset(0f, y30),
                    end = Offset(totalW, y30),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dashEffect
                )
                drawLine(
                    color = StressModerate.copy(alpha = 0.22f),
                    start = Offset(0f, y60),
                    end = Offset(totalW, y60),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dashEffect
                )
                drawLine(
                    color = StressHigh.copy(alpha = 0.22f),
                    start = Offset(0f, y80),
                    end = Offset(totalW, y80),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dashEffect
                )

                // Epoch bars
                for (i in 0 until barCount) {
                    val valScore = epochValues[i]
                    val barH = (valScore / 100f) * totalH
                    val x = (i * barSlotWidth) + ((barSlotWidth - barWidth) / 2f)
                    val y = totalH - barH

                    val barColor = when {
                        valScore < 30 -> StressRelaxed
                        valScore < 60 -> StressBalanced
                        valScore < 80 -> StressModerate
                        else -> StressHigh
                    }

                    val isSelected = (i == selectedBarIndex)
                    val alpha = when {
                        isSelected -> 1.0f
                        selectedBarIndex != null -> 0.35f
                        else -> 0.95f
                    }

                    drawRoundRect(
                        color = barColor.copy(alpha = alpha),
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barH.coerceAtLeast(3.dp.toPx())),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )

                    if (isSelected) {
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(x - 0.5.dp.toPx(), y - 0.5.dp.toPx()),
                            size = Size(barWidth + 1.dp.toPx(), barH.coerceAtLeast(3.dp.toPx()) + 1.dp.toPx()),
                            cornerRadius = CornerRadius((barWidth + 1.dp.toPx()) / 2f, (barWidth + 1.dp.toPx()) / 2f),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }

                }

                // Vertical glowing scrubber line + indicator dot
                if (selectedBarIndex != null) {
                    val selIdx = selectedBarIndex!!
                    val scrubberX = (selIdx * barSlotWidth) + (barSlotWidth / 2f)
                    val valScore = epochValues[selIdx]
                    val barH = (valScore / 100f) * totalH
                    val scrubberY = totalH - barH

                    drawLine(
                        color = Color.White.copy(alpha = 0.75f),
                        start = Offset(scrubberX, 0f),
                        end = Offset(scrubberX, totalH),
                        strokeWidth = 1.5.dp.toPx()
                    )

                    val selColor = when {
                        valScore < 30 -> StressRelaxed
                        valScore < 60 -> StressBalanced
                        valScore < 80 -> StressModerate
                        else -> StressHigh
                    }
                    drawCircle(
                        color = selColor.copy(alpha = 0.35f),
                        radius = 6.dp.toPx(),
                        center = Offset(scrubberX, scrubberY)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 2.5.dp.toPx(),
                        center = Offset(scrubberX, scrubberY)
                    )
                }
            }

            // The axis names the four windows the ring aggregates (0-5, 6-11, 12-17,
            // 18-23) as hour ranges. Phase words do not fit four-up on a 192dp screen:
            // each label owns a quarter of the row and centres under its own bar.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                AxisLabel(stringResource(R.string.stress_window_dawn))
                AxisLabel(stringResource(R.string.stress_window_morning))
                AxisLabel(stringResource(R.string.stress_window_afternoon))
                AxisLabel(stringResource(R.string.stress_window_night))
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Centered 3-Column Metrics Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = measuredBlocks.minOf { it.score }.toString(),
                        color = StressRelaxed,
                        fontSize = TextBody,
                        style = TabularNums,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.stress_block_lowest),
                        color = Color(0xFF94A3B8),
                        fontSize = TextCaption,
                        textAlign = TextAlign.Center
                    )
                }
                Box(modifier = Modifier.width(1.dp).height(18.dp).background(Color(0x22FFFFFF)))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = (measuredBlocks.maxOf { it.score } - measuredBlocks.minOf { it.score }).toString(),
                        color = StressModerate,
                        fontSize = TextBody,
                        style = TabularNums,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.stress_block_spread),
                        color = Color(0xFF94A3B8),
                        fontSize = TextCaption,
                        textAlign = TextAlign.Center
                    )
                }
                Box(modifier = Modifier.width(1.dp).height(18.dp).background(Color(0x22FFFFFF)))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = measuredBlocks.maxOf { it.score }.toString(),
                        color = StressHigh,
                        fontSize = TextBody,
                        style = TabularNums,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.stress_block_peak),
                        color = Color(0xFF94A3B8),
                        fontSize = TextCaption,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * One quarter of the axis row, centred under its own bar. Labels share the width
 * evenly so a long translation cannot push its neighbours off the card.
 */
@Composable
private fun RowScope.AxisLabel(text: String) {
    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = Color(0xFF94A3B8),
            fontSize = TextCaption,
            style = TabularNums,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StressTimelineEmptyCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeCard)
            .background(CardBg)
            .border(SpecularBorderCard, ShapeCard)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFD8B4FE))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.stress_timeline_title),
                    color = Color.White,
                    fontSize = TextBody,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    lineHeight = TextBody,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.no_data),
                color = Color(0xFF94A3B8),
                fontSize = TextCaption
            )
        }
    }
}

@Composable
private fun AutonomicBalanceCard(hrv: Int, score: Int, stressInsight: String = "") {
    val targetProgress = (score / 100f).coerceIn(0.05f, 0.95f)
    val animProgress = remember { Animatable(targetProgress) }
    LaunchedEffect(targetProgress) {
        animProgress.animateTo(
            targetValue = targetProgress,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    }

    val trackBrush = remember {
        Brush.horizontalGradient(
            listOf(StressRelaxed, StressBalanced, StressModerate, StressHigh)
        )
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.ic_comp_stress),
                    contentDescription = stringResource(R.string.stress_autonomic_balance_title),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = stringResource(R.string.stress_autonomic_balance_title),
                    color = Color(0xFF00E5FF),
                    fontSize = TextBody,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 2,
                    lineHeight = TextBody,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Autonomic Tone Slider
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(9.dp)
            ) {
                val totalW = size.width
                val h = size.height

                drawRoundRect(
                    brush = trackBrush,
                    topLeft = Offset(0f, 0f),
                    size = Size(totalW, h),
                    cornerRadius = CornerRadius(h / 2f, h / 2f),
                    alpha = 0.5f
                )

                val markerX = animProgress.value * totalW
                drawCircle(
                    color = Color.White,
                    radius = 5.5.dp.toPx(),
                    center = Offset(markerX, h / 2f)
                )
                drawCircle(
                    color = Color(0xFF141624),
                    radius = 2.dp.toPx(),
                    center = Offset(markerX, h / 2f)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.calm_vagal),
                    color = StressRelaxed,
                    fontSize = TextCaption,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.alert_load),
                    color = StressHigh,
                    fontSize = TextCaption,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            val hrvVal = if (hrv > 0) "$hrv ms" else "--"
            Text(
                text = stringResource(R.string.stress_hrv_label, hrvVal),
                color = Color.White,
                fontSize = TextLabel,
                style = TabularNums,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(4.dp))

            val explanation = if (stressInsight.isNotBlank()) {
                stressInsight
            } else when {
                score < 30 -> stringResource(R.string.stress_desc_relaxed)
                score < 60 -> stringResource(R.string.stress_desc_balanced)
                score < 80 -> stringResource(R.string.stress_desc_moderate)
                else -> stringResource(R.string.stress_desc_high)
            }

            Text(
                text = explanation,
                color = Color(0xFFB0BEC5),
                fontSize = TextLabel,
                lineHeight = TextBody
            )
        }
    }
}

@Composable
private fun StressZoneRow(label: String, range: String, color: Color, isActive: Boolean) {
    val bgColor by animateColorAsState(
        targetValue = if (isActive) color.copy(alpha = 0.18f) else Color.Transparent,
        animationSpec = tween(350),
        label = "stressZoneBg"
    )
    val labelColor by animateColorAsState(
        targetValue = if (isActive) Color.White else Color(0xFFB0BEC5),
        animationSpec = tween(350),
        label = "stressZoneLabel"
    )
    val rangeColor by animateColorAsState(
        targetValue = if (isActive) color else Color(0xFF78909C),
        animationSpec = tween(350),
        label = "stressZoneRange"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeChip)
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = label,
                color = labelColor,
                fontSize = TextLabel,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
            )
        }
        Text(
            text = range,
            color = rangeColor,
            fontSize = TextLabel,
            style = TabularNums,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * Lifecycle-gated biometric aura breathing pulse.
 * Speed is proportional to the metric and paused automatically when not Lifecycle.State.RESUMED (0% AOD battery drain).
 */
@Composable
private fun rememberBiometricAuraPulse(cycleDurationMs: Int): State<Float> {
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

    return if (isResumed) {
        val infiniteTransition = rememberInfiniteTransition(label = "stress_aura_pulse")
        infiniteTransition.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = cycleDurationMs / 2, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "auraScale"
        )
    } else {
        remember { mutableFloatStateOf(1.0f) }
    }
}

/**
 * 270-degree animated circular gauge for Stress Score in One UI Watch style.
 */
@Composable
private fun StressDialGauge(
    score: Int,
    modifier: Modifier = Modifier
) {
    val animProgress = remember { Animatable(0f) }
    val targetProgress = (score.coerceIn(0, 100) / 100f)

    LaunchedEffect(targetProgress) {
        animProgress.animateTo(
            targetValue = targetProgress,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
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
                StressRelaxed,
                StressBalanced,
                StressModerate,
                StressHigh,
                StressRelaxed
            )
        )
    }

    Canvas(modifier = modifier) {
        val diameter = size.minDimension - strokeWidth
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)

        // Background track (270 degrees from 135 to 405)
        drawArc(
            color = Color(0x228B5CF6),
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

private enum class BreathPhase {
    INHALE,
    EXHALE
}

/**
 * Triggers on-demand live sync request to the phone bridge via Wearable MessageClient.
 */
private fun triggerRingConnSync(context: Context) {
    try {
        val nodeClient = Wearable.getNodeClient(context)
        val messageClient = Wearable.getMessageClient(context)
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            nodes?.forEach { node ->
                messageClient.sendMessage(node.id, "/action/sync_ringconn", ByteArray(0))
            }
        }
    } catch (_: Exception) {
    }
}

/**
 * Interactive Haptic Cardiac Coherence Breathwork Coach (0% Idle Battery Drain).
 * Protocol: Resonance frequency (0.1 Hz = 6 breaths/min = 10s cycle: 4.0s Inhale + 6.0s Exhale).
 * Features eyes-closed haptic metronome, zero-allocation Canvas Coherence Lotus, and pre/post biofeedback sync.
 */
@Composable
private fun CardiacCoherenceBreathworkCard(vitals: RingConnVitals) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val isSpanish = remember { Locale.getDefault().language.equals("es", ignoreCase = true) }

    // 100% On-Demand state
    var isBreathingActive by remember { mutableStateOf(false) }
    var remainingSeconds by remember { mutableIntStateOf(60) }
    var completedCycles by remember { mutableIntStateOf(0) }
    var currentCycle by remember { mutableIntStateOf(1) }
    var currentPhase by remember { mutableStateOf(BreathPhase.INHALE) }
    var sessionCompleted by remember { mutableStateOf(false) }

    // Baseline telemetry recorded at session start
    var baselineStress by remember { mutableIntStateOf(vitals.stressScore) }
    var baselineHr by remember { mutableIntStateOf(vitals.lastHeartRate) }

    // Smooth Coherence Lotus / Concentric Liquid Ring scale (0.55f -> 1.0f -> 0.55f)
    val breathScale = remember { Animatable(0.55f) }

    // Lifecycle-gating: automatic stop if screen goes ambient or paused (0% idle battery drain)
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

    fun completeBiofeedback() {
        if (sessionCompleted) return
        sessionCompleted = true
        isBreathingActive = false
        scope.launch {
            // Double haptic confirmation upon completion
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(160L)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            // Dispatch live sync request to phone
            triggerRingConnSync(context)
        }
    }

    fun handleStop() {
        if (!isBreathingActive) return
        val finished = completedCycles
        isBreathingActive = false
        if (finished >= 3) {
            completeBiofeedback()
        } else {
            sessionCompleted = false
            scope.launch {
                breathScale.snapTo(0.55f)
            }
        }
    }

    // Automatically stop breathing if screen leaves resumed state
    LaunchedEffect(isResumed) {
        if (!isResumed && isBreathingActive) {
            handleStop()
        }
    }

    // Resonance Frequency Protocol Coroutine: 6 breaths/min (10s cycle: 4.0s Inhale + 6.0s Exhale)
    LaunchedEffect(isBreathingActive) {
        if (!isBreathingActive) {
            return@LaunchedEffect
        }

        // 60-second coroutine countdown
        val timerJob = launch {
            while (remainingSeconds > 0) {
                delay(1000L)
                remainingSeconds--
            }
        }

        try {
            for (cycle in 1..6) {
                if (!isBreathingActive) break
                currentCycle = cycle

                // --- INHALE PHASE (0.0s to 4.0s) ---
                currentPhase = BreathPhase.INHALE
                // 0.0s: Inhale start haptic cue
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                val inhaleAnim = launch {
                    breathScale.animateTo(
                        targetValue = 1.0f,
                        animationSpec = tween(
                            durationMillis = 4000,
                            easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
                        )
                    )
                }

                // 2.0s: Soft mid-inhale tactile cue
                delay(2000L)
                if (!isBreathingActive) break
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                // 4.0s: Inhale end
                delay(2000L)
                inhaleAnim.join()
                if (!isBreathingActive) break

                // --- EXHALE PHASE (4.0s to 10.0s) ---
                currentPhase = BreathPhase.EXHALE
                // 4.0s: Exhale start haptic cue
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                val exhaleAnim = launch {
                    breathScale.animateTo(
                        targetValue = 0.55f,
                        animationSpec = tween(
                            durationMillis = 6000,
                            easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
                        )
                    )
                }

                // 6.0s Exhale duration
                delay(6000L)
                exhaleAnim.join()

                completedCycles = cycle
            }

            if (completedCycles >= 3 || remainingSeconds == 0) {
                completeBiofeedback()
            }
        } finally {
            timerJob.cancel()
        }
    }

    // Hoisted Brushes and Strokes with remember for zero per-frame allocations
    val density = LocalDensity.current
    val outerStroke = remember(density) {
        Stroke(width = with(density) { 3.dp.toPx() }, cap = StrokeCap.Round)
    }
    val midStroke = remember(density) {
        Stroke(width = with(density) { 2.dp.toPx() }, cap = StrokeCap.Round)
    }
    val innerStroke = remember(density) {
        Stroke(width = with(density) { 1.5.dp.toPx() }, cap = StrokeCap.Round)
    }
    val petalStroke = remember(density) {
        Stroke(width = with(density) { 1.5.dp.toPx() }, cap = StrokeCap.Round)
    }

    val liquidSweepBrush = remember {
        Brush.sweepGradient(
            listOf(
                Color(0xFF00E5FF),
                Color(0xFF00E676),
                Color(0xFF8B5CF6),
                Color(0xFFD8B4FE),
                Color(0xFF00E5FF)
            )
        )
    }
    val lotusPetalBrush = remember {
        Brush.sweepGradient(
            listOf(
                Color(0xFF00E5FF).copy(alpha = 0.65f),
                Color(0xFF00E676).copy(alpha = 0.65f),
                Color(0xFFD8B4FE).copy(alpha = 0.65f),
                Color(0xFF00E5FF).copy(alpha = 0.65f)
            )
        )
    }
    val auraGlowBrush = remember {
        Brush.radialGradient(
            listOf(
                Color(0xFF00E5FF).copy(alpha = 0.35f),
                Color(0xFF00E676).copy(alpha = 0.12f),
                Color.Transparent
            )
        )
    }

    // Precomputed unit vectors for 6 lotus petals (0°, 60°, 120°, 180°, 240°, 300°)
    val petalUnitVectors = remember {
        List(6) { i ->
            val rad = (i * 60.0) * (PI / 180.0)
            Pair(cos(rad).toFloat(), sin(rad).toFloat())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeCard)
            .background(CardBg)
            .border(SpecularBorderCard, ShapeCard)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Header: Title + Resonance Tag
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
                            .background(if (isBreathingActive) Color(0xFF00E5FF) else Color(0xFF00E676))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isSpanish) "Coherencia Cardíaca" else "Cardiac Coherence",
                        color = Color.White,
                        fontSize = TextBody,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        lineHeight = TextBody,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(ShapeChip)
                        .background(Color(0x2200E5FF))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "0.1 Hz",
                        color = Color(0xFF00E5FF),
                        fontSize = TextCaption,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (isBreathingActive) {
                // =========================================================================
                // ACTIVE BREATHING MODE: Concentric Liquid Ring / Lotus Canvas + HUD
                // =========================================================================
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(136.dp)
                        .padding(vertical = 2.dp)
                ) {
                    Canvas(modifier = Modifier.size(132.dp)) {
                        val currentScale = breathScale.value.coerceIn(0.55f, 1.0f)
                        val radius = (size.minDimension / 2f) - with(density) { 5.dp.toPx() }
                        val centerOffset = center

                        // 1. Soft radial background glow
                        drawCircle(
                            brush = auraGlowBrush,
                            radius = radius * currentScale,
                            center = centerOffset
                        )

                        // 2. Coherence Lotus: 6 petals representing 6 breaths/min resonance
                        val petalOffset = radius * 0.30f * currentScale
                        val petalRadius = radius * 0.35f * currentScale
                        val petalAlpha = (0.35f + (currentScale - 0.55f) * 0.7f).coerceIn(0f, 1f)
                        for (unit in petalUnitVectors) {
                            val px = centerOffset.x + (petalOffset * unit.first)
                            val py = centerOffset.y + (petalOffset * unit.second)
                            drawCircle(
                                brush = lotusPetalBrush,
                                radius = petalRadius,
                                center = Offset(px, py),
                                style = petalStroke,
                                alpha = petalAlpha
                            )
                        }

                        // 3. Concentric Liquid Rings
                        drawCircle(
                            brush = liquidSweepBrush,
                            radius = (radius * currentScale).coerceAtLeast(1f),
                            center = centerOffset,
                            style = outerStroke
                        )
                        drawCircle(
                            brush = liquidSweepBrush,
                            radius = (radius * currentScale * 0.74f).coerceAtLeast(1f),
                            center = centerOffset,
                            style = midStroke,
                            alpha = 0.70f
                        )
                        drawCircle(
                            brush = liquidSweepBrush,
                            radius = (radius * currentScale * 0.48f).coerceAtLeast(1f),
                            center = centerOffset,
                            style = innerStroke,
                            alpha = 0.50f
                        )

                        // 4. Center nucleus
                        drawCircle(
                            color = Color.White,
                            radius = with(density) { 2.5.dp.toPx() } * currentScale,
                            center = centerOffset
                        )
                    }

                    // Centered Text HUD inside the Lotus
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val phaseColor = if (currentPhase == BreathPhase.INHALE) Color(0xFF00E5FF) else Color(0xFFD8B4FE)
                        val phaseText = if (currentPhase == BreathPhase.INHALE) {
                            if (isSpanish) "INHALA" else "INHALE"
                        } else {
                            if (isSpanish) "EXHALA" else "EXHALE"
                        }

                        Box(
                            modifier = Modifier
                                .clip(ShapePill)
                                .background(phaseColor.copy(alpha = 0.22f))
                                .border(BorderStroke(1.dp, phaseColor.copy(alpha = 0.5f)), ShapePill)
                                .padding(horizontal = 8.dp, vertical = 1.5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = phaseText,
                                color = phaseColor,
                                fontSize = TextCaption,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = "${remainingSeconds}s",
                            fontSize = 28.sp,
                            style = TabularNums,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = if (isSpanish) "Ciclo $currentCycle de 6" else "Cycle $currentCycle of 6",
                            fontSize = TextCaption,
                            color = Color(0xFF94A3B8),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Detener (Stop) button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapePill)
                        .background(Color(0x33FF3D00))
                        .border(BorderStroke(1.dp, Color(0x66FF3D00)), ShapePill)
                        .clickable { handleStop() }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isSpanish) "Detener" else "Stop",
                        color = Color(0xFFFF5252),
                        fontWeight = FontWeight.Bold,
                        fontSize = TextLabel,
                        textAlign = TextAlign.Center
                    )
                }

            } else {
                // =========================================================================
                // IDLE / COMPLETED MODE
                // =========================================================================
                if (sessionCompleted) {
                    // Biofeedback Completion Banner
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ShapePill)
                            .background(Color(0x2200E676))
                            .border(BorderStroke(1.dp, StressRelaxed.copy(alpha = 0.6f)), ShapePill)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(StressRelaxed)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isSpanish) "Biofeedback Completado" else "Biofeedback Completed",
                                color = StressRelaxed,
                                fontSize = TextCaption,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Pre/Post Comparison Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.heart_rate),
                                color = Color(0xFF94A3B8),
                                fontSize = TextCaption,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val currentHr = vitals.lastHeartRate
                            val hrDisplay = if (baselineHr > 0 && currentHr > 0) {
                                "$baselineHr → $currentHr"
                            } else if (currentHr > 0) {
                                "$currentHr bpm"
                            } else "--"
                            Text(
                                text = hrDisplay,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = TextLabel,
                                style = TabularNums,
                                textAlign = TextAlign.Center
                            )
                            if (baselineHr > 0 && currentHr > 0) {
                                val hrDelta = currentHr - baselineHr
                                val hrText = if (hrDelta > 0) "+$hrDelta bpm" else "$hrDelta bpm"
                                Text(
                                    text = hrText,
                                    color = if (hrDelta <= 0) StressRelaxed else StressModerate,
                                    fontSize = TextCaption,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Box(modifier = Modifier.width(1.dp).height(24.dp).background(Color(0x22FFFFFF)))

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.stress),
                                color = Color(0xFF94A3B8),
                                fontSize = TextCaption,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val currentStress = vitals.stressScore
                            val stressDisplay = if (baselineStress > 0 && currentStress > 0) {
                                "$baselineStress → $currentStress"
                            } else if (currentStress > 0) {
                                "$currentStress pts"
                            } else "--"
                            Text(
                                text = stressDisplay,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = TextLabel,
                                style = TabularNums,
                                textAlign = TextAlign.Center
                            )
                            if (baselineStress > 0 && currentStress > 0) {
                                val sDelta = currentStress - baselineStress
                                val sText = if (sDelta > 0) "+$sDelta pts" else "$sDelta pts"
                                Text(
                                    text = sText,
                                    color = if (sDelta <= 0) StressRelaxed else StressModerate,
                                    fontSize = TextCaption,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (isSpanish) "Sincronización RingConn enviada a teléfono" else "RingConn sync requested on phone",
                        color = Color(0xFF00E5FF),
                        fontSize = TextCaption,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                } else {
                    // Protocol Description
                    Text(
                        text = if (isSpanish) {
                            "Frecuencia de resonancia a 6 resp/min (4s inhala, 6s exhala) para sincronizar el ritmo cardíaco y activar el tono vagal. Guía háptica táctil para ojos cerrados."
                        } else {
                            "Resonance frequency at 6 breaths/min (4s inhale, 6s exhale) to synchronize HRV and stimulate vagal tone. Haptic guidance for eyes-closed practice."
                        },
                        color = Color(0xFFCFD8DC),
                        fontSize = TextCaption,
                        lineHeight = 14.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Iniciar Respiración (1 min) button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapePill)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF00E5FF), Color(0xFF00E676))
                            )
                        )
                        .clickable {
                            baselineStress = vitals.stressScore
                            baselineHr = vitals.lastHeartRate
                            remainingSeconds = 60
                            completedCycles = 0
                            currentCycle = 1
                            sessionCompleted = false
                            isBreathingActive = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color.Black)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSpanish) "Iniciar Respiración (1 min)" else "Start Breathing (1 min)",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = TextLabel,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CircadianStressBlocksCard(vitals: RingConnVitals) {
    val isSpanish = remember { Locale.getDefault().language.startsWith("es") }
    // A block the ring has not measured yet can gain a reading later the same day, so it
    // is omitted from the grid instead of printed as a placeholder: the row reappears by
    // itself and the remaining cells close the gap.
    val measuredBlocks = remember(vitals) { StressPhases.measured(vitals) }

    fun blockTitle(phase: StressPhase): String = when (phase) {
        StressPhase.DAWN -> if (isSpanish) "Madrugada" else "Dawn"
        StressPhase.MORNING -> if (isSpanish) "Mañana" else "Morning"
        StressPhase.AFTERNOON -> if (isSpanish) "Tarde" else "Afternoon"
        StressPhase.NIGHT -> if (isSpanish) "Noche" else "Night"
    }

    fun blockHours(phase: StressPhase): String = when (phase) {
        StressPhase.DAWN -> "00–06h"
        StressPhase.MORNING -> "06–12h"
        StressPhase.AFTERNOON -> "12–18h"
        StressPhase.NIGHT -> "18–24h"
    }

    fun blockColor(score: Int): Color = when {
        score < 30 -> StressRelaxed
        score < 60 -> StressBalanced
        score < 80 -> StressModerate
        else -> StressHigh
    }

    fun blockStatus(score: Int): String = when {
        score < 30 -> if (isSpanish) "Calma" else "Calm"
        score < 60 -> if (isSpanish) "Normal" else "Normal"
        score < 80 -> if (isSpanish) "Medio" else "Med"
        else -> if (isSpanish) "Alto" else "High"
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
                            .background(StressBalanced)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isSpanish) "Bloques Circadianos" else "Circadian Blocks",
                        color = Color.White,
                        fontSize = TextBody,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        lineHeight = TextBody,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(ShapeChip)
                        .background(Color(0x2200E5FF))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "4x6h · EST",
                        color = StressBalanced,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (measuredBlocks.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_data),
                    color = LabelSilver,
                    fontSize = TextCaption
                )
            } else {
                measuredBlocks.chunked(2).forEachIndexed { index, row ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        row.forEach { reading ->
                            CircadianBlockCell(
                                modifier = Modifier.weight(1f),
                                title = blockTitle(reading.phase),
                                hours = blockHours(reading.phase),
                                score = reading.score,
                                status = blockStatus(reading.score),
                                color = blockColor(reading.score)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (isSpanish) {
                        "Divide tu carga autonómica en 4 franjas de 6h para detectar si tu estrés ocurre dormido o durante el día."
                    } else {
                        "Splits autonomic load into 4x6h blocks to spot nocturnal vs daytime stress."
                    },
                    fontSize = 9.sp,
                    color = LabelSilver,
                    lineHeight = 12.sp
                )
            }
        }
    }
}

@Composable
private fun CircadianBlockCell(
    title: String,
    hours: String,
    score: Int,
    status: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(ShapeChip)
            .background(Color(0x14FFFFFF))
            .padding(horizontal = 7.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 9.sp,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = hours,
                    fontSize = 8.sp,
                    color = LabelSilver,
                    style = TabularNums,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "$score",
                    fontSize = TextBody,
                    style = TabularNums,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = status,
                    fontSize = 9.sp,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

@Preview(
    device = "id:wearos_small_round",
    showSystemUi = true
)
@Composable
fun StressDetailPreview() {
    StressDetailContent(
        vitals = RingConnVitals(
            stressScore = 28,
            lastHeartRate = 72,
            lastHrv = 46,
            isConnected = true
        )
    )
}
