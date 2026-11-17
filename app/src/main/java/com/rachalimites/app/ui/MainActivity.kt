package com.rachalimites.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rachalimites.app.datos.Ajustes
import com.rachalimites.app.datos.AppInstalada
import com.rachalimites.app.datos.Categoria
import com.rachalimites.app.limites.LimitesService
import com.rachalimites.app.limites.Uso
import com.rachalimites.app.puente.EstadoShizuku
import com.rachalimites.app.puente.Shizuku
import com.rachalimites.app.racha.MotorRacha
import com.rachalimites.app.racha.RachaService
import com.rachalimites.app.servicio.Programador

private val Naranja = Color(0xFFFF7043)
private val Verde = Color(0xFF66BB6A)
private val Rojo = Color(0xFFEF5350)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Naranja, secondary = Verde)) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF0E1116), contentColor = Color.White) { Pantalla() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Shizuku.actualizar()
        LimitesService.iniciar(this, LimitesService.ACCION_REVISAR)
    }
}

@Composable
private fun Pantalla() {
    var verApps by remember { mutableStateOf<Categoria?>(null) }
    val cat = verApps
    if (cat != null) { SelectorApps(cat) { verApps = null }; return }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.systemBars).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Racha y Límites", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Cuida tu racha y tu tiempo", color = Color.Gray)
        TarjetaEstado()
        TarjetaRacha()
        TarjetaLimites { verApps = it }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Tarjeta(titulo: String, icono: androidx.compose.ui.graphics.vector.ImageVector, contenido: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1F27), contentColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icono, null, tint = Naranja); Spacer(Modifier.width(8.dp))
                Text(titulo, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            contenido()
        }
    }
}

@Composable
private fun FilaEstado(ok: Boolean, texto: String, boton: String? = null, accion: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.Error, null, tint = if (ok) Verde else Rojo, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(texto, Modifier.weight(1f), fontSize = 14.sp)
        if (!ok && boton != null) TextButton(onClick = accion) { Text(boton) }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun TarjetaEstado() {
    val ctx = LocalContext.current
    val shizuku by Shizuku.estado.collectAsStateWithLifecycle()
    val cambios by Shizuku.cambios.collectAsStateWithLifecycle()
    val pm = ctx.getSystemService(PowerManager::class.java)
    val bateria = remember(cambios, shizuku) { pm.isIgnoringBatteryOptimizations(ctx.packageName) }
    val uso = remember(cambios, shizuku) { Uso.tienePermiso(ctx) }

    Tarjeta("Permisos", Icons.Default.Security) {
        FilaEstado(shizuku == EstadoShizuku.LISTO, shizuku.texto, when (shizuku) {
            EstadoShizuku.SIN_PERMISO -> "Dar permiso"
            EstadoShizuku.NO_ACTIVO -> "Abrir Shizuku"
            else -> null
        }) {
            if (shizuku == EstadoShizuku.SIN_PERMISO) Shizuku.pedirPermiso()
            else ctx.packageManager.getLaunchIntentForPackage(Shizuku.PAQUETE)?.let { ctx.startActivity(it) }
        }
        FilaEstado(bateria, if (bateria) "Batería sin restricciones" else "La batería puede cortar la app", "Permitir") {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }
        }
        FilaEstado(uso, if (uso) "Acceso al tiempo de uso" else "Falta acceso al tiempo de uso", "Activar") {
            if (shizuku == EstadoShizuku.LISTO) Shizuku.otorgarPermisos()
            else ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }
}

@Composable
private fun TarjetaRacha() {
    val ctx = LocalContext.current
    val v by Ajustes.version.collectAsStateWithLifecycle()
    var contacto by remember { mutableStateOf(Ajustes.rachaContacto) }
    var mensaje by remember { mutableStateOf(Ajustes.rachaMensaje) }
    var pin by remember { mutableStateOf("") }
    val tienePin = remember(v) { !Ajustes.pin.isNullOrBlank() }
    val activa = remember(v) { Ajustes.rachaActiva }
    val soloSiFalta = remember(v) { Ajustes.rachaSoloSiFalta }
    val hora = remember(v) { "%02d:%02d".format(Ajustes.rachaHora, Ajustes.rachaMinuto) }
    val resultado = remember(v) { Ajustes.rachaUltimoResultado }
    val dias = remember(v) { Ajustes.rachaDias }
    val hecha = remember(v) { Ajustes.rachaHechaHoy() }

    Tarjeta("Racha de TikTok", Icons.Default.LocalFireDepartment) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (dias > 0) "🔥 $dias días" else "🔥 Racha", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(if (hecha) "Hoy ya está cuidada ✅" else "Hoy todavía falta", color = if (hecha) Verde else Naranja, fontSize = 14.sp)
            }
            Switch(activa, { Ajustes.rachaActiva = it; Programador.programarRacha(ctx) })
        }
        OutlinedTextField(contacto, { contacto = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Nombre del chat (como aparece en Mensajes)") })
        OutlinedTextField(mensaje, { mensaje = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Mensaje diario") })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hora de envío: ", fontSize = 15.sp)
            TextButton(onClick = {
                TimePickerDialog(ctx, { _, h, m ->
                    Ajustes.rachaHora = h; Ajustes.rachaMinuto = m; Programador.programarRacha(ctx)
                }, Ajustes.rachaHora, Ajustes.rachaMinuto, true).show()
            }) { Text(hora, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("No mandar si ya le escribí hoy", Modifier.weight(1f), fontSize = 14.sp)
            Switch(soloSiFalta, { Ajustes.rachaSoloSiFalta = it })
        }
        OutlinedTextField(pin, { pin = it.filter { c -> !c.isWhitespace() } }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(if (tienePin) "Contraseña guardada (cifrada) · escribí otra para cambiarla" else "Contraseña del celular (para desbloquear solo)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                Ajustes.rachaContacto = contacto.trim(); Ajustes.rachaMensaje = mensaje.ifBlank { "🔥" }
                if (pin.isNotBlank()) { Ajustes.pin = pin; pin = "" }
                Programador.programarRacha(ctx)
            }) { Text("Guardar") }
            OutlinedButton(onClick = {
                Ajustes.rachaContacto = contacto.trim(); Ajustes.rachaMensaje = mensaje.ifBlank { "🔥" }
                if (!MotorRacha.enCurso) RachaService.iniciar(ctx, forzar = true)
            }) { Text("Enviar ahora") }
            if (tienePin) TextButton(onClick = { Ajustes.pin = null }) { Text("Borrar contraseña") }
        }
        Text("Último: $resultado", color = Color.Gray, fontSize = 13.sp)
        Text("Recordá: la racha sigue solo si los dos se escriben cada día. La app manda tu parte.",
            color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun TarjetaLimites(abrirApps: (Categoria) -> Unit) {
    val ctx = LocalContext.current
    val v by Ajustes.version.collectAsStateWithLifecycle()
    val estado by LimitesService.estado.collectAsStateWithLifecycle()
    val activos = remember(v) { Ajustes.limitesActivos }

    Tarjeta("Límites diarios", Icons.Default.Timer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Al llegar al límite, esas apps se pausan hasta las 00:00.", Modifier.weight(1f), fontSize = 13.sp, color = Color.Gray)
            Switch(activos, { Ajustes.limitesActivos = it; LimitesService.iniciar(ctx, LimitesService.ACCION_REVISAR) })
        }
        Categoria.entries.forEach { cat ->
            val u = estado.firstOrNull { it.categoria == cat }
            val limite = remember(v) { Ajustes.minutos(cat) }
            val usado = u?.usadoMin ?: 0
            HorizontalDivider(color = Color(0xFF2A303A))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cat.titulo, fontWeight = FontWeight.SemiBold)
                    Text("Usado hoy: ${LimitesService.formato(usado)} de ${LimitesService.formato(limite)}",
                        fontSize = 13.sp, color = if (u?.agotado == true) Rojo else Color.Gray)
                }
                IconButton(onClick = { Ajustes.fijarMinutos(cat, limite - 15); LimitesService.iniciar(ctx, LimitesService.ACCION_REVISAR) }) {
                    Icon(Icons.Default.Remove, "Menos")
                }
                IconButton(onClick = { Ajustes.fijarMinutos(cat, limite + 15); LimitesService.iniciar(ctx, LimitesService.ACCION_REVISAR) }) {
                    Icon(Icons.Default.Add, "Más")
                }
            }
            LinearProgressIndicator(
                progress = { if (limite == 0) 1f else (usado.toFloat() / limite).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = if (u?.agotado == true) Rojo else Naranja, trackColor = Color(0xFF2A303A),
            )
            Text("Apps controladas (${u?.paquetes?.size ?: 0}) · tocar para elegir", Modifier.clickable { abrirApps(cat) },
                color = Naranja, fontSize = 13.sp)
        }
        Text("WhatsApp, Telegram, Discord, Classroom y el Campus nunca se limitan.", fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable
private fun SelectorApps(cat: Categoria, volver: () -> Unit) {
    val ctx = LocalContext.current
    val instaladas = remember { Categoria.instaladas(ctx).filter { it.paquete !in Categoria.NUNCA } }
    var elegidas by remember { mutableStateOf(cat.paquetes(instaladas)) }
    val (marcadas, resto) = remember { instaladas.partition { it.paquete in elegidas } }
    val orden: List<AppInstalada> = remember { marcadas + resto }
    BackHandler(onBack = volver)

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = volver) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
            Text(cat.titulo, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = {
                Ajustes.fijarApps(cat, elegidas); LimitesService.iniciar(ctx, LimitesService.ACCION_REVISAR); volver()
            }) { Text("Guardar") }
        }
        TextButton(onClick = { elegidas = cat.porDefecto(instaladas) }) { Text("Restablecer las de por defecto") }
        LazyColumn(Modifier.fillMaxSize()) {
            items(orden, key = { it.paquete }) { app ->
                val marcada = app.paquete in elegidas
                Row(
                    Modifier.fillMaxWidth().clickable { elegidas = if (marcada) elegidas - app.paquete else elegidas + app.paquete }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(marcada, null)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(app.nombre)
                        Text(app.paquete, fontSize = 11.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
}
