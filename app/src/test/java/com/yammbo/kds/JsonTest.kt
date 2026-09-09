package com.yammbo.kds

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El «null» que salia en el cartel y en el papel.
 *
 * 🚨 Esta prueba NO puede hacerse con un JSONObject de verdad: en la JVM el
 * org.json de referencia devuelve "" para un null y el fallo no aparece —
 * es el JSONObject de ANDROID el que devuelve las cuatro letras "null". Asi
 * que se prueba justo la funcion que lo limpia, que es la que corre en el
 * aparato. Se comprobo que falla si se le quita el arreglo.
 */
class JsonTest {

    @Test fun elNullDeAndroidNoLlegaAlCartel() {
        // Lo que optString devuelve en Android cuando el campo vale null.
        assertEquals("", Json.limpio("null"))
        assertEquals("", Json.limpio("undefined"))
        assertEquals("", Json.limpio(null))
        assertEquals("", Json.limpio(""))
        assertEquals("", Json.limpio("   "))
    }

    @Test fun unaNotaDeVerdadSeRespeta() {
        assertEquals("sin cebolla", Json.limpio("sin cebolla"))
        assertEquals("sin cebolla", Json.limpio("  sin cebolla  "))
        // Ni se recorta ni se toca lo que solo CONTIENE la palabra.
        assertEquals("nulling", Json.limpio("nulling"))
        assertEquals("sin null", Json.limpio("sin null"))
    }
}
