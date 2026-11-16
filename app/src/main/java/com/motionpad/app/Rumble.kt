package com.motionpad.app

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.abs
import kotlin.math.max

/** Converte os comandos de vibração (HD Rumble) do Switch em vibração do celular. */
class Rumble(context: Context) {
    private val vib: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    @Volatile var enabled = true
    private var lastLevel = 0f
    private var lastCall = 0L

    /** data[off..off+7] = 4 bytes do lado esquerdo + 4 do direito */
    fun onRumble(data: ByteArray, off: Int) {
        val v = vib ?: return
        if (!enabled || data.size < off + 8) return
        val level = max(amp(data, off), amp(data, off + 4))
        val now = SystemClock.uptimeMillis()
        if (level < 0.04f) {
            if (lastLevel > 0f) { v.cancel(); lastLevel = 0f }
            return
        }
        if (abs(level - lastLevel) > 0.1f || now - lastCall > 40) {
            val a = if (v.hasAmplitudeControl()) (level * 255).toInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
            v.vibrate(VibrationEffect.createOneShot(60, a))
            lastLevel = level
            lastCall = now
        }
    }

    fun stop() { vib?.cancel() }

    private fun amp(d: ByteArray, o: Int): Float {
        val b1 = d[o + 1].toInt() and 0xFF
        val b3 = d[o + 3].toInt() and 0xFF
        val hf = (b1 shr 1) / 100f
        val lf = (b3 - 0x40).coerceAtLeast(0) / 50f
        return max(hf, lf).coerceIn(0f, 1f)
    }
}
