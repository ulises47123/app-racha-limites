package com.rachalimites.app.limites

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.rachalimites.app.datos.Ajustes
import com.rachalimites.app.datos.AppInstalada
import com.rachalimites.app.datos.Categoria
import com.rachalimites.app.puente.Dispositivo
import com.rachalimites.app.puente.Shell
import com.rachalimites.app.racha.MotorRacha
import com.rachalimites.app.racha.RachaService
import com.rachalimites.app.servicio.Notificaciones
import com.rachalimites.app.servicio.Programador
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Uso de hoy de una categoría. */
data class UsoCategoria(val categoria: Categoria, val usadoMs: Long, val limiteMin: Int, val paquetes: Set<String>) {
    val usadoMin get() = (usadoMs / 60_000).toInt()
    val restanteMin get() = (limiteMin - usadoMin).coerceAtLeast(0)
    val agotado get() = usadoMs >= limiteMin * 60_000L
}

/**
 * Servicio permanente (consume poco): cada 30 s con la pantalla encendida suma el uso de hoy por
 * categoría. Al llegar al límite pausa esas apps con `pm suspend` (Shizuku) hasta las 00:00.
 * También manda la racha pendiente apenas desbloqueás el teléfono.
 */
class LimitesService : Service() {

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var instaladas: List<AppInstalada> = emptyList()
    private var instaladasEn = 0L

    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_USER_PRESENT) racharSiQuedoPendiente()
            alcance.launch { revisar() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val n = notificacion("Calculando tu tiempo de hoy…")
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(Notificaciones.ID_LIMITES, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        else startForeground(Notificaciones.ID_LIMITES, n)
        registerReceiver(receptor, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON)
        })
        Programador.programarMedianoche(this)
        alcance.launch {
            while (isActive) {
                runCatching { revisar() }.onFailure { Log.e(TAG, "revisar", it) }
                val encendida = getSystemService(PowerManager::class.java).isInteractive
                delay(if (encendida) 30_000 else 300_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_NUEVO_DIA || intent?.action == ACCION_REVISAR) alcance.launch { revisar() }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receptor) }
        alcance.cancel()
        super.onDestroy()
    }

    private fun racharSiQuedoPendiente() {
        val hoy = LocalDate.now().toString()
        if (Ajustes.rachaActiva && Ajustes.rachaEsperaDesbloqueo == hoy && !Ajustes.rachaHechaHoy() && !MotorRacha.enCurso) {
            Log.i(TAG, "desbloqueado con racha pendiente: enviando")
            RachaService.iniciar(this, forzar = false)
        }
    }

    @Synchronized
    private fun revisar() {
        val hoy = LocalDate.now().toString()

        // Nuevo día: se reactiva todo lo que esta app había pausado.
        if (Ajustes.diaPausadas != hoy) {
            if (Ajustes.pausadas.isNotEmpty() && Shell.hayPermiso()) {
                if (Dispositivo.reactivar(Ajustes.pausadas)) Ajustes.pausadas = emptySet()
            }
            if (Ajustes.pausadas.isEmpty() || !Shell.hayPermiso()) {
                Ajustes.diaPausadas = hoy
                Ajustes.avisosDados = emptySet()
            }
        }

        if (System.currentTimeMillis() - instaladasEn > 10 * 60_000 || instaladas.isEmpty()) {
            instaladas = Categoria.instaladas(this); instaladasEn = System.currentTimeMillis()
        }
        if (!Uso.tienePermiso(this)) {
            actualizarNotificacion("Falta el permiso de uso de apps: abrí la app y activá Shizuku.")
            return
        }
        val uso = Uso.hoy(this)
        val estado = Categoria.entries.map { cat ->
            val pk = cat.paquetes(instaladas)
            UsoCategoria(cat, pk.sumOf { uso[it] ?: 0L }, Ajustes.minutos(cat), pk)
        }
        _estado.value = estado

        if (Ajustes.limitesActivos) {
            avisarCasiAgotado(estado, hoy)
            aplicar(estado)
        } else if (Ajustes.pausadas.isNotEmpty() && Shell.hayPermiso()) {
            if (Dispositivo.reactivar(Ajustes.pausadas)) Ajustes.pausadas = emptySet()
        }
        actualizarNotificacion(resumen(estado))
    }

    /** Pausa lo que se pasó del límite y reactiva lo que ya no corresponde (p. ej. si subiste el límite). */
    private fun aplicar(estado: List<UsoCategoria>) {
        if (!Shell.hayPermiso()) return
        val deben = HashMap<String, Categoria>()
        estado.filter { it.agotado }.forEach { u -> u.paquetes.forEach { deben.putIfAbsent(it, u.categoria) } }
        // Mientras se envía la racha, TikTok no se toca.
        if (MotorRacha.enCurso) deben.remove(MotorRacha.TIKTOK)

        val sobran = Ajustes.pausadas - deben.keys
        if (sobran.isNotEmpty() && Dispositivo.reactivar(sobran)) Ajustes.pausadas = Ajustes.pausadas - sobran

        val nuevas = deben.keys - Ajustes.pausadas
        if (nuevas.isEmpty()) return
        val primerPlano = Dispositivo.appEnPrimerPlano()
        if (primerPlano != null && primerPlano in nuevas) Dispositivo.irAlInicio()
        nuevas.groupBy { deben[it]!! }.forEach { (cat, pkgs) ->
            val msg = "Llegaste al límite de ${cat.titulo.lowercase()} por hoy (${formato(Ajustes.minutos(cat))}). " +
                "Vuelve a estar disponible a las 00:00. ¡Aprovechá para estudiar! 📚"
            if (Dispositivo.pausar(pkgs, msg)) {
                Ajustes.pausadas = Ajustes.pausadas + pkgs
                Notificaciones.aviso(this, cat.ordinal, "⏰ Se terminó el tiempo de ${cat.titulo.lowercase()}", msg)
            }
        }
    }

    private fun avisarCasiAgotado(estado: List<UsoCategoria>, hoy: String) {
        estado.filter { !it.agotado && it.limiteMin > 5 && it.restanteMin <= 5 }.forEach { u ->
            val clave = "$hoy:${u.categoria.name}"
            if (clave !in Ajustes.avisosDados) {
                Ajustes.avisosDados = Ajustes.avisosDados + clave
                Notificaciones.aviso(this, u.categoria.ordinal, "Te quedan ${u.restanteMin} min de ${u.categoria.titulo.lowercase()}",
                    "Después de eso esas apps se pausan hasta mañana.")
            }
        }
    }

    private fun resumen(e: List<UsoCategoria>) = e.joinToString(" · ") {
        val corto = when (it.categoria) { Categoria.REDES -> "Redes"; Categoria.JUEGOS -> "Juegos"; Categoria.PELICULAS -> "Pelis" }
        "$corto ${it.usadoMin.coerceAtMost(it.limiteMin)}/${it.limiteMin}m"
    } + if (Ajustes.limitesActivos) "" else " (límites apagados)"

    private fun notificacion(texto: String) =
        Notificaciones.base(this, Notificaciones.CANAL_LIMITES, "Tiempo de hoy", texto).setOngoing(true).build()

    private fun actualizarNotificacion(texto: String) {
        getSystemService(NotificationManager::class.java).notify(Notificaciones.ID_LIMITES, notificacion(texto))
    }

    companion object {
        private const val TAG = "Racha/Limites"
        const val ACCION_NUEVO_DIA = "nuevo_dia"
        const val ACCION_REVISAR = "revisar"

        private val _estado = MutableStateFlow<List<UsoCategoria>>(emptyList())
        val estado: StateFlow<List<UsoCategoria>> = _estado.asStateFlow()

        fun iniciar(c: Context, accion: String? = null) {
            runCatching { c.startForegroundService(Intent(c, LimitesService::class.java).setAction(accion)) }
                .onFailure { Log.e(TAG, "no se pudo iniciar", it) }
        }

        fun formato(min: Int) = if (min < 60) "$min min" else if (min % 60 == 0) "${min / 60} h" else if (min > 60) "${min / 60} h ${min % 60} min" else "$min min"
    }
}
