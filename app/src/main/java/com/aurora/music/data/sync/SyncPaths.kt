package com.aurora.music.data.sync

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/** Maps Navidrome's path field without changing ordinary file names. */
object SyncPaths {
    data class Result(val relPath: String, val isRealPath: Boolean)

    fun map(path: String?, songId: String, suffix: String?, serverRootPath: String = ""): Result {
        val normalized = path.orEmpty().replace('\\', '/')
        if (normalized.isBlank()) {
            val name = sanitizeSegment("$songId.${suffix.orEmpty().ifBlank { "bin" }}")
            return Result("Unknown/$name", false)
        }
        val absolute = normalized.startsWith('/') || normalized.matches(Regex("^[A-Za-z]:/.*"))
        val relative = if (!absolute) {
            normalized.removePrefix("./").removePrefix("/")
        } else {
            val root = serverRootPath.replace('\\', '/').trimEnd('/')
            when {
                root.isNotBlank() && (normalized == root || normalized.startsWith("$root/")) ->
                    normalized.removePrefix(root).trimStart('/')
                else -> normalized.split('/').filter { it.isNotEmpty() }.takeLast(3).joinToString("/")
            }
        }
        val segments = relative.split('/').filter { it.isNotEmpty() }.map(::sanitizeSegment)
        if (segments.isEmpty()) {
            val name = sanitizeSegment("$songId.${suffix.orEmpty().ifBlank { "bin" }}")
            return Result("Unknown/$name", absolute)
        }
        return Result(segments.joinToString("/"), absolute)
    }

    fun sanitizeSegment(segment: String): String {
        val replaced = segment.map { c ->
            when {
                c.code < 32 || c in "\\:*?\"<>|" -> '_'
                else -> c
            }
        }.joinToString("").trimEnd(' ', '.')
        return if (replaced.isEmpty() || replaced == "." || replaced == "..") "_" else replaced
    }

    fun withCollision(base: String, songId: String, occupied: Set<String>): String {
        if (occupied.none { it.equals(base, ignoreCase = true) }) return base
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        val candidate = "$stem [${songId.take(8)}]$ext"
        if (occupied.none { it.equals(candidate, ignoreCase = true) }) return candidate
        var n = 2
        while (occupied.any { it.equals("$stem [${songId.take(8)} $n]$ext", ignoreCase = true) }) n++
        return "$stem [${songId.take(8)} $n]$ext"
    }

    fun realTreeRoot(tree: Uri): File? {
        if (tree.authority != "com.android.externalstorage.documents") return null
        val doc = DocumentsContract.getTreeDocumentId(tree)
        val colon = doc.indexOf(':')
        if (colon <= 0) return null
        val volume = doc.substring(0, colon)
        val relative = doc.substring(colon + 1)
        return if (volume.equals("primary", true)) File(Environment.getExternalStorageDirectory(), relative)
        else File("/storage/$volume", relative)
    }

    fun readableTreePath(tree: Uri, internalStorageLabel: String): String? {
        if (tree.authority != "com.android.externalstorage.documents") return null
        val doc = DocumentsContract.getTreeDocumentId(tree)
        val colon = doc.indexOf(':')
        if (colon <= 0) return null
        val volume = doc.substring(0, colon)
        val relative = doc.substring(colon + 1).trim('/')
        return if (volume.equals("primary", true)) {
            internalStorageLabel + if (relative.isBlank()) "" else "/$relative"
        } else {
            "/storage/$volume" + if (relative.isBlank()) "" else "/$relative"
        }
    }
}
