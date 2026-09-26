package com.aurora.music.data.sync

enum class SyncMode { MANUAL, AUTO }

data class NavidromeSyncConfig(
    val accountKey: String = "",
    val treeUri: String = "",
    val mode: SyncMode = SyncMode.MANUAL,
    val selectedPlaylistIds: Set<String> = emptySet(),
    val serverRootPath: String = "",
)

data class RemotePlaylistSummary(
    val id: String,
    val name: String,
    val songCount: Int,
    val owner: String,
)

data class SyncTrack(
    val id: String,
    val title: String = "",
    val artist: String = "",
    val durationSec: Int = 0,
    val path: String = "",
    val size: Long = 0,
    val suffix: String = "",
    val coverArt: String? = null,
)

data class RemotePlaylist(
    val id: String,
    val name: String,
    val owner: String = "",
    val tracks: List<SyncTrack> = emptyList(),
)

data class ManifestTrack(val relPath: String = "", val size: Long = 0)
data class ManifestPlaylist(
    val name: String = "",
    val m3uRelPath: String = "",
    val localPlaylistId: String = "",
)

data class SyncManifest(
    val treeUri: String = "",
    val accountKey: String = "",
    val tracks: Map<String, ManifestTrack> = emptyMap(),
    val playlists: Map<String, ManifestPlaylist> = emptyMap(),
    val extras: Map<String, String> = emptyMap(),
    /** Files whose destination was reserved before a download started. */
    val pending: Map<String, String> = emptyMap(),
)

data class TrackDownload(val track: SyncTrack, val relPath: String)
data class TrackDeletion(val songId: String, val relPath: String)
data class PlaylistWrite(val playlistId: String, val name: String, val relPath: String, val content: String)
data class PlaylistDeletion(val playlistId: String, val relPath: String, val localPlaylistId: String)

data class SyncPlan(
    val downloads: List<TrackDownload> = emptyList(),
    val trackDeletions: List<TrackDeletion> = emptyList(),
    val playlistWrites: List<PlaylistWrite> = emptyList(),
    val playlistDeletions: List<PlaylistDeletion> = emptyList(),
    val resolvedTracks: Map<String, ManifestTrack> = emptyMap(),
    val resolvedPlaylists: Map<String, ManifestPlaylist> = emptyMap(),
)

enum class SyncPhase { PREPARING, FETCHING, DOWNLOADING, COVERS, PLAYLISTS, CLEANUP, LIBRARY }

sealed interface SyncIssue {
    data object TagBasedPaths : SyncIssue
    data class DownloadFailed(val title: String, val detail: String) : SyncIssue
    data class CoverFailed(val dir: String) : SyncIssue
    data object MirrorUnsupportedProvider : SyncIssue
    data object MirrorUnavailable : SyncIssue
    data object NotConfigured : SyncIssue
    data object NoAccount : SyncIssue
    data object AccountUnavailable : SyncIssue
    data object PermissionLost : SyncIssue
    data object NothingSelected : SyncIssue
    data object AllPlaylistsMissing : SyncIssue
    data class ServerError(val detail: String) : SyncIssue
    data class Unexpected(val detail: String) : SyncIssue
}

sealed class SyncStatus {
    data object Idle : SyncStatus()
    data class Running(val phase: SyncPhase, val completed: Int, val total: Int, val currentItem: String) : SyncStatus()
    data class Finished(
        val downloaded: Int,
        val skipped: Int,
        val deleted: Int,
        val failed: Int,
        val warnings: List<SyncIssue>,
        val finishedAtMillis: Long,
    ) : SyncStatus()
    data class Failed(val issue: SyncIssue) : SyncStatus()
}

data class SyncSummary(
    val downloaded: Int,
    val skipped: Int,
    val deleted: Int,
    val failed: Int,
    val warnings: List<SyncIssue>,
)
