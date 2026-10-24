#!/bin/bash
# Manual APK build using Android SDK build-tools only (no Gradle daemon).
# Works anywhere JAVA_HOME (JDK 17) and an Android SDK with platform 34 +
# build-tools 34 are available. The same sources also build in Android Studio.
set -e
SDK="${ANDROID_HOME:-$HOME/workspace/tools/android-sdk}"
JAVABIN="${JAVA_HOME:-$HOME/workspace/tools/jdk17}/bin"
BT="$SDK/build-tools/34.0.0"
export PATH="${JAVA_HOME:-$HOME/workspace/tools/jdk17}/bin:$PATH"
AJAR="$SDK/platforms/android-34/android.jar"
SRC="$(cd "$(dirname "$0")" && pwd)/app-project/app/src/main"
OUT="$(cd "$(dirname "$0")" && pwd)/build-out"
KS="$(cd "$(dirname "$0")" && pwd)/muse-recorder.keystore"
rm -rf "$OUT"; mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/compiled.zip"
# aapt2 (unlike AGP) requires a package attribute in the manifest; inject it
# into a build copy so the source manifest stays AGP-style (namespace only).
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="personal.muse.recorder">|' \
  "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" -I "$AJAR" \
  --manifest "$OUT/AndroidManifest.xml" --java "$OUT/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  -A "$SRC/assets" \
  --version-code 22 --version-name 1.8.6 "$OUT/compiled.zip"

find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JAVABIN/javac" -encoding UTF-8 -cp "$AJAR" -d "$OUT/classes" @"$OUT/sources.txt"
(cd "$OUT/classes" && "$JAVABIN/jar" cf "$OUT/classes.jar" .)
"$BT/d8" --min-api 26 --lib "$AJAR" --output "$OUT/dex" "$OUT/classes.jar"

cp "$OUT/app-unsigned.apk" "$OUT/app.apk"
(cd "$OUT/dex" && zip -q "$OUT/app.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/app.apk" "$OUT/app-aligned.apk"

if [ ! -f "$KS" ]; then
  KSPASS="$(python3 -c "import secrets; print(secrets.token_hex(12))")"
  echo "keystore password: $KSPASS (alias: muse, keep this file!)" \
    > "$(dirname "$KS")/keystore-info.txt"
  "$JAVABIN/keytool" -genkeypair -keystore "$KS" -storepass "$KSPASS" \
    -keypass "$KSPASS" -alias muse -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Muse Recorder, O=Personal, C=CN" >/dev/null 2>&1
fi
KSPASS="$(sed -n 's/keystore password: \([a-z0-9]*\).*/\1/p' "$(dirname "$KS")/keystore-info.txt")"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
  --out "$(dirname "$KS")/MuseRecorder.apk" "$OUT/app-aligned.apk"
"$BT/apksigner" verify "$(dirname "$KS")/MuseRecorder.apk"
echo "BUILT: $(dirname "$KS")/MuseRecorder.apk"
