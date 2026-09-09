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
import android.text.TextUtils
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
                description = ctx.getString(R.string.canal_pedidos_desc)
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
            .setContentText(ctx.getString(R.string.canal_servicio_desc))
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

    /** dp a px. Los tamaños en crudo salian minusculos en una tablet densa y
     *  enormes en una barata: el cartel tiene que medir lo mismo en las dos. */
    private fun dp(ctx: Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * Ancho de la tarjeta. ACOTADO, nunca a pantalla completa.
     *
     * 🚨 Con MATCH_PARENT, en una tablet en horizontal salia un cartel de borde
     * a borde con el texto pegado a la izquierda y un palmo de negro vacio a la
     * derecha: imposible de leer de un vistazo desde la caja, que es justo para
     * lo que existe. Se acota tambien contra el ancho real para que en un movil
     * no quede pegado a los bordes.
     */
    private fun anchoTarjeta(ctx: Context): Int {
        val dm = ctx.resources.displayMetrics
        return minOf(dp(ctx, 340f), dm.widthPixels - dp(ctx, 48f))
            .coerceAtLeast(dp(ctx, 220f))
    }

    /**
     * sp a px. El texto crece con el tamaño de letra del SISTEMA, que en una
     * tablet de mostrador suele estar en grande: medir en dp los renglones que
     * se pintan en sp da una cuenta corta y la tarjeta se sale.
     */
    private fun sp(ctx: Context, v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics).toInt()

    /**
     * Cuantos renglones de platos caben sin que la tarjeta se salga.
     *
     * 🚨 Si se sale, el FrameLayout la recorta por ABAJO y lo que se pierde son
     * los ultimos platos y el pie ("toca para abrir"), justo lo que no puede
     * faltar. Por eso se descuenta primero todo lo fijo — encabezado, titulo,
     * cuerpo de hasta dos lineas, separador, pie y paddings — y solo lo que
     * sobra se reparte en renglones.
     *
     * Lo usa Vigia para recortar la comanda: el corte se hace una sola vez, en
     * el sitio donde todavia se sabe cuantos platos quedan fuera.
     */
    fun lineasQueCaben(ctx: Context): Int {
        val alto = ctx.resources.displayMetrics.heightPixels
        val fijo = sp(ctx, 100f) + dp(ctx, 62f)
        val renglon = sp(ctx, 16f) + dp(ctx, 6f)
        return ((alto * 0.88f - fijo) / renglon).toInt().coerceIn(2, 9)
    }

    /**
     * El cartel encima de todo. Se quita solo a los 20 s o al tocarlo: dejarlo
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

                fun linea(
                    t: String,
                    sp: Float,
                    color: Int,
                    arriba: Int,
                    negrita: Boolean = false,
                    lineas: Int = 1,
                ) = TextView(ctx).apply {
                    text = t
                    setTextColor(color)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                    setPadding(0, arriba, 0, 0)
                    // Con la tarjeta estrecha, un plato de nombre largo la
                    // estiraria hacia abajo hasta comerse la pantalla.
                    maxLines = lineas
                    ellipsize = TextUtils.TruncateAt.END
                    if (negrita) typeface = android.graphics.Typeface.DEFAULT_BOLD
                }

                // Tarjeta centrada, no pegada arriba: ahi chocaba con la propia
                // notificacion emergente y el cartel quedaba medio tapado.
                val tarjeta = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.BLACK)
                        setStroke(dp(ctx, 2f), Color.WHITE)
                        cornerRadius = dp(ctx, 18f).toFloat()
                    }
                    setPadding(dp(ctx, 20f), dp(ctx, 15f), dp(ctx, 20f), dp(ctx, 13f))
                    addView(
                        linea(ctx.getString(R.string.cartel_encabezado), 11f,
                            Color.parseColor("#9E9E9E"), 0).apply { letterSpacing = 0.16f }
                    )
                    addView(
                        linea(titulo, 23f, Color.WHITE, dp(ctx, 2f), negrita = true, lineas = 2)
                    )
                    addView(
                        linea(cuerpo, 14f, Color.parseColor("#C7C7C7"), dp(ctx, 2f), lineas = 2)
                    )
                    // Lo que se va a cocinar. El cajero decide con esto si le
                    // corre prisa, sin tener que abrir la cocina.
                    if (detalle.isNotEmpty()) {
                        addView(View(ctx).apply {
                            setBackgroundColor(Color.parseColor("#3A3A3A"))
                            layoutParams = LinearLayout.LayoutParams(-1, dp(ctx, 1f))
                                .apply { topMargin = dp(ctx, 11f) }
                        })
                        detalle.forEachIndexed { i, d ->
                            val sangrada = d.startsWith(" ")
                            addView(
                                linea(
                                    d.trim(),
                                    if (sangrada) 13f else 16f,
                                    if (sangrada) Color.parseColor("#9E9E9E") else Color.WHITE,
                                    dp(ctx, if (i == 0) 10f else if (sangrada) 1f else 6f),
                                    negrita = !sangrada,
                                )
                            )
                        }
                    }
                    addView(
                        linea(ctx.getString(R.string.cartel_abrir), 12f,
                            Color.parseColor("#8A8A8A"), dp(ctx, 13f))
                    )
                }

                // Marco a pantalla completa: tocar FUERA de la tarjeta lo cierra
                // sin abrir nada, que es lo que espera quien esta cobrando.
                val marco = FrameLayout(ctx).apply {
                    addView(
                        tarjeta,
                        FrameLayout.LayoutParams(anchoTarjeta(ctx), -2, Gravity.CENTER),
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
            // Con una nota de linea a proposito: es el renglon que salia
            // como «null» antes de leer el JSON con Json.texto.
            listOf(
                "1  Papa rellena",
                "2  Tacos de pollo",
                "   + Gallo pinto, Arroz",
                "   “sin cebolla”",
                "1  Carne asada",
            ),
        )
    }
}
