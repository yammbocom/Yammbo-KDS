package com.yammbo.kds

import android.content.Context
import android.os.Build

/**
 * Variante Google Play: sin autoactualizador. Play se encarga de las
 * actualizaciones, asi que no hay red, ni FileProvider, ni instalador.
 * Mantiene la misma API que la variante `direct` para que el codigo comun compile.
 */
object Actualizador {

    data class Version(val code: Int, val name: String, val url: String, val notas: String)

    fun ultima(): Version? = null

    fun hayNueva(ctx: Context, v: Version): Boolean = false

    fun tocaMirar(ctx: Context): Boolean = false

    fun puedeInstalar(ctx: Context): Boolean = false

    fun pedirPermisoInstalar(ctx: Context) {}

    fun descargarEInstalar(ctx: Context, v: Version): String? = null

    fun instalada(ctx: Context): Int = runCatching {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.longVersionCode.toInt()
        else @Suppress("DEPRECATION") p.versionCode
    }.getOrDefault(0)

    fun nombreInstalado(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
