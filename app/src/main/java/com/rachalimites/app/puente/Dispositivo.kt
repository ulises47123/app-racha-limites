package com.rachalimites.app.puente

import android.util.Log

/** Acciones sobre el teléfono con uid shell (vía [Shell]): tocar, teclas, desbloquear, pausar apps. */
object Dispositivo {
    private const val TAG = "Racha/Dispositivo"

    fun tocar(x: Int, y: Int) { Shell.ejecutar("input tap $x $y") }
    fun tecla(codigo: Int) { Shell.ejecutar("input keyevent $codigo") }
    fun atras() = tecla(4)
    fun irAlInicio() = tecla(3)
    fun deslizar(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int = 300) {
        Shell.ejecutar("input swipe $x1 $y1 $x2 $y2 $ms")
    }

    /** Escribe texto ASCII con `input text` (los espacios van como %s). */
    fun escribirAscii(texto: String) {
        val t = texto.replace("%", "").replace(" ", "%s")
        Shell.ejecutar("input text ${Shell.citar(t)}")
    }

    fun pegar() = tecla(279 /* KEYCODE_PASTE */)

    fun abrirEnlace(url: String, paquete: String) {
        Shell.ejecutar("am start -a android.intent.action.VIEW -d ${Shell.citar(url)} -p $paquete")
    }

    fun cerrarApp(paquete: String) { Shell.ejecutar("am force-stop $paquete") }

    fun pantallaEncendida(): Boolean =
        Shell.ejecutar("dumpsys power | grep -m1 mWakefulness=")?.salida?.contains("Awake") == true

    fun estaBloqueado(): Boolean =
        Shell.ejecutar("dumpsys window | grep -m1 isKeyguardShowing")?.salida?.contains("isKeyguardShowing=true") == true

    /** 224 despierta; si no basta (HyperOS), 26 alterna el botón de encendido. Patrón de agente-whatsapp-ia. */
    fun despertar() {
        repeat(3) { i ->
            if (pantallaEncendida()) return
            tecla(if (i < 2) 224 /* KEYCODE_WAKEUP */ else 26 /* KEYCODE_POWER */)
            Thread.sleep(600)
        }
    }

    fun apagarPantalla() = tecla(223 /* KEYCODE_SLEEP */)

    /** Tamaño que usa `input` (override si existe, si no el físico). */
    fun tamanoPantalla(): Pair<Int, Int> {
        val s = Shell.ejecutar("wm size")?.salida.orEmpty()
        val m = Regex("Override size:\\s*(\\d+)x(\\d+)").find(s) ?: Regex("Physical size:\\s*(\\d+)x(\\d+)").find(s)
        return m?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() } ?: (1080 to 2400)
    }

    private const val MAX_INTENTOS_CLAVE = 2
    private const val ESPERA_TRAS_FALLO_MS = 5 * 60_000L
    @Volatile private var noReintentarHasta = 0L

    /**
     * Enciende y desbloquea con la contraseña (o PIN) guardada cifrada. Misma secuencia probada en
     * agente-whatsapp-ia: `wm dismiss-keyguard` → contraseña → deslizar → contraseña. Nunca prueba la
     * contraseña más de 2 veces por intento y, si falla, espera 5 minutos: así el teléfono no se traba
     * por demasiados intentos fallidos.
     */
    fun desbloquear(clave: String?): Boolean {
        despertar(); Thread.sleep(700)
        if (!estaBloqueado()) return true
        val (ancho, alto) = tamanoPantalla()
        val puedeTeclear = !clave.isNullOrEmpty() && System.currentTimeMillis() >= noReintentarHasta
        if (!clave.isNullOrEmpty() && !puedeTeclear) Log.w(TAG, "contraseña en pausa tras un fallo reciente")
        var intentos = 0
        fun probar(): Boolean {
            if (!puedeTeclear || intentos >= MAX_INTENTOS_CLAVE) return false
            intentos++
            Shell.ejecutar(comandoCredencial(clave!!), sensible = true); Thread.sleep(400)
            tecla(66 /* ENTER */); Thread.sleep(1300)
            return !estaBloqueado()
        }
        repeat(3) {
            Shell.ejecutar("wm dismiss-keyguard"); Thread.sleep(1200)
            if (!estaBloqueado()) return true
            if (probar()) return true
            deslizar(ancho / 2, (alto * 0.92).toInt(), ancho / 2, (alto * 0.12).toInt(), 350)
            Thread.sleep(900)
            if (!estaBloqueado()) return true
            if (probar()) return true
            despertar()
        }
        if (intentos > 0 && estaBloqueado()) noReintentarHasta = System.currentTimeMillis() + ESPERA_TRAS_FALLO_MS
        return !estaBloqueado()
    }

    /** PIN numérico → teclas 0..9 (en el bloqueo `input text` no sirve para PIN); contraseña → `input text` literal. */
    private fun comandoCredencial(c: String): String =
        if (c.all { it in '0'..'9' }) "input keyevent " + c.map { 7 + (it - '0') }.joinToString(" ")
        else "input text ${Shell.citar(c)}"

    /** Paquete de la app en primer plano, o null. */
    fun appEnPrimerPlano(): String? {
        val s = Shell.ejecutar("dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'")?.salida
            ?: return null
        return Regex("([a-zA-Z0-9_.]+)/[a-zA-Z0-9_.$]+").find(s)?.groupValues?.get(1)
    }

    // ---------- Pausar / reactivar apps (límites) ----------

    fun pausar(paquetes: Collection<String>, mensaje: String): Boolean {
        if (paquetes.isEmpty()) return true
        val r = Shell.ejecutar("pm suspend --dialogMessage ${Shell.citar(mensaje)} ${paquetes.joinToString(" ")}")
        Log.i(TAG, "pausar $paquetes -> ${r?.salida}")
        return r != null && r.codigo == 0
    }

    fun reactivar(paquetes: Collection<String>): Boolean {
        if (paquetes.isEmpty()) return true
        val r = Shell.ejecutar("pm unsuspend ${paquetes.joinToString(" ")}")
        Log.i(TAG, "reactivar $paquetes -> ${r?.salida}")
        return r != null && r.codigo == 0
    }

    fun estaPausada(paquete: String): Boolean =
        Shell.ejecutar("dumpsys package $paquete | grep -m1 -o 'suspended=[a-z]*'")?.salida?.contains("true") == true
}
