package com.aurora.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaylistCoverKeyTest {
    @Test fun keySeparatesServerAndPlaylistIdsWithoutDelimiterCollisions() {
        assertNotEquals(PlaylistCoverKey.build("ab", "c"), PlaylistCoverKey.build("a", "bc"))
        assertNotEquals(PlaylistCoverKey.build("server-a", "playlist"), PlaylistCoverKey.build("server-b", "playlist"))
        assertEquals(PlaylistCoverKey.build("server-a", "playlist"), PlaylistCoverKey.build("server-a", "playlist"))
    }
}
