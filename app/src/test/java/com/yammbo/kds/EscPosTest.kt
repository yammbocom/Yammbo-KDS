package com.yammbo.kds

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El ajuste de linea del ticket no se puede mirar en una termica cada vez que
 * se toca. Esto renderiza y deja ver el papel como texto, quitando las ordenes
 * ESC/POS, que es donde se ven los descuadres.
 */
class EscPosTest {

    /** Quita las secuencias de control para leer solo lo que se imprime. */
    private fun comoTexto(b: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        val ESC = 27; val GS = 29
        while (i < b.size) {
            val v = b[i].toInt() and 0xFF
            when {
                v == ESC && i + 1 < b.size -> {
                    // ESC @ / ESC a n / ESC E n / ESC d n / ESC t n
                    val c = b[i + 1].toInt().toChar()
                    i += if (c == '@') 2 else 3
                }
                v == GS -> i += if (i + 1 < b.size && b[i + 1].toInt().toChar() == 'V') 4 else 3
                else -> { sb.append(v.toChar()); i++ }
            }
        }
        return sb.toString()
    }

    private fun ticket() = Ticket(
        local = "Yambo Restuarant",
        comanda = "0082",
        origen = "web",
        hora = "2026-09-06 19:33:22",
        entrega = "delivery",
        cliente = "Frank Test",
        nota = "Tocar el timbre, apartamento 3 y preguntar por el encargado",
        lineas = listOf(
            Linea(1, "Papa rellena", emptyList(), null),
            Linea(2, "Tacos de pollo", listOf("Gallo pinto", "Arroz", "Ensalada"), "sin cebolla"),
            Linea(1, "Bistec encebollado con guarnicion doble", emptyList(), null),
            Linea(3, "Supercalifragilisticoespialidoso", emptyList(), null),
        ),
    )

    @Test fun papel58mm() {
        val t = comoTexto(EscPos.render(ticket(), 32))
        println("===== 58 mm (32 col) =====")
        t.split(Char(10)).forEach { println("|" + it + "|") }
        // Ninguna linea puede pasarse del papel.
        t.split(Char(10)).forEach { assertTrue("linea larga: " + it, it.length <= 32) }
    }

    @Test fun papel80mm() {
        val t = comoTexto(EscPos.render(ticket(), 48))
        println("===== 80 mm (48 col) =====")
        t.split(Char(10)).forEach { println("|" + it + "|") }
        t.split(Char(10)).forEach { assertTrue("linea larga: " + it, it.length <= 48) }
    }
}
