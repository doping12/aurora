package com.aurora.music.playback

import androidx.media3.common.Player
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.PlaybackReportDispatcher

// No-op stand-in for the controller upstream (85dcf21) referenced but never committed.
@Suppress("unused")
internal class PlaybackReportingController(
    private val repository: MusicRepository,
    private val dispatcher: PlaybackReportDispatcher,
    private val allowed: () -> Boolean,
    private val nativePlaying: (Player?) -> Boolean,
) {
    fun observe(player: Player?) {}
    fun sample() {}
    fun close() {}
}
