package com.yammbo.kds

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Convierte un [Ticket] en bytes ESC/POS con el MISMO orden visual que el
 * ticket de la pantalla web: local pequeno, numero enorme, metadatos, platos
 * grandes, nota enmarcada y pie.
 *
 * Una termica no tiene grises ni tipografias: la jerarquia se hace con tamano
 * de caracter, negrita y reglas. Por eso el numero va a doble alto y ancho, y
 * los platos a doble alto: son lo que se lee de lejos con las manos ocupadas.
 */
object EscPos {

    private val INIT = byteArrayOf(0x1B, 0x40)                 // ESC @
    private fun align(n: Int) = byteArrayOf(0x1B, 0x61, n.toByte())
    private fun bold(on: Boolean) = byteArrayOf(0x1B, 0x45, if (on) 1 else 0)
    /** GS ! n — n = (ancho-1)<<4 | (alto-1). 0x00 normal, 0x11 doble ambos. */
    private fun tam(ancho: Int, alto: Int) =
        byteArrayOf(0x1D, 0x21, (((ancho - 1) shl 4) or (alto - 1)).toByte())
    private fun feed(n: Int) = byteArrayOf(0x1B, 0x64, n.toByte())
    /** GS V 66 n — corte parcial dejando avance; el mas compatible. */
    private val CORTE = byteArrayOf(0x1D, 0x56, 66, 0x00)

    /**
     * CP850 lleva acentos y la enye. Si la JVM del dispositivo no la trae, se
     * cae a ASCII plegado: mejor "Bistec encebollado" sin tilde que un chorro
     * de simbolos raros en mitad de la comanda.
     */
    /** Espacio, salto de linea, retorno y tabulador, por codigo: una nota
     *  del cliente puede traer saltos y hay que tratarlos como separadores. */
    private val BLANCOS = charArrayOf(Char(32), Char(10), Char(13), Char(9))

    private val CP: Charset? = runCatching { Charset.forName("IBM850") }.getOrNull()

    private fun plegar(s: String): String {
        val de = "áéíóúÁÉÍÓÚñÑüÜ¿¡"
        val a = "aeiouAEIOUnNuU??"
        val sb = StringBuilder(s.length)
        for (c in s) { val i = de.indexOf(c); sb.append(if (i >= 0) a[i] else c) }
        return sb.toString()
    }

    private fun bytes(s: String): ByteArray =
        if (CP != null) s.toByteArray(CP) else plegar(s).toByteArray(Charsets.US_ASCII)

    /**
     * Parte [texto] al ancho del papel: [prefijo] abre la primera linea y las
     * siguientes entran con [sangria] espacios, de modo que los extras y las
     * notas quedan colgando bajo su plato y no pegados al margen.
     *
     * Una palabra mas larga que el papel se parte aqui: si no, la corta la
     * termica por donde le toca y descuadra la linea.
     */
    private fun envolver(prefijo: String, texto: String, ancho: Int, sangria: Int): List<String> {
        val pad = " ".repeat(sangria.coerceIn(0, maxOf(0, ancho - 1)))
        val out = ArrayList<String>()
        var linea = StringBuilder(if (prefijo.length < ancho) prefijo else prefijo.take(ancho))
        var vacia = true

        fun volcar() { out.add(linea.toString().trimEnd()); linea = StringBuilder(pad); vacia = true }

        for (palabra in texto.split(*BLANCOS).filter { it.isNotEmpty() }) {
            var w = palabra
            var guarda = 0
            while (w.isNotEmpty() && guarda++ < 200) {
                val hueco = ancho - linea.length - (if (vacia) 0 else 1)
                when {
                    hueco <= 0 -> volcar()
                    w.length <= hueco -> {
                        if (!vacia) linea.append(' ')
                        linea.append(w); vacia = false; w = ""
                    }
                    // No cabe entera y tampoco cabria en una linea limpia:
                    // partirla es la unica salida.
                    w.length > ancho - pad.length -> {
                        if (!vacia) linea.append(' ')
                        linea.append(w.substring(0, hueco)); w = w.substring(hueco)
                        vacia = false; volcar()
                    }
                    else -> volcar()
                }
            }
        }
        if (linea.toString().isNotBlank()) out.add(linea.toString().trimEnd())
        return if (out.isEmpty()) listOf(prefijo.trimEnd()) else out
    }

    /** Etiqueta a la izquierda y valor a la derecha; si no cabe, el valor baja
     *  a la linea siguiente en vez de cortarse a la mitad. */
    private fun etiquetaValor(etq: String, valor: String, ancho: Int): List<String> {
        val hueco = ancho - etq.length - valor.length
        return if (hueco >= 1) listOf(etq + " ".repeat(hueco) + valor)
        else envolver(etq + " ", valor, ancho, etq.length + 1)
    }

    /** La hora del pedido viene en UTC sin la Z (formato de D1). */
    private fun horaLocal(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return runCatching {
            val limpio = iso.replace(" ", "T").removeSuffix("Z")
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            SimpleDateFormat("hh:mm a", Locale.US).format(f.parse(limpio)!!)
        }.getOrDefault("")
    }

    /** @param ancho columnas del papel: 32 en 58 mm, 48 en 80 mm. */
    fun render(t: Ticket, ancho: Int = 32, tk: TextosTicket = TextosTicket.ES): ByteArray {
        val w = ancho.coerceIn(24, 64)
        val o = ByteArrayOutputStream()
        fun raw(b: ByteArray) = o.write(b)
        fun ln(s: String = "") { o.write(bytes(s)); o.write('\n'.code) }
        fun regla(c: Char) = ln(c.toString().repeat(w))

        raw(INIT)
        if (CP != null) raw(byteArrayOf(0x1B, 0x74, 0x02))   // ESC t 2 -> CP850

        // Cabecera: el local discreto, el numero dominante.
        raw(align(1))
        if (t.local.isNotBlank()) {
            raw(bold(true)); envolver("", t.local.uppercase(), w, 0).forEach { ln(it) }; raw(bold(false))
        }
        regla('=')
        raw(tam(2, 2)); raw(bold(true))
        ln(t.comanda)
        raw(bold(false)); raw(tam(1, 1))
        regla('=')

        // Metadatos: de donde viene y a que hora entro.
        raw(align(0))
        val izq = (if (t.origen == "tpv") tk.tpv else tk.web) +
            when (t.entrega) {
                "delivery" -> " " + plegar(tk.envio)
                "pickup" -> " " + plegar(tk.recoge)
                else -> ""
            }
        raw(bold(true)); etiquetaValor(izq, horaLocal(t.hora), w).forEach { ln(it) }; raw(bold(false))

        t.cliente?.let { regla('-'); etiquetaValor(tk.cliente, it, w).forEach { s -> ln(s) } }

        // Los platos: lo mas grande del papel despues del numero.
        regla('=')
        for (l in t.lineas) {
            raw(tam(1, 2)); raw(bold(true))
            // tam(1,2) es doble ALTO con ancho normal: caben las columnas
            // enteras. Dividir por dos partia "TACOS DE POLLO" en dos lineas.
            envolver(l.n.toString().padEnd(3), l.nombre.uppercase(), w, 3).forEach { ln(it) }
            raw(bold(false)); raw(tam(1, 1))
            if (l.extras.isNotEmpty()) {
                envolver("   + ", l.extras.joinToString(", "), w, 5).forEach { ln(it) }
            }
            l.nota?.let { envolver("   " + Char(34).toString(), it + Char(34).toString(), w, 5).forEach { s -> ln(s) } }
            ln()
        }

        // La nota del pedido: lo unico que no se puede pasar por alto.
        t.nota?.let {
            regla('-')
            raw(bold(true)); ln(tk.nota); raw(bold(false))
            envolver("", it, w, 0).forEach { s -> ln(s) }
        }

        regla('-')
        raw(align(1))
        val arts = t.articulos.toString() + " " + if (t.articulos == 1) tk.articulo else tk.articulos
        ln(arts + "  " + SimpleDateFormat("hh:mm a", Locale.US).format(Date()))

        raw(feed(4))   // cola de papel: sin esto el cortador se come el pie
        raw(CORTE)
        return o.toByteArray()
    }
}
