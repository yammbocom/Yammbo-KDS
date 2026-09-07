package com.yammbo.kds

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/**
 * La termica, por donde este conectada. Tres caminos porque una cocina real
 * usa los tres:
 *
 *  - **bt**  Bluetooth SPP. Las portatiles baratas. Se empareja UNA vez en los
 *            ajustes de Android y aqui solo se elige de la lista.
 *  - **red** TCP al puerto 9100 (el crudo de casi todas las de red). Es el mas
 *            fiable para una cocina fija: la impresora cuelga del router y le
 *            imprime cualquiera, sin emparejar ni depender de una tablet.
 *  - **usb** Cable directo con OTG.
 *
 * Un solo hilo para imprimir, sea cual sea el transporte. Con dos comandas en
 * el mismo tick de 5 s y la impresion automatica puesta, dos hilos abrian a la
 * vez la MISMA impresora: el segundo fallaba y ese ticket se perdia, con el
 * unico aviso en un Toast de una pantalla que esta detras de Loyverse.
 */
object Impresora {
    private const val TAG = "YammboKDS"
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val cola = Executors.newSingleThreadExecutor()
    const val ACCION_USB = "com.yammbo.kds.USB"

    class SinImpresora(msg: String) : Exception(msg)

    // ---- Bluetooth: descubrimiento -----------------------------------------

    @SuppressLint("MissingPermission")
    fun emparejadas(ctx: Context): List<Pair<String, String>> {
        val ad = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return runCatching { ad.bondedDevices.map { (it.name ?: it.address) to it.address } }
            .getOrDefault(emptyList())
    }

    /**
     * Falta el permiso para siquiera leer la lista? Se distingue de "no hay
     * ninguna emparejada", que es un problema completamente distinto.
     */
    @SuppressLint("MissingPermission")
    fun puedeVerDispositivos(ctx: Context): Boolean {
        val ad = BluetoothAdapter.getDefaultAdapter() ?: return false
        return runCatching { ad.bondedDevices; true }.getOrDefault(false)
    }

    // ---- USB ---------------------------------------------------------------

    /** La primera que se anuncie como impresora (clase 7). */
    fun usbImpresora(ctx: Context): UsbDevice? {
        val um = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        for (d in um.deviceList.values) {
            for (i in 0 until d.interfaceCount) {
                if (d.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_PRINTER) return d
            }
        }
        return null
    }

    fun usbTienePermiso(ctx: Context): Boolean {
        val um = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val d = usbImpresora(ctx) ?: return false
        return um.hasPermission(d)
    }

    /** Lanza el dialogo del sistema. Se llama desde Ajustes, que tiene UI. */
    fun usbPedirPermiso(ctx: Context) {
        val um = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val d = usbImpresora(ctx) ?: return
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ACCION_USB).setPackage(ctx.packageName), flags
        )
        um.requestPermission(d, pi)
    }

    @Throws(Exception::class)
    private fun enviarUsb(ctx: Context, datos: ByteArray) {
        val um = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: throw SinImpresora("Esta tablet no tiene USB")
        val dev = usbImpresora(ctx)
            ?: throw SinImpresora("No veo ninguna impresora USB conectada")
        if (!um.hasPermission(dev))
            throw SinImpresora("Falta dar permiso al USB: entra en Ajustes y prueba de nuevo")

        var iface = dev.getInterface(0)
        for (i in 0 until dev.interfaceCount) {
            val cand = dev.getInterface(i)
            if (cand.interfaceClass == UsbConstants.USB_CLASS_PRINTER) { iface = cand; break }
        }
        var salida = -1
        for (e in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(e)
            if (ep.direction == UsbConstants.USB_DIR_OUT &&
                ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK
            ) { salida = e; break }
        }
        if (salida < 0) throw SinImpresora("Esa impresora USB no acepta datos")

        val con = um.openDevice(dev) ?: throw SinImpresora("No se pudo abrir el USB")
        try {
            if (!con.claimInterface(iface, true)) throw SinImpresora("El USB esta ocupado")
            val ep = iface.getEndpoint(salida)
            var i = 0
            while (i < datos.size) {
                val n = minOf(1024, datos.size - i)
                val trozo = datos.copyOfRange(i, i + n)
                if (con.bulkTransfer(ep, trozo, trozo.size, 5000) < 0)
                    throw SinImpresora("La impresora USB corto la comunicacion")
                i += n
            }
        } finally {
            runCatching { con.releaseInterface(iface) }
            runCatching { con.close() }
        }
    }

    // ---- Red ---------------------------------------------------------------

    @Throws(Exception::class)
    private fun enviarRed(ip: String, puerto: Int, datos: ByteArray) {
        if (ip.isBlank()) throw SinImpresora("Falta la IP de la impresora")
        val s = Socket()
        try {
            s.connect(InetSocketAddress(ip, puerto), 6000)
            s.soTimeout = 6000
            val out = s.getOutputStream()
            out.write(datos)
            out.flush()
            Thread.sleep(120)
        } finally {
            runCatching { s.close() }
        }
    }

    // ---- Bluetooth: envio --------------------------------------------------

    /**
     * Cada impresion abre y cierra el socket. Es mas lento que mantenerlo
     * vivo, pero una termica de cocina se apaga, se queda sin papel y se va de
     * rango todo el rato; un socket cacheado se queda medio muerto y el ticket
     * se pierde en silencio, que es el peor final posible.
     */
    @SuppressLint("MissingPermission")
    @Throws(Exception::class)
    private fun enviarBt(mac: String, datos: ByteArray) {
        if (mac.isBlank()) throw SinImpresora("No hay impresora elegida")
        val ad = BluetoothAdapter.getDefaultAdapter()
            ?: throw SinImpresora("Esta tablet no tiene Bluetooth")
        if (!ad.isEnabled) throw SinImpresora("El Bluetooth esta apagado")

        val dev = ad.getRemoteDevice(mac)
        var sock: BluetoothSocket? = null
        var out: OutputStream? = null
        try {
            sock = dev.createRfcommSocketToServiceRecord(SPP)
            runCatching { ad.cancelDiscovery() }
            sock.connect()
            out = sock.outputStream
            // A trozos: algunas termicas baratas pierden bytes si se les manda
            // el ticket entero de golpe.
            var i = 0
            while (i < datos.size) {
                val n = minOf(256, datos.size - i)
                out.write(datos, i, n)
                out.flush()
                i += n
                Thread.sleep(20)
            }
            Thread.sleep(120)   // que termine de quemar antes de cerrar
        } finally {
            runCatching { out?.close() }
            runCatching { sock?.close() }
        }
    }

    // ---- Entrada unica -----------------------------------------------------

    @Throws(Exception::class)
    fun enviar(ctx: Context, prefs: Prefs, datos: ByteArray) {
        when (prefs.conexion) {
            "red" -> enviarRed(prefs.ip, prefs.puerto, datos)
            "usb" -> enviarUsb(ctx, datos)
            else -> enviarBt(prefs.impresora, datos)
        }
        Log.i(TAG, "ticket enviado por " + prefs.conexion)
    }

    /** Encola un ticket. [aviso] solo se llama si acaba mal. */
    fun encolar(ctx: Context, json: String, aviso: (String) -> Unit) {
        val app = ctx.applicationContext
        cola.execute {
            val prefs = Prefs(app)
            val r = runCatching {
                val datos = EscPos.render(Ticket.de(json), prefs.ancho)
                try {
                    enviar(app, prefs, datos)
                } catch (e: Exception) {
                    // Una termica se va de rango y vuelve. Un reintento cubre
                    // el caso comun sin convertir esto en un bucle.
                    Thread.sleep(1200)
                    enviar(app, prefs, datos)
                }
            }
            r.exceptionOrNull()?.let {
                Log.w(TAG, "no se imprimio: " + it.message)
                aviso("No se pudo imprimir: " + (it.message ?: "error"))
            }
        }
    }
}
