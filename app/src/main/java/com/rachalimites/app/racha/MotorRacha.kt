package com.rachalimites.app.racha

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rachalimites.app.App
import com.rachalimites.app.datos.Ajustes
import com.rachalimites.app.puente.Dispositivo
import com.rachalimites.app.puente.LectorPantalla
import com.rachalimites.app.puente.LectorPantalla.normalizar
import com.rachalimites.app.puente.Nodo
import com.rachalimites.app.puente.Shell
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

sealed class Resultado(val texto: String) {
    class Enviado(texto: String, val dias: Int?) : Resultado(texto)
    class YaHecho(texto: String, val dias: Int?) : Resultado(texto)
    /** [reintentar]: vale la pena volver a probar más tarde; [bloqueado]: esperar a que el usuario desbloquee. */
    class Fallo(texto: String, val reintentar: Boolean = true, val bloqueado: Boolean = false) : Resultado(texto)
}

/**
 * Mantiene la racha de TikTok: abre la bandeja de mensajes, busca el chat por su nombre, escribe
 * el mensaje, toca "Enviar" y comprueba que salió. Lee la pantalla con `uiautomator` (Shizuku) en
 * vez de usar coordenadas fijas, así sobrevive a los cambios de diseño de TikTok.
 */
object MotorRacha {
    private const val TAG = "Racha/Motor"
    const val TIKTOK = "com.zhiliaoapp.musically"
    private const val ENLACE_BANDEJA = "snssdk1233://inbox"

    @Volatile var enCurso = false
        private set

    /** Lo activa el botón de emergencia del escudo. */
    @Volatile private var cancelado = false
    private class Cancelado : Exception()

    private fun verificar() { if (cancelado) throw Cancelado() }

    private fun leer(): List<Nodo>? { verificar(); return LectorPantalla.leer() }

    /** Los toques de la app atraviesan el escudo; los del usuario no. */
    private fun tocar(x: Int, y: Int) {
        verificar()
        EscudoToques.dejarPasar(true)
        try { Dispositivo.tocar(x, y) } finally { EscudoToques.dejarPasar(false) }
    }

    private fun deslizar(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) {
        verificar()
        EscudoToques.dejarPasar(true)
        try { Dispositivo.deslizar(x1, y1, x2, y2, ms) } finally { EscudoToques.dejarPasar(false) }
    }

    /** Botones de ventanas emergentes que se pueden cerrar sin riesgo. */
    private val CERRAR = setOf(
        "ahorano", "no,gracias", "nogracias", "cerrar", "omitir", "entendido", "mástarde", "mastarde",
        "notnow", "skip", "gotit", "close", "cancelar", "descartar",
    ).map { normalizar(it) }.toSet()

    fun ejecutar(forzar: Boolean): Resultado {
        if (enCurso) return Resultado.Fallo("Ya se está enviando", reintentar = false)
        enCurso = true
        try {
            if (!Shell.hayPermiso()) return Resultado.Fallo("Shizuku no está activo. Abrí Shizuku y tocá Iniciar.")
            if (enLlamada()) return Resultado.Fallo("Estás en una llamada; lo intento más tarde.")

            val estabaApagado = !Dispositivo.pantallaEncendida() || Dispositivo.estaBloqueado()
            if (estabaApagado && !Dispositivo.desbloquear(Ajustes.pin)) {
                Dispositivo.apagarPantalla()
                return Resultado.Fallo(
                    "No pude desbloquear el teléfono. Lo mando apenas lo desbloquees" +
                        (if (Ajustes.pin.isNullOrBlank()) " (o guardá tu contraseña en la app)." else "."),
                    bloqueado = true,
                )
            }

            // Si TikTok quedó pausado por el límite de redes, se habilita solo para mandar la racha.
            val estabaPausado = Dispositivo.estaPausada(TIKTOK)
            if (estabaPausado) Dispositivo.reactivar(listOf(TIKTOK))
            cancelado = false
            EscudoToques.mostrar(App.instancia) { cancelado = true }
            try {
                return enviar(forzar)
            } catch (e: Cancelado) {
                return Resultado.Fallo("Lo detuviste vos. Podés mandarlo con \"Enviar ahora\".", reintentar = false)
            } catch (e: Exception) {
                Log.e(TAG, "error inesperado", e)
                return Resultado.Fallo("Error inesperado: ${e.message}")
            } finally {
                // Al terminar: inicio, TikTok cerrado, escudo fuera y (si estaba bloqueado) se vuelve a bloquear.
                Dispositivo.irAlInicio()
                Thread.sleep(500)
                Dispositivo.cerrarApp(TIKTOK)
                EscudoToques.ocultar(App.instancia)
                if (estabaPausado) {
                    Dispositivo.pausar(listOf(TIKTOK), "Llegaste al límite de redes sociales por hoy. Vuelve a estar disponible a las 00:00.")
                }
                if (estabaApagado) Dispositivo.apagarPantalla()
            }
        } finally { enCurso = false }
    }

    private fun enviar(forzar: Boolean): Resultado {
        val objetivo = normalizar(Ajustes.rachaContacto)
        if (objetivo.isEmpty()) return Resultado.Fallo("Falta el nombre del chat", reintentar = false)
        val mensaje = Ajustes.rachaMensaje.ifBlank { "🔥" }

        // 1) Bandeja de mensajes y fila del chat.
        Dispositivo.abrirEnlace(ENLACE_BANDEJA, TIKTOK)
        Thread.sleep(4500)
        var fila: Nodo? = null
        var nodos: List<Nodo> = emptyList()
        var desplazamientos = 0
        for (intento in 1..10) {
            nodos = leer() ?: run { Thread.sleep(1500); null } ?: continue
            if (sesionCerrada(nodos)) return Resultado.Fallo("TikTok cerró tu sesión: abrí TikTok e iniciá sesión.", reintentar = false)
            fila = nodos.firstOrNull { it.id.endsWith(":id/user_name") && normalizar(it.texto) == objetivo }
                ?: nodos.firstOrNull { normalizar(it.texto) == objetivo && it.y1 > 300 }
            if (fila != null) break
            if (cerrarVentana(nodos)) continue
            val enBandeja = nodos.any { it.id.endsWith(":id/user_name") }
            if (enBandeja && desplazamientos < 4) {
                val (w, h) = Dispositivo.tamanoPantalla()
                deslizar(w / 2, (h * 0.75).toInt(), w / 2, (h * 0.35).toInt(), 400)
                desplazamientos++
                Thread.sleep(1200)
            } else if (!enBandeja) {
                Dispositivo.abrirEnlace(ENLACE_BANDEJA, TIKTOK)
                Thread.sleep(3000)
            } else break
        }
        val f = fila ?: return Resultado.Fallo("No encontré el chat \"${Ajustes.rachaContacto}\" en la bandeja de TikTok.")

        val diasFila = numeroRacha(nodos, f)
        if (!forzar && Ajustes.rachaSoloSiFalta && yaEscribioHoy(nodos, f)) {
            return Resultado.YaHecho("Ya le habías escrito hoy, no hizo falta mandar nada.", diasFila)
        }

        // 2) Abrir el chat y comprobar que es el correcto.
        tocar(f.cx, f.cy)
        Thread.sleep(2500)
        var campo: Nodo? = null
        for (intento in 1..5) {
            nodos = leer() ?: continue
            val encabezado = nodos.any { normalizar(it.texto) == objetivo && it.y2 < 400 }
            campo = nodos.firstOrNull { it.clase == "android.widget.EditText" && it.visible }
            if (encabezado && campo != null) break
            if (cerrarVentana(nodos)) continue
            campo = null
            Thread.sleep(1200)
        }
        val c = campo ?: return Resultado.Fallo("Abrí el chat pero no encontré dónde escribir.")

        // 3) Escribir el mensaje.
        tocar(c.cx, c.cy)
        Thread.sleep(900)
        val escrito = escribir(mensaje)
        if (!escrito) return Resultado.Fallo("No pude escribir el mensaje en el chat.")

        // 4) Enviar.
        nodos = leer() ?: return Resultado.Fallo("No pude leer la pantalla para enviar.")
        val enviar = nodos.firstOrNull { (it.desc.equals("Enviar", true) || it.desc.equals("Send", true)) && it.visible }
            ?: return Resultado.Fallo("No encontré el botón Enviar.")
        tocar(enviar.cx, enviar.cy)
        Thread.sleep(2000)

        // 5) Comprobar: el campo quedó vacío.
        nodos = leer() ?: return Resultado.Enviado("Mensaje enviado (sin poder comprobarlo).", diasFila)
        val campoDespues = nodos.firstOrNull { it.clase == "android.widget.EditText" }
        if (campoDespues != null && campoDespues.texto.isNotBlank() && contiene(campoDespues.texto, mensaje)) {
            return Resultado.Fallo("El mensaje quedó escrito pero no se envió.")
        }
        val dias = nodos.firstOrNull { it.y2 < 260 && it.texto.isNotBlank() && it.texto.all(Char::isDigit) }?.texto?.toIntOrNull()
        Dispositivo.atras(); Thread.sleep(400); Dispositivo.atras()
        return Resultado.Enviado("Racha enviada a ${Ajustes.rachaContacto} ✅", dias ?: diasFila)
    }

    /** Escribe el texto y verifica que aparezca en el campo. Emojis y acentos van por el portapapeles. */
    private fun escribir(mensaje: String): Boolean {
        val ascii = mensaje.all { it.code in 32..126 }
        if (ascii) Dispositivo.escribirAscii(mensaje) else { copiar(mensaje); Thread.sleep(300); Dispositivo.pegar() }
        Thread.sleep(1200)
        var campo = leer()?.firstOrNull { it.clase == "android.widget.EditText" }
        if (campo != null && contiene(campo.texto, mensaje)) return true
        if (!ascii) {
            // Respaldo: escribir solo la parte de texto común (por ejemplo "racha").
            val soloTexto = mensaje.filter { it.code in 32..126 }.trim().ifEmpty { "racha" }
            Dispositivo.escribirAscii(soloTexto)
            Thread.sleep(1000)
            campo = leer()?.firstOrNull { it.clase == "android.widget.EditText" }
            return campo != null && contiene(campo.texto, soloTexto)
        }
        return false
    }

    private fun contiene(campo: String, mensaje: String): Boolean {
        val a = campo.filterNot(Char::isWhitespace)
        val b = mensaje.filterNot(Char::isWhitespace)
        return b.isNotEmpty() && a.contains(b)
    }

    private fun copiar(texto: String) {
        val listo = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val cm = App.instancia.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("racha", texto))
            }.onFailure { Log.w(TAG, "portapapeles", it) }
            listo.countDown()
        }
        listo.await(2, TimeUnit.SECONDS)
    }

    private fun cerrarVentana(nodos: List<Nodo>): Boolean {
        val b = nodos.firstOrNull { it.visible && (normalizar(it.texto) in CERRAR || normalizar(it.desc) in CERRAR) }
            ?: return false
        Log.i(TAG, "cerrando ventana emergente: '${b.texto}${b.desc}'")
        tocar(b.cx, b.cy)
        Thread.sleep(1200)
        return true
    }

    private fun sesionCerrada(nodos: List<Nodo>): Boolean = nodos.any {
        val t = normalizar(it.texto)
        t == "iniciarsesión" || t == "iniciasesión" || t == "registrarte" || t == "regístrate" || t == "login"
    } && nodos.none { it.id.endsWith(":id/user_name") }

    /** Número que TikTok muestra junto al nombre (días de racha). */
    private fun numeroRacha(nodos: List<Nodo>, fila: Nodo): Int? = nodos.firstOrNull {
        it.texto.isNotBlank() && it.texto.all(Char::isDigit) && it.cy in fila.y1..fila.y2 && it.x1 >= fila.x2
    }?.texto?.toIntOrNull()

    /**
     * Debajo del nombre TikTok muestra "Se envió hace 4 min" cuando el último mensaje es tuyo.
     * Si eso pasó después de las 00:00 de hoy, la racha de hoy ya está cubierta de tu lado.
     */
    private fun yaEscribioHoy(nodos: List<Nodo>, fila: Nodo): Boolean {
        val sub = nodos.firstOrNull { it.id.endsWith(":id/mux") && it.y1 >= fila.y2 - 4 && it.y1 <= fila.y2 + 70 }?.texto
            ?: return false
        val t = sub.lowercase()
        if (!t.contains("se envió") && !t.contains("se envio") && !t.contains("sent")) return false
        val m = Regex("(\\d+)\\s*(s|seg|min|h)\\b").find(t) ?: return t.contains("ahora") || t.contains("now")
        val n = m.groupValues[1].toInt()
        val minutosAtras = when (m.groupValues[2]) { "s", "seg" -> 0; "min" -> n; else -> n * 60 }
        val ahora = LocalTime.now()
        return minutosAtras < ahora.hour * 60 + ahora.minute
    }

    private fun enLlamada(): Boolean {
        val am = App.instancia.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_RINGTONE
    }
}
