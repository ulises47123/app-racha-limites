package com.rachalimites.app.servicio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rachalimites.app.limites.LimitesService
import com.rachalimites.app.racha.RachaService

/** Despertador de las alarmas exactas. */
class AlarmaReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            Programador.ACCION_RACHA -> RachaService.iniciar(c, forzar = false)
            Programador.ACCION_MEDIANOCHE -> {
                Programador.programarMedianoche(c)
                LimitesService.iniciar(c, LimitesService.ACCION_NUEVO_DIA)
                Programador.programarRacha(c)
            }
        }
    }
}

/** Al prender el teléfono o actualizar la app: vuelve a programar todo y arranca el control de límites. */
class ArranqueReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Programador.programarRacha(c)
        Programador.programarMedianoche(c)
        LimitesService.iniciar(c)
    }
}
