package com.aurora.music.data.sync

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.OutputStream
import java.util.UUID

class SafSyncStorage(context: Context, treeUri: Uri) : SyncStorage {
    private val resolver: ContentResolver = context.contentResolver
    private val tree = treeUri
    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val cache = HashMap<String, MutableMap<String, Uri>>()
    private val tempFiles = HashMap<String, Uri>()
    private val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)

    override fun beginSync() { cache.clear(); tempFiles.clear() }
    override fun endSync() { cache.clear(); tempFiles.clear() }

    private fun children(parent: Uri, cached: Boolean = true): MutableMap<String, Uri> {
        val key = parent.toString()
        if (cached) cache[key]?.let { return it }
        val result = LinkedHashMap<String, Uri>()
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val cursor = resolver.query(childUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?: error("Cannot list sync directory $parent")
        cursor.use {
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idCol)
                result[cursor.getString(nameCol)] = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            }
        }
        cache[key] = result
        return result
    }

    private fun child(parent: Uri, name: String): Uri? {
        val entries = children(parent)
        return entries[name] ?: if (tree.authority == "com.android.externalstorage.documents") {
            entries.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        } else null
    }

    private fun removeChild(parent: Uri, name: String) {
        children(parent).keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { children(parent).remove(it) }
    }

    private fun displayName(uri: Uri): String? = resolver.query(
        uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) else null
    }

    private fun isDirectory(uri: Uri): Boolean = resolver.query(
        uri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null,
    )?.use { cursor ->
        cursor.moveToFirst() && cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)) ==
            DocumentsContract.Document.MIME_TYPE_DIR
    } ?: false

    private fun requireCreatedName(uri: Uri, expected: String, kind: String): Uri {
        val actual = displayName(uri)
        if (actual != expected) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            error("Storage provider changed $kind name '$expected' to '${actual ?: "<unknown>"}'")
        }
        return uri
    }

    private fun parentAndName(relPath: String, create: Boolean): Pair<Uri, String> {
        val parts = relPath.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "Empty sync path" }
        var parent = rootUri
        for (part in parts.dropLast(1)) {
            val existing = child(parent, part)
            parent = existing ?: if (create) {
                val made = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, part)
                    ?: error("Cannot create directory $part")
                requireCreatedName(made, part, "directory")
                children(parent)[part] = made
                made
            } else return parent to parts.last()
        }
        return parent to parts.last()
    }

    private fun find(relPath: String): Uri? {
        val parts = relPath.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var parent = rootUri
        for (part in parts.dropLast(1)) parent = child(parent, part) ?: return null
        return child(parent, parts.last())
    }

    override fun size(relPath: String): Long? {
        val uri = find(relPath) ?: return null
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use {
            if (it.moveToFirst()) return it.getLong(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE))
        }
        return null
    }

    override fun exists(relPath: String): Boolean = find(relPath) != null

    override fun openTemp(relPath: String): OutputStream {
        val (parent, name) = parentAndName(relPath, true)
        val tempName = ".aurora-sync-${UUID.randomUUID().toString().replace("-", "").take(8)}.part"
        val temp = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", tempName)
            ?: error("Cannot create temporary file $tempName")
        requireCreatedName(temp, tempName, "temporary file")
        children(parent)[tempName] = temp
        tempFiles[relPath] = temp
        return resolver.openOutputStream(temp, "w") ?: error("Cannot open $tempName")
    }

    override fun commitTemp(relPath: String, replaceExisting: Boolean) {
        val (parent, name) = parentAndName(relPath, false)
        val temp = tempFiles[relPath] ?: error("Missing temporary file for $relPath")
        val tempName = displayName(temp) ?: error("Missing temporary file for $relPath")
        val existing = child(parent, name)
        if (existing != null) {
            require(replaceExisting) { "Refusing to replace unmanaged $relPath" }
            require(!isDirectory(existing)) { "Refusing to replace directory $relPath" }
            require(DocumentsContract.deleteDocument(resolver, existing)) { "Cannot replace $relPath" }
        }
        tempFiles.remove(relPath)
        val renamed = DocumentsContract.renameDocument(resolver, temp, name) ?: error("Cannot commit $relPath")
        val actual = displayName(renamed)
        if (actual != name) {
            runCatching { DocumentsContract.deleteDocument(resolver, renamed) }
            removeChild(parent, tempName)
            error("Storage provider changed file name '$name' to '${actual ?: "<unknown>"}'")
        }
        removeChild(parent, tempName)
        children(parent)[name] = renamed
    }

    override fun deleteTemp(relPath: String) {
        tempFiles.remove(relPath)?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
    }

    override fun delete(relPath: String): Boolean {
        val uri = find(relPath) ?: return true
        if (isDirectory(uri)) return false
        val (parent, name) = parentAndName(relPath, false)
        val result = DocumentsContract.deleteDocument(resolver, uri)
        if (result) removeChild(parent, name)
        return result
    }

    override fun pruneEmptyParents(relPath: String) {
        val parts = relPath.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() }
        var parent = rootUri
        val parents = ArrayList<Triple<Uri, Uri, String>>()
        for (part in parts.dropLast(1)) {
            val existing = child(parent, part) ?: return
            parents += Triple(parent, existing, part)
            parent = existing
        }
        for ((container, dir, name) in parents.asReversed()) {
            val currentChildren = children(dir, cached = false)
            if (currentChildren.isNotEmpty()) break
            if (!DocumentsContract.deleteDocument(resolver, dir)) break
            removeChild(container, name)
        }
    }

    override fun cleanupStrayTemps(directories: Set<String>) {
        val paths = linkedSetOf("").apply { addAll(directories) }
        paths.forEach { path ->
            val parent = if (path.isBlank()) rootUri else find(path) ?: return@forEach
            children(parent, cached = false).filterKeys { TEMP_PATTERN.matches(it) }.values.forEach {
                runCatching { DocumentsContract.deleteDocument(resolver, it) }
            }
            cache.remove(parent.toString())
        }
        tempFiles.clear()
    }

    private companion object {
        val TEMP_PATTERN = Regex("\\.aurora-sync-[0-9a-fA-F]{8}\\.part")
    }
}
