package com.rachalimites.app.servicio

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.rachalimites.app.datos.Ajustes
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Alarmas exactas: envío diario de la racha, reintentos y el reinicio de límites a medianoche. */
object Programador {
    private const val TAG = "Racha/Programador"
    const val ACCION_RACHA = "com.rachalimites.app.RACHA"
    const val ACCION_MEDIANOCHE = "com.rachalimites.app.MEDIANOCHE"

    private fun pi(c: Context, accion: String, codigo: Int) = PendingIntent.getBroadcast(
        c, codigo, Intent(c, AlarmaReceiver::class.java).setAction(accion),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun poner(c: Context, cuando: LocalDateTime, accion: String, codigo: Int) {
        val am = c.getSystemService(AlarmManager::class.java)
        val ms = cuando.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val p = pi(c, accion, codigo)
        val exacta = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exacta) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
        Log.i(TAG, "$accion programada para $cuando (exacta=$exacta)")
    }

    /** Próximo envío de la racha a la hora configurada (hoy si todavía no pasó y falta hacerla). */
    fun programarRacha(c: Context) {
        if (!Ajustes.rachaActiva) { c.getSystemService(AlarmManager::class.java).cancel(pi(c, ACCION_RACHA, 1)); return }
        val hora = LocalTime.of(Ajustes.rachaHora, Ajustes.rachaMinuto)
        val hoy = LocalDate.now().atTime(hora)
        val cuando = if (hoy.isAfter(LocalDateTime.now()) && !Ajustes.rachaHechaHoy()) hoy else LocalDate.now().plusDays(1).atTime(hora)
        poner(c, cuando, ACCION_RACHA, 1)
    }

    /**
     * Reintento dentro de [minutos]. Nunca después de las 23:50: pasado eso, la racha de hoy ya no se
     * puede salvar y se programa la de mañana.
     */
    fun reintentarRacha(c: Context, minutos: Long): Boolean {
        val cuando = LocalDateTime.now().plusMinutes(minutos)
        val limite = LocalDate.now().atTime(23, 50)
        if (cuando.isAfter(limite) || cuando.toLocalDate() != LocalDate.now()) {
            val ultimo = LocalDate.now().atTime(23, 45)
            if (LocalDateTime.now().isBefore(ultimo.minusMinutes(1))) { poner(c, ultimo, ACCION_RACHA, 1); return true }
            programarRacha(c); return false
        }
        poner(c, cuando, ACCION_RACHA, 1)
        return true
    }

    fun programarMedianoche(c: Context) {
        poner(c, LocalDate.now().plusDays(1).atTime(0, 0, 5), ACCION_MEDIANOCHE, 2)
    }
}
