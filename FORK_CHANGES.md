# Fork changes

This repository is a fork of [nessli420/aurora](https://github.com/nessli420/aurora). This file lists everything the fork adds or changes on top of upstream, so the changes stay easy to review, reuse or upstream.

Convention: every branch that adds a fork-specific change appends its own section below, in that branch, describing what it adds and why. Merging a branch therefore also merges its description.

## main

### Build fix: stand-ins for playback reporting sources missing upstream

Upstream commit `85dcf21` ("Youtube Music listening history, playback state/progress reporting to Jellyfin, Navidrome and Plex") references two source files that were not committed, so the app does not compile from that revision. The fork adds minimal no-op versions so the project builds:

- `app/src/main/java/com/aurora/music/data/PlaybackReport.kt`
- `app/src/main/java/com/aurora/music/playback/PlaybackReportingController.kt`
- the string resource `playback_history_sync_failed` that `AppContainer` references (`app/src/main/res/values/strings.xml`)

Effect: playback state/progress reporting to servers does nothing in this fork; everything else, including playback, is unaffected. Replace these files with upstream's real implementation once it is published.

### `daily` build type

`bash gradlew :app:assembleDaily` builds a non-debuggable APK (Java and JNI) with the same application id and the standard debug signing key. It replaces an installed debug build with `adb install -r` without losing app data, while running the per-sample audio code at full speed (debuggable builds are several times slower there; see `fix/dsp-screen-off-underrun`). Library modules fall back to their `release` variants. Use it for everyday listening; keep `assembleDebug` for debugging.

### Repository hygiene

- Root `.gitignore` for Gradle/Android build outputs, `local.properties` and IDE files.
- Local agent instruction files (`AGENTS.md`, `CLAUDE.md`, `.claude/`) are ignored and never published, because they can contain machine- or person-specific information.

## feature/sync

### Navidrome playlist sync (MediaMonkey-style)

Copies selected Navidrome playlists and the audio files they reference to a folder on the phone over Wi-Fi, and keeps that folder in sync with the server. Aurora already streams and caches from Navidrome; this adds an explicit, file-level sync to a user-visible folder (e.g. an SD card).

**Where**: Settings → Navidrome sync. Pick the Navidrome (Subsonic) account, choose the destination folder (Storage Access Framework, SD cards supported), tick the playlists to sync, then tap "Sync now". The selection is saved.

**What a sync does**

- Downloads each track with Subsonic `download.view`, so files are byte-identical to the server (tags and file names are not modified).
- Keeps the original file name and the `Artist/Album/file` directory layout (the last three path segments; set "Server music folder" to keep the full path relative to the library root). Characters that FAT/exFAT cannot store (`\ : * ? " < > |`) are replaced with `_`.
- A track that appears in several playlists is stored once.
- Writes `<playlist name>.m3u8` (relative paths) at the destination root and mirrors each playlist into Aurora's local library, so it is playable offline without the server.
- Copies a non-embedded album cover as `cover.jpg`/`cover.png`/`cover.webp` when the server has one (Navidrome's generic placeholder image is skipped).
- Reflects server-side changes on the next sync: tracks added to or removed from a playlist, deleted playlists, renamed or re-encoded files. Files no longer referenced by any synced playlist are deleted, together with their cover and any directories left empty.
- Never changes anything on the server.

**Safety**

- Only files the sync created are ever replaced or deleted. They are tracked in an app-private manifest (`files/navidrome_sync/manifest.json`), which is written atomically and records in-flight downloads so an interrupted sync can recover. A pre-existing file at a target path is left alone, and the synced file gets a collision name (`name [<first 8 chars of song id>].ext`).
- A sync with no playlist selected is refused, and a sync in which every selected playlist has disappeared from the server is aborted before anything is deleted.
- The sync holds a wake lock and a Wi-Fi lock while it runs.

**Server setup for original file names**: by default Navidrome reports tag-based fake paths through the Subsonic API. In Navidrome open Settings → Players, find the player named `AuroraSync` (created on the first sync) and enable "Report Real Path", or start Navidrome with `ND_SUBSONIC_DEFAULTREPORTREALPATH=true` before that player exists. Without it, files are saved under tag-based names and the app shows a warning; after enabling it, the next sync re-downloads the files under their real names and removes the old ones.

**Not implemented yet**: automatic sync (the Manual/Automatic switch is stored but only manual sync runs), and running the sync as a foreground service.

**Code**

- `app/src/main/java/com/aurora/music/data/sync/`:
  - `NavidromeSyncExecutor`: Android-independent orchestration, covered by JVM tests.
  - `NavidromeSyncPlanner` and `SyncPaths`: pure planning and path mapping.
  - `SafSyncStorage`: SAF file access.
  - `NavidromeSyncManager`: Android glue (account resolution, permissions, locks, MediaStore scan, local playlist mirroring).
- UI: `app/src/main/java/com/aurora/music/ui/screens/settings/NavidromeSyncScreen.kt`. Strings are available in English and Russian.
- Small additive changes: `SubsonicClient` (configurable client name, `downloadUrl`, original-size cover URL), `SongDto.size`, `LocalStore.upsertPlaylist`.
- Debug builds only: `NavidromeSyncDebugReceiver` lets adb drive the sync (protected by the `DUMP` permission, so only adb can use it).

**End-to-end test harness**: `tools/navidrome-sync-test/` starts a throwaway Navidrome on port 4534 with all data under the repository, generates tiny test tracks, and runs four scenarios on a device or emulator: new sync; playlist update and deletion; server-side rename and content change; removal of a whole album. The transfer itself always goes over the network (adb is used only for control and verification). See its `README.md`.

## fix/dsp-screen-off-underrun

### Custom DSP: skip identity EQ sections

With DSP mode *Custom*, playback became choppy/slow as soon as the screen turned off (seen on an Xperia 10 VI, Android 16, wired headphones). With the screen off the playback thread is kept on the little CPU cores, and the Custom DSP could no longer keep up: AudioFlinger reported continuous underruns, and a profile of the playback thread showed most of its time in `PrecisionEffectsKernel.cascade`.

The kernel ran all 79 biquad slots (31 graphic + 12 parametric × 4 sections) per channel and sample, even though unused slots are identity filters (a 10-band graphic EQ uses 10). Now:

- `PrecisionDspCoefficients` precomputes the non-identity sections (value comparison, so gain-0 graphic bands stay active).
- `PrecisionEffectsKernel` processes only those. When a coefficient change activates a skipped section, its filter history is seeded from the signal that entered it, so the output matches the previous all-slots processing exactly (covered by unit tests against a reference all-slots cascade).

Note: debuggable builds (`assembleDebug`) are several times slower in this per-sample Kotlin code (ART does not inline in debuggable mode). On the test device a debuggable build still underran with the screen off even after this change, while a non-debuggable build using the same code had no underruns and about 10–20 % playback-thread CPU. For daily listening, use a non-debuggable build.

## feature/scroll-sort

Added themed, draggable, auto-hiding scrollbars to the library song/list/grid views, the all-library overview, and detail track lists. The scrollbar reflects approximate list position, stays above bottom content padding, and moves left when the A–Z rail is present.

Detail screens now offer original order, name, release date, artist, album, and date-added sorting, with reversible directions. Name sorting groups symbols, digits, Latin, kana, and other scripts (folding kana and normalizing case); release-date sorting uses the new `Song.releaseYear`, populated only for Subsonic/Navidrome, Jellyfin, and local files.
