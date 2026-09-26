#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

PID_FILE=$WORK/navidrome.pid
LOG_FILE=$WORK/navidrome.log
MUSIC_DIR=$WORK/music
DATA_DIR=$WORK/data
CACHE_DIR=$WORK/cache

process_is_ours() {
    local pid=$1 exe env_text cmdline
    [[ -r "/proc/$pid/exe" && -r "/proc/$pid/environ" && -r "/proc/$pid/cmdline" ]] || return 1
    exe=$(readlink -f "/proc/$pid/exe" 2>/dev/null || true)
    [[ "$exe" == "$(readlink -f "$NAVIDROME_BIN")" ]] || return 1
    cmdline=$(tr '\0' ' ' < "/proc/$pid/cmdline")
    [[ "$cmdline" == "$NAVIDROME_BIN" || "$cmdline" == "$NAVIDROME_BIN "* \
        || "$cmdline" == "$exe" || "$cmdline" == "$exe "* ]] || return 1
    env_text=$(tr '\0' '\n' < "/proc/$pid/environ")
    grep -Fqx "ND_MUSICFOLDER=$MUSIC_DIR" <<< "$env_text" || return 1
    grep -Fqx "ND_DATAFOLDER=$DATA_DIR" <<< "$env_text" || return 1
    grep -Fqx "ND_CACHEFOLDER=$CACHE_DIR" <<< "$env_text" || return 1
    grep -Fqx "ND_PORT=$PORT" <<< "$env_text" || return 1
    grep -Fqx 'ND_ADDRESS=0.0.0.0' <<< "$env_text" || return 1
}

read_pid() { [[ -s "$PID_FILE" ]] && tr -d '[:space:]' < "$PID_FILE"; }

stop_server() {
    if [[ ! -s "$PID_FILE" ]]; then
        log "Test Navidrome is not running"
        return 0
    fi
    local pid
    pid=$(read_pid)
    [[ "$pid" =~ ^[0-9]+$ ]] || die "invalid pid file: $PID_FILE"
    if ! kill -0 "$pid" 2>/dev/null; then
        rm -f -- "$PID_FILE"
        log "Removed stale pid file"
        return 0
    fi
    process_is_ours "$pid" || die "refusing to stop pid $pid: it is not this harness's Navidrome"
    kill -TERM "$pid"
    for _ in {1..50}; do
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.2
    done
    if kill -0 "$pid" 2>/dev/null; then
        process_is_ours "$pid" || die "pid $pid changed while stopping; refusing to kill"
        kill -KILL "$pid"
    fi
    rm -f -- "$PID_FILE"
    log "Stopped test Navidrome (pid $pid)"
}

start_server() {
    mkdir -p "$WORK" "$MUSIC_DIR" "$DATA_DIR" "$CACHE_DIR"
    if [[ -s "$PID_FILE" ]]; then
        local existing
        existing=$(read_pid)
        if [[ "$existing" =~ ^[0-9]+$ ]] && kill -0 "$existing" 2>/dev/null; then
            process_is_ours "$existing" || die "pid file points to an unrelated process ($existing)"
            log "Test Navidrome is already running (pid $existing)"
            return 0
        fi
        rm -f -- "$PID_FILE"
    fi
    [[ -x "$NAVIDROME_BIN" ]] || die "Navidrome executable not found: $NAVIDROME_BIN"
    log "Starting test Navidrome on 0.0.0.0:$PORT"
    env \
        ND_MUSICFOLDER="$MUSIC_DIR" \
        ND_DATAFOLDER="$DATA_DIR" \
        ND_CACHEFOLDER="$CACHE_DIR" \
        ND_PORT="$PORT" \
        ND_ADDRESS=0.0.0.0 \
        ND_LOGLEVEL=info \
        ND_SCANNER_SCHEDULE=0 \
        ND_SUBSONIC_DEFAULTREPORTREALPATH=true \
        "$NAVIDROME_BIN" > "$LOG_FILE" 2>&1 < /dev/null &
    local pid=$!
    printf '%s\n' "$pid" > "$PID_FILE"
    process_is_ours "$pid" || die "new Navidrome process failed harness ownership check"

    local ready=0
    for _ in {1..100}; do
        if curl -fsS --connect-timeout 1 --max-time 2 "$SERVER_URL_LOCAL/ping" >/dev/null 2>&1; then
            ready=1
            break
        fi
        kill -0 "$pid" 2>/dev/null || { tail -40 "$LOG_FILE" >&2 || true; die "Navidrome exited during startup"; }
        sleep 0.2
    done
    (( ready == 1 )) || { tail -40 "$LOG_FILE" >&2 || true; die "Navidrome did not answer within 20 seconds"; }

    local response status body
    response=$(curl -sS --connect-timeout 3 --max-time 10 -w '\n%{http_code}' \
        -X POST "$SERVER_URL_LOCAL/auth/createAdmin" \
        -H 'Content-Type: application/json' \
        --data "{\"username\":\"$TEST_USER\",\"password\":\"$TEST_PASSWORD\"}")
    status=${response##*$'\n'}
    body=${response%$'\n'*}
    if [[ "$status" =~ ^2 ]]; then
        log "Created Navidrome admin user '$TEST_USER'"
    else
        if ! api_json ping >/dev/null 2>&1; then
            log "createAdmin response ($status): $body" >&2
            die "could not create or authenticate test admin '$TEST_USER'"
        fi
        log "Using existing Navidrome admin user '$TEST_USER'"
    fi
}

status_server() {
    if [[ ! -s "$PID_FILE" ]]; then
        log "Test Navidrome is stopped"
        return 1
    fi
    local pid
    pid=$(read_pid)
    if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null && process_is_ours "$pid"; then
        log "Test Navidrome is running (pid $pid, port $PORT)"
    else
        log "Test Navidrome is not running (stale or invalid pid file)"
        return 1
    fi
}

reset_server() {
    stop_server
    rm -rf -- "$WORK"
    mkdir -p "$WORK"
    log "Reset test server data, cache, logs, pid and music"
}

case "${1:-}" in
    start) start_server ;;
    stop) stop_server ;;
    status) status_server ;;
    reset) reset_server ;;
    *) die "usage: $0 start|stop|status|reset" ;;
esac
