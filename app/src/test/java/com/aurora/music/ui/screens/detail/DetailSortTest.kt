package com.aurora.music.ui.screens.detail

import com.aurora.music.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailSortTest {
    private fun song(
        id: String,
        title: String = id,
        artist: String = "Artist",
        album: String = "Album",
        year: Int = 0,
        added: Long = 0,
    ) = Song(id, title, artist, album, "", 180, dateAddedSec = added, releaseYear = year)

    @Test fun originalOrderAndReverseArePreserved() {
        val tracks = listOf(song("a"), song("b"), song("c"))
        assertEquals(listOf("a", "b", "c"), sortDetailTracks(tracks, DetailSort.ORIGINAL, false).map { it.id })
        assertEquals(listOf("c", "b", "a"), sortDetailTracks(tracks, DetailSort.ORIGINAL, true).map { it.id })
    }

    @Test fun releaseDatePutsUnknownLastAndKeepsTiesStable() {
        val tracks = listOf(song("old", year = 2000), song("tie-1", year = 2010), song("tie-2", year = 2010), song("unknown"))
        assertEquals(listOf("old", "tie-1", "tie-2", "unknown"), sortDetailTracks(tracks, DetailSort.RELEASE_DATE, false).map { it.id })
    }

    @Test fun dateAddedDescendingAndReverseDirectionWork() {
        val tracks = listOf(song("unknown"), song("new", added = 20), song("old", added = 10))
        assertEquals(listOf("new", "old", "unknown"), sortDetailTracks(tracks, DetailSort.DATE_ADDED, true).map { it.id })
        assertEquals(listOf("old", "new", "unknown"), sortDetailTracks(tracks, DetailSort.DATE_ADDED, false).map { it.id })
    }

    @Test fun artistUsesTitleAsTieBreakAndAlbumIsStable() {
        val tracks = listOf(
            song("z", title = "Z", artist = "Same", album = "A"),
            song("a", title = "A", artist = "Same", album = "A"),
            song("b", title = "B", artist = "Other", album = "B"),
        )
        assertEquals(listOf("b", "a", "z"), sortDetailTracks(tracks, DetailSort.ARTIST, false).map { it.id })
        assertEquals(listOf("z", "a", "b"), sortDetailTracks(tracks, DetailSort.ALBUM, false).map { it.id })
    }
}
