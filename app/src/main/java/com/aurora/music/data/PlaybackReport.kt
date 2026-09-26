package com.aurora.music.data

import com.aurora.music.data.remote.YouTubeMusicTransport
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope

// Stand-ins for playback reporting sources that upstream (85dcf21) referenced but never committed.
// Reports are never produced, so server-side progress/history sync stays inactive.

enum class PlaybackReportEvent { START, PROGRESS, STOP, SCROBBLE }

enum class PlaybackReportState { PLAYING, PAUSED, BUFFERING, STOPPED }

data class PlaybackReport(
    val event: PlaybackReportEvent,
    val state: PlaybackReportState,
    val song: Song,
    val sessionId: String,
    val positionMs: Long,
    val durationMs: Long,
    val listenedMs: Long,
    val startedAtMs: Long,
    val playbackRate: Float = 1f,
)

class PlaybackReportTarget(val song: Song, val send: suspend (PlaybackReport) -> Unit)

@Suppress("unused")
class PlaybackReportDispatcher(
    private val scope: CoroutineScope,
    private val allowed: () -> Boolean,
    private val onFailure: () -> Unit,
)

@Suppress("unused")
internal class YouTubeMusicPlaybackTracker(private val api: YouTubeMusicTransport) {
    suspend fun report(report: PlaybackReport) {}
}
