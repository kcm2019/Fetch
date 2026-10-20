#!/bin/bash
# Manual build for Fetch (framework APIs only, minSdk 25).
# Gradle is unusable in this sandbox (daemon dies even on `gradle help`),
# so this uses the SDK tools directly: aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-$HOME/toolchains/jdk-17.0.20.1+1}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK:-$HOME/android-sdk}"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
PROJ="$(cd "$(dirname "$0")" && pwd)"
OUT="$PROJ/build/manual"

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/classes" "$OUT/dex" "$OUT/apk"

echo "== aapt2 compile =="
"$BT/aapt2" compile --dir "$PROJ/src/main/res" -o "$OUT/res.zip"

echo "== aapt2 link =="
"$BT/aapt2" link -o "$OUT/apk/unsigned.apk" \
  -I "$AJAR" \
  --manifest "$PROJ/src/main/AndroidManifest.xml" \
  --min-sdk-version 25 --target-sdk-version 25 \
  "$OUT/res.zip"

echo "== javac (source/target 8 against android.jar) =="
find "$PROJ/src/main/java" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -cp "$AJAR" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "== d8 (min-api 25) =="
find "$OUT/classes" -name '*.class' > "$OUT/classfiles.txt"
"$BT/d8" --min-api 25 --lib "$AJAR" --output "$OUT/dex" @"$OUT/classfiles.txt"

echo "== add classes.dex =="
cp "$OUT/apk/unsigned.apk" "$OUT/apk/app.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/apk/app.apk" classes.dex)

echo "== zipalign =="
"$BT/zipalign" -f -p 4 "$OUT/apk/app.apk" "$OUT/apk/aligned.apk"

echo "== apksigner =="
"$BT/apksigner" sign \
  --ks "$PROJ/keystore.jks" --ks-pass pass:CHANGEME --key-pass pass:CHANGEME \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT/apk/Fetch.apk" "$OUT/apk/aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT/apk/Fetch.apk" | head -12
ls -lh "$OUT/apk/Fetch.apk"
echo "BUILD OK"
