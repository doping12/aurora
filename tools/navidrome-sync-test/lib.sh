#!/usr/bin/env bash
set -euo pipefail

HARNESS_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WORK=$HARNESS_DIR/.work
MUSIC_DIR=$WORK/music
DATA_DIR=$WORK/data
CACHE_DIR=$WORK/cache
PORT=4534
TEST_USER=${TEST_USER:-aurora}
TEST_PASSWORD=${TEST_PASSWORD:-aurora-test}
ADB=${ADB:-"${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"}
NAVIDROME_BIN=${NAVIDROME_BIN:-/usr/bin/navidrome}
SERVER_URL_LOCAL="http://127.0.0.1:$PORT"

ADB_ARGS=()
DEVICE_CONTEXT_READY=0
DEST_WAS_SET=${DEST+x}
SERVER_HOST_WAS_SET=${SERVER_HOST+x}
DEST=${DEST-}
SERVER_HOST=${SERVER_HOST-}
SERVER_URL=${SERVER_URL-}

log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

adb_exec() {
    ensure_device_context
    [[ -x "$ADB" || -x "$(command -v "$ADB" 2>/dev/null || true)" ]] || die "adb not found: $ADB"
    "$ADB" "${ADB_ARGS[@]}" "$@"
}

resolve_adb_device() {
    (( DEVICE_CONTEXT_READY == 0 )) || return 0
    if [[ "${ANDROID_SERIAL:-}" == emulator-* ]]; then
        ADB_ARGS=(-s "$ANDROID_SERIAL")
        return 0
    fi
    if [[ -n "${ANDROID_SERIAL:-}" ]]; then
        ADB_ARGS=(-s "$ANDROID_SERIAL")
        return 0
    fi

    [[ -x "$ADB" || -n "$(command -v "$ADB" 2>/dev/null || true)" ]] || die "adb not found: $ADB"
    local devices
    devices=$("$ADB" devices 2>&1) || die "could not list adb devices: $devices"
    local -a serials=()
    mapfile -t serials < <(awk 'NR > 1 && ($2 == "device" || $2 == "offline" || $2 == "unauthorized" || ($2 == "no" && $3 == "permissions")) { print $1 }' <<< "$devices")
    case "${#serials[@]}" in
        0) die "no adb device is attached; connect a device or set ANDROID_SERIAL" ;;
        1) ANDROID_SERIAL=${serials[0]} ; ADB_ARGS=(-s "$ANDROID_SERIAL") ;;
        *)
            die "multiple adb devices are attached ($(IFS=', '; printf '%s' "${serials[*]}")); set ANDROID_SERIAL to one of: ${serials[*]}"
            ;;
    esac
}

device_is_emulator() {
    resolve_adb_device
    if [[ "${ANDROID_SERIAL:-}" == emulator-* ]]; then
        return 0
    fi
    local qemu=''
    qemu=$("$ADB" "${ADB_ARGS[@]}" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r' || true)
    [[ "$qemu" == 1 ]]
}

ensure_device_context() {
    (( DEVICE_CONTEXT_READY == 1 )) && return 0
    resolve_adb_device
    if device_is_emulator; then
        if [[ -z "$DEST_WAS_SET" ]]; then
            DEST=/storage/emulated/0/Music/AuroraSyncTest
        fi
    elif [[ -z "$DEST" ]]; then
        die "physical device DEST is unset; set DEST to the folder you picked in Aurora (e.g. DEST=/storage/XXXX-XXXX/AuroraSyncTest for an SD card or /storage/emulated/0/Music/AuroraSyncTest)"
    fi
    if [[ -z "$SERVER_HOST_WAS_SET" ]]; then
        if device_is_emulator; then
            SERVER_HOST=10.0.2.2
        else
            SERVER_HOST=$(detect_lan_ip)
        fi
    fi
    SERVER_URL="http://$SERVER_HOST:$PORT"
    DEVICE_CONTEXT_READY=1
}

detect_lan_ip() {
    local ip=''
    if command -v ip >/dev/null 2>&1; then
        ip=$(ip -4 route get 192.0.2.1 2>/dev/null \
            | awk '{for (i = 1; i <= NF; i++) if ($i == "src") { print $(i + 1); exit }}' || true)
    fi
    if [[ -z "$ip" ]]; then
        ip=$(hostname -I 2>/dev/null | awk '{for (i = 1; i <= NF; i++) if ($i !~ /^127\./ && $i !~ /^169\.254\./) { print $i; exit }}' || true)
    fi
    [[ -n "$ip" ]] || die "could not detect a LAN IPv4 address; set SERVER_HOST explicitly"
    printf '%s\n' "$ip"
}

if [[ -n "$SERVER_HOST_WAS_SET" ]]; then
    SERVER_URL="http://$SERVER_HOST:$PORT"
fi

require_tools() {
    local tool
    for tool in "$@"; do
        command -v "$tool" >/dev/null 2>&1 || die "required command not found: $tool"
    done
}

ensure_work() { mkdir -p "$WORK"; }

subsonic_request() {
    local endpoint=$1
    shift
    local -a args=(
        "u=$TEST_USER"
        "p=$TEST_PASSWORD"
        'v=1.16.1'
        'c=aurora-navsync-test'
        'f=json'
    )
    args+=("$@")
    local -a curl_args=(-G "$SERVER_URL_LOCAL/rest/${endpoint}.view")
    local arg
    for arg in "${args[@]}"; do
        curl_args+=(--data-urlencode "$arg")
    done
    curl -fsS --connect-timeout 5 --max-time "${CURL_TIMEOUT:-30}" "${curl_args[@]}"
}

api_json() {
    subsonic_request "$@" | python3 -c '
import json, sys
obj = json.load(sys.stdin)
response = obj.get("subsonic-response", {})
if response.get("status") != "ok":
    raise SystemExit("Subsonic API error: " + json.dumps(response, ensure_ascii=False))
json.dump(obj, sys.stdout, ensure_ascii=False)
sys.stdout.write("\n")
'
}

playlist_rows() {
    api_json getPlaylists | python3 -c '
import json, sys
obj = json.load(sys.stdin)["subsonic-response"].get("playlists", {})
rows = obj.get("playlist", []) or []
if isinstance(rows, dict): rows = [rows]
for row in rows:
    print("{}\t{}\t{}".format(row.get("id", ""), row.get("name", ""), row.get("songCount", 0)))
'
}

song_id_from_search() {
    python3 -c '
import json, sys
obj = json.load(sys.stdin)["subsonic-response"].get("searchResult3", {})
songs = obj.get("song", []) or []
if isinstance(songs, dict): songs = [songs]
if len(songs) != 1:
    raise SystemExit("expected exactly one search result, got {}".format(len(songs)))
print(songs[0]["id"])
'
}

song_id_by_title() {
    local title=$1
    api_json search3 "query=$title" 'songCount=20' | python3 -c '
import json, sys
title = sys.argv[1]
obj = json.load(sys.stdin)["subsonic-response"].get("searchResult3", {})
songs = obj.get("song", []) or []
if isinstance(songs, dict): songs = [songs]
matches = [song for song in songs if song.get("title") == title]
if len(matches) != 1:
    raise SystemExit("expected exactly one exact song title match, got {}".format(len(matches)))
print(matches[0]["id"])
' "$title"
}

create_playlist() {
    local name=$1
    shift
    local -a args=("name=$name")
    local id
    for id in "$@"; do args+=("songId=$id"); done
    api_json createPlaylist "${args[@]}"
}

playlist_song_ids() {
    local playlist_id=$1
    api_json getPlaylist "id=$playlist_id" | python3 -c '
import json, sys
obj = json.load(sys.stdin)["subsonic-response"].get("playlist", {})
entries = obj.get("entry", []) or []
if isinstance(entries, dict): entries = [entries]
for entry in entries:
    print(entry["id"])
'
}

playlist_id_from_response() {
    python3 -c '
import json, sys
obj = json.load(sys.stdin)["subsonic-response"].get("playlist", {})
print(obj["id"])
'
}

replace_playlist_contents() {
    local playlist_id=$1
    shift
    local -a expected=("$@")
    local current_text song
    local -a current=()
    current_text=$(playlist_song_ids "$playlist_id")
    while IFS= read -r song; do
        [[ -n "$song" ]] && current+=("$song")
    done <<< "$current_text"

    local -a remove_args=("playlistId=$playlist_id")
    local index
    for (( index = 0; index < ${#current[@]}; index++ )); do
        remove_args+=("songIndexToRemove=$index")
    done
    if (( ${#current[@]} > 0 )); then
        api_json updatePlaylist "${remove_args[@]}" >/dev/null
    fi
    for song in "${expected[@]}"; do
        api_json updatePlaylist "playlistId=$playlist_id" "songIdToAdd=$song" >/dev/null
    done

    local actual_text
    local -a actual=()
    actual_text=$(playlist_song_ids "$playlist_id")
    while IFS= read -r song; do
        [[ -n "$song" ]] && actual+=("$song")
    done <<< "$actual_text"
    if (( ${#actual[@]} != ${#expected[@]} )); then
        die "playlist $playlist_id has ${#actual[@]} songs after update; expected ${#expected[@]}"
    fi
    for (( index = 0; index < ${#expected[@]}; index++ )); do
        if [[ "${actual[index]}" != "${expected[index]}" ]]; then
            die "playlist $playlist_id order differs after update (position $index: expected ${expected[index]}, got ${actual[index]})"
        fi
    done
}

print_playlists() {
    log "Test-user playlists (id, name, song count):"
    playlist_rows | while IFS=$'\t' read -r id name count; do
        printf '  %s\t%s\t%s\n' "$id" "$name" "$count"
    done
}

path_to_device_rel() {
    python3 - "$1" <<'PY'
import os
import sys

path = os.path.normpath(sys.argv[1]).split(os.sep)
parts = path[-3:]
translation = str.maketrans({c: "_" for c in r'\:*?"<>|'})
print("/".join(part.translate(translation) for part in parts))
PY
}

write_sorted_expected() {
    local number=$1
    shift
    printf '%s\n' "$@" | LC_ALL=C sort > "$WORK/expected-$number.txt"
}

write_m3u_expected() {
    local number=$1 playlist=$2
    shift 2
    printf '%s\n' "$@" > "$WORK/expected-$number-$playlist.m3u8"
}

write_expected_sources() {
    local number=$1
    shift
    local output="$WORK/expected-$number-sources.tsv"
    : > "$output"
    local device_rel server_path
    while (( $# > 0 )); do
        (( $# >= 2 )) || die "expected source mapping requires device and server paths"
        device_rel=$1
        server_path=$2
        shift 2
        printf '%s\t%s\n' "$device_rel" "$server_path" >> "$output"
    done
}

shell_quote() {
    local value=${1//\'/\'\\\'\'}
    printf "'%s'" "$value"
}

device_list_files() {
    ensure_device_context
    local quoted
    quoted=$(shell_quote "$DEST")
    adb_exec shell "find $quoted -type f -print" \
        | tr -d '\r' \
        | sed "s#^$(printf '%s' "$DEST" | sed 's/[.[\\*^$()+?{|]/\\&/g')/##" \
        | LC_ALL=C sort
}

device_md5() {
    ensure_device_context
    local path=$1 quoted
    quoted=$(shell_quote "$path")
    adb_exec shell "md5sum $quoted" | awk 'NR == 1 { print $1; exit }'
}

local_md5() { md5sum "$1" | awk '{print $1}'; }

audio_suffixes='flac|mp3|m4a|wav|ogg|opus|aac|alac|wma'

require_harness_server() {
    [[ -f "$WORK/navidrome.pid" ]] || die "test server is not running; run server.sh start"
}
