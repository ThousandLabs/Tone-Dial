package com.thousandlabs.tonedial.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.media3.exoplayer.ExoPlayer
import com.thousandlabs.tonedial.data.Features
import kotlin.math.sqrt

/**
 * Secouer le téléphone pour passer au titre suivant (ou mettre en pause).
 * Utilise l'accéléromètre, sans autorisation à demander. Le capteur n'écoute que
 * pendant la lecture (et 10 minutes après une pause faite par secousse, pour pouvoir reprendre).
 * Il faut deux secousses franches et rapprochées : marcher ou poser le téléphone ne suffit pas.
 */
class ShakeControl(private val context: Context, private val player: ExoPlayer, private val handler: Handler) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private var registered = false
    private var listenUntil = 0L

    private var firstPeak = 0L
    private var lastPeak = 0L
    private var peaks = 0
    private var lastAction = 0L

    private val refreshRunnable = Runnable { refresh() }

    /** À appeler quand la lecture démarre ou s'arrête, et quand le réglage change. */
    fun refresh() {
        val want = Features.shake.on && (player.playWhenReady || SystemClock.elapsedRealtime() < listenUntil)
        if (want == registered) return
        if (want) {
            val sm = sensors ?: return
            val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            registered = runCatching { sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, handler) }.getOrDefault(false)
        } else {
            runCatching { sensors?.unregisterListener(this) }
            registered = false
            peaks = 0
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        val threshold = when (Features.shakeSensitivity()) {
            0 -> 3.2f // faible : il faut secouer fort
            2 -> 2.1f // forte : une secousse légère suffit
            else -> 2.6f
        }
        if (g < threshold) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPeak < 100) return // même mouvement
        lastPeak = now
        if (now - firstPeak > 1000) {
            firstPeak = now
            peaks = 1
            return
        }
        peaks++
        // deux secousses (aller-retour) = au moins 3 pics en moins d'une seconde
        if (peaks >= 3 && now - lastAction > 1500) {
            lastAction = now
            peaks = 0
            firstPeak = 0
            act()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun act() {
        when (Features.shakeAction()) {
            1 -> {
                if (player.playWhenReady) {
                    player.pause()
                    // on continue d'écouter un moment pour pouvoir relancer d'une secousse
                    listenUntil = SystemClock.elapsedRealtime() + 10 * 60_000L
                    handler.removeCallbacks(refreshRunnable)
                    handler.postDelayed(refreshRunnable, 10 * 60_000L + 500)
                } else {
                    player.play()
                }
            }
            else -> {
                if (!player.hasNextMediaItem()) return
                player.seekToNextMediaItem()
            }
        }
        if (Features.shakeVibrate.on) vibrate()
    }

    private fun vibrate() = runCatching {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        v?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun release() {
        handler.removeCallbacks(refreshRunnable)
        runCatching { sensors?.unregisterListener(this) }
        registered = false
    }
}
