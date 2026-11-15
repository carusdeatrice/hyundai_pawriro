#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

config=backend/.env
inherited_ors_key="${ORS_API_KEY:-}"
unset ORS_API_KEY
export JAVA_HOME="${JAVA_HOME:-$PWD/build/toolchain/jdk}"
if [[ -f "$config" ]]; then
    source "$config"
fi

if [[ -z "${ORS_API_KEY:-}" ]]; then
    ORS_API_KEY="$inherited_ors_key"
    if [[ -z "$ORS_API_KEY" ]]; then
        if [[ ! -t 0 ]]; then
            echo "Run ./backend/start.sh in a terminal to save the ORS API key." >&2
            exit 1
        fi
        read -r -s -p 'ORS API 키: ' ORS_API_KEY
        printf '\n'
    fi
    if [[ -z "$ORS_API_KEY" ]]; then
        echo "ORS API 키를 입력해 주세요." >&2
        exit 1
    fi
    umask 077
    printf '\nexport ORS_API_KEY=%q\n' "$ORS_API_KEY" >> "$config"
    echo "ORS API 키를 backend/.env에 저장했습니다."
fi

export ORS_API_KEY
exec backend/build/install/backend/bin/backend
