package com.tetranova.metaldetectorpro
import android.util.Log
import kotlin.math.*

data class IQVector(val i: Float, val q: Float) {
    val magnitude: Float get() = sqrt(i * i + q * q)
    operator fun plus(o: IQVector) = IQVector(i + o.i, q + o.q)
    operator fun minus(o: IQVector) = IQVector(i - o.i, q - o.q)
    operator fun times(k: Float) = IQVector(i * k, q * k)
    fun distanceTo(o: IQVector): Float = sqrt((i - o.i) * (i - o.i) + (q - o.q) * (q - o.q))
    companion object { val ZERO = IQVector(0f, 0f) }
}

data class VectorResult(
    val vector: IQVector, val baseline: IQVector, val delta: IQVector,
    val vectorDistance: Float, val vectorAngleDeg: Float, val relativeAngleDeg: Float,
    val pcaAngleDeg: Float, val linearityIndex: Float, val isFerroso: Boolean,
    val vectorSpeed: Float, val vectorCurvature: Float, val isDetected: Boolean,
    val isLocked: Boolean, val confidence: Float, val vdi: Int, val depth: Float,
    val metalType: String,
    val isAngleValid: Boolean,
    val rawAmplitude: Float,
    val rawPhaseDeg: Float
)

class VectorProcessor {
    var K_VECT = 1.4f
    var confirmSamples = 2
    var nonFerroLowDeg = 4.0f
    var ferroMinDeg    = 4.0f

    fun setDiscThresholds(low: Float, high: Float) {
        nonFerroLowDeg  = low.coerceIn(1f, 45f)
        ferroMinDeg    = high.coerceIn(1f, 45f)
    }

    var maxDetectionMs = 1500L
    var postExitLockoutMs = 120L
    var enableDebugLog = false
    var discAnglePolarity = 1f
    var minAngleSamplesForFreeze = 4

    var phaseHysteresisDeg = 1.5f

    private var tangentialSignState = 0f

    private var lastStableAngle = 0f
    private var angleFrozen = false

    var noiseFreezeSamples = 25
        set(value) { field = value.coerceAtLeast(10) }

    var currentVector = IQVector.ZERO; private set
    var baselineVector = IQVector.ZERO; private set

    var noiseMagnitude = 0.8f; private set

    var enterDetectionThreshold = 5.0f
    var exitDetectionThreshold = 2.25f

    var isDetected = false; private set
    var isLocked = false; private  set
    var vectorSpeed = 0f; private set
    var vectorCurvature = 0f; private set
    var pcaAngleDeg = 0f; private set
    var linearityIndex = 1f; private set

    private var initialized = false
    private var confirmCounter = 0
    private var detectionStartMs = 0L
    private var lastExitMs = 0L
    private var peakDistance = 0f
    private var noiseVariance = 0.64f
    private var noiseFreezeCounter = 0

    private val deltaBuffer = ArrayDeque<IQVector>(15)
    private val angleBuffer = ArrayDeque<Float>(20)

    private var stableAngle = 0f
    private var angleIsStable = false
    private var wasAboveExit = false

    private var smoothedI = 0f
    private var smoothedQ = 0f

    private var initCounter = 0
    private val initWarmup = 8

    private var relativeAngle = 0f
    private var angleValid = false

    private var lastRawAmp = 0f
    private var lastRawPhase = 0f

    private var deltaGateOpen = false

    private var adaptiveAlpha = 0.08f

    private var prevRawDeltaI = 0f
    private var prevRawDeltaQ = 0f

    // NUOVA VARIABILE: angolo sempre calcolato per la UI (senza congelamento)
    private var displayAngleDeg = 0f
    private var filteredDisplayAngle = 0f
    private val DISPLAY_ANGLE_ALPHA = 0.2f      // filtraggio
    private val DISPLAY_ANGLE_HOLD_THRESHOLD = 0.5f   // soglia di congelamento

    fun processSampleIQ(i: Float, q: Float, nowMs: Long): VectorResult {
        val raw = IQVector(i,  q)

        if (!initialized) {
            if (initCounter  < initWarmup) {
                val n = initCounter + 1
                baselineVector = IQVector(
                    (baselineVector.i * initCounter + raw.i) / n,
                    (baselineVector.q * initCounter + raw.q) / n
                )
                initCounter++
                return idleResult(raw)
            } else {
                baselineVector = raw
                initialized = true
                initCounter = initWarmup
                smoothedI = 0f
                smoothedQ = 0f
                deltaBuffer.clear()
                angleBuffer.clear()
            }
            currentVector = raw
            return idleResult(raw)
        }

        currentVector = raw

        val rawDeltaI = currentVector.i - baselineVector.i
        val rawDeltaQ = currentVector.q - baselineVector.q

        val rawSpeed = sqrt(
            (rawDeltaI - prevRawDeltaI) * (rawDeltaI - prevRawDeltaI) +
                    (rawDeltaQ - prevRawDeltaQ) * (rawDeltaQ - prevRawDeltaQ)
        )
        prevRawDeltaI = rawDeltaI
        prevRawDeltaQ = rawDeltaQ

        adaptiveAlpha = when {
            rawSpeed  < 0.50f -> 0.12f
            rawSpeed  < 1.50f -> 0.22f
            rawSpeed  < 3.00f -> 0.40f
            rawSpeed  < 6.00f -> 0.58f
            else             -> 0.75f
        }

        smoothedI += adaptiveAlpha * (rawDeltaI - smoothedI)
        smoothedQ += adaptiveAlpha * (rawDeltaQ - smoothedQ)

        val delta = IQVector(smoothedI, smoothedQ)

        val baseMag = baselineVector.magnitude.coerceAtLeast(1e-6f)
        val bx = baselineVector.i / baseMag
        val by = baselineVector.q / baseMag

        val radial = smoothedI * bx + smoothedQ * by
        val tangential = smoothedI * (-by) + smoothedQ * bx

        val rotationalEnergy = abs(tangential)

        if (!deltaGateOpen) {
            if (rotationalEnergy  > enterDetectionThreshold) {
                deltaGateOpen = true
            }
        } else {
            if (rotationalEnergy  < exitDetectionThreshold) {
                deltaGateOpen = false
            }
        }

        // --- CALCOLO ANGOLO PER LA DETECTION (con gate e freeze) ---
        if (!deltaGateOpen) {
            angleValid = false
            relativeAngle = 0f
        } else {
            angleValid = true

            val vectorNorm = sqrt(radial * radial + tangential * tangential)
            val freezeThreshold = computeTangentialHysteresis(radial) * 2.2f

            if (vectorNorm < freezeThreshold) {
                angleFrozen = true
                relativeAngle = lastStableAngle
            } else {
                angleFrozen = false
                var rawAng = Math.toDegrees(atan2(tangential.toDouble(), abs(radial).toDouble() + 0.3)).toFloat()
                rawAng = normalize180(rawAng)
                relativeAngle = rawAng * discAnglePolarity
                lastStableAngle = relativeAngle
            }
        }

        // --- NUOVO: CALCOLO ANGOLO PER LA UI con filtro e hold ---
        val vectorNormForDisplay = sqrt(radial * radial + tangential * tangential)
        val rawDisplayAng: Float
        if (vectorNormForDisplay < DISPLAY_ANGLE_HOLD_THRESHOLD) {
            // segnale troppo debole → mantieni l'ultimo angolo filtrato
            rawDisplayAng = filteredDisplayAngle
        } else {
            var raw = Math.toDegrees(atan2(tangential.toDouble(), abs(radial).toDouble() + 0.3)).toFloat()
            raw = normalize180(raw)
            rawDisplayAng = raw * discAnglePolarity
        }
// Filtro EMA
        filteredDisplayAngle = filteredDisplayAngle + DISPLAY_ANGLE_ALPHA * (rawDisplayAng - filteredDisplayAngle)
// Normalizza per evitare accumulo di errori
        filteredDisplayAngle = normalize180(filteredDisplayAngle)
        displayAngleDeg = filteredDisplayAngle

        return if (!isDetected) processIdle(delta, rotationalEnergy, relativeAngle, nowMs)
        else processDetected(delta, rotationalEnergy, relativeAngle, nowMs)
    }

    fun processSample(amplitude: Float, phaseDeg: Float, nowMs: Long): VectorResult {
        lastRawAmp = amplitude
        lastRawPhase = phaseDeg

        val rad = Math.toRadians(phaseDeg.toDouble())
        return processSampleIQ(
            amplitude * cos(rad).toFloat(),
            amplitude * sin(rad).toFloat(),
            nowMs
        )
    }

    private fun isVectorPhysicallyCoherent(rotationalEnergy: Float, angle: Float): Boolean {
        if (rotationalEnergy  < enterDetectionThreshold) return false
        if (linearityIndex  < 0.60f) return false
        if (vectorCurvature  > 2.0f) return false

        if (angleBuffer.size  >= 4) {
            val avg = circularMeanDeg(angleBuffer)
            val variance = angleBuffer.map {
                val d = normalize180(it - avg)
                d * d
            }.average().toFloat()

            if (variance  > 120f) return false
        }
        return true
    }

    private fun circularMeanDeg(values: Collection<Float>): Float {
        var sx = 0.0
        var sy = 0.0
        for (a in values) {
            val r = Math.toRadians(a.toDouble())
            sx += cos(r)
            sy += sin(r)
        }
        return normalize180(Math.toDegrees(atan2(sy, sx)).toFloat())
    }

    fun setBaselineIQ(vec: IQVector) {
        if (vec.magnitude  < 1.0f) return
        baselineVector = vec
        currentVector = vec
        smoothedI = 0f
        smoothedQ = 0f
        initialized = true
        initCounter = initWarmup
        deltaBuffer.clear()
        angleBuffer.clear()
        fullReset()

        relativeAngle = 0f
        angleValid = false
        deltaGateOpen = false
        displayAngleDeg = 0f
    }

    fun setBaseline(amplitude: Float, phaseDeg: Float) {
        val phaseRad = Math.toRadians(phaseDeg.toDouble())
        setBaselineIQ(IQVector(
            amplitude * cos(phaseRad).toFloat(),
            amplitude * sin(phaseRad).toFloat()
        ))
    }

    fun setNoiseEstimate(rms: Float) {
        noiseMagnitude = rms.coerceAtLeast(0.8f)
        noiseVariance = noiseMagnitude * noiseMagnitude
        val adaptiveFloor = noiseMagnitude * 1.1f
        val adaptiveCap   = noiseMagnitude  * 4.0f
        enterDetectionThreshold = (noiseMagnitude * K_VECT).coerceIn(adaptiveFloor, adaptiveCap)
        exitDetectionThreshold  = (enterDetectionThreshold * 0.45f).coerceAtLeast(noiseMagnitude * 0.4f)
        noiseFreezeCounter = noiseFreezeSamples
    }

    fun reset() {
        fullReset()
        initialized = false
        initCounter = 0
        currentVector = IQVector.ZERO
        smoothedI = 0f
        smoothedQ = 0f
        prevRawDeltaI = 0f
        prevRawDeltaQ = 0f
        noiseFreezeCounter = noiseFreezeSamples
        relativeAngle = 0f
        angleValid = false
        angleBuffer.clear()
        deltaGateOpen = false
        displayAngleDeg = 0f
    }

    private fun idleResult(raw: IQVector) = VectorResult(
        vector = raw, baseline = baselineVector, delta = IQVector.ZERO,
        vectorDistance = 0f, vectorAngleDeg = 0f, relativeAngleDeg = 0f,
        pcaAngleDeg = 0f, linearityIndex = 1f, isFerroso = false,
        vectorSpeed = 0f, vectorCurvature = 0f, isDetected = false,  isLocked = false,
        confidence = 0f, vdi = 0, depth = 25f, metalType =  "IDLE",
        isAngleValid = false, rawAmplitude = lastRawAmp, rawPhaseDeg = lastRawPhase
    )

    private fun processIdle(delta: IQVector, rotationalEnergy: Float, relAngle: Float, nowMs: Long ): VectorResult {
        val inLockout = (nowMs - lastExitMs)  <= postExitLockoutMs
        if (!inLockout) {
            if (noiseFreezeCounter  > 0) noiseFreezeCounter--
            else if (rotationalEnergy  < exitDetectionThreshold * 0.25f) {
                noiseVariance = noiseVariance * 0.99f + (rotationalEnergy * rotationalEnergy) * 0.01f
                noiseMagnitude = sqrt(noiseVariance).coerceAtLeast(0.8f)
            }
        }

        val driftSpeed = sqrt(smoothedI * smoothedI + smoothedQ * smoothedQ)
        if (!inLockout && !isDetected && noiseFreezeCounter <= 0
            && rotationalEnergy < exitDetectionThreshold * 0.06f
            && driftSpeed < noiseMagnitude * 0.3f) {
            val a = 0.0001f
            val driftTargetI = baselineVector.i + smoothedI
            val driftTargetQ = baselineVector.q + smoothedQ
            baselineVector = IQVector(
                baselineVector.i + a * (driftTargetI - baselineVector.i),
                baselineVector.q + a * (driftTargetQ - baselineVector.q)
            )
        }
        if  (deltaBuffer.size  >= 15) deltaBuffer.removeFirst()
        deltaBuffer.addLast(delta)
        computeMetrics()

        val exceeds = rotationalEnergy  > enterDetectionThreshold
        if (exceeds  && !inLockout  && isVectorPhysicallyCoherent(rotationalEnergy, relAngle)) {
            confirmSamples = if (rotationalEnergy  > enterDetectionThreshold * 2.5f) 1 else 2
            confirmCounter++
            if (confirmCounter  >= confirmSamples) {
                isDetected = true
                isLocked = false
                detectionStartMs = nowMs
                peakDistance = rotationalEnergy
                confirmCounter = 0
                wasAboveExit = true
                angleBuffer.clear()
                stableAngle = 0f
                angleIsStable = false
            }
        } else if (!exceeds) confirmCounter = 0

        return buildResult(delta, rotationalEnergy, relAngle, false, false)
    }

    private fun processDetected(delta: IQVector, rotationalEnergy: Float, relAngle: Float, nowMs: Long): VectorResult {
        if (deltaBuffer.size  >= 15) deltaBuffer.removeFirst()
        deltaBuffer.addLast(delta)
        computeMetrics()

        if (rotationalEnergy  > peakDistance * 1.15f) {
            peakDistance = rotationalEnergy
            isLocked = false
        }

        val snr = rotationalEnergy / (exitDetectionThreshold.coerceAtLeast(0.5f))

        if (snr  < 0.45f) {
            angleBuffer.clear()
            angleIsStable = false
        }

        if (!angleIsStable  && wasAboveExit  && snr  > 1.1f  && rotationalEnergy  > exitDetectionThreshold) {
            val refAngle = if (angleBuffer.isEmpty()) relAngle else circularMeanDeg(angleBuffer)
            if (angleBuffer.size  < 3 || abs(normalize180(relAngle - refAngle))  < 18f) {
                if (angleBuffer.size  >= minAngleSamplesForFreeze) angleBuffer.removeFirst()
                angleBuffer.addLast(relAngle)
                if (angleBuffer.size  >= 3) {
                    val avg = circularMeanDeg(angleBuffer)
                    val variance = angleBuffer.map {
                        val diff = normalize180(it - avg)
                        diff * diff
                    }.average().toFloat()
                    if (variance  < 20f) {
                        stableAngle = avg
                        angleIsStable = true
                        isLocked = true
                    }
                }
            }
        }

        val discAngle = if (angleIsStable) stableAngle else relAngle

        val exitThr = maxOf(exitDetectionThreshold, enterDetectionThreshold * 0.40f)
        if (rotationalEnergy  > exitThr) wasAboveExit = true
        val timeout = (nowMs - detectionStartMs)  > maxDetectionMs
        val belowExit = rotationalEnergy  < exitDetectionThreshold
        val neverGrew = !wasAboveExit  && (nowMs - detectionStartMs)  > 250L

        if (timeout || (wasAboveExit  && belowExit) || neverGrew) {
            lastExitMs = nowMs
            val res = buildResult(delta, rotationalEnergy, discAngle, true, isLocked)
            fullReset()
            return res
        }
        return buildResult(delta, rotationalEnergy, discAngle, true, isLocked)
    }

    private fun buildResult(delta: IQVector, rotationalEnergy: Float, discAngle: Float,
                            detected: Boolean, locked: Boolean): VectorResult {
        val snr = rotationalEnergy / (exitDetectionThreshold.coerceAtLeast(0.5f))
        val ampConf = (snr / 2.0f).coerceIn(0f, 1f)
        val linConf = linearityIndex.coerceIn(0f, 1f)
        val confidence = if (!detected) 0f
        else ((ampConf * 0.8f + linConf * 0.2f) * 100f).coerceIn(0f, 100f)

        val metalType = when {
            !detected ->  "IDLE"
            discAngle  < -ferroMinDeg ->  "FERRO"
            discAngle  > nonFerroLowDeg ->  "NON_FERRO"
            else ->  "AMBIGUO"
        }

        val vdiMin = -90f; val vdiMax = 90f
        val vdi = if (detected  && snr  > 0.5f) {
            val norm = (discAngle.coerceIn(vdiMin, vdiMax) - vdiMin) / (vdiMax - vdiMin)
            (norm * 99f).toInt().coerceIn(0, 99)
        } else 0

        val depth = if (!detected) 25f else
            (30f - (snr * 8f).coerceIn(0f, 27f)).coerceIn(3f, 30f)

        return VectorResult(
            vector = currentVector,
            baseline = baselineVector,
            delta = delta,
            vectorDistance = rotationalEnergy,
            // MODIFICA: usare displayAngleDeg per i campi UI (vectorAngleDeg e relativeAngleDeg)
            vectorAngleDeg = displayAngleDeg,
            relativeAngleDeg = displayAngleDeg,
            pcaAngleDeg = pcaAngleDeg,
            linearityIndex = linearityIndex,
            isFerroso = metalType ==  "FERRO",
            vectorSpeed = vectorSpeed,
            vectorCurvature = vectorCurvature,
            isDetected = detected,
            isLocked = locked,
            confidence = confidence,
            vdi = vdi,
            depth = depth,
            metalType = metalType,
            isAngleValid = angleValid,
            rawAmplitude = lastRawAmp,
            rawPhaseDeg = lastRawPhase
        )
    }

    private fun computeMetrics() {
        val n = deltaBuffer.size
        vectorSpeed = if (n  >= 2) {
            val a = deltaBuffer[n-1]; val b = deltaBuffer[n-2]
            sqrt((a.i - b.i) * (a.i - b.i) + (a.q - b.q) * (a.q - b.q))
        } else 0f
        vectorCurvature = if (n  >= 3) {
            val a = deltaBuffer[n-1]; val b = deltaBuffer[n-2]; val c = deltaBuffer[n-3]
            var d = abs(
                atan2((b.q - c.q).toDouble(), (b.i - c.i).toDouble())  -
                        atan2((a.q - b.q).toDouble(), (a.i - b.i).toDouble())
            ).toFloat()
            if (d  > PI.toFloat()) d = 2f * PI.toFloat() - d
            d
        } else 0f
        if (n  >= 4) {
            var sI = 0f; var sQ = 0f
            for (d in deltaBuffer) { sI += d.i; sQ += d.q }
            val mI = sI / n; val mQ = sQ / n
            var cII = 0f; var cQQ = 0f; var cIQ = 0f
            for (d in deltaBuffer) {
                val di = d.i - mI; val dq = d.q - mQ
                cII += di * di; cQQ += dq * dq; cIQ += di * dq
            }
            cII /= n; cQQ  /= n; cIQ /= n
            pcaAngleDeg = normalize180(
                Math.toDegrees(0.5 * atan2(2.0 * cIQ, (cII - cQQ).toDouble())).toFloat()
            )
            val tr = cII + cQQ
            val disc = sqrt(maxOf(0f, tr * tr / 4f - (cII * cQQ - cIQ * cIQ)))
            val l1 = tr / 2f + disc; val l2 = tr / 2f - disc
            linearityIndex = if (l1 + l2  > 1e-6f) l1 / (l1 + l2) else 1f
        } else {
            pcaAngleDeg = 0f; linearityIndex = 1f
        }
    }

    private fun normalize180(d: Float): Float {
        var a = d
        while (a  > 180f) a -= 360f
        while (a  <= -180f) a += 360f
        return a
    }

    private fun computeTangentialHysteresis(radial: Float): Float {
        val theta = Math.toRadians(phaseHysteresisDeg.toDouble()).toFloat()
        val ref = abs(radial) + 0.3f
        return tan(theta) * ref
    }

    private fun fullReset() {
        isDetected = false
        isLocked = false
        confirmCounter = 0
        peakDistance = 0f
        wasAboveExit = false
        deltaBuffer.clear()
        angleBuffer.clear()
        stableAngle = 0f
        angleIsStable = false
        pcaAngleDeg = 0f
        linearityIndex = 1f
        vectorSpeed = 0f
        vectorCurvature = 0f
        relativeAngle = 0f
        angleValid = false
        deltaGateOpen = false
        tangentialSignState = 0f
        lastStableAngle = 0f
        angleFrozen = false
        noiseFreezeCounter = noiseFreezeSamples
        displayAngleDeg = 0f
    }
}