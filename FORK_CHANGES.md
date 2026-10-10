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

## fix/dsp-screen-off-underrun

### Custom DSP: skip identity EQ sections

With DSP mode *Custom*, playback became choppy/slow as soon as the screen turned off (seen on an Xperia 10 VI, Android 16, wired headphones). With the screen off the playback thread is kept on the little CPU cores, and the Custom DSP could no longer keep up: AudioFlinger reported continuous underruns, and a profile of the playback thread showed most of its time in `PrecisionEffectsKernel.cascade`.

The kernel ran all 79 biquad slots (31 graphic + 12 parametric × 4 sections) per channel and sample, even though unused slots are identity filters (a 10-band graphic EQ uses 10). Now:

- `PrecisionDspCoefficients` precomputes the non-identity sections (value comparison, so gain-0 graphic bands stay active).
- `PrecisionEffectsKernel` processes only those. When a coefficient change activates a skipped section, its filter history is seeded from the signal that entered it, so the output matches the previous all-slots processing exactly (covered by unit tests against a reference all-slots cascade).

Note: debuggable builds (`assembleDebug`) are several times slower in this per-sample Kotlin code (ART does not inline in debuggable mode). On the test device a debuggable build still underran with the screen off even after this change, while a non-debuggable build using the same code had no underruns and about 10–20 % playback-thread CPU. For daily listening, use a non-debuggable build.

## feature/auto-volume

### "Auto" volume leveling

Settings → Loudness → Volume leveling has a fourth option, **Auto**, next to Off / Track / Album. It uses the track's ReplayGain tag when there is one (same as Track). For tracks without tags (most streamed tracks, local files that were never scanned) it measures the loudness itself and lowers loud tracks toward a common level:

- While the track plays, the PCM level meter's ~100 ms blocks are averaged (silence below −60 dBFS ignored). After 3 s there is an estimate (plain RMS in dBFS, no K-weighting); the first 20 s are used.
- Gain = −18 dBFS − estimate, attenuation only (never boosts, so no clipping), applied through the existing player-volume path and slewed at no more than 2 dB per second.
- The estimate is cached per track (`AutoLevelStore`, `auto_levels.json`, up to 5,000 entries) once at least 10 s were measured, so the next play is leveled from the first second.

Limits: Auto only estimates on the Android PCM paths with a level meter (16-bit, or the Custom DSP float path); with native USB output (player volume is ignored) it has no effect, and MixPlayer / network output use tags only. Quiet tracks are not boosted. Code: `playback/LoudnessEstimator.kt`, `data/AutoLevelStore.kt`, `PlaybackService.updateAutoLevel`.
