#!/bin/bash
# Baut Skyline ohne Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner
# Läuft unter Windows (Git Bash) und Linux (GitHub Actions).
#   VERSION_CODE / VERSION_NAME   überschreiben die Werte aus dem Manifest (CI: fortlaufende Build-Nummer)
#   KEYSTORE / KEYSTORE_PASS      Signatur (Standard: skyline.keystore neben diesem Skript, Passwort skyline)
set -e -o pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
BT="$SDK/build-tools/35.0.0"; JAR="$SDK/platforms/android-34/android.jar"
if [ -f "$BT/aapt2.exe" ]; then EXE=.exe; BAT=.bat; PY=python; else EXE=; BAT=; PY=python3; fi
KS="${KEYSTORE:-skyline.keystore}"; KSP="${KEYSTORE_PASS:-skyline}"

VER=()
[ -n "$VERSION_CODE" ] && VER+=(--version-code "$VERSION_CODE")
[ -n "$VERSION_NAME" ] && VER+=(--version-name "$VERSION_NAME")
[ ${#VER[@]} -gt 0 ] && VER+=(--replace-version)

rm -rf build; mkdir -p build/res build/classes build/dex build/gen
"$BT/aapt2$EXE" compile --dir res -o build/res/
"$BT/aapt2$EXE" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml --java build/gen -A assets build/res/*.flat --auto-add-overlay -0 jpg "${VER[@]}"
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$JAR" -encoding UTF-8 -d build/classes $(find src build/gen -name '*.java')
"$BT/d8$BAT" --release --min-api 28 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
$PY mkapk.py build/base.apk build/dex/classes.dex build/unsigned.apk
"$BT/zipalign$EXE" -f -p 4 build/unsigned.apk build/aligned.apk
if [ ! -f "$KS" ]; then
  if [ -n "$CI" ]; then echo "Keystore fehlt (Secret SKYLINE_KEYSTORE)" >&2; exit 1; fi
  "/c/Program Files/Java/jdk-23/bin/keytool.exe" -genkeypair -keystore "$KS" -storepass "$KSP" -keypass "$KSP" -alias skyline -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Skyline, O=wesnik" >/dev/null 2>&1
fi
"$BT/apksigner$BAT" sign --ks "$KS" --ks-pass "pass:$KSP" --ks-key-alias skyline --out skyline.apk build/aligned.apk
ls -la skyline.apk
