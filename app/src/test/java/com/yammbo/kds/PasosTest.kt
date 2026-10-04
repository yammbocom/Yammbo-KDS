package com.yammbo.kds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los pasos de permisos de la primera vez.
 *
 * Lo que mas importa aqui es que un paso respondido no vuelva: pedir otra vez
 * lo que Android ya contesta solo es lo que antes acababa en bucle.
 */
class PasosTest {

    @Test fun conTodoConcedidoNoHayPasos() {
        assertTrue(Pasos.pendientes(true, 34, true, true, true).isEmpty())
    }

    @Test fun unaTabletYaVinculadaVaDirectaALaCocina() {
        // Faltan los dos permisos, pero no es la primera vinculacion.
        assertTrue(Pasos.pendientes(false, 34, false, true, false).isEmpty())
        assertEquals(
            listOf(Paso.AVISOS, Paso.ENCIMA), Pasos.pendientes(true, 34, false, true, false))
    }

    @Test fun antesDeAndroid13NoSePidenAvisos() {
        assertEquals(listOf(Paso.ENCIMA), Pasos.pendientes(true, 32, false, true, false))
    }

    @Test fun sinQuererElCartelNoSePideDibujarEncima() {
        assertEquals(listOf(Paso.AVISOS), Pasos.pendientes(true, 34, false, false, false))
    }

    @Test fun unPasoRespondidoNoVuelveAunqueSeDenegara() {
        val plan = Pasos.pendientes(true, 34, false, true, false)
        assertEquals(Paso.AVISOS, Pasos.siguiente(plan, emptySet(), emptySet()))
        // Denegado: sigue sin estar concedido, pero ya se respondio.
        assertEquals(Paso.ENCIMA, Pasos.siguiente(plan, setOf(Paso.AVISOS), emptySet()))
        assertNull(Pasos.siguiente(plan, setOf(Paso.AVISOS, Paso.ENCIMA), emptySet()))
    }

    @Test fun loConcedidoEntretantoSeSalta() {
        val plan = Pasos.pendientes(true, 34, false, true, false)
        assertEquals(Paso.ENCIMA, Pasos.siguiente(plan, emptySet(), setOf(Paso.AVISOS)))
    }

    @Test fun elContadorNoCambiaDeTotalAMitadDeCamino() {
        val plan = Pasos.pendientes(true, 34, false, true, false)
        assertEquals(1 to 2, Pasos.contador(plan, Paso.AVISOS))
        assertEquals(2 to 2, Pasos.contador(plan, Paso.ENCIMA))
    }
}
