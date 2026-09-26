#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

PACKAGE=com.aurora.music
APK=$HARNESS_DIR/../../app/build/outputs/apk/debug/app-debug.apk
RESULT_TIMEOUT=${RESULT_TIMEOUT:-300}

broadcast() {
    ensure_device_context
    local cmd=$1
    shift
    local remote_cmd="am broadcast"
    remote_cmd+=" -a $(shell_quote 'com.aurora.music.debug.NAVSYNC')"
    remote_cmd+=" -n $(shell_quote "$PACKAGE/com.aurora.music.debug.NavidromeSyncDebugReceiver")"
    remote_cmd+=" --es $(shell_quote 'cmd') $(shell_quote "$cmd")"
    local arg
    for arg in "$@"; do
        remote_cmd+=" $(shell_quote "$arg")"
    done
    adb_exec shell "$remote_cmd"
}

install_app() {
    log "Building debug APK"
    (cd "$HARNESS_DIR/../.." && JAVA_HOME="${JAVA_HOME:-$HOME/Applications/jdk-21.0.12.1+1}" \
        ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}" bash gradlew :app:assembleDebug)
    adb_exec install -r "$APK"
}

wait_for_log_marker() {
    local marker=$1 timeout=$2
    local deadline=$((SECONDS + timeout)) line
    while (( SECONDS < deadline )); do
        line=$(adb_exec logcat -d -s AuroraNavSync:I '*:S' | grep -F "$marker " | tail -1 || true)
        if [[ -n "$line" ]]; then
            printf '%s\n' "$line"
            return 0
        fi
        sleep 1
    done
    return 1
}

add_account() {
    adb_exec logcat -c
    local output line payload validation
    if ! output=$(broadcast add-account --es server "$SERVER_URL" --es user "$TEST_USER" --es password "$TEST_PASSWORD" 2>&1); then
        printf '%s\n' "$output" >&2
        die "add-account broadcast failed"
    fi
    line=$(printf '%s\n' "$output" | grep -F 'NAVSYNC_ACCOUNT ' | tail -1 || true)
    if [[ -z "$line" ]] && ! line=$(wait_for_log_marker NAVSYNC_ACCOUNT 20); then
        log "Timed out waiting for NAVSYNC_ACCOUNT" >&2
        adb_exec logcat -d -s AuroraNavSync:I '*:S' >&2 || true
        return 1
    fi
    printf '%s\n' "$line"
    payload=${line#*NAVSYNC_ACCOUNT }
    validation=$(python3 - "$payload" <<'PY'
import json
import sys

try:
    obj = json.loads(sys.argv[1])
except json.JSONDecodeError as exc:
    print(f"invalid NAVSYNC_ACCOUNT JSON: {exc}")
    raise SystemExit(1)
if obj.get("ok") is not True:
    print("NAVSYNC_ACCOUNT did not report ok=true: " + json.dumps(obj, ensure_ascii=False))
    raise SystemExit(1)
if obj.get("error"):
    print("NAVSYNC_ACCOUNT reported an error: " + json.dumps(obj, ensure_ascii=False))
    raise SystemExit(1)
print("ok")
PY
) || die "$validation"
}

configure() {
    local playlists=${1:-}
    [[ -n "$playlists" ]] || die "usage: $0 configure 'Sync A,Sync B'"
    adb_exec logcat -c
    local output line payload validation
    if ! output=$(broadcast configure --es server "$SERVER_URL" --es playlists "$playlists" 2>&1); then
        printf '%s\n' "$output" >&2
        die "configure broadcast failed"
    fi
    line=$(printf '%s\n' "$output" | grep -F 'NAVSYNC_CONFIGURED ' | tail -1 || true)
    if [[ -z "$line" ]] && ! line=$(wait_for_log_marker NAVSYNC_CONFIGURED 20); then
        log "Timed out waiting for NAVSYNC_CONFIGURED" >&2
        adb_exec logcat -d -s AuroraNavSync:I '*:S' >&2 || true
        return 1
    fi
    printf '%s\n' "$line"
    payload=${line#*NAVSYNC_CONFIGURED }
    validation=$(python3 - "$payload" <<'PY'
import json
import sys

try:
    obj = json.loads(sys.argv[1])
except json.JSONDecodeError as exc:
    print(f"invalid NAVSYNC_CONFIGURED JSON: {exc}")
    raise SystemExit(1)
if obj.get("ok") is not True:
    if obj.get("error"):
        print(obj["error"])
    else:
        print("NAVSYNC_CONFIGURED did not report ok=true: " + json.dumps(obj, ensure_ascii=False))
    raise SystemExit(1)
if not obj.get("treeUri"):
    print("DESTINATION_FOLDER_MISSING")
    raise SystemExit(2)
if obj.get("error"):
    print("NAVSYNC_CONFIGURED reported an error: " + json.dumps(obj, ensure_ascii=False))
    raise SystemExit(1)
print("ok")
PY
) || {
        if [[ "$validation" == DESTINATION_FOLDER_MISSING ]]; then
            die "destination folder must be picked once in Aurora (Settings → Navidrome sync → Choose folder)"
        fi
        die "$validation"
    }
}

sync_device() {
    adb_exec logcat -c
    broadcast sync >/dev/null
    local deadline=$((SECONDS + RESULT_TIMEOUT)) line result=''
    log "Waiting up to ${RESULT_TIMEOUT}s for NAVSYNC_RESULT"
    while (( SECONDS < deadline )); do
        line=$(adb_exec logcat -d -s AuroraNavSync:I '*:S' | grep 'NAVSYNC_RESULT ' | tail -1 || true)
        if [[ -n "$line" ]]; then
            result=${line#*NAVSYNC_RESULT }
            printf '%s\n' "$result"
            python3 - "$result" <<'PY'
import json
import sys

obj = json.loads(sys.argv[1])
if obj.get("state") != "finished":
    raise SystemExit("sync did not finish: " + json.dumps(obj, ensure_ascii=False))
if int(obj.get("failed", 0)) > 0:
    raise SystemExit("sync reported failed files: " + json.dumps(obj, ensure_ascii=False))
PY
            return 0
        fi
        sleep 1
    done
    log "Timed out waiting for NAVSYNC_RESULT" >&2
    adb_exec logcat -d -s AuroraNavSync:I '*:S' >&2 || true
    return 1
}

list_device() { device_list_files; }

verify_m3u() {
    local number=$1
    local playlist=$2
    local expected="$WORK/expected-$number-$playlist.m3u8"
    local device_path="$DEST/$playlist.m3u8" actual
    [[ -f "$expected" ]] || die "missing m3u expectation: $expected"
    actual=$(mktemp "$WORK/m3u.XXXXXX")
    adb_exec shell "cat $(shell_quote "$device_path")" \
        | tr -d '\r' | awk 'NF && $0 !~ /^#/' > "$actual"
    if ! cmp -s "$expected" "$actual"; then
        log "FAIL: m3u8 mismatch: $device_path"
        diff -u "$expected" "$actual" || true
        rm -f -- "$actual"
        return 1
    fi
    rm -f -- "$actual"
}

verify_device() {
    local number=${1:-}
    [[ "$number" =~ ^[1234]$ ]] || die "usage: $0 verify <1|2|3|4>"
    local expected="$WORK/expected-$number.txt"
    [[ -f "$expected" ]] || die "missing expected file: $expected"
    local actual="$WORK/device-list-$number.txt"
    list_device > "$actual"
    local failed=0
    if ! cmp -s "$expected" "$actual"; then
        log "FAIL: device file list differs for scenario $number"
        diff -u "$expected" "$actual" || true
        failed=1
    fi

    local sources="$WORK/expected-$number-sources.tsv"
    [[ -f "$sources" ]] || die "missing expected source mapping: $sources"
    while IFS=$'\t' read -r rel server_file; do
        [[ -n "$rel" ]] || continue
        local server_sum device_sum
        if [[ ! -f "$server_file" ]]; then
            log "FAIL: no server source for mapped device file: $rel -> $server_file"
            failed=1
            continue
        fi
        server_sum=$(local_md5 "$server_file")
        device_sum=$(device_md5 "$DEST/$rel")
        if [[ "$server_sum" != "$device_sum" ]]; then
            log "FAIL: md5 mismatch: $rel (server $server_file $server_sum, device $device_sum)"
            failed=1
        fi
    done < "$sources"

    case "$number" in
        1) verify_m3u 1 'Sync A' || failed=1; verify_m3u 1 'Sync B' || failed=1 ;;
        2|3|4) verify_m3u "$number" 'Sync A' || failed=1 ;;
    esac

    if [[ "$number" == 4 ]]; then
        local directory state
        for directory in 'Artist Alpha' 'Artist Beta'; do
            state=$(adb_exec shell "if [ -d $(shell_quote "$DEST/$directory") ]; then printf present; else printf absent; fi" | tr -d '\r\n') || {
                log "FAIL: could not check device directory: $DEST/$directory"
                failed=1
                continue
            }
            if [[ "$state" != absent ]]; then
                log "FAIL: stale device directory still exists: $DEST/$directory"
                failed=1
            fi
        done
    fi
    if (( failed )); then
        log "FAIL: scenario $number verification failed"
        return 1
    fi
    log "PASS: scenario $number verified"
}

clean_dest() {
    local assume_yes=0
    if [[ "${1:-}" == -y ]]; then
        assume_yes=1
    elif [[ -n "${1:-}" ]]; then
        die "usage: $0 clean-dest [-y]"
    fi
    ensure_device_context
    [[ -n "$DEST" && "$DEST" != / ]] || die "refusing to clean empty or root DEST"
    if (( ! assume_yes )); then
        printf 'Delete every file under %s on the selected device? Type DELETE: ' "$DEST" >&2
        local answer
        read -r answer
        [[ "$answer" == DELETE ]] || die "clean-dest cancelled"
    fi
    adb_exec shell "find $(shell_quote "$DEST") -mindepth 1 -exec rm -rf {} +"
    log "Cleaned device destination: $DEST"
}

dump_logs() { adb_exec logcat -d -s AuroraNavSync:I '*:S'; }

case "${1:-}" in
    install) install_app ;;
    add-account) add_account ;;
    configure) shift; configure "${1:-}" ;;
    sync) sync_device ;;
    list) list_device ;;
    verify) shift; verify_device "${1:-}" ;;
    clean-dest) shift; clean_dest "${1:-}" ;;
    logs) dump_logs ;;
    *) die "usage: $0 install|add-account|configure <names>|sync|list|verify <n>|clean-dest [-y]|logs" ;;
esac
