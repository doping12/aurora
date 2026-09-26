package com.aurora.music.data.sync

import org.junit.Assert.*
import org.junit.Test

class NavidromeSyncPlannerTest {
    private fun track(id: String, path: String = "Artist/Album/$id.flac", size: Long = 10) =
        SyncTrack(id, "Title $id", "Artist", 120, path, size, "flac")

    @Test fun newSyncWritesOneFileForSharedTrackAndM3u() {
        val shared = track("shared")
        val first = RemotePlaylist("p1", "One", tracks = listOf(shared, track("a")))
        val second = RemotePlaylist("p2", "Two", tracks = listOf(shared))
        val plan = NavidromeSyncPlanner.plan(SyncManifest(), listOf(first, second))
        assertEquals(2, plan.downloads.size)
        assertEquals(0, plan.trackDeletions.size)
        assertTrue(plan.playlistWrites.single { it.playlistId == "p1" }.content.contains("#EXTINF:120,Artist - Title shared"))
    }

    @Test fun incrementalUnmanagedMatchingFileIsDownloadedAndUnreferencedIsDeleted() {
        val old = track("old")
        val manifest = SyncManifest(tracks = mapOf("old" to ManifestTrack("Artist/Album/old.flac", 10)))
        val current = RemotePlaylist("p", "List", tracks = listOf(track("new")))
        val plan = NavidromeSyncPlanner.plan(manifest, listOf(current), mapOf("Artist/Album/new.flac" to 10L))
        assertEquals(1, plan.downloads.size)
        assertEquals("new", plan.downloads.single().track.id)
        assertEquals(listOf("old"), plan.trackDeletions.map { it.songId })
        assertEquals(old.size, manifest.tracks.getValue("old").size)
    }

    @Test fun sizeMismatchAndMovedTrackAreDownloadedAndOldPathRemoved() {
        val old = track("id", "Artist/Album/old.flac", 10)
        val manifest = SyncManifest(tracks = mapOf("id" to ManifestTrack("Artist/Album/old.flac", 10)))
        val moved = old.copy(path = "Artist/Album/new.flac", size = 11)
        val plan = NavidromeSyncPlanner.plan(manifest, listOf(RemotePlaylist("p", "P", tracks = listOf(moved))),
            mapOf("Artist/Album/new.flac" to 3L))
        assertEquals("Artist/Album/new [id].flac", plan.downloads.single().relPath)
        assertEquals("Artist/Album/old.flac", plan.trackDeletions.single().relPath)
    }

    @Test fun m3uPreservesOrderAndDuplicates() {
        val t = track("id")
        val playlist = RemotePlaylist("p", "P", tracks = listOf(t, t))
        val output = NavidromeSyncPlanner.writeM3u(playlist, mapOf("id" to "Artist/Album/id.flac"))
        assertEquals(2, output.lines().count { it == "Artist/Album/id.flac" })
        assertTrue(output.startsWith("#EXTM3U\n#EXTINF:120,Artist - Title id\n"))
    }

    @Test fun renamedPlaylistDeletesItsOldFile() {
        val old = SyncManifest(playlists = mapOf("p" to ManifestPlaylist("Old", "Old.m3u8", "navsync-p")))
        val plan = NavidromeSyncPlanner.plan(old, listOf(RemotePlaylist("p", "New")))
        assertEquals("Old.m3u8", plan.playlistDeletions.single().relPath)
        assertEquals("New.m3u8", plan.playlistWrites.single().relPath)
    }

    @Test fun pathOwnedByUnreferencedTrackIsReusableByReplacement() {
        val manifest = SyncManifest(tracks = mapOf("x" to ManifestTrack("P/song.flac", 10)))
        val current = RemotePlaylist("p", "P", tracks = listOf(track("y", "P/song.flac")))
        val plan = NavidromeSyncPlanner.plan(manifest, listOf(current))
        assertEquals("P/song.flac", plan.downloads.single().relPath)
        assertTrue(plan.trackDeletions.none { it.relPath.equals("P/song.flac", ignoreCase = true) })
    }

    @Test fun unreferencedTrackIsSelectedForDeletion() {
        val deletion = TrackDeletion("old", "Artist/Album/old.flac")

        assertEquals(
            listOf(deletion),
            trackDeletionsToExecute(
                plannedDeletions = listOf(deletion),
                manifestTracks = mapOf("old" to ManifestTrack(deletion.relPath, 10)),
                referencedTrackIds = emptySet(),
                failedIds = emptySet(),
            ),
        )
    }

    @Test fun movedTrackWithSuccessfulDownloadIsSelectedForDeletion() {
        val deletion = TrackDeletion("id", "Artist/Album/old.flac")

        assertEquals(
            listOf(deletion),
            trackDeletionsToExecute(
                plannedDeletions = listOf(deletion),
                manifestTracks = mapOf("id" to ManifestTrack("Artist/Album/new.flac", 10)),
                referencedTrackIds = setOf("id"),
                failedIds = emptySet(),
            ),
        )
    }

    @Test fun movedTrackWithFailedDownloadIsKept() {
        val deletion = TrackDeletion("id", "Artist/Album/old.flac")

        assertTrue(
            trackDeletionsToExecute(
                plannedDeletions = listOf(deletion),
                manifestTracks = mapOf("id" to ManifestTrack(deletion.relPath, 10)),
                referencedTrackIds = setOf("id"),
                failedIds = setOf("id"),
            ).isEmpty(),
        )
    }

    @Test fun pathOwnedByAnotherReferencedTrackIsKeptCaseInsensitively() {
        val deletion = TrackDeletion("old", "Artist/Album/old.flac")

        assertTrue(
            trackDeletionsToExecute(
                plannedDeletions = listOf(deletion),
                manifestTracks = mapOf(
                    "old" to ManifestTrack("other path.flac", 10),
                    "replacement" to ManifestTrack("artist/album/OLD.FLAC", 10),
                ),
                referencedTrackIds = setOf("replacement"),
                failedIds = emptySet(),
            ).isEmpty(),
        )
    }

    @Test fun deletedPlaylistPathIsNotDeletedWhenRenamedPlaylistUsesIt() {
        val manifest = SyncManifest(playlists = mapOf("a" to ManifestPlaylist("A", "A.m3u8", "navsync-a")))
        val plan = NavidromeSyncPlanner.plan(manifest, listOf(RemotePlaylist("b", "A")))
        assertTrue(plan.playlistWrites.any { it.relPath == "A.m3u8" })
        assertTrue(plan.playlistDeletions.none { it.relPath.equals("A.m3u8", ignoreCase = true) })
    }
}
