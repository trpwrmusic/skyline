#!/bin/bash
# Baut die Handy-Fernbedienung (de.wesnik.skyline.remote) ohne Gradle und legt sie in die Beamer-App
# (launcher/assets/remote/skyline-remote.apk, der Beamer liefert sie unter /skyline-remote.apk aus).
# Läuft unter Windows (Git Bash) und Linux (GitHub Actions). KEYSTORE / KEYSTORE_PASS wie bei launcher/build.sh.
set -e -o pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
BT="$SDK/build-tools/35.0.0"; JAR="$SDK/platforms/android-34/android.jar"
if [ -f "$BT/aapt2.exe" ]; then EXE=.exe; BAT=.bat; PY=python; else EXE=; BAT=; PY=python3; fi
KS="${KEYSTORE:-../launcher/skyline.keystore}"; KSP="${KEYSTORE_PASS:-skyline}"
rm -rf build; mkdir -p build/res build/classes build/dex build/gen
"$BT/aapt2$EXE" compile --dir res -o build/res/
"$BT/aapt2$EXE" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml --java build/gen build/res/*.flat --auto-add-overlay -0 jpg
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$JAR" -encoding UTF-8 -d build/classes $(find src build/gen -name '*.java')
"$BT/d8$BAT" --release --min-api 24 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
$PY ../launcher/mkapk.py build/base.apk build/dex/classes.dex build/unsigned.apk
"$BT/zipalign$EXE" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner$BAT" sign --ks "$KS" --ks-pass "pass:$KSP" --ks-key-alias skyline --out skyline-remote.apk build/aligned.apk
cp skyline-remote.apk ../launcher/assets/remote/skyline-remote.apk
ls -la skyline-remote.apk
