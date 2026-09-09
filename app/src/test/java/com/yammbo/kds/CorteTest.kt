package com.yammbo.kds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El recorte del cartel: que quepa, que no mienta y que sirva.
 *
 * El corte vive en [Vigia.recortar] justo para poderlo probar aqui — la
 * cuenta de renglones que caben depende de la pantalla y del tamaño de letra
 * del sistema, y esos dos son los que rompian el cartel en la tablet.
 */
class CorteTest {

    /** Un plato de una sola linea, sin extras ni nota. */
    private fun simple(nombre: String) = listOf("1  $nombre")

    /** El caso real: plato con extras y con nota. Ocupa TRES renglones. */
    private val conTodo = listOf("1  Papa rellena", "   + Queso, Crema", "   “sin sal”")

    @Test fun siCabeTodoNoHayContador() {
        val (lineas, restan) = Vigia.recortar(
            listOf(simple("Tacos"), simple("Carne asada")), maxPlatos = 5, maxLineas = 9,
        )
        assertEquals(listOf("1  Tacos", "1  Carne asada"), lineas)
        assertEquals(0, restan)
    }

    @Test fun dejaSitioParaElContador() {
        // Caben 3 renglones y hay 5 platos: 2 platos + el contador = 3.
        val (lineas, restan) = Vigia.recortar(
            List(5) { simple("Plato $it") }, maxPlatos = 5, maxLineas = 3,
        )
        assertEquals(listOf("1  Plato 0", "1  Plato 1"), lineas)
        assertEquals(3, restan)
    }

    /**
     * 🚨 El invariante que de verdad importa: si el cartel se pasa de alto, el
     * marco lo recorta por ABAJO y lo que se pierde son los ultimos platos y el
     * pie («toca para abrir»). Se barren todas las formas de comanda hasta
     * cuatro platos contra todas las alturas que [Aviso.lineasQueCaben] puede
     * devolver, porque el fallo aparecia solo en las combinaciones estrechas.
     */
    @Test fun nuncaSePintaMasDeLoQueCabe() {
        val formas = listOf(1, 2, 3) // renglones que puede ocupar un plato
        for (maxLineas in 2..9) {
            for (a in formas) for (b in formas) for (c in formas) for (d in formas) {
                val bloques = listOf(a, b, c, d).map { n -> List(n) { "x" } }
                val (lineas, restan) = Vigia.recortar(bloques, maxPlatos = 5, maxLineas = maxLineas)
                val pintados = lineas.size + if (restan > 0) 1 else 0
                assertTrue(
                    "maxLineas=$maxLineas formas=$a$b$c$d pinta $pintados renglones",
                    pintados <= maxLineas,
                )
                // Y nunca un cartel mudo: si hay platos, se ve al menos uno.
                assertTrue("maxLineas=$maxLineas formas=$a$b$c$d sin un solo plato", lineas.isNotEmpty())
            }
        }
    }

    @Test fun unPlatoEntraEnteroONoEntra() {
        // El segundo plato ocupa 3 y solo queda hueco para 1: no se parte.
        val (lineas, restan) = Vigia.recortar(
            listOf(simple("Tacos"), conTodo, simple("Flan")), maxPlatos = 5, maxLineas = 3,
        )
        assertEquals(listOf("1  Tacos"), lineas)
        assertEquals(2, restan)
    }

    @Test fun elPrimerPlatoEntraAunqueNoQuepaEntero() {
        // 🚨 Pantalla baja con la letra del sistema en grande: ni el primer
        // plato cabe con sus extras. Antes salia un cartel que solo decia
        // «y 3 mas», sin un solo plato — inutil para quien esta cobrando.
        val (lineas, restan) = Vigia.recortar(
            listOf(conTodo, simple("Flan"), simple("Cafe")), maxPlatos = 5, maxLineas = 3,
        )
        assertEquals(listOf("1  Papa rellena"), lineas)
        // Ya se ve el primero: el contador cuenta los DOS que faltan, no tres.
        assertEquals(2, restan)
    }

    @Test fun elContadorCuentaPlatosNoRenglones() {
        // Dos platos que ocupan CUATRO renglones y caben de sobra: el contador
        // esta a cero aunque se hayan pintado cuatro lineas.
        val (lineas, restan) = Vigia.recortar(
            listOf(conTodo, simple("Flan")), maxPlatos = 5, maxLineas = 9,
        )
        assertEquals(4, lineas.size)
        assertEquals(2, lineas.count { !it.startsWith("   ") })
        assertEquals(0, restan)
    }

    @Test fun elContadorCuentaLoQueNiSiquieraSeLeyo() {
        // El worker mando una linea que no era un objeto y `detalle` la salto,
        // pero el cuerpo del cartel («3 articulos») si la cuenta. Si el
        // contador la olvidara, el mismo cartel se contradiria.
        val (lineas, restan) = Vigia.recortar(
            listOf(simple("Tacos")), maxPlatos = 5, maxLineas = 9, total = 3,
        )
        assertEquals(listOf("1  Tacos"), lineas)
        assertEquals(2, restan)
    }

    @Test fun elTopeDePlatosSigueMandando() {
        // Aunque la pantalla sea enorme, no se vuelca una comanda de veinte.
        val (lineas, restan) = Vigia.recortar(
            List(20) { simple("Plato $it") }, maxPlatos = 5, maxLineas = 9,
        )
        assertEquals(5, lineas.size)
        assertEquals(15, restan)
    }

    @Test fun unPedidoVacioNoPintaNada() {
        val (lineas, restan) = Vigia.recortar(emptyList(), maxPlatos = 5, maxLineas = 9)
        assertEquals(emptyList<String>(), lineas)
        assertEquals(0, restan)
    }
}
