package com.yammbo.kds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El enlace de cocina: lo que se pega, cuando vale y que dice el servidor. */
class EnlaceTest {

    private val bueno = "https://pos.yammbo.com/kds/0123456789abcdef0123456789abcdef"

    @Test fun seSacaElEnlaceDelTextoQueLoRodea() {
        assertEquals(bueno, Enlace.normalizar("Este es el enlace de la cocina: $bueno"))
        assertEquals(bueno, Enlace.normalizar("  $bueno\n"))
    }

    @Test fun laPuntuacionPegadaNoEntraEnElEnlace() {
        assertEquals(bueno, Enlace.normalizar("$bueno."))
        assertEquals(bueno, Enlace.normalizar("($bueno)"))
        assertEquals(bueno, Enlace.normalizar("«$bueno»"))
    }

    @Test fun sinEnlaceSeDevuelveLoEscritoLimpio() {
        assertEquals("hola", Enlace.normalizar("  hola "))
    }

    @Test fun soloValeHttpsDeCocina() {
        assertTrue(Enlace.valido(bueno))
        assertFalse(Enlace.valido(bueno.replace("https://", "http://")))
        assertFalse(Enlace.valido("https://pos.yammbo.com/panel"))
        assertFalse(Enlace.valido(""))
    }

    @Test fun laUrlDeDatosEsLaDeSiempre() {
        assertEquals("$bueno/data", Enlace.datos(bueno))
        assertEquals("$bueno/data", Enlace.datos("$bueno/"))
    }

    @Test fun loQueContestaElServidor() {
        assertEquals(Enlace.Resultado.OK, Enlace.clasificar(200, """{"comandas":[]}"""))
        // Contesta, pero no es una cocina.
        assertEquals(Enlace.Resultado.INVALIDO, Enlace.clasificar(200, "<html></html>"))
        // El worker responde 404 igual a un token revocado que a uno inventado.
        assertEquals(Enlace.Resultado.INVALIDO, Enlace.clasificar(404, null))
        // Cuenta en pausa: el enlace es bueno y la pagina lo explica.
        assertEquals(Enlace.Resultado.PAUSADO, Enlace.clasificar(402, null))
        // Limitado o caido: culpa del servidor, no del enlace.
        assertEquals(Enlace.Resultado.SERVIDOR, Enlace.clasificar(429, null))
        assertEquals(Enlace.Resultado.SERVIDOR, Enlace.clasificar(503, null))
    }
}
