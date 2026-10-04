package com.yammbo.kds

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
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
    /** La notificacion de pedido: una sola, la ultima pisa a la anterior. */
    const val ID_PEDIDO = 1001

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
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(ctx.getString(R.string.canal_servicio_desc))
            .setSmallIcon(R.drawable.ic_noti)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(abrirApp(ctx))
            .addAction(0, ctx.getString(R.string.ajustes), abrirAjustes(ctx))
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
            // Desplegada se lee entera: la comanda no cabe en una linea.
            .setStyle(NotificationCompat.BigTextStyle().bigText(cuerpo))
            .setSmallIcon(R.drawable.ic_noti)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setSound(uriAlarma(), android.media.AudioManager.STREAM_ALARM)  // pre-Oreo
            .setContentIntent(abrirApp(ctx))
            .apply { if (BuildConfig.FULL_SCREEN_ALERT) setFullScreenIntent(abrirApp(ctx), true) }
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
        return minOf(Ui.dp(ctx, 400f), dm.widthPixels - Ui.dp(ctx, 48f))
            .coerceAtLeast(Ui.dp(ctx, 260f))
    }

    /**
     * Cuantos renglones de platos caben sin que la tarjeta se salga.
     *
     * 🚨 Si se sale, el FrameLayout la recorta por ABAJO y lo que se pierde son
     * los ultimos platos y los botones, justo lo que no puede faltar. Por eso
     * se descuenta primero todo lo fijo — chip, titulo de hasta dos lineas,
     * cuerpo de hasta dos, separador, botones y paddings — y solo lo que sobra
     * se reparte en renglones. Lo que es texto se mide en sp (crece con la
     * letra del sistema) y lo demas en dp.
     *
     * Lo usa Vigia para recortar la comanda: el corte se hace una sola vez, en
     * el sitio donde todavia se sabe cuantos platos quedan fuera.
     */
    fun lineasQueCaben(ctx: Context): Int {
        val alto = ctx.resources.displayMetrics.heightPixels
        // sp: chip 14 + titulo 2 x 33 + cuerpo 2 x 19. dp: paddings 44, chip 14,
        // margenes 16, separador 17, primer renglon 4, botones 20 + 52.
        val fijo = Ui.sp(ctx, 120f) + Ui.dp(ctx, 168f)
        val renglon = Ui.sp(ctx, 21f) + Ui.dp(ctx, 8f)
        return ((alto * 0.88f - fijo) / renglon).toInt().coerceIn(2, 9)
    }

    /**
     * El cartel encima de todo. Se quita solo a los 20 s, al tocar fuera o con
     * "Cerrar": dejarlo fijo taparia la caja justo cuando el cajero esta
     * cobrando. Tocar la tarjeta o "Abrir cocina" abre la cocina.
     *
     * Tarjeta INVERTIDA respecto al sistema (negra de dia, clara de noche): es
     * lo que pide atencion, y en B/N la atencion se dice invirtiendo.
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
                val p = Ui.paleta(ctx).invertida()
                fun dp(v: Float) = Ui.dp(ctx, v)

                fun linea(
                    t: String,
                    sp: Float,
                    color: Int,
                    medio: Boolean = false,
                    lineas: Int = 1,
                ) = Ui.texto(ctx, t, sp, medio, color).apply {
                    // Con la tarjeta acotada, un plato de nombre largo la
                    // estiraria hacia abajo hasta comerse la pantalla.
                    maxLines = lineas
                    ellipsize = TextUtils.TruncateAt.END
                }

                fun abrirCocina() {
                    quitarCartel(ctx)
                    ctx.startActivity(
                        Intent(ctx, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                }

                // Tarjeta centrada, no pegada arriba: ahi chocaba con la propia
                // notificacion emergente y el cartel quedaba medio tapado.
                val tarjeta = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Ui.forma(p.fondo, dp(20f).toFloat())
                    elevation = dp(16f).toFloat()
                    setPadding(dp(24f), dp(24f), dp(24f), dp(20f))
                    addView(
                        Ui.chip(ctx, p, ctx.getString(R.string.cartel_encabezado), lleno = true)
                            .apply { letterSpacing = 0.08f },
                        LinearLayout.LayoutParams(-2, -2),
                    )
                    addView(
                        linea(titulo, 28f, p.texto, medio = true, lineas = 2),
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) },
                    )
                    addView(
                        linea(cuerpo, 16f, p.secundario, lineas = 2),
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) },
                    )
                    // Lo que se va a cocinar. El cajero decide con esto si le
                    // corre prisa, sin tener que abrir la cocina.
                    if (detalle.isNotEmpty()) {
                        addView(View(ctx).apply {
                            setBackgroundColor(p.linea)
                            layoutParams = LinearLayout.LayoutParams(-1, maxOf(1, dp(1f)))
                                .apply { topMargin = dp(16f) }
                        })
                        detalle.forEachIndexed { i, d ->
                            // La sangria inicial es la señal de "esto va debajo
                            // del plato": extras, nota y "y N mas".
                            val sangrada = d.startsWith(" ")
                            val arriba = dp(if (i == 0) 12f else if (sangrada) 2f else 8f)
                            if (sangrada) {
                                addView(
                                    linea(d.trim(), 14f, p.secundario),
                                    LinearLayout.LayoutParams(-1, -2).apply {
                                        topMargin = arriba
                                        marginStart = dp(36f)
                                    },
                                )
                            } else {
                                // "2  Tacos de pollo": la cantidad en su columna.
                                val corte = d.indexOf("  ")
                                val cant = if (corte in 1..4) d.substring(0, corte) else ""
                                val nombre = if (cant.isNotEmpty()) d.substring(corte).trim() else d
                                addView(
                                    LinearLayout(ctx).apply {
                                        orientation = LinearLayout.HORIZONTAL
                                        addView(
                                            linea(cant, 18f, p.texto, medio = true),
                                            LinearLayout.LayoutParams(dp(36f), -2),
                                        )
                                        addView(
                                            linea(nombre, 18f, p.texto, medio = true),
                                            LinearLayout.LayoutParams(0, -2, 1f),
                                        )
                                    },
                                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = arriba },
                                )
                            }
                        }
                    }
                    addView(
                        LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            addView(
                                Ui.boton(ctx, p, ctx.getString(R.string.cartel_cerrar), Ui.Tipo.SECUNDARIO) {
                                    quitarCartel(ctx)
                                },
                                LinearLayout.LayoutParams(0, -2, 1f),
                            )
                            addView(
                                Ui.boton(ctx, p, ctx.getString(R.string.cartel_abrir)) { abrirCocina() },
                                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12f) },
                            )
                        },
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20f) },
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
                tarjeta.setOnClickListener { abrirCocina() }

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
