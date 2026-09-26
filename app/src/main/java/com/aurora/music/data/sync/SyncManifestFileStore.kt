package com.aurora.music.data.sync

import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class SyncManifestFileStore(
    private val file: File,
    private val gson: Gson = Gson(),
) : SyncManifestPersistence {
    fun load(): SyncManifest {
        if (!file.exists()) return SyncManifest()
        return try {
            val raw = gson.fromJson(file.readText(), SyncManifest::class.java)
                ?: error("empty manifest")
            raw.copy(
                treeUri = raw.treeUri.orEmpty(),
                accountKey = raw.accountKey.orEmpty(),
                tracks = raw.tracks ?: emptyMap(),
                playlists = raw.playlists ?: emptyMap(),
                extras = raw.extras ?: emptyMap(),
                pending = raw.pending ?: emptyMap(),
            )
        } catch (_: Exception) {
            val corrupt = File(file.parentFile, "manifest.json.corrupt-" + System.currentTimeMillis())
            runCatching { Files.move(file.toPath(), corrupt.toPath(), StandardCopyOption.ATOMIC_MOVE) }
                .onFailure { runCatching { Files.move(file.toPath(), corrupt.toPath()) } }
            throw SyncExecutionException(SyncIssue.Unexpected("manifest unreadable"))
        }
    }

    override fun save(manifest: SyncManifest) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "." + file.name + "-" + UUID.randomUUID() + ".tmp")
        try {
            java.io.FileOutputStream(temporary).use {
                it.write(gson.toJson(manifest).toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}
