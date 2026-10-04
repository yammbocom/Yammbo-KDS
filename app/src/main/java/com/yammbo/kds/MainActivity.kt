package com.yammbo.kds

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

/**
 * La pantalla. Es un WebView sobre `/kds/<token>`: la interfaz de las comandas
 * ya vive en el worker, asi que el diseno se cambia en el servidor y todas las
 * cocinas lo ven al recargar, sin recompilar ni repartir un APK.
 *
 * Lo que la app anade es lo que una pestana no puede: hablar con la termica,
 * seguir viva por detras y dibujar el aviso encima de Loyverse.
 *
 * Antes de la cocina pasa por dos estados nativos: la bienvenida (pegar el
 * enlace, cuando aun no hay) y los pasos de permisos, uno por pantalla.
 */
class MainActivity : AppCompatActivity() {

    private enum class Estado { BIENVENIDA, PASOS, COCINA }
    private enum class Fallo { RED, ENLACE, SERVIDOR }

    private var web: WebView? = null
    private lateinit var prefs: Prefs
    private var urlCargada: String = ""
    private var estado = Estado.BIENVENIDA
    private val manejador = Handler(Looper.getMainLooper())
    private var delante = false

    /** Host del que se acepta que vengan llamadas a los puentes. */
    @Volatile private var hostActual: String = ""

    // ---- Bienvenida
    /** Lo escrito en el campo. Sobrevive a repintar la pantalla (giro, modo noche). */
    private var borrador: String = ""
    private var comprobando = false
    private var errorBienvenida: Enlace.Resultado? = null
    private var errorFormato = false

    // ---- Pasos
    private var plan: List<Paso> = emptyList()
    private val hechos = HashSet<Paso>()
    private var pasoActual: Paso? = null
    private var pidiendoAvisos = false

    // ---- Cocina
    private var capa: FrameLayout? = null
    private var fallo: Fallo? = null
    private var falloEnEstaCarga = false
    private var actualizacionMirada = false

    /** Con la cocina caida se reintenta sola: la tablet esta colgada y nadie la toca. */
    private val reintento = Runnable {
        if (estado == Estado.COCINA && fallo != null && fallo != Fallo.ENLACE) cargar(conCarga = false)
    }

    /**
     * Se engancha al `fetch` que la propia pagina ya hace cada 5 s. Asi el
     * nativo se entera de las comandas SIN pedir nada por su cuenta: el
     * endpoint consulta a Loyverse y la cuota es del comercio.
     *
     * Acepta tanto `fetch(url)` como `fetch(new Request(url))`: con solo
     * `String(a[0])` un Request daria "[object Request]" y no dispararia nunca.
     */
    private val HOOK = """
        (function(){
          if(window.__yammboHook) return; window.__yammboHook=1;
          var of=window.fetch.bind(window);
          window.fetch=function(){
            var a=arguments;
            return of.apply(null,a).then(function(res){
              try{
                var u=(a[0]&&a[0].url)?a[0].url:String(a[0]||'');
                if(u.indexOf('/data')>=0){
                  res.clone().text().then(function(t){
                    try{ YammboKds.datos(t) }catch(e){}
                  });
                }
              }catch(e){}
              return res;
            });
          };
        })();
    """.trimIndent()

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        prefs = Prefs(this)
        Aviso.crearCanales(this)
        // Estado explicito y IGUAL en todas las versiones: nosotros pintamos de
        // borde a borde y nosotros reservamos el hueco de las barras.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        borrador = saved?.getString("borrador") ?: ""

        // Ya vinculada: directa a la cocina aunque falte un permiso. Ajustes
        // enseña cual falta y lleva a la pantalla de Android.
        if (!prefs.configurada) mostrarBienvenida() else empezar(primeraVez = false)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("borrador", borrador)
    }

    /** El manifest se queda los cambios de giro y de modo noche: se repinta a mano. */
    override fun onConfigurationChanged(nueva: Configuration) {
        super.onConfigurationChanged(nueva)
        when (estado) {
            Estado.BIENVENIDA -> mostrarBienvenida()
            Estado.PASOS -> pasoActual?.let { mostrarPaso(it) }
            // La cocina es negra siempre, como la pagina que lleva dentro.
            Estado.COCINA -> Unit
        }
    }

    private fun dp(v: Float): Int = Ui.dp(this, v)

    // ---- Bienvenida --------------------------------------------------------

    private var campoEnlace: AppCompatEditText? = null

    /**
     * La primera pantalla: que es esto y el enlace de cocina, que es la llave.
     * No hay usuario ni contraseña: el enlace sale del panel y se pega aqui.
     */
    private fun mostrarBienvenida() {
        estado = Estado.BIENVENIDA
        pasoActual = null
        val p = Ui.paleta(this)
        Ui.barras(this, p)

        val col = Ui.Columna(this, dp(480f)).apply {
            setPadding(dp(24f), dp(40f), dp(24f), dp(32f))
        }
        col.addView(Ui.logo(this, p, 72f), LinearLayout.LayoutParams(dp(72f), dp(72f)))
        col.addView(
            Ui.texto(this, getString(R.string.app_name), Ui.Estilo.TITULO, p.texto),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(32f) },
        )
        col.addView(
            Ui.texto(this, getString(R.string.bv_texto), Ui.Estilo.CUERPO, p.secundario),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) },
        )
        col.addView(
            Ui.texto(this, getString(R.string.bv_enlace), Ui.Estilo.SECUNDARIO_MEDIO, p.texto),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(40f) },
        )
        val campo = Ui.campo(
            this, p, "https://pos.yammbo.com/kds/…",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        ).apply {
            setText(borrador)
            setSelection(borrador.length)
            contentDescription = getString(R.string.bv_enlace)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, accion, _ ->
                if (accion == EditorInfo.IME_ACTION_GO) { conectar(); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    borrador = s?.toString() ?: ""
                }
            })
        }
        campoEnlace = campo
        col.addView(campo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        col.addView(
            Ui.texto(this, getString(R.string.bv_enlace_ayuda), Ui.Estilo.CAPTION, p.secundario),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) },
        )

        // El error del enlace va pegado al campo; el de red, en una tarjeta
        // invertida con su boton de reintentar.
        val error = errorBienvenida
        if (errorFormato || error == Enlace.Resultado.INVALIDO) {
            col.addView(
                Ui.texto(
                    this,
                    getString(if (errorFormato) R.string.bv_error_formato else R.string.bv_error_invalido),
                    Ui.Estilo.SECUNDARIO_MEDIO, p.texto,
                ).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) },
            )
        } else if (error != null) {
            col.addView(
                tarjetaError(p, error),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20f) },
            )
        }

        val conectar = Ui.boton(
            this, p,
            getString(if (comprobando) R.string.bv_comprobando else R.string.bv_conectar),
        ) { conectar() }
        Ui.habilitar(conectar, !comprobando)
        col.addView(conectar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(32f) })
        val pegar = Ui.boton(this, p, getString(R.string.bv_pegar), Ui.Tipo.SECUNDARIO) { pegar() }
        Ui.habilitar(pegar, !comprobando)
        col.addView(pegar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })

        val raiz = Ui.pantalla(this, p, col)
        setContentView(raiz)
        Ui.rellenarBarras(raiz)
    }

    /** Tarjeta invertida: pide atencion sin usar color. */
    private fun tarjetaError(p: Ui.Paleta, r: Enlace.Resultado): View {
        val q = p.invertida()
        val (titulo, texto) = when (r) {
            Enlace.Resultado.SIN_RED -> R.string.bv_error_sin_red_t to R.string.bv_error_sin_red
            Enlace.Resultado.SERVIDOR -> R.string.bv_error_servidor_t to R.string.bv_error_servidor
            else -> R.string.bv_error_no_llega_t to R.string.bv_error_no_llega
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.forma(q.fondo, dp(16f).toFloat())
            setPadding(dp(20f), dp(20f), dp(20f), dp(20f))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(Ui.texto(this@MainActivity, getString(titulo), Ui.Estilo.CUERPO_MEDIO, q.texto))
            addView(
                Ui.texto(this@MainActivity, getString(texto), Ui.Estilo.SECUNDARIO, q.secundario),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) },
            )
            addView(
                Ui.boton(this@MainActivity, q, getString(R.string.reintentar), Ui.Tipo.SECUNDARIO) {
                    conectar()
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) },
            )
        }
    }

    private fun pegar() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        val t = if (clip != null && clip.itemCount > 0)
            clip.getItemAt(0).coerceToText(this)?.toString().orEmpty() else ""
        if (t.isBlank()) {
            Toast.makeText(this, R.string.bv_portapapeles_vacio, Toast.LENGTH_SHORT).show()
            return
        }
        borrador = Enlace.normalizar(t)
        campoEnlace?.setText(borrador)
        campoEnlace?.setSelection(borrador.length)
        // Pegado un enlace con buena pinta, se prueba ya: es lo que iba a
        // hacer el encargado a continuacion.
        if (Enlace.valido(borrador)) conectar()
    }

    private fun conectar() {
        if (comprobando) return
        val url = Enlace.normalizar(borrador)
        borrador = url
        errorBienvenida = null
        errorFormato = false
        if (!Enlace.valido(url)) {
            errorFormato = true
            mostrarBienvenida()
            return
        }
        if (!hayRed()) {
            errorBienvenida = Enlace.Resultado.SIN_RED
            mostrarBienvenida()
            return
        }
        comprobando = true
        campoEnlace?.let { c ->
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(c.windowToken, 0)
        }
        mostrarBienvenida()
        Thread {
            val r = Enlace.comprobar(url)
            runOnUiThread {
                comprobando = false
                if (isFinishing || isDestroyed || estado != Estado.BIENVENIDA) return@runOnUiThread
                if (r == Enlace.Resultado.OK || r == Enlace.Resultado.PAUSADO) {
                    prefs.url = url
                    borrador = ""
                    Vigia.reiniciar()
                    empezar(primeraVez = true)
                } else {
                    errorBienvenida = r
                    mostrarBienvenida()
                }
            }
        }.start()
    }

    private fun hayRed(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ---- Pasos de permisos -------------------------------------------------

    /**
     * Con enlace: el servicio arranca ya y, SOLO al vincular por primera vez,
     * los permisos que falten, de uno en uno.
     */
    private fun empezar(primeraVez: Boolean) {
        ServicioKds.arrancar(this)
        plan = Pasos.pendientes(
            primeraVez, Build.VERSION.SDK_INT, avisosConcedidos(), prefs.encima,
            Aviso.puedeDibujarEncima(this),
        )
        hechos.clear()
        siguientePaso()
    }

    private fun avisosConcedidos(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun concedidos(): Set<Paso> {
        val s = HashSet<Paso>(2)
        if (avisosConcedidos()) s.add(Paso.AVISOS)
        if (Aviso.puedeDibujarEncima(this)) s.add(Paso.ENCIMA)
        return s
    }

    private fun siguientePaso() {
        val p = Pasos.siguiente(plan, hechos, concedidos())
        if (p == null) abrirCocina() else mostrarPaso(p)
    }

    private fun hecho(p: Paso) {
        if (estado != Estado.PASOS) return
        hechos.add(p)
        siguientePaso()
    }

    private fun mostrarPaso(paso: Paso) {
        estado = Estado.PASOS
        pasoActual = paso
        val p = Ui.paleta(this)
        Ui.barras(this, p)
        val contador = Pasos.contador(plan, paso)
        val v = when (paso) {
            Paso.AVISOS -> Ui.paso(
                this, p, R.drawable.ic_paso_avisos, contador,
                getString(R.string.paso_avisos_t), getString(R.string.paso_avisos), null,
                getString(R.string.paso_avisos_btn), { pedirAvisos() }, { hecho(Paso.AVISOS) },
            )
            Paso.ENCIMA -> Ui.paso(
                this, p, R.drawable.ic_paso_encima, contador,
                getString(R.string.paso_encima_t), getString(R.string.paso_encima),
                getString(R.string.paso_encima_ayuda),
                getString(R.string.paso_encima_btn), { abrirPermisoEncima() }, { hecho(Paso.ENCIMA) },
            )
        }
        setContentView(v)
        Ui.rellenarBarras(v)
    }

    /** UNA peticion y solo al tocar el boton: la respuesta, sea cual sea, cierra el paso. */
    private fun pedirAvisos() {
        if (pidiendoAvisos) return
        if (Build.VERSION.SDK_INT >= 33) {
            pidiendoAvisos = true
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        } else hecho(Paso.AVISOS)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == 7) {
            pidiendoAvisos = false
            hecho(Paso.AVISOS)
        }
    }

    /**
     * El de dibujar encima no se concede desde un dialogo: hay que mandar al
     * usuario a Ajustes de Android. Al volver, [onResume] mira si lo dio.
     */
    private fun abrirPermisoEncima() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
            )
        }.onFailure { hecho(Paso.ENCIMA) }
    }

    // ---- Cocina ------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun crearWeb(): WebView = WebView(this).apply {
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true          // la pagina guarda "imprimir sola"
        settings.mediaPlaybackRequiresUserGesture = false
        addJavascriptInterface(PuentePrint(), "YammboPrint")
        addJavascriptInterface(PuenteDatos(), "YammboKds")
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView?, url: String?, f: android.graphics.Bitmap?) {
                hostActual = runCatching { Uri.parse(url).host ?: "" }.getOrDefault("")
                falloEnEstaCarga = false
            }
            override fun onPageFinished(v: WebView?, url: String?) {
                hostActual = runCatching { Uri.parse(url).host ?: "" }.getOrDefault("")
                v?.evaluateJavascript(HOOK, null)
                if (!falloEnEstaCarga) ocultarCapa()
            }
            // En vez de la pagina de error del WebView, una tarjeta nativa con
            // reintentar. Solo cuenta la pagina principal, no un recurso suelto.
            override fun onReceivedError(v: WebView?, r: WebResourceRequest?, e: WebResourceError?) {
                if (r?.isForMainFrame != true) return
                falloEnEstaCarga = true
                mostrarFallo(Fallo.RED)
            }
            override fun onReceivedHttpError(
                v: WebView?, r: WebResourceRequest?, resp: WebResourceResponse?,
            ) {
                if (r?.isForMainFrame != true) return
                val c = resp?.statusCode ?: return
                // 402 (cuenta en pausa) NO: el worker tiene su propia pagina
                // que explica que es la cuenta y no un enlace roto.
                val f = when {
                    c == 401 || c == 403 || c == 404 || c == 410 -> Fallo.ENLACE
                    c >= 500 -> Fallo.SERVIDOR
                    else -> return
                }
                falloEnEstaCarga = true
                mostrarFallo(f)
            }
            // El WebView no sale del sitio del KDS: fuera de el, los puentes
            // dejarian imprimir basura o falsear avisos.
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                val h = r?.url?.host ?: return false
                return h != hostEsperado()
            }
        }
    }

    private fun abrirCocina() {
        estado = Estado.COCINA
        pasoActual = null
        // Negra siempre, como la pantalla web del KDS: una tablet colgada en
        // cocina se mira de lejos y el blanco a pantalla completa deslumbra.
        val p = Ui.OSCURA
        Ui.barras(this, p)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))

        val w = web ?: crearWeb().also { web = it }
        (w.parent as? ViewGroup)?.removeView(w)

        val contenido = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(w, FrameLayout.LayoutParams(-1, -1))
        }
        capa = FrameLayout(this).apply { visibility = View.GONE }
        contenido.addView(capa, FrameLayout.LayoutParams(-1, -1))

        // El hueco de las barras se reserva en un CONTENEDOR, no en el WebView:
        // el padding sobre el propio WebView no movia la cabecera de la pagina.
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        raiz.addView(tira(p), LinearLayout.LayoutParams(-1, -2))
        raiz.addView(contenido, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(raiz)
        Ui.rellenarBarras(raiz)

        // pauseTimers es de todo el proceso: si la cocina se rehace tras
        // desvincular, los temporizadores de la pagina seguirian parados.
        w.resumeTimers()
        cargar()
        if (BuildConfig.SELF_UPDATE && !actualizacionMirada) {
            actualizacionMirada = true
            mirarActualizacion()
        }
    }

    /**
     * Barra fina con el acceso a Ajustes. Va SIEMPRE y encima del WebView, no
     * flotando sobre el: cualquier boton superpuesto acabaria tapando algo de
     * la pantalla de comandas, y la cabecera del KDS ya lleva el check
     * "Imprimir sola" pegado a la derecha.
     */
    private fun tira(p: Ui.Paleta): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(p.superficie)
        setPadding(dp(16f), 0, dp(4f), 0)
        addView(
            Ui.icono(this@MainActivity, R.drawable.ic_noti, p.secundario),
            LinearLayout.LayoutParams(dp(20f), dp(20f)),
        )
        addView(
            Ui.texto(this@MainActivity, getString(R.string.app_name), Ui.Estilo.SECUNDARIO_MEDIO, p.secundario),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10f) },
        )
        addView(
            Ui.botonIcono(this@MainActivity, p, R.drawable.ic_ajustes, getString(R.string.ajustes)) {
                startActivity(Intent(this@MainActivity, AjustesActivity::class.java))
            },
            LinearLayout.LayoutParams(dp(48f), dp(48f)),
        )
    }

    /** Mientras carga: un indicador centrado sobre el negro, nada por encima de las comandas. */
    private fun mostrarCarga() {
        val c = capa ?: return
        val p = Ui.OSCURA
        c.removeAllViews()
        c.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.BLACK)
                addView(
                    ProgressBar(this@MainActivity).apply {
                        isIndeterminate = true
                        indeterminateTintList = ColorStateList.valueOf(p.texto)
                    },
                    LinearLayout.LayoutParams(dp(40f), dp(40f)),
                )
                addView(
                    Ui.texto(this@MainActivity, getString(R.string.cocina_cargando), Ui.Estilo.SECUNDARIO, p.secundario),
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16f) },
                )
            },
            FrameLayout.LayoutParams(-1, -1),
        )
        c.visibility = View.VISIBLE
    }

    private fun mostrarFallo(f: Fallo) {
        val c = capa ?: return
        fallo = f
        val p = Ui.OSCURA
        val reintentar = { cargar() }
        val aAjustes = { startActivity(Intent(this, AjustesActivity::class.java)) }
        val v = when (f) {
            Fallo.RED -> Ui.estado(
                this, p, R.drawable.ic_sin_red,
                getString(R.string.cocina_sin_red_t), getString(R.string.cocina_sin_red),
                getString(R.string.cocina_reintento),
                listOf(
                    Ui.boton(this, p, getString(R.string.reintentar)) { reintentar() },
                    Ui.boton(this, p, getString(R.string.ajustes), Ui.Tipo.TERCIARIO) { aAjustes() },
                ),
            )
            Fallo.SERVIDOR -> Ui.estado(
                this, p, R.drawable.ic_noti,
                getString(R.string.cocina_servidor_t), getString(R.string.cocina_servidor),
                getString(R.string.cocina_reintento),
                listOf(
                    Ui.boton(this, p, getString(R.string.reintentar)) { reintentar() },
                    Ui.boton(this, p, getString(R.string.ajustes), Ui.Tipo.TERCIARIO) { aAjustes() },
                ),
            )
            Fallo.ENLACE -> Ui.estado(
                this, p, R.drawable.ic_enlace,
                getString(R.string.cocina_enlace_t), getString(R.string.cocina_enlace), null,
                listOf(
                    Ui.boton(this, p, getString(R.string.cocina_cambiar_enlace)) { aAjustes() },
                    Ui.boton(this, p, getString(R.string.reintentar), Ui.Tipo.SECUNDARIO) { reintentar() },
                ),
            )
        }
        c.removeAllViews()
        c.addView(v, FrameLayout.LayoutParams(-1, -1))
        c.visibility = View.VISIBLE
        manejador.removeCallbacks(reintento)
        if (f != Fallo.ENLACE && delante) manejador.postDelayed(reintento, REINTENTO_MS)
    }

    private fun ocultarCapa() {
        fallo = null
        manejador.removeCallbacks(reintento)
        capa?.removeAllViews()
        capa?.visibility = View.GONE
    }

    /** Al desvincular desde Ajustes: la pagina deja de pedir `/data` ya. */
    private fun desmontarCocina() {
        manejador.removeCallbacks(reintento)
        fallo = null
        capa = null
        urlCargada = ""
        web?.let { w ->
            w.stopLoading()
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        }
        web = null
    }

    /**
     * Una mirada al dia. La tablet de cocina no se toca en meses: si no se
     * actualiza sola, se queda en la version del dia que la colgaron.
     */
    fun mirarActualizacion(forzar: Boolean = false) {
        if (!forzar && !Actualizador.tocaMirar(this)) return
        Thread {
            val v = Actualizador.ultima() ?: return@Thread
            if (!Actualizador.hayNueva(this, v)) return@Thread
            runOnUiThread { ofrecer(v) }
        }.start()
    }

    private fun ofrecer(v: Actualizador.Version) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.act_nueva, v.name))
            .setMessage(if (v.notas.isBlank()) getString(R.string.act_generico) else v.notas.take(700))
            .setPositiveButton(R.string.act_actualizar) { _, _ -> instalar(v) }
            .setNegativeButton(R.string.act_ahora_no, null)
            .show()
    }

    private fun instalar(v: Actualizador.Version) {
        // Android no deja instalar en silencio: hay que tener permitido
        // "instalar apps desconocidas" y aun asi alguien confirma una vez.
        if (!Actualizador.puedeInstalar(this)) {
            Toast.makeText(this, R.string.act_permiso_instalar, Toast.LENGTH_LONG).show()
            Actualizador.pedirPermisoInstalar(this)
            return
        }
        Toast.makeText(this, R.string.act_descargando, Toast.LENGTH_LONG).show()
        Thread {
            val err = Actualizador.descargarEInstalar(this, v)
            if (err != null) runOnUiThread {
                Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun hostEsperado(): String =
        runCatching { Uri.parse(prefs.url).host ?: "" }.getOrDefault("")

    private fun cargar(conCarga: Boolean = true) {
        val w = web ?: return
        urlCargada = prefs.url
        if (conCarga) mostrarCarga()
        w.loadUrl(urlCargada)
    }

    // launchMode singleTask: si la actividad ya vivia, el intent nuevo llega
    // por aqui y no por onCreate.
    override fun onNewIntent(nuevo: Intent) {
        super.onNewIntent(nuevo)
        setIntent(nuevo)
    }

    override fun onResume() {
        super.onResume()
        delante = true
        if (BuildConfig.SELF_UPDATE && intent?.getBooleanExtra("buscar_actualizacion", false) == true) {
            intent.removeExtra("buscar_actualizacion")
            mirarActualizacion(forzar = true)
        }
        Vigia.enPrimerPlano = true
        Aviso.quitarCartel(this)
        when (estado) {
            // Se pego el enlace en Ajustes y se volvio con "Guardar".
            // Antes no habia enlace: cuenta como primera vinculacion.
            Estado.BIENVENIDA -> if (prefs.configurada && !comprobando) empezar(primeraVez = true)
            Estado.PASOS -> when {
                !prefs.configurada -> mostrarBienvenida()
                // De vuelta de los ajustes de Android con el permiso ya dado.
                pasoActual != null && pasoActual in concedidos() -> hecho(pasoActual!!)
            }
            Estado.COCINA -> {
                if (!prefs.configurada) {
                    desmontarCocina()
                    mostrarBienvenida()
                    return
                }
                val w = web ?: return
                w.resumeTimers()
                // Si se cambio el enlace en Ajustes, esta instancia sobrevive
                // (launchMode singleTask) y seguiria alimentando a Vigia con el token
                // viejo mientras el servicio usa el nuevo.
                if (prefs.url != urlCargada) cargar()
                else if (fallo != null && fallo != Fallo.ENLACE) cargar(conCarga = false)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        delante = false
        Vigia.enPrimerPlano = false
        manejador.removeCallbacks(reintento)
        web?.pauseTimers()
    }

    override fun onDestroy() {
        manejador.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Los puentes solo valen si la pagina cargada es la del KDS configurado. */
    private fun origenValido(): Boolean {
        val esperado = hostEsperado()
        return esperado.isNotEmpty() && hostActual == esperado
    }

    /** El contrato que la pantalla web espera: `window.YammboPrint.imprimir(json)`. */
    inner class PuentePrint {
        @JavascriptInterface
        fun imprimir(json: String) {
            if (!origenValido()) return
            Impresora.encolar(applicationContext, json) { msg ->
                runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Recibe el mismo `/data` que la pagina acaba de leer. */
    inner class PuenteDatos {
        @JavascriptInterface
        fun datos(json: String) {
            if (!origenValido()) return
            Vigia.procesar(this@MainActivity, json)
        }
    }

    companion object {
        private const val REINTENTO_MS = 30_000L
    }
}
