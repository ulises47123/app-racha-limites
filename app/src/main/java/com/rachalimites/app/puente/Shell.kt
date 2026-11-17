package com.rachalimites.app.puente

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku as ShizukuApi

data class ResultadoShell(val codigo: Int, val salida: String)

/**
 * Ejecuta comandos con los privilegios de Shizuku (uid shell). Usa Shizuku.newProcess
 * (API privada, por reflexión), la forma más fiable en MIUI/HyperOS. Mismo patrón que Automatic.
 */
object Shell {
    private const val TAG = "Racha/Shell"

    fun hayPermiso(): Boolean = try {
        ShizukuApi.pingBinder() && !ShizukuApi.isPreV11() &&
            ShizukuApi.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) { false }

    private fun proceso(comando: String, sensible: Boolean = false): Process? {
        if (!hayPermiso()) return null
        return try {
            val m = ShizukuApi::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            m.isAccessible = true
            m.invoke(null, arrayOf("/system/bin/sh", "-c", comando), null, null) as Process
        } catch (e: Exception) {
            Log.e(TAG, "newProcess falló: ${if (sensible) "(comando oculto)" else comando}", e); null
        }
    }

    /** Ejecuta y espera la salida (stdout+stderr). Mata el comando si pasa [limiteMs]. No usar en el hilo principal. */
    fun ejecutar(comando: String, limiteMs: Long = 20_000, sensible: Boolean = false): ResultadoShell? {
        val p = proceso("{ $comando ; } 2>&1", sensible) ?: return null
        return try {
            val salida = StringBuilder()
            val lector = Thread {
                runCatching { p.inputStream.bufferedReader().use { salida.append(it.readText()) } }
            }.apply { isDaemon = true; start() }
            lector.join(limiteMs)
            if (lector.isAlive) {
                Log.w(TAG, "tiempo agotado (${limiteMs}ms): ${if (sensible) "(comando oculto)" else comando}")
                runCatching { p.destroy() }
                lector.join(1000)
                return ResultadoShell(-1, "")
            }
            ResultadoShell(p.waitFor(), salida.toString().trim())
        } catch (e: Exception) { Log.e(TAG, "ejecutar falló", e); null }
    }

    /** Comillas simples seguras para el shell. */
    fun citar(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
