package com.motionpad.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

@SuppressLint("MissingPermission", "UseSwitchCompatOrMaterialCode", "SetTextI18n")
class MainActivity : Activity(), JoyConEmulator.Listener {
    private val state = ControllerState()
    private lateinit var motion: MotionSensor
    private lateinit var rumble: Rumble
    private var emulator: JoyConEmulator? = null

    private lateinit var statusText: TextView
    private lateinit var lightsText: TextView
    private lateinit var gyroText: TextView
    private lateinit var logText: TextView
    private val logLines = ArrayDeque<String>()
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("motionpad", MODE_PRIVATE) }
    private var lastGyroCount = 0L
    private var pairingUntil = 0L

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        motion = MotionSensor(this)
        rumble = Rumble(this).apply { enabled = prefs.getBoolean("rumble", true) }
        setContentView(buildUi())
        if (!motion.available) statusText.text = "Este celular não tem giroscópio."
        registerReceiver(scanModeReceiver, android.content.IntentFilter(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED))
        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        motion.start()
        ui.post(gyroTicker)
        // dá um instante para o app voltar a ser "primeiro plano" antes de registrar
        ui.postDelayed({ emulator?.ensureRegistered() }, 400)
    }

    override fun onPause() {
        super.onPause()
        motion.stop()
        ui.removeCallbacks(gyroTicker)
    }

    override fun onDestroy() {
        try { unregisterReceiver(scanModeReceiver) } catch (e: Exception) {}
        emulator?.stop()
        emulator = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- permissões / Bluetooth

    private fun ensurePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            val need = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), REQ_PERMS); return }
        }
        startEmulator()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_PERMS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startEmulator()
        else statusText.text = "Sem a permissão de \"Dispositivos próximos\" o app não consegue usar o Bluetooth. Libere nas configurações do app."
    }

    private fun startEmulator() {
        if (emulator != null) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { statusText.text = "Este aparelho não tem Bluetooth."; return }
        if (!adapter.isEnabled) {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE)
            return
        }
        emulator = JoyConEmulator(applicationContext, state, motion, rumble, this).apply {
            altAxes = prefs.getBoolean("alt_axes", false)
            start()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ENABLE) {
            if (resultCode == RESULT_OK) startEmulator() else statusText.text = "Ligue o Bluetooth para usar o controle."
        }
        if (requestCode == REQ_DISCOVERABLE) {
            if (resultCode == RESULT_CANCELED) {
                pairingUntil = 0
                statusText.text = "Você recusou deixar o celular visível. Toque em \"Parear\" de novo e aceite."
            } else {
                onLog("visível por ${resultCode}s")
                statusText.text = "Pronto e visível como \"${JoyConEmulator.CONTROLLER_NAME}\". No Switch, abra Controles > Mudar empunhadura/ordem e aguarde. Deixe este app aberto."
            }
        }
    }

    private fun pair() {
        if (emulator == null) { ensurePermissions(); return }
        pairingUntil = System.currentTimeMillis() + 300_000
        statusText.text = "Aceite deixar o celular visível e volte para este app."
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300),
            REQ_DISCOVERABLE
        )
    }

    private fun reconnect() {
        val e = emulator ?: return ensurePermissions()
        if (e.isConnected()) { statusText.text = "Já está conectado."; return }
        if (!e.hasSavedHost()) { statusText.text = "Nenhum Switch pareado ainda. Use \"Parear\" primeiro."; return }
        if (!e.reconnect()) statusText.text = "Não deu para reconectar agora. Tente de novo ou pareie outra vez."
    }

    // ---------------------------------------------------------------- callbacks do emulador

    override fun onStatus(status: JoyConEmulator.Status, detail: String?) {
        ui.post {
            statusText.text = when (status) {
                JoyConEmulator.Status.STARTING -> "Iniciando Bluetooth…"
                JoyConEmulator.Status.READY ->
                    if (System.currentTimeMillis() < pairingUntil)
                        "Pronto e visível como \"${JoyConEmulator.CONTROLLER_NAME}\". No Switch, abra Controles > Mudar empunhadura/ordem e aguarde. Deixe este app aberto."
                    else (detail?.let { "$it. " } ?: "") + "Pronto. Toque em \"Parear\" (1ª vez) ou \"Reconectar\"."
                JoyConEmulator.Status.CONNECTING -> detail ?: "Conectando…"
                JoyConEmulator.Status.CONNECTED -> "Conectado ao ${detail ?: "Switch"}! Segure o celular com a tela para cima e o topo apontando para a TV."
                JoyConEmulator.Status.ERROR -> "Erro: ${detail ?: "desconhecido"}"
            }
            statusText.setTextColor(if (status == JoyConEmulator.Status.CONNECTED) GREEN else Color.WHITE)
            if (status != JoyConEmulator.Status.CONNECTED) lightsText.text = "○ ○ ○ ○"
        }
    }

    override fun onPlayerLights(mask: Int) {
        val on = mask or (mask shr 4) // luzes fixas ou piscando
        ui.post { lightsText.text = (0 until 4).joinToString(" ") { if (on and (1 shl it) != 0) "●" else "○" } }
    }

    override fun onLog(line: String) {
        android.util.Log.i("MotionPad", line)
        ui.post {
            logLines.addLast(line)
            if (line.startsWith("nome=") && logLines.lastOrNull()?.startsWith("nome=") == true) logLines.removeLast()
            while (logLines.size > 10) logLines.removeFirst()
            logText.text = logLines.joinToString("\n")
        }
    }

    private val scanModeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) { emulator?.logAdapter() }
    }

    private val gyroTicker = object : Runnable {
        override fun run() {
            val n = motion.gyroSamples
            gyroText.text = "Giroscópio: ${n - lastGyroCount} Hz"
            lastGyroCount = n
            ui.postDelayed(this, 1000)
        }
    }

    // ---------------------------------------------------------------- interface

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(px(12), px(12), px(12), px(12))
        }

        // topo: status
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "MotionPad"; textSize = 20f; setTextColor(Color.WHITE); paint.isFakeBoldText = true
        }, LinearLayout.LayoutParams(0, wrap(), 1f))
        lightsText = TextView(this).apply { text = "○ ○ ○ ○"; textSize = 18f; setTextColor(GREEN) }
        header.addView(lightsText)
        root.addView(header)
        statusText = TextView(this).apply { textSize = 14f; setTextColor(Color.WHITE); setPadding(0, px(4), 0, 0) }
        root.addView(statusText)
        gyroText = TextView(this).apply { textSize = 12f; setTextColor(GRAY) }
        root.addView(gyroText)
        // registro de diagnóstico no topo, para sempre aparecer no print
        logText = TextView(this).apply {
            textSize = 10f; setTextColor(GRAY); typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(0xFF0E1015.toInt()); setPadding(px(6), px(4), px(6), px(4))
            minLines = 10; maxLines = 10
        }
        root.addView(logText)

        // gatilhos
        root.addView(row(2f, btn("ZR", Btn.ZR, false, RED), btn("R", Btn.R, false, RED)))

        // analógico
        root.addView(StickView(this, state), LinearLayout.LayoutParams(match(), 0, 4f))

        // ABXY em losango
        val diamond = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        diamond.addView(row(1f, space(), btn("X", Btn.X, true, BLUE), space()))
        diamond.addView(row(1f, btn("Y", Btn.Y, true, BLUE), space(), btn("A", Btn.A, true, BLUE)))
        diamond.addView(row(1f, space(), btn("B", Btn.B, true, BLUE), space()))
        root.addView(diamond, LinearLayout.LayoutParams(match(), 0, 5f).apply { leftMargin = px(30); rightMargin = px(30) })

        root.addView(row(1.3f, btn("+", Btn.PLUS, false, GRAY), btn("HOME", Btn.HOME, false, GRAY), btn("R3", Btn.RSTICK, false, GRAY)))
        root.addView(row(1.3f, btn("SL", Btn.SL, false, GREEN), btn("SR", Btn.SR, false, GREEN), btn("SL+SR", Btn.SL or Btn.SR, false, GREEN)))

        // ações
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(action("Parear") { pair() }, LinearLayout.LayoutParams(0, wrap(), 1f))
        actions.addView(action("Reconectar") { reconnect() }, LinearLayout.LayoutParams(0, wrap(), 1f))
        root.addView(actions)

        root.addView(toggle("Eixos alternativos (use se o movimento sair invertido)", prefs.getBoolean("alt_axes", false)) {
            prefs.edit().putBoolean("alt_axes", it).apply()
            emulator?.altAxes = it
        })
        root.addView(toggle("Vibração", rumble.enabled) {
            prefs.edit().putBoolean("rumble", it).apply()
            rumble.enabled = it
            if (!it) rumble.stop()
        })
        return root
    }

    private fun btn(label: String, mask: Int, round: Boolean, color: Int) = PadButton(this, label, mask, state, round, color)

    private fun space() = View(this)

    private fun row(weight: Float, vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, match(), 1f)) }
        layoutParams = LinearLayout.LayoutParams(match(), 0, weight)
    }

    private fun action(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        isChecked = initial
        setPadding(0, px(4), 0, px(4))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun px(v: Int) = (v * dp).toInt()
    private fun match() = ViewGroup.LayoutParams.MATCH_PARENT
    private fun wrap() = ViewGroup.LayoutParams.WRAP_CONTENT

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_ENABLE = 2
        private const val REQ_DISCOVERABLE = 3
        private const val BG = 0xFF15181F.toInt()
        private const val RED = 0xFFFF4D3D.toInt()
        private const val BLUE = 0xFF4DA3FF.toInt()
        private const val GREEN = 0xFF3DDC84.toInt()
        private const val GRAY = 0xFF9AA3B2.toInt()
    }
}
