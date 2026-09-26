# Navidrome playlist-sync test harness

This directory contains a repeatable, shell-only end-to-end harness for Aurora's Navidrome playlist sync. It runs a dedicated Navidrome on port `4534`, keeps all server state below `tools/navidrome-sync-test/.work/`, and never contacts the production Navidrome on port `4533`. Music is transferred from the test server over HTTP/Wi-Fi; `adb` is used only for app control and device inspection.

## Prerequisites

- Linux with Bash, `curl`, `python3`, `ffmpeg`, and `navidrome` 0.64.0 (or a compatible version).
- A debug APK build environment and the Android SDK `adb` executable.
- A device or emulator with USB debugging enabled and Aurora's debug APK installed. When `ANDROID_SERIAL` is unset, the harness selects the only attached device; if multiple devices are attached, set `ANDROID_SERIAL` explicitly.
- The phone and computer must be on the same Wi-Fi network. For an emulator, the harness uses `10.0.2.2` automatically.

The production server is not used. Override `SERVER_HOST` if the detected LAN address is not reachable by the phone. `DEST` and `ADB` can also be overridden.

## One-time device setup

The emulator default destination is `/storage/emulated/0/Music/AuroraSyncTest`. Physical devices have no default: set `DEST` to the folder picked in Aurora. SD-card paths look like `/storage/<VOLUME-ID>/<folder>`. Create the destination first, for example:

```sh
DEST=/storage/XXXX-XXXX/AuroraSyncTest
"$HOME/Android/Sdk/platform-tools/adb" shell mkdir -p "$DEST"
```

Install the debug APK, grant Aurora audio/media permission, then in Aurora open **Settings → Navidrome sync → Choose folder** and select the destination. Folder selection is a SAF operation and is intentionally not part of the broadcast contract. Repeat this setup on each device.

If the app is uninstalled, Android may revoke its SAF permission; choose the folder again after reinstalling.

## Standard run

From any working directory:

```sh
tools/navidrome-sync-test/run-all.sh --install
```

Use `--no-install` when the debug APK already on the device is the desired build:

```sh
tools/navidrome-sync-test/run-all.sh --no-install
```

Every standard run clears all files under the selected device destination after
the optional APK installation and before scenario 1. This keeps the run
reproducible even when files remain from an earlier run.
The selected playlists are configured once after scenario 1; scenarios 2, 3, and 4
reuse those selected playlist IDs without reconfiguring.

The test user is `aurora` / `aurora-test`. Server data, logs, generated audio, expectations, and the pid file are all under `.work/`. Useful individual commands are:

```sh
tools/navidrome-sync-test/server.sh status
tools/navidrome-sync-test/device.sh list
tools/navidrome-sync-test/device.sh logs
tools/navidrome-sync-test/device.sh clean-dest -y
```

`clean-dest` requires typing `DELETE` unless `-y` is supplied. It refuses an empty destination or `/`.

## What the scenarios verify

1. Two selected playlists share the Beta track, retain original filenames, preserve the last-three-directory album layout, copy the non-embedded `cover.jpg`, sanitize `?` and `:` to `_`, write two m3u8 files, and exclude the unselected Gamma album.
2. The first playlist removes Alpha track 2 and adds Gamma track 2; the second playlist is deleted. The shared Beta file remains, stale files and m3u8 are deleted, and playlist order is updated.
3. The Gamma file is renamed on the server and Alpha track 1 is re-encoded and retagged with changed bytes. The old path is removed, the new path appears, and device/server MD5 values are compared.
4. The selected playlist is reduced to the renamed Gamma track. The Alpha and Beta albums, including Alpha's `cover.jpg`, are removed, their empty artist directories are pruned, the stale files and m3u8 are deleted, and the remaining m3u8 contains only the Gamma path.

Each scenario keeps a matching playlist ID when it already exists, replaces its contents in order, creates it only when missing, and deletes playlists not wanted by that scenario. It writes a sorted expected file list, an expected server-source mapping for MD5 checks, and an expected m3u8 path list, then prints playlist IDs and song counts. m3u8 verification compares the ordered non-comment path lines, so both the app's standard `#EXTM3U`/`#EXTINF` headers and the required file paths are handled.

## Troubleshooting

- **No sync result / connection failure:** confirm the phone is on the same Wi-Fi, use `SERVER_HOST=<PC-LAN-IP>`, and allow inbound TCP `4534` through the firewall. The emulator should use automatic `10.0.2.2`.
- **Wrong device:** set `ANDROID_SERIAL` and optionally `DEST` explicitly.
- **Folder permission errors:** recreate the destination, pick it again in the app, and check that it is writable. Uninstalling the app can lose the SAF grant.
- **Server startup errors:** inspect `.work/navidrome.log`; `server.sh stop` only stops the recorded pid after checking its executable and harness-specific environment.
- **Stale test state:** `server.sh reset` removes only this harness's repository-local `.work/` data. It never calls `pkill` and does not touch the production instance.
