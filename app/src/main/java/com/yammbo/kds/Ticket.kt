package com.yammbo.kds

import org.json.JSONObject

/**
 * Lo que la pantalla web manda por `window.YammboPrint.imprimir(json)`.
 *
 * El contrato lo define el worker (src/kds.ts, campo `v`). Se lee con
 * tolerancia: si un dia le anaden un campo, aqui no se rompe nada; si falta
 * uno opcional, sale el ticket igual. Lo unico que no se puede perder es la
 * clave y las lineas, porque sin eso el papel no sirve de nada.
 */
data class Linea(
    val n: Int,
    val nombre: String,
    val extras: List<String>,
    val nota: String?,
)

data class Ticket(
    val local: String,
    val comanda: String,
    val origen: String,      // "web" | "tpv"
    val hora: String?,       // created_at, UTC sin zona (formato D1)
    val entrega: String?,    // "delivery" | "pickup" | null
    val cliente: String?,
    val nota: String?,
    val lineas: List<Linea>,
) {
    val articulos: Int get() = lineas.sumOf { it.n }

    companion object {
        fun de(json: String): Ticket {
            val o = JSONObject(json)
            val arr = o.optJSONArray("lineas")
            val ls = ArrayList<Linea>(arr?.length() ?: 0)
            for (i in 0 until (arr?.length() ?: 0)) {
                val l = arr!!.getJSONObject(i)
                val ex = l.optJSONArray("extras")
                val extras = ArrayList<String>(ex?.length() ?: 0)
                for (j in 0 until (ex?.length() ?: 0)) {
                    ex!!.optString(j).takeIf { it.isNotBlank() }?.let(extras::add)
                }
                ls.add(
                    Linea(
                        n = l.optInt("n", 1),
                        nombre = l.optString("nombre", ""),
                        extras = extras,
                        nota = l.optString("nota").ifBlank { null },
                    )
                )
            }
            // Sin lineas no hay nada que cocinar: mejor un error visible que
            // un trozo de papel en blanco saliendo de la termica.
            if (ls.isEmpty()) throw IllegalArgumentException("Comanda sin líneas")
            return Ticket(
                local = o.optString("local", ""),
                comanda = o.optString("comanda", ""),
                origen = o.optString("origen", "web"),
                hora = o.optString("hora").ifBlank { null },
                entrega = o.optString("entrega").ifBlank { null },
                cliente = o.optString("cliente").ifBlank { null },
                nota = o.optString("nota").ifBlank { null },
                lineas = ls,
            )
        }
    }
}
