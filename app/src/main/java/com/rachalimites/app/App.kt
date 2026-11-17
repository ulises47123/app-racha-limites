package com.rachalimites.app

import android.app.Application
import com.rachalimites.app.datos.Ajustes
import com.rachalimites.app.limites.LimitesService
import com.rachalimites.app.puente.Shizuku
import com.rachalimites.app.servicio.Notificaciones
import com.rachalimites.app.servicio.Programador

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instancia = this
        Ajustes.init(this)
        Notificaciones.crearCanales(this)
        Shizuku.init(this)
        Programador.programarRacha(this)
        Programador.programarMedianoche(this)
        LimitesService.iniciar(this)
    }

    companion object {
        lateinit var instancia: App
            private set
    }
}
