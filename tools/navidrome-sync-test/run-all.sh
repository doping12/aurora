#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "$0")" && pwd)

install_mode=install
case "${1:-}" in
    '') ;;
    --install) install_mode=install ;;
    --no-install) install_mode=no-install ;;
    *) printf 'usage: %s [--install|--no-install]\n' "$0" >&2; exit 2 ;;
esac

status=0
run() {
    "$SCRIPT_DIR/server.sh" reset || return $?
    "$SCRIPT_DIR/server.sh" start || return $?
    "$SCRIPT_DIR/fixtures.sh" || return $?
    if [[ "$install_mode" == install ]]; then
        "$SCRIPT_DIR/device.sh" install || return $?
    fi
    "$SCRIPT_DIR/device.sh" clean-dest -y || return $?
    "$SCRIPT_DIR/device.sh" add-account || return $?
    "$SCRIPT_DIR/scenario.sh" 1 || return $?
    "$SCRIPT_DIR/device.sh" configure 'Sync A,Sync B' || return $?
    "$SCRIPT_DIR/device.sh" sync || return $?
    "$SCRIPT_DIR/device.sh" verify 1 || return $?
    for n in 2 3 4; do
        "$SCRIPT_DIR/scenario.sh" "$n" || return $?
        "$SCRIPT_DIR/device.sh" sync || return $?
        "$SCRIPT_DIR/device.sh" verify "$n" || return $?
    done
}

if run; then
    status=0
else
    status=$?
fi
if (( status == 0 )); then
    printf 'NAVIDROME SYNC HARNESS: PASS\n'
else
    printf 'NAVIDROME SYNC HARNESS: FAIL (exit %d)\n' "$status" >&2
fi
exit "$status"
