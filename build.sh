#!/usr/bin/env bash
# Dependency-free build: aapt + javac + dx + zipalign. No Gradle, no Maven.
set -euo pipefail

SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
BT="$SDK/build-tools/25.0.3"
JAR="$SDK/platforms/android-25/android.jar"
SRC=app/src/main
OUT=build

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes"

echo "== 1/6 resources -> R.java"
"$BT/aapt" package -f -m -J "$OUT/gen" -M $SRC/AndroidManifest.xml -S $SRC/res -I "$JAR"

echo "== 2/6 javac (Java 7 bytecode)"
find $SRC/java "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 1.7 -target 1.7 -bootclasspath "$JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== 3/6 dex"
"$BT/dx" --dex --output="$OUT/classes.dex" "$OUT/classes"

echo "== 4/6 package"
"$BT/aapt" package -f -M $SRC/AndroidManifest.xml -S $SRC/res -I "$JAR" -F "$OUT/unsigned.apk"
( cd "$OUT" && "$BT/aapt" add unsigned.apk classes.dex )

echo "== 5/6 sign (SHA1: required by Android 2.x)"
[ -f "$OUT/debug.keystore" ] || keytool -genkeypair -keystore "$OUT/debug.keystore" \
  -storepass android -keypass android -alias debug -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=Debug,O=Android,C=US"
jarsigner -keystore "$OUT/debug.keystore" -storepass android -keypass android \
  -sigalg SHA1withRSA -digestalg SHA1 "$OUT/unsigned.apk" debug

echo "== 6/6 zipalign"
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/WildfireCam-debug.apk"
ls -l "$OUT/WildfireCam-debug.apk"
