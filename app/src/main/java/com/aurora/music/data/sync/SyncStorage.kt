package com.aurora.music.data.sync

import java.io.OutputStream

interface SyncStorage {
    fun beginSync() {}
    fun endSync() {}
    fun size(relPath: String): Long?
    fun exists(relPath: String): Boolean = size(relPath) != null
    fun openTemp(relPath: String): OutputStream
    /** Legacy commit entry point; implementations may override either overload. */
    fun commitTemp(relPath: String) { commitTemp(relPath, replaceExisting = false) }
    /** Commits a temp file, replacing the destination only when this caller owns it. */
    fun commitTemp(relPath: String, replaceExisting: Boolean) { commitTemp(relPath) }
    fun deleteTemp(relPath: String) {}
    fun delete(relPath: String): Boolean
    fun pruneEmptyParents(relPath: String)
    fun cleanupStrayTemps(directories: Set<String> = emptySet()) {}
    fun writeText(relPath: String, text: String, replaceExisting: Boolean = true) {
        openTemp(relPath).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        commitTemp(relPath, replaceExisting)
    }
}
