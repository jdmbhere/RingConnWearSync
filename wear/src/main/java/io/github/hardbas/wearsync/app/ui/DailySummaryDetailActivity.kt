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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.*
import io.github.hardbas.wearsync.app.R
import io.github.hardbas.wearsync.app.complication.RingConnComplicationProvider
import io.github.hardbas.wearsync.app.data.RingConnHistoryStore
import io.github.hardbas.wearsync.app.data.RingConnVitals
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

private val DailyActivityBrush = Brush.verticalGradient(listOf(ActivityGradientStart, ActivityGradientEnd))

class DailySummaryDetailActivity : ComponentActivity() {
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
            DailySummaryDetailScreen(this)
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
fun DailySummaryDetailScreen(context: Context) {
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

    DailySummaryDetailContent(vitals = vitals)
}

@Composable
fun DailySummaryDetailContent(vitals: RingConnVitals) {
    val listState = rememberScalingLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val liveSteps = vitals.liveSteps
    val targetSteps = if (vitals.targetSteps > 0) vitals.targetSteps else 8000
    // Real active minutes from RingConn (cadence fallback when duration collapsed)
    val activeMins = if (vitals.activeMinutes > 0) vitals.activeMinutes else if (vitals.liveSteps > 0) (vitals.liveSteps / 100).coerceAtLeast(1) else 0
    val targetActiveMins = 60
    val calories = vitals.liveCalorie
    val targetCalories = if (vitals.targetCalorie > 0) vitals.targetCalorie else 500
    val activityScore = vitals.activityScore

    val animatedActivityScore by animateIntAsState(
        targetValue = activityScore,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "activityScoreAnim"
    )

    val stepsFormatted = remember(liveSteps) {
        NumberFormat.getIntegerInstance(Locale.getDefault()).format(liveSteps)
    }

    val stepsProgress = (liveSteps.toFloat() / targetSteps).coerceIn(0f, 1.2f)
    val timeProgress = (activeMins.toFloat() / targetActiveMins).coerceIn(0f, 1.2f)
    val calProgress = (calories.toFloat() / targetCalories).coerceIn(0f, 1.2f)

    val density = LocalDensity.current

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
            FullScreenDailySummaryBackdrop(
                listState = listState,
                activityScore = activityScore,
                steps = liveSteps,
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
            // Header: Clean title with circular clearance
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.daily_activity_title),
                        fontSize = TextBody,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.ring_model_subtitle),
                        fontSize = TextCaption,
                        color = LabelSilver,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // 1. Hero: Center Concentric Rings Dial with Biometric Reactive Aura and 3D depth
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(134.dp)
                            .graphicsLayer {
                                cameraDistance = 12f * density.density
                            }
                    ) {
                        BiometricHeroAura(
                            color = LimeSteps,
                            modifier = Modifier.size(134.dp)
                        )
                        ConcentricActivityRings(
                            stepsProgress = stepsProgress,
                            timeProgress = timeProgress,
                            calProgress = calProgress,
                            modifier = Modifier.size(122.dp)
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "$animatedActivityScore",
                                fontSize = TextHero,
                                style = TabularNums,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = stringResource(R.string.pts_unit).uppercase(),
                                fontSize = TextCaption,
                                color = LabelSilver,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val statusLabel = when {
                        activityScore >= 85 -> stringResource(R.string.excellent)
                        activityScore >= 70 -> stringResource(R.string.status_good_pace)
                        else -> stringResource(R.string.status_in_progress)
                    }
                    val statusColor = when {
                        activityScore >= 85 -> LimeSteps
                        activityScore >= 70 -> CyanActive
                        else -> Color(0xFFFFD600)
                    }
                    Box(
                        modifier = Modifier
                            .clip(ShapePill)
                            .background(statusColor.copy(alpha = 0.26f))
                            .padding(horizontal = 14.dp, vertical = 3.5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = statusLabel.uppercase(),
                            fontSize = TextCaption,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // 2. Card: Pasos Diarios (Neon Lime)
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
                                        .background(LimeSteps)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.steps),
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
                                    .background(LimeSteps.copy(alpha = 0.26f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${(stepsProgress * 100).toInt()}%",
                                    fontSize = TextCaption,
                                    style = TabularNums,
                                    color = LimeSteps,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = stepsFormatted,
                                fontSize = TextBig,
                                style = TabularNums,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "/ 8,000",
                                fontSize = TextLabel,
                                style = TabularNums,
                                color = LabelSilver,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        ActivityProgressBar(progress = stepsProgress, color = LimeSteps)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val distKm = (liveSteps * 0.00075f)
                            Text(
                                text = String.format(Locale.US, "%.1f km", distKm),
                                fontSize = TextCaption,
                                style = TabularNums,
                                color = LabelSilver
                            )
                            val cadence = if (activeMins > 0) (liveSteps / activeMins).coerceIn(0, 180) else 0
                            Text(
                                text = if (cadence > 0) "$cadence spm" else "--",
                                fontSize = TextCaption,
                                style = TabularNums,
                                color = LabelSilver
                            )
                        }
                    }
                }
            }

            // 3. Card: Tiempo Activo (Electric Cyan)
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
                                    text = stringResource(R.string.active_time),
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
                                    .background(CyanActive.copy(alpha = 0.26f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.goal_format_min, targetActiveMins),
                                    fontSize = TextCaption,
                                    style = TabularNums,
                                    color = CyanActive,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "$activeMins",
                                fontSize = TextBig,
                                style = TabularNums,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.min_unit),
                                fontSize = TextLabel,
                                color = LabelSilver,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        ActivityProgressBar(progress = timeProgress, color = CyanActive)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (activeMins >= targetActiveMins) stringResource(R.string.goal_achieved) else stringResource(R.string.goal_remaining_min, targetActiveMins - activeMins),
                            fontSize = TextCaption,
                            color = LabelSilver
                        )
                    }
                }
            }

            // 4. Card: Calorías Activas (Vivid Violet)
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
                                        .background(VioletCalorie)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.active_calories),
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
                                    .background(VioletCalorie.copy(alpha = 0.26f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.goal_format_cal, targetCalories),
                                    fontSize = TextCaption,
                                    style = TabularNums,
                                    color = VioletCalorie,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "$calories",
                                fontSize = TextBig,
                                style = TabularNums,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "kcal",
                                fontSize = TextLabel,
                                color = LabelSilver,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        ActivityProgressBar(progress = calProgress, color = VioletCalorie)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.calorie_desc),
                            fontSize = TextCaption,
                            color = LabelSilver
                        )
                    }
                }
            }

            // 5. Card: Active Stand Hours (StandSyncModel)
            item {
                StandHoursCard(standHours = vitals.standHours)
            }

            // 6. Perspectiva RingConn (Floating Typography)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.ringconn_insights),
                        fontSize = TextLabel,
                        color = CyanActive,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (vitals.activityInsight.isNotBlank()) {
                            vitals.activityInsight
                        } else if (activityScore >= 80) {
                            stringResource(R.string.daily_insight_high)
                        } else {
                            stringResource(R.string.daily_insight_low)
                        },
                        fontSize = TextCaption,
                        color = Color(0xFFCFD8DC),
                        lineHeight = 14.sp
                    )
                }
            }

            // 6. 7-Day Trend Sparkline Cards (Steps & Activity Score)
            item {
                RingConnHistoryStore.SevenDayTrendCard(
                    metricTitle = stringResource(R.string.steps),
                    unit = stringResource(R.string.steps_short),
                    accentColor = LimeSteps,
                    selector = { it.steps.toFloat() },
                    currentFallback = liveSteps.toFloat()
                )
            }

            item {
                RingConnHistoryStore.SevenDayTrendCard(
                    metricTitle = stringResource(R.string.daily_activity_title),
                    unit = stringResource(R.string.pts_unit),
                    accentColor = CyanActive,
                    selector = { it.activityScore.toFloat() },
                    currentFallback = activityScore.toFloat()
                )
            }
        }
        }
    }
}

private data class KineticSpark(
    val radiusNorm: Float,
    val speed: Float,
    val angleOffsetDeg: Float,
    val sparkRadiusDp: Float,
    val isLime: Boolean
)

/**
 * Full-Screen Kinetic Solar Sparks & Velocity Field Backdrop.
 * Placed behind ScalingLazyColumn across Modifier.fillMaxSize(), featuring
 * kinetic lime/emerald and warm amber energy sparks drifting along curved orbital
 * tracks across the full screen, subtle radial velocity ticks near the bezel,
 * and a breathing activity aura behind glassmorphic metric cards with scroll
 * parallax. Gated strictly on Lifecycle.State.RESUMED for 0.0% ambient drain.
 */
@Composable
private fun FullScreenDailySummaryBackdrop(
    listState: ScalingLazyListState,
    activityScore: Int,
    steps: Int,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val isResumed = lifecycleState == Lifecycle.State.RESUMED

    val transition = rememberInfiniteTransition(label = "dailyActivityField")

    val orbitPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sparkOrbitPhase"
    )

    val velocityPulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "velocityPulse"
    )

    val auraBreath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "auraBreath"
    )

    val sparks = remember {
        listOf(
            KineticSpark(0.32f, 1.2f, 15f, 1.4f, true),
            KineticSpark(0.45f, 0.9f, 65f, 1.8f, false),
            KineticSpark(0.55f, 1.4f, 120f, 1.2f, true),
            KineticSpark(0.68f, 0.75f, 190f, 2.0f, false),
            KineticSpark(0.80f, 1.1f, 245f, 1.5f, true),
            KineticSpark(0.38f, 0.85f, 310f, 1.3f, false),
            KineticSpark(0.60f, 1.3f, 40f, 1.6f, true),
            KineticSpark(0.72f, 0.95f, 95f, 1.7f, false),
            KineticSpark(0.85f, 0.7f, 160f, 1.2f, true),
            KineticSpark(0.28f, 1.5f, 210f, 1.5f, false),
            KineticSpark(0.50f, 1.0f, 280f, 1.8f, true),
            KineticSpark(0.65f, 0.8f, 340f, 1.4f, false),
            KineticSpark(0.76f, 1.15f, 80f, 1.6f, true),
            KineticSpark(0.88f, 0.65f, 145f, 1.9f, false),
            KineticSpark(0.42f, 1.25f, 230f, 1.3f, true),
            KineticSpark(0.58f, 0.9f, 300f, 1.5f, false)
        )
    }

    val limeColor = remember { Color(0xFFA0F116) }
    val amberColor = remember { Color(0xFFFFB703) }
    val emeraldColor = remember { Color(0xFF00E676) }
    val cyanColor = remember { Color(0xFF47CDFF) }

    val scrollParallax = (listState.centerItemIndex * 40f + listState.centerItemScrollOffset * 0.25f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val parallaxY = -(scrollParallax % h)
        val heroCenter = Offset(w / 2f, (h * 0.38f + parallaxY * 0.2f).coerceIn(h * 0.2f, h * 0.7f))

        // 1. Breathing Activity Aura behind cards
        val breathAlpha = if (isResumed) {
            0.12f + 0.08f * kotlin.math.sin(auraBreath * 2.0 * Math.PI).toFloat()
        } else {
            0.14f
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    limeColor.copy(alpha = breathAlpha * 1.1f),
                    cyanColor.copy(alpha = breathAlpha * 0.5f),
                    Color.Transparent
                ),
                center = heroCenter,
                radius = w * 0.70f
            ),
            radius = w * 0.70f,
            center = heroCenter
        )

        // 2. Subtle Radial Velocity Ticks near Bezel
        val bezelR = (w / 2f) * 0.95f
        val center = Offset(w / 2f, h / 2f)
        val numTicks = 60
        val sweepTick = if (isResumed) (velocityPulse * numTicks).toInt() else 15

        for (t in 0 until numTicks) {
            val angleDeg = t * (360f / numTicks)
            val rad = Math.toRadians(angleDeg.toDouble())
            val cosA = kotlin.math.cos(rad).toFloat()
            val sinA = kotlin.math.sin(rad).toFloat()

            val distFromSweep = kotlin.math.abs(t - sweepTick).let { kotlin.math.min(it, numTicks - it) }
            val isSweepNear = distFromSweep <= 4
            val tickAlpha = if (isSweepNear) {
                0.35f * (1f - distFromSweep / 5f)
            } else {
                0.07f
            }
            val tickLen = if (isSweepNear) 4.5.dp.toPx() else 2.5.dp.toPx()
            val tickColor = if (isSweepNear) limeColor else Color.White

            val startP = Offset(center.x + (bezelR - tickLen) * cosA, center.y + (bezelR - tickLen) * sinA)
            val endP = Offset(center.x + bezelR * cosA, center.y + bezelR * sinA)

            drawLine(
                color = tickColor.copy(alpha = tickAlpha),
                start = startP,
                end = endP,
                strokeWidth = 1.dp.toPx()
            )
        }

        // 3. Kinetic Solar Sparks drifting along curved orbital tracks
        val maxOrbitR = (w / 2f) * 0.88f
        for (spark in sparks) {
            val curAngle = if (isResumed) {
                (spark.angleOffsetDeg + orbitPhase * spark.speed) % 360f
            } else {
                spark.angleOffsetDeg
            }
            val rad = Math.toRadians(curAngle.toDouble())
            val r = spark.radiusNorm * maxOrbitR
            val sx = heroCenter.x + r * kotlin.math.cos(rad).toFloat()
            val sy = heroCenter.y + r * 0.85f * kotlin.math.sin(rad).toFloat()

            val sparkColor = if (spark.isLime) limeColor else amberColor
            val rPx = spark.sparkRadiusDp.dp.toPx()
            val alpha = if (isResumed) {
                0.30f + 0.35f * kotlin.math.sin(Math.toRadians(curAngle.toDouble() * 2.0)).toFloat().let { kotlin.math.abs(it) }
            } else {
                0.40f
            }

            drawCircle(
                color = sparkColor.copy(alpha = alpha),
                radius = rPx,
                center = Offset(sx, sy)
            )

            val tailAngle = rad - Math.toRadians(12.0 * spark.speed)
            val tx = heroCenter.x + r * kotlin.math.cos(tailAngle).toFloat()
            val ty = heroCenter.y + r * 0.85f * kotlin.math.sin(tailAngle).toFloat()

            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        sparkColor.copy(alpha = alpha * 0.6f)
                    ),
                    start = Offset(tx, ty),
                    end = Offset(sx, sy)
                ),
                start = Offset(tx, ty),
                end = Offset(sx, sy),
                strokeWidth = (rPx * 0.9f),
                cap = StrokeCap.Round
            )

            drawCircle(
                color = Color.White.copy(alpha = (alpha * 0.9f).coerceIn(0f, 1f)),
                radius = rPx * 0.45f,
                center = Offset(sx, sy)
            )
        }
    }
}


/**
 * Biometric reactive breathing radial aura placed behind the hero gauge.
 * Uses Modifier.graphicsLayer draw phase animation strictly gated on Lifecycle.State.RESUMED
 * to ensure zero recompositions and zero battery drain when in Ambient Mode or screen-off.
 */
@Composable
private fun BiometricHeroAura(
    color: Color,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val isResumed = lifecycleState == Lifecycle.State.RESUMED

    val infiniteTransition = rememberInfiniteTransition(label = "heroAura")
    val auraScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heroAuraScale"
    )
    val auraAlpha by infiniteTransition.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.36f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heroAuraAlpha"
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

/**
 * 3 Concentric Activity Rings (One UI Watch 8 clone)
 * Outer: Neon Lime (Steps)
 * Middle: Electric Cyan (Active Time)
 * Inner: Vivid Violet (Calories)
 * Cascades outward with organic spring physics and zero per-frame object allocations.
 */
@Composable
private fun ConcentricActivityRings(
    stepsProgress: Float,
    timeProgress: Float,
    calProgress: Float,
    modifier: Modifier = Modifier
) {
    val outerAnim = remember { Animatable(0f) }
    val midAnim = remember { Animatable(0f) }
    val innerAnim = remember { Animatable(0f) }

    LaunchedEffect(stepsProgress, timeProgress, calProgress) {
        launch {
            delay(0L)
            outerAnim.animateTo(
                targetValue = stepsProgress,
                animationSpec = spring(
                    dampingRatio = 0.75f,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
        launch {
            delay(90L)
            midAnim.animateTo(
                targetValue = timeProgress,
                animationSpec = spring(
                    dampingRatio = 0.75f,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
        launch {
            delay(180L)
            innerAnim.animateTo(
                targetValue = calProgress,
                animationSpec = spring(
                    dampingRatio = 0.75f,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
    }

    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 7.dp.toPx() } }
    val spacingPx = remember(density, strokeWidthPx) { with(density) { strokeWidthPx + 2.dp.toPx() } }
    val ringStroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)

        // Ring 1: Steps (Outer)
        val outerRadius = (size.minDimension / 2f) - (strokeWidthPx / 2f)
        val outerTopLeft = Offset(center.x - outerRadius, center.y - outerRadius)
        val outerSize = Size(outerRadius * 2f, outerRadius * 2f)
        drawArc(
            color = LimeSteps.copy(alpha = 0.22f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = outerTopLeft,
            size = outerSize,
            style = ringStroke
        )
        drawArc(
            color = LimeSteps,
            startAngle = -90f,
            sweepAngle = (outerAnim.value * 360f).coerceIn(0f, 360f),
            useCenter = false,
            topLeft = outerTopLeft,
            size = outerSize,
            style = ringStroke
        )

        // Ring 2: Active Time (Middle)
        val middleRadius = outerRadius - spacingPx
        val middleTopLeft = Offset(center.x - middleRadius, center.y - middleRadius)
        val middleSize = Size(middleRadius * 2f, middleRadius * 2f)
        drawArc(
            color = CyanActive.copy(alpha = 0.22f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = middleTopLeft,
            size = middleSize,
            style = ringStroke
        )
        drawArc(
            color = CyanActive,
            startAngle = -90f,
            sweepAngle = (midAnim.value * 360f).coerceIn(0f, 360f),
            useCenter = false,
            topLeft = middleTopLeft,
            size = middleSize,
            style = ringStroke
        )

        // Ring 3: Calories (Inner)
        val innerRadius = middleRadius - spacingPx
        val innerTopLeft = Offset(center.x - innerRadius, center.y - innerRadius)
        val innerSize = Size(innerRadius * 2f, innerRadius * 2f)
        drawArc(
            color = VioletCalorie.copy(alpha = 0.22f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = innerTopLeft,
            size = innerSize,
            style = ringStroke
        )
        drawArc(
            color = VioletCalorie,
            startAngle = -90f,
            sweepAngle = (innerAnim.value * 360f).coerceIn(0f, 360f),
            useCenter = false,
            topLeft = innerTopLeft,
            size = innerSize,
            style = ringStroke
        )
    }
}

/**
 * Clean animated horizontal progress bar with rounded ends.
 */
@Composable
private fun ActivityProgressBar(progress: Float, color: Color) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        anim.animateTo(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = tween(
                durationMillis = 400,
                easing = CubicBezierEasing(0.22f, 0.25f, 0.0f, 1.0f)
            )
        )
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.5.dp)
    ) {
        val corner = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(
            color = Color(0x33FFFFFF),
            size = size,
            cornerRadius = corner
        )
        val barW = size.width * anim.value
        if (barW > 0f) {
            drawRoundRect(
                color = color,
                size = Size(barW, size.height),
                cornerRadius = corner
            )
        }
    }
}

/**
 * StandSyncModel - Interactive Stand Hours Card
 * Shows Active Stand Hours (${vitals.standHours} / 12 h) with a 12-segment horizontal capsule bar.
 * Filled segments glow in CyanActive, and tapping/dragging across segments triggers HapticFeedbackType.TextHandleMove.
 */
@Composable
private fun StandHoursCard(
    standHours: Int,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var touchedSegment by remember { mutableStateOf<Int?>(null) }
    var lastSegment by remember { mutableStateOf(-1) }
    val targetHours = 12
    val clampedHours = standHours.coerceIn(0, targetHours)
    val progress = (clampedHours.toFloat() / targetHours).coerceIn(0f, 1f)

    Box(
        modifier = modifier
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
                    val isSpanish = remember { java.util.Locale.getDefault().language.startsWith("es") }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isSpanish) "Horas Activas" else "Stand Hours",
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
                        .background(CyanActive.copy(alpha = 0.26f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        fontSize = TextCaption,
                        style = TabularNums,
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
                verticalAlignment = Alignment.Bottom
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "$standHours",
                        fontSize = TextBig,
                        style = TabularNums,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "/ 12 h",
                        fontSize = TextLabel,
                        style = TabularNums,
                        color = LabelSilver,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

                if (touchedSegment != null) {
                    val segIdx = touchedSegment!!
                    val isStanding = segIdx < standHours
                    val isSpanish = java.util.Locale.getDefault().language.startsWith("es")
                    Text(
                        text = "H${segIdx + 1}: ${if (isStanding) (if (isSpanish) "Activa" else "Active") else (if (isSpanish) "Pausa" else "Idle")}",
                        fontSize = TextCaption,
                        color = if (isStanding) CyanActive else LabelSilver,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val density = LocalDensity.current
            val spacingPx = with(density) { 2.5.dp.toPx() }
            val glowRadiusPx = with(density) { 2.dp.toPx() }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { offset ->
                                val seg = (offset.x / (size.width / 12f)).toInt().coerceIn(0, 11)
                                touchedSegment = seg
                                if (seg != lastSegment) {
                                    lastSegment = seg
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                tryAwaitRelease()
                                touchedSegment = null
                                lastSegment = -1
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                val seg = (offset.x / (size.width / 12f)).toInt().coerceIn(0, 11)
                                touchedSegment = seg
                                if (seg != lastSegment) {
                                    lastSegment = seg
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                            onDragEnd = {
                                touchedSegment = null
                                lastSegment = -1
                            },
                            onDragCancel = {
                                touchedSegment = null
                                lastSegment = -1
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                val seg = (change.position.x / (size.width / 12f)).toInt().coerceIn(0, 11)
                                touchedSegment = seg
                                if (seg != lastSegment) {
                                    lastSegment = seg
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        )
                    }
            ) {
                val totalWidth = size.width
                val totalHeight = size.height
                val segWidth = (totalWidth - (spacingPx * 11)) / 12f
                val cornerRadius = CornerRadius(totalHeight / 2f, totalHeight / 2f)

                for (i in 0 until 12) {
                    val left = i * (segWidth + spacingPx)
                    val isFilled = i < standHours
                    val isHovered = touchedSegment == i

                    if (isFilled || isHovered) {
                        drawRoundRect(
                            color = CyanActive.copy(alpha = if (isHovered) 0.55f else 0.28f),
                            topLeft = Offset(left - glowRadiusPx, -glowRadiusPx),
                            size = Size(segWidth + (glowRadiusPx * 2f), totalHeight + (glowRadiusPx * 2f)),
                            cornerRadius = CornerRadius((totalHeight + glowRadiusPx * 2f) / 2f)
                        )
                    }

                    val segColor = when {
                        isHovered && isFilled -> Color.White
                        isHovered -> CyanActive.copy(alpha = 0.6f)
                        isFilled -> CyanActive
                        else -> Color(0x33FFFFFF)
                    }

                    drawRoundRect(
                        color = segColor,
                        topLeft = Offset(left, 0f),
                        size = Size(segWidth, totalHeight),
                        cornerRadius = cornerRadius
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val isSpanishFooter = java.util.Locale.getDefault().language.startsWith("es")
            Text(
                text = if (standHours >= 12) (if (isSpanishFooter) "¡Meta lograda! 12/12 h" else "Goal reached! 12/12 h") else (if (isSpanishFooter) "Faltan ${12 - standHours} h para la meta" else "${12 - standHours} h left to goal"),
                fontSize = TextCaption,
                color = LabelSilver
            )
        }
    }
}

@Preview(
    device = "id:wearos_small_round",
    showSystemUi = true
)
@Composable
fun DailySummaryDetailPreview() {
    DailySummaryDetailContent(
        vitals = RingConnVitals(
            liveSteps = 6420,
            targetSteps = 10000,
            activeMinutes = 42,
            liveCalorie = 340,
            targetCalorie = 500,
            activityScore = 78,
            isConnected = true,
            standHours = 8
        )
    )
}
