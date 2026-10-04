package com.yammbo.kds

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * El enlace de cocina: como se limpia lo que se pega, cuando vale y que
 * contesta el servidor al probarlo.
 *
 * Lo que se puede probar en la JVM va aparte de la llamada de red.
 */
object Enlace {

    enum class Resultado {
        /** Es una cocina y contesta. */
        OK,
        /** Es una cocina, pero la cuenta esta en pausa: la pagina lo explica. */
        PAUSADO,
        /** El servidor no conoce ese enlace: inventado, mal copiado o revocado. */
        INVALIDO,
        /** El servidor contesto con un error suyo. */
        SERVIDOR,
        /** No se llego al servidor. */
        NO_LLEGA,
        /** La tablet no tiene red. */
        SIN_RED,
    }

    /**
     * Saca el enlace de lo que se pego. Al copiar desde un chat suele venir
     * con texto alrededor ("Este es el enlace: https://...") o con un punto
     * final pegado.
     */
    fun normalizar(texto: String): String {
        val t = texto.trim()
        val m = Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(t) ?: return t
        return m.value.trimEnd('.', ',', ';', ':', ')', ']', '"', '\'', '»', '”')
    }

    // https y no http: el manifest lleva usesCleartextTraffic=false, asi que
    // una URL en claro fallaria siempre sin decir por que.
    fun valido(url: String): Boolean = url.startsWith("https://") && url.contains("/kds/")

    fun datos(url: String): String = url.trimEnd('/') + "/data"

    /**
     * Que significa la respuesta de `/data`.
     *
     * El worker contesta 404 igual a un token revocado que a uno inventado, y
     * 402 si la cuenta esta en pausa (el enlace es bueno: se guarda y la
     * pagina lo explica). Un 429 o un 5xx son del servidor, no del enlace.
     */
    fun clasificar(codigo: Int, cuerpo: String?): Resultado = when {
        codigo == 200 && cuerpo != null && cuerpo.contains("\"comandas\"") -> Resultado.OK
        // Contesta, pero no es una cocina: el enlace apunta a otra cosa.
        codigo == 200 -> Resultado.INVALIDO
        codigo == 402 -> Resultado.PAUSADO
        codigo in setOf(400, 401, 403, 404, 410) -> Resultado.INVALIDO
        else -> Resultado.SERVIDOR
    }

    /** Prueba el enlace contra el servidor. Bloquea: hilo aparte. */
    fun comprobar(url: String): Resultado {
        val c = try {
            URL(datos(url)).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return Resultado.INVALIDO
        }
        return try {
            c.requestMethod = "GET"
            c.setRequestProperty("accept", "application/json")
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            val codigo = c.responseCode
            val cuerpo = if (codigo == 200) c.inputStream.bufferedReader().use { it.readText() } else null
            clasificar(codigo, cuerpo)
        } catch (e: IOException) {
            Resultado.NO_LLEGA
        } finally {
            c.disconnect()
        }
    }
}
