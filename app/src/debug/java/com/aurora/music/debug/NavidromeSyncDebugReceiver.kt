package com.aurora.music.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ServerType
import com.aurora.music.data.accountKey
import com.aurora.music.data.remote.SubsonicClient
import com.aurora.music.data.sync.NavidromeSyncManager
import com.aurora.music.data.sync.SyncIssue
import com.aurora.music.data.sync.SyncPhase
import com.aurora.music.data.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class NavidromeSyncDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as AuroraApplication
        receiverScope.launch {
            try {
                withTimeout(8_500) { handle(app, intent) }
            } catch (e: Exception) {
                log("NAVSYNC_ERROR", JSONObject().put("error", e.message ?: e.javaClass.simpleName))
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(app: AuroraApplication, intent: Intent) {
        val manager = app.container.navidromeSync
        when (intent.getStringExtra("cmd").orEmpty()) {
            "add-account" -> addAccount(app, intent)
            "configure" -> configure(app, manager, intent)
            "sync" -> startSync(manager)
            "status" -> logStatus(manager)
            else -> error("unknown cmd")
        }
    }

    private suspend fun addAccount(app: AuroraApplication, intent: Intent) {
        try {
            val session = SubsonicClient.buildSession(
                intent.getStringExtra("server").orEmpty(),
                intent.getStringExtra("user").orEmpty(),
                intent.getStringExtra("password").orEmpty(),
            )
            val response = SubsonicClient(session, clientName = "AuroraSync").api.ping().response
            check(response.isOk) { response.error?.message ?: "Navidrome ping failed" }
            app.container.settingsStore.addSavedSession(session)
            log("NAVSYNC_ACCOUNT", JSONObject().put("ok", true).put("accountKey", session.accountKey()))
        } catch (e: Exception) {
            log("NAVSYNC_ACCOUNT", JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName))
        }
    }

    private suspend fun configure(app: AuroraApplication, manager: NavidromeSyncManager, intent: Intent) {
        try {
            val server = SubsonicClient.normalizeServer(intent.getStringExtra("server").orEmpty())
            val active = app.container.settingsStore.session.first()
            val saved = app.container.settingsStore.savedSessions.first()
            val session = (listOfNotNull(active) + saved).firstOrNull {
                it.type == ServerType.SUBSONIC && SubsonicClient.normalizeServer(it.server) == server
            } ?: error("No saved Navidrome account matches $server")
            manager.configStore.setAccountKey(session.accountKey())
            val available = manager.fetchPlaylists().getOrElse { error(it.message ?: "Could not fetch playlists") }
            val requested = intent.getStringExtra("playlists").orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }
            val selected = if (requested.size == 1 && requested.single() == "*") available else available.filter { it.name in requested }
            val unknown = if (requested.size == 1 && requested.single() == "*") emptyList() else requested.filter { name -> available.none { it.name == name } }
            check(unknown.isEmpty()) { "Unknown playlist(s): ${unknown.joinToString(", ")}" }
            manager.configStore.setSelectedPlaylistIds(selected.map { it.id }.toSet())
            manager.configStore.setServerRootPath(intent.getStringExtra("root") ?: "")
            val result = JSONObject()
                .put("ok", true)
                .put("accountKey", session.accountKey())
                .put("selectedIds", JSONArray(selected.map { it.id }))
                .put("selectedNames", JSONArray(selected.map { it.name }))
                .put("treeUri", manager.config.value.treeUri)
            log("NAVSYNC_CONFIGURED", result)
        } catch (e: Exception) {
            log("NAVSYNC_CONFIGURED", JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName))
        }
    }

    private fun startSync(manager: NavidromeSyncManager) {
        if (!manager.startManualSync()) {
            val state = manager.state.value
            val issue = (state as? SyncStatus.Failed)?.issue ?: SyncIssue.Unexpected("Sync is already running")
            log("NAVSYNC_RESULT", JSONObject().put("state", "failed").put("error", issueText(issue)))
            return
        }
        syncCollector?.cancel()
        syncCollector = processScope.launch {
            var lastPhase: SyncPhase? = null
            manager.state.onEach { state ->
                when (state) {
                    is SyncStatus.Running -> {
                        if (lastPhase != state.phase) {
                            lastPhase = state.phase
                            logStatus(manager)
                        }
                    }
                    is SyncStatus.Finished -> {
                        log("NAVSYNC_RESULT", JSONObject()
                            .put("state", "finished")
                            .put("downloaded", state.downloaded)
                            .put("skipped", state.skipped)
                            .put("deleted", state.deleted)
                            .put("failed", state.failed)
                            .put("warnings", JSONArray(state.warnings.map { issueText(it) })))
                    }
                    is SyncStatus.Failed -> {
                        log("NAVSYNC_RESULT", JSONObject().put("state", "failed").put("error", issueText(state.issue)))
                    }
                    SyncStatus.Idle -> Unit
                }
            }.takeWhile { it !is SyncStatus.Finished && it !is SyncStatus.Failed }.collect()
        }
    }

    private fun logStatus(manager: NavidromeSyncManager) {
        val state = manager.state.value
        val json = JSONObject().put("state", stateName(state))
        if (state is SyncStatus.Running) {
            json.put("phase", state.phase.name).put("completed", state.completed).put("total", state.total).put("currentItem", state.currentItem)
        }
        json.put("treeUri", manager.config.value.treeUri)
            .put("accountKey", manager.config.value.accountKey)
            .put("mode", manager.config.value.mode.name)
            .put("serverRootPath", manager.config.value.serverRootPath)
            .put("selectedPlaylistIds", JSONArray(manager.config.value.selectedPlaylistIds.toList()))
        log("NAVSYNC_STATUS", json)
    }

    private fun stateName(state: SyncStatus): String = when (state) {
        SyncStatus.Idle -> "idle"
        is SyncStatus.Running -> "running"
        is SyncStatus.Finished -> "finished"
        is SyncStatus.Failed -> "failed"
    }

    private fun issueText(issue: SyncIssue): String = when (issue) {
        SyncIssue.TagBasedPaths -> "TagBasedPaths"
        is SyncIssue.DownloadFailed -> "DownloadFailed: ${issue.title}: ${issue.detail}"
        is SyncIssue.CoverFailed -> "CoverFailed: ${issue.dir}"
        SyncIssue.MirrorUnsupportedProvider -> "MirrorUnsupportedProvider"
        SyncIssue.MirrorUnavailable -> "MirrorUnavailable"
        SyncIssue.NotConfigured -> "NotConfigured"
        SyncIssue.NoAccount -> "NoAccount"
        SyncIssue.AccountUnavailable -> "AccountUnavailable"
        SyncIssue.PermissionLost -> "PermissionLost"
        SyncIssue.NothingSelected -> "NothingSelected"
        SyncIssue.AllPlaylistsMissing -> "AllPlaylistsMissing"
        is SyncIssue.ServerError -> "ServerError: ${issue.detail}"
        is SyncIssue.Unexpected -> "Unexpected: ${issue.detail}"
    }

    private fun log(tag: String, json: JSONObject) {
        Log.i("AuroraNavSync", "$tag $json")
    }

    private companion object {
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var syncCollector: Job? = null
    }
}
