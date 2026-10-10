package com.aurora.music.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeModesTest {
    @Test
    fun networkSchemesUseNetworkWakeModeCaseInsensitively() {
        listOf("http", "https", "aurora-yt", "aurora-extension", "HTTPS", "Aurora-Yt")
            .forEach { scheme -> assertEquals(C.WAKE_MODE_NETWORK, wakeModeFor(scheme)) }
    }

    @Test
    fun localSchemesAndMissingSchemesUseLocalWakeMode() {
        listOf("file", "content", "android.resource", null, "")
            .forEach { scheme -> assertEquals(C.WAKE_MODE_LOCAL, wakeModeFor(scheme)) }
    }
}
