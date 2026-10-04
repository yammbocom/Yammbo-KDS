package com.yammbo.kds

/** Los permisos que la pantalla pide la primera vez, uno por pantalla. */
enum class Paso { AVISOS, ENCIMA }

/**
 * El orden de los pasos de permisos, aparte de Android para poder probarlo.
 *
 * 🚨 Un paso que ya se respondio NO se vuelve a pedir en la misma sesion,
 * se haya concedido o no. Si Android ya no enseña el dialogo (denegado dos
 * veces), la respuesta llega al instante y sin esta regla la pantalla volveria
 * a pedirlo en bucle. Por eso cada paso se pide solo al tocar su boton, y en
 * cuanto responde pasa a [siguiente] marcado como hecho.
 */
object Pasos {

    /**
     * Lo que falta, en orden. Se calcula UNA vez al empezar, para que el
     * contador "Paso 1 de 2" no cambie de total a mitad de camino.
     *
     * Solo al vincular por primera vez ([primeraVez]). Una tablet ya vinculada
     * que arranca (tras un corte de luz, por ejemplo) va directa a la cocina
     * aunque falte un permiso: una pantalla de permisos colgada en la pared
     * es una cocina sin comandas. Ajustes enseña lo que falta.
     */
    fun pendientes(
        primeraVez: Boolean,
        sdk: Int,
        avisosConcedidos: Boolean,
        quiereEncima: Boolean,
        encimaConcedido: Boolean,
    ): List<Paso> {
        if (!primeraVez) return emptyList()
        val out = ArrayList<Paso>(2)
        // El permiso de notificaciones existe desde Android 13.
        if (sdk >= 33 && !avisosConcedidos) out.add(Paso.AVISOS)
        if (quiereEncima && !encimaConcedido) out.add(Paso.ENCIMA)
        return out
    }

    /** El siguiente que queda: ni respondido ni concedido entretanto. */
    fun siguiente(plan: List<Paso>, hechos: Set<Paso>, concedidos: Set<Paso>): Paso? =
        plan.firstOrNull { it !in hechos && it !in concedidos }

    /** "Paso n de total". */
    fun contador(plan: List<Paso>, paso: Paso): Pair<Int, Int> =
        (plan.indexOf(paso) + 1).coerceAtLeast(1) to plan.size.coerceAtLeast(1)
}
