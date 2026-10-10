package com.aurora.music.playback

import androidx.media3.common.C

/** Selects the wake mode from the original media URI, before any cache resolution. */
internal fun wakeModeFor(scheme: String?): Int = when (scheme?.lowercase()) {
    "http", "https", "aurora-yt", "aurora-extension" -> C.WAKE_MODE_NETWORK
    else -> C.WAKE_MODE_LOCAL
}
