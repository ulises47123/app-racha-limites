#!/usr/bin/env bash
# Entorno de desarrollo. Usa por defecto el JDK/SDK/Gradle ya descargados (solo lectura).
RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HERRAMIENTAS="${HERRAMIENTAS:-/c/Users/Usuario/Music/proyectos/app-mando-facil/.tools}"
export JAVA_HOME="$HERRAMIENTAS/jdk"
export ANDROID_HOME="$HERRAMIENTAS/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
if [ -x "$ANDROID_HOME/platform-tools/adb.exe" ]; then ADB="$ANDROID_HOME/platform-tools/adb.exe"; else ADB="$ANDROID_HOME/platform-tools/adb"; fi
export ADB
export MSYS2_ARG_CONV_EXCL="/data;/sdcard;/dev;/system;/storage;/proc;/vendor"
PAQUETE="com.rachalimites.app"
[ -f "$RAIZ/local.properties" ] || echo "sdk.dir=$(cd "$ANDROID_HOME" && pwd -W 2>/dev/null || pwd)" | sed 's/:/\:/' > "$RAIZ/local.properties"
