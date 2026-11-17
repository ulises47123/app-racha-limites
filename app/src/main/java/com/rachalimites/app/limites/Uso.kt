package com.rachalimites.app.limites

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId

/** Tiempo en primer plano de cada app desde las 00:00 de hoy (a partir de los eventos del sistema). */
object Uso {

    fun tienePermiso(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    /** Milisegundos de uso de hoy por paquete. */
    fun hoy(c: Context): Map<String, Long> {
        val usm = c.getSystemService(UsageStatsManager::class.java)
        val desde = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val ahora = System.currentTimeMillis()
        val eventos = usm.queryEvents(desde, ahora) ?: return emptyMap()

        val total = HashMap<String, Long>()
        val abiertas = HashMap<String, MutableSet<String>>() // paquete -> actividades en primer plano
        val inicio = HashMap<String, Long>()
        val vistos = HashSet<String>()
        val e = UsageEvents.Event()

        fun sumar(pkg: String, ms: Long) { if (ms > 0) total[pkg] = (total[pkg] ?: 0L) + ms }

        while (eventos.hasNextEvent()) {
            eventos.getNextEvent(e)
            val pkg = e.packageName ?: continue
            val clase = e.className ?: ""
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val set = abiertas.getOrPut(pkg) { HashSet() }
                    if (set.isEmpty()) inicio[pkg] = e.timeStamp
                    set += clase
                    vistos += pkg
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val set = abiertas[pkg]
                    if (set != null && set.remove(clase)) {
                        if (set.isEmpty()) inicio.remove(pkg)?.let { sumar(pkg, e.timeStamp - it) }
                    } else if (pkg !in vistos && e.eventType == UsageEvents.Event.ACTIVITY_PAUSED) {
                        // Estaba abierta desde antes de las 00:00.
                        sumar(pkg, e.timeStamp - desde)
                    }
                    vistos += pkg
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, 26 /* DEVICE_SHUTDOWN */ -> {
                    // Pantalla apagada: cierra todo lo que figure abierto.
                    for ((p, t) in inicio) sumar(p, e.timeStamp - t)
                    inicio.clear(); abiertas.clear()
                }
            }
        }
        for ((p, t) in inicio) sumar(p, ahora - t)
        return total
    }
}
