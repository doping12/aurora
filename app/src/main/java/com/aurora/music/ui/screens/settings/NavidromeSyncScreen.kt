package com.aurora.music.ui.screens.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.ServerType
import com.aurora.music.data.accountKey
import com.aurora.music.data.sync.RemotePlaylistSummary
import com.aurora.music.data.sync.SyncIssue
import com.aurora.music.data.sync.SyncMode
import com.aurora.music.data.sync.SyncPaths
import com.aurora.music.data.sync.SyncPhase
import com.aurora.music.data.sync.SyncStatus
import com.aurora.music.localization.appString
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun NavidromeSyncScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val manager = container.navidromeSync
    val config by manager.config.collectAsStateWithLifecycle()
    val status by manager.state.collectAsStateWithLifecycle()
    val active by container.settingsStore.session.collectAsStateWithLifecycle(initialValue = null)
    val saved by container.settingsStore.savedSessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val accounts = remember(active, saved) {
        (listOfNotNull(active) + saved).filter { it.type == ServerType.SUBSONIC }.distinctBy { it.accountKey() }
    }
    var playlists by remember { mutableStateOf<List<RemotePlaylistSummary>>(emptyList()) }
    var loadingPlaylists by remember { mutableStateOf(false) }
    var playlistError by remember { mutableStateOf(false) }
    var folderError by remember { mutableStateOf(false) }
    var rootText by remember(config.serverRootPath) { mutableStateOf(config.serverRootPath) }
    val busy = status is SyncStatus.Running

    suspend fun refreshPlaylists() {
        loadingPlaylists = true
        playlistError = false
        manager.fetchPlaylists().onSuccess { playlists = it }.onFailure { playlistError = true }
        loadingPlaylists = false
    }

    LaunchedEffect(config.accountKey, accounts) {
        if (accounts.isNotEmpty()) refreshPlaylists()
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && !busy) {
            folderError = false
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val old = config.treeUri
            val granted = runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }.isSuccess
            if (!granted) {
                folderError = true
            } else {
                manager.configStore.setTreeUri(uri.toString())
                if (old.isNotBlank() && old != uri.toString()) {
                    runCatching { context.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(old), flags) }
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.navidrome_sync_title), onBack)
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            SettingsSectionTitle(appString(R.string.navidrome_sync_account))
            SettingsGroup {
                SettingsSwitchRow(
                    title = appString(R.string.navidrome_sync_automatic_account),
                    checked = config.accountKey.isBlank(),
                    onCheckedChange = { if (it && !busy) manager.configStore.setAccountKey("", accounts.firstOrNull()?.accountKey()) },
                )
                accounts.forEach { account ->
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        title = "${account.username} @ ${account.server}",
                        checked = config.accountKey == account.accountKey(),
                        onCheckedChange = { if (it && !busy) manager.configStore.setAccountKey(account.accountKey(), accounts.firstOrNull()?.accountKey()) },
                    )
                }
            }

            SettingsSectionTitle(appString(R.string.navidrome_sync_destination))
            SettingsGroup {
                SettingsNavRow(
                    Icons.Filled.Folder,
                    appString(R.string.navidrome_sync_destination),
                    subtitle = config.treeUri.takeIf { it.isNotBlank() }?.let { uri ->
                        SyncPaths.readableTreePath(android.net.Uri.parse(uri), context.getString(R.string.navidrome_sync_internal_storage))
                    }
                        ?: appString(R.string.navidrome_sync_not_set),
                    onClick = { if (!busy) folderLauncher.launch(null) },
                )
            }
            OutlinedButton(
                onClick = { folderLauncher.launch(null) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            ) { Text(appString(R.string.navidrome_sync_choose_folder)) }
            if (folderError) Text(
                appString(R.string.navidrome_sync_folder_permission_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            SettingsSectionTitle(appString(R.string.navidrome_sync_sync_mode))
            SegmentedRow(
                appString(R.string.navidrome_sync_sync_mode),
                listOf(appString(R.string.navidrome_sync_manual), appString(R.string.navidrome_sync_automatic)),
                if (config.mode == SyncMode.AUTO) 1 else 0,
            ) { if (!busy) manager.configStore.setMode(if (it == 1) SyncMode.AUTO else SyncMode.MANUAL) }
            if (config.mode == SyncMode.AUTO) {
                Text(
                    appString(R.string.navidrome_sync_automatic_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            SettingsSectionTitle(appString(R.string.navidrome_sync_playlists))
            SettingsGroup {
                TextButton(onClick = { scope.launch { refreshPlaylists() } }, enabled = !loadingPlaylists && !busy) {
                    Text(if (loadingPlaylists) appString(R.string.navidrome_sync_loading) else appString(R.string.navidrome_sync_refresh))
                }
                if (playlistError) {
                    Text(appString(R.string.navidrome_sync_refresh_failed), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                playlists.forEach { playlist ->
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        title = playlist.name,
                        subtitle = appString(R.string.navidrome_sync_song_count, playlist.songCount),
                        checked = playlist.id in config.selectedPlaylistIds,
                        onCheckedChange = { checked -> if (!busy) {
                            manager.configStore.setSelectedPlaylistIds(
                                if (checked) config.selectedPlaylistIds + playlist.id else config.selectedPlaylistIds - playlist.id,
                            )
                        } },
                    )
                }
            }

            SettingsSectionTitle(appString(R.string.navidrome_sync_sync))
            SyncStatusContent(status)
            val canSync = !busy && config.treeUri.isNotBlank() && config.selectedPlaylistIds.isNotEmpty() &&
                (config.accountKey.isNotBlank() || accounts.any { it.type == ServerType.SUBSONIC })
            Button(
                onClick = { manager.startManualSync() },
                enabled = canSync,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            ) { Text(if (busy) appString(R.string.navidrome_sync_loading) else appString(R.string.navidrome_sync_sync_now)) }
            if (busy) {
                OutlinedButton(
                    onClick = manager::cancel,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                ) { Text(appString(R.string.navidrome_sync_cancel)) }
            }

            SettingsSectionTitle(appString(R.string.navidrome_sync_advanced))
            Text(
                appString(R.string.navidrome_sync_server_root_path_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = rootText,
                onValueChange = { if (!busy) rootText = it },
                readOnly = busy,
                label = { Text(appString(R.string.navidrome_sync_server_root_path)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (!busy) manager.configStore.setServerRootPath(rootText) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                    .onFocusChanged { if (!it.isFocused && !busy) manager.configStore.setServerRootPath(rootText) },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SyncStatusContent(status: SyncStatus) {
    when (status) {
        SyncStatus.Idle -> Unit
        is SyncStatus.Running -> {
            val progress = if (status.total > 0) (status.completed.toFloat() / status.total).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
            Text(
                "${phaseLabel(status.phase)} · ${status.completed} / ${status.total}" +
                    status.currentItem.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        is SyncStatus.Finished -> {
            Text(
                appString(R.string.navidrome_sync_finished_summary, status.downloaded, status.skipped, status.deleted, status.failed),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Text(
                appString(R.string.navidrome_sync_finished_at, DateFormat.getDateTimeInstance().format(Date(status.finishedAtMillis))),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            if (status.warnings.isNotEmpty()) {
                Text(appString(R.string.navidrome_sync_warnings), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                status.warnings.forEach { issue ->
                    Text(issueText(issue), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
                }
                if (status.warnings.any { it is SyncIssue.TagBasedPaths }) {
                    Text(appString(R.string.navidrome_sync_tag_paths), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                }
            }
        }
        is SyncStatus.Failed -> Text(issueText(status.issue), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
}

private fun phaseLabel(phase: SyncPhase): String = when (phase) {
    SyncPhase.PREPARING -> appString(R.string.navidrome_sync_preparing)
    SyncPhase.FETCHING -> appString(R.string.navidrome_sync_fetching)
    SyncPhase.DOWNLOADING -> appString(R.string.navidrome_sync_downloading)
    SyncPhase.COVERS -> appString(R.string.navidrome_sync_covers)
    SyncPhase.PLAYLISTS -> appString(R.string.navidrome_sync_playlists_phase)
    SyncPhase.CLEANUP -> appString(R.string.navidrome_sync_cleanup)
    SyncPhase.LIBRARY -> appString(R.string.navidrome_sync_library)
}

private fun issueText(issue: SyncIssue): String = when (issue) {
    SyncIssue.TagBasedPaths -> appString(R.string.navidrome_sync_tag_paths)
    is SyncIssue.DownloadFailed -> appString(R.string.navidrome_sync_download_failed, issue.title, issue.detail)
    is SyncIssue.CoverFailed -> appString(R.string.navidrome_sync_cover_failed, issue.dir)
    SyncIssue.MirrorUnsupportedProvider -> appString(R.string.navidrome_sync_mirror_unsupported)
    SyncIssue.MirrorUnavailable -> appString(R.string.navidrome_sync_mirror_unavailable)
    SyncIssue.NotConfigured -> appString(R.string.navidrome_sync_not_configured)
    SyncIssue.NoAccount -> appString(R.string.navidrome_sync_no_account)
    SyncIssue.AccountUnavailable -> appString(R.string.navidrome_sync_account_unavailable)
    SyncIssue.PermissionLost -> appString(R.string.navidrome_sync_permission_lost)
    SyncIssue.NothingSelected -> appString(R.string.navidrome_sync_nothing_selected)
    SyncIssue.AllPlaylistsMissing -> appString(R.string.navidrome_sync_all_playlists_missing)
    is SyncIssue.ServerError -> appString(R.string.navidrome_sync_server_error, issue.detail)
    is SyncIssue.Unexpected -> appString(R.string.navidrome_sync_unexpected, issue.detail)
}
