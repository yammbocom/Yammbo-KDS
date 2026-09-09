package com.yammbo.kds

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject

/**
 * Decide QUE es una comanda nueva y avisa una sola vez por comanda.
 *
 * Vive fuera del servicio y del WebView a proposito: los dos miran el mismo
 * `/data` (nunca a la vez) y comparten esta memoria, para que al pasar de
 * primer plano a segundo no vuelva a sonar lo ya visto — ni se pierda lo que
 * entro justo en el cambio, que es el momento para el que existe la app.
 */
object Vigia {
    private val vistos = HashSet<String>()
    /** Vueltas seguidas que un id lleva sin aparecer. Ver [procesar]. */
    private val ausencias = HashMap<String, Int>()
    private var primera = true

    /** True mientras la pantalla del KDS esta delante y poleteando ella. */
    @Volatile var enPrimerPlano: Boolean = false

    /** Cuando llego el ultimo `/data` que se pudo LEER. Es el latido que usa
     *  [ServicioKds] para saber si la pagina de verdad esta trayendo datos. */
    @Volatile var ultimoDato: Long = 0L

    fun reiniciar() {
        synchronized(vistos) { vistos.clear(); ausencias.clear(); primera = true }
    }

    fun hayDatosRecientes(msMax: Long): Boolean {
        val t = ultimoDato
        return t != 0L && SystemClock.elapsedRealtime() - t < msMax
    }

    /** @param json el cuerpo de `/kds/<token>/data`. */
    fun procesar(ctx: Context, json: String) {
        // 🚨 Una respuesta que no se entiende NO es "no hay comandas". Un token
        // revocado, un 5xx con cuerpo o un corte a medias devolverian aqui una
        // lista vacia y borrarian la memoria; ver el bloque de purga.
        val arr = runCatching { JSONObject(json).optJSONArray("comandas") }.getOrNull() ?: return
        ultimoDato = SystemClock.elapsedRealtime()

        val nuevas = ArrayList<JSONObject>()
        val arrancando: Boolean
        // Todo el ajuste de memoria en UN solo bloque: el hilo del puente JS y
        // la corrutina del servicio se solapan durante las transiciones, y con
        // dos bloques sueltos una purga vieja borraba un id recien anunciado.
        synchronized(vistos) {
            val vivos = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.texto("id")
                if (id.isBlank()) continue
                vivos.add(id)
                // Solo las que estan SIN EMPEZAR: una que ya se cocina no es
                // noticia, y al moverla no debe volver a sonar.
                if (o.texto("status") != "pushed") continue
                if (vistos.add(id)) nuevas.add(o)
            }
            // Purga con tolerancia: un id no se olvida hasta faltar 3 vueltas
            // seguidas. Asi una respuesta parcial o un hueco de Loyverse no
            // vacia la memoria y re-anuncia el turno entero.
            val it = vistos.iterator()
            while (it.hasNext()) {
                val id = it.next()
                if (vivos.contains(id)) { ausencias.remove(id); continue }
                val n = (ausencias[id] ?: 0) + 1
                if (n >= 3) { it.remove(); ausencias.remove(id) } else ausencias[id] = n
            }
            arrancando = primera
            primera = false
        }

        // Al abrir, todas serian "nuevas" y la cocina aprenderia a ignorar el
        // aviso. Solo se calla la PRIMERA lectura de la vida del proceso.
        if (arrancando) return

        // 🚨 Todos los avisos son SOLO de pedidos EN LINEA. Una venta del TPV la
        // acaba de teclear el propio cajero: sonarle y notificarle lo que acaba
        // de cobrar es ruido, y el ruido inutil enseña a ignorar los avisos de
        // verdad. Las del TPV se siguen viendo en la pantalla del KDS.
        val online = nuevas.filter { it.texto("origen") == "web" }
        if (online.isEmpty()) return

        val app = ctx.applicationContext
        val prefs = Prefs(app)
        val o = online.first()
        val titulo = if (online.size == 1) "Pedido " + o.texto("clave")
        else online.size.toString() + " pedidos en línea"
        val cuerpo = resumen(o)

        Aviso.notificar(app, "Pedido en línea", titulo + " · " + cuerpo)
        if (prefs.sonido) Aviso.sonar(app)
        Aviso.despertar(app)
        // Con el KDS delante no hace falta cartel: taparia su propia cabecera,
        // justo donde esta el check "Imprimir sola".
        if (prefs.encima && !enPrimerPlano) {
            // Si falta el permiso de dibujar encima, el cartel no sale. Antes
            // se callaba y parecia que el aviso "no funcionaba"; ahora queda en
            // el log y Ajustes lo enseña en claro.
            if (!Aviso.encima(app, titulo, cuerpo, detalle(app, o))) {
                android.util.Log.w("YammboKDS", "sin permiso para dibujar encima: no hay cartel")
            }
        }
    }

    /**
     * Los platos del pedido para el cartel.
     *
     * Arma un bloque por plato — el plato, sus extras y su nota — y deja el
     * recorte a [recortar], que es donde se puede probar.
     */
    private fun detalle(ctx: Context, o: JSONObject, maxPlatos: Int = 5): List<String> {
        val ls = o.optJSONArray("lineas") ?: return emptyList()
        val bloques = ArrayList<List<String>>(ls.length())
        for (i in 0 until ls.length()) {
            val l = ls.optJSONObject(i) ?: continue
            val bloque = ArrayList<String>(3)
            bloque.add(l.optInt("n", 1).toString() + "  " + l.texto("nombre"))
            val ex = l.optJSONArray("extras")
            if (ex != null && ex.length() > 0) {
                val e = ArrayList<String>()
                for (j in 0 until ex.length()) {
                    ex.texto(j).takeIf { it.isNotBlank() }?.let(e::add)
                }
                // La sangria inicial es la señal de "esto va debajo del plato":
                // el cartel la usa para pintarlo mas pequeño y en gris.
                if (e.isNotEmpty()) bloque.add("   + " + e.joinToString(", "))
            }
            l.texto("nota").takeIf { it.isNotBlank() }?.let { bloque.add("   " + "\u201C" + it + "\u201D") }
            bloques.add(bloque)
        }
        // El total va aparte de los bloques: lo que no sea un objeto se salto
        // arriba, pero el cuerpo del cartel («4 articulos») si lo cuenta.
        val (lineas, restan) =
            recortar(bloques, maxPlatos, Aviso.lineasQueCaben(ctx), ls.length())
        if (restan <= 0) return lineas
        return lineas + ("   " + ctx.getString(R.string.cartel_mas, restan))
    }

    /**
     * El recorte del cartel, en un solo sitio y aparte del JSON para poder
     * probarlo.
     *
     * Devuelve los renglones que caben y CUANTOS PLATOS quedan fuera. Se corta
     * por renglones — no por platos — porque es lo que de verdad limita la
     * pantalla: los extras y la nota ocupan renglon igual que el plato. Un
     * plato entra entero o no entra, para que nadie lea media comanda.
     *
     * 🚨 La excepcion es el PRIMERO: si ni el primero cabe entero — pantalla
     * baja, en horizontal, con la letra del sistema en grande — entra al menos
     * su nombre. Un cartel que solo dice «y 4 mas» no le sirve de nada a quien
     * esta en la caja, que es para quien existe.
     *
     * El contador cuenta PLATOS, no renglones, y solo cuenta los que no se
     * llegaron a poner: si el corte se hiciera tambien mas adelante, mentiria.
     *
     * [total] son los platos que traia la comanda, que pueden ser MAS que los
     * bloques: quien llama salta lo que no sea un objeto. Tienen que entrar en
     * el contador igual, o el cartel dice «4 articulos» arriba y ensena tres
     * sin avisar de que falta uno.
     */
    internal fun recortar(
        bloques: List<List<String>>,
        maxPlatos: Int,
        maxLineas: Int,
        total: Int = bloques.size,
    ): Pair<List<String>, Int> {
        val out = ArrayList<String>()
        var i = 0
        while (i < bloques.size) {
            val bloque = bloques[i]
            // Si este no es el ultimo, hay que dejar sitio para el contador.
            val tope = if (i == bloques.size - 1) maxLineas else maxLineas - 1
            if (i >= maxPlatos || out.size + bloque.size > tope) {
                if (out.isEmpty() && i < maxPlatos && bloque.isNotEmpty()) {
                    // Solo el nombre, sin extras ni nota. Cuenta como puesto:
                    // el plato ya se ve, y el contador no debe repetirlo.
                    out.add(bloque[0])
                    i++
                }
                break
            }
            out.addAll(bloque)
            i++
        }
        return out to (total - i)
    }

    private fun resumen(o: JSONObject): String {
        val ls = o.optJSONArray("lineas")
        var n = 0
        for (i in 0 until (ls?.length() ?: 0)) n += (ls!!.optJSONObject(i)?.optInt("n", 1) ?: 1)
        val partes = ArrayList<String>()
        partes.add(n.toString() + if (n == 1) " artículo" else " artículos")
        when (o.texto("fulfillment")) {
            "delivery" -> partes.add("Envío")
            "pickup" -> partes.add("Recoge")
        }
        o.texto("customer_name").takeIf { it.isNotBlank() }?.let(partes::add)
        return partes.joinToString(" · ")
    }
}
