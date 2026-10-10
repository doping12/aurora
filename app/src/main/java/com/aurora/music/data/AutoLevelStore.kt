package com.aurora.music.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.LinkedHashMap

/** Recent per-track measured RMS levels, stored as dBFS. */
class AutoLevelStore(context: Context) {
    private val file = File(context.filesDir, "auto_levels.json")
    private val gson = Gson()
    private val lock = Any()
    private val values = LinkedHashMap<String, Double>(load())

    private fun load(): Map<String, Double> = try {
        if (!file.exists()) emptyMap()
        else gson.fromJson<Map<String, Double>>(file.readText(), object : TypeToken<Map<String, Double>>() {}.type)
            ?.filterValues { it.isFinite() } ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    fun get(key: String): Double? = synchronized(lock) { values[key] }

    fun put(key: String, estimateDb: Double) = synchronized(lock) {
        if (key.isBlank() || !estimateDb.isFinite()) return@synchronized
        values.remove(key)
        values[key] = estimateDb
        while (values.size > MAX_ENTRIES) values.remove(values.keys.first())
        file.writeText(gson.toJson(values))
    }

    companion object { const val MAX_ENTRIES = 5000 }
}
