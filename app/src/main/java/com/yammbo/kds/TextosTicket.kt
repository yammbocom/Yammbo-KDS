package com.yammbo.kds

import android.content.Context

/**
 * Las etiquetas que van impresas en el papel, ya resueltas.
 *
 * `EscPos` no recibe un Context a proposito: es lo unico de la app que se puede
 * probar en la JVM, y ahi es donde se comprueba que ninguna linea se pase del
 * ancho del papel. Con recursos de Android dentro haria falta Robolectric para
 * comprobar una division entera.
 *
 * Van en MAYUSCULAS y cortas porque caben 32 columnas en un papel de 58 mm:
 * "CUSTOMER" ya ocupa un cuarto de la linea.
 */
data class TextosTicket(
    val tpv: String,
    val web: String,
    val envio: String,
    val recoge: String,
    val cliente: String,
    val nota: String,
    val articulo: String,
    val articulos: String,
) {
    companion object {
        fun de(ctx: Context): TextosTicket = TextosTicket(
            tpv = ctx.getString(R.string.tk_tpv),
            web = ctx.getString(R.string.tk_web),
            envio = ctx.getString(R.string.tk_envio),
            recoge = ctx.getString(R.string.tk_recoge),
            cliente = ctx.getString(R.string.tk_cliente),
            nota = ctx.getString(R.string.tk_nota),
            articulo = ctx.getString(R.string.tk_articulo),
            articulos = ctx.getString(R.string.tk_articulos),
        )

        /** El juego castellano, para las pruebas y como respaldo. */
        val ES = TextosTicket(
            tpv = "TPV", web = "WEB", envio = "· ENVIO", recoge = "· RECOGE",
            cliente = "CLIENTE", nota = "NOTA",
            articulo = "articulo", articulos = "articulos",
        )
    }
}
