package com.yammbo.kds

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** La tablet de cocina esta colgada de la pared: tras un corte de luz tiene
 *  que volver sola, sin que nadie se suba a una silla a abrir la app. */
class ArranqueReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs(ctx).configurada) return
        ServicioKds.arrancar(ctx)
    }
}
