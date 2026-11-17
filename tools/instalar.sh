#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"; cd "$RAIZ"
"$ADB" install -r -t app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am start -n "$PAQUETE/.ui.MainActivity"
