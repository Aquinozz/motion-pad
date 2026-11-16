#!/usr/bin/env bash
# Compila os ajudantes (PairHelper, SetCod) para .dex e envia ao celular via adb.
# Uso: ANDROID_HOME=/caminho/do/sdk ./build.sh [serial-do-adb]
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:?defina ANDROID_HOME}"
JAR="$SDK/platforms/android-35/android.jar"
D8="$SDK/build-tools/35.0.0/d8"; [ -x "$D8" ] || D8="$D8.bat"
ADB="$SDK/platform-tools/adb"; [ -n "${1:-}" ] && ADB="$ADB -s $1"
for cls in PairHelper SetCod; do
  rm -rf out && mkdir out
  javac --release 17 -cp "$JAR" -d out "$cls.java"
  "$D8" --min-api 28 --lib "$JAR" --output . out/*.class
  mv classes.dex "$(echo "$cls" | tr 'A-Z' 'a-z').dex"
  MSYS_NO_PATHCONV=1 $ADB push "$(echo "$cls" | tr 'A-Z' 'a-z').dex" /data/local/tmp/ >/dev/null
  echo "enviado: /data/local/tmp/$(echo "$cls" | tr 'A-Z' 'a-z').dex"
done
rm -rf out
