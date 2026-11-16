package com.motionpad.app

/**
 * Memória SPI virtual do controle. O Switch lê daqui a calibração de fábrica
 * (analógicos e IMU), cores e parâmetros. Valores escolhidos para que a
 * conversão fique "limpa":
 *  - acelerômetro: 4096 unidades = 1 G
 *  - giroscópio: 13371/936 unidades por grau/s
 */
class SpiFlash {
    private val mem = ByteArray(0x10000) { 0xFF.toByte() }

    init {
        mem[0x6012] = 0x02 // tipo: Joy-Con direito
        mem[0x601B] = 0x01 // usar cores da SPI

        // Calibração de fábrica da IMU (0x6020, 24 bytes)
        putShorts(
            0x6020,
            0, 0, 0,                    // origem do acelerômetro
            0x4000, 0x4000, 0x4000,     // sensibilidade do acelerômetro
            0, 0, 0,                    // origem do giroscópio
            0x343B, 0x343B, 0x343B      // sensibilidade do giroscópio
        )

        // Analógico esquerdo (0x603D): acima do centro, centro, abaixo do centro
        putStick(0x603D, STICK_RANGE, STICK_RANGE)
        putStick(0x6040, STICK_CENTER, STICK_CENTER)
        putStick(0x6043, STICK_RANGE, STICK_RANGE)
        // Analógico direito (0x6046): centro, abaixo do centro, acima do centro
        putStick(0x6046, STICK_CENTER, STICK_CENTER)
        putStick(0x6049, STICK_RANGE, STICK_RANGE)
        putStick(0x604C, STICK_RANGE, STICK_RANGE)
        mem[0x604F] = 0xFF.toByte()

        // Cores: corpo, botões, grip esquerdo, grip direito
        putBytes(0x6050, 0xFF, 0x4D, 0x3D, 0x1A, 0x1A, 0x1A, 0xFF, 0x4D, 0x3D, 0xFF, 0x4D, 0x3D)

        // Offsets horizontais da IMU + parâmetros dos analógicos
        putBytes(0x6080, 0x50, 0xFD, 0x00, 0x00, 0xC6, 0x0F)
        val stickParams = intArrayOf(
            0x0F, 0x30, 0x61, 0x96, 0x30, 0xF3, 0xD4, 0x14, 0x54,
            0x41, 0x15, 0x54, 0xC7, 0x79, 0x9C, 0x33, 0x36, 0x63
        )
        putBytes(0x6086, *stickParams)
        putBytes(0x6098, *stickParams)
        // 0x8010+ (calibração do usuário) fica 0xFF = "sem calibração do usuário"
    }

    fun read(address: Int, length: Int): ByteArray =
        ByteArray(length) { i ->
            val a = address + i
            if (a in mem.indices) mem[a] else 0xFF.toByte()
        }

    private fun putBytes(addr: Int, vararg v: Int) {
        v.forEachIndexed { i, b -> mem[addr + i] = b.toByte() }
    }

    private fun putShorts(addr: Int, vararg v: Int) {
        v.forEachIndexed { i, s ->
            mem[addr + i * 2] = (s and 0xFF).toByte()
            mem[addr + i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
    }

    private fun putStick(addr: Int, x: Int, y: Int) {
        mem[addr] = (x and 0xFF).toByte()
        mem[addr + 1] = (((x shr 8) and 0x0F) or ((y and 0x0F) shl 4)).toByte()
        mem[addr + 2] = ((y shr 4) and 0xFF).toByte()
    }

    companion object {
        const val STICK_CENTER = 0x800
        const val STICK_RANGE = 0x580
    }
}
