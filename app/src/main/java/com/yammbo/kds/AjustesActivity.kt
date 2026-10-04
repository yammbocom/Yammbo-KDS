package com.yammbo.kds

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Se toca una vez, al colgar la tablet. Todo lo que hay aqui es de ESTA
 * pantalla: el enlace del local, como se llega a su impresora y como avisa.
 *
 * Los cambios se quedan en un borrador hasta "Guardar" (o hasta una prueba,
 * que guarda para probar lo que se ve): salir con atras no toca nada.
 */
class AjustesActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var p: Ui.Paleta
    private var impresoras: List<Pair<String, String>> = emptyList()
    private val claves = listOf("bt", "red", "usb")

    // El borrador. Sobrevive al giro de pantalla: antes se perdia lo pegado.
    private var url = ""
    private var conexion = "bt"
    private var impresora = ""
    private var ip = ""
    private var puerto = "9100"
    private var ancho = 32
    private var sonido = true
    private var encima = true

    // Se refrescan al volver de los ajustes de Android sin rehacer la pantalla.
    private var chipConexion: TextView? = null
    private var chipEncima: TextView? = null
    private var chipNotis: TextView? = null
    private var filaPermisoEncima: Ui.Fila? = null
    private var pidiendoBt = false

    private val manejador = Handler(Looper.getMainLooper())
    private val latido = object : Runnable {
        override fun run() {
            refrescarEstado()
            manejador.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        prefs = Prefs(this)
        if (saved != null) {
            url = saved.getString("url", "")
            conexion = saved.getString("conexion", "bt")
            impresora = saved.getString("impresora", "")
            ip = saved.getString("ip", "")
            puerto = saved.getString("puerto", "9100")
            ancho = saved.getInt("ancho", 32)
            sonido = saved.getBoolean("sonido", true)
            encima = saved.getBoolean("encima", true)
        } else {
            url = prefs.url
            conexion = prefs.conexion
            impresora = prefs.impresora
            ip = prefs.ip
            puerto = prefs.puerto.toString()
            ancho = prefs.ancho
            sonido = prefs.sonido
            encima = prefs.encima
        }
        p = Ui.paleta(this)
        Ui.barras(this, p)
        // Se pide AQUI: sin el permiso bondedDevices lanza y la lista sale
        // vacia como si no hubiera ninguna impresora emparejada. Solo al
        // entrar, no en cada giro: una peticion a la vez.
        if (saved == null && Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) pedirBt()
        pintar()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("url", url)
        out.putString("conexion", conexion)
        out.putString("impresora", impresora)
        out.putString("ip", ip)
        out.putString("puerto", puerto)
        out.putInt("ancho", ancho)
        out.putBoolean("sonido", sonido)
        out.putBoolean("encima", encima)
    }

    private fun pedirBt() {
        if (pidiendoBt || Build.VERSION.SDK_INT < 31) return
        pidiendoBt = true
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 9
        )
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == 9) {
            pidiendoBt = false
            pintar()   // ya se puede leer la lista de emparejadas
        }
    }

    override fun onResume() {
        super.onResume()
        manejador.removeCallbacks(latido)
        manejador.post(latido)
    }

    override fun onPause() {
        super.onPause()
        manejador.removeCallbacks(latido)
    }

    private fun dp(v: Float): Int = Ui.dp(this, v)

    /** Lo que cambia sin que el encargado toque nada: permisos y conexion. */
    private fun refrescarEstado() {
        chipConexion?.let { c ->
            c.visibility = if (prefs.configurada) View.VISIBLE else View.GONE
            val vivo = Vigia.hayDatosRecientes(30_000)
            Ui.pintarChip(c, p, getString(if (vivo) R.string.aj_en_linea else R.string.aj_sin_datos), vivo)
        }
        val okEncima = Aviso.puedeDibujarEncima(this)
        chipEncima?.let {
            Ui.pintarChip(it, p, getString(if (okEncima) R.string.aj_concedido else R.string.aj_falta), okEncima)
        }
        filaPermisoEncima?.subtitulo(
            getString(if (okEncima) R.string.aj_permiso_encima_ok else R.string.aj_permiso_encima_no)
        )
        val okNotis = NotificationManagerCompat.from(this).areNotificationsEnabled()
        chipNotis?.let {
            Ui.pintarChip(it, p, getString(if (okNotis) R.string.aj_activadas else R.string.aj_desactivadas), okNotis)
        }
    }

    private fun pintar() {
        impresoras = Impresora.emparejadas(this)
        // Como el desplegable de antes: sin una guardada que siga emparejada,
        // queda elegida la primera de la lista.
        if (impresoras.isNotEmpty() && impresoras.none { it.second == impresora }) {
            impresora = impresoras[0].second
        }

        val col = Ui.Columna(this, dp(640f)).apply {
            setPadding(dp(16f), 0, dp(16f), dp(24f))
        }

        // ---- Conexion
        col.addView(Ui.cabecera(this, p, getString(R.string.aj_seccion_conexion)))
        val conexionT = Ui.tarjeta(this, p)
        val chip = Ui.chip(this, p, "", false)
        chipConexion = chip
        lateinit var filaEnlace: Ui.Fila
        filaEnlace = Ui.fila(this, p, getString(R.string.aj_enlace), resumenEnlace(url), chip) {
            editarEnlace { filaEnlace.subtitulo(resumenEnlace(url)) }
        }
        conexionT.addView(filaEnlace.vista)
        if (prefs.configurada) {
            conexionT.addView(Ui.separador(this, p))
            conexionT.addView(
                Ui.fila(
                    this, p, getString(R.string.aj_desvincular), getString(R.string.aj_desvincular_sub),
                    Ui.chevron(this, p),
                ) { confirmarDesvincular() }.vista
            )
        }
        col.addView(conexionT)

        // ---- Avisos
        col.addView(Ui.cabecera(this, p, getString(R.string.aj_avisos)))
        val avisosT = Ui.tarjeta(this, p)
        val swSonido = Ui.interruptor(this, p, sonido) { sonido = it }
        avisosT.addView(filaInterruptor(getString(R.string.aj_sonido), null, swSonido))
        avisosT.addView(Ui.separador(this, p))
        val swEncima = Ui.interruptor(this, p, encima) { encima = it }
        avisosT.addView(
            filaInterruptor(getString(R.string.aj_encima), getString(R.string.aj_encima_sub), swEncima)
        )
        avisosT.addView(Ui.separador(this, p))
        // El permiso de dibujar encima no se concede desde un dialogo: hay que
        // ir a los ajustes de Android. Si falta, el cartel no sale, y aqui se
        // dice en claro.
        val chipE = Ui.chip(this, p, "", false)
        chipEncima = chipE
        val filaE = Ui.fila(this, p, getString(R.string.aj_permiso_encima), null, chipE) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
                )
            }
        }
        filaPermisoEncima = filaE
        avisosT.addView(filaE.vista)
        avisosT.addView(Ui.separador(this, p))
        val chipN = Ui.chip(this, p, "", false)
        chipNotis = chipN
        avisosT.addView(
            Ui.fila(
                this, p, getString(R.string.aj_notificaciones), getString(R.string.aj_notificaciones_sub), chipN,
            ) { abrirAjustesNotificaciones() }.vista
        )
        avisosT.addView(
            enTarjeta(
                Ui.boton(this, p, getString(R.string.aj_probar_aviso), Ui.Tipo.SECUNDARIO) {
                    guardar()
                    val cartel = Aviso.probar(this)
                    Toast.makeText(
                        this,
                        getString(if (cartel) R.string.aj_aviso_ok else R.string.aj_aviso_sin_cartel),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            )
        )
        col.addView(avisosT)

        // ---- Impresora
        col.addView(Ui.cabecera(this, p, getString(R.string.aj_seccion_impresora)))
        val impT = Ui.tarjeta(this, p)
        val tipos = listOf(getString(R.string.aj_bt), getString(R.string.aj_red), getString(R.string.aj_usb))

        val vBt = Ui.valor(this, p, nombreImpresora())
        val bloqueBt = bloque(
            Ui.fila(this, p, getString(R.string.aj_bt_lista), null, vBt.vista) {
                elegirImpresora { vBt.texto.text = nombreImpresora() }
            }.vista
        )
        val vIp = Ui.valor(this, p, ip.ifBlank { getString(R.string.aj_sin_valor) })
        val vPuerto = Ui.valor(this, p, puerto)
        val bloqueRed = bloque(
            Ui.fila(this, p, getString(R.string.aj_ip), null, vIp.vista) {
                editarTexto(
                    getString(R.string.aj_ip), ip, "192.168.1.50", InputType.TYPE_CLASS_TEXT,
                ) {
                    ip = it.trim()
                    vIp.texto.text = ip.ifBlank { getString(R.string.aj_sin_valor) }
                }
            }.vista,
            Ui.fila(this, p, getString(R.string.aj_puerto), null, vPuerto.vista) {
                editarTexto(
                    getString(R.string.aj_puerto), puerto, "9100", InputType.TYPE_CLASS_NUMBER,
                ) {
                    puerto = it.trim()
                    vPuerto.texto.text = puerto.ifBlank { "9100" }
                }
            }.vista,
        )
        val bloqueUsb = bloque(
            Ui.fila(
                this, p, getString(R.string.aj_usb_cable), getString(R.string.aj_usb_permiso),
                Ui.chevron(this, p),
            ) {
                if (Impresora.usbImpresora(this) == null) {
                    Toast.makeText(this, getString(R.string.aj_usb_ninguna), Toast.LENGTH_LONG).show()
                } else Impresora.usbPedirPermiso(this)
            }.vista
        )
        fun mostrarSegunTipo() {
            bloqueBt.visibility = if (conexion == "bt") View.VISIBLE else View.GONE
            bloqueRed.visibility = if (conexion == "red") View.VISIBLE else View.GONE
            bloqueUsb.visibility = if (conexion == "usb") View.VISIBLE else View.GONE
        }
        val vTipo = Ui.valor(this, p, tipos[claves.indexOf(conexion).coerceAtLeast(0)])
        impT.addView(
            Ui.fila(this, p, getString(R.string.aj_conexion), null, vTipo.vista) {
                elegir(getString(R.string.aj_conexion), tipos, claves.indexOf(conexion)) { i ->
                    conexion = claves[i]
                    vTipo.texto.text = tipos[i]
                    mostrarSegunTipo()
                }
            }.vista
        )
        impT.addView(bloqueBt)
        impT.addView(bloqueRed)
        impT.addView(bloqueUsb)
        impT.addView(Ui.separador(this, p))
        val anchos = listOf(getString(R.string.aj_ancho_58), getString(R.string.aj_ancho_80))
        val vAncho = Ui.valor(this, p, anchos[if (ancho >= 48) 1 else 0])
        impT.addView(
            Ui.fila(this, p, getString(R.string.aj_ancho), null, vAncho.vista) {
                elegir(getString(R.string.aj_ancho), anchos, if (ancho >= 48) 1 else 0) { i ->
                    ancho = if (i == 1) 48 else 32
                    vAncho.texto.text = anchos[i]
                }
            }.vista
        )
        impT.addView(
            enTarjeta(
                Ui.boton(this, p, getString(R.string.aj_probar_impresion), Ui.Tipo.SECUNDARIO) {
                    guardar()
                    Impresora.encolar(this, ejemploJson()) { msg ->
                        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
                    }
                    Toast.makeText(this, R.string.aj_enviando, Toast.LENGTH_SHORT).show()
                }
            )
        )
        mostrarSegunTipo()
        col.addView(impT)

        // ---- Aplicacion
        col.addView(Ui.cabecera(this, p, getString(R.string.aj_seccion_app)))
        val appT = Ui.tarjeta(this, p)
        if (BuildConfig.SELF_UPDATE) {
            appT.addView(
                Ui.fila(this, p, getString(R.string.act_buscar), null, Ui.chevron(this, p)) {
                    buscarActualizacion()
                }.vista
            )
            appT.addView(Ui.separador(this, p))
        }
        appT.addView(
            Ui.fila(
                this, p, getString(R.string.aj_info_app), getString(R.string.aj_info_app_sub),
                Ui.chevron(this, p),
            ) {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName))
                    )
                }
            }.vista
        )
        col.addView(appT)
        col.addView(
            Ui.texto(
                this, getString(R.string.aj_version, Actualizador.nombreInstalado(this)),
                Ui.Estilo.CAPTION, p.secundario,
            ).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24f) },
        )

        val desplazable = ScrollView(this).apply {
            isFillViewport = true
            addView(
                FrameLayout(this@AjustesActivity).apply {
                    addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
                },
                ViewGroup.LayoutParams(-1, -2),
            )
        }

        // Guardar va fijo abajo: es lo unico que hay que hacer al terminar.
        val guardarB = Ui.boton(this, p, getString(R.string.aj_guardar)) { guardarYSalir() }
        val abajo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(this@AjustesActivity).apply { setBackgroundColor(p.linea) }, LinearLayout.LayoutParams(-1, maxOf(1, dp(1f))))
            addView(
                FrameLayout(this@AjustesActivity).apply {
                    setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
                    addView(
                        Ui.Columna(this@AjustesActivity, dp(640f)).apply { addView(guardarB, LinearLayout.LayoutParams(-1, -2)) },
                        FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL),
                    )
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.fondo)
            addView(Ui.barraSuperior(this@AjustesActivity, p, getString(R.string.ajustes)), LinearLayout.LayoutParams(-1, -2))
            addView(desplazable, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(abajo, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(raiz)
        Ui.rellenarBarras(raiz)
        refrescarEstado()
    }

    /** Toda la fila enciende o apaga: el interruptor solo es una diana pequeña. */
    private fun filaInterruptor(titulo: String, sub: String?, sw: SwitchCompat): View =
        Ui.fila(this, p, titulo, sub, sw) { sw.toggle() }.vista.also {
            sw.contentDescription = titulo
        }

    /** Filas que se esconden juntas, con su separador. */
    private fun bloque(vararg filas: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        for (f in filas) {
            addView(Ui.separador(this@AjustesActivity, p))
            addView(f)
        }
    }

    /** Un boton dentro de la tarjeta, con su margen. */
    private fun enTarjeta(b: View): View = FrameLayout(this).apply {
        setPadding(dp(16f), dp(4f), dp(16f), dp(16f))
        addView(b, FrameLayout.LayoutParams(-1, -2))
    }

    private fun resumenEnlace(u: String): String {
        if (u.isBlank()) return getString(R.string.aj_sin_enlace)
        // El token es la llave: no se enseña entero en una pantalla que mira
        // toda la cocina, solo lo justo para reconocer cual es.
        val host = runCatching { Uri.parse(u).host }.getOrNull().orEmpty()
        val token = u.trimEnd('/').substringAfterLast('/')
        return if (host.isNotEmpty() && token.length > 4) host + "/kds/…" + token.takeLast(4) else u
    }

    private fun nombreImpresora(): String {
        // Un fallo de permiso y "no hay ninguna emparejada" son problemas
        // distintos y se dicen distinto.
        if (impresoras.isEmpty()) {
            return getString(
                if (!Impresora.puedeVerDispositivos(this)) R.string.aj_bt_sin_permiso else R.string.aj_bt_ninguna
            )
        }
        return impresoras.firstOrNull { it.second == impresora }?.first ?: impresoras[0].first
    }

    private fun elegirImpresora(alElegir: () -> Unit) {
        when {
            impresoras.isNotEmpty() -> {
                val i = impresoras.indexOfFirst { it.second == impresora }.coerceAtLeast(0)
                elegir(getString(R.string.aj_bt_lista), impresoras.map { it.first }, i) { n ->
                    impresora = impresoras[n].second
                    alElegir()
                }
            }
            Build.VERSION.SDK_INT >= 31 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED -> pedirBt()
            !Impresora.puedeVerDispositivos(this) ->
                Toast.makeText(this, R.string.err_sin_bt, Toast.LENGTH_LONG).show()
            // Hay Bluetooth pero ninguna emparejada: se empareja en Android.
            else -> runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
    }

    private fun elegir(titulo: String, opciones: List<String>, actual: Int, alElegir: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setSingleChoiceItems(opciones.toTypedArray(), actual) { d, i ->
                alElegir(i)
                d.dismiss()
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    /** Dialogo con un campo. [validar] devuelve el error, o null si vale. */
    private fun editarTexto(
        titulo: String,
        actual: String,
        pista: String,
        tipo: Int,
        ayuda: String? = null,
        validar: ((String) -> String?)? = null,
        alGuardar: (String) -> Unit,
    ) {
        val campo = Ui.campo(this, p, pista, tipo).apply {
            setText(actual)
            setSelection(actual.length)
            contentDescription = titulo
        }
        val error = Ui.texto(this, "", Ui.Estilo.SECUNDARIO_MEDIO, p.texto).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(8f), dp(24f), 0)
            addView(campo, LinearLayout.LayoutParams(-1, -2))
            if (ayuda != null) {
                addView(
                    Ui.texto(this@AjustesActivity, ayuda, Ui.Estilo.CAPTION, p.secundario),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) },
                )
            }
            addView(error, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        }
        val d = AlertDialog.Builder(this)
            .setTitle(titulo)
            .setView(caja)
            .setPositiveButton(R.string.listo, null)
            .setNegativeButton(R.string.cancelar, null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = campo.text?.toString().orEmpty()
                val e = validar?.invoke(v)
                if (e != null) {
                    error.text = e
                    error.visibility = View.VISIBLE
                } else {
                    alGuardar(v)
                    d.dismiss()
                }
            }
        }
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        campo.requestFocus()
    }

    private fun editarEnlace(alCambiar: () -> Unit) {
        editarTexto(
            getString(R.string.aj_enlace), url, "https://pos.yammbo.com/kds/…",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            ayuda = getString(R.string.bv_enlace_ayuda),
            validar = { v ->
                if (Enlace.valido(Enlace.normalizar(v))) null else getString(R.string.bv_error_formato)
            },
        ) {
            url = Enlace.normalizar(it)
            alCambiar()
        }
    }

    private fun confirmarDesvincular() {
        AlertDialog.Builder(this)
            .setTitle(R.string.aj_desvincular_q)
            .setMessage(R.string.aj_desvincular_sub)
            .setPositiveButton(R.string.aj_desvincular_ok) { _, _ ->
                prefs.url = ""
                Vigia.reiniciar()
                // Sin enlace el servicio no tiene a quien vigilar: que no siga
                // diciendo en la barra que esta atento a los pedidos.
                stopService(Intent(this, ServicioKds::class.java))
                // Ni un pedido de la cocina que se acaba de desvincular en la barra.
                NotificationManagerCompat.from(this).cancel(Aviso.ID_PEDIDO)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    private fun abrirAjustesNotificaciones() {
        val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName))
        runCatching { startActivity(i) }
    }

    private fun guardar() {
        prefs.url = url
        prefs.conexion = conexion
        prefs.ip = ip
        prefs.puerto = puerto.toIntOrNull() ?: 9100
        prefs.ancho = ancho
        prefs.sonido = sonido
        prefs.encima = encima
        // Solo si hay lista de verdad: con la lista vacia se estaba pisando la
        // MAC ya guardada.
        if (impresoras.isNotEmpty() && impresoras.any { it.second == impresora }) {
            prefs.impresora = impresora
        }
    }

    private fun guardarYSalir() {
        guardar()
        if (!prefs.configurada) {
            Toast.makeText(this, getString(R.string.aj_enlace_malo), Toast.LENGTH_LONG).show()
            return
        }
        Vigia.reiniciar()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun buscarActualizacion() {
        Toast.makeText(this, R.string.act_comprobando, Toast.LENGTH_SHORT).show()
        Thread {
            val v = Actualizador.ultima()
            runOnUiThread {
                when {
                    v == null -> Toast.makeText(this, R.string.act_sin_comprobar, Toast.LENGTH_LONG).show()
                    !Actualizador.hayNueva(this, v) -> Toast.makeText(
                        this,
                        getString(R.string.act_al_dia, Actualizador.nombreInstalado(this)),
                        Toast.LENGTH_LONG,
                    ).show()
                    else -> {
                        // La descarga y el dialogo viven en MainActivity;
                        // aqui solo se abre para que los enseñe.
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra("buscar_actualizacion", true)
                        )
                    }
                }
            }
        }.start()
    }

    /** Comanda de mentira para ver como sale el papel antes del servicio. */
    private fun ejemploJson(): String =
        """{"v":1,"local":"Yambo Restuarant","comanda":"0082","origen":"web",
            "hora":null,"entrega":"delivery","cliente":"Frank Test",
            "nota":"Tocar el timbre, apartamento 3",
            "lineas":[
              {"n":1,"nombre":"Papa rellena","extras":[],"nota":null},
              {"n":2,"nombre":"Tacos de pollo","extras":["Gallo pinto","Arroz"],"nota":"sin cebolla"},
              {"n":1,"nombre":"Bistec encebollado","extras":[],"nota":null}]}"""
}
