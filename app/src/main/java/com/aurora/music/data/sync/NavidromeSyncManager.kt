package com.aurora.music.data.sync

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.aurora.music.R
import com.aurora.music.data.LocalLibrary
import com.aurora.music.data.LocalStore
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.SettingsStore
import com.aurora.music.data.accountKey
import com.aurora.music.data.remote.SongDto
import com.aurora.music.data.remote.SubsonicClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private class SyncFailureException(val issue: SyncIssue) : Exception()

internal fun coverFileExtension(cacheControl: String?, contentType: String?): String? {
    if (cacheControl?.contains("no-store", ignoreCase = true) == true) return null
    return when (contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> null
    }
}

class NavidromeSyncManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val localLibrary: LocalLibrary,
    private val localStore: LocalStore,
) {
    @Volatile var automaticAccountKey: String = ""
    val configStore = NavidromeSyncStore(context) { automaticAccountKey }
    val config: StateFlow<NavidromeSyncConfig> = configStore.config
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val state: StateFlow<SyncStatus> = _state.asStateFlow()
    private val manifestFile = File(context.filesDir, "navidrome_sync/manifest.json")
    private val manifestStore = SyncManifestFileStore(manifestFile)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).build()
    @Volatile private var running = false
    @Volatile private var runJob: Job? = null

    fun startManualSync(): Boolean {
        synchronized(this) {
            if (running) return false
            if (config.value.treeUri.isBlank()) {
                _state.value = SyncStatus.Failed(SyncIssue.NotConfigured)
                return false
            }
            running = true
            _state.value = SyncStatus.Running(SyncPhase.PREPARING, 0, 0, "")
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val currentJob = currentCoroutineContext()[Job]
                try {
                    val result = performSync()
                    publishTerminal(currentJob, SyncStatus.Finished(
                        result.downloaded, result.skipped, result.deleted, result.failed,
                        result.warnings, System.currentTimeMillis(),
                    ))
                } catch (_: CancellationException) {
                    publishTerminal(currentJob, SyncStatus.Idle)
                } catch (e: Exception) {
                    publishTerminal(currentJob, SyncStatus.Failed(
                        (e as? SyncFailureException)?.issue
                            ?: (e as? SyncExecutionException)?.issue
                            ?: SyncIssue.Unexpected(e.message ?: "Navidrome sync failed"),
                    ))
                } finally {
                    synchronized(this@NavidromeSyncManager) {
                        if (runJob === currentJob) {
                            running = false
                            runJob = null
                        }
                    }
                }
            }
            runJob = job
            job.start()
            return true
        }
    }

    private fun publishTerminal(job: Job?, status: SyncStatus) {
        synchronized(this) {
            if (runJob === job) {
                // Clear first so a subscriber can immediately start the next sync.
                running = false
                runJob = null
                _state.value = status
            }
        }
    }

    fun cancel() { synchronized(this) { runJob?.cancel() } }

    suspend fun fetchPlaylists(): Result<List<RemotePlaylistSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val client = SubsonicClient(resolveAccount(), clientName = "AuroraSync")
            val response = client.api.getPlaylists().response
            if (!response.isOk) throw SyncFailureException(
                SyncIssue.ServerError(response.error?.message ?: "Could not fetch Navidrome playlists"),
            )
            response.playlists?.playlist.orEmpty().map {
                RemotePlaylistSummary(it.id, it.name, it.songCount, it.owner.orEmpty())
            }
        }
    }

    private suspend fun performSync(): SyncSummary {
        val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aurora:NavidromeSync")
        @Suppress("DEPRECATION")
        val wifi = (context.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Aurora:NavidromeSync")
        try {
            wake.acquire()
            @Suppress("DEPRECATION")
            wifi.acquire()
            val cfg = config.value
            val account = resolveAccount()
            val accountKey = account.accountKey()
            val tree = Uri.parse(cfg.treeUri)
            verifyPermission(tree)
            val client = SubsonicClient(account, clientName = "AuroraSync")
            _state.value = SyncStatus.Running(SyncPhase.FETCHING, 0, 0, "")
            val listResponse = client.api.getPlaylists().response
            if (!listResponse.isOk) throw SyncFailureException(
                SyncIssue.ServerError(listResponse.error?.message ?: "Could not fetch Navidrome playlists"),
            )
            val available = listResponse.playlists?.playlist.orEmpty()
            val availableIds = available.map { it.id }.toSet()
            val selected = available.filter { it.id in cfg.selectedPlaylistIds }
            val remote = ArrayList<RemotePlaylist>()
            for ((index, summary) in selected.withIndex()) {
                currentCoroutineContext().ensureActive()
                _state.value = SyncStatus.Running(SyncPhase.FETCHING, index, selected.size, summary.name)
                val response = client.api.getPlaylist(summary.id).response
                if (!response.isOk) throw SyncFailureException(
                    SyncIssue.ServerError(response.error?.message ?: "Could not fetch playlist " + summary.name),
                )
                remote += response.playlist?.let {
                    RemotePlaylist(it.id, it.name, it.owner.orEmpty(), it.entry.map(::toTrack))
                } ?: throw SyncFailureException(SyncIssue.ServerError("Navidrome returned no playlist " + summary.id))
            }

            val loaded = manifestStore.load()
            val initial = loaded.takeIf { it.treeUri == cfg.treeUri && it.accountKey == accountKey }
                ?: SyncManifest(cfg.treeUri, accountKey)
            val storage = SafSyncStorage(context, tree)
            val persistence: SyncManifestPersistence = manifestStore
            val executor = NavidromeSyncExecutor(
                storage = storage,
                download = { item, output -> downloadTrack(client.downloadUrl(item.track.id), item, output) },
                fetchCover = { track ->
                    val cover = track.coverArt
                    if (cover == null) null else downloadCover(client.originalCoverArtUrl(cover))
                },
                persistence = persistence,
                progress = { phase, completed, total, current ->
                    _state.value = SyncStatus.Running(phase, completed, total, current)
                },
            )
            val result = executor.execute(
                initialManifest = initial,
                remotePlaylists = remote,
                selectedPlaylistIds = cfg.selectedPlaylistIds,
                availablePlaylistIds = availableIds,
                serverRootPath = cfg.serverRootPath,
            )
            val finalManifest = result.manifest.copy(treeUri = cfg.treeUri, accountKey = accountKey)
            val mirrorWarnings = mirror(cfg.treeUri, remote, finalManifest, result.changedAudioPaths)
            manifestStore.save(finalManifest)
            return result.summary.copy(warnings = result.summary.warnings + mirrorWarnings)
        } finally {
            @Suppress("DEPRECATION")
            if (wifi.isHeld) wifi.release()
            if (wake.isHeld) wake.release()
        }
    }

    private suspend fun downloadTrack(url: String, item: TrackDownload, output: OutputStream) = withContext(Dispatchers.IO) {
        val response = http.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            check(it.isSuccessful) { "HTTP " + it.code }
            val body = it.body ?: error("Empty response")
            var count = 0L
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    count += n
                }
            }
            val expected = item.track.size.takeIf { it > 0 }
                ?: it.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
            check(expected == null || expected == count) { "Downloaded " + count + " bytes, expected " + expected }
        }
    }

    private suspend fun downloadCover(url: String): CoverDownload? = withContext(Dispatchers.IO) {
        val response = http.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.isSuccessful || it.body == null) return@withContext null
            val ext = coverFileExtension(it.header("Cache-Control"), it.header("Content-Type"))
                ?: return@withContext null
            CoverDownload(ext, it.body!!.bytes())
        }
    }

    private suspend fun mirror(tree: String, remote: List<RemotePlaylist>, manifest: SyncManifest, changed: Set<String>): List<SyncIssue> {
        val root = SyncPaths.realTreeRoot(Uri.parse(tree))
            ?: return listOf(SyncIssue.MirrorUnsupportedProvider)
        val paths = changed.map { File(root, it).absolutePath }
        val warnings = ArrayList<SyncIssue>()
        if (withTimeoutOrNull(30_000) { scan(paths) } == null) warnings += SyncIssue.MirrorUnavailable
        try {
            localLibrary.refresh()
            val byPath = localLibrary.songs.associateBy { File(it.path).absolutePath }
            if (byPath.isEmpty() && manifest.tracks.isNotEmpty()) return warnings + SyncIssue.MirrorUnavailable
            val desired = remote.map { "navsync-" + it.id }.toSet()
            localStore.playlists().filter { it.id.startsWith("navsync-") && it.id !in desired }
                .forEach { localStore.deletePlaylist(it.id) }
            val subtitle = context.getString(R.string.navidrome_sync_mirror_subtitle)
            remote.forEach { playlist ->
                val ids = playlist.tracks.mapNotNull { track ->
                    manifest.tracks[track.id]?.let { byPath[File(root, it.relPath).absolutePath]?.id }
                }
                localStore.upsertPlaylist("navsync-" + playlist.id, playlist.name, subtitle, ids)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            warnings += SyncIssue.MirrorUnavailable
        }
        return warnings
    }

    private suspend fun scan(paths: List<String>) = suspendCancellableCoroutine<Unit> { continuation ->
        if (paths.isEmpty()) {
            continuation.resume(Unit)
            return@suspendCancellableCoroutine
        }
        val remaining = java.util.concurrent.atomic.AtomicInteger(paths.size)
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
            if (remaining.decrementAndGet() == 0 && continuation.isActive) continuation.resume(Unit)
        }
    }

    private fun verifyPermission(uri: Uri) {
        val ok = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!ok) throw SyncFailureException(SyncIssue.PermissionLost)
    }

    private suspend fun resolveAccount(): Session {
        val cfg = config.value
        val active = settingsStore.session.first()
        val saved = settingsStore.savedSessions.first()
        val candidates = listOfNotNull(active) + saved
        val result = if (cfg.accountKey.isBlank()) {
            candidates.firstOrNull { it.type == ServerType.SUBSONIC }
                ?: throw SyncFailureException(SyncIssue.NoAccount)
        } else {
            candidates.firstOrNull { it.accountKey() == cfg.accountKey && it.type == ServerType.SUBSONIC }
                ?: throw SyncFailureException(SyncIssue.AccountUnavailable)
        }
        automaticAccountKey = candidates.firstOrNull { it.type == ServerType.SUBSONIC }?.accountKey().orEmpty()
        return result
    }

    private fun toTrack(song: SongDto) = SyncTrack(
        song.id, song.title, song.artist.orEmpty(), song.duration, song.path.orEmpty(),
        song.size, song.suffix.orEmpty(), song.coverArt,
    )

}
