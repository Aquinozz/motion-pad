package com.motionpad.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread

/**
 * Lê giroscópio e acelerômetro na taxa máxima e guarda tudo o que chegou desde o
 * último relatório enviado. O giroscópio é INTEGRADO (ângulo girado), assim a
 * rotação total que o Switch recebe é exatamente a rotação real do celular,
 * mesmo com atrasos ou variações no Bluetooth.
 */
class MotionSensor(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val available get() = gyro != null && accel != null

    private var thread: HandlerThread? = null
    private val lock = Any()

    // incrementos de ângulo (rad) por amostra do giroscópio, desde o último take()
    private val gx = FloatArray(CAP); private val gy = FloatArray(CAP); private val gz = FloatArray(CAP)
    private var gn = 0
    private var lastGyroTs = 0L

    // amostras de aceleração (m/s²) desde o último take()
    private val ax = FloatArray(CAP); private val ay = FloatArray(CAP); private val az = FloatArray(CAP)
    private var an = 0
    private val lastAccel = floatArrayOf(0f, 0f, SensorManager.GRAVITY_EARTH)

    /** total de amostras do giroscópio, para mostrar a taxa (Hz) na tela */
    @Volatile var gyroSamples = 0L
        private set

    fun start() {
        if (thread != null || !available) return
        val t = HandlerThread("sensors", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
        thread = t
        val h = Handler(t.looper)
        lastGyroTs = 0L
        sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST, h)
        sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST, h)
    }

    fun stop() {
        sm?.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        discard()
    }

    override fun onSensorChanged(e: SensorEvent) {
        synchronized(lock) {
            when (e.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    gyroSamples++
                    val prev = lastGyroTs
                    lastGyroTs = e.timestamp
                    if (prev == 0L) return
                    val dt = (e.timestamp - prev) * 1e-9f
                    if (dt <= 0f || dt > 0.05f) return
                    val i = if (gn < CAP) gn++ else CAP - 1 // se lotar, acumula na última
                    gx[i] += e.values[0] * dt
                    gy[i] += e.values[1] * dt
                    gz[i] += e.values[2] * dt
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    lastAccel[0] = e.values[0]; lastAccel[1] = e.values[1]; lastAccel[2] = e.values[2]
                    if (an < CAP) {
                        ax[an] = e.values[0]; ay[an] = e.values[1]; az[an] = e.values[2]; an++
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Divide o que chegou em 3 quadros (o relatório do controle leva 3 amostras).
     * out[k*6 + 0..2] = aceleração média (m/s²), out[k*6 + 3..5] = ângulo girado (rad).
     * Coordenadas do celular.
     */
    fun take(out: FloatArray) {
        synchronized(lock) {
            for (k in 0 until 3) {
                val gs = k * gn / 3; val ge = (k + 1) * gn / 3
                var sx = 0f; var sy = 0f; var sz = 0f
                for (i in gs until ge) { sx += gx[i]; sy += gy[i]; sz += gz[i] }
                out[k * 6 + 3] = sx; out[k * 6 + 4] = sy; out[k * 6 + 5] = sz

                val s = k * an / 3; val e = (k + 1) * an / 3
                if (e > s) {
                    var mx = 0f; var my = 0f; var mz = 0f
                    for (i in s until e) { mx += ax[i]; my += ay[i]; mz += az[i] }
                    val n = (e - s).toFloat()
                    out[k * 6] = mx / n; out[k * 6 + 1] = my / n; out[k * 6 + 2] = mz / n
                } else {
                    out[k * 6] = lastAccel[0]; out[k * 6 + 1] = lastAccel[1]; out[k * 6 + 2] = lastAccel[2]
                }
            }
            clearLocked()
        }
    }

    fun discard() = synchronized(lock) { clearLocked() }

    private fun clearLocked() {
        for (i in 0 until gn) { gx[i] = 0f; gy[i] = 0f; gz[i] = 0f }
        gn = 0
        an = 0
    }

    companion object { private const val CAP = 512 }
}
