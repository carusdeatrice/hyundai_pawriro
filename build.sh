#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
if [[ -z "${JAVA_HOME:-}" && -x "$PWD/build/toolchain/jdk/bin/java" ]]; then
    JAVA_HOME="$PWD/build/toolchain/jdk"
fi
if [[ -z "${JAVA_HOME:-}" ]]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
fi
if [[ -z "$JAVA_HOME" || ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "Set JAVA_HOME to JDK 21 and rerun ./build.sh." >&2
    exit 1
fi
if [[ ! -f "$sdk/platforms/android-35/android.jar" && -z "${ANDROID_SDK_ROOT:-}${ANDROID_HOME:-}" &&
    -f "$PWD/build/toolchain/sdk/platforms/android-35/android.jar" ]]; then
    sdk="$PWD/build/toolchain/sdk"
fi
export JAVA_HOME ANDROID_HOME="$sdk"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/build/gradle-home}"

if [[ ! -f "$sdk/platforms/android-35/android.jar" ]]; then
    echo "Install Android SDK Platform 35 in Android Studio SDK Manager." >&2
    exit 1
fi
if [[ "${1:-}" == release ]]; then
    variant=release
    task=assembleRelease
    output="apk/조수인_pawriro_release.apk"
elif [[ $# -eq 0 ]]; then
    variant=debug
    task=assembleDebug
    output=build/pawriro.apk
else
    echo "Usage: ./build.sh [release]" >&2
    exit 2
fi

tools="$sdk/build-tools/${BUILD_TOOLS_VERSION:-36.0.0}"
./gradlew --no-daemon ":$task"
"$tools/zipalign" -f 4 build/outputs/apk/"$variant"/*-"$variant"-unsigned.apk build/aligned.apk
if [[ ! -f build/demo.keystore ]]; then
    keytool -genkeypair -alias demo -dname 'CN=pawriro Demo' -keyalg RSA -validity 10000 \
        -keystore build/demo.keystore -storepass android -keypass android
fi
signed=build/pawriro-"$variant"-signed.apk
"$tools/apksigner" sign --ks build/demo.keystore --ks-pass pass:android --out "$signed" build/aligned.apk
"$tools/apksigner" verify "$signed"
if [[ "$variant" == release ]]; then
    badging=$("$tools/aapt" dump badging "$signed")
    if [[ "$badging" == *application-debuggable* || "$badging" != *launchable-activity:* ]]; then
        echo "Release APK is debuggable or missing a launcher activity." >&2
        exit 1
    fi
fi
mkdir -p "$(dirname "$output")"
cp "$signed" "$output"
printf 'APK: %s\n' "$output"
