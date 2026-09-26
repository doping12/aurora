package com.aurora.music.data.sync

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class NavidromeSyncStore(
    context: Context,
    private val automaticAccountKey: () -> String = { "" },
) {
    private val file = File(context.filesDir, "navidrome_sync/config.json")
    private val gson = Gson()
    private val lock = Any()
    private val _config = MutableStateFlow(load())
    val config: StateFlow<NavidromeSyncConfig> = _config.asStateFlow()
    val state: StateFlow<NavidromeSyncConfig> = config

    private fun load(): NavidromeSyncConfig = synchronized(lock) {
        runCatching {
            val raw = if (file.exists()) gson.fromJson(file.readText(), NavidromeSyncConfig::class.java) else null
            raw?.copy(
                accountKey = raw.accountKey.orEmpty(),
                treeUri = raw.treeUri.orEmpty(),
                mode = raw.mode ?: SyncMode.MANUAL,
                selectedPlaylistIds = raw.selectedPlaylistIds ?: emptySet(),
                serverRootPath = raw.serverRootPath.orEmpty(),
            ) ?: NavidromeSyncConfig()
        }.getOrDefault(NavidromeSyncConfig())
    }

    fun update(
        resolvedAutomaticAccountKey: String? = null,
        transform: (NavidromeSyncConfig) -> NavidromeSyncConfig,
    ) = synchronized(lock) {
        val old = _config.value
        val next = transform(old).let {
            if (it.accountKey != old.accountKey) {
                val automatic = resolvedAutomaticAccountKey ?: automaticAccountKey()
                val oldResolved = old.accountKey.ifBlank { automatic }
                val newResolved = it.accountKey.ifBlank { automatic }
                if (oldResolved != newResolved) it.copy(selectedPlaylistIds = emptySet()) else it
            } else it
        }
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(next))
        _config.value = next
        next
    }

    fun setAccountKey(value: String, resolvedAutomaticAccountKey: String? = null) =
        update(resolvedAutomaticAccountKey) { it.copy(accountKey = value) }
    fun setTreeUri(value: String) = update { it.copy(treeUri = value) }
    fun setMode(value: SyncMode) = update { it.copy(mode = value) }
    fun setSelectedPlaylistIds(value: Set<String>) = update { it.copy(selectedPlaylistIds = value.toSet()) }
    fun setServerRootPath(value: String) = update { it.copy(serverRootPath = value) }
}

typealias NavidromeSyncConfigStore = NavidromeSyncStore
