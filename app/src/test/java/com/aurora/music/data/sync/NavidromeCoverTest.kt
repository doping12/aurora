package com.aurora.music.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavidromeCoverTest {
    @Test fun placeholderResponseIsSkipped() {
        assertNull(coverFileExtension("public, no-store", "image/webp"))
    }

    @Test fun jpegWithCacheHeadersUsesJpg() {
        assertEquals("jpg", coverFileExtension("public, max-age=31536000, immutable", "image/jpeg"))
    }

    @Test fun pngUsesPng() {
        assertEquals("png", coverFileExtension("public, max-age=31536000", "image/png"))
    }

    @Test fun nonImageIsSkipped() {
        assertNull(coverFileExtension("public, max-age=31536000", "application/octet-stream"))
    }
}
