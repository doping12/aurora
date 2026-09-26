package com.aurora.music.data.sync

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileSyncStorageTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun tempRenameAndPruningNeverDeletesRoot() {
        val storage = FileSyncStorage(folder.root)
        storage.openTemp("Artist/Album/song.flac").use { it.write(byteArrayOf(1, 2, 3)) }
        assertNull(storage.size("Artist/Album/song.flac"))
        storage.commitTemp("Artist/Album/song.flac")
        assertEquals(3L, storage.size("Artist/Album/song.flac"))
        storage.delete("Artist/Album/song.flac")
        storage.pruneEmptyParents("Artist/Album/song.flac")
        assertTrue(folder.root.exists())
        assertFalse(folder.root.resolve("Artist").exists())
    }

    @Test fun textIsUtf8AndExistingFileIsReplaced() {
        val storage = FileSyncStorage(folder.root)
        storage.writeText("list.m3u8", "#EXTM3U\n日本語\n")
        storage.writeText("list.m3u8", "#EXTM3U\nnew\n")
        assertEquals("#EXTM3U\nnew\n", folder.root.resolve("list.m3u8").readText())
        assertFalse(folder.root.resolve("list.m3u8.part").exists())
    }
}
