package com.aurora.music.data.sync

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NavidromeSyncExecutorTest {
    @get:Rule val folder = TemporaryFolder()

    private fun track(id: String, path: String = "Artist/Album/song.flac", size: Long = 4) =
        SyncTrack(id, "Title", "Artist", 1, path, size, "flac")

    private fun execute(
        root: File,
        manifest: SyncManifest = SyncManifest(),
        remote: List<RemotePlaylist>,
        selected: Set<String> = setOf("p"),
        available: Set<String> = setOf("p"),
        fail: Boolean = false,
        persisted: MutableList<SyncManifest> = mutableListOf(),
        storage: SyncStorage = FileSyncStorage(root),
        fetchCover: suspend (SyncTrack) -> CoverDownload? = { null },
    ): SyncExecutionResult = runBlocking {
        NavidromeSyncExecutor(
            storage,
            download = { _, output ->
                if (fail) error("offline")
                output.write("data".toByteArray())
            },
            fetchCover = fetchCover,
            persistence = SyncManifestPersistence { persisted += it },
        ).execute(manifest, remote, selected, available)
    }

    private class DeleteFailingStorage(
        private val delegate: SyncStorage,
        private val failedPath: String,
    ) : SyncStorage by delegate {
        override fun delete(relPath: String): Boolean =
            if (relPath.equals(failedPath, ignoreCase = true)) false else delegate.delete(relPath)
    }

    @Test fun unmanagedFileIsPreservedAndCollisionIsDownloaded() {
        val root = folder.root
        root.resolve("Artist/Album/song.flac").apply { parentFile.mkdirs(); writeText("keep") }
        val result = execute(root, remote = listOf(RemotePlaylist("p", "List", tracks = listOf(track("song")))))
        assertEquals("keep", root.resolve("Artist/Album/song.flac").readText())
        assertTrue(root.resolve("Artist/Album/song [song].flac").isFile)
        assertEquals("Artist/Album/song [song].flac", result.manifest.tracks.getValue("song").relPath)
    }

    @Test fun replacementOfUnreferencedOwnedPathDownloadsInPlace() {
        val root = folder.root
        root.resolve("Artist/Album/song.flac").apply { parentFile.mkdirs(); writeText("old") }
        val manifest = SyncManifest(
            tracks = mapOf("old-id" to ManifestTrack("Artist/Album/song.flac", 3)),
        )
        val result = execute(
            root,
            manifest,
            listOf(RemotePlaylist("p", "List", tracks = listOf(track("new-id")))),
            storage = DeleteFailingStorage(FileSyncStorage(root), "Artist/Album/song.flac"),
        )
        assertEquals("data", root.resolve("Artist/Album/song.flac").readText())
        assertEquals("Artist/Album/song.flac", result.manifest.tracks.getValue("new-id").relPath)
        assertFalse(result.manifest.tracks.containsKey("old-id"))
        assertEquals(0, result.summary.deleted)
    }

    @Test fun unmanagedPlaylistIsPreservedAndGetsCollisionName() {
        val root = folder.root
        root.resolve("List.m3u8").writeText("keep playlist")
        val result = execute(root, remote = listOf(RemotePlaylist("p", "List", tracks = listOf(track("song")))))
        assertEquals("keep playlist", root.resolve("List.m3u8").readText())
        assertTrue(root.resolve("List [p].m3u8").isFile)
        assertEquals("List [p].m3u8", result.manifest.playlists.getValue("p").m3uRelPath)
    }

    @Test fun unreferencedTrackIsDeletedAndMovedTrackFailureKeepsOldPath() {
        val root = folder.root
        root.resolve("old.flac").writeText("old!")
        val oldManifest = SyncManifest(
            tracks = mapOf("old" to ManifestTrack("old.flac", 4)),
            playlists = mapOf("old-p" to ManifestPlaylist(m3uRelPath = "Old.m3u8")),
        )
        val result = execute(root, oldManifest, listOf(RemotePlaylist("p", "New", tracks = listOf(track("new")))))
        assertFalse(root.resolve("old.flac").exists())
        assertTrue(result.manifest.tracks.containsKey("new"))

        val movedRoot = folder.newFolder("moved")
        movedRoot.resolve("old.flac").writeText("old!")
        val moved = SyncManifest(tracks = mapOf("song" to ManifestTrack("old.flac", 4)))
        val failed = execute(
            movedRoot, moved,
            listOf(RemotePlaylist("p", "List", tracks = listOf(track("song", "new.flac", 5)))),
            fail = true,
        )
        assertTrue(movedRoot.resolve("old.flac").exists())
        assertTrue(movedRoot.resolve("List.m3u8").readText().contains("old.flac"))
        assertTrue(failed.manifest.tracks.getValue("song").relPath == "old.flac")
    }

    @Test fun failedTrackDeletionKeepsManifestEntryAndRetriesLater() {
        val root = folder.root
        root.resolve("old.flac").writeText("old!")
        val manifest = SyncManifest(tracks = mapOf("old" to ManifestTrack("old.flac", 4)))
        val result = execute(
            root,
            manifest,
            listOf(RemotePlaylist("p", "List", tracks = listOf(track("new")))),
            storage = DeleteFailingStorage(FileSyncStorage(root), "old.flac"),
        )
        assertTrue(root.resolve("old.flac").exists())
        assertTrue(result.manifest.tracks.containsKey("old"))
        assertEquals(0, result.summary.deleted)
    }

    @Test fun missingTrackDeletionDoesNotIncreaseDeletedCount() {
        val result = execute(
            folder.root,
            SyncManifest(tracks = mapOf("old" to ManifestTrack("old.flac", 4))),
            listOf(RemotePlaylist("p", "List", tracks = listOf(track("new")))),
        )
        assertFalse(result.manifest.tracks.containsKey("old"))
        assertEquals(0, result.summary.deleted)
    }

    @Test fun coverIsRemovedWhenItsAlbumHasNoRemainingTrack() {
        val root = folder.root
        root.resolve("Artist/Album/song.flac").apply { parentFile.mkdirs(); writeText("old!") }
        root.resolve("Artist/Album/cover.jpg").writeText("cover")
        val manifest = SyncManifest(
            tracks = mapOf("old" to ManifestTrack("Artist/Album/song.flac", 4)),
            extras = mapOf("Artist/Album/cover.jpg" to "Artist/Album"),
        )
        val result = execute(
            root,
            manifest,
            listOf(RemotePlaylist("p", "List")),
        )
        assertFalse(root.resolve("Artist/Album/cover.jpg").exists())
        assertFalse(root.resolve("Artist/Album").exists())
        assertFalse(result.manifest.extras.containsKey("Artist/Album/cover.jpg"))
    }

    @Test fun coverIsKeptWhileItsAlbumHasARemainingTrack() {
        val root = folder.root
        root.resolve("Artist/Album/song.flac").apply { parentFile.mkdirs(); writeText("data") }
        root.resolve("Artist/Album/cover.jpg").writeText("cover")
        val manifest = SyncManifest(
            tracks = mapOf("song" to ManifestTrack("Artist/Album/song.flac", 4)),
            extras = mapOf("Artist/Album/cover.jpg" to "Artist/Album"),
        )
        val result = execute(
            root,
            manifest,
            listOf(RemotePlaylist("p", "List", tracks = listOf(track("song")))),
        )
        assertTrue(root.resolve("Artist/Album/cover.jpg").exists())
        assertTrue(result.manifest.extras.containsKey("Artist/Album/cover.jpg"))
    }

    @Test fun failedPlaylistDeletionKeepsManifestEntry() {
        val root = folder.root
        root.resolve("Old.m3u8").writeText("old playlist")
        val manifest = SyncManifest(
            playlists = mapOf("old" to ManifestPlaylist("Old", "Old.m3u8", "navsync-old")),
        )
        val result = execute(
            root,
            manifest,
            listOf(RemotePlaylist("p", "New")),
            storage = DeleteFailingStorage(FileSyncStorage(root), "Old.m3u8"),
        )
        assertTrue(root.resolve("Old.m3u8").exists())
        assertTrue(result.manifest.playlists.containsKey("old"))
        assertEquals(0, result.summary.deleted)
    }

    @Test fun failedCoverDeletionKeepsManifestEntry() {
        val root = folder.root
        root.resolve("Artist/Album/cover.jpg").apply { parentFile.mkdirs(); writeText("cover") }
        val manifest = SyncManifest(
            extras = mapOf("Artist/Album/cover.jpg" to "Artist/Album"),
        )
        val result = execute(
            root,
            manifest,
            listOf(RemotePlaylist("p", "List")),
            storage = DeleteFailingStorage(FileSyncStorage(root), "Artist/Album/cover.jpg"),
        )
        assertTrue(root.resolve("Artist/Album/cover.jpg").exists())
        assertTrue(result.manifest.extras.containsKey("Artist/Album/cover.jpg"))
        assertEquals(0, result.summary.deleted)
    }

    @Test fun emptySelectionAndAllMissingAreRefusedBeforeDeletion() {
        val old = ManifestTrack("old.flac", 4)
        val manifest = SyncManifest(tracks = mapOf("old" to old), playlists = mapOf("old-p" to ManifestPlaylist(m3uRelPath = "old.m3u8")))
        var emptyIssue: SyncIssue? = null
        try { execute(folder.root, manifest, emptyList(), emptySet()) } catch (e: SyncExecutionException) { emptyIssue = e.issue }
        assertEquals(SyncIssue.NothingSelected, emptyIssue)
        var missingIssue: SyncIssue? = null
        try { execute(folder.root, manifest, emptyList(), setOf("gone"), emptySet()) } catch (e: SyncExecutionException) { missingIssue = e.issue }
        assertEquals(SyncIssue.AllPlaylistsMissing, missingIssue)
        assertFalse(folder.root.resolve("old.flac").exists())
    }

    @Test fun failedRedownloadKeepsOwnedFileAndPlaylistEntry() {
        val root = folder.root
        root.resolve("Artist/Album/song.flac").apply { parentFile.mkdirs(); writeText("old") }
        val manifest = SyncManifest(
            tracks = mapOf("song" to ManifestTrack("Artist/Album/song.flac", 3)),
        )
        val result = execute(
            root, manifest,
            listOf(RemotePlaylist("p", "List", tracks = listOf(track("song", size = 4)))),
            fail = true,
        )
        assertEquals("old", root.resolve("Artist/Album/song.flac").readText())
        assertTrue(result.summary.warnings.any { it is SyncIssue.DownloadFailed })
        assertTrue(root.resolve("List.m3u8").readText().contains("Artist/Album/song.flac"))
    }

    @Test fun pendingFileIsRecoveredAndZeroSizeMissingTrackDownloads() {
        val root = folder.root
        root.resolve("Artist/Album/pending.flac").apply { parentFile.mkdirs(); writeText("data") }
        val pending = SyncManifest(pending = mapOf("Artist/Album/pending.flac" to "pending"))
        val recovered = execute(
            root, pending,
            listOf(RemotePlaylist("p", "List", tracks = listOf(track("pending", "Artist/Album/pending.flac", 0)))),
        )
        assertTrue(recovered.manifest.tracks.containsKey("pending"))
        assertTrue(recovered.manifest.pending.isEmpty())
        val zero = execute(
            folder.newFolder("zero"),
            remote = listOf(RemotePlaylist("p", "List", tracks = listOf(track("zero", size = 0)))),
        )
        assertTrue(zero.manifest.tracks.containsKey("zero"))
    }

    @Test fun strayTempsAreRemovedFromManagedDirectories() {
        val root = folder.root
        root.resolve(".aurora-sync-deadbeef.part").writeText("stray")
        root.resolve("Artist/.aurora-sync-cafebabe.part").apply { parentFile.mkdirs(); writeText("stray") }
        execute(root, remote = listOf(RemotePlaylist("p", "List", tracks = listOf(track("song")))))
        assertFalse(root.resolve(".aurora-sync-deadbeef.part").exists())
        assertFalse(root.resolve("Artist/.aurora-sync-cafebabe.part").exists())
    }

    @Test fun corruptManifestIsMovedAsideInsteadOfBecomingEmpty() {
        val manifestFile = folder.root.resolve("manifest.json")
        manifestFile.writeText("{not json")
        var issue: SyncIssue? = null
        try {
            SyncManifestFileStore(manifestFile).load()
        } catch (e: SyncExecutionException) {
            issue = e.issue
        }
        assertEquals(SyncIssue.Unexpected("manifest unreadable"), issue)
        assertFalse(manifestFile.exists())
        assertTrue(folder.root.listFiles()!!.any { it.name.startsWith("manifest.json.corrupt-") })
    }
}
