#!/usr/bin/env bash
# Builds a signed release APK to share and sideload: dist/SongWriter-<version>.apk
#
#   scripts/build-apk.sh        (or: make apk)
#
# The first run creates a release signing key at ~/.android/song-writer-release.jks and writes its
# passwords to keystore.properties (both stay out of git). Back both up: Android only installs an
# update signed with the same key, so a new key means uninstalling the app, which deletes its songs.
set -euo pipefail

cd "$(dirname "$0")/.."

# Toolchain from docs/emulator-setup.md, unless the shell already set it up.
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"

KEYSTORE="${SONG_WRITER_KEYSTORE:-$HOME/.android/song-writer-release.jks}"
ALIAS="song-writer"

if [[ ! -f keystore.properties ]]; then
  if [[ -f "$KEYSTORE" ]]; then
    echo "error: $KEYSTORE exists but keystore.properties doesn't. Restore keystore.properties from your backup." >&2
    exit 1
  fi
  echo "Creating a release signing key at $KEYSTORE"
  PASSWORD="$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)"
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -v -keystore "$KEYSTORE" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$PASSWORD" -keypass "$PASSWORD" -dname "CN=Song Writer" >/dev/null 2>&1
  umask 077
  cat > keystore.properties <<PROPS
# Release signing for scripts/build-apk.sh. Never commit this file; back it up with the keystore.
storeFile=$KEYSTORE
storePassword=$PASSWORD
keyAlias=$ALIAS
keyPassword=$PASSWORD
PROPS
  echo "Back up $KEYSTORE and keystore.properties somewhere safe."
fi

# Every build gets a higher version code, so it installs over the previous one: yyMMddHH.
VERSION_CODE="$(date +%y%m%d%H)"
BASE_VERSION="$(sed -n 's/.*?: "\([0-9][0-9.]*\)".*/\1/p' app/build.gradle.kts | head -1)"
VERSION_NAME="${BASE_VERSION:-0.1.0}-$(date +%Y%m%d.%H%M)"

echo "Building release $VERSION_NAME (code $VERSION_CODE)"
./gradlew -q assembleRelease -PversionCode="$VERSION_CODE" -PversionName="$VERSION_NAME"

APK="app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$APK" ]]; then
  echo "error: $APK wasn't produced (is the release build unsigned?)" >&2
  exit 1
fi

APKSIGNER="$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
if [[ -n "$APKSIGNER" ]]; then
  "$APKSIGNER" verify "$APK"
  CERT="$("$APKSIGNER" verify --print-certs "$APK" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)"
fi

mkdir -p dist
OUT="dist/SongWriter-$VERSION_NAME.apk"
cp "$APK" "$OUT"

echo
echo "APK:     $OUT ($(du -h "$OUT" | cut -f1))"
echo "SHA-256: $(shasum -a 256 "$OUT" | cut -d' ' -f1)"
[[ -n "${CERT:-}" ]] && echo "Signed:  $CERT"
echo
echo "To install: send the file to the phone and open it (allow installs from that app when asked),"
echo "or: adb install -r \"$OUT\""
