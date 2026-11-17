package com.rachalimites.app.racha

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Capa a pantalla completa mientras se envía la racha: se "traga" los toques del usuario para que
 * no interfieran. Los toques de la propia app (`input tap/swipe`) chocarían con la capa, así que
 * justo antes de cada uno se vuelve atravesable ([dejarPasar]) y después vuelve a bloquear.
 * Botón de emergencia: mantenerlo apretado 1,5 s cancela el envío. Patrón tomado de Automatic.
 */
object EscudoToques {
    private const val OPACIDAD = 0.75f // Android 12+ solo deja pasar toques por capas con opacidad ≤ 0.8
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var vista: View? = null
    private var params: WindowManager.LayoutParams? = null

    fun disponible(c: Context) = Settings.canDrawOverlays(c)

    @SuppressLint("ClickableViewAccessibility")
    fun mostrar(context: Context, onDetener: () -> Unit) {
        val c = context.applicationContext
        if (!disponible(c)) return
        val listo = CountDownLatch(1)
        main.post {
            try {
                if (vista != null) return@post
                val wm = c.getSystemService(WindowManager::class.java)
                val contenedor = FrameLayout(c).apply {
                    setBackgroundColor(Color.parseColor("#40000000"))
                    setOnTouchListener { _, _ -> true } // consume todos los toques
                }
                val caja = LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    val p = dp(c, 14); setPadding(p, p, p, p)
                    background = fondo(Color.parseColor("#E6202124"), dp(c, 20))
                }
                caja.addView(texto(c, "🔥 Enviando la racha…\nNo toques la pantalla", 16f))
                caja.addView(botonDetener(c, onDetener))
                contenedor.addView(caja, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply { bottomMargin = dp(c, 140) })

                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    if (Build.VERSION.SDK_INT >= 30) { fitInsetsTypes = 0; fitInsetsSides = 0 }
                }
                runCatching { wm.addView(contenedor, lp); vista = contenedor; params = lp }
            } finally { listo.countDown() }
        }
        listo.await(2, TimeUnit.SECONDS)
    }

    fun ocultar(context: Context) {
        val listo = CountDownLatch(1)
        main.post {
            vista?.let { v -> runCatching { context.applicationContext.getSystemService(WindowManager::class.java).removeView(v) } }
            vista = null; params = null
            listo.countDown()
        }
        listo.await(2, TimeUnit.SECONDS)
    }

    /** [si] = true: la capa deja pasar los toques; false: vuelve a bloquear. Espera a que el cambio se aplique. */
    fun dejarPasar(si: Boolean) {
        if (vista == null) return
        val listo = CountDownLatch(1)
        main.post {
            val v = vista; val lp = params
            if (v != null && lp != null) {
                lp.flags = if (si) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                lp.alpha = if (si) OPACIDAD else 1f
                runCatching { v.context.getSystemService(WindowManager::class.java).updateViewLayout(v, lp) }
            }
            listo.countDown()
        }
        listo.await(1, TimeUnit.SECONDS)
        if (si) Thread.sleep(120) // el despachador de toques se actualiza en el siguiente cuadro
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun botonDetener(c: Context, onDetener: () -> Unit) = texto(c, "Mantené apretado para detener", 14f).apply {
        setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 10))
        background = fondo(Color.parseColor("#FFC62828"), dp(c, 24))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(c, 10) }
        val detener = Runnable { text = "Deteniendo…"; onDetener() }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.alpha = 0.6f; main.postDelayed(detener, 1500) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; main.removeCallbacks(detener) }
            }
            true
        }
    }

    private fun texto(c: Context, t: String, sp: Float) = TextView(c).apply {
        text = t; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); gravity = Gravity.CENTER
    }

    private fun fondo(color: Int, radio: Int) = GradientDrawable().apply { cornerRadius = radio.toFloat(); setColor(color) }
    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
}
