package com.aurora.music.data.sync

import org.junit.Assert.*
import org.junit.Test

class SyncPathsTest {
    @Test fun mapsRealAndFakePaths() {
        assertEquals("Artist/Album/original.flac", SyncPaths.map("/srv/music/Artist/Album/original.flac", "id", "flac").relPath)
        assertEquals("Artist/Album/original.flac", SyncPaths.map("/srv/music/Artist/Album/original.flac", "id", "flac", "/srv/music").relPath)
        assertEquals("Artist/Album/song.flac", SyncPaths.map("Artist\\Album\\song.flac", "id", "flac").relPath)
        assertFalse(SyncPaths.map("Artist/Album/song.flac", "id", "flac").isRealPath)
        assertEquals("Artist/Album/file.flac", SyncPaths.map("C:/music/Artist/Album/file.flac", "id", "flac").relPath)
        assertEquals("Unknown/id.bin", SyncPaths.map("", "id", "").relPath)
    }

    @Test fun sanitizesOnlyUnsafeCharactersAndCollisionIsStable() {
        assertEquals("bad_name_.txt", SyncPaths.sanitizeSegment("bad:name?.txt "))
        assertEquals("_", SyncPaths.sanitizeSegment(".."))
        val first = SyncPaths.withCollision("Artist/song.flac", "first-id", emptySet())
        val second = SyncPaths.withCollision("Artist/song.flac", "second-id", setOf(first))
        assertEquals("Artist/song.flac", first)
        assertEquals("Artist/song [second-i].flac", second)
        assertEquals(second, SyncPaths.withCollision("Artist/song.flac", "second-id", setOf(first)))
    }
}
