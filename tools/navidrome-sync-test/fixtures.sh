#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

require_tools ffmpeg python3 curl
if [[ -n "${FIXTURE_ROOT:-}" ]]; then
    MUSIC_DIR=$FIXTURE_ROOT
fi

make_audio() {
    local format=$1 output=$2 frequency=$3 title=$4 artist=$5 album=$6 track=$7
    local codec_args=()
    case "$format" in
        flac) codec_args=(-c:a flac) ;;
        mp3) codec_args=(-c:a libmp3lame -q:a 8) ;;
        m4a) codec_args=(-c:a aac -b:a 64k -movflags +faststart) ;;
        *) die "unsupported fixture format: $format" ;;
    esac
    ffmpeg -hide_banner -loglevel error -y \
        -f lavfi -i "sine=frequency=$frequency:duration=1.2" \
        -ar 44100 -ac 1 "${codec_args[@]}" \
        -metadata "title=$title" -metadata "artist=$artist" \
        -metadata "album=$album" -metadata "track=$track" "$output"
}

generate_fixtures() {
    [[ -n "${FIXTURE_ROOT:-}" ]] || mkdir -p "$WORK"
    rm -rf -- "$MUSIC_DIR"
    mkdir -p "$MUSIC_DIR/Artist Alpha/Album One" \
        "$MUSIC_DIR/Artist Beta/Album Two" \
        "$MUSIC_DIR/Artist Gamma/Album Three"
    log "Generating deterministic test audio under $MUSIC_DIR"
    make_audio flac "$MUSIC_DIR/Artist Alpha/Album One/01 alpha-one original.flac" \
        440 'Tag Alpha One' 'Tagged Artist Alpha' 'Tagged Album One' 1
    make_audio mp3 "$MUSIC_DIR/Artist Alpha/Album One/02 alpha two.mp3" \
        554 'Tag Alpha Two' 'Tagged Artist Alpha' 'Tagged Album One' 2
    make_audio flac "$MUSIC_DIR/Artist Beta/Album Two/01 ベータ 曲.flac" \
        660 'Tag Beta Song' 'Tagged Artist Beta' 'Tagged Album Two' 1
    make_audio mp3 "$MUSIC_DIR/Artist Beta/Album Two/02 What? A: song.mp3" \
        770 'Tag Question Song' 'Tagged Artist Beta' 'Tagged Album Two' 2
    make_audio m4a "$MUSIC_DIR/Artist Gamma/Album Three/01 gamma one.m4a" \
        880 'Tag Gamma One' 'Tagged Artist Gamma' 'Tagged Album Three' 1
    make_audio flac "$MUSIC_DIR/Artist Gamma/Album Three/02 gamma two.flac" \
        990 'Tag Gamma Two' 'Tagged Artist Gamma' 'Tagged Album Three' 2

    # Deliberately separate from the audio stream: this checks the optional cover copy.
    ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'color=c=navy:s=32x32' \
        -frames:v 1 "$MUSIC_DIR/Artist Alpha/Album One/cover.jpg"
}

generate_fixtures
if [[ "${FIXTURES_ONLY:-0}" == 1 ]]; then
    log "Fixture-only generation complete"
    exit 0
fi

require_harness_server

log "Starting a full Navidrome scan"
api_json startScan >/dev/null
scanning=true
for _ in {1..120}; do
    scan=$(api_json getScanStatus)
    scanning=$(python3 -c 'import json,sys; print(str(json.load(sys.stdin)["subsonic-response"].get("scanStatus",{}).get("scanning",False)).lower())' <<< "$scan")
    [[ "$scanning" == false ]] && break
    sleep 0.5
done
[[ "$scanning" == false ]] || die "Navidrome scan did not finish within 60 seconds"
log "Fixture generation and scan complete"
