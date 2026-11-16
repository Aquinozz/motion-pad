package com.motionpad.app

import java.util.concurrent.atomic.AtomicInteger

/** Bits dos 3 bytes de botões do relatório de entrada (direito, compartilhado, esquerdo). */
object Btn {
    // byte "direito"
    const val Y = 1 shl 0
    const val X = 1 shl 1
    const val B = 1 shl 2
    const val A = 1 shl 3
    const val SR = 1 shl 4
    const val SL = 1 shl 5
    const val R = 1 shl 6
    const val ZR = 1 shl 7

    // byte compartilhado
    const val MINUS = 1 shl 8
    const val PLUS = 1 shl 9
    const val RSTICK = 1 shl 10
    const val LSTICK = 1 shl 11
    const val HOME = 1 shl 12
    const val CAPTURE = 1 shl 13
}

/** Estado atual dos botões e do analógico, escrito pela UI e lido pela thread do Bluetooth. */
class ControllerState {
    private val buttons = AtomicInteger(0)

    /** -1..1, direita positiva */
    @Volatile var stickX = 0f

    /** -1..1, cima positivo */
    @Volatile var stickY = 0f

    fun press(mask: Int) { buttons.getAndUpdate { it or mask } }
    fun release(mask: Int) { buttons.getAndUpdate { it and mask.inv() } }
    fun buttons(): Int = buttons.get()
}
