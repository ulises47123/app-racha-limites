package com.rachalimites.app.servicio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.rachalimites.app.R
import com.rachalimites.app.racha.RachaService
import com.rachalimites.app.ui.MainActivity

object Notificaciones {
    const val CANAL_LIMITES = "limites"
    const val CANAL_RACHA = "racha"
    const val CANAL_AVISOS = "avisos"

    const val ID_LIMITES = 10
    const val ID_RACHA_EN_CURSO = 11
    const val ID_RACHA_RESULTADO = 12
    private const val ID_AVISO_BASE = 100

    fun crearCanales(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL_LIMITES, "Tiempo de uso", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Muestra cuánto te queda de redes, juegos y películas"; setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CANAL_RACHA, "Racha de TikTok", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CANAL_AVISOS, "Avisos importantes", NotificationManager.IMPORTANCE_HIGH))
    }

    fun abrirApp(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun base(c: Context, canal: String, titulo: String, texto: String): Notification.Builder =
        Notification.Builder(c, canal)
            .setSmallIcon(R.drawable.ic_fuego)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setContentIntent(abrirApp(c))

    fun resultadoRacha(c: Context, titulo: String, texto: String, conReintento: Boolean) {
        val b = base(c, if (conReintento) CANAL_AVISOS else CANAL_RACHA, titulo, texto).setAutoCancel(true)
        if (conReintento) {
            val pi = PendingIntent.getForegroundService(
                c, 5, RachaService.intent(c, forzar = true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(Notification.Action.Builder(null, "Enviar ahora", pi).build())
        }
        c.getSystemService(NotificationManager::class.java).notify(ID_RACHA_RESULTADO, b.build())
    }

    fun aviso(c: Context, n: Int, titulo: String, texto: String) {
        c.getSystemService(NotificationManager::class.java)
            .notify(ID_AVISO_BASE + n, base(c, CANAL_AVISOS, titulo, texto).setAutoCancel(true).build())
    }
}
