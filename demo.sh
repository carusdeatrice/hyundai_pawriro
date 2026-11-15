#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
adb="$sdk/platform-tools/adb"
package=kr.routerescue.demo
case "${1:-}" in
  install)
    "$adb" install -r build/pawriro.apk
    "$adb" shell am force-stop "$package"
    "$adb" shell am start --user current -n "$package/.PawriroComposeActivity" --ez demo true ;;
  logs) "$adb" logcat -s PawriroDemo:I ;;
  drive|park|driver-door|driver-exit|walk-minutes|driver-return|photo-approve|seat|navi-refresh|navi-candidate|tick|turn|clear-turn|reply|timeout|fail|stop)
    args=(--es event "$1")
    if [[ -n "${2:-}" ]]; then
        escaped=$(printf "%s" "$2" | sed "s/'/'\\''/g")
        args+=(--es answer "'$escaped'")
    fi
    "$adb" shell am broadcast --user current -a "$package.CONTROL" -p "$package" "${args[@]}" ;;
  *) echo 'Usage: ./demo.sh install|logs|drive|park|driver-door|driver-exit|walk-minutes|driver-return|photo-approve|seat|navi-refresh|navi-candidate|tick|turn|clear-turn|reply [answer]|timeout|fail|stop'; exit 2 ;;
esac
