package com.rachalimites.app.datos

import android.content.Context
import android.content.SharedPreferences
import com.rachalimites.app.BuildConfig
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/** Configuración y estado de la app (preferencias simples). */
object Ajustes {
    private lateinit var p: SharedPreferences
    private lateinit var seguro: SharedPreferences

    /** Cambia cada vez que se guarda algo, para que la interfaz se refresque. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(c: Context) {
        p = c.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
        seguro = runCatching {
            EncryptedSharedPreferences.create(
                c, "seguro",
                MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse { c.getSharedPreferences("seguro_respaldo", Context.MODE_PRIVATE) }
    }

    private fun editar(b: SharedPreferences.Editor.() -> Unit) { p.edit().apply(b).apply(); _version.value++ }

    // ---------------- Racha ----------------
    var rachaActiva: Boolean
        get() = p.getBoolean("racha_activa", true)
        set(v) = editar { putBoolean("racha_activa", v) }

    /** Nombre del chat tal como aparece en la bandeja de TikTok. */
    var rachaContacto: String
        get() = p.getString("racha_contacto", BuildConfig.RACHA_CONTACTO)!!
        set(v) = editar { putString("racha_contacto", v) }

    var rachaMensaje: String
        get() = p.getString("racha_mensaje", "🔥")!!
        set(v) = editar { putString("racha_mensaje", v) }

    var rachaHora: Int
        get() = p.getInt("racha_hora", 19)
        set(v) = editar { putInt("racha_hora", v) }

    var rachaMinuto: Int
        get() = p.getInt("racha_minuto", 0)
        set(v) = editar { putInt("racha_minuto", v) }

    /** No mandar si ya le escribiste hoy a mano (lo detecta en la bandeja). */
    var rachaSoloSiFalta: Boolean
        get() = p.getBoolean("racha_solo_si_falta", true)
        set(v) = editar { putBoolean("racha_solo_si_falta", v) }

    /** Último día (yyyy-MM-dd) en que quedó cumplida la racha. */
    var rachaUltimoDia: String
        get() = p.getString("racha_ultimo_dia", "")!!
        set(v) = editar { putString("racha_ultimo_dia", v) }

    var rachaUltimoResultado: String
        get() = p.getString("racha_resultado", "Todavía no se ejecutó")!!
        set(v) = editar { putString("racha_resultado", v) }

    var rachaDias: Int
        get() = p.getInt("racha_dias", 0)
        set(v) = editar { putInt("racha_dias", v) }

    /** Día (yyyy-MM-dd) en que la racha quedó esperando que el usuario desbloquee el teléfono. */
    var rachaEsperaDesbloqueo: String
        get() = p.getString("racha_espera", "")!!
        set(v) = editar { putString("racha_espera", v) }

    fun rachaHechaHoy() = rachaUltimoDia == LocalDate.now().toString()

    var pin: String?
        get() = seguro.getString("pin", null)
        set(v) { seguro.edit().apply { if (v.isNullOrBlank()) remove("pin") else putString("pin", v) }.apply(); _version.value++ }

    // ---------------- Límites ----------------
    var limitesActivos: Boolean
        get() = p.getBoolean("limites_activos", true)
        set(v) = editar { putBoolean("limites_activos", v) }

    fun minutos(cat: Categoria): Int = p.getInt("min_${cat.name}", cat.minutosPorDefecto)
    fun fijarMinutos(cat: Categoria, v: Int) = editar { putInt("min_${cat.name}", v.coerceIn(0, 24 * 60)) }

    /** Apps elegidas para la categoría (si nunca se editó, null = usar las detectadas por defecto). */
    fun apps(cat: Categoria): Set<String>? = p.getStringSet("apps_${cat.name}", null)?.toSet()
    fun fijarApps(cat: Categoria, v: Set<String>) = editar { putStringSet("apps_${cat.name}", v) }

    /** Paquetes que ESTA app pausó (para reactivarlos a medianoche y no tocar otros). */
    var pausadas: Set<String>
        get() = p.getStringSet("pausadas", emptySet())!!.toSet()
        set(v) = editar { putStringSet("pausadas", v) }

    var diaPausadas: String
        get() = p.getString("dia_pausadas", "")!!
        set(v) = editar { putString("dia_pausadas", v) }

    /** Avisos de "te quedan 5 minutos" ya mostrados hoy, como "2026-11-10:REDES". */
    var avisosDados: Set<String>
        get() = p.getStringSet("avisos", emptySet())!!.toSet()
        set(v) = editar { putStringSet("avisos", v) }
}
