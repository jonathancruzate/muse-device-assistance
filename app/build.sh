#!/bin/bash
# Build Muse Device Assistance (framework-only Android app, NO AndroidX — the
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
VERSION_CODE=16
VERSION_NAME="1.7.0"
PKG_PATH="com/jonathancruzate/metamusedeviceassistance"

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
# v1.4 libs stage (design: docs/loopback-shell-bridge-v1.4-design-2026-11-04.md,
# Route B): the vendored jars in libs/ are the extracted classes.jar of
# libadb-android 3.1.0, conscrypt-android 2.5.3, sun-security-android 1.1,
# spake2-android 2.2.0, plus bcprov-jdk15to18 1.81 — provenance + SHA-256
# hashes recorded in the design doc. libs/ is local-only build input
# (like the keystore): fetched artifacts, never committed.
LIB_JARS="$(ls libs/*.jar)"
LIB_CP="$(echo "$LIB_JARS" | tr '\n' ':' | sed 's/:$//')"
find src build/gen -name "*.java" > build/sources.txt
# No -bootclasspath (see Muse Voicemail build note): JDK 21 javac +
# android.jar on the classpath at source 11; d8 keeps lambdas at min-api 29.
javac -source 11 -target 11 -cp "$ANDROID_JAR:$LIB_CP" -d build/classes @build/sources.txt

echo "== guard: every app class compiled non-empty =="
CLASS_COUNT="$(find build/classes/$PKG_PATH -name '*.class' | wc -l)"
if [ "$CLASS_COUNT" -lt 10 ]; then
  echo "GUARD FAIL: only $CLASS_COUNT app classes compiled" >&2; exit 1
fi
echo "app classes: $CLASS_COUNT"

echo "== d8 =="
"$BT/d8" --release --min-api 29 --lib "$ANDROID_JAR" \
  --output build/dex $(find build/classes -name "*.class") $LIB_JARS

echo "== guard: dex defines every app class + the named v1.4 libs, references no banned androidx =="
cat build/dex/*.dex > /dev/null  # all dex shards present
"$BT/dexdump" -f build/dex/classes.dex > build/dexdump.txt
for extra in build/dex/classes2.dex build/dex/classes3.dex; do
  [ -f "$extra" ] && "$BT/dexdump" -f "$extra" >> build/dexdump.txt
done
for cls in $(cd build/classes && find $PKG_PATH -name '*.class' | sed 's#/#.#g; s#\.class$##'); do
  grep -q "Class descriptor.*L$(echo "$cls" | tr '.' '/');" build/dexdump.txt \
    || { echo "GUARD FAIL: $cls missing from dex" >&2; exit 1; }
done
# The named Route-B libraries must actually be in the dex (a silently
# dropped jar would ship a bridge that crashes on first command).
for libcls in "Lio/github/muntashirakon/adb/AbsAdbConnectionManager;" \
              "Lorg/conscrypt/Conscrypt;" \
              "Landroid/sun/security/x509/X509CertImpl;" \
              "Lorg/bouncycastle/jce/provider/BouncyCastleProvider;" \
              "Lio/github/muntashirakon/crypto/spake2/Spake2Context;"; do
  grep -q "Class descriptor.*$libcls" build/dexdump.txt \
    || { echo "GUARD FAIL: library class $libcls missing from dex" >&2; exit 1; }
done
# AndroidX ban (amended for Route B per the design): no androidx class
# may be DEFINED or referenced, except the compile-time annotation
# artifact (androidx.annotation) that the named libraries carry —
# annotation classes are stripped by d8 and never execute.
if grep -E "Landroidx/" build/dexdump.txt | grep -qvE "Landroidx/annotation/"; then
  echo "GUARD FAIL: non-annotation androidx reference in dex" >&2; exit 1
fi
echo "dex guards passed"

echo "== package =="
cp build/unsigned.apk build/staged.apk
# classes*.dex must sit at the APK ROOT (see Muse Voicemail build note).
(cd build/dex && zip -q ../staged.apk *.dex)
# Native libs for the Route-B stack (conscrypt JNI + spake2 JNI), laid
# out as lib/<abi>/; the manifest sets extractNativeLibs=true, so they
# are extracted at install and need no page alignment.
mkdir -p build/nativelib/lib
cp -r libs/jni/* build/nativelib/lib/
(cd build/nativelib && zip -q -r ../staged.apk lib)
"$BT/zipalign" -f 4 build/staged.apk build/aligned.apk

echo "== sign =="
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --out "Muse-Device-Assistance-$VERSION_NAME.apk" build/aligned.apk

echo "== verify =="
"$BT/apksigner" verify --print-certs "Muse-Device-Assistance-$VERSION_NAME.apk"
echo "== guard: native libs packaged =="
for so in lib/arm64-v8a/libconscrypt_jni.so lib/arm64-v8a/libspake2.so; do
  unzip -l "Muse-Device-Assistance-$VERSION_NAME.apk" | grep -q "$so" \
    || { echo "GUARD FAIL: $so missing from APK" >&2; exit 1; }
done
echo "native lib guards passed"
ls -l "Muse-Device-Assistance-$VERSION_NAME.apk"
sha256sum "Muse-Device-Assistance-$VERSION_NAME.apk"
