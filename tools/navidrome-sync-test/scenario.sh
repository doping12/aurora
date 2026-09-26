#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

scenario=${1:-}
[[ "$scenario" =~ ^[1234]$ ]] || die "usage: $0 <1|2|3|4>"
require_tools ffmpeg ffprobe python3 curl
require_harness_server
[[ -d "$MUSIC_DIR" ]] || die "fixtures are missing; run fixtures.sh first"

scan_and_wait() {
    api_json startScan >/dev/null
    local scan scanning=true
    for _ in {1..120}; do
        scan=$(api_json getScanStatus)
        scanning=$(python3 -c 'import json,sys; print(str(json.load(sys.stdin)["subsonic-response"].get("scanStatus",{}).get("scanning",False)).lower())' <<< "$scan")
        [[ "$scanning" == false ]] && return 0
        sleep 0.5
    done
    die "Navidrome scan did not finish"
}

restore_baseline_if_needed() {
    local changed=0
    local old_gamma="$MUSIC_DIR/Artist Gamma/Album Three/02 gamma two.flac"
    local new_gamma="$MUSIC_DIR/Artist Gamma/Album Three/02 gamma two (renamed).flac"
    if [[ -f "$new_gamma" && ! -e "$old_gamma" ]]; then
        mv -- "$new_gamma" "$old_gamma"
        changed=1
    fi
    [[ -f "$old_gamma" ]] || die "baseline Gamma fixture is missing"

    local alpha="$MUSIC_DIR/Artist Alpha/Album One/01 alpha-one original.flac"
    local title
    title=$(ffprobe -v error -show_entries format_tags=title -of default=nw=1:nk=1 "$alpha")
    if [[ "$title" != 'Tag Alpha One' ]]; then
        local restored="$WORK/01 alpha-one original.restored.flac"
        ffmpeg -hide_banner -loglevel error -y \
            -f lavfi -i 'sine=frequency=440:duration=1.2' \
            -ar 44100 -ac 1 -c:a flac \
            -metadata 'title=Tag Alpha One' -metadata 'artist=Tagged Artist Alpha' \
            -metadata 'album=Tagged Album One' -metadata track=1 "$restored"
        mv -- "$restored" "$alpha"
        changed=1
    fi
    (( changed == 0 )) || { log "Restored baseline server paths/bytes; rescanning"; scan_and_wait; }
}

if [[ "$scenario" == 1 || "$scenario" == 2 ]]; then
    restore_baseline_if_needed
fi

if [[ "$scenario" == 3 ]]; then
    old_gamma="$MUSIC_DIR/Artist Gamma/Album Three/02 gamma two.flac"
    new_gamma="$MUSIC_DIR/Artist Gamma/Album Three/02 gamma two (renamed).flac"
    if [[ -f "$old_gamma" && ! -e "$new_gamma" ]]; then
        mv -- "$old_gamma" "$new_gamma"
    elif [[ ! -f "$new_gamma" ]]; then
        die "scenario 3 expected either $old_gamma or $new_gamma"
    fi
    old_alpha="$MUSIC_DIR/Artist Alpha/Album One/01 alpha-one original.flac"
    changed_alpha="$WORK/01 alpha-one original.changed.flac"
    ffmpeg -hide_banner -loglevel error -y \
        -f lavfi -i 'sine=frequency=1234:duration=1.8' \
        -ar 44100 -ac 1 -c:a flac \
        -metadata 'title=Tag Alpha One Changed' \
        -metadata 'artist=Tagged Artist Alpha' -metadata 'album=Tagged Album One' \
        -metadata track=1 "$changed_alpha"
    mv -- "$changed_alpha" "$old_alpha"
    log "Scenario 3 changed server paths/bytes; rescanning"
    scan_and_wait
fi

if [[ "$scenario" == 3 || "$scenario" == 4 ]]; then
    alpha_one=$(song_id_by_title 'Tag Alpha One Changed')
else
    alpha_one=$(song_id_by_title 'Tag Alpha One')
fi
alpha_two=$(song_id_by_title 'Tag Alpha Two')
beta_song=$(song_id_by_title 'Tag Beta Song')
question_song=$(song_id_by_title 'Tag Question Song')
gamma_one=$(song_id_by_title 'Tag Gamma One')
gamma_two=$(song_id_by_title 'Tag Gamma Two')

ensure_playlist() {
    local name=$1
    shift
    local rows id row_name count selected=''
    rows=$(playlist_rows)
    while IFS=$'\t' read -r id row_name count; do
        [[ -n "$id" ]] || continue
        [[ "$row_name" == "$name" ]] || continue
        if [[ -z "$selected" ]]; then
            selected=$id
        else
            log "Deleting duplicate playlist: $row_name ($id); keeping $selected" >&2
            api_json deletePlaylist "id=$id" >/dev/null
        fi
    done <<< "$rows"

    if [[ -z "$selected" ]]; then
        selected=$(create_playlist "$name" "$@" | playlist_id_from_response)
        log "Created playlist '$name' (id=$selected)" >&2
    else
        replace_playlist_contents "$selected" "$@"
        log "Updated playlist '$name' in place (id=$selected)" >&2
    fi
    printf '%s\n' "$selected"
}

delete_unwanted_playlists() {
    local rows id name count keep
    rows=$(playlist_rows)
    while IFS=$'\t' read -r id name count; do
        [[ -n "$id" ]] || continue
        keep=0
        local wanted
        for wanted in "$@"; do
            [[ "$name" == "$wanted" ]] && keep=1
        done
        if (( ! keep )); then
            log "Deleting playlist not wanted by scenario: $name ($id)"
            api_json deletePlaylist "id=$id" >/dev/null
        fi
    done <<< "$rows"
}

case "$scenario" in
    1)
        sync_a_id=$(ensure_playlist 'Sync A' "$alpha_one" "$alpha_two" "$beta_song")
        sync_b_id=$(ensure_playlist 'Sync B' "$beta_song" "$question_song")
        not_synced_id=$(ensure_playlist 'Not Synced' "$gamma_one")
        delete_unwanted_playlists 'Sync A' 'Sync B' 'Not Synced'
        write_sorted_expected 1 \
            'Artist Alpha/Album One/01 alpha-one original.flac' \
            'Artist Alpha/Album One/02 alpha two.mp3' \
            'Artist Alpha/Album One/cover.jpg' \
            'Artist Beta/Album Two/01 ベータ 曲.flac' \
            'Artist Beta/Album Two/02 What_ A_ song.mp3' \
            'Sync A.m3u8' 'Sync B.m3u8'
        write_m3u_expected 1 'Sync A' \
            'Artist Alpha/Album One/01 alpha-one original.flac' \
            'Artist Alpha/Album One/02 alpha two.mp3' \
            'Artist Beta/Album Two/01 ベータ 曲.flac'
        write_m3u_expected 1 'Sync B' \
            'Artist Beta/Album Two/01 ベータ 曲.flac' \
            'Artist Beta/Album Two/02 What_ A_ song.mp3'
        ;;
    2)
        sync_a_id=$(ensure_playlist 'Sync A' "$alpha_one" "$beta_song" "$gamma_two")
        not_synced_id=$(ensure_playlist 'Not Synced' "$gamma_one")
        delete_unwanted_playlists 'Sync A' 'Not Synced'
        write_sorted_expected 2 \
            'Artist Alpha/Album One/01 alpha-one original.flac' \
            'Artist Alpha/Album One/cover.jpg' \
            'Artist Beta/Album Two/01 ベータ 曲.flac' \
            'Artist Gamma/Album Three/02 gamma two.flac' \
            'Sync A.m3u8'
        write_m3u_expected 2 'Sync A' \
            'Artist Alpha/Album One/01 alpha-one original.flac' \
            'Artist Beta/Album Two/01 ベータ 曲.flac' \
            'Artist Gamma/Album Three/02 gamma two.flac'
        ;;
    3)
        sync_a_id=$(ensure_playlist 'Sync A' "$alpha_one" "$beta_song" "$gamma_two")
        not_synced_id=$(ensure_playlist 'Not Synced' "$gamma_one")
        delete_unwanted_playlists 'Sync A' 'Not Synced'
        write_sorted_expected 3 \
            'Artist Alpha/Album One/01 alpha-one original.flac' \
            'Artist Alpha/Album One/cover.jpg' \
            'Artist Beta/Album Two/01 ベータ 曲.flac' \
            'Artist Gamma/Album Three/02 gamma two (renamed).flac' \
            'Sync A.m3u8'
        write_m3u_expected 3 'Sync A' \
            'Artist Alpha/Album One/01 alpha-one original.flac' \
            'Artist Beta/Album Two/01 ベータ 曲.flac' \
            'Artist Gamma/Album Three/02 gamma two (renamed).flac'
        ;;
    4)
        sync_a_id=$(ensure_playlist 'Sync A' "$gamma_two")
        not_synced_id=$(ensure_playlist 'Not Synced' "$gamma_one")
        delete_unwanted_playlists 'Sync A' 'Not Synced'
        write_sorted_expected 4 \
            'Artist Gamma/Album Three/02 gamma two (renamed).flac' \
            'Sync A.m3u8'
        write_m3u_expected 4 'Sync A' \
            'Artist Gamma/Album Three/02 gamma two (renamed).flac'
        ;;
esac

case "$scenario" in
    1)
        write_expected_sources 1 \
            'Artist Alpha/Album One/01 alpha-one original.flac' "$MUSIC_DIR/Artist Alpha/Album One/01 alpha-one original.flac" \
            'Artist Alpha/Album One/02 alpha two.mp3' "$MUSIC_DIR/Artist Alpha/Album One/02 alpha two.mp3" \
            'Artist Alpha/Album One/cover.jpg' "$MUSIC_DIR/Artist Alpha/Album One/cover.jpg" \
            'Artist Beta/Album Two/01 ベータ 曲.flac' "$MUSIC_DIR/Artist Beta/Album Two/01 ベータ 曲.flac" \
            'Artist Beta/Album Two/02 What_ A_ song.mp3' "$MUSIC_DIR/Artist Beta/Album Two/02 What? A: song.mp3"
        ;;
    2)
        write_expected_sources 2 \
            'Artist Alpha/Album One/01 alpha-one original.flac' "$MUSIC_DIR/Artist Alpha/Album One/01 alpha-one original.flac" \
            'Artist Alpha/Album One/cover.jpg' "$MUSIC_DIR/Artist Alpha/Album One/cover.jpg" \
            'Artist Beta/Album Two/01 ベータ 曲.flac' "$MUSIC_DIR/Artist Beta/Album Two/01 ベータ 曲.flac" \
            'Artist Gamma/Album Three/02 gamma two.flac' "$MUSIC_DIR/Artist Gamma/Album Three/02 gamma two.flac"
        ;;
    3)
        write_expected_sources 3 \
            'Artist Alpha/Album One/01 alpha-one original.flac' "$MUSIC_DIR/Artist Alpha/Album One/01 alpha-one original.flac" \
            'Artist Alpha/Album One/cover.jpg' "$MUSIC_DIR/Artist Alpha/Album One/cover.jpg" \
            'Artist Beta/Album Two/01 ベータ 曲.flac' "$MUSIC_DIR/Artist Beta/Album Two/01 ベータ 曲.flac" \
            'Artist Gamma/Album Three/02 gamma two (renamed).flac' "$MUSIC_DIR/Artist Gamma/Album Three/02 gamma two (renamed).flac"
        ;;
    4)
        write_expected_sources 4 \
            'Artist Gamma/Album Three/02 gamma two (renamed).flac' "$MUSIC_DIR/Artist Gamma/Album Three/02 gamma two (renamed).flac"
        ;;
esac

print_playlists
log "Scenario $scenario server state and expected device state are ready"
