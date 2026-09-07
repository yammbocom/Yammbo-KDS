package com.yammbo.kds

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mantiene la vigilancia cuando la pantalla del KDS no puede hacerla.
 *
 * 🚨 Cuota: `/data` consulta a Loyverse en cada llamada y el limite es 300
 * peticiones / 5 min POR COMERCIO; la pantalla web ya gasta 60 cada 5 min ella
 * sola. Por eso este bucle se aparta mientras la pagina poletea.
 *
 * Pero "la activity esta delante" no es lo mismo que "estan llegando datos":
 * si el worker cae, el wifi se va o el token se revoca, la pagina se queda en
 * un error y nadie vigilaria. De ahi el latido [Vigia.ultimoDato]: si en
 * primer plano no llega nada en [SIN_DATOS_MS], este bucle toma el relevo.
 */
class ServicioKds : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bucle: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Aviso.crearCanales(this)
        // En 14+ arrancar un servicio en primer plano desde ciertos contextos
        // (arranque del sistema, por ejemplo) puede estar prohibido: que no se
        // lleve por delante el proceso.
        runCatching { startForeground(1, Aviso.notificacionServicio(this)) }
            .onFailure { Log.w(TAG, "sin primer plano: " + it.message) }
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yammbo:kds-svc")
                .also { it.acquire() }
        }
        arrancar()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bucle?.isActive != true) arrancar()
        return START_STICKY
    }

    /** Android 15 puede pedir que un servicio largo se retire; obedecer rapido
     *  evita el ANR. START_STICKY lo vuelve a levantar despues. */
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "el sistema pide parar el servicio")
        stopSelf()
    }

    private fun meToca(prefs: Prefs): Boolean {
        if (!prefs.configurada) return false
        if (!Vigia.enPrimerPlano) return true
        // Delante pero sin datos: la pagina no esta trayendo nada.
        return !Vigia.hayDatosRecientes(SIN_DATOS_MS)
    }

    private fun arrancar() {
        bucle?.cancel()
        bucle = scope.launch {
            val prefs = Prefs(this@ServicioKds)
            var espera = PAUSA_MS
            while (isActive) {
                if (!meToca(prefs)) { delay(3_000); espera = PAUSA_MS; continue }

                val cuerpo = runCatching { pedir(prefs.urlDatos()) }.getOrNull()
                if (cuerpo != null) {
                    // Sin `avisar=false` aqui: Vigia ya calla la primerisima
                    // lectura, y silenciar el primer poll de cada relevo hacia
                    // desaparecer justo la comanda que entraba en el cambio.
                    Vigia.procesar(this@ServicioKds, cuerpo)
                    espera = PAUSA_MS
                } else {
                    // Si el otro lado esta limitando o caido, aflojar en vez de
                    // insistir cada 5 s contra la cuota del comercio.
                    espera = minOf(espera * 2, TOPE_MS)
                }
                delay(espera)
            }
        }
    }

    private fun pedir(url: String): String? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("accept", "application/json")
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        return try {
            val code = c.responseCode
            if (code != 200) { Log.w(TAG, "data HTTP " + code); null }
            else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    override fun onDestroy() {
        runCatching { wake?.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "YammboKDS"
        private const val PAUSA_MS = 5_000L
        private const val TOPE_MS = 60_000L
        /** Margen sobre los 5 s de la pagina antes de dar por muda la pantalla. */
        private const val SIN_DATOS_MS = 20_000L

        fun arrancar(ctx: Context) {
            val i = Intent(ctx, ServicioKds::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }.onFailure { Log.w(TAG, "no se pudo arrancar: " + it.message) }
        }
    }
}
