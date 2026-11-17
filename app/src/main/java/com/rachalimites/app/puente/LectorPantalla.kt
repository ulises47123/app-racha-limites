package com.rachalimites.app.puente

import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** Un elemento de la pantalla tal como lo describe `uiautomator dump`. */
data class Nodo(
    val texto: String,
    val desc: String,
    val id: String,
    val clase: String,
    val x1: Int, val y1: Int, val x2: Int, val y2: Int,
) {
    val cx get() = (x1 + x2) / 2
    val cy get() = (y1 + y2) / 2
    val visible get() = x2 > x1 && y2 > y1
}

/**
 * Lee lo que hay en pantalla con `uiautomator dump` (uid shell). Así la app busca los botones de
 * TikTok por su texto o descripción en vez de usar coordenadas fijas, que se rompen con cada
 * actualización.
 */
object LectorPantalla {
    private const val TAG = "Racha/Lector"
    private const val ARCHIVO = "/data/local/tmp/racha_ui.xml"

    /** Devuelve los nodos de la pantalla o null si no se pudo leer (p. ej. un video en reproducción). */
    fun leer(intentos: Int = 3): List<Nodo>? {
        repeat(intentos) { i ->
            val r = Shell.ejecutar("rm -f $ARCHIVO; uiautomator dump $ARCHIVO >/dev/null 2>&1; cat $ARCHIVO 2>/dev/null", 25_000)
            val xml = r?.salida
            if (xml != null && xml.contains("<hierarchy")) {
                val desde = xml.indexOf("<?xml").coerceAtLeast(0)
                runCatching { return parsear(xml.substring(desde)) }
                    .onFailure { Log.w(TAG, "XML inválido", it) }
            }
            Log.w(TAG, "no se pudo leer la pantalla (intento ${i + 1})")
            Thread.sleep(1200)
        }
        return null
    }

    private val BOUNDS = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")

    private fun parsear(xml: String): List<Nodo> {
        val lista = ArrayList<Nodo>(256)
        val p = Xml.newPullParser()
        p.setInput(StringReader(xml))
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG || p.name != "node") continue
            val b = BOUNDS.find(p.getAttributeValue(null, "bounds") ?: "") ?: continue
            val (a, c, d, e) = b.destructured
            lista += Nodo(
                texto = p.getAttributeValue(null, "text").orEmpty(),
                desc = p.getAttributeValue(null, "content-desc").orEmpty(),
                id = p.getAttributeValue(null, "resource-id").orEmpty(),
                clase = p.getAttributeValue(null, "class").orEmpty(),
                x1 = a.toInt(), y1 = c.toInt(), x2 = d.toInt(), y2 = e.toInt(),
            )
        }
        return lista
    }

    /**
     * Normaliza nombres para comparar: minúsculas, sin espacios, sin marcas invisibles de dirección
     * (TikTok antepone U+200E) ni emojis/símbolos. "nombre _chat" y "‎Nombre_Chat" quedan iguales.
     */
    fun normalizar(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '.' }
}
