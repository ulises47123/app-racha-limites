# Racha y Límites

App Android (Xiaomi/HyperOS, Android 15) en español con dos funciones:

1. **Racha de TikTok**: todos los días a la hora elegida abre TikTok, busca el chat por su nombre
   en la bandeja de Mensajes, escribe el mensaje (por defecto 🔥), toca **Enviar** y comprueba que
   salió. Si ese día ya le escribiste a mano, no manda nada.
2. **Límites diarios**: cuenta el tiempo de hoy por categoría y, al llegar al límite, pausa esas apps
   con `pm suspend` (aparece "La app no está disponible" con un mensaje propio) hasta las 00:00.

| Categoría | Límite | Apps por defecto |
|---|---|---|
| Redes sociales | 1 h | TikTok, Facebook, Instagram, Pinterest (+ otras conocidas si se instalan) |
| Juegos | 2 h | Minecraft, Roblox, Brawl Stars, BombSquad… + toda app que Android marque como juego |
| Películas y series | 1 h 30 | GoldenTV, Prime Video, Disney+, NewPipe, YouTube, Netflix, reproductor propio |

WhatsApp, Telegram, Signal, Discord, Classroom, el Campus IES 5, Chrome y Meet **nunca** se limitan.
Las listas se editan desde la app ("Apps controladas").

## Cómo funciona

- **Shizuku (uid shell)**: `input`, `am`, `uiautomator dump`, `pm suspend/unsuspend`, `appops`.
  Patrón de `Shell.kt` reutilizado de Automatic / MandoFácil.
- **TikTok sin coordenadas fijas**: se entra por `snssdk1233://inbox` (directo a Mensajes; sobre el
  feed de videos `uiautomator` falla con "could not get idle state") y los botones se buscan por
  texto/descripción: fila `:id/user_name`, campo `EditText`, botón con descripción "Enviar".
- El nombre del chat se compara normalizado (sin espacios ni marcas invisibles): el nombre se configura
  en la app (el valor inicial sale de `racha.properties`, que no se sube; ver abajo).
- Emojis y acentos se escriben por el portapapeles + `KEYCODE_PASTE` (`input text` solo acepta ASCII).
- **Desbloqueo**: secuencia de agente-whatsapp-ia (`wm dismiss-keyguard` → contraseña → deslizar →
  contraseña), máximo 2 intentos de contraseña y 5 min de pausa tras un fallo para no trabar el
  teléfono. La contraseña se guarda cifrada (Jetpack Security) y no se escribe en los logs.
  Si no hay contraseña guardada, la racha se manda **apenas desbloqueás** el celular.
- **Escudo de toques** (patrón de Automatic): mientras se manda la racha, una capa a pantalla completa
  se traga los toques del usuario y se vuelve atravesable solo durante cada `input tap` de la app.
  Mantener apretado el botón rojo 1,5 s cancela. Al terminar: inicio, `am force-stop` de TikTok,
  se quita el escudo y, si el teléfono estaba bloqueado, se vuelve a bloquear.
- **Reintentos**: si falla, vuelve a probar cada 15–30 min hasta las 23:45 y avisa con una
  notificación con botón "Enviar ahora".
- **Batería**: al tener Shizuku se agrega sola a la lista blanca de batería, inicio automático de
  HyperOS, alarmas exactas y acceso al tiempo de uso. Arranca sola al prender el teléfono.
- **Medianoche**: alarma a las 00:00:05 que reactiva todo lo pausado.

## Compilar e instalar

Opcional: copiá `racha.properties.example` a `racha.properties` y poné el nombre del chat de TikTok
que querés usar por defecto (también se puede cambiar dentro de la app).

```bash
bash tools/compilar.sh          # usa el JDK/SDK de la carpeta HERRAMIENTAS (jdk/ y android-sdk/)
adb -s <dispositivo> install -r -t app/build/outputs/apk/debug/app-debug.apk
```

## Límites conocidos

- La racha de TikTok solo sigue si **los dos** se escriben cada día: la app manda tu parte.
- Si TikTok cambia los textos ("Enviar", `user_name`), hay que ajustar `racha/MotorRacha.kt`.
- Si desinstalás la app con apps pausadas, se reactivan con `adb shell pm unsuspend <paquete>`.
