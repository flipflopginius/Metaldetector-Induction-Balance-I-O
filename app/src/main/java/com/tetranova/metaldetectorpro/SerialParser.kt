package com.tetranova.metaldetectorpro

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.ArrayDeque
import kotlin.math.*

class SerialParser(
    private val telemetryFlow: MutableSharedFlow<TelemetryData>,
    private val paramsFlow: MutableStateFlow<DeviceParams>,
    private val consoleFlow: MutableSharedFlow<String>,
    private val onPong: () -> Unit = {}
) {
    private var lastValidPhase: Float = 0f
    private val PHASE_JUMP_THRESHOLD = 30f

    private val BATTERY_AVG_WINDOW = 4
    private val batteryWindow = ArrayDeque<Float>(BATTERY_AVG_WINDOW)

    private fun normalize180(angle: Float): Float {
        var a = angle % 360f
        if (a > 180f) a -= 360f
        if (a <= -180f) a += 360f
        return a
    }

    fun parse(line: String) {
        val cleanLine = line.trimEnd('\r').trim()
        Log.d("Parser", "📥 RAW: '$cleanLine'")
        try {
            when {
                cleanLine.startsWith("B:") -> {
                    val payload = cleanLine.substring(2).trim()
                    val samples = payload.split(';')
                    val currentGroundPhase = paramsFlow.value.groundPhase

                    for (sample in samples) {
                        val cleanSample = sample.trimEnd('\r').trim()
                        if (cleanSample.isEmpty()) continue

                        val commaIdx = cleanSample.indexOf(',')
                        if (commaIdx <= 0 || commaIdx == cleanSample.lastIndex) continue

                        val deltaStr = cleanSample.substring(0, commaIdx).trim()
                        val phaseStr = cleanSample.substring(commaIdx + 1).trim()

                        val delta = deltaStr.toFloatOrNull()
                        val rawPhase = phaseStr.toFloatOrNull()

                        if (delta == null || rawPhase == null) continue
                        if (delta.isNaN() || rawPhase.isNaN()) continue
                        if (rawPhase !in -180f..180f) continue

                        var phase = rawPhase
                        if (abs(currentGroundPhase) > 10f) {
                            phase = normalize180(rawPhase - currentGroundPhase)
                            if (abs(phase - lastValidPhase) > PHASE_JUMP_THRESHOLD) {
                                phase = lastValidPhase
                            } else {
                                lastValidPhase = phase
                            }
                        }

                        telemetryFlow.tryEmit(
                            TelemetryData(
                                delta = delta,
                                phase = phase,
                                vdi = 0,
                                confidence = 0f,
                                metalType = "IDLE",
                                isDetected = false,
                                depth = 0f
                            )
                        )
                    }
                }

                cleanLine == "PONG" -> onPong()

                cleanLine == "MSG:MODE_BT" -> {
                    // 🔄 Reset parser automatico sullo switch → risolve bug #2
                    reset()
                    paramsFlow.value = paramsFlow.value.copy(transportMode = "BT")
                    consoleFlow.tryEmit("🔵 Modalità Bluetooth attiva")
                }
                cleanLine == "MSG:MODE_USB" -> {
                    // 🔄 Reset parser automatico sullo switch → risolve bug #2
                    reset()
                    paramsFlow.value = paramsFlow.value.copy(transportMode = "USB")
                    consoleFlow.tryEmit("🟢 Modalità USB attiva")
                }

                cleanLine.startsWith("CALIB:") -> {
                    val content = cleanLine.substring(6)
                    val parts = content.split(",")
                    val baseline = parts[0].toFloatOrNull() ?: 0f
                    val phaseOff = if (parts.size > 1 && parts[1].startsWith("PHASE_OFF:")) {
                        parts[1].substring(10).toFloatOrNull() ?: 0f
                    } else 0f
                    lastValidPhase = 0f
                    paramsFlow.value = paramsFlow.value.copy(
                        baseline = baseline,
                        groundPhase = phaseOff
                    )
                    Log.d("Parser", "📊 Calibrazione: baseline=$baseline, phaseOff=$phaseOff")
                }

                cleanLine.startsWith("INF:") -> {
                    val parts = cleanLine.substring(4).split(",")
                    if (parts.size >= 2) {
                        val batteryRaw = parts[0].toFloatOrNull() ?: 0f
                        val freq = parts[1].toFloatOrNull() ?: 0f
                        val batteryCorrected = batteryRaw * 2.05f

                        if (batteryWindow.size >= BATTERY_AVG_WINDOW) batteryWindow.removeFirst()
                        batteryWindow.addLast(batteryCorrected)

                        val batteryAvg = batteryWindow.sum() / batteryWindow.size
                        val isReady = batteryWindow.size >= BATTERY_AVG_WINDOW

                        paramsFlow.value = paramsFlow.value.copy(
                            battery = batteryAvg,
                            frequency = freq,
                            batteryReady = isReady
                        )
                        Log.d("Parser", "🔋 Battery: $batteryAvg V, Freq: $freq Hz")
                    }
                }

                cleanLine.startsWith("MSG:") -> {
                    val msg = cleanLine.substring(4)
                    consoleFlow.tryEmit("ℹ️ $msg")
                    Log.d("Parser", "💬 MSG: $msg")
                }

                else -> {
                    Log.d("Parser", "⚠️ Riga non riconosciuta: '$cleanLine'")
                }
            }
        } catch (e: Exception) {
            Log.e("Parser", "Parse error: $cleanLine", e)
        }
    }

    fun reset() {
        lastValidPhase = 0f
        batteryWindow.clear()
        Log.d("Parser", "Parser resettato")
    }
}