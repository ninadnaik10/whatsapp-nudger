#!/usr/bin/env bash
# Build, install, set up and launch WhatsApp Nudger on a connected Android phone — no Android Studio.
#
#   ./run-on-phone.sh          build + install + push model + grant permissions + launch
#   ./run-on-phone.sh logs     follow the app's logs
#   ./run-on-phone.sh pair     pair over Wi-Fi (Android 11+ Wireless debugging)
#
# Overridable env: JAVA_HOME, ANDROID_HOME, ANDROID_SERIAL (pick a device when several are connected).
set -euo pipefail
cd "$(dirname "$0")"

PKG=com.hackday.notifrouter
MODEL_NAME=gemma-4-E2B-it.litertlm
LOCAL_MODEL=models/$MODEL_NAME
DEVICE_DIR=/sdcard/Android/data/$PKG/files

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
unset ANDROID_SDK_ROOT # a stale value here confuses the Android Gradle plugin
if [[ -z "${JAVA_HOME:-}" && -x "$HOME/.local/jdks/jdk-21/bin/javac" ]]; then
  export JAVA_HOME="$HOME/.local/jdks/jdk-21"
fi
ADB="$ANDROID_HOME/platform-tools/adb"

die() { echo "error: $*" >&2; exit 1; }
[[ -x "$ADB" ]] || die "adb not found at $ADB (set ANDROID_HOME)"
[[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]] || die "need a JDK 17–21; set JAVA_HOME"

case "${1:-run}" in
  logs)
    exec "$ADB" logcat -v time -s RouterListener:V Router:V GemmaEngine:V NotificationParser:V AndroidRuntime:E
    ;;
  pair)
    echo "On the phone: Developer options → Wireless debugging → Pair device with pairing code."
    read -rp "Pairing IP:port shown on phone: " pair_addr
    "$ADB" pair "$pair_addr"
    read -rp "IP:port shown under 'IP address & port' (not the pairing one): " conn_addr
    "$ADB" connect "$conn_addr"
    exit 0
    ;;
  run) ;;
  *) die "unknown command '$1' (use: run | logs | pair)" ;;
esac

# 1. Device
mapfile -t devices < <("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
unauthorized=$("$ADB" devices | awk 'NR>1 && ($2=="unauthorized" || $2=="no") {print $1}')
if [[ ${#devices[@]} -eq 0 ]]; then
  [[ -n "$unauthorized" ]] && echo "Phone found but not authorized: unlock it and accept the 'Allow USB debugging?' prompt." >&2
  cat >&2 <<'EOF'
No phone connected. On the phone:
  1. Settings → About phone → tap "Build number" 7 times (enables Developer options)
  2. Settings → System → Developer options → enable "USB debugging"
  3. Plug in via USB and accept the prompt
     (or use Wi-Fi: enable "Wireless debugging" and run ./run-on-phone.sh pair)
If adb shows "no permissions" over USB on Linux, use Wi-Fi pairing instead.
EOF
  exit 1
fi
if [[ ${#devices[@]} -gt 1 && -z "${ANDROID_SERIAL:-}" ]]; then
  die "several devices connected (${devices[*]}); set ANDROID_SERIAL=<id>"
fi
export ANDROID_SERIAL="${ANDROID_SERIAL:-${devices[0]}}"
model_info=$("$ADB" shell 'echo "$(getprop ro.product.manufacturer) $(getprop ro.product.model), Android $(getprop ro.build.version.release)"')
echo "==> Device: $ANDROID_SERIAL ($model_info)"

# 2. Build + install
echo "==> Building and installing (first run downloads dependencies)…"
./gradlew --console=plain -q installDebug

# 3. Model
if [[ -f "$LOCAL_MODEL" ]]; then
  local_size=$(stat -c %s "$LOCAL_MODEL")
  remote_size=$("$ADB" shell "stat -c %s $DEVICE_DIR/$MODEL_NAME 2>/dev/null" | tr -d '\r' || true)
  if [[ "$remote_size" == "$local_size" ]]; then
    echo "==> Model already on phone"
  else
    echo "==> Pushing model (~$((local_size / 1000000)) MB, takes a minute or two over USB)…"
    "$ADB" shell mkdir -p "$DEVICE_DIR"
    "$ADB" push "$LOCAL_MODEL" "$DEVICE_DIR/"
  fi
else
  echo "==> No $LOCAL_MODEL yet; download it with:"
  echo "    curl -L -o $LOCAL_MODEL https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$MODEL_NAME"
  echo "    (the app still runs; it uses the rule-based fallback until the model is present)"
fi

# 4. Permissions — adb can grant these directly, including notification-listener access.
echo "==> Granting permissions"
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
"$ADB" shell pm grant "$PKG" android.permission.READ_CONTACTS 2>/dev/null || true
"$ADB" shell cmd notification allow_listener "$PKG/$PKG.service.RouterListenerService" \
  || echo "    couldn't grant notification access automatically; grant it from the app's Setup card"

# 5. Launch
echo "==> Launching"
"$ADB" shell am start -n "$PKG/.ui.MainActivity" >/dev/null
echo "Done. Tap 'Load model' in the app, then try a message. Follow logs with: ./run-on-phone.sh logs"
