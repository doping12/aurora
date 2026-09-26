package com.aurora.music.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.OutputStream

fun interface SyncManifestPersistence {
    fun save(manifest: SyncManifest)
}

data class CoverDownload(val extension: String, val bytes: ByteArray)

data class SyncExecutionResult(
    val manifest: SyncManifest,
    val summary: SyncSummary,
    val changedAudioPaths: Set<String>,
    val managedDirectories: Set<String>,
)

class SyncExecutionException(val issue: SyncIssue) : Exception()

class NavidromeSyncExecutor(
    private val storage: SyncStorage,
    private val download: suspend (TrackDownload, OutputStream) -> Unit,
    private val fetchCover: suspend (SyncTrack) -> CoverDownload?,
    private val persistence: SyncManifestPersistence,
    private val progress: (SyncPhase, Int, Int, String) -> Unit = { _, _, _, _ -> },
) {
    suspend fun execute(
        initialManifest: SyncManifest,
        remotePlaylists: List<RemotePlaylist>,
        selectedPlaylistIds: Set<String>,
        availablePlaylistIds: Set<String> = remotePlaylists.map { it.id }.toSet(),
        serverRootPath: String = "",
    ): SyncExecutionResult {
        val hasManagedState = initialManifest.tracks.isNotEmpty() || initialManifest.playlists.isNotEmpty() ||
            initialManifest.pending.isNotEmpty()
        if (selectedPlaylistIds.isEmpty() && hasManagedState) {
            throw SyncExecutionException(SyncIssue.NothingSelected)
        }
        val existingSelected = selectedPlaylistIds.intersect(availablePlaylistIds)
        if (selectedPlaylistIds.isNotEmpty() && existingSelected.isEmpty() && hasManagedState) {
            throw SyncExecutionException(SyncIssue.AllPlaylistsMissing)
        }

        storage.beginSync()
        var manifest = initialManifest
        val failedIds = LinkedHashSet<String>()
        val changedAudioPaths = LinkedHashSet<String>()
        val managedDirectories = LinkedHashSet<String>()
        var downloaded = 0
        var failed = 0
        var deleted = 0
        val warnings = ArrayList<SyncIssue>()
        try {
            progress(SyncPhase.PREPARING, 0, 0, "")
            val selectedRemote = remotePlaylists.filter { it.id in existingSelected }
            val candidatePaths = LinkedHashSet<String>()
            selectedRemote.flatMap { it.tracks }.forEach { track ->
                candidatePaths += SyncPaths.map(track.path, track.id, track.suffix, serverRootPath).relPath
            }
            selectedRemote.forEach { playlist ->
                candidatePaths += SyncPaths.sanitizeSegment(playlist.name.ifBlank { "Playlist" }) + ".m3u8"
            }
            candidatePaths += manifest.tracks.values.map { it.relPath }
            candidatePaths += manifest.pending.keys
            candidatePaths += manifest.playlists.values.map { it.m3uRelPath }
            val sizes = candidatePaths.associateWith { storage.size(it) }.toMutableMap()
            val plan = NavidromeSyncPlanner.plan(
                manifest = manifest,
                remotePlaylists = selectedRemote,
                existingSizes = sizes,
                selectedPlaylistIds = existingSelected,
                serverRootPath = serverRootPath,
                pathExists = { path ->
                    if (sizes[path] != null) true else {
                        val exists = storage.exists(path)
                        sizes[path] = storage.size(path)
                        exists
                    }
                },
            )
            fun rememberDirectories(path: String) {
                val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() }
                for (index in 1 until parts.size) managedDirectories += parts.take(index).joinToString("/")
            }
            (plan.downloads.map { it.relPath } + plan.playlistWrites.map { it.relPath } + manifest.extras.keys)
                .forEach(::rememberDirectories)
            val referencedIds = plan.resolvedTracks.keys
            val stalePending = manifest.pending.filterValues { it !in referencedIds }
            val resolvedPaths = plan.resolvedTracks.values.map { it.relPath }
            stalePending.forEach { (path, _) ->
                val protectedByTrack = manifest.tracks.values.any { it.relPath.equals(path, true) } ||
                    resolvedPaths.any { it.equals(path, true) }
                if (protectedByTrack) return@forEach
                val existed = storage.exists(path)
                val deletedNow = existed && storage.delete(path)
                val removed = !existed || deletedNow || !storage.exists(path)
                if (deletedNow) {
                    deleted++
                    changedAudioPaths += path
                }
                if (removed) {
                    storage.pruneEmptyParents(path)
                }
            }
            val pendingToRemove = stalePending.filter { (path, _) ->
                manifest.tracks.values.any { it.relPath.equals(path, true) } ||
                    resolvedPaths.any { it.equals(path, true) } ||
                    !storage.exists(path)
            }.keys
            manifest = manifest.copy(pending = manifest.pending - pendingToRemove)

            if (selectedRemote.flatMap { it.tracks }.any {
                    !SyncPaths.map(it.path, it.id, it.suffix, serverRootPath).isRealPath
                }) warnings += SyncIssue.TagBasedPaths
            progress(SyncPhase.DOWNLOADING, 0, plan.downloads.size, "")
            for ((index, item) in plan.downloads.withIndex()) {
                coroutineContext.ensureActive()
                progress(SyncPhase.DOWNLOADING, index, plan.downloads.size, item.track.title)
                val ownedPath = manifest.tracks.values.any { it.relPath.equals(item.relPath, true) } ||
                    manifest.pending.keys.any { it.equals(item.relPath, true) }
                manifest = manifest.copy(pending = manifest.pending + (item.relPath to item.track.id))
                // This write is deliberately outside the download catch block: if it fails,
                // the pending reservation must remain the last durable state.
                persistence.save(manifest)
                try {
                    storage.openTemp(item.relPath).use { output -> download(item, output) }
                    storage.commitTemp(item.relPath, replaceExisting = ownedPath)
                } catch (e: CancellationException) {
                    storage.deleteTemp(item.relPath)
                    throw e
                } catch (e: Exception) {
                    storage.deleteTemp(item.relPath)
                    manifest = manifest.copy(pending = manifest.pending - item.relPath)
                    persistence.save(manifest)
                    failed++
                    failedIds += item.track.id
                    warnings += SyncIssue.DownloadFailed(item.track.title, e.message ?: "unknown error")
                    continue
                }
                val size = item.track.size.takeIf { it > 0 } ?: storage.size(item.relPath) ?: 0
                manifest = manifest.copy(
                    tracks = manifest.tracks + (item.track.id to ManifestTrack(item.relPath, size)),
                    pending = manifest.pending - item.relPath,
                )
                persistence.save(manifest)
                changedAudioPaths += item.relPath
                downloaded++
            }

            val recoveredOrUnchanged = plan.resolvedTracks.filterKeys { it !in failedIds }
            if (recoveredOrUnchanged.isNotEmpty()) {
                manifest = manifest.copy(
                    tracks = manifest.tracks + recoveredOrUnchanged,
                    pending = manifest.pending - recoveredOrUnchanged.values.map { it.relPath }.toSet(),
                )
                persistence.save(manifest)
            }
            val selectedTrackIds = selectedRemote.flatMap { it.tracks }.map { it.id }.toSet()
            val skipped = selectedTrackIds.count { it !in failedIds && plan.downloads.none { item -> item.track.id == it } }

            progress(SyncPhase.COVERS, 0, 0, "")
            val coverTracks = selectedRemote.flatMap { it.tracks }.distinctBy { it.id }
            for ((dir, songs) in coverTracks.groupBy { manifest.tracks[it.id]?.relPath?.substringBeforeLast('/', "") }) {
                if (dir.isNullOrBlank()) continue
                managedDirectories += dir
                if (listOf("cover.jpg", "cover.png", "cover.webp", "folder.jpg", "folder.png", "folder.webp")
                        .any { storage.exists("$dir/$it") }) continue
                val coverTrack = songs.firstOrNull { !it.coverArt.isNullOrBlank() } ?: continue
                var coverPath: String? = null
                try {
                    val data = fetchCover(coverTrack) ?: continue
                    val path = dir + "/cover." + data.extension
                    coverPath = path
                    storage.openTemp(path).use { it.write(data.bytes) }
                    storage.commitTemp(path, replaceExisting = false)
                    manifest = manifest.copy(extras = manifest.extras + (path to dir))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    coverPath?.let { storage.deleteTemp(it) }
                    storage.deleteTemp("$dir/cover.jpg")
                    storage.deleteTemp("$dir/cover.png")
                    storage.deleteTemp("$dir/cover.webp")
                    warnings += SyncIssue.CoverFailed(dir)
                }
            }

            progress(SyncPhase.PLAYLISTS, 0, plan.playlistWrites.size, "")
            val effectivePaths = manifest.tracks.mapValues { it.value.relPath }.toMutableMap()
            for ((index, write) in plan.playlistWrites.withIndex()) {
                coroutineContext.ensureActive()
                progress(SyncPhase.PLAYLISTS, index, plan.playlistWrites.size, write.name)
                val playlist = selectedRemote.first { it.id == write.playlistId }
                val content = NavidromeSyncPlanner.writeM3u(playlist, effectivePaths)
                val old = manifest.playlists[write.playlistId]
                val owned = old?.m3uRelPath?.equals(write.relPath, true) == true ||
                    manifest.playlists.values.any { it.m3uRelPath.equals(write.relPath, true) }
                storage.writeText(write.relPath, content, replaceExisting = owned)
                manifest = manifest.copy(
                    playlists = manifest.playlists + (write.playlistId to
                        ManifestPlaylist(playlist.name, write.relPath, "navsync-" + playlist.id)),
                )
            }
            persistence.save(manifest)

            progress(SyncPhase.CLEANUP, 0, plan.playlistDeletions.size + plan.trackDeletions.size, "")
            var cleanupDone = 0
            for (deletion in plan.playlistDeletions) {
                val existed = storage.exists(deletion.relPath)
                val deletedNow = existed && storage.delete(deletion.relPath)
                val removed = !existed || deletedNow || !storage.exists(deletion.relPath)
                if (deletedNow) deleted++
                if (removed) storage.pruneEmptyParents(deletion.relPath)
                if (removed && manifest.playlists[deletion.playlistId]?.m3uRelPath?.equals(deletion.relPath, true) == true) {
                    manifest = manifest.copy(playlists = manifest.playlists - deletion.playlistId)
                }
                cleanupDone++
                progress(SyncPhase.CLEANUP, cleanupDone, plan.playlistDeletions.size + plan.trackDeletions.size, deletion.relPath)
            }
            persistence.save(manifest)
            for (deletion in trackDeletionsToExecute(plan.trackDeletions, manifest.tracks, plan.resolvedTracks.keys, failedIds)) {
                val existed = storage.exists(deletion.relPath)
                val deletedNow = existed && storage.delete(deletion.relPath)
                val removed = !existed || deletedNow || !storage.exists(deletion.relPath)
                if (deletedNow) {
                    deleted++
                    changedAudioPaths += deletion.relPath
                }
                if (removed) storage.pruneEmptyParents(deletion.relPath)
                if (removed && manifest.tracks[deletion.songId]?.relPath?.equals(deletion.relPath, true) == true) {
                    manifest = manifest.copy(tracks = manifest.tracks - deletion.songId)
                }
                cleanupDone++
                progress(SyncPhase.CLEANUP, cleanupDone, plan.playlistDeletions.size + plan.trackDeletions.size, deletion.relPath)
            }
            val resolvedTrackPaths = plan.resolvedTracks
                .filterKeys { it !in failedIds }
                .values
                .map { it.relPath }
            val staleTracks = manifest.tracks.filterKeys { it !in plan.resolvedTracks.keys && it !in failedIds }
                .filterValues { track ->
                    resolvedTrackPaths.any { it.equals(track.relPath, true) } || !storage.exists(track.relPath)
                }
                .keys
            if (staleTracks.isNotEmpty()) manifest = manifest.copy(tracks = manifest.tracks - staleTracks)
            val dirs = manifest.tracks.values.map { it.relPath.substringBeforeLast('/', "") } +
                manifest.playlists.values.map { it.m3uRelPath.substringBeforeLast('/', "") }
            dirs.filter { it.isNotBlank() }.forEach(::rememberDirectories)
            val trackDirectories = manifest.tracks.values
                .map { it.relPath.substringBeforeLast('/', "") }
                .filter { it.isNotBlank() }
                .toSet()
            manifest.extras.filterValues { owner ->
                trackDirectories.none { it.equals(owner, ignoreCase = true) }
            }.forEach { (path, _) ->
                val existed = storage.exists(path)
                val deletedNow = existed && storage.delete(path)
                val removed = !existed || deletedNow || !storage.exists(path)
                if (deletedNow) deleted++
                if (removed) {
                    storage.pruneEmptyParents(path)
                    manifest = manifest.copy(extras = manifest.extras - path)
                }
            }
            persistence.save(manifest)
            return SyncExecutionResult(
                manifest,
                SyncSummary(downloaded, skipped, deleted, failed, warnings),
                changedAudioPaths,
                managedDirectories,
            )
        } finally {
            try {
                storage.cleanupStrayTemps(managedDirectories)
            } finally {
                storage.endSync()
            }
        }
    }
}
