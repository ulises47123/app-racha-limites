package com.rachalimites.app.datos

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/** Una app instalada que se puede asignar a una categoría. */
data class AppInstalada(val paquete: String, val nombre: String, val categoriaSistema: Int)

enum class Categoria(
    val titulo: String,
    val minutosPorDefecto: Int,
    /** Apps conocidas que entran por defecto (si están instaladas). */
    val conocidas: Set<String>,
    /** Categoría que declara la propia app en Android (ApplicationInfo.CATEGORY_*). */
    val categoriaAndroid: Int?,
) {
    REDES(
        "Redes sociales", 60,
        setOf(
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go",
            "com.facebook.katana", "com.facebook.lite", "com.instagram.android", "com.instagram.lite",
            "com.pinterest", "com.twitter.android", "com.snapchat.android", "com.instagram.barcelona",
            "com.reddit.frontpage", "com.bereal.ft",
        ),
        null, // Android marca como "social" a Gmail, Contactos, Meet, etc.: solo se usa la lista fija.
    ),
    JUEGOS(
        "Juegos", 120,
        setOf(
            "com.mojang.minecraftpe", "com.roblox.client", "com.supercell.brawlstars", "com.supercell.clashroyale",
            "com.dts.freefireth", "com.dts.freefiremax", "com.tencent.ig", "com.activision.callofduty.shooter",
            "net.froemling.bombsquad", "com.autumn.skullgirls", "aoc.androidoyunclub.raymanlde",
            "com.astroloop.game", "com.arthur.arcade", "com.krystelligence.solipsism",
        ),
        ApplicationInfo.CATEGORY_GAME,
    ),
    PELICULAS(
        "Películas y series", 90,
        setOf(
            "com.mothur.goldentv", "com.amazon.avod.thirdpartyclient", "com.disney.disneyplus",
            "com.netflix.mediaclient", "com.hbo.hbonow", "com.wbd.stream", "com.google.android.youtube",
            "org.wisso.newpipematerial", "org.schabi.newpipe", "com.video.app", "tv.pluto.android",
            "com.star.plus", "ar.tvpublica.cont.ar", "com.flowtv.android",
        ),
        null, // "video" incluye cámaras y editores: solo se usa la lista fija.
    );

    /** Apps de la categoría que hay que controlar (las elegidas por el usuario o las de por defecto). */
    fun paquetes(instaladas: List<AppInstalada>): Set<String> {
        Ajustes.apps(this)?.let { elegidas -> return elegidas.filterTo(HashSet()) { p -> instaladas.any { it.paquete == p } } }
        return porDefecto(instaladas)
    }

    fun porDefecto(instaladas: List<AppInstalada>): Set<String> = instaladas.filter { app ->
        app.paquete !in NUNCA && (app.paquete in conocidas ||
            (categoriaAndroid != null && app.categoriaSistema == categoriaAndroid && app.paquete !in OTRAS_CONOCIDAS[this].orEmpty()))
    }.mapTo(HashSet()) { it.paquete }

    companion object {
        /** Nunca se limitan: mensajería, estudio, trabajo y sistema. */
        val NUNCA = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thunderdog.challegram",
            "org.thoughtcrime.securesms", "com.discord", "com.google.android.apps.classroom",
            "ar.edu.infd.ies5tello.campus_ies5_movil", "com.google.android.apps.labs.language.tailwind",
            "com.rachalimites.app", "moe.shizuku.privileged.api", "com.android.chrome",
            "com.google.android.apps.docs.editors.docs", "org.jitsi.meet",
            // Cámara, edición y descargas: también se declaran "video" pero no son para mirar pelis.
            "com.nexstreaming.app.kinemasterfree", "com.miui.screenrecorder", "com.miui.mediaeditor",
            "com.miui.gallery", "com.efectocam", "com.fadcam", "com.github.digitallyrefined.androidipcamera",
            "com.dv.adm", "idm.internet.download.manager", "com.android.camera", "com.miui.videoplayer",
        )

        /** Evita que una app conocida de una categoría caiga en otra por su categoría de Android. */
        private val OTRAS_CONOCIDAS: Map<Categoria, Set<String>> by lazy {
            entries.associateWith { c -> entries.filter { it != c }.flatMapTo(HashSet()) { it.conocidas } }
        }

        /** Apps con ícono en el lanzador (las únicas que tiene sentido limitar). */
        fun instaladas(c: Context): List<AppInstalada> {
            val pm = c.packageManager
            val lanzables = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ).map { it.activityInfo.packageName }.toSet()
            return lanzables.mapNotNull { pkg ->
                runCatching {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    AppInstalada(pkg, pm.getApplicationLabel(ai).toString(), ai.category)
                }.getOrNull()
            }.filter { it.paquete != c.packageName }.sortedBy { it.nombre.lowercase() }
        }
    }
}
