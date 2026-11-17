package com.rachalimites.app.racha

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.rachalimites.app.datos.Ajustes
import com.rachalimites.app.servicio.Notificaciones
import com.rachalimites.app.servicio.Programador
import java.time.LocalDate
import java.time.LocalTime

/** Servicio breve en primer plano que ejecuta [MotorRacha] y decide si hay que reintentar. */
class RachaService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val forzar = intent?.getBooleanExtra(EXTRA_FORZAR, false) == true
        val n = Notificaciones.base(this, Notificaciones.CANAL_RACHA, "Racha de TikTok", "Enviando el mensaje a ${Ajustes.rachaContacto}…")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(Notificaciones.ID_RACHA_EN_CURSO, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        else startForeground(Notificaciones.ID_RACHA_EN_CURSO, n)

        if (!forzar && (!Ajustes.rachaActiva || Ajustes.rachaHechaHoy())) {
            Programador.programarRacha(this); terminar(); return START_NOT_STICKY
        }

        Thread {
            val wl = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RachaYLimites:racha").apply { acquire(3 * 60_000L) }
            try {
                procesar(MotorRacha.ejecutar(forzar))
            } catch (e: Exception) {
                Log.e(TAG, "falló", e)
                procesar(Resultado.Fallo("Error: ${e.message}"))
            } finally {
                runCatching { wl.release() }
                terminar()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun procesar(r: Resultado) {
        val hora = LocalTime.now().toString().take(5)
        Ajustes.rachaUltimoResultado = "$hora · ${r.texto}"
        when (r) {
            is Resultado.Enviado, is Resultado.YaHecho -> {
                Ajustes.rachaUltimoDia = LocalDate.now().toString()
                Ajustes.rachaEsperaDesbloqueo = ""
                val dias = (r as? Resultado.Enviado)?.dias ?: (r as? Resultado.YaHecho)?.dias
                if (dias != null) Ajustes.rachaDias = dias
                Programador.programarRacha(this)
                Notificaciones.resultadoRacha(this, "🔥 Racha cuidada" + (dias?.let { " · $it días" } ?: ""), r.texto, conReintento = false)
            }
            is Resultado.Fallo -> {
                if (r.bloqueado) Ajustes.rachaEsperaDesbloqueo = LocalDate.now().toString()
                val habra = r.reintentar && Programador.reintentarRacha(this, if (r.bloqueado) 30 else 15)
                val extra = if (habra) " Lo vuelvo a intentar en un rato." else ""
                Notificaciones.resultadoRacha(this, "⚠️ Racha en peligro", r.texto + extra, conReintento = true)
            }
        }
    }

    private fun terminar() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "Racha/Servicio"
        private const val EXTRA_FORZAR = "forzar"

        fun intent(c: Context, forzar: Boolean) = Intent(c, RachaService::class.java).putExtra(EXTRA_FORZAR, forzar)

        fun iniciar(c: Context, forzar: Boolean) {
            runCatching { c.startForegroundService(intent(c, forzar)) }
                .onFailure { Log.e(TAG, "no se pudo iniciar el servicio", it) }
        }
    }
}
