package com.rachalimites.app.puente

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku as ShizukuApi

enum class EstadoShizuku(val texto: String) {
    NO_INSTALADO("Shizuku no está instalado"),
    NO_ACTIVO("Shizuku no está activo (abrí Shizuku y tocá Iniciar)"),
    SIN_PERMISO("Falta darle permiso a esta app en Shizuku"),
    LISTO("Shizuku listo"),
}

/** Estado de Shizuku y concesión automática de permisos (batería, alarmas, uso de apps) vía shell. */
object Shizuku {
    private const val TAG = "Racha/Shizuku"
    private const val CODIGO_PERMISO = 9101
    const val PAQUETE = "moe.shizuku.privileged.api"

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    private val _estado = MutableStateFlow(EstadoShizuku.NO_ACTIVO)
    val estado: StateFlow<EstadoShizuku> = _estado.asStateFlow()

    /** Aumenta cada vez que se otorgan permisos (para refrescar la interfaz). */
    private val _cambios = MutableStateFlow(0)
    val cambios: StateFlow<Int> = _cambios.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
        ShizukuApi.addBinderReceivedListenerSticky { main.post { actualizar() } }
        ShizukuApi.addBinderDeadListener { main.post { actualizar() } }
        ShizukuApi.addRequestPermissionResultListener { codigo, resultado ->
            if (codigo == CODIGO_PERMISO) main.post {
                if (resultado == PackageManager.PERMISSION_GRANTED) otorgarPermisos()
                actualizar()
            }
        }
        actualizar()
    }

    fun actualizar() {
        _estado.value = calcular()
        if (_estado.value == EstadoShizuku.LISTO) otorgarPermisos()
    }

    private fun calcular(): EstadoShizuku {
        val vivo = try { ShizukuApi.pingBinder() } catch (e: Exception) { false }
        if (!vivo) return if (estaInstalado()) EstadoShizuku.NO_ACTIVO else EstadoShizuku.NO_INSTALADO
        return try {
            if (ShizukuApi.isPreV11()) EstadoShizuku.NO_ACTIVO
            else if (ShizukuApi.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) EstadoShizuku.LISTO
            else EstadoShizuku.SIN_PERMISO
        } catch (e: Exception) { EstadoShizuku.NO_ACTIVO }
    }

    fun listo(): Boolean = calcular() == EstadoShizuku.LISTO

    private fun estaInstalado(): Boolean = try {
        app.packageManager.getPackageInfo(PAQUETE, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    fun pedirPermiso() {
        try { ShizukuApi.requestPermission(CODIGO_PERMISO) } catch (e: Exception) { Log.e(TAG, "pedirPermiso", e) }
    }

    @Volatile private var otorgando = false

    /**
     * Quita las restricciones de batería y segundo plano, habilita alarmas exactas y el acceso
     * a las estadísticas de uso (necesario para contar el tiempo de cada app).
     */
    fun otorgarPermisos() {
        if (otorgando) return
        otorgando = true
        Thread {
            try {
                val pkg = app.packageName
                val cmds = mutableListOf(
                    "dumpsys deviceidle whitelist +$pkg",
                    "cmd appops set $pkg RUN_IN_BACKGROUND allow",
                    "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
                    "cmd appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore",
                    "cmd appops set $pkg SCHEDULE_EXACT_ALARM allow",
                    "cmd appops set $pkg GET_USAGE_STATS allow",
                    // Escudo que bloquea los toques del usuario mientras se manda la racha.
                    "cmd appops set $pkg SYSTEM_ALERT_WINDOW allow",
                )
                if (Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")) {
                    cmds += "cmd appops set $pkg 10008 allow" // inicio automático (MIUI/HyperOS)
                    cmds += "cmd appops set $pkg 10021 allow" // abrir ventanas en segundo plano
                }
                if (Build.VERSION.SDK_INT >= 33) cmds += "pm grant $pkg android.permission.POST_NOTIFICATIONS"
                cmds.forEach { Log.i(TAG, "$it -> ${Shell.ejecutar(it)?.salida}") }
            } finally { otorgando = false; _cambios.value++ }
        }.start()
    }
}
