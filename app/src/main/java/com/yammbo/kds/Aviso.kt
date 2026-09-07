package com.yammbo.kds

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * El aviso de que entro una comanda.
 *
 * Son tres cosas a la vez y ninguna sobra: notificacion (queda en la barra si
 * nadie mira), sonido (la cocina esta de espaldas) y un cartel DIBUJADO ENCIMA
 * de lo que haya delante — normalmente Loyverse, que es justo el caso que una
 * pestana de navegador no puede cubrir.
 */
object Aviso {
    // 🚨 Los ajustes de un canal (sonido, vibracion, importancia) son INMUTABLES
    // una vez creado en el dispositivo: cambiarlos en el codigo no toca el canal
    // ya instalado. Para que el sonido de alarma llegue a las tablets que ya
    // tenian la version anterior hay que estrenar id.
    const val CANAL_PEDIDOS = "pedidos_v2"
    const val CANAL_SERVICIO = "servicio"
    private const val ID_PEDIDO = 1001

    /** Alarma y no notificacion: es lo que suena aunque el aparato este en
     *  silencio, que es como acaba siempre una tablet de mostrador. */
    private fun atributosAlarma(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun uriAlarma(): android.net.Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    fun crearCanales(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CANAL_PEDIDOS, ctx.getString(R.string.canal_pedidos),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Cuando entra una comanda nueva"
                setSound(uriAlarma(), atributosAlarma())
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
                enableLights(true)
                lightColor = Color.WHITE
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            // Baja a proposito: es el aviso permanente de "la pantalla esta
            // viva", y no debe sonar ni empujar nada.
            NotificationChannel(
                CANAL_SERVICIO, ctx.getString(R.string.canal_servicio),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    fun notificacionServicio(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CANAL_SERVICIO)
            .setContentTitle("Yammbo KDS")
            .setContentText("Atento a los pedidos")
            .setSmallIcon(R.drawable.ic_noti)
            .setOngoing(true)
            .setContentIntent(abrirApp(ctx))
            .addAction(0, "Ajustes", abrirAjustes(ctx))
            .build()

    private fun abrirApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, 0, i, banderas())
    }

    private fun abrirAjustes(ctx: Context): PendingIntent {
        val i = Intent(ctx, AjustesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, 1, i, banderas())
    }

    private fun banderas(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    fun notificar(ctx: Context, titulo: String, cuerpo: String) {
        val n = NotificationCompat.Builder(ctx, CANAL_PEDIDOS)
            .setContentTitle(titulo)
            .setContentText(cuerpo)
            .setSmallIcon(R.drawable.ic_noti)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setSound(uriAlarma(), android.media.AudioManager.STREAM_ALARM)  // pre-Oreo
            .setContentIntent(abrirApp(ctx))
            .setFullScreenIntent(abrirApp(ctx), true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_PEDIDO, n) }
    }

    private var tono: Ringtone? = null

    /**
     * Suena por el canal de ALARMA. Antes eran tres pitidos sinteticos con
     * ToneGenerator y en un aparato en silencio no se oian: el tono del sistema
     * con USAGE_ALARM si atraviesa el modo silencio.
     */
    fun sonar(ctx: Context) {
        runCatching {
            val uri = uriAlarma() ?: return
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    tono?.let { if (it.isPlaying) it.stop() }
                    val r = RingtoneManager.getRingtone(ctx.applicationContext, uri) ?: return@post
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        r.audioAttributes = atributosAlarma()
                    }
                    r.play()
                    tono = r
                    // Un aviso, no una sirena: cuatro segundos y calla.
                    Handler(Looper.getMainLooper()).postDelayed(
                        { runCatching { if (r.isPlaying) r.stop() } }, 4000)
                }
            }
        }
    }

    /** Enciende la pantalla apagada: de noche la cocina no ve un aviso a oscuras. */
    fun despertar(ctx: Context) {
        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "yammbo:kds",
            )
            wl.acquire(8000)
        }
    }

    fun puedeDibujarEncima(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    private var cartel: View? = null

    /**
     * El cartel encima de todo. Se quita solo a los 12 s o al tocarlo: dejarlo
     * fijo taparia la caja justo cuando el cajero esta cobrando.
     *
     * Devuelve false si no hay permiso, para que quien llama pueda decirlo en
     * vez de callarse: un aviso que no aparece y no explica por que es el peor
     * fallo posible aqui.
     */
    fun encima(
        ctx: Context,
        titulo: String,
        cuerpo: String,
        detalle: List<String> = emptyList(),
    ): Boolean {
        if (!puedeDibujarEncima(ctx)) return false
        Handler(Looper.getMainLooper()).post {
            runCatching {
                quitarCartel(ctx)
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

                fun linea(t: String, sp: Float, color: Int, arriba: Int, negrita: Boolean = false) =
                    TextView(ctx).apply {
                        text = t
                        setTextColor(color)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                        setPadding(0, arriba, 0, 0)
                        if (negrita) typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }

                // Tarjeta centrada, no pegada arriba: ahi chocaba con la propia
                // notificacion emergente y el cartel quedaba medio tapado.
                val tarjeta = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.BLACK)
                        setStroke(4, Color.WHITE)
                        cornerRadius = 34f
                    }
                    setPadding(64, 52, 64, 48)
                    addView(linea("PEDIDO EN LÍNEA", 12f, Color.parseColor("#9E9E9E"), 0).apply {
                        letterSpacing = 0.18f
                    })
                    addView(linea(titulo, 27f, Color.WHITE, 10, negrita = true))
                    addView(linea(cuerpo, 16f, Color.parseColor("#C7C7C7"), 6))
                    // Lo que se va a cocinar. El cajero decide con esto si le
                    // corre prisa, sin tener que abrir la cocina.
                    if (detalle.isNotEmpty()) {
                        addView(View(ctx).apply {
                            setBackgroundColor(Color.parseColor("#3A3A3A"))
                            layoutParams = LinearLayout.LayoutParams(-1, 2).apply { topMargin = 24 }
                        })
                        detalle.forEachIndexed { i, d ->
                            val sangrada = d.startsWith(" ")
                            addView(
                                linea(
                                    d.trim(),
                                    if (sangrada) 14f else 18f,
                                    if (sangrada) Color.parseColor("#9E9E9E") else Color.WHITE,
                                    if (i == 0) 22 else if (sangrada) 2 else 12,
                                    negrita = !sangrada,
                                )
                            )
                        }
                    }
                    addView(linea("Toca aquí para abrir la cocina", 13f, Color.parseColor("#9E9E9E"), 28))
                }

                // Marco a pantalla completa: tocar FUERA de la tarjeta lo cierra
                // sin abrir nada, que es lo que espera quien esta cobrando.
                val marco = FrameLayout(ctx).apply {
                    setPadding(52, 0, 52, 0)
                    addView(
                        tarjeta,
                        FrameLayout.LayoutParams(-1, -2, Gravity.CENTER),
                    )
                    isClickable = true
                    setOnClickListener { quitarCartel(ctx) }
                }
                tarjeta.setOnClickListener {
                    quitarCartel(ctx)
                    ctx.startActivity(
                        Intent(ctx, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                }

                val tipo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    tipo,
                    // NOT_FOCUSABLE: no roba el teclado ni el foco a Loyverse,
                    // pero la ventana si recibe el toque para poder cerrarse.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                    android.graphics.PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.CENTER
                    dimAmount = 0.6f
                }
                wm.addView(marco, lp)
                cartel = marco
                // Comparar identidad: si entretanto entro otra comanda, el
                // temporizador de la anterior apagaria el cartel nuevo.
                Handler(Looper.getMainLooper()).postDelayed(
                    { if (cartel === marco) quitarCartel(ctx) }, 20_000)
            }
        }
        return true
    }

    fun quitarCartel(ctx: Context) {
        val v = cartel ?: return
        cartel = null
        runCatching {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        }
    }

    /** Dispara el aviso completo para probarlo sin esperar a un pedido real. */
    fun probar(ctx: Context): Boolean {
        val app = ctx.applicationContext
        crearCanales(app)
        notificar(app, "Pedido en línea 0082", "4 artículos · Recoge · Frank Test")
        sonar(app)
        despertar(app)
        return encima(
            app, "Pedido 0082", "4 artículos · Recoge · Frank Test",
            listOf("1  Papa rellena", "2  Tacos de pollo", "   + Gallo pinto, Arroz", "1  Carne Asada"),
        )
    }
}
