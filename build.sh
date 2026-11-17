#!/usr/bin/env bash
# Builds the APK without Gradle or the Android SDK, using only tools from apt and Maven Central.
#   sudo apt-get install aapt zipalign apksigner dalvik-exchange
set -euo pipefail
cd "$(dirname "$0")"

TOOLS=.tools
OUT=build
FRAMEWORK="$TOOLS/android-all.jar"
FRAMEWORK_URL=https://repo.maven.apache.org/maven2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar

mkdir -p "$TOOLS"
# Android 14 framework classes and resources, used only to compile and link against.
[ -f "$FRAMEWORK" ] || curl -sSfL -o "$FRAMEWORK" "$FRAMEWORK_URL"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

aapt2 compile --dir app/res -o "$OUT/res.zip"
aapt2 link -o "$OUT/unsigned.apk" -I "$FRAMEWORK" --manifest app/AndroidManifest.xml \
    --java "$OUT/gen" --min-sdk-version 29 --target-sdk-version 34 "$OUT/res.zip"

javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -classpath "$FRAMEWORK" -d "$OUT/classes" \
    $(find app/src "$OUT/gen" -name '*.java')

dalvik-exchange --dex --min-sdk-version=29 --output="$OUT/dex/classes.dex" "$OUT/classes"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)

zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# A fixed signing key keeps new builds installable over old ones.
[ -f signing.keystore ] || keytool -genkeypair -keystore signing.keystore -storepass android -keypass android \
    -alias app -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=VideoCompressor"
mkdir -p apk
apksigner sign --v2-signing-enabled true --ks signing.keystore --ks-pass pass:android --key-pass pass:android \
    --out apk/VideoCompressor.apk "$OUT/aligned.apk"
rm -f apk/VideoCompressor.apk.idsig
apksigner verify --verbose apk/VideoCompressor.apk | head -5
ls -la apk/VideoCompressor.apk
