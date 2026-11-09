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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
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
import io.github.hardbas.wearsync.app.data.RingConnVitals
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val TempCool = Color(0xFF38BDF8)          // Electric Sky Cyan
private val TempOptimal = Color(0xFF00E676)       // Clinical Mint
private val TempWarmAlert = Color(0xFFFF7043)     // Warm Amber / Coral
private val TempEmeraldSoft = Color(0xFF34D399)   // Luminous Mint Emerald

class TemperatureDetailActivity : ComponentActivity() {
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
            TemperatureDetailScreen(this)
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
fun TemperatureDetailScreen(context: Context) {
    var vitals by remember { mutableStateOf(RingConnComplicationProvider.getLatestVitals(context)) }

    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("RingConnWearCache", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            vitals = RingConnComplicationProvider.getLatestVitals(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    TemperatureDetailContent(vitals = vitals)
}

@Composable
fun TemperatureDetailContent(vitals: RingConnVitals) {
    val listState = rememberScalingLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val hasData = vitals.skinTempBaseline > 0f || vitals.skinTempOffset != 0f
    val animatedOffset = animateFloatAsState(
        targetValue = if (hasData) vitals.skinTempOffset else 0f,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "heroTempOffsetAnim"
    )
    val offset = vitals.skinTempOffset
    val offsetToDisplay = if (hasData) animatedOffset.value else 0f
    val offsetFormatted = if (hasData) {
        when {
            offsetToDisplay in -0.05f..0.05f -> "±0.0 °C"
            offsetToDisplay > 0.05f -> String.format(Locale.US, "+%.1f °C", offsetToDisplay)
            else -> String.format(Locale.US, "%.1f °C", offsetToDisplay)
        }
    } else {
        "--.-"
    }

    val statusLabel = when {
        !hasData -> stringResource(R.string.no_data)
        offset in -0.5f..0.5f -> stringResource(R.string.temp_stable_optimal)
        offset > 0.5f -> stringResource(R.string.temp_warm)
        else -> stringResource(R.string.temp_cool)
    }

    val statusColor = when {
        !hasData -> LabelSilver
        offset in -0.5f..0.5f -> TempOptimal
        offset > 0.5f -> TempWarmAlert
        else -> TempCool
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
            FullScreenTemperatureBackdrop(
                listState = listState,
                skinTempOffset = offset,
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
                        text = stringResource(R.string.skin_temp),
                        fontSize = TextBody,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.ring_model_subtitle),
                        fontSize = TextCaption,
                        color = TempEmeraldSoft,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // =========================================================================
            // b) Hero: Centered Temp Dial (122dp), Offset & Status Pill
            // =========================================================================
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val auraCycleMs = remember(offset) {
                        (3400 - (abs(offset).coerceIn(0f, 1.5f) * 1200)).toInt().coerceIn(1600, 3400)
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

                        TempDialGauge(
                            offset = offset,
                            hasData = hasData,
                            modifier = Modifier.size(122.dp)
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = offsetFormatted,
                                fontSize = TextHero,
                                style = TabularNums,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = stringResource(R.string.skin_temp).uppercase(),
                                fontSize = TextCaption,
                                color = TempEmeraldSoft,
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
            // c) Card: Desviación Dérmica y Escala Circadiana
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
                                        .background(TempOptimal)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.baseline_temp),
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
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = statusLabel.uppercase(),
                                    fontSize = TextCaption,
                                    style = TabularNums,
                                    color = statusColor,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = if (hasData) offsetFormatted else "--.- °C",
                                fontSize = TextBig,
                                style = TabularNums,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (vitals.skinTempBaseline > 0f) stringResource(R.string.temp_baseline_format, vitals.skinTempBaseline) else "Baseline: --.- °C",
                                fontSize = TextLabel,
                                style = TabularNums,
                                color = LabelSilver,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Real-time Deviation Scale Bar (-1.0°C to +1.0°C)
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                        ) {
                            val totalW = size.width
                            val h = size.height

                            // Track Background
                            drawRoundRect(
                                color = Color(0x33FFFFFF),
                                topLeft = Offset(0f, 0f),
                                size = Size(totalW, h),
                                cornerRadius = CornerRadius(h / 2f, h / 2f)
                            )

                            // Optimal Middle Zone (-0.5°C to +0.5°C, 25% to 75%)
                            val optStart = totalW * 0.25f
                            val optWidth = totalW * 0.5f
                            drawRoundRect(
                                color = TempOptimal.copy(alpha = 0.45f),
                                topLeft = Offset(optStart, 0f),
                                size = Size(optWidth, h),
                                cornerRadius = CornerRadius(h / 2f, h / 2f)
                            )

                            // Center Reference Mark (0.0°C)
                            drawLine(
                                color = Color.White,
                                start = Offset(totalW * 0.5f, -2.dp.toPx()),
                                end = Offset(totalW * 0.5f, h + 2.dp.toPx()),
                                strokeWidth = 2.dp.toPx()
                            )

                            // Marker Indicator Dot
                            val normOffset = if (hasData) ((offset + 1.0f) / 2.0f).coerceIn(0f, 1f) else 0.5f
                            val markerX = normOffset * totalW
                            drawCircle(
                                color = Color.White,
                                radius = 5.dp.toPx(),
                                center = Offset(markerX.coerceIn(5.dp.toPx(), totalW - 5.dp.toPx()), h / 2f)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("-1.0 °C", fontSize = TextCaption, style = TabularNums, color = LabelSilver)
                            Text("0.0 °C", fontSize = TextCaption, style = TabularNums, color = TempOptimal)
                            Text("+1.0 °C", fontSize = TextCaption, style = TabularNums, color = LabelSilver)
                        }
                    }
                }
            }

            // =========================================================================
            // c) Nocturnal Circadian Thermal Curve Chart
            // =========================================================================
            item {
                CircadianThermalCurveCard(vitals = vitals)
            }

            // =========================================================================
            // d) Card: Perspectiva Fisiológica
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
                                    .background(if (hasData) TempEmeraldSoft else LabelSilver)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (hasData) stringResource(R.string.temp_delta_explanation_title) else stringResource(R.string.skin_temp),
                                color = Color.White,
                                fontSize = TextBody,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 2,
                                lineHeight = TextBody,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (vitals.vitalsInsight.isNotBlank()) {
                                vitals.vitalsInsight
                            } else if (hasData) {
                                stringResource(R.string.temp_delta_explanation_desc)
                            } else {
                                stringResource(R.string.temp_no_data_desc)
                            },
                            color = Color(0xFFCFD8DC),
                            fontSize = TextCaption,
                            lineHeight = 14.sp
                        )
                    }
                }
            }

            // =========================================================================
            // e) Card: Rangos de Termorregulación Circadiana
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
                                    .background(TempOptimal)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.temp_thermoregulation_title),
                                color = Color.White,
                                fontSize = TextBody,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        TempBandRow(
                            label = stringResource(R.string.optimal),
                            range = "±0.5 °C",
                            desc = stringResource(R.string.euthermia_desc),
                            color = TempOptimal,
                            isActive = offset in -0.5f..0.5f
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        TempBandRow(
                            label = stringResource(R.string.elevated),
                            range = "> +0.5 °C",
                            desc = stringResource(R.string.elevated_temp_desc),
                            color = TempWarmAlert,
                            isActive = offset > 0.5f
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        TempBandRow(
                            label = stringResource(R.string.temp_cool),
                            range = "< -0.5 °C",
                            desc = stringResource(R.string.cool_temp_desc),
                            color = TempCool,
                            isActive = offset < -0.5f
                        )
                    }
                }
            }

            // =========================================================================
            // f) Card: Alerta Inmune Temprana
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
                                    .background(TempWarmAlert)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.temp_recovery_title),
                                color = Color.White,
                                fontSize = TextBody,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.temp_recovery_desc),
                            color = Color(0xFFCFD8DC),
                            fontSize = TextCaption,
                            lineHeight = 14.sp
                        )
                    }
                }
            }

            // =========================================================================
            // g) 7-Day Trend Sparkline Card
            // =========================================================================
            item {
                RingConnHistoryStore.SevenDayTrendCard(
                    metricTitle = stringResource(R.string.skin_temp),
                    unit = "°C",
                    accentColor = TempEmeraldSoft,
                    selector = { it.skinTempOffset },
                    currentFallback = offset
                )
            }
        }
        }
    }
}

private data class ThermalParticle(
    val xNorm: Float,
    val yNorm: Float,
    val radiusDp: Float,
    val speed: Float,
    val phaseOffset: Float,
    val isWarm: Boolean
)

/**
 * Full-Screen Thermodynamic Aurora & Micro-Particles Backdrop.
 * Placed behind ScalingLazyColumn across Modifier.fillMaxSize(), featuring
 * a breathing thermal equilibrium aurora (cool cyan vs clinical mint vs warm amber),
 * floating thermodynamic micro-particles, and subtle concentric thermal contours
 * with scroll parallax. Gated strictly on Lifecycle.State.RESUMED for 0.0% ambient drain.
 */
@Composable
private fun FullScreenTemperatureBackdrop(
    listState: ScalingLazyListState,
    skinTempOffset: Float,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val isResumed = lifecycleState == Lifecycle.State.RESUMED

    val (primaryColor, secondaryColor) = remember(skinTempOffset) {
        when {
            skinTempOffset > 0.2f -> Pair(Color(0xFFFF7043), Color(0xFFFFB703))
            skinTempOffset < -0.2f -> Pair(Color(0xFF38BDF8), Color(0xFF00E5FF))
            else -> Pair(Color(0xFF00E676), Color(0xFF34D399))
        }
    }

    val transition = rememberInfiniteTransition(label = "tempField")

    val driftPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "tempDriftPhase"
    )

    val contourPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "contourPhase"
    )

    val particles = remember {
        listOf(
            ThermalParticle(0.14f, 0.85f, 1.3f, 0.7f, 0.1f, true),
            ThermalParticle(0.82f, 0.90f, 1.7f, 0.9f, 0.35f, false),
            ThermalParticle(0.25f, 0.72f, 1.2f, 0.6f, 0.6f, true),
            ThermalParticle(0.76f, 0.68f, 1.5f, 0.85f, 0.85f, false),
            ThermalParticle(0.48f, 0.95f, 2.0f, 1.1f, 0.2f, true),
            ThermalParticle(0.12f, 0.48f, 1.4f, 0.65f, 0.45f, false),
            ThermalParticle(0.88f, 0.42f, 1.6f, 0.75f, 0.7f, true),
            ThermalParticle(0.34f, 0.32f, 1.1f, 0.55f, 0.15f, false),
            ThermalParticle(0.66f, 0.28f, 1.8f, 0.8f, 0.9f, true),
            ThermalParticle(0.22f, 0.58f, 1.3f, 0.7f, 0.5f, false),
            ThermalParticle(0.80f, 0.52f, 1.2f, 0.65f, 0.3f, true),
            ThermalParticle(0.42f, 0.62f, 1.7f, 0.95f, 0.75f, false),
            ThermalParticle(0.58f, 0.78f, 1.4f, 0.7f, 0.4f, true),
            ThermalParticle(0.08f, 0.78f, 1.5f, 0.85f, 0.05f, false),
            ThermalParticle(0.92f, 0.82f, 1.2f, 0.6f, 0.65f, true),
            ThermalParticle(0.50f, 0.22f, 1.9f, 1.0f, 0.25f, false)
        )
    }

    val scrollParallax = (listState.centerItemIndex * 40f + listState.centerItemScrollOffset * 0.25f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val parallaxY = -(scrollParallax % h)
        val heroCenter = Offset(w / 2f, (h * 0.38f + parallaxY * 0.2f).coerceIn(h * 0.2f, h * 0.7f))

        // 1. Concentric Thermal Equilibrium Contours
        if (isResumed) {
            for (i in 0 until 3) {
                val contourP = (contourPhase + i * 0.33f) % 1.0f
                val contourRadius = 45.dp.toPx() + contourP * (w * 0.58f)
                val contourAlpha = (1f - contourP).let { it * it } * 0.20f

                drawCircle(
                    color = primaryColor.copy(alpha = contourAlpha),
                    radius = contourRadius,
                    center = heroCenter,
                    style = Stroke(width = 1.1.dp.toPx())
                )
            }
        }

        // 2. Breathing Thermodynamic Aurora Field
        val breathAlpha = if (isResumed) {
            0.11f + 0.07f * kotlin.math.sin(contourPhase * 2.0 * Math.PI).toFloat()
        } else {
            0.13f
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryColor.copy(alpha = breathAlpha * 1.1f),
                    secondaryColor.copy(alpha = breathAlpha * 0.5f),
                    Color.Transparent
                ),
                center = heroCenter,
                radius = w * 0.70f
            ),
            radius = w * 0.70f,
            center = heroCenter
        )

        // 3. Floating Thermodynamic Micro-Particles
        for (p in particles) {
            val drift = if (isResumed) driftPhase else 0.5f
            val rawCy = p.yNorm * h - drift * h * p.speed + parallaxY * 0.35f
            val cy = ((rawCy % h) + h) % h
            val swayX = kotlin.math.sin((drift + p.phaseOffset) * 2.0 * Math.PI).toFloat() * 7.dp.toPx()
            val cx = (p.xNorm * w + swayX).coerceIn(4.dp.toPx(), w - 4.dp.toPx())

            val alpha = if (isResumed) {
                val cycle = ((drift + p.phaseOffset) % 1.0f)
                val sinVal = kotlin.math.sin(cycle * 2.0 * Math.PI).toFloat()
                0.22f + 0.35f * (sinVal + 1f)
            } else {
                0.30f
            }

            val pColor = if (p.isWarm) primaryColor else secondaryColor
            val rPx = p.radiusDp.dp.toPx()

            drawCircle(
                color = pColor.copy(alpha = alpha),
                radius = rPx,
                center = Offset(cx, cy)
            )

            if (rPx >= 1.5.dp.toPx() && alpha > 0.45f) {
                drawCircle(
                    color = Color.White.copy(alpha = (alpha - 0.45f) * 0.5f),
                    radius = rPx * 0.4f,
                    center = Offset(cx - rPx * 0.2f, cy - rPx * 0.2f)
                )
            }
        }
    }
}


@Composable
private fun CircadianThermalCurveCard(vitals: RingConnVitals) {
    val hasData = vitals.skinTempBaseline > 0f || vitals.skinTempOffset != 0f
    val offset = vitals.skinTempOffset
    val curveProgress = remember { Animatable(0f) }

    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var lastStep by remember { mutableStateOf<Int?>(null) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(scrubFraction) {
        if (scrubFraction != null) {
            delay(2500L)
            scrubFraction = null
            lastStep = null
        }
    }

    LaunchedEffect(vitals.skinTempOffset) {
        curveProgress.snapTo(0f)
        curveProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(950, easing = FastOutSlowInEasing)
        )
    }

    val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f) }
    val curveStrokeWidth = with(LocalDensity.current) { 2.dp.toPx() }
    val curveStroke = remember(curveStrokeWidth) {
        Stroke(width = curveStrokeWidth, cap = StrokeCap.Round)
    }
    val curveStrokeBrush = remember {
        Brush.horizontalGradient(
            listOf(TempCool, Color(0xFF00E676), TempEmeraldSoft, Color(0xFF00E5FF))
        )
    }
    val fillBrush = remember {
        Brush.verticalGradient(
            listOf(TempEmeraldSoft.copy(alpha = 0.25f), TempCool.copy(alpha = 0.25f))
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.temp_circadian_curve_title),
                    color = Color.White,
                    fontSize = TextBody,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 2,
                    lineHeight = TextBody,
                    overflow = TextOverflow.Ellipsis
                )

                if (scrubFraction != null) {
                    val frac = scrubFraction!!
                    val scrubMinutes = (frac * 480f).roundToInt()
                    val totalMin = (23 * 60 + scrubMinutes) % (24 * 60)
                    val scrubHour = totalMin / 60
                    val scrubMin = totalMin % 60
                    val timeStr = String.format(Locale.US, "%02d:%02d", scrubHour, scrubMin)

                    // Estimate interpolated thermal deviation across nocturnal curve
                    val coolingNadir = -0.4f
                    val scrubOffsetVal = if (frac <= 0.5f) {
                        offset + (coolingNadir - offset) * (frac / 0.5f)
                    } else {
                        coolingNadir + (offset - coolingNadir) * ((frac - 0.5f) / 0.5f)
                    }
                    val offsetStr = if (scrubOffsetVal >= 0f) {
                        String.format(Locale.US, "+%.1f°C", scrubOffsetVal)
                    } else {
                        String.format(Locale.US, "%.1f°C", scrubOffsetVal)
                    }

                    Box(
                        modifier = Modifier
                            .clip(ShapePill)
                            .background(TempEmeraldSoft.copy(alpha = 0.22f))
                            .border(BorderStroke(1.dp, TempEmeraldSoft.copy(alpha = 0.5f)), ShapePill)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$timeStr · $offsetStr",
                            color = TempEmeraldSoft,
                            fontSize = TextCaption,
                            fontWeight = FontWeight.Bold,
                            style = TabularNums
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.sleep_stat),
                        color = TempEmeraldSoft,
                        fontSize = TextCaption,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Nocturnal Circadian Spline Canvas with interactive touch/drag scrubber
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { offsetPos ->
                            val frac = (offsetPos.x / size.width.toFloat()).coerceIn(0f, 1f)
                            scrubFraction = frac
                            val step = (frac * 16).toInt().coerceIn(0, 15)
                            if (step != lastStep) {
                                lastStep = step
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { offsetPos ->
                                val frac = (offsetPos.x / size.width.toFloat()).coerceIn(0f, 1f)
                                scrubFraction = frac
                                val step = (frac * 16).toInt().coerceIn(0, 15)
                                if (step != lastStep) {
                                    lastStep = step
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                scrubFraction = frac
                                val step = (frac * 16).toInt().coerceIn(0, 15)
                                if (step != lastStep) {
                                    lastStep = step
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        )
                    }
            ) {
                val totalW = size.width
                val totalH = size.height - 12.dp.toPx()
                val midY = totalH / 2f

                // Shaded Optimal Corridor (Band from -0.5°C to +0.5°C)
                val bandTopY = midY - (totalH * 0.35f)
                val bandBottomY = midY + (totalH * 0.35f)
                val bandHeight = bandBottomY - bandTopY

                drawRoundRect(
                    color = TempOptimal.copy(alpha = 0.08f),
                    topLeft = Offset(0f, bandTopY),
                    size = Size(totalW, bandHeight),
                    cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
                )

                drawLine(
                    color = TempEmeraldSoft.copy(alpha = 0.25f),
                    start = Offset(0f, bandTopY),
                    end = Offset(totalW, bandTopY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dashEffect
                )
                drawLine(
                    color = TempCool.copy(alpha = 0.25f),
                    start = Offset(0f, bandBottomY),
                    end = Offset(totalW, bandBottomY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dashEffect
                )

                // Central Reference 0.0°C Baseline Line
                drawLine(
                    color = Color.White.copy(alpha = 0.45f),
                    start = Offset(0f, midY),
                    end = Offset(totalW, midY),
                    strokeWidth = 1.dp.toPx()
                )

                // Smooth Cubic Spline Nocturnal Curve
                val p0 = Offset(0f, midY - (offset * 10.dp.toPx()))
                val p1 = Offset(totalW * 0.25f, midY + 7.dp.toPx())
                val p2 = Offset(totalW * 0.50f, midY + 18.dp.toPx()) // Nadir (~ -0.4°C cooling)
                val p3 = Offset(totalW * 0.75f, midY + 3.dp.toPx())
                val p4 = Offset(totalW, midY - (offset * 12.dp.toPx()).coerceIn(-12.dp.toPx(), 12.dp.toPx()))

                val splinePath = Path().apply {
                    moveTo(p0.x, p0.y)
                    cubicTo(totalW * 0.12f, p0.y, totalW * 0.18f, p1.y, p1.x, p1.y)
                    cubicTo(totalW * 0.35f, p1.y + 8.dp.toPx(), totalW * 0.42f, p2.y, p2.x, p2.y)
                    cubicTo(totalW * 0.58f, p2.y, totalW * 0.65f, p3.y, p3.x, p3.y)
                    cubicTo(totalW * 0.85f, p3.y - 6.dp.toPx(), totalW * 0.92f, p4.y, p4.x, p4.y)
                }

                // Fill area under/above curve
                val fillPath = Path().apply {
                    addPath(splinePath)
                    lineTo(totalW, midY)
                    lineTo(0f, midY)
                    close()
                }

                // Progressive draw-on clip from left to right
                clipRect(right = totalW * curveProgress.value) {
                    drawPath(
                        path = fillPath,
                        brush = fillBrush
                    )

                    drawPath(
                        path = splinePath,
                        brush = curveStrokeBrush,
                        style = curveStroke
                    )

                    // Point Markers
                    drawCircle(color = TempCool, radius = 3.5.dp.toPx(), center = p2)
                    drawCircle(color = Color.White, radius = 1.8.dp.toPx(), center = p2)

                    drawCircle(color = TempOptimal, radius = 3.5.dp.toPx(), center = p4)
                    drawCircle(color = Color.White, radius = 1.8.dp.toPx(), center = p4)
                }

                // Subtle glowing indicator dot riding the tip of the curve
                if (curveProgress.value > 0.01f && scrubFraction == null) {
                    val tip = sampleCircadianSpline(
                        t = curveProgress.value,
                        totalW = totalW,
                        p0 = p0,
                        p1 = p1,
                        p2 = p2,
                        p3 = p3,
                        p4 = p4,
                        c1_2_offset = 8.dp.toPx(),
                        c3_2_offset = 6.dp.toPx()
                    )
                    drawCircle(
                        color = TempEmeraldSoft.copy(alpha = 0.35f),
                        radius = 5.dp.toPx(),
                        center = tip
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 2.dp.toPx(),
                        center = tip
                    )
                }

                // Interactive scrubber: vertical laser cursor line + glowing dot at sampled position
                if (scrubFraction != null) {
                    val scrubPt = sampleCircadianSpline(
                        t = scrubFraction!!,
                        totalW = totalW,
                        p0 = p0,
                        p1 = p1,
                        p2 = p2,
                        p3 = p3,
                        p4 = p4,
                        c1_2_offset = 8.dp.toPx(),
                        c3_2_offset = 6.dp.toPx()
                    )

                    // Vertical laser cursor line
                    drawLine(
                        color = TempEmeraldSoft.copy(alpha = 0.85f),
                        start = Offset(scrubPt.x, 0f),
                        end = Offset(scrubPt.x, totalH),
                        strokeWidth = 1.5.dp.toPx()
                    )

                    // Glowing dot
                    drawCircle(
                        color = TempEmeraldSoft.copy(alpha = 0.35f),
                        radius = 7.dp.toPx(),
                        center = scrubPt
                    )
                    drawCircle(
                        color = TempEmeraldSoft,
                        radius = 3.5.dp.toPx(),
                        center = scrubPt
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 1.8.dp.toPx(),
                        center = scrubPt
                    )
                }
            }

            // X-Axis Sleep Time Labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "23h", color = Color(0xFF94A3B8), fontSize = TextCaption, style = TabularNums)
                Text(text = "02h", color = Color(0xFF94A3B8), fontSize = TextCaption, style = TabularNums)
                Text(text = "05h", color = Color(0xFF94A3B8), fontSize = TextCaption, style = TabularNums)
                Text(text = "08h", color = Color(0xFF94A3B8), fontSize = TextCaption, style = TabularNums)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Centered 3-Column Metrics Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val col1Value = if (hasData) String.format(Locale.US, "%+.1f°C", vitals.skinTempOffset) else "--.-°C"
                val col2Value = if (vitals.skinTempBaseline > 0f) String.format(Locale.US, "%.1f°C", vitals.skinTempBaseline) else "--.-°C"
                val col3Value = if (vitals.skinTempBaseline > 0f) String.format(Locale.US, "%.1f°C", vitals.skinTempBaseline + vitals.skinTempOffset) else "--.-°C"

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = col1Value,
                        color = if (hasData) TempCool else LabelSilver,
                        fontSize = TextBody,
                        style = TabularNums,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.temp_deviation_stat),
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
                        text = col2Value,
                        color = if (vitals.skinTempBaseline > 0f) Color.White else LabelSilver,
                        fontSize = TextBody,
                        style = TabularNums,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.baseline_stat),
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
                        text = col3Value,
                        color = if (vitals.skinTempBaseline > 0f) TempOptimal else LabelSilver,
                        fontSize = TextBody,
                        style = TabularNums,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.temp_absolute_est),
                        color = Color(0xFF94A3B8),
                        fontSize = TextCaption,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

private fun sampleCircadianSpline(
    t: Float,
    totalW: Float,
    p0: Offset,
    p1: Offset,
    p2: Offset,
    p3: Offset,
    p4: Offset,
    c1_2_offset: Float,
    c3_2_offset: Float
): Offset {
    val clamped = t.coerceIn(0f, 1f)
    val s: Offset
    val c1: Offset
    val c2: Offset
    val e: Offset
    val u: Float

    when {
        clamped < 0.25f -> {
            s = p0
            c1 = Offset(totalW * 0.12f, p0.y)
            c2 = Offset(totalW * 0.18f, p1.y)
            e = p1
            u = clamped / 0.25f
        }
        clamped < 0.50f -> {
            s = p1
            c1 = Offset(totalW * 0.35f, p1.y + c1_2_offset)
            c2 = Offset(totalW * 0.42f, p2.y)
            e = p2
            u = (clamped - 0.25f) / 0.25f
        }
        clamped < 0.75f -> {
            s = p2
            c1 = Offset(totalW * 0.58f, p2.y)
            c2 = Offset(totalW * 0.65f, p3.y)
            e = p3
            u = (clamped - 0.50f) / 0.25f
        }
        else -> {
            s = p3
            c1 = Offset(totalW * 0.85f, p3.y - c3_2_offset)
            c2 = Offset(totalW * 0.92f, p4.y)
            e = p4
            u = (clamped - 0.75f) / 0.25f
        }
    }
    val invU = 1f - u
    val y = (invU * invU * invU * s.y) +
            (3f * invU * invU * u * c1.y) +
            (3f * invU * u * u * c2.y) +
            (u * u * u * e.y)
    return Offset(totalW * clamped, y)
}

private fun getThermalColor(progress: Float): Color {
    val p = progress.coerceIn(0f, 1f)
    return if (p <= 0.5f) {
        lerp(TempCool, TempOptimal, p / 0.5f)
    } else {
        lerp(TempOptimal, TempWarmAlert, (p - 0.5f) / 0.5f)
    }
}

@Composable
private fun TempBandRow(
    label: String,
    range: String,
    desc: String,
    color: Color,
    isActive: Boolean
) {
    val bgColor by animateColorAsState(
        targetValue = if (isActive) color.copy(alpha = 0.18f) else Color.Transparent,
        animationSpec = tween(350),
        label = "tempBandBg"
    )
    val labelColor by animateColorAsState(
        targetValue = if (isActive) Color.White else Color(0xFFB0BEC5),
        animationSpec = tween(350),
        label = "tempBandLabel"
    )
    val rangeColor by animateColorAsState(
        targetValue = if (isActive) color else Color(0xFF78909C),
        animationSpec = tween(350),
        label = "tempBandRange"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeChip)
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 5.dp)
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
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = label,
                    color = labelColor,
                    fontSize = TextLabel,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = desc,
            color = Color(0xFF90A4AE),
            fontSize = TextCaption,
            lineHeight = TextBody,
            modifier = Modifier.padding(start = 14.dp)
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
        val infiniteTransition = rememberInfiniteTransition(label = "temp_aura_pulse")
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
 * 270-degree animated circular gauge for Skin Temperature in One UI Watch style.
 */
@Composable
private fun TempDialGauge(
    offset: Float,
    hasData: Boolean = true,
    modifier: Modifier = Modifier
) {
    val animProgress = remember { Animatable(if (hasData) 0.5f else 0f) }
    // Map -1.0..+1.0°C to 0..1f (0.0°C baseline = 0.5f)
    val targetProgress = if (hasData) ((offset + 1.0f) / 2.0f).coerceIn(0.05f, 0.95f) else 0f

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
                TempCool,
                TempOptimal,
                TempWarmAlert,
                TempOptimal
            )
        )
    }

    Canvas(modifier = modifier) {
        val diameter = size.minDimension - strokeWidth
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        val activeGlowColor = getThermalColor(animProgress.value)

        // Background track (270 degrees from 135 to 405)
        drawArc(
            color = activeGlowColor.copy(alpha = 0.15f),
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = dialStroke
        )

        // Progress Arc
        if (hasData && animProgress.value > 0f) {
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

            // Dynamic thermal color indicator dot riding the tip of the dial arc
            val angleRad = Math.toRadians((135f + sweep).toDouble())
            val radius = diameter / 2f
            val cx = topLeft.x + radius + (radius * Math.cos(angleRad)).toFloat()
            val cy = topLeft.y + radius + (radius * Math.sin(angleRad)).toFloat()
            drawCircle(
                color = activeGlowColor.copy(alpha = 0.45f),
                radius = 5.dp.toPx(),
                center = Offset(cx, cy)
            )
            drawCircle(
                color = Color.White,
                radius = 2.dp.toPx(),
                center = Offset(cx, cy)
            )
        }
    }
}

@Preview(
    device = "id:wearos_small_round",
    showSystemUi = true
)
@Composable
fun TemperatureDetailPreview() {
    TemperatureDetailContent(
        vitals = RingConnVitals(
            skinTempOffset = 0.3f,
            skinTempBaseline = 36.4f,
            refreshTimestamp = System.currentTimeMillis() / 1000L,
            isConnected = true
        )
    )
}
