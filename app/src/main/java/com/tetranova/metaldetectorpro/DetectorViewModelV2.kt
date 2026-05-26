package com.tetranova.metaldetectorpro
import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*

class DetectorViewModelV2(application: Application) : AndroidViewModel(application) {
    val context: Context = getApplication<Application>().applicationContext
    val usb = UsbCdcManager(context)

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    val vectorProcessor = VectorProcessor().apply { enableDebugLog = true }
    val vectorAudio     = VectorAudioEngine()

    var analysis           by mutableStateOf(AnalysisResult())
    var params             by mutableStateOf(DeviceParams())
    var liveDelta          by mutableStateOf(0f)
    var phaseDiff          by mutableStateOf(0f)
    var phaseNormalized    by mutableStateOf(0f)
    var phaseFiltered      by mutableStateOf(0f)
    var vectorResult       by mutableStateOf<VectorResult?>(null)
    var iqCurrent          by mutableStateOf(IQVector(0f, 0f))
    var iqBaseline         by mutableStateOf(IQVector(0f, 0f))
    var normalizedDistance by mutableStateOf(0f)
    var vectorSpeed        by mutableStateOf(0f)
    var vectorCurvature    by mutableStateOf(0f)
    var linearity          by mutableStateOf(1f)
    var isAngleValid       by mutableStateOf(false)   // esposto alla UI per grayout lancetta
    var console            = mutableStateListOf<String>()
    var cmd                by mutableStateOf("  ")

    private val _isMetalDetecting = MutableStateFlow(false)
    val isMetalDetecting: StateFlow<Boolean> = _isMetalDetecting.asStateFlow()

    // Impostazioni utente
    var sensAmpiezza by mutableStateOf(5.5f); private set
    var discLow      by mutableStateOf(4f)   // nonFerroLowDeg — allineato con VectorProcessor
    var discHigh     by mutableStateOf(5f)   // ferroMinDeg    — allineato con VectorProcessor
    var phaseHysteresis by mutableStateOf(1.5f)  // Isteresi anti-flip fase (0°..5°) — ora controlla la zona morta IQ

    // FIX CRASH SLIDER: discHigh deve sempre essere > discLow.
    val sensFase: Float get() = discHigh

    private var postCalibSilenceUntil = 0L
    private var calibrationInProgress = false

    private var dataCollectionJob: Job? = null
    private var autoConnectJob: Job? = null
    private var usbStatusJob: Job? = null

    private val _rawDataMessages = MutableStateFlow<List<String>>(emptyList())
    val rawDataMessages: StateFlow<List<String>> = _rawDataMessages.asStateFlow()
    private val rawBuffer = ArrayDeque<String>(100)
    private var rawSampleCounter = 0
    private val RAW_SAMPLE_DECIMATION = 10

    @Volatile private var pendingUsbDevice: UsbDevice? = null
    private var lastUsbEventTime = 0L

    // FIX: conserva l'offset di fase calcolato durante la calibrazione Android
    private var groundPhaseOffset = 0f

    companion object {
        const val USB_EVENT_DEBOUNCE_MS = 2000L
        // Range UI della lancetta: ±30° corrisponde a ±1.0 normalizzato.
        const val PHASE_DISPLAY_RANGE_DEG = 30f
        // Margine minimo tra discLow e discHigh per evitare crash slider
        const val DISC_MIN_GAP = 1f
    }

    init {
        vectorProcessor.K_VECT = kFromSens(sensAmpiezza)
        vectorProcessor.setDiscThresholds(discLow, discHigh)
        vectorProcessor.phaseHysteresisDeg = phaseHysteresis

        updateSensAmpiezza(sensAmpiezza)

        rebuildDataCollection()
        startPersistentAutoConnect()
        vectorAudio.start()

        viewModelScope.launch {
            delay(100)
            checkUsbOnStartup()
        }
    }

    // ----------------------------------------------------------------------
    // USB events
    // ----------------------------------------------------------------------
    fun onUsbDeviceAttached(device: UsbDevice) {
        val now = System.currentTimeMillis()
        if (now - lastUsbEventTime < USB_EVENT_DEBOUNCE_MS) return
        lastUsbEventTime = now
        log("🔌 Cavo USB rilevato ")
        connectToUSB(device)
    }

    fun connectToUSB(device: UsbDevice) {
        if (usb.connectionStatus.value is ConnectionStatus.Connected) return
        viewModelScope.launch(Dispatchers.IO) {
            if (dataCollectionJob?.isActive == true) {
                dataCollectionJob?.cancelAndJoin()
                dataCollectionJob = null
            }
            _connectionStatus.value = ConnectionStatus.Connecting
            usb.connect(device)
            val connected = withTimeoutOrNull(6000L) {
                usb.connectionStatus.first { it is ConnectionStatus.Connected }
            }
            if (connected == null) {
                log("⚠️ USB timeout ")
                usb.disconnect()
                _connectionStatus.value = ConnectionStatus.Disconnected
                return@launch
            }
            withContext(Dispatchers.Main) {
                _connectionStatus.value = ConnectionStatus.Connected("USB ")
                params = params.copy(transportMode = "USB ")
                vectorAudio.start()
                log("✅ USB connesso ")
                Toast.makeText(context, "Connesso (USB) ", Toast.LENGTH_SHORT).show()
                rebuildDataCollection()
            }
            delay(1500)
            usb.sendCommand("STATUS")
        }
    }

    private fun rebuildDataCollection() {
        dataCollectionJob?.cancel()
        usbStatusJob?.cancel()
        dataCollectionJob = null

        usbStatusJob = viewModelScope.launch {
            usb.connectionStatus.collect { status ->
                if (!calibrationInProgress)
                    withContext(Dispatchers.Main) { updateConnectionStatus(status) }
            }
        }

        dataCollectionJob = viewModelScope.launch {
            usb.telemetryFlow
                .buffer(capacity = 64)
                .flowOn(Dispatchers.Default)
                .onEach { data ->
                    if (!calibrationInProgress) {
                        withContext(Dispatchers.Main) { processTelemetry(data) }
                    }
                    rawSampleCounter++
                    if (rawSampleCounter % RAW_SAMPLE_DECIMATION == 0) {
                        val rawLine = "delta=%.4f  phase=%.2f° ".format(data.delta, data.phase)
                        if (rawBuffer.size >= 100) rawBuffer.removeFirst()
                        rawBuffer.addLast(rawLine)
                        _rawDataMessages.value = rawBuffer.toList()
                    }
                }
                .launchIn(this)

            usb.consoleFlow
                .buffer(capacity = 32)
                .onEach { msg ->
                    withContext(Dispatchers.Main) {
                        console.add(msg)
                        if (console.size > 120) console.removeAt(0)
                    }
                }
                .launchIn(this)

            usb.params
                .collect { newParams ->
                    withContext(Dispatchers.Main) {
                        params = newParams.copy(transportMode = params.transportMode)
                    }
                }
        }
    }

    private fun updateConnectionStatus(status: ConnectionStatus) {
        _connectionStatus.value = status
        when (status) {
            is ConnectionStatus.Connected -> {
                params = params.copy(transportMode = "USB ")
                vectorAudio.start()
                viewModelScope.launch {
                    delay(200)
                    sendCommand("STATUS")
                }
                Toast.makeText(context, "Connesso (USB) ", Toast.LENGTH_SHORT).show()
            }
            is ConnectionStatus.Disconnected -> vectorAudio.stop()
            is ConnectionStatus.Error -> {
                vectorAudio.stop()
                log("🔴 ${status.message} ")
            }
            else -> {}
        }
    }

    private fun checkUsbOnStartup() {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.firstOrNull { d ->
            com.hoho.android.usbserial.driver.UsbSerialProber.getDefaultProber().probeDevice(d) != null
        }
        if (device != null && usbManager.hasPermission(device)) {
            log("🔌 Cavo rilevato all'avvio → USB ")
            connectToUSB(device)
        }
    }

    private fun startPersistentAutoConnect() {
        autoConnectJob?.cancel()
        autoConnectJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)
                if (usb.connectionStatus.value !is ConnectionStatus.Connected &&
                    usb.connectionStatus.value !is ConnectionStatus.Connecting) {
                    val device = usb.findDevice()
                    if (device != null) connectToUSB(device)
                }
            }
        }
    }

    // ----------------------------------------------------------------------
    // Telemetria e calibrazione
    // ----------------------------------------------------------------------
    private fun resetProcessorState() {
        vectorProcessor.reset()
        vectorResult            = null
        iqCurrent               = IQVector(0f, 0f)
        iqBaseline              = IQVector(0f, 0f)
        liveDelta               = 0f
        phaseDiff               = 0f
        phaseNormalized         = 0f
        normalizedDistance      = 0f
        vectorSpeed             = 0f
        vectorCurvature         = 0f
        linearity               = 1f
        isAngleValid            = false
        analysis                = AnalysisResult()
        _isMetalDetecting.value = false
        postCalibSilenceUntil   = 0L
        groundPhaseOffset       = 0f
    }

    private fun processTelemetry(data: TelemetryData) {
        if (data.delta.isNaN() || data.phase.isNaN() || abs(data.delta) > 10_000f) return
        val now = System.currentTimeMillis()
        if (now < postCalibSilenceUntil) {
            vectorAudio.forceSilence()
            return
        }

        // 1) VectorProcessor per detection e audio (non modifica la visualizzazione della fase)
        val vr = vectorProcessor.processSample(data.delta, data.phase, now)
        vectorResult             = vr
        iqCurrent                = vr.vector
        iqBaseline               = vr.baseline
        liveDelta                = vr.vectorDistance
        _isMetalDetecting.value  = vr.isDetected
        vectorSpeed              = vr.vectorSpeed
        vectorCurvature          = vr.vectorCurvature
        linearity                = vr.linearityIndex
        normalizedDistance       = (vr.vectorDistance /
                vectorProcessor.enterDetectionThreshold.coerceAtLeast(0.001f)).coerceIn(0f, 1f)

        analysis = AnalysisResult(
            vdi        = vr.vdi,
            type       = vr.metalType,
            confidence = vr.confidence,
            amplitude  = vr.vectorDistance * 1000f,
            isLocked   = vr.isLocked,
            depth      = vr.depth
        )
        vectorAudio.update(vr)

        // 2) Visualizzazione della fase: usa la fase ESP32 compensata con l'offset Android
        // Calcola l'angolo relativo rispetto al groundPhaseOffset ottenuto in calibrazione
        val rawPhase = data.phase
        val relativePhase = normalizeAngle(rawPhase - groundPhaseOffset)
        phaseDiff = relativePhase
        // Normalizzazione per la lancetta (range ±30°)
        phaseNormalized = (relativePhase / PHASE_DISPLAY_RANGE_DEG).coerceIn(-1f, 1f)
        // La fase è sempre considerata valida per la visualizzazione
        isAngleValid = true
    }

    // Funzione di normalizzazione dell'angolo in gradi nell'intervallo [-180, 180]
    private fun normalizeAngle(angle: Float): Float {
        var a = angle % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        return a
    }

    fun performCalibration() {
        if (calibrationInProgress) return
        viewModelScope.launch {
            calibrationInProgress = true
            log(">>> Calibrazione in corso...")
            val samples = mutableListOf<IQVector>()
            val startMs   = System.currentTimeMillis()
            val discardMs = startMs + 300L
            val endMs     = startMs + 2000L
            withTimeoutOrNull(2200L) {
                usb.telemetryFlow.collect { data ->
                    if (usb.connectionStatus.value !is ConnectionStatus.Connected) {
                        log(">>> Interrutta: connessione persa")
                        return@collect
                    }
                    val now = System.currentTimeMillis()
                    if (now < discardMs || now >= endMs) return@collect
                    if (data.delta.isNaN() || data.phase.isNaN() || abs(data.delta) > 10_000f) return@collect
                    val rad = Math.toRadians(data.phase.toDouble())
                    val a = abs(data.delta)
                    samples.add(IQVector((a * cos(rad)).toFloat(), (a * sin(rad)).toFloat()))
                }
            }
            try {
                if (samples.size < 30) {
                    log(">>> ERRORE: campioni insufficienti (${samples.size})")
                    return@launch
                }
                val avgI = samples.map { it.i }.average().toFloat()
                val avgQ = samples.map { it.q }.average().toFloat()
                val baseline = IQVector(avgI, avgQ)
                if (baseline.magnitude < 0.01f) {
                    log(">>> ERRORE: baseline debole")
                    return@launch
                }
                val dists = samples.map { it.distanceTo(baseline) }.sorted()
                val valid = dists.drop(dists.size / 10).take(dists.size * 9 / 10)
                val mean  = valid.average()
                val rms   = sqrt(valid.map { d -> (d - mean).pow(2) }.average()).toFloat()
                    .coerceAtLeast(0.001f)

                // Imposta il baseline nel processore vettoriale
                vectorProcessor.setBaselineIQ(baseline)
                vectorProcessor.setNoiseEstimate(rms)

                // Calcola l'offset di fase dalla baseline (angolo del vettore I/Q)
                val baseAngle = Math.toDegrees(atan2(baseline.q.toDouble(), baseline.i.toDouble())).toFloat()
                groundPhaseOffset = normalizeAngle(baseAngle)

                postCalibSilenceUntil = System.currentTimeMillis() + 1500L
                log(">>> OK! I=%.4f Q=%.4f mag=%.4f RMS=%.4f SogliaEntra=%.4f OffsetFase=%.2f°"
                    .format(baseline.i, baseline.q, baseline.magnitude, rms,
                        vectorProcessor.enterDetectionThreshold, groundPhaseOffset))
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Calibrazione completata", Toast.LENGTH_SHORT).show()
                }
            } finally {
                calibrationInProgress = false
            }
        }
    }

    // ----------------------------------------------------------------------
    // Comandi e impostazioni
    // ----------------------------------------------------------------------
    suspend fun sendCommand(cmd: String) = usb.sendCommand(cmd)

    fun updateSensAmpiezza(value: Float) {
        sensAmpiezza = value
        vectorProcessor.K_VECT = kFromSens(value)

        val adaptiveFloor = vectorProcessor.noiseMagnitude * 1.1f
        val adaptiveCap   = vectorProcessor.noiseMagnitude * 4.0f
        vectorProcessor.enterDetectionThreshold = (vectorProcessor.noiseMagnitude * vectorProcessor.K_VECT).coerceIn(adaptiveFloor, adaptiveCap)
        vectorProcessor.exitDetectionThreshold  = (vectorProcessor.enterDetectionThreshold * 0.45f).coerceAtLeast(vectorProcessor.noiseMagnitude * 0.4f)

        log("Sens.Ampiezza: %.1f → K=%.2f SogliaEntra=%.4f SogliaEsci=%.4f"
            .format(value, vectorProcessor.K_VECT, vectorProcessor.enterDetectionThreshold, vectorProcessor.exitDetectionThreshold))
    }

    fun updateDiscThresholds(low: Float, high: Float) {
        val l = low.coerceIn(1f, 29f)
        val h = high.coerceIn(l + DISC_MIN_GAP, 30f)
        vectorProcessor.setDiscThresholds(l, h)
        discLow  = vectorProcessor.nonFerroLowDeg
        discHigh = vectorProcessor.ferroMinDeg
        log("Disc: basso=%.1f° alto=%.1f°".format(discLow, discHigh))
    }

    fun updateSensFase(value: Float) = updateDiscThresholds(discLow, value)

    fun updatePhaseHysteresis(value: Float) {
        phaseHysteresis = value.coerceIn(0f, 5f)
        vectorProcessor.phaseHysteresisDeg = phaseHysteresis
        log("Isteresi Fase (zona morta IQ): %.1f°".format(phaseHysteresis))
    }

    fun invertDiscPolarity() {
        vectorProcessor.discAnglePolarity = -vectorProcessor.discAnglePolarity
        log("Polarità disco invertita")
    }

    fun disconnect() {
        usb.disconnect()
        _connectionStatus.value = ConnectionStatus.Disconnected
    }

    fun send() {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return
        val lower = trimmed.lowercase()
        cmd = "  "
        when (lower) {
            "status" -> { showStatus(); return }
            "calibra" -> { performCalibration(); return }
            "help" -> { showHelp(); return }
            "invertdisc" -> { invertDiscPolarity(); return }
            "reboot" -> {
                viewModelScope.launch { sendCommand("REBOOT") }
                log("Riavvio ESP...")
                return
            }
        }
        if (lower.startsWith("set ")) { handleLocalCommand(trimmed); return }
        if (connectionStatus.value is ConnectionStatus.Connected) {
            viewModelScope.launch { sendCommand(trimmed) }
            log("➡️ $trimmed")
        } else {
            log("❌ Non connesso")
        }
    }

    private fun showStatus() {
        val vr = vectorResult
        log("=== Stato ===\n" +
                "Trasporto: USB | ${_connectionStatus.value}\n" +
                "Baseline: I=%.4f Q=%.4f | Vettore: I=%.4f Q=%.4f\n".format(
                    vectorProcessor.baselineVector.i, vectorProcessor.baselineVector.q,
                    iqCurrent.i, iqCurrent.q) +
                "EnergiaTangenziale: %.4f  SogliaEntra: %.4f  SogliaEsci: %.4f  Rumore: %.4f  K: %.2f\n".format(
                    vr?.vectorDistance ?: 0f, vectorProcessor.enterDetectionThreshold,
                    vectorProcessor.exitDetectionThreshold, vectorProcessor.noiseMagnitude, vectorProcessor.K_VECT) +
                "Angolo: %.1f°  Valid: %s  Tipo: %s  Det: %s  Locked: %s\n".format(
                    vr?.relativeAngleDeg ?: 0f, vr?.isAngleValid,
                    vr?.metalType ?: "?", vr?.isDetected, vr?.isLocked) +
                "Batteria: %.1fV  Freq: %.0fHz  Hyst: %.1f°  OffsetFase: %.2f°".format(
                    params.battery, params.frequency, vectorProcessor.phaseHysteresisDeg, groundPhaseOffset))
    }

    private fun showHelp() = log(
        "Comandi: status | calibra | invertdisc | reboot | " +
                "set sens <1-10> | set disc <valore alto ferro> | set hyst <0-5> | help"
    )

    private fun handleLocalCommand(cmd: String) {
        val p = cmd.split(" ")
        if (p.size < 3) { log("Comando incompleto"); return }
        when (p[1].lowercase()) {
            "sens" -> p[2].toFloatOrNull()?.let { updateSensAmpiezza(it) } ?: log("Valore non valido")
            "disc" -> p[2].toFloatOrNull()?.let { updateSensFase(it) } ?: log("Valore non valido")
            "hyst" -> p[2].toFloatOrNull()?.let { updatePhaseHysteresis(it) } ?: log("Valore non valido")
            else   -> log("Sconosciuto: ${p[1]}")
        }
    }

    private fun log(msg: String) { console.add(msg) }

    // K range [1.2, 4.0]
    private fun kFromSens(s: Float) = (1.2f + (10f - s) * (4.3f / 9f)).coerceIn(1.2f, 5.5f)

    override fun onCleared() {
        usb.cleanup()
        vectorAudio.stop()
        super.onCleared()
    }
}