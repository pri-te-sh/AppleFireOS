#!/usr/bin/env zsh
# Builds and signs build/AppleFireOS.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Usage: ./build.sh                    build it
#        FIRETV=<tv-ip> ./build.sh --install   build, then install and set it up on that TV (setup.sh)
# Needs a JDK (8 or newer) and the Android SDK with build-tools and an android-33 platform; set
# ANDROID_HOME if it isn't found by itself.
set -euo pipefail
cd "$(dirname "$0")"

for SDK in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${ANDROID_SDK:-}" "$HOME/Library/Android/sdk" \
           "$HOME/Android/Sdk" "$HOME/Library/Developer/Xamarin/android-sdk-macosx"; do
    [[ -n "$SDK" && -d "$SDK/build-tools" ]] && break
done
[[ -d "$SDK/build-tools" ]] || { echo "Android SDK not found: set ANDROID_HOME." >&2; exit 1; }
# The newest build-tools installed, unless BUILD_TOOLS names one.
BT="$SDK/build-tools/${BUILD_TOOLS:-$(ls "$SDK/build-tools" | sort -V | tail -1)}"
JAR="$SDK/platforms/android-33/android.jar"
[[ -f "$JAR" ]] || { echo "Missing $JAR: install the Android 13 (API 33) platform." >&2; exit 1; }
OUT="${OUT:-build}"
APK="$OUT/AppleFireOS.apk"

# One design language: screens must use Theme / Kit / Row / Motion (see tools/design-lint.sh).
tools/design-lint.sh || { echo "Fix the styling above (or use the design system) and build again." >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/assets"

[[ -d assets ]] && cp -R assets/ "$OUT/assets/"
# TMDB key for the Top Shelf: put it in tmdb.key (or $TMDB_KEY). Without one the shelf shows app names.
TMDB_KEY="${TMDB_KEY:-$( [[ -f tmdb.key ]] && tr -d ' \n\r' < tmdb.key || true )}"
printf '%s' "$TMDB_KEY" > "$OUT/assets/tmdb.key"

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --java "$OUT/gen" -A "$OUT/assets" \
    -o "$OUT/unsigned.apk" "$OUT/res.zip"

javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
    -bootclasspath "$JAR" -classpath "$BT/core-lambda-stubs.jar" -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java')
"$BT/d8" --release --min-api 25 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)

"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [[ ! -f keystore.jks ]]; then
    keytool -genkeypair -keystore keystore.jks -storepass android -keypass android \
        -alias applefireos -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AppleFireOS" >/dev/null
fi
"$BT/apksigner" sign --ks keystore.jks --ks-pass pass:android --out "$APK" "$OUT/aligned.apk"
rm -f "$OUT/unsigned.apk" "$OUT/aligned.apk" "$APK.idsig"
echo "Built $APK"

if [[ "${1:-}" == "--install" ]]; then
    [[ -n "${FIRETV:-}" ]] || { echo "Set FIRETV to your TV's IP address: FIRETV=192.168.1.20 ./build.sh --install" >&2; exit 1; }
    ADB="${ADB:-$SDK/platform-tools/adb}" ./setup.sh "$FIRETV" --apk "$APK"
fi
