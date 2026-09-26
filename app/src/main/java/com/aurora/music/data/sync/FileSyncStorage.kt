package com.aurora.music.data.sync

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID

class FileSyncStorage(private val root: File) : SyncStorage {
    private val tempFiles = HashMap<String, File>()
    private fun file(relPath: String): File {
        val clean = relPath.replace('\\', '/').trimStart('/')
        val result = File(root, clean)
        require(result.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "Path escapes sync root" }
        return result
    }

    override fun size(relPath: String): Long? = file(relPath).takeIf { it.isFile }?.length()

    override fun exists(relPath: String): Boolean = file(relPath).exists()

    override fun openTemp(relPath: String): OutputStream {
        val target = file(relPath)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".aurora-sync-${UUID.randomUUID().toString().replace("-", "").take(8)}.part")
        tempFiles[relPath] = temp
        return FileOutputStream(temp)
    }

    override fun commitTemp(relPath: String, replaceExisting: Boolean) {
        val target = file(relPath)
        val temp = tempFiles[relPath] ?: error("Missing temporary file for $relPath")
        require(temp.isFile) { "Missing temporary file for $relPath" }
        if (target.exists()) {
            require(replaceExisting) { "Refusing to replace unmanaged $relPath" }
            require(target.delete()) { "Cannot replace $relPath" }
        }
        tempFiles.remove(relPath)
        require(temp.renameTo(target)) { "Cannot commit $relPath" }
    }

    override fun deleteTemp(relPath: String) {
        tempFiles.remove(relPath)?.delete()
    }

    override fun delete(relPath: String): Boolean = file(relPath).let { !it.exists() || it.delete() }

    override fun pruneEmptyParents(relPath: String) {
        var current = file(relPath).parentFile
        val canonicalRoot = root.canonicalFile
        while (current != null && current.canonicalFile != canonicalRoot && current.isDirectory && current.list()?.isEmpty() == true) {
            if (!current.delete()) break
            current = current.parentFile
        }
    }

    override fun cleanupStrayTemps(directories: Set<String>) {
        val roots = linkedSetOf(root).apply { addAll(directories.map { file(it) }) }
        roots.filter { it.isDirectory }.forEach { directory ->
            directory.listFiles()?.filter { it.isFile && TEMP_PATTERN.matches(it.name) }?.forEach { it.delete() }
        }
        tempFiles.entries.removeIf { !it.value.exists() }
    }

    private companion object {
        val TEMP_PATTERN = Regex("\\.aurora-sync-[0-9a-fA-F]{8}\\.part")
    }
}
