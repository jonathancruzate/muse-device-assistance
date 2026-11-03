#!/bin/bash
# Build Muse Phone Bridge (framework-only Android app, NO AndroidX — the
# raw build therefore cannot hit the KMP empty-jar trap documented in
# AGENTS.md; the guards below enforce that: the build FAILS if the dex
# references any androidx class or if any app class is missing from it).
# Toolchain: meta-muse-widget tools/sdk (build-tools 35.0.0, platform
# android-35), used READ-ONLY for compilation, + Temurin JDK 21.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-$HOME/workspace/meta-muse-widget/tools/sdk}"
export JAVA_HOME="$HOME/workspace/custom-skills/local-rea/tools/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
KS="keystore/musephonebridge.keystore"
KS_PASS_FILE="keystore/keystore-password.txt"
VERSION_CODE=3
VERSION_NAME="1.2.0"
PKG_PATH="com/jonathancruzate/metamusephonebridge"

rm -rf build
mkdir -p build/gen build/classes build/dex keystore

if [ ! -f "$KS" ]; then
  PASS="$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)"
  echo "$PASS" > "$KS_PASS_FILE"
  chmod 600 "$KS_PASS_FILE"
  keytool -genkeypair -keystore "$KS" -alias musephonebridge -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=Muse Phone Bridge, O=JonathanCruzate, C=US" 2>/dev/null
  echo "keystore created"
fi
PASS="$(cat "$KS_PASS_FILE")"

echo "== aapt2 compile + link =="
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --java build/gen \
  --min-sdk-version 29 --target-sdk-version 35 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  build/res.zip

echo "== javac =="
find src build/gen -name "*.java" > build/sources.txt
# No -bootclasspath (see Muse Voicemail build note): JDK 21 javac +
# android.jar on the classpath at source 11; d8 keeps lambdas at min-api 29.
javac -source 11 -target 11 -cp "$ANDROID_JAR" -d build/classes @build/sources.txt

echo "== guard: every app class compiled non-empty =="
CLASS_COUNT="$(find build/classes/$PKG_PATH -name '*.class' | wc -l)"
if [ "$CLASS_COUNT" -lt 10 ]; then
  echo "GUARD FAIL: only $CLASS_COUNT app classes compiled" >&2; exit 1
fi
echo "app classes: $CLASS_COUNT"

echo "== d8 =="
"$BT/d8" --release --min-api 29 --lib "$ANDROID_JAR" \
  --output build/dex $(find build/classes -name "*.class")

echo "== guard: dex defines every app class, references no androidx =="
"$BT/dexdump" -f build/dex/classes.dex > build/dexdump.txt
for cls in $(cd build/classes && find $PKG_PATH -name '*.class' | sed 's#/#.#g; s#\.class$##'); do
  grep -q "Class descriptor.*L$(echo "$cls" | tr '.' '/');" build/dexdump.txt \
    || { echo "GUARD FAIL: $cls missing from dex" >&2; exit 1; }
done
if grep -q "Landroidx/" build/dexdump.txt; then
  echo "GUARD FAIL: androidx reference in dex" >&2; exit 1
fi
echo "dex guards passed"

echo "== package =="
cp build/unsigned.apk build/staged.apk
# classes.dex must sit at the APK ROOT (see Muse Voicemail build note).
(cd build/dex && zip -q ../staged.apk classes.dex)
"$BT/zipalign" -f 4 build/staged.apk build/aligned.apk

echo "== sign =="
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --out "Muse-Phone-Bridge-$VERSION_NAME.apk" build/aligned.apk

echo "== verify =="
"$BT/apksigner" verify --print-certs "Muse-Phone-Bridge-$VERSION_NAME.apk"
ls -l "Muse-Phone-Bridge-$VERSION_NAME.apk"
sha256sum "Muse-Phone-Bridge-$VERSION_NAME.apk"
