package com.yammbo.kds

import android.content.Context

/**
 * Lo que sabe ESTA tablet. La llave del KDS es una URL con token: vive solo
 * aqui, nunca se manda a ningun sitio nuestro, y si el encargado la revoca en
 * el panel deja de valer sola.
 */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("yammbo_kds", Context.MODE_PRIVATE)

    var url: String
        get() = p.getString("url", "") ?: ""
        set(v) = p.edit().putString("url", v.trim()).apply()

    /** Como se llega a la termica: "bt", "red" o "usb". */
    var conexion: String
        get() = p.getString("conexion", "bt") ?: "bt"
        set(v) = p.edit().putString("conexion", v).apply()

    /** MAC de la termica ya emparejada en los ajustes de Android (conexion bt). */
    var impresora: String
        get() = p.getString("impresora", "") ?: ""
        set(v) = p.edit().putString("impresora", v).apply()

    /** IP de la termica de red (conexion "red"). */
    var ip: String
        get() = p.getString("ip", "") ?: ""
        set(v) = p.edit().putString("ip", v.trim()).apply()

    /** 9100 es el puerto crudo de casi todas las termicas de red. */
    var puerto: Int
        get() = p.getInt("puerto", 9100)
        set(v) = p.edit().putInt("puerto", v).apply()

    /** Columnas del papel: 32 en 58 mm, 48 en 80 mm. */
    var ancho: Int
        get() = p.getInt("ancho", 32)
        set(v) = p.edit().putInt("ancho", v).apply()

    var sonido: Boolean
        get() = p.getBoolean("sonido", true)
        set(v) = p.edit().putBoolean("sonido", v).apply()

    var encima: Boolean
        get() = p.getBoolean("encima", true)
        set(v) = p.edit().putBoolean("encima", v).apply()

    // https y no http: el manifest lleva usesCleartextTraffic=false, asi que
    // una URL en claro fallaria siempre sin decir por que.
    val configurada: Boolean get() = url.startsWith("https://") && url.contains("/kds/")

    fun urlDatos(): String = url.trimEnd('/') + "/data"
}
