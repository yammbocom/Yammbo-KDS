package com.yammbo.kds

import org.json.JSONArray
import org.json.JSONObject

/**
 * Como se leen los textos de `/data` y del contrato de impresion.
 *
 * 🚨 El `JSONObject.optString` de ANDROID no se comporta como el org.json de
 * referencia: cuando el campo existe pero vale `null` devuelve las cuatro
 * letras "null" en vez de la cadena vacia. El worker declara `nota`, `note`,
 * `customer_name` y `fulfillment` como `string | null` y los manda como null
 * de verdad, asi que cada plato sin nota salia en el cartel — y en el papel —
 * con un «null» entrecomillado debajo.
 *
 * Un test en la JVM NO puede pillar esto (ahi org.json si devuelve ""), por eso
 * el arreglo va aqui, en un solo sitio, y no repartido por cada lectura.
 */
object Json {

    /**
     * Limpia lo que devuelve un `optString`.
     *
     * Tambien tira la cadena literal "null" — la que aparece cuando alguien
     * aguas arriba paso un null por `String(...)`. Un plato o una nota que de
     * verdad digan "null" no existen en una cocina; un «null» impreso en el
     * ticket, si, y ya se vio.
     */
    fun limpio(v: String?): String {
        val s = v?.trim() ?: return ""
        return if (s == "null" || s == "undefined") "" else s
    }
}

/** Texto de un campo, con el null de JSON tratado como ausencia. */
fun JSONObject.texto(clave: String): String =
    if (isNull(clave)) "" else Json.limpio(optString(clave))

/** Igual que [texto] pero para un elemento de un array. */
fun JSONArray.texto(i: Int): String =
    if (isNull(i)) "" else Json.limpio(optString(i))
