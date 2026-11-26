package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * 100% procedural PCM audio synthesizer using Android AudioTrack.
 * Generates comic wedding sound effects out-of-the-box with zero external audio files.
 */
class SoundEngine(private val context: Context) {
    private val sampleRate = 22050
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var isSpeakerMuted: Boolean = false

    fun playBonk() {
        vibrate(140)
        playGenerated(0.36f) { t, dur ->
            // Loud woody bamboo crack + comic descending boing
            val env = exp(-t * 10f)
            val crack = if (t < 0.03f) (Random.nextFloat() * 2f - 1f) * (1f - t / 0.03f) * 0.9f else 0f
            val freq = 340f * exp(-t * 4.5f) + 120f + 55f * sin(2f * PI.toFloat() * 14f * t)
            val body = sin(2f * PI.toFloat() * freq * t) * env * 0.85f
            val knock = sin(2f * PI.toFloat() * 620f * t) * exp(-t * 28f) * 0.55f
            (body + crack + knock) * (1f - (t / dur).coerceIn(0f, 1f) * 0.2f)
        }
    }

    fun playScream() {
        vibrate(90)
        playGenerated(0.55f) { t, dur ->
            val env = sin(PI.toFloat() * (t / dur)).coerceIn(0f, 1f)
            val vib = sin(2f * PI.toFloat() * 18f * t) * 45f
            val freq = 720f + 320f * (t / dur) + vib
            (sin(2f * PI.toFloat() * freq * t) * 0.5f +
                sin(2f * PI.toFloat() * freq * 1.5f * t) * 0.25f) * env
        }
    }

    fun playBambooWhoosh() {
        playGenerated(0.20f) { t, dur ->
            val p = t / dur
            val env = sin(PI.toFloat() * p)
            val noise = (Random.nextFloat() * 2f - 1f) * 0.35f
            val sweep = sin(2f * PI.toFloat() * (220f + 360f * (1f - p)) * t) * 0.45f
            (noise + sweep) * env
        }
    }

    fun playFootstep(isIndoor: Boolean, isWetGround: Boolean = false) {
        playGenerated(if (isWetGround && !isIndoor) 0.085f else 0.055f) { t, _ ->
            if (isWetGround && !isIndoor) {
                val env = exp(-t * 36f)
                val splash = (Random.nextFloat() * 2f - 1f) * 0.36f
                val squelch = sin(2f * PI.toFloat() * (260f + 180f * exp(-t * 40f)) * t) * 0.22f
                (splash + squelch) * env
            } else {
                val env = exp(-t * (if (isIndoor) 68f else 52f))
                val baseFreq = if (isIndoor) 185f else 115f
                val tap = sin(2f * PI.toFloat() * baseFreq * t) * 0.32f
                val grit = (Random.nextFloat() * 2f - 1f) * (if (isIndoor) 0.14f else 0.24f)
                (tap + grit) * env
            }
        }
    }

    fun playWeatherShift(isRain: Boolean, isFog: Boolean) {
        playGenerated(0.55f) { t, dur ->
            val p = (t / dur).coerceIn(0f, 1f)
            val env = sin(PI.toFloat() * p)
            when {
                isRain -> {
                    // Distant soft monsoon thunder rumble + light drizzle hiss
                    val rumble = sin(2f * PI.toFloat() * (72f - 18f * p) * t) * 0.35f
                    val hiss = (Random.nextFloat() * 2f - 1f) * 0.24f
                    (rumble + hiss) * env
                }
                isFog -> {
                    // Eerie soft night wind swell
                    val wind = (Random.nextFloat() * 2f - 1f) * 0.18f
                    val hum = sin(2f * PI.toFloat() * (190f + 35f * sin(p * PI.toFloat())) * t) * 0.24f
                    (wind + hum) * env
                }
                else -> {
                    // Gentle clearing chime
                    val chime = sin(2f * PI.toFloat() * 587.33f * t) * exp(-t * 6f) * 0.32f
                    chime * env
                }
            }
        }
    }

    fun playSandalThrow() {
        playGenerated(0.22f) { t, dur ->
            val env = sin(PI.toFloat() * (t / dur))
            val freq = 480f - 240f * (t / dur) + 90f * sin(2f * PI.toFloat() * 24f * t)
            sin(2f * PI.toFloat() * freq * t) * env * 0.45f
        }
    }

    fun playSandalHit() {
        vibrate(80)
        playGenerated(0.24f) { t, _ ->
            val slap = if (t < 0.04f) (Random.nextFloat() * 2f - 1f) * (1f - t / 0.04f) else 0f
            val thwack = sin(2f * PI.toFloat() * (260f - 140f * t) * t) * exp(-t * 16f)
            (slap * 0.7f + thwack * 0.75f)
        }
    }

    fun playBananaSlip() {
        vibrate(75)
        playGenerated(0.45f) { t, dur ->
            val p = t / dur
            val whistle = if (p < 0.72f) {
                val freq = 1150f * exp(-p * 2.4f) + 220f * sin(p * 12f)
                sin(2f * PI.toFloat() * freq * t) * 0.55f * (1f - p * 0.4f)
            } else {
                // Thud at the end
                val td = t - 0.72f * dur
                sin(2f * PI.toFloat() * 110f * td) * exp(-td * 22f) * 0.85f
            }
            whistle
        }
    }

    fun playCameraFlash() {
        vibrate(60)
        playGenerated(0.32f) { t, dur ->
            val shutter = if (t < 0.025f) (Random.nextFloat() * 2f - 1f) * 0.8f else 0f
            val whine = sin(2f * PI.toFloat() * (1400f + 1900f * (t / dur)) * t) * exp(-t * 8f) * 0.45f
            val pop = sin(2f * PI.toFloat() * 980f * t) * exp(-t * 35f) * 0.5f
            shutter + whine + pop
        }
    }

    fun playCngHorn() {
        vibrate(100)
        playGenerated(0.46f) { t, _ ->
            val burst = (t < 0.16f) || (t in 0.22f..0.42f)
            if (!burst) 0f else {
                val s1 = sin(2f * PI.toFloat() * 440f * t)
                val s2 = sin(2f * PI.toFloat() * 554f * t)
                val buzz = sin(2f * PI.toFloat() * 880f * t) * 0.25f
                (s1 * 0.45f + s2 * 0.4f + buzz) * 0.7f
            }
        }
    }

    fun playCapturePulse() {
        playGenerated(0.11f) { t, _ ->
            sin(2f * PI.toFloat() * 165f * t) * exp(-t * 28f) * 0.65f
        }
    }

    fun playChatPop() {
        playGenerated(0.09f) { t, dur ->
            val freq = 520f + 480f * (t / dur)
            sin(2f * PI.toFloat() * freq * t) * exp(-t * 25f) * 0.4f
        }
    }

    fun playTaskComplete() {
        playGenerated(0.38f) { t, _ ->
            val freq = when {
                t < 0.12f -> 523.25f
                t < 0.24f -> 659.25f
                else -> 783.99f
            }
            sin(2f * PI.toFloat() * freq * t) * exp(-((t % 0.12f) * 10f)) * 0.5f
        }
    }

    fun playVictoryFanfare() {
        vibrate(180)
        val notes = floatArrayOf(523.25f, 659.25f, 783.99f, 1046.5f)
        playGenerated(0.76f) { t, _ ->
            val idx = (t / 0.18f).toInt().coerceIn(0, notes.lastIndex)
            val lt = t - idx * 0.18f
            val f = notes[idx]
            (sin(2f * PI.toFloat() * f * t) * 0.55f +
                sin(2f * PI.toFloat() * f * 2f * t) * 0.2f) * exp(-lt * 4.5f)
        }
    }

    fun playDefeatFanfare() {
        vibrate(160)
        val notes = floatArrayOf(392f, 369.99f, 349.23f, 293.66f)
        playGenerated(0.84f) { t, _ ->
            val idx = (t / 0.21f).toInt().coerceIn(0, notes.lastIndex)
            val lt = t - idx * 0.21f
            val f = notes[idx]
            sin(2f * PI.toFloat() * f * t) * exp(-lt * 3.8f) * 0.55f
        }
    }

    private fun playGenerated(durationSec: Float, sampleFn: (Float, Float) -> Float) {
        if (isSpeakerMuted) return
        scope.launch {
            try {
                val count = (sampleRate * durationSec).toInt().coerceAtLeast(1)
                val pcm = ShortArray(count)
                for (i in 0 until count) {
                    val t = i.toFloat() / sampleRate
                    val v = sampleFn(t, durationSec).coerceIn(-1f, 1f)
                    pcm[i] = (v * 26000f).toInt().toShort()
                }
                val bufferBytes = pcm.size * 2
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                kotlinx.coroutines.delay((durationSec * 1000).toLong() + 80L)
                track.stop()
                track.release()
            } catch (_: Throwable) {
                // Gracefully ignore if audio hardware is busy on an emulator
            }
        }
    }

    private fun vibrate(ms: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(ms)
            }
        } catch (_: Throwable) {
            // Optional haptic feedback
        }
    }
}
