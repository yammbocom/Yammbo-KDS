package com.yammbo.kds

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Se toca una vez, al colgar la tablet. Todo lo que hay aqui es de ESTA
 * pantalla: el enlace del local, como se llega a su impresora y como avisa.
 */
class AjustesActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var impresoras: List<Pair<String, String>> = emptyList()
    // Se refrescan al volver de los ajustes de Android sin rehacer la pantalla,
    // que borraria el enlace que el encargado acaba de pegar.
    private var estadoEncima: TextView? = null
    private var botonPermiso: Button? = null

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        prefs = Prefs(this)
        // Se pide AQUI y no en MainActivity: en la primera instalacion esta es
        // la primera pantalla, y sin el permiso bondedDevices lanza y la lista
        // sale vacia como si no hubiera ninguna impresora emparejada.
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 9
        )
        pintar()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == 9) pintar()   // ya se puede leer la lista de emparejadas
    }

    override fun onResume() {
        super.onResume()
        refrescarPermisoEncima()
    }

    private fun refrescarPermisoEncima() {
        val ok = Aviso.puedeDibujarEncima(this)
        estadoEncima?.text = if (ok) "Permiso para avisar encima: concedido"
            else "Permiso para avisar encima: FALTA. Sin el, el aviso no salta sobre Loyverse."
        estadoEncima?.setTextColor(if (ok) Color.parseColor("#9E9E9E") else Color.WHITE)
        botonPermiso?.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun pintar() {
        impresoras = Impresora.emparejadas(this)
        val puedeVer = Impresora.puedeVerDispositivos(this)

        fun etiqueta(t: String) = TextView(this).apply {
            text = t
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 28, 0, 8)
        }

        val url = EditText(this).apply {
            setText(prefs.url)
            hint = "https://pos.yammbo.com/kds/..."
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }

        val tipos = listOf("Bluetooth", "Red / WiFi (IP)", "USB (cable)")
        val claves = listOf("bt", "red", "usb")
        val spTipo = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@AjustesActivity, android.R.layout.simple_spinner_dropdown_item, tipos)
            setSelection(claves.indexOf(prefs.conexion).coerceAtLeast(0))
        }

        // Un fallo de permiso y "no hay ninguna emparejada" son problemas
        // distintos y se dicen distinto.
        val sinLista = if (!puedeVer) "(sin permiso de Bluetooth)" else "(ninguna emparejada)"
        val spImpresora = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@AjustesActivity, android.R.layout.simple_spinner_dropdown_item,
                if (impresoras.isEmpty()) listOf(sinLista) else impresoras.map { it.first },
            )
            val i = impresoras.indexOfFirst { it.second == prefs.impresora }
            if (i >= 0) setSelection(i)
        }

        val ip = EditText(this).apply {
            setText(prefs.ip)
            hint = "192.168.1.50"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val puerto = EditText(this).apply {
            setText(prefs.puerto.toString())
            hint = "9100"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        val bUsb = Button(this).apply {
            text = "Dar permiso al USB"
            setOnClickListener {
                if (Impresora.usbImpresora(this@AjustesActivity) == null) {
                    Toast.makeText(
                        this@AjustesActivity,
                        "No veo ninguna impresora USB. Conecta el cable OTG.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else Impresora.usbPedirPermiso(this@AjustesActivity)
            }
        }

        // Etiquetas que se enseñan u ocultan con el tipo de conexion.
        val lbBt = etiqueta("Impresora Bluetooth (emparejada en Android)")
        val lbIp = etiqueta("IP de la impresora")
        val lbPuerto = etiqueta("Puerto (9100 en casi todas)")
        val lbUsb = etiqueta("Impresora por cable")

        fun mostrarSegunTipo() {
            val k = claves[spTipo.selectedItemPosition]
            val bt = if (k == "bt") View.VISIBLE else View.GONE
            val red = if (k == "red") View.VISIBLE else View.GONE
            val usb = if (k == "usb") View.VISIBLE else View.GONE
            lbBt.visibility = bt; spImpresora.visibility = bt
            lbIp.visibility = red; ip.visibility = red
            lbPuerto.visibility = red; puerto.visibility = red
            lbUsb.visibility = usb; bUsb.visibility = usb
        }
        spTipo.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) =
                mostrarSegunTipo()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val spAncho = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@AjustesActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("58 mm (32 columnas)", "80 mm (48 columnas)"),
            )
            setSelection(if (prefs.ancho >= 48) 1 else 0)
        }

        val cbSonido = CheckBox(this).apply {
            text = "Sonar al entrar una comanda"; setTextColor(Color.WHITE); isChecked = prefs.sonido
        }
        val cbEncima = CheckBox(this).apply {
            text = "Avisar encima de otras apps"; setTextColor(Color.WHITE); isChecked = prefs.encima
        }

        fun guardar() {
            prefs.url = url.text.toString()
            prefs.conexion = claves[spTipo.selectedItemPosition]
            prefs.ip = ip.text.toString()
            prefs.puerto = puerto.text.toString().toIntOrNull() ?: 9100
            prefs.ancho = if (spAncho.selectedItemPosition == 1) 48 else 32
            prefs.sonido = cbSonido.isChecked
            prefs.encima = cbEncima.isChecked
            // Solo si hay lista de verdad: con la lista vacia (o con un texto
            // de aviso dentro) se estaba pisando la MAC ya guardada.
            if (impresoras.isNotEmpty()) {
                val i = spImpresora.selectedItemPosition
                if (i in impresoras.indices) prefs.impresora = impresoras[i].second
            }
        }

        // El permiso de dibujar encima no se concede desde un dialogo: hay que
        // ir a los ajustes de Android. Si falta, el cartel no sale y hasta ahora
        // no habia forma de enterarse desde la app.
        val txtEncima = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 10, 0, 10)
        }
        val bPermiso = Button(this).apply {
            text = "Dar permiso para avisar encima"
            setOnClickListener {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + packageName),
                        )
                    )
                }
            }
        }
        estadoEncima = txtEncima
        botonPermiso = bPermiso

        val bProbarAviso = Button(this).apply {
            text = "Probar aviso (sonido y cartel)"
            setOnClickListener {
                guardar()
                val cartel = Aviso.probar(this@AjustesActivity)
                Toast.makeText(
                    this@AjustesActivity,
                    if (cartel) "Aviso lanzado: deberias oirlo y ver el cartel"
                    else "Sono y notifico, pero falta el permiso para el cartel",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        val bProbar = Button(this).apply {
            text = "Probar impresion"
            setOnClickListener {
                guardar()
                Impresora.encolar(this@AjustesActivity, ejemploJson()) { msg ->
                    runOnUiThread {
                        Toast.makeText(this@AjustesActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
                Toast.makeText(this@AjustesActivity, "Enviando ticket de prueba...", Toast.LENGTH_SHORT).show()
            }
        }

        val bActualizar = Button(this).apply {
            text = "Buscar actualización"
            setOnClickListener {
                Toast.makeText(this@AjustesActivity, "Comprobando...", Toast.LENGTH_SHORT).show()
                Thread {
                    val v = Actualizador.ultima()
                    runOnUiThread {
                        when {
                            v == null -> Toast.makeText(
                                this@AjustesActivity,
                                "No se pudo comprobar. ¿Hay internet?", Toast.LENGTH_LONG).show()
                            !Actualizador.hayNueva(this@AjustesActivity, v) -> Toast.makeText(
                                this@AjustesActivity,
                                "Ya tienes la última (" + Actualizador.nombreInstalado(this@AjustesActivity) + ")",
                                Toast.LENGTH_LONG).show()
                            else -> {
                                // La descarga y el dialogo viven en MainActivity;
                                // aqui solo se abre para que los enseñe.
                                startActivity(
                                    Intent(this@AjustesActivity, MainActivity::class.java)
                                        .putExtra("buscar_actualizacion", true)
                                )
                            }
                        }
                    }
                }.start()
            }
        }

        val bGuardar = Button(this).apply {
            text = "Guardar y abrir la cocina"
            setOnClickListener {
                guardar()
                if (!prefs.configurada) {
                    Toast.makeText(
                        this@AjustesActivity,
                        "Pega el enlace https de cocina (panel > Cocina > Copiar)",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@setOnClickListener
                }
                Vigia.reiniciar()
                startActivity(Intent(this@AjustesActivity, MainActivity::class.java))
                finish()
            }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 56)
            setBackgroundColor(Color.BLACK)
            addView(TextView(this@AjustesActivity).apply {
                text = "Yammbo KDS"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(etiqueta("Enlace de cocina (panel > Cocina > Copiar)"))
            addView(url)
            addView(etiqueta("Como se conecta la impresora"))
            addView(spTipo)
            addView(lbBt); addView(spImpresora)
            addView(lbIp); addView(ip)
            addView(lbPuerto); addView(puerto)
            addView(lbUsb); addView(bUsb)
            addView(etiqueta("Ancho de papel"))
            addView(spAncho)
            addView(etiqueta("Avisos"))
            addView(cbSonido)
            addView(cbEncima)
            addView(txtEncima)
            addView(bPermiso)
            addView(bProbarAviso)
            addView(bProbar)
            addView(bActualizar)
            addView(bGuardar)
            addView(TextView(this@AjustesActivity).apply {
                text = "Versión instalada " + Actualizador.nombreInstalado(this@AjustesActivity)
                setTextColor(Color.parseColor("#7A7A7A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, 26, 0, 0)
            })
        }
        val raiz = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(col, ViewGroup.LayoutParams(-1, -2))
        }
        setContentView(raiz)
        Insets.aplicar(raiz)
        mostrarSegunTipo()
        refrescarPermisoEncima()
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

/**
 * Con targetSdk 35+ Android dibuja de borde a borde y ya no reserva sitio para
 * la barra de estado: sin esto el titulo queda debajo del reloj del sistema.
 */
object Insets {
    fun aplicar(v: View) {
        ViewCompat.setOnApplyWindowInsetsListener(v) { vista, ventana ->
            val b = ventana.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            vista.setPadding(b.left, b.top, b.right, b.bottom)
            ventana
        }
        ViewCompat.requestApplyInsets(v)
    }
}
