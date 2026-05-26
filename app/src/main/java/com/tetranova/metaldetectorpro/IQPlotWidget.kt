package com.tetranova.metaldetectorpro
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

// ============================================================================
// IQ PLOT WIDGET
// ============================================================================
private const val TRAIL_LENGTH = 30

@Composable
fun IQPlotWidget(
    vm: DetectorViewModelV2,
    modifier: Modifier = Modifier.size(220.dp)
) {
    val trail = remember { mutableStateListOf<IQVector>() }
    val current = vm.iqCurrent
    val baseline = vm.iqBaseline
    val vr = vm.vectorResult
    val threshold = vm.vectorProcessor.enterDetectionThreshold

    LaunchedEffect(current) {
        trail.add(current)
        if (trail.size  > TRAIL_LENGTH) trail.removeAt(0)
    }

    val maxRange = maxOf(
        threshold * 4f,
        (vr?.vectorDistance ?: 0f) * 2f,
        0.05f
    )

    Column(
        modifier = modifier.background(Color(0xFF0D0D0D)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "PIANO I/Q (ORTOGONALE) ", color = Color(0xFF666666), fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp)
        )

        Canvas(modifier = Modifier.weight(1f). fillMaxWidth().padding(8.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val scale = (size.minDimension / 2f) / maxRange
            drawGrid(cx, cy,  scale, maxRange)

            // Il cerchio rappresenta il limite di guardia dell'energia rotazionale tangenziale
            drawCircle(
                color = Color(0xFF1A3A1A), radius = threshold * scale,
                center = Offset(cx, cy), style = Stroke(width = 1.dp.toPx())
            )
            drawTrail(trail, baseline, cx, cy, scale)
            drawCircle(color = Color(0xFF00FF88), radius = 5.dp.toPx(), center = Offset(cx, cy))

            val isDetected = vr?.isDetected == true
            val isFerroso = vr?.isFerroso == true
            val dotColor =  when {
                !isDetected -> Color(0xFF888888)
                isFerroso   -> Color(0xFFFF6666)
                else        -> Color(0xFF66FFCC)
            }
            val dotRadius = if (isDetected) 8.dp.toPx() else 5.dp.toPx()
            val dI = current.i - baseline.i
            val dQ = current.q - baseline.q
            val px = cx + dI * scale
            val py = cy - dQ * scale

            if (isDetected) {
                drawCircle(
                    color = dotColor.copy(alpha = 0.25f), radius = dotRadius * 2.5f,
                    center = Offset(px, py)
                )
                drawLine(
                    color = dotColor.copy(alpha = 0.6f), start = Offset(cx, cy),
                    end = Offset(px, py), strokeWidth = 1.5.dp.toPx()
                )
            }
            drawCircle(color = dotColor, radius = dotRadius, center = Offset(px, py))
        }

        val vr2  = vm.vectorResult
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Text(
                "e_tang=%.3f ".format(vr2?.vectorDistance ?: 0f), color = Color(0xFFAAAAAA),
                fontSize = 9.sp, fontFamily = FontFamily.Monospace
            )
            Text(
                "θ=%.1f° ".format(vr2?.relativeAngleDeg ?: 0f), color = Color(0xFFAAAAAA),
                fontSize = 9.sp, fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

private fun DrawScope.drawGrid(cx: Float, cy: Float, scale: Float, maxRange: Float) {
    val gridColor = Color(0xFF2A2A2A)
    val step = maxRange / 2f * scale
    for (sign in listOf(-1f, 1f)) {
        drawLine(gridColor, Offset(cx + sign * step, 0f), Offset(cx + sign * step, size.height))
        drawLine(gridColor, Offset(0f, cy + sign * step), Offset(size.width, cy + sign * step))
    }
    drawLine(Color(0xFF444444), Offset(cx, 0f), Offset(cx, size.height))
    drawLine(Color(0xFF444444), Offset(0f, cy), Offset(size.width, cy))
}

private fun DrawScope.drawTrail(
    trail: List<IQVector>, baseline: IQVector, cx: Float, cy: Float, scale: Float
) {
    if (trail.size < 2) return
    for (i in 1 until trail.size) {
        val alpha = (i.toFloat() / trail.size) * 0.7f
        val v0 = trail[i - 1]; val v1 = trail[i]
        val x0 = cx + (v0.i - baseline.i) * scale; val y0 = cy - (v0.q - baseline.q) * scale
        val x1 = cx + (v1.i - baseline.i) * scale; val y1 = cy - (v1.q - baseline.q) * scale
        drawLine(
            color = Color.White.copy(alpha = alpha), start = Offset(x0, y0),
            end = Offset(x1, y1), strokeWidth = 1.5.dp.toPx()
        )
    }
}

// ============================================================================
// SCHERMATA PRINCIPALE V2 - SPAZIO OTTIMIZZATO SENZA TOPBAR
// ============================================================================
@Composable
fun MainScanDisplayV2(
    vm: DetectorViewModelV2,
    modifier: Modifier = Modifier
) {
    val res = vm.analysis
    val params = vm.params
    val liveDelta = vm.liveDelta
    val phaseDiff = vm.phaseDiff
    val vr = vm.vectorResult
    val sogliaAttuale = vm.vectorProcessor.enterDetectionThreshold
    val maxScale = sogliaAttuale * 5f
    val deltaNorm = (abs(liveDelta) / maxScale).coerceIn(0f, 1f)

    val phaseNorm = vm.phaseNormalized

    // Sincronizzazione dinamica dello stato Compose con i parametri atomici del ViewModel
    var sensAmpiezza by remember(vm.sensAmpiezza) { mutableStateOf(vm.sensAmpiezza) }
    var discLow      by remember(vm.discLow) { mutableStateOf(vm.discLow) }
    var discHigh     by remember(vm.discHigh) { mutableStateOf(vm.discHigh) }
    var phaseHysteresis by remember(vm.phaseHysteresis) { mutableStateOf(vm.phaseHysteresis) }

    val batteryVoltage = params.battery
    val batteryAvailable = batteryVoltage  > 0f
    val isBatteryLow = batteryAvailable  && batteryVoltage  < 14.0f

    val batteryColor = when {
        !batteryAvailable -> Color(0xFF555555)
        isBatteryLow      -> Color(0xFFFF6B6B)
        else              -> Color(0xFF4ECDC4)
    }
    val batteryFillColor = when {
        !batteryAvailable -> Color(0xFF333333)
        isBatteryLow      -> Color(0xFFFF5252)
        else              -> Color(0xFF2ECC71)
    }
    val batteryLevel = if (batteryAvailable)
        ((batteryVoltage - 12.0f) / (16.8f - 12.0f)).coerceIn(0f, 1f)
    else 0f

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    )  {
        // Header compatto: Batteria + Stato Blocco Canale
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (batteryAvailable)  "BAT: %.1fV ".format(batteryVoltage) else  "BAT: ---",
                color = batteryColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            BatteryIcon(
                level = batteryLevel, isLow = isBatteryLow,
                fillColor = batteryFillColor, strokeColor = batteryColor,
                modifier = Modifier.size(28.dp, 14.dp)
            )

            val displayType = when (res.type.trim()) {
                "FERRO " ->  "FERROSO "
                "NON_FERRO " ->  "NON FERROSO "
                else -> res.type.trim()
            }
            val statusText = when {
                res.type.trim() ==  "IDLE " ->  "🔍 RICERCA "
                res.isLocked ->  "🔒 $displayType "
                displayType.isNotEmpty() -> displayType
                else ->  "🔍 RICERCA "
            }
            val statusColor = when {
                res.type.trim() ==  "IDLE " -> Color(0xFF888888)
                res.isLocked -> Color(0xFF64FFDA)
                else -> Color(0xFFFFD740)
            }
            Text(
                statusText, color = statusColor, fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = if (res.isLocked) FontWeight.ExtraBold else FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        IQPlotWidget(vm = vm, modifier = Modifier.size(200.dp))
        Spacer(modifier = Modifier.height(12.dp))

        AmplitudeMeter(
            value = deltaNorm, modifier = Modifier.size(140.dp),
            activeColor = if (res.isLocked) Color(0xFF64FFDA) else Color(0xFF888888)
        )
        Text(
            "SPINTA TANGENZIALE: %.4f ".format(vr?.vectorDistance ?: 0f),
            fontSize = 12.sp, color = Color(0xFFAAAAAA)
        )
        Spacer(modifier = Modifier.height(12.dp))

        PhaseMeter(
            phase = phaseNorm, modifier = Modifier.size(140.dp),
            activeColor = if (res.isLocked) Color(0xFF64FFDA) else Color(0xFF888888)
        )
        Text(
            "TORSIONE FASE: %d° ".format(phaseDiff.toInt()),
            fontSize = 12.sp, color = Color(0xFFAAAAAA)
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Pannello di telemetria VDI / Affidabilità / Profondità stimata
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            InfoTileWithTooltip( "CONF ",  "${res.confidence.toInt()}% ",  "Affidabilità classificazione\n0% = incerto, 100% = certo ")
            InfoTileWithTooltip( "VDI ",  "${res.vdi} ",  "Identificazione metallo\n0-30: ferro, 31-60: leghe, 61-99: nobili ")
            InfoTileWithTooltip( "DEPTH ",  "${res.depth.toInt()}cm ",  "Stima profondità bersaglio\nIndicativo: dipende da suolo/target ")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Interfaccia Guadagno e Moltiplicatore K_VECT
        Text(
            "Sensibilità: ${sensAmpiezza.toInt()}  (K=%.2f) ".format(vm.vectorProcessor.K_VECT),
            color = Color(0xFFAAAAAA), fontSize = 12.sp
        )
        Slider(
            value = sensAmpiezza,
            onValueChange = { sensAmpiezza = it; vm.updateSensAmpiezza(it) },
            valueRange = 1f..10f, steps = 8,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor =  Color(0xFF64FFDA), activeTrackColor = Color(0xFF64FFDA)
            )
        )

        // ── NUOVO: Slider Zona Morta IQ (0° → 5°) ───────────────────────────
        Text(
            "Zona Morta IQ: %.1f° ".format(phaseHysteresis),
            color = Color(0xFFAAAAAA), fontSize = 12.sp
        )
        Slider(
            value = phaseHysteresis,
            onValueChange = {
                phaseHysteresis = it
                vm.updatePhaseHysteresis(it)
            },
            valueRange = 0f..5f, steps = 50,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF9FA8DA), activeTrackColor = Color(0xFF9FA8DA)
            )
        )
        Text(
            "0°=min filtro | 1.5°=stabile | 5°=max freeze ",
            color = Color(0xFF666666), fontSize = 9.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        // ── Sezione Discriminazione Spaziale Reale ───────────────────────────
        var showDiscInfo by remember { mutableStateOf(false) }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Discriminazione Angolare Relativa ",
                color = Color(0xFFAAAAAA), fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                "ℹ️ ", color = Color(0xFF66FFCC), fontSize = 14.sp,
                modifier = Modifier.clickable { showDiscInfo = !showDiscInfo }.padding(end = 8.dp)
            )
        }
        if (showDiscInfo)  {
            Box(
                modifier = Modifier
                    .background(Color(0xFF1A1A1A))
                    .padding(8.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    "📐 SOGLIE DI SCOMPOSIZIONE ORTOGONALE\n " +
                            "Confine basso (🟡): Inizio zona Non Ferroso (VLF Target Accettabile)\n " +
                            "Confine alto  (🔴): Inizio zona Ferroso (Sfasamento induttivo)\n\n " +
                            "Grazie all'isolamento radiale, le micro-rotazioni generate da monete profonde sono stabili e protette dal jitter. ",
                    color = Color(0xFFCCCCCC), fontSize = 10.sp,
                    lineHeight = 13.sp, fontFamily = FontFamily.SansSerif
                )
            }
        }

        DiscriminationBar(
            discLow  = discLow,
            discHigh = discHigh,
            modifier = Modifier.fillMaxWidth().height(14.dp).padding(vertical = 2.dp)
        )

        // Slider Confine Basso (AMBIGUO | NON_FERRO) limitato a 45°
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "🟡 Confine basso: %3.0f° ".format(discLow),
                color = Color(0xFFFFD740), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(170.dp)
            )
        }
        Slider(
            value = discLow,
            onValueChange = { newLow ->
                val clamped = newLow.coerceIn(1f, discHigh - 1.5f)
                discLow = clamped
                vm.updateDiscThresholds(clamped, discHigh)
            },
            valueRange  = 1f..45f,
            steps = 44,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor       = Color(0xFFFFD740),
                activeTrackColor = Color(0xFFFFD740)
            )
        )

        // Slider Confine Alto (NON_FERRO | FERRO) accoppiato a 45° massimi
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "🔴 Confine alto:  %3.0f° ".format(discHigh),
                color = Color(0xFFFF6666), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(170.dp)
            )
        }
        Slider(
            value = discHigh,
            onValueChange = { newHigh ->
                val clamped = newHigh.coerceIn(discLow + 1.5f, 45f)
                discHigh = clamped
                vm.updateDiscThresholds(discLow, clamped)
            },
            valueRange  = 1f..45f,
            steps = 44,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor       = Color(0xFFFF6666),
                activeTrackColor = Color(0xFFFF6666)
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { vm.performCalibration() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1E3A2F), contentColor = Color(0xFF64FFDA)
            )
        ) {
            Text( "CALIBRA ORA (I/Q) ", fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ============================================================================
// COMPONENTI UI AUSILIARI
// ============================================================================
@Composable
fun InfoTileWithTooltip(label: String, value: String, tooltip: String) {
    var showTooltip by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(70.dp).clickable { showTooltip = !showTooltip }
    ) {
        Text(label, color = Color(0xFF888888), fontSize = 11.sp)
        Text(
            value, color = Color(0xFFE0E0E0), fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        if (showTooltip) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF1A1A1A))
                    .padding(4.dp).width(120.dp)
            ) {
                Text(
                    tooltip, color = Color(0xFFCCCCCC), fontSize = 9.sp,
                    lineHeight = 11.sp, fontFamily = FontFamily.SansSerif
                )
            }
        }
    }
}

@Composable
fun BatteryIcon(
    level: Float, isLow: Boolean,
    fillColor: Color = Color(0xFF4CAF50), strokeColor: Color = Color(0xFF888888),
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val width = size.width; val height = size.height
        val strokeWidth = 1.5.dp.toPx()
        drawRoundRect(
            color = strokeColor, topLeft = Offset(0f, 0f),
            size = Size(width * 0.85f, height),
            style = Stroke(width = strokeWidth),
            cornerRadius = CornerRadius(2.dp.toPx())
        )
        drawRect(
            color = strokeColor,
            topLeft = Offset(width * 0.85f, height * 0.3f),
            size = Size(width * 0.15f, height * 0.4f)
        )
        val fillWidth = (width * 0.85f - strokeWidth * 2) * level
        if (fillWidth > 0) drawRoundRect(
            color = fillColor,
            topLeft = Offset(strokeWidth, strokeWidth),
            size = Size(fillWidth, height - strokeWidth * 2),
            cornerRadius = CornerRadius(1.dp.toPx())
        )
    }
}

@Composable
fun AmplitudeMeter(
    value: Float, modifier: Modifier = Modifier.size(150.dp),
    activeColor: Color = Color(0xFF64FFDA), inactiveColor: Color = Color(0xFF2A2A2A)
) {
    Canvas(modifier = modifier) {
        drawArc(
            color = inactiveColor, startAngle = 135f, sweepAngle = 270f,
            useCenter = false, style = Stroke(width = 12.dp.toPx())
        )
        drawArc(
            color = activeColor, startAngle = 135f, sweepAngle = value * 270f,
            useCenter = false, style = Stroke(width = 12.dp.toPx())
        )
    }
}

@Composable
fun PhaseMeter(
    phase: Float, modifier: Modifier = Modifier.size(160.dp),
    activeColor: Color = Color(0xFF64FFDA), inactiveColor: Color = Color(0xFF2A2A2A)
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2 - 12.dp.toPx()
        val strokeWidth = 8.dp.toPx()
        drawArc(
            color = inactiveColor, startAngle = 180f, sweepAngle = 180f,
            useCenter = false, style = Stroke(width = strokeWidth),
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2)
        )
        val angle = 270f + (phase * 90f)
        val needleLength = radius - strokeWidth / 2
        val needleEnd = Offset(
            center.x + needleLength * cos(Math.toRadians(angle.toDouble())).toFloat(),
            center.y + needleLength * sin(Math.toRadians(angle.toDouble())).toFloat()
        )
        drawLine(
            color = activeColor, start = center, end = needleEnd,
            strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round
        )
        drawCircle(color = activeColor, radius = 6.dp.toPx(), center = center)
    }
}

// ============================================================================
// DISCRIMINATION BAR - CONFIGURATA SU RANGE MASSIMO A 45°
// ============================================================================
@Composable
fun DiscriminationBar(
    discLow:  Float,
    discHigh: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val range = 45f // Allineato al limite effettivo di scomposizione angolare del processore
        val xLow  = (discLow  / range) * w
        val xHigh = (discHigh / range) * w

        drawRect(
            color    = Color(0xFF555555),
            topLeft  = Offset(0f, 0f),
            size     = Size(xLow.coerceIn(0f, w), h)
        )
        drawRect(
            color    = Color(0xFF1A4A3A),
            topLeft  = Offset(xLow.coerceIn(0f, w), 0f),
            size     = Size((xHigh - xLow).coerceIn(0f, w), h)
        )
        drawRect(
            color    = Color(0xFF4A1A1A),
            topLeft  = Offset(xHigh.coerceIn(0f, w), 0f),
            size     = Size((w - xHigh).coerceIn(0f, w), h)
        )

        drawLine(
            color       = Color(0xFFFFD740),
            start       = Offset(xLow.coerceIn(0f, w), 0f),
            end         = Offset(xLow.coerceIn(0f, w), h),
            strokeWidth = 2.dp.toPx()
        )
        drawLine(
            color       = Color(0xFFFF6666),
            start       = Offset(xHigh.coerceIn(0f, w), 0f),
            end         = Offset(xHigh.coerceIn(0f, w), h),
            strokeWidth = 2.dp.toPx()
        )
    }
}