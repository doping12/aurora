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

### Repository hygiene

- Root `.gitignore` for Gradle/Android build outputs, `local.properties` and IDE files.
- Local agent instruction files (`AGENTS.md`, `CLAUDE.md`, `.claude/`) are ignored and never published, because they can contain machine- or person-specific information.
