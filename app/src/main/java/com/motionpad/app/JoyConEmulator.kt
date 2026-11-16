package com.motionpad.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.provider.Settings
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.locks.LockSupport
import kotlin.random.Random

/**
 * Faz o celular se apresentar ao Switch como um Joy-Con (R) via Bluetooth HID
 * (API BluetoothHidDevice, Android 9+, sem root).
 *
 * Fluxo: o Switch conecta -> pede informações (subcomandos no relatório 0x01) ->
 * respondemos com relatórios 0x21 -> ele liga o modo 0x30 e a IMU -> enviamos
 * relatórios 0x30 a cada 15 ms com botões, analógico e 3 amostras de movimento.
 */
@SuppressLint("MissingPermission")
class JoyConEmulator(
    private val context: Context,
    private val state: ControllerState,
    private val motion: MotionSensor,
    private val rumble: Rumble,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(status: Status, detail: String? = null)
        fun onPlayerLights(mask: Int)
        fun onLog(line: String)
    }

    enum class Status { STARTING, READY, CONNECTING, CONNECTED, ERROR }

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = context.getSharedPreferences("motionpad", Context.MODE_PRIVATE)
    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private val spi = SpiFlash()
    private val mac = loadMac()

    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var registered = false
    @Volatile private var imuEnabled = false
    @Volatile private var imuMode = 0
    @Volatile private var running = false

    /** Se o movimento sair invertido no jogo, o usuário liga isto. */
    @Volatile var altAxes = false

    private val replies = ConcurrentLinkedQueue<ByteArray>()
    private var sender: Thread? = null
    private var timer = 0
    private var debugCount = 0
    private var lastLoggedButtons = 0
    private val gyroCarry = FloatArray(3)

    // ---------------------------------------------------------------- ciclo de vida

    fun start() {
        val a = adapter ?: return listener.onStatus(Status.ERROR, "Este aparelho não tem Bluetooth")
        listener.onStatus(Status.STARTING)
        setControllerName()
        running = true
        sender = Thread(::sendLoop, "joycon-sender").apply { priority = Thread.MAX_PRIORITY; start() }
        val ok = a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                hid = proxy as BluetoothHidDevice
                register()
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null; host = null; registered = false
                listener.onStatus(Status.ERROR, "Serviço HID do Android caiu")
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) listener.onStatus(Status.ERROR, "Este Android não permite o modo controle Bluetooth (HID)")
    }

    fun stop() {
        running = false
        sender?.let { LockSupport.unpark(it) }
        sender = null
        val h = hid
        try {
            host?.let { h?.disconnect(it) }
            if (registered) h?.unregisterApp()
            if (h != null) adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
        } catch (e: Exception) { Log.w(TAG, "stop", e) }
        hid = null; host = null; registered = false
        restoreName()
        rumble.stop()
        callbackExecutor.shutdown()
    }

    /** Reconecta ao último Switch pareado (o Switch precisa estar ligado). */
    fun reconnect(): Boolean {
        val addr = prefs.getString(KEY_HOST, null) ?: return false
        val h = hid ?: return false
        if (!registered) return false
        return try {
            listener.onStatus(Status.CONNECTING, "Reconectando…")
            h.connect(adapter!!.getRemoteDevice(addr))
        } catch (e: Exception) { false }
    }

    /** Chamado quando o app volta para a tela: registra de novo se o Android cancelou. */
    fun ensureRegistered() {
        // o HyperOS às vezes devolve o nome original; o Switch só aceita "Joy-Con (R)"
        try { if (adapter?.name != CONTROLLER_NAME) setControllerName() } catch (e: Exception) {}
        if (hid != null && !registered) {
            try { hid?.unregisterApp() } catch (e: Exception) {}
            register()
        }
    }

    fun hasSavedHost() = prefs.getString(KEY_HOST, null) != null

    fun isConnected() = host != null

    // ---------------------------------------------------------------- Bluetooth

    private fun register() {
        val h = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Wireless Gamepad", "Gamepad", "Nintendo", 0x08.toByte(), DESCRIPTOR
        )
        val ok = h.registerApp(sdp, null, null, callbackExecutor, callback)
        listener.onLog("registerApp -> $ok")
        if (!ok) listener.onStatus(Status.ERROR, "Não foi possível registrar o controle. Desinstale o Joy-Con Droid ou outros apps de controle/teclado Bluetooth e abra o app de novo.")
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@JoyConEmulator.registered = registered
            listener.onLog("HID registrado=$registered plugged=${pluggedDevice?.address}")
            logAdapter()
            if (registered) {
                listener.onStatus(Status.READY)
                if (host == null) reconnect() // tenta voltar sozinho para o último Switch
            } else if (running) {
                // O Android cancela o registro quando o app sai do primeiro plano
                // (ex.: janela de "tornar visível"). Re-registramos ao voltar.
                listener.onLog("registro cancelado pelo Android; re-registrando ao voltar ao app")
                listener.onStatus(Status.STARTING)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            listener.onLog("conexão ${device.address} (${device.name}) estado=$state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    imuEnabled = false
                    replies.clear()
                    prefs.edit().putString(KEY_HOST, device.address).apply()
                    listener.onStatus(Status.CONNECTED, device.name)
                }
                BluetoothProfile.STATE_CONNECTING -> listener.onStatus(Status.CONNECTING)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host == null || host == device) {
                        host = null
                        imuEnabled = false
                        rumble.stop()
                        listener.onStatus(Status.READY, "Desconectado")
                    }
                }
            }
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            handleOutput(reportId.toInt() and 0xFF, data)
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            handleOutput(id.toInt() and 0xFF, data)
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val r = ByteArray(REPORT_LEN)
            synchronized(this@JoyConEmulator) { fillCommon(r) }
            hid?.replyReport(device, type, id, r)
        }
    }

    // ---------------------------------------------------------------- Switch -> controle

    private fun handleOutput(rawId: Int, rawData: ByteArray) {
        var id = rawId
        var data = rawData
        if (id == 0 && data.isNotEmpty()) { // alguns Androids entregam o ID dentro dos dados
            id = data[0].toInt() and 0xFF
            data = data.copyOfRange(1, data.size)
        }
        when (id) {
            0x01 -> {
                rumble.onRumble(data, 1)
                if (data.size >= 10) handleSubcommand(data[9].toInt() and 0xFF, data, 10)
            }
            0x10 -> rumble.onRumble(data, 1)
        }
    }

    private fun handleSubcommand(sub: Int, buf: ByteArray, off: Int) {
        fun arg(i: Int) = if (off + i < buf.size) buf[off + i].toInt() and 0xFF else 0
        listener.onLog("subcomando 0x%02X args=%02X %02X %02X %02X %02X".format(sub, arg(0), arg(1), arg(2), arg(3), arg(4)))
        when (sub) {
            0x01 -> reply(0x81, sub, 0x03) // pareamento manual
            0x02 -> reply(0x82, sub, 0x04, 0x00, 0x02 /* Joy-Con R */, 0x02, *mac, 0x01, 0x01)
            0x03 -> reply(0x80, sub) // modo de relatório (0x30)
            0x04 -> reply(0x83, sub, 0x2C, 0x01, 0x2C, 0x01) // tempo de L/R pressionados
            0x10 -> { // leitura da SPI
                val addr = arg(0) or (arg(1) shl 8) or (arg(2) shl 16) or (arg(3) shl 24)
                val len = arg(4).coerceAtMost(0x1D)
                val head = intArrayOf(arg(0), arg(1), arg(2), arg(3), len)
                val body = spi.read(addr, len).map { it.toInt() and 0xFF }
                reply(0x90, sub, *(head + body))
            }
            0x11, 0x12 -> reply(0x80, sub, 0x00) // escrita/apagamento SPI: finge que deu certo
            0x21 -> reply(0x80, sub) // config. do chip NFC/IR: só confirma (igual ao switch-pico)
            0x30 -> { listener.onPlayerLights(arg(0)); reply(0x80, sub) }
            0x40 -> { // 0 = desligada, 1 = dados brutos, 2 = quaternion
                synchronized(this) { imuMode = arg(0); resetQuaternion(); gyroCarry.fill(0f) }
                imuEnabled = imuMode != 0
                motion.discard()
                reply(0x80, sub)
            }
            else -> reply(0x80, sub) // 0x08, 0x22, 0x38, 0x41, 0x48, ...
        }
    }

    private fun makeReply(ack: Int, sub: Int, vararg data: Int): ByteArray {
        val r = ByteArray(REPORT_LEN)
        r[12] = ack.toByte()
        r[13] = sub.toByte()
        data.forEachIndexed { i, b -> if (14 + i < REPORT_LEN) r[14 + i] = b.toByte() }
        return r
    }

    private fun reply(ack: Int, sub: Int, vararg data: Int) = queue(makeReply(ack, sub, *data))

    private fun queue(r: ByteArray) {
        replies.add(r)
        sender?.let { LockSupport.unpark(it) }
    }

    // ---------------------------------------------------------------- controle -> Switch

    private fun sendLoop() {
        val motionBuf = FloatArray(18)
        var next = System.nanoTime()
        while (running) {
            val h = host
            val dev = hid
            val reply = replies.poll()
            if (reply != null) {
                if (h != null && dev != null) {
                    synchronized(this) { fillCommon(reply) }
                    send(dev, h, 0x21, reply)
                }
                continue
            }
            val now = System.nanoTime()
            if (now < next) {
                LockSupport.parkNanos(next - now)
                continue
            }
            if (h != null && dev != null) {
                val r = ByteArray(REPORT_LEN)
                synchronized(this) { fillCommon(r) }
                if (imuEnabled) {
                    motion.take(motionBuf)
                    synchronized(this) { encodeImu(r, motionBuf) }
                    if (++debugCount % 66 == 0) { // ~1x por segundo: o que está saindo
                        fun s(o: Int) = ((r[o].toInt() and 0xFF) or (r[o + 1].toInt() shl 8)).toShort().toInt()
                        Log.i("MotionPad", "imu modo=%d acc=%d,%d,%d g/q=%d,%d,%d quat=%.2f,%.2f,%.2f,%.2f".format(
                            imuMode, s(12), s(14), s(16), s(18), s(20), s(22), qx, qy, qz, qw))
                    }
                } else motion.discard()
                send(dev, h, 0x30, r)
            } else motion.discard()
            next += PERIOD_NS
            if (now - next > 100_000_000L) next = now + PERIOD_NS // atrasou demais: ressincroniza
        }
    }

    private fun send(dev: BluetoothHidDevice, h: BluetoothDevice, id: Int, r: ByteArray) {
        try { dev.sendReport(h, id, r) } catch (e: Exception) { Log.w(TAG, "sendReport", e) }
    }

    /** Bytes 0..11: timer, bateria, botões, analógicos, vibração. */
    private fun fillCommon(r: ByteArray) {
        timer = (timer + 1) and 0xFF
        r[0] = timer.toByte()
        r[1] = 0x8E.toByte() // bateria cheia + "é um Joy-Con"
        val b = state.buttons()
        if (b != lastLoggedButtons) {
            lastLoggedButtons = b
            listener.onLog("botões enviados=%06X".format(b))
        }
        r[2] = (b and 0xFF).toByte()
        r[3] = ((b shr 8) and 0xFF).toByte()
        r[4] = ((b shr 16) and 0xFF).toByte()
        // analógico esquerdo (r[5..7]) fica zerado no Joy-Con direito
        val x = (SpiFlash.STICK_CENTER + state.stickX * STICK_SPAN).toInt().coerceIn(0, 0xFFF)
        val y = (SpiFlash.STICK_CENTER + state.stickY * STICK_SPAN).toInt().coerceIn(0, 0xFFF)
        r[8] = (x and 0xFF).toByte()
        r[9] = (((x shr 8) and 0x0F) or ((y and 0x0F) shl 4)).toByte()
        r[10] = ((y shr 4) and 0xFF).toByte()
        r[11] = 0x80.toByte()
    }

    /**
     * Converte do eixo do celular para o eixo do Joy-Con (R) segurado "como controle remoto":
     * topo do celular apontando para a TV = ponta do ZR apontando para a TV, tela para cima.
     *   Joy-Con X = celular Y,  Joy-Con Y = celular X,  Joy-Con Z = -celular Z
     * Modo alternativo = mesmo mapeamento girado 180° no eixo longo.
     */
    private fun encodeImu(r: ByteArray, m: FloatArray) {
        if (imuMode == 2) return encodeImuQuaternion(r, m)
        val sy = if (altAxes) -1f else 1f
        for (k in 0 until 3) {
            val b = k * 6
            val accel = floatArrayOf(m[b + 1], sy * m[b], -sy * m[b + 2])
            val ang = floatArrayOf(m[b + 4], sy * m[b + 3], -sy * m[b + 5])
            val o = 12 + k * 12
            for (i in 0 until 3) {
                putShort(r, o + i * 2, (accel[i] * ACCEL_SCALE).toInt().coerceIn(-32767, 32767))
                // ângulo nesta fatia de 5 ms -> velocidade; o que passar do limite fica para a próxima
                var v = ang[i] * GYRO_SCALE + gyroCarry[i]
                val c = v.coerceIn(-32767f, 32767f)
                gyroCarry[i] = (v - c).coerceIn(-200_000f, 200_000f)
                v = c
                putShort(r, o + 6 + i * 2, v.toInt())
            }
        }
    }

    /**
     * Modo 2 da IMU (o que o Switch pede hoje): o controle integra a orientação num
     * quaternion e manda ele compactado. Formato conforme o projeto switch-pico
     * (github.com/jyapayne/switch-pico, fill_quaternion_imu_report_data).
     */
    private fun encodeImuQuaternion(r: ByteArray, m: FloatArray) {
        val sy = if (altAxes) -1.0 else 1.0
        var ax = 0.0; var ay = 0.0; var az = 0.0
        for (k in 0 until 3) {
            val b = k * 6
            // mesmos eixos do modo 1 (referencial do Joy-Con)
            val jx = m[b + 4].toDouble(); val jy = sy * m[b + 3]; val jz = -sy * m[b + 5]
            ax = m[b + 1].toDouble(); ay = sy * m[b]; az = -sy * m[b + 2]
            // o modo 2 usa a ordem Y, X, Z nos eixos do quaternion
            rotate(jy, jx, jz)
        }
        java.util.Arrays.fill(r, 12, 48, 0)
        val o = 12
        putShort(r, o + 0, (ay * ACCEL_SCALE).toInt().coerceIn(-32767, 32767))
        putShort(r, o + 2, (ax * ACCEL_SCALE).toInt().coerceIn(-32767, 32767))
        putShort(r, o + 4, (az * ACCEL_SCALE).toInt().coerceIn(-32767, 32767))

        val c = doubleArrayOf(qx, qy, qz, qw)
        var maxI = 0
        for (i in 1 until 4) if (Math.abs(c[i]) > Math.abs(c[maxI])) maxI = i
        val sign = if (c[maxI] < 0) -1.0 else 1.0
        val p = IntArray(3) { i ->
            val v = (c[(maxI + i + 1) and 3] * 1073741824.0 * sign).toLong()
            ((v shr 10) and 0x1FFFFF).toInt()
        }
        writeBits(r, o, 48, 2, 2)
        writeBits(r, o, 50, maxI, 2)
        writeBits(r, o, 52, p[0], 21)
        writeBits(r, o, 73, p[1], 21)
        writeBits(r, o, 94, p[2] and 0x3, 2)
        writeBits(r, o, 144, p[2] ushr 2, 19)
        writeBits(r, o, 271, (android.os.SystemClock.uptimeMillis() and 0x7FF).toInt(), 11)
        writeBits(r, o, 282, 3, 6)
    }

    // orientação atual (quaternion), integrada a partir do giroscópio
    private var qx = 0.0; private var qy = 0.0; private var qz = 0.0; private var qw = 1.0

    private fun resetQuaternion() { qx = 0.0; qy = 0.0; qz = 0.0; qw = 1.0 }

    /** Aplica uma rotação pequena (ângulos em rad, referencial do controle): q = q ⊗ dq */
    private fun rotate(x: Double, y: Double, z: Double) {
        val n = Math.sqrt(x * x + y * y + z * z)
        val s = if (n > 1e-12) Math.sin(n / 2) / n else 0.5
        val dw = if (n > 1e-12) Math.cos(n / 2) else 1.0
        val dx = x * s; val dy = y * s; val dz = z * s
        val nx = qw * dx + qx * dw + qy * dz - qz * dy
        val ny = qw * dy - qx * dz + qy * dw + qz * dx
        val nz = qw * dz + qx * dy - qy * dx + qz * dw
        val nw = qw * dw - qx * dx - qy * dy - qz * dz
        val mag = Math.sqrt(nx * nx + ny * ny + nz * nz + nw * nw)
        if (mag > 1e-12) { qx = nx / mag; qy = ny / mag; qz = nz / mag; qw = nw / mag } else resetQuaternion()
    }

    private fun writeBits(r: ByteArray, base: Int, bitOffset: Int, value: Int, width: Int) {
        for (bit in 0 until width) {
            if (value and (1 shl bit) != 0) {
                val ob = bitOffset + bit
                val i = base + (ob shr 3)
                r[i] = (r[i].toInt() or (1 shl (ob and 7))).toByte()
            }
        }
    }

    private fun putShort(r: ByteArray, o: Int, v: Int) {
        r[o] = (v and 0xFF).toByte()
        r[o + 1] = ((v shr 8) and 0xFF).toByte()
    }

    // ---------------------------------------------------------------- nome / MAC

    private fun setControllerName() {
        val a = adapter ?: return
        try {
            val current = a.name
            if (current != null && current != CONTROLLER_NAME) prefs.edit().putString(KEY_ORIG_NAME, current).apply()
            val ok = a.setName(CONTROLLER_NAME)
            listener.onLog("setName -> $ok (antes: $current)")
        } catch (e: Exception) { listener.onLog("setName falhou: $e") }
    }

    /** Mostra nome, modo de visibilidade e classe (CoD) do Bluetooth do celular. */
    fun logAdapter() {
        val a = adapter ?: return
        val mode = try { a.scanMode } catch (e: Exception) { -1 }
        val cod = try {
            val c = a.javaClass.getMethod("getBluetoothClass").invoke(a)
            c?.javaClass?.getMethod("getClassOfDevice")?.invoke(c)?.let { "0x%06X".format(it as Int) }
        } catch (e: Throwable) { "?" }
        listener.onLog("nome=${try { a.name } catch (e: Exception) { "?" }} visível=${mode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE} classe=$cod")
    }

    private fun restoreName() {
        val orig = prefs.getString(KEY_ORIG_NAME, null) ?: return
        try { adapter?.setName(orig) } catch (e: Exception) { Log.w(TAG, "restoreName", e) }
    }

    private fun loadMac(): IntArray {
        val candidates = listOf(
            try { Settings.Secure.getString(context.contentResolver, "bluetooth_address") } catch (e: Exception) { null },
            try { adapter?.address } catch (e: Exception) { null },
            prefs.getString(KEY_MAC, null),
        )
        for (c in candidates) {
            val parts = c?.split(":") ?: continue
            if (parts.size != 6 || c == "02:00:00:00:00:00") continue
            val bytes = parts.mapNotNull { it.toIntOrNull(16) }
            if (bytes.size == 6) return bytes.toIntArray()
        }
        // não dá para ler o MAC real: gera um fixo para este aparelho
        val gen = IntArray(6) { Random.nextInt(256) }.also { it[0] = (it[0] and 0xFC) or 0x02 }
        prefs.edit().putString(KEY_MAC, gen.joinToString(":") { "%02X".format(it) }).apply()
        return gen
    }

    companion object {
        private const val TAG = "JoyConEmulator"
        const val CONTROLLER_NAME = "Joy-Con (R)"
        private const val KEY_HOST = "host"
        private const val KEY_ORIG_NAME = "orig_name"
        private const val KEY_MAC = "mac"
        private const val REPORT_LEN = 49
        private const val PERIOD_NS = 15_000_000L
        private const val STICK_SPAN = 1400f
        private const val ACCEL_SCALE = 4096f / 9.80665f // m/s² -> unidades (4096 = 1 G)
        private const val GYRO_SCALE = (180f / Math.PI.toFloat()) / 0.005f * (13371f / 936f) // rad em 5 ms -> unidades

        /** Descritor HID do controle da Nintendo (mesmo layout do original). */
        private val DESCRIPTOR = intArrayOf(
            0x05, 0x01, 0x15, 0x00, 0x09, 0x04, 0xA1, 0x01,
            0x85, 0x30, 0x05, 0x01, 0x05, 0x09, 0x19, 0x01, 0x29, 0x0A, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95, 0x0A, 0x55, 0x00, 0x65, 0x00, 0x81, 0x02,
            0x05, 0x09, 0x19, 0x0B, 0x29, 0x0E, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x04, 0x81, 0x02,
            0x75, 0x01, 0x95, 0x02, 0x81, 0x03,
            0x0B, 0x01, 0x00, 0x01, 0x00, 0xA1, 0x00,
            0x0B, 0x30, 0x00, 0x01, 0x00, 0x0B, 0x31, 0x00, 0x01, 0x00,
            0x0B, 0x32, 0x00, 0x01, 0x00, 0x0B, 0x35, 0x00, 0x01, 0x00,
            0x15, 0x00, 0x27, 0xFF, 0xFF, 0x00, 0x00, 0x75, 0x10, 0x95, 0x04, 0x81, 0x02, 0xC0,
            0x0B, 0x39, 0x00, 0x01, 0x00, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01,
            0x65, 0x14, 0x75, 0x04, 0x95, 0x01, 0x81, 0x02,
            0x05, 0x09, 0x19, 0x0F, 0x29, 0x12, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x04, 0x81, 0x02,
            0x75, 0x08, 0x95, 0x34, 0x81, 0x03,
            0x06, 0x00, 0xFF,
            0x85, 0x21, 0x09, 0x01, 0x75, 0x08, 0x95, 0x3F, 0x81, 0x03,
            0x85, 0x81, 0x09, 0x02, 0x75, 0x08, 0x95, 0x3F, 0x81, 0x03,
            0x85, 0x01, 0x09, 0x03, 0x75, 0x08, 0x95, 0x3F, 0x91, 0x83,
            0x85, 0x10, 0x09, 0x04, 0x75, 0x08, 0x95, 0x3F, 0x91, 0x83,
            0x85, 0x80, 0x09, 0x05, 0x75, 0x08, 0x95, 0x3F, 0x91, 0x83,
            0x85, 0x82, 0x09, 0x06, 0x75, 0x08, 0x95, 0x3F, 0x91, 0x83,
            0xC0,
        ).map { it.toByte() }.toByteArray()
    }
}
