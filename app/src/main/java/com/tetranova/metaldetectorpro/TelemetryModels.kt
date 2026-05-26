package com.tetranova.metaldetectorpro
//TelemetryModels.kt
data class TelemetryData(
    val delta: Float,
    val phase: Float,
    val vdi: Int = 0,              // ← NUOVO: valore discriminazione 0-99
    val confidence: Float = 0f,       // ← NUOVO: confidence 0-100%
    val metalType: String = "IDLE",   // ← NUOVO: "FERRO", "NON_FERRO", "AMBIGUO", "IDLE"
    val isDetected: Boolean = false,  // ← NUOVO: stato detection
    val depth: Float = 0f,            // ← NUOVO: stima profondità (cm)
    val timestamp: Long = System.currentTimeMillis()
)

data class DeviceParams(
    val battery: Float = 0f,
    val baseline: Float = 512.0f,
    val threshold: Float = 10.0f,
    val groundPhase: Float = 0f,
    val frequency: Float = 0f,
    val batteryReady: Boolean = false,
    val transportMode: String = "BT"   // "BT" o "USB" ricevuto dal firmware
)

data class AnalysisResult(
    val vdi: Int = 0,
    val type: String = "IDLE",
    val confidence: Float = 0f,
    val amplitude: Float = 0f,
    val isLocked: Boolean = false,
    val depth: Float = 0f
)

sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Connecting : ConnectionStatus()
    data class Connected(val deviceName: String) : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}