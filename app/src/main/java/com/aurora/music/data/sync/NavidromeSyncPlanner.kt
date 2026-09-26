package com.aurora.music.data.sync

import java.util.Locale

internal fun trackDeletionsToExecute(
    plannedDeletions: List<TrackDeletion>,
    manifestTracks: Map<String, ManifestTrack>,
    referencedTrackIds: Set<String>,
    failedIds: Set<String>,
): List<TrackDeletion> {
    val protectedPaths = manifestTracks.filterKeys { it in referencedTrackIds }.values
        .map { it.relPath.lowercase(Locale.ROOT) }.toSet()
    return plannedDeletions.filter { it.songId !in failedIds && it.relPath.lowercase(Locale.ROOT) !in protectedPaths }
}

object NavidromeSyncPlanner {
    fun plan(
        manifest: SyncManifest,
        remotePlaylists: List<RemotePlaylist>,
        existingSizes: Map<String, Long?> = emptyMap(),
        selectedPlaylistIds: Set<String> = remotePlaylists.map { it.id }.toSet(),
        serverRootPath: String = "",
        pathExists: (String) -> Boolean = { existingSizes[it] != null },
    ): SyncPlan {
        val selected = remotePlaylists.filter { it.id in selectedPlaylistIds }
        val tracks = LinkedHashMap<String, SyncTrack>()
        selected.forEach { playlist -> playlist.tracks.forEach { tracks.putIfAbsent(it.id, it) } }
        val orderedTracks = tracks.entries.sortedWith(
            compareByDescending<Map.Entry<String, SyncTrack>> {
                manifest.tracks.containsKey(it.key) || manifest.pending.values.any { owner -> owner == it.key }
            },
        )
        val resolvedTracks = LinkedHashMap<String, ManifestTrack>()
        val assigned = LinkedHashSet<String>()
        val paths = HashMap<String, String>()
        val selectedTrackIds = tracks.keys
        val manifestOwnedPaths = manifest.tracks.values.map { it.relPath } + manifest.pending.keys
        val currentlyReferenced = manifest.tracks
            .filterKeys { it in selectedTrackIds }
            .values
            .map { it.relPath } + manifest.pending.filterValues { it in selectedTrackIds }.keys

        fun containsPath(paths: Iterable<String>, path: String): Boolean =
            paths.any { it.equals(path, ignoreCase = true) }

        for ((id, track) in orderedTracks) {
            val base = SyncPaths.map(track.path, id, track.suffix, serverRootPath).relPath
            val pendingPath = manifest.pending.entries.firstOrNull { it.value == id }?.key
            val previous = manifest.tracks[id]?.relPath
            val canReusePrevious = previous?.let { it.equals(base, true) || isCollisionName(it, base, id) } == true
            val reusable = pendingPath ?: previous?.takeIf { canReusePrevious }
            val occupied = LinkedHashSet<String>().apply {
                addAll(assigned)
                addAll(currentlyReferenced.filterNot { it.equals(reusable, true) })
                addAll(existingSizes.filterValues { it != null }.keys.filter {
                    !containsPath(manifestOwnedPaths, it) && !it.equals(reusable, true)
                })
            }
            val relPath = reusable?.takeIf { assigned.none { it.equals(reusable, true) } }
                ?: collisionPath(base, id, occupied) { candidate ->
                    !containsPath(manifestOwnedPaths, candidate) && pathExists(candidate)
                }
            assigned += relPath
            paths[id] = relPath
            val actualSize = existingSizes[relPath]
            resolvedTracks[id] = ManifestTrack(
                relPath,
                track.size.takeIf { it > 0 } ?: actualSize ?: previous?.let { manifest.tracks[id]?.size } ?: 0,
            )
        }

        val downloads = ArrayList<TrackDownload>()
        for ((id, track) in orderedTracks) {
            val relPath = paths.getValue(id)
            val old = manifest.tracks[id]
            val pending = manifest.pending.entries.firstOrNull { it.value == id }?.key
            val actual = existingSizes[relPath]
            val owned = old?.relPath?.equals(relPath, true) == true || pending?.equals(relPath, true) == true
            val moved = old != null && !old.relPath.equals(relPath, true)
            if (!owned || actual == null || moved || (track.size > 0 && actual != track.size)) {
                downloads += TrackDownload(track, relPath)
            }
        }

        val trackDeletions = ArrayList<TrackDeletion>()
        for ((id, old) in manifest.tracks) {
            val next = resolvedTracks[id]
            if ((next == null || !old.relPath.equals(next.relPath, true)) &&
                resolvedTracks.values.none { it.relPath.equals(old.relPath, true) }) {
                trackDeletions += TrackDeletion(id, old.relPath)
            }
        }

        val resolvedPlaylists = LinkedHashMap<String, ManifestPlaylist>()
        val usedPlaylistPaths = LinkedHashSet<String>()
        val writes = ArrayList<PlaylistWrite>()
        for (playlist in selected) {
            val old = manifest.playlists[playlist.id]
            val base = SyncPaths.sanitizeSegment(playlist.name.ifBlank { "Playlist" }) + ".m3u8"
            val reusable = old?.m3uRelPath?.takeIf {
                (it.equals(base, true) || isCollisionName(it, base, playlist.id)) &&
                    usedPlaylistPaths.none { path -> path.equals(it, true) }
            } ?: manifest.playlists.values.firstOrNull {
                it.m3uRelPath.equals(base, true) && usedPlaylistPaths.none { path -> path.equals(it.m3uRelPath, true) }
            }?.m3uRelPath
            val occupied = LinkedHashSet<String>().apply {
                addAll(usedPlaylistPaths)
                addAll(manifest.playlists.values.map { it.m3uRelPath }.filterNot { it.equals(reusable, true) })
                addAll(existingSizes.filterValues { it != null }.keys.filterNot { it.equals(reusable, true) })
            }
            val relPath = reusable ?: collisionPath(base, playlist.id, occupied, pathExists)
            usedPlaylistPaths += relPath
            resolvedPlaylists[playlist.id] = ManifestPlaylist(playlist.name, relPath, "navsync-" + playlist.id)
            writes += PlaylistWrite(playlist.id, playlist.name, relPath, writeM3u(playlist, paths))
        }
        val playlistDeletions = ArrayList<PlaylistDeletion>()
        manifest.playlists.forEach { (id, old) ->
            val next = resolvedPlaylists[id]
            if ((next == null || !next.m3uRelPath.equals(old.m3uRelPath, true)) &&
                resolvedPlaylists.values.none { it.m3uRelPath.equals(old.m3uRelPath, true) }) {
                playlistDeletions += PlaylistDeletion(id, old.m3uRelPath, old.localPlaylistId)
            }
        }
        return SyncPlan(downloads, trackDeletions, writes, playlistDeletions, resolvedTracks, resolvedPlaylists)
    }

    fun writeM3u(playlist: RemotePlaylist, paths: Map<String, String>): String = buildString {
        append("#EXTM3U\n")
        playlist.tracks.forEach { track ->
            val rel = paths[track.id] ?: return@forEach
            append("#EXTINF:").append(track.durationSec).append(',')
                .append(track.artist).append(" - ").append(track.title).append('\n')
            append(rel).append('\n')
        }
    }

    private fun collisionPath(base: String, id: String, occupied: Set<String>, pathExists: (String) -> Boolean): String {
        fun free(candidate: String) = occupied.none { it.equals(candidate, true) } && !pathExists(candidate)
        if (free(base)) return base
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        val suffix = id.take(8)
        val first = stem + " [" + suffix + "]" + ext
        if (free(first)) return first
        var n = 2
        while (!free(stem + " [" + suffix + " " + n + "]" + ext)) n++
        return stem + " [" + suffix + " " + n + "]" + ext
    }

    private fun isCollisionName(path: String, base: String, id: String): Boolean {
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        return path.equals(stem + " [" + id.take(8) + "]" + ext, ignoreCase = true)
    }
}
