package com.yammbo.kds

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Todo lo que se ve, en un solo sitio: colores, medidas, letra y las piezas
 * con las que se arman las pantallas. Ninguna pantalla escribe un color a mano.
 *
 * B/N estricto, la misma familia que Yammbo Delivery. Los estados se dicen con
 * peso, relleno e inversion, NUNCA con color: un chip relleno es "bien /
 * conectado", uno solo con borde es "esperando / sin conexion", y una tarjeta
 * invertida es "esto pide atencion".
 *
 * 🚨 Los grises son neutros de verdad (R = G = B). Un gris azulado se cuela
 * solo en cuanto se copia una paleta de otro sitio.
 */
object Ui {

    class Paleta(
        val fondo: Int,
        val texto: Int,
        val secundario: Int,
        val linea: Int,
        val superficie: Int,
        /** Onda al pulsar: texto con poca opacidad, sin tono. */
        val pulsado: Int,
        val oscura: Boolean,
    ) {
        /** La de enfrente: la que se usa para lo que pide atencion. */
        fun invertida(): Paleta = if (oscura) CLARA else OSCURA
    }

    val CLARA = Paleta(
        fondo = 0xFFFFFFFF.toInt(),
        texto = 0xFF0B0B0B.toInt(),
        secundario = 0xFF5F5F5F.toInt(),
        linea = 0xFFE6E6E6.toInt(),
        superficie = 0xFFF4F4F4.toInt(),
        pulsado = 0x1F000000,
        oscura = false,
    )

    val OSCURA = Paleta(
        fondo = 0xFF0B0B0B.toInt(),
        texto = 0xFFF2F2F2.toInt(),
        secundario = 0xFFA3A3A3.toInt(),
        linea = 0xFF262626.toInt(),
        superficie = 0xFF161616.toInt(),
        pulsado = 0x33FFFFFF,
        oscura = true,
    )

    /** Sigue al sistema: claro de dia, oscuro si el aparato esta en modo noche. */
    fun paleta(ctx: Context): Paleta {
        val modo = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (modo == Configuration.UI_MODE_NIGHT_YES) OSCURA else CLARA
    }

    // ---- Medidas -----------------------------------------------------------

    fun dp(ctx: Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /** sp a px: crece con el tamaño de letra del sistema. */
    fun sp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics).toInt()

    /** Una raya fina que no desaparezca en una pantalla de baja densidad. */
    private fun fino(ctx: Context): Int = maxOf(1, dp(ctx, 1f))

    // ---- Letra -------------------------------------------------------------

    private val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val MEDIA: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    enum class Estilo(val sp: Float, val medio: Boolean) {
        TITULO(28f, true),
        SECCION(20f, true),
        CUERPO(16f, false),
        CUERPO_MEDIO(16f, true),
        SECUNDARIO(14f, false),
        SECUNDARIO_MEDIO(14f, true),
        CAPTION(12f, false),
        ETIQUETA(12f, true),
    }

    fun texto(ctx: Context, t: CharSequence, e: Estilo, color: Int): TextView =
        texto(ctx, t, e.sp, e.medio, color)

    fun texto(ctx: Context, t: CharSequence, sp: Float, medio: Boolean, color: Int): TextView =
        TextView(ctx).apply {
            text = t
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            typeface = if (medio) MEDIA else REGULAR
        }

    // ---- Fondos ------------------------------------------------------------

    fun forma(color: Int, radio: Float, trazo: Int = 0, colorTrazo: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radio
            if (trazo > 0) setStroke(trazo, colorTrazo)
        }

    /** Onda al pulsar, recortada a la forma. */
    fun pulsable(p: Paleta, contenido: Drawable?, radio: Float): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(p.pulsado), contenido, forma(Color.WHITE, radio))

    /** Apagado se ve apagado: medio transparente y sin responder. */
    fun habilitar(v: View, si: Boolean) {
        v.isEnabled = si
        v.alpha = if (si) 1f else 0.4f
    }

    // ---- Botones -----------------------------------------------------------

    enum class Tipo { PRIMARIO, SECUNDARIO, TERCIARIO }

    /**
     * 52 dp de alto y radio 14. Primario = relleno del color del texto;
     * secundario = borde de 1,5 dp; terciario = solo texto.
     */
    fun boton(
        ctx: Context,
        p: Paleta,
        t: CharSequence,
        tipo: Tipo = Tipo.PRIMARIO,
        alPulsar: () -> Unit,
    ): TextView = TextView(ctx).apply {
        text = t
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = MEDIA
        isAllCaps = false
        minHeight = dp(ctx, 52f)
        minimumHeight = dp(ctx, 52f)
        minWidth = dp(ctx, 48f)
        setPadding(dp(ctx, 20f), dp(ctx, 12f), dp(ctx, 20f), dp(ctx, 12f))
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        val r = dp(ctx, 14f).toFloat()
        when (tipo) {
            Tipo.PRIMARIO -> {
                setTextColor(p.fondo)
                background = pulsable(p.invertida(), forma(p.texto, r), r)
            }
            Tipo.SECUNDARIO -> {
                setTextColor(p.texto)
                background = pulsable(
                    p, forma(Color.TRANSPARENT, r, dp(ctx, 1.5f), p.texto), r)
            }
            Tipo.TERCIARIO -> {
                setTextColor(p.texto)
                background = pulsable(p, null, r)
            }
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { alPulsar() }
    }

    /** Boton de solo icono: 48 dp de diana y SIEMPRE con descripcion. */
    fun botonIcono(
        ctx: Context,
        p: Paleta,
        icono: Int,
        descripcion: String,
        alPulsar: () -> Unit,
    ): ImageView = ImageView(ctx).apply {
        setImageResource(icono)
        imageTintList = ColorStateList.valueOf(p.texto)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val m = dp(ctx, 12f)
        setPadding(m, m, m, m)
        minimumWidth = dp(ctx, 48f)
        minimumHeight = dp(ctx, 48f)
        contentDescription = descripcion
        background = pulsable(p, null, dp(ctx, 24f).toFloat())
        isClickable = true
        isFocusable = true
        setOnClickListener { alPulsar() }
    }

    // ---- Piezas ------------------------------------------------------------

    /** Chip de estado. Relleno = bien; solo borde = esperando o sin conexion. */
    fun chip(ctx: Context, p: Paleta, t: CharSequence, lleno: Boolean): TextView =
        TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = MEDIA
            gravity = Gravity.CENTER
            maxLines = 1
            minHeight = dp(ctx, 28f)
            setPadding(dp(ctx, 12f), dp(ctx, 4f), dp(ctx, 12f), dp(ctx, 4f))
            pintarChip(this, p, t, lleno)
        }

    fun pintarChip(chip: TextView, p: Paleta, t: CharSequence, lleno: Boolean) {
        val ctx = chip.context
        val r = dp(ctx, 14f).toFloat()
        chip.text = t
        if (lleno) {
            chip.setTextColor(p.fondo)
            chip.background = forma(p.texto, r)
        } else {
            chip.setTextColor(p.texto)
            chip.background = forma(Color.TRANSPARENT, r, dp(ctx, 1.5f), p.texto)
        }
    }

    /** Un icono vectorial teñido. Sin descripcion = decorativo. */
    fun icono(ctx: Context, res: Int, color: Int, descripcion: String? = null): ImageView =
        ImageView(ctx).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(color)
            if (descripcion == null) importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            else contentDescription = descripcion
        }

    /**
     * El logo: el glifo de la pantalla con tres columnas sobre una baldosa. Es
     * el mismo dibujo que el icono de la app y el de la barra de estado.
     */
    fun logo(ctx: Context, p: Paleta, tam: Float): View = FrameLayout(ctx).apply {
        background = forma(p.texto, dp(ctx, tam * 0.24f).toFloat())
        val m = dp(ctx, tam * 0.1f)
        addView(
            icono(ctx, R.drawable.ic_noti, p.fondo),
            FrameLayout.LayoutParams(-1, -1).apply { setMargins(m, m, m, m) },
        )
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** Barra de arriba: logo, titulo y una accion opcional a la derecha. */
    fun barraSuperior(ctx: Context, p: Paleta, titulo: String, accion: View? = null): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 64f)
            setPadding(dp(ctx, 20f), dp(ctx, 8f), dp(ctx, if (accion != null) 8f else 20f), dp(ctx, 8f))
            addView(logo(ctx, p, 32f), LinearLayout.LayoutParams(dp(ctx, 32f), dp(ctx, 32f)))
            addView(
                texto(ctx, titulo, Estilo.SECCION, p.texto).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    ViewCompat.setAccessibilityHeading(this, true)
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(ctx, 12f) },
            )
            if (accion != null) {
                addView(accion, LinearLayout.LayoutParams(dp(ctx, 48f), dp(ctx, 48f)))
            }
        }

    fun cabecera(ctx: Context, p: Paleta, t: String): TextView =
        texto(ctx, t, Estilo.SECCION, p.texto).apply {
            setPadding(dp(ctx, 4f), dp(ctx, 32f), dp(ctx, 4f), dp(ctx, 12f))
            ViewCompat.setAccessibilityHeading(this, true)
        }

    /** Tarjeta: superficie, radio 16 y lo de dentro recortado a la curva. */
    fun tarjeta(ctx: Context, p: Paleta): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = forma(p.superficie, dp(ctx, 16f).toFloat())
        clipToOutline = true
    }

    fun separador(ctx: Context, p: Paleta, sangria: Float = 16f): View = View(ctx).apply {
        setBackgroundColor(p.linea)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(-1, fino(ctx)).apply {
            marginStart = dp(ctx, sangria)
        }
    }

    class Fila(val vista: LinearLayout, val titulo: TextView, val subtitulo: TextView) {
        fun subtitulo(t: CharSequence?) {
            subtitulo.text = t ?: ""
            subtitulo.visibility = if (t.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    /** Fila de ajustes: titulo, subtitulo y algo a la derecha (interruptor, valor, chip). */
    fun fila(
        ctx: Context,
        p: Paleta,
        titulo: String,
        subtitulo: CharSequence? = null,
        final: View? = null,
        alPulsar: (() -> Unit)? = null,
    ): Fila {
        val t = texto(ctx, titulo, Estilo.CUERPO, p.texto)
        val s = texto(ctx, "", Estilo.SECUNDARIO, p.secundario).apply {
            setPadding(0, dp(ctx, 2f), 0, 0)
        }
        val textos = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(t)
            addView(s)
        }
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 64f)
            setPadding(dp(ctx, 16f), dp(ctx, 12f), dp(ctx, 16f), dp(ctx, 12f))
            addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
            if (final != null) {
                addView(final, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(ctx, 16f) })
            }
            if (alPulsar != null) {
                background = pulsable(p, null, 0f)
                isClickable = true
                isFocusable = true
                setOnClickListener { alPulsar() }
            }
        }
        return Fila(v, t, s).also { it.subtitulo(subtitulo) }
    }

    class Valor(val vista: LinearLayout, val texto: TextView)

    /** El valor elegido y el chevron de "toca para cambiarlo", dibujado en vector. */
    fun valor(ctx: Context, p: Paleta, t: CharSequence): Valor {
        val tv = texto(ctx, t, Estilo.SECUNDARIO, p.secundario).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(ctx, 220f)
        }
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tv)
            addView(
                chevron(ctx, p),
                LinearLayout.LayoutParams(dp(ctx, 24f), dp(ctx, 24f)).apply {
                    marginStart = dp(ctx, 4f)
                },
            )
        }
        return Valor(v, tv)
    }

    fun chevron(ctx: Context, p: Paleta): ImageView = icono(ctx, R.drawable.ic_chevron, p.secundario)

    /** Interruptor en B/N: encendido = pulgar del color del texto. */
    fun interruptor(ctx: Context, p: Paleta, encendido: Boolean, cambio: (Boolean) -> Unit): SwitchCompat =
        SwitchCompat(ctx).apply {
            isChecked = encendido
            val estados = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(
                estados, intArrayOf(p.texto, if (p.oscura) p.secundario else p.fondo))
            trackTintList = ColorStateList(estados, intArrayOf(p.texto, p.secundario))
            setOnCheckedChangeListener { _, c -> cambio(c) }
        }

    /** Campo de texto: superficie con raya fina; con el foco, borde del color del texto. */
    fun campo(ctx: Context, p: Paleta, pista: String, tipo: Int): AppCompatEditText =
        AppCompatEditText(ctx).apply {
            hint = pista
            setTextColor(p.texto)
            setHintTextColor(p.secundario)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = REGULAR
            inputType = tipo
            setSingleLine(true)
            minHeight = dp(ctx, 52f)
            setPadding(dp(ctx, 16f), dp(ctx, 12f), dp(ctx, 16f), dp(ctx, 12f))
            val r = dp(ctx, 12f).toFloat()
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_focused),
                    forma(p.fondo, r, dp(ctx, 1.5f), p.texto),
                )
                addState(intArrayOf(), forma(p.superficie, r, fino(ctx), p.linea))
            }
        }

    // ---- Pantallas enteras -------------------------------------------------

    /**
     * Una columna que no pasa de [maxPx]: en una tablet en horizontal un
     * formulario de borde a borde no se lee de un vistazo.
     */
    @SuppressLint("ViewConstructor")
    class Columna(ctx: Context, private val maxPx: Int) : LinearLayout(ctx) {
        init { orientation = VERTICAL }

        override fun onMeasure(ancho: Int, alto: Int) {
            val modo = MeasureSpec.getMode(ancho)
            val tam = MeasureSpec.getSize(ancho)
            val a = if (modo != MeasureSpec.UNSPECIFIED && tam > maxPx)
                MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.EXACTLY) else ancho
            super.onMeasure(a, alto)
        }
    }

    /** Una pantalla que se desplaza si no cabe y se centra si sobra sitio. */
    fun pantalla(ctx: Context, p: Paleta, contenido: View, gravedad: Int = Gravity.CENTER): ScrollView =
        ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(p.fondo)
            addView(
                FrameLayout(ctx).apply {
                    addView(contenido, FrameLayout.LayoutParams(-1, -2, gravedad))
                },
                ViewGroup.LayoutParams(-1, -1),
            )
        }

    /** El dibujo de una pantalla de paso o de estado: glifo en un circulo. */
    private fun ilustracion(ctx: Context, p: Paleta, glifo: Int): View = FrameLayout(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(p.superficie)
        }
        addView(
            icono(ctx, glifo, p.texto),
            FrameLayout.LayoutParams(dp(ctx, 56f), dp(ctx, 56f), Gravity.CENTER),
        )
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** "Paso 2 de 4" en rayas: las hechas y la actual rellenas. */
    private fun segmentos(ctx: Context, p: Paleta, actual: Int, total: Int): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            for (i in 1..total) {
                addView(
                    View(ctx).apply {
                        background = forma(
                            if (i <= actual) p.texto else p.linea, dp(ctx, 2f).toFloat())
                    },
                    LinearLayout.LayoutParams(0, -1, 1f).apply {
                        if (i > 1) marginStart = dp(ctx, 6f)
                    },
                )
            }
        }

    /**
     * Pantalla centrada con glifo, titulo, una linea de razon y botones. La
     * usan los pasos de permisos y los errores a pantalla completa.
     */
    fun estado(
        ctx: Context,
        p: Paleta,
        glifo: Int,
        titulo: String,
        razon: String,
        nota: String? = null,
        botones: List<View> = emptyList(),
        contador: Pair<Int, Int>? = null,
    ): ScrollView {
        val col = Columna(ctx, dp(ctx, 480f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(ctx, 24f), dp(ctx, 32f), dp(ctx, 24f), dp(ctx, 32f))
        }
        if (contador != null) {
            val (n, total) = contador
            col.addView(
                texto(ctx, ctx.getString(R.string.paso_contador, n, total), Estilo.ETIQUETA, p.secundario)
                    .apply { letterSpacing = 0.06f },
            )
            col.addView(
                segmentos(ctx, p, n, total),
                LinearLayout.LayoutParams(dp(ctx, minOf(total * 44, 220).toFloat()), dp(ctx, 4f))
                    .apply { topMargin = dp(ctx, 12f) },
            )
        }
        col.addView(
            ilustracion(ctx, p, glifo),
            LinearLayout.LayoutParams(dp(ctx, 112f), dp(ctx, 112f)).apply {
                topMargin = dp(ctx, if (contador != null) 40f else 0f)
            },
        )
        col.addView(
            texto(ctx, titulo, Estilo.TITULO, p.texto).apply {
                gravity = Gravity.CENTER
                ViewCompat.setAccessibilityHeading(this, true)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, 32f) },
        )
        col.addView(
            texto(ctx, razon, Estilo.CUERPO, p.secundario).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, 12f) },
        )
        if (!nota.isNullOrEmpty()) {
            col.addView(
                texto(ctx, nota, Estilo.CAPTION, p.secundario).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, 8f) },
            )
        }
        botones.forEachIndexed { i, b ->
            col.addView(
                b,
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(ctx, if (i == 0) 40f else 8f)
                },
            )
        }
        return pantalla(ctx, p, col)
    }

    /** Pantalla de paso: contador, glifo, titulo, razon, boton y "Ahora no". */
    fun paso(
        ctx: Context,
        p: Paleta,
        glifo: Int,
        contador: Pair<Int, Int>,
        titulo: String,
        razon: String,
        nota: String?,
        boton: String,
        alPulsar: () -> Unit,
        ahoraNo: (() -> Unit)?,
    ): ScrollView {
        val botones = ArrayList<View>(2)
        botones.add(boton(ctx, p, boton, Tipo.PRIMARIO, alPulsar))
        if (ahoraNo != null) {
            botones.add(boton(ctx, p, ctx.getString(R.string.ahora_no), Tipo.TERCIARIO, ahoraNo))
        }
        return estado(ctx, p, glifo, titulo, razon, nota, botones, contador)
    }

    // ---- Ventana -----------------------------------------------------------

    /**
     * De borde a borde y con los iconos de las barras en el tono correcto.
     * targetSdk 36 lo impone; se hace igual en todas las versiones para que
     * nada dependa de en que Android caiga la tablet.
     */
    @Suppress("DEPRECATION")
    fun barras(a: Activity, p: Paleta) {
        val w = a.window
        WindowCompat.setDecorFitsSystemWindows(w, false)
        w.statusBarColor = Color.TRANSPARENT
        // Antes de la 8.0 los iconos de la barra de navegacion no pueden ir en
        // oscuro: ahi se deja la barra negra del sistema.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) w.navigationBarColor = Color.TRANSPARENT
        w.setBackgroundDrawable(ColorDrawable(p.fondo))
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val c = WindowInsetsControllerCompat(w, w.decorView)
        c.isAppearanceLightStatusBars = !p.oscura
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.isAppearanceLightNavigationBars = !p.oscura
    }

    /**
     * Reserva el hueco de las barras del sistema (y del teclado) en [v]. Sin
     * esto, con targetSdk 35+ el titulo queda debajo del reloj.
     */
    fun rellenarBarras(v: View) {
        ViewCompat.setOnApplyWindowInsetsListener(v) { vista, ventana ->
            val b = ventana.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val teclado = ventana.getInsets(WindowInsetsCompat.Type.ime()).bottom
            vista.setPadding(b.left, b.top, b.right, maxOf(b.bottom, teclado))
            ventana
        }
        ViewCompat.requestApplyInsets(v)
    }
}
