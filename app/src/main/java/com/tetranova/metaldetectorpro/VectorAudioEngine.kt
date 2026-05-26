package com.tetranova.metaldetectorpro
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.*

class VectorAudioEngine {
    companion object {
        private const val SAMPLE_RATE = 44100
        private const val FREQ_WHISPER_MIN = 80f
        private const val FREQ_WHISPER_MAX = 200f
        private const val FREQ_FERROUS = 550f
        private const val FREQ_NONFERROUS = 950f
        private const val AMP_IDLE = 0.28f
        private const val AMP_PEAK = 0.85f

        @Volatile private var previousDetected = false

        private const val MAX_DIST_WHISPER = 0.30f
        private const val SMOOTH_ALPHA = 0.03f
        private const val LOCK_CHIRP = 8
    }

    private enum class Band { WHISPER, FERROUS, NONFERROUS }
    @Volatile private var targetFreq = FREQ_WHISPER_MIN.toDouble()
    @Volatile private var targetAmp  = AMP_IDLE.toDouble()
    @Volatile private var lockTriggered = false
    @Volatile private var isRunning = false
    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null
    @Volatile private var currentBand = Band.WHISPER

    fun update(result: VectorResult) {
        if (!isRunning) return
        val t = (result.vectorDistance / MAX_DIST_WHISPER).coerceIn(0f, 1f)

        // FIX: aggiunto .trim() per sicurezza contro spazi residui
        val newBand = if (result.isDetected) {
            when (result.metalType.trim()) {
                "FERRO" -> Band.FERROUS
                "NON_FERRO" -> Band.NONFERROUS
                else -> Band.WHISPER
            }
        } else Band.WHISPER

        val newFreq = when (newBand) {
            Band.WHISPER -> FREQ_WHISPER_MIN + (FREQ_WHISPER_MAX - FREQ_WHISPER_MIN) * t
            Band.FERROUS -> FREQ_FERROUS
            Band.NONFERROUS -> FREQ_NONFERROUS
        }
        val newAmp = AMP_IDLE + (AMP_PEAK - AMP_IDLE) * t.toDouble().pow(1.2)

        if (newBand != currentBand) currentBand = newBand
        targetFreq = newFreq.toDouble()
        targetAmp = newAmp

        if (result.isLocked && !lockTriggered) lockTriggered = true
    }

    fun forceSilence() { targetFreq = FREQ_WHISPER_MIN.toDouble(); targetAmp = 0.0; lockTriggered = false }

    fun start() {
        if (isRunning) return
        isRunning = true
        val bufSize = maxOf(AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), 2048) * 2
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(bufSize).setTransferMode(AudioTrack.MODE_STREAM).build()

        audioThread = Thread({
            val track = audioTrack ?: return@Thread
            val frames = track.bufferSizeInFrames
            val buf = ShortArray(frames)
            var curFreq = FREQ_WHISPER_MIN.toDouble()
            var curAmp = AMP_IDLE.toDouble()
            var phase = 0.0
            var chirp = 0
            try {
                track.play()
                while (isRunning) {
                    val doChirp = lockTriggered
                    if (doChirp) { lockTriggered = false; chirp = LOCK_CHIRP }

                    if (abs(curFreq - targetFreq) > 10.0) curFreq = targetFreq
                    else curFreq += (targetFreq - curFreq) * SMOOTH_ALPHA
                    curAmp += (targetAmp - curAmp) * SMOOTH_ALPHA

                    val freq = if (chirp > 0) { curFreq * (1.0 + (1.0 - chirp.toDouble() / LOCK_CHIRP) * 0.5) } else curFreq
                    if (chirp > 0) chirp--

                    val inc = (freq / SAMPLE_RATE) * 2.0 * PI
                    val amp = curAmp * Short.MAX_VALUE
                    for (i in buf.indices) {
                        buf[i] = (sin(phase) * amp).toInt().toShort()
                        phase += inc
                        if (phase >= 2.0 * PI) phase -= 2.0 * PI
                    }
                    if (track.write(buf, 0, frames) < 0) break
                }
            } catch (_: Exception) {} finally { try { track.stop() } catch (_: Exception) {} }
        }, "AudioGen").apply { isDaemon = true; start() }
    }

    fun stop() {
        isRunning = false
        audioThread?.join(1000)
        audioTrack?.release(); audioTrack = null; audioThread = null
    }
}