package com.aurora.music.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

internal object PlaylistCoverKey {
    fun build(serverId: String, playlistId: String): String =
        "${serverId.length}:$serverId${playlistId.length}:$playlistId"
}

class PlaylistCoverStore(private val context: Context) {
    private val directory = File(context.filesDir, "playlist-covers")
    private val indexFile = File(context.filesDir, "playlist_covers.json")
    private val gson = Gson()
    private val lock = Any()

    @Volatile private var data: Map<String, String> = load()

    private fun load(): Map<String, String> = runCatching {
        if (!indexFile.isFile) return@runCatching emptyMap()
        val type = object : TypeToken<Map<String, String>>() {}.type
        gson.fromJson<Map<String, String>>(indexFile.readText(), type).orEmpty()
    }.getOrDefault(emptyMap())

    fun get(serverId: String, playlistId: String): String? =
        data[PlaylistCoverKey.build(serverId, playlistId)]

    fun setUrl(serverId: String, playlistId: String, url: String) {
        require(url.isNotBlank()) { "Playlist cover URL must not be blank." }
        update(PlaylistCoverKey.build(serverId, playlistId), url)
    }

    suspend fun setImage(serverId: String, playlistId: String, uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open this image." }.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_INPUT_BYTES) { "Choose an image smaller than 20 MB." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= MAX_PIXELS) {
            "Choose a supported image under 100 megapixels."
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE * 2) sample *= 2
        var bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })) { "Cannot read this image." }
        try {
            val orientation = runCatching {
                bytes.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
            }
            val scale = MAX_EDGE.toDouble() / maxOf(bitmap.width, bitmap.height)
            if (scale < 1) {
                val resized = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                if (resized !== bitmap) { bitmap.recycle(); bitmap = resized }
            }
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) { "Cannot encode this image." }
            directory.mkdirs()
            val key = PlaylistCoverKey.build(serverId, playlistId)
            val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val image = File.createTempFile("$hash-${System.currentTimeMillis()}-", ".jpg", directory)
            try {
                image.writeBytes(output.toByteArray())
                update(key, Uri.fromFile(image).toString())
            } catch (e: Exception) {
                image.delete()
                throw e
            }
        } finally { bitmap.recycle() }
    }

    fun clear(serverId: String, playlistId: String) {
        remove(PlaylistCoverKey.build(serverId, playlistId))
    }

    fun remove(serverId: String, playlistId: String) = clear(serverId, playlistId)

    private fun update(key: String, url: String) = synchronized(lock) {
        val old = data[key]
        val updated = data + (key to url)
        indexFile.writeText(gson.toJson(updated))
        data = updated
        if (old != url) deleteStoredImage(old)
    }

    private fun remove(key: String) = synchronized(lock) {
        val old = data[key] ?: return
        val updated = data - key
        indexFile.writeText(gson.toJson(updated))
        data = updated
        deleteStoredImage(old)
    }

    private fun deleteStoredImage(url: String?) {
        if (url.isNullOrBlank()) return
        runCatching { Uri.parse(url).path?.let(::File) }
            .getOrNull()?.takeIf { it.parentFile?.canonicalFile == directory.canonicalFile }?.delete()
    }

    private companion object {
        const val MAX_INPUT_BYTES = 20 * 1024 * 1024
        const val MAX_PIXELS = 100_000_000L
        const val MAX_EDGE = 768
    }
}
