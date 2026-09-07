package com.yammbo.kds

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * La pantalla. Es un WebView sobre `/kds/<token>`: la interfaz de las comandas
 * ya vive en el worker, asi que el diseno se cambia en el servidor y todas las
 * cocinas lo ven al recargar, sin recompilar ni repartir un APK.
 *
 * Lo que la app anade es lo que una pestana no puede: hablar con la termica,
 * seguir viva por detras y dibujar el aviso encima de Loyverse.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var prefs: Prefs
    private var urlCargada: String = ""
    private lateinit var raiz: LinearLayout

    /** Host del que se acepta que vengan llamadas a los puentes. */
    @Volatile private var hostActual: String = ""

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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        prefs = Prefs(this)
        Aviso.crearCanales(this)
        // Estado explicito y IGUAL en todas las versiones: nosotros pintamos de
        // borde a borde y nosotros reservamos el hueco de las barras.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (!prefs.configurada) {
            startActivity(Intent(this, AjustesActivity::class.java))
            finish()
            return
        }

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true          // la pagina guarda "imprimir sola"
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(PuentePrint(), "YammboPrint")
            addJavascriptInterface(PuenteDatos(), "YammboKds")
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView?, url: String?, f: android.graphics.Bitmap?) {
                    hostActual = runCatching { Uri.parse(url).host ?: "" }.getOrDefault("")
                }
                override fun onPageFinished(v: WebView?, url: String?) {
                    hostActual = runCatching { Uri.parse(url).host ?: "" }.getOrDefault("")
                    v?.evaluateJavascript(HOOK, null)
                }
                // El WebView no sale del sitio del KDS: fuera de el, los puentes
                // dejarian imprimir basura o falsear avisos.
                override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                    val h = r?.url?.host ?: return false
                    return h != hostEsperado()
                }
            }
        }

        // El hueco de las barras se reserva en un CONTENEDOR, no en el WebView:
        // el padding sobre el propio WebView no movia la cabecera de la pagina.
        raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        raiz.addView(barraSuperior(), LinearLayout.LayoutParams(-1, -2))
        raiz.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(raiz)
        Insets.aplicar(raiz)
        // Fondo negro: los iconos de la barra tienen que ir en claro.
        WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = false

        pedirPermisos()
        ServicioKds.arrancar(this)
        cargar()
        mirarActualizacion()
    }

    /**
     * Barra fina con el acceso a Ajustes. Va SIEMPRE y encima del WebView, no
     * flotando sobre el: cualquier boton superpuesto acabaria tapando algo de
     * la pantalla de comandas, y la cabecera del KDS ya lleva el check
     * "Imprimir sola" pegado a la derecha.
     */
    private fun barraSuperior(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        setBackgroundColor(Color.parseColor("#141414"))
        setPadding(20, 6, 20, 6)
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.ajustes)
            setTextColor(Color.parseColor("#BDBDBD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(30, 14, 30, 14)
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AjustesActivity::class.java))
            }
        })
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
            Toast.makeText(
                this, "Permite instalar apps desde Yammbo KDS y vuelve a intentarlo",
                Toast.LENGTH_LONG,
            ).show()
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

    private fun cargar() {
        urlCargada = prefs.url
        web.loadUrl(urlCargada)
    }

    private fun pedirPermisos() {
        val faltan = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) faltan.add(Manifest.permission.POST_NOTIFICATIONS)
        if (faltan.isNotEmpty()) ActivityCompat.requestPermissions(this, faltan.toTypedArray(), 7)

        // El de dibujar encima no se concede desde un dialogo: hay que mandar
        // al usuario a Ajustes de Android. Se pide una vez y no se insiste.
        if (prefs.encima && !Aviso.puedeDibujarEncima(this)) {
            Toast.makeText(this, R.string.aviso_falta_encima, Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
                )
            }
        }
    }

    // launchMode singleTask: si la actividad ya vivia, el intent nuevo llega
    // por aqui y no por onCreate.
    override fun onNewIntent(nuevo: Intent) {
        super.onNewIntent(nuevo)
        setIntent(nuevo)
    }

    override fun onResume() {
        super.onResume()
        if (intent?.getBooleanExtra("buscar_actualizacion", false) == true) {
            intent.removeExtra("buscar_actualizacion")
            mirarActualizacion(forzar = true)
        }
        Vigia.enPrimerPlano = true
        Aviso.quitarCartel(this)
        if (!::web.isInitialized) return
        web.resumeTimers()
        // Si se cambio el enlace en Ajustes, esta instancia sobrevive
        // (launchMode singleTask) y seguiria alimentando a Vigia con el token
        // viejo mientras el servicio usa el nuevo.
        if (prefs.url != urlCargada) cargar()
    }

    override fun onPause() {
        super.onPause()
        Vigia.enPrimerPlano = false
        if (::web.isInitialized) web.pauseTimers()
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
}
