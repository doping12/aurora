package com.aurora.music.playback

import java.text.Normalizer
import java.util.ArrayDeque
import java.util.PriorityQueue
import kotlin.random.Random

/** Title based shuffle that tries to keep versions of the same song apart. */
object SmartShuffle {
    const val TITLE_PREFIX_LENGTH = 5
    const val DEFAULT_MIN_GAP = 5

    fun titleKey(title: String, prefixLength: Int = TITLE_PREFIX_LENGTH): String {
        if (prefixLength <= 0) return ""
        val normalized = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase()
        val delimiterStart = versionDelimiterStart(normalized)
        val source = if (delimiterStart > 0) normalized.substring(0, delimiterStart) else normalized
        val key = buildString {
            var offset = 0
            var count = 0
            while (offset < source.length && count < prefixLength) {
                val codePoint = source.codePointAt(offset)
                offset += Character.charCount(codePoint)
                val type = Character.getType(codePoint)
                if (type == Character.UPPERCASE_LETTER.toInt() ||
                    type == Character.LOWERCASE_LETTER.toInt() ||
                    type == Character.TITLECASE_LETTER.toInt() ||
                    type == Character.MODIFIER_LETTER.toInt() ||
                    type == Character.OTHER_LETTER.toInt() ||
                    type == Character.DECIMAL_DIGIT_NUMBER.toInt() ||
                    type == Character.LETTER_NUMBER.toInt() ||
                    type == Character.OTHER_NUMBER.toInt()
                ) {
                    appendCodePoint(codePoint)
                    count++
                }
            }
        }
        return key
    }

    private fun versionDelimiterStart(title: String): Int {
        val bracket = listOf('(', '[', '{', '（', '［', '｛')
            .mapNotNull { title.indexOf(it).takeIf { index -> index >= 0 } }
            .minOrNull()
        val spacedDelimiter = Regex("\\s[-–—~/]\\s|\\s[:：～]").find(title)?.range?.first
        return listOfNotNull(bracket, spacedDelimiter).minOrNull() ?: -1
    }

    /**
     * Shuffles uniformly first, then greedily separates matching title keys. [pinFirst] keeps
     * the first input item at index zero and treats it as already played for gap purposes.
     * Empty title keys are made unique per item so they never match another song.
     */
    fun <T> shuffle(
        items: List<T>,
        titleOf: (T) -> String,
        random: Random = Random.Default,
        minGap: Int = DEFAULT_MIN_GAP,
        pinFirst: Boolean = false,
        recentTitles: List<String> = emptyList(),
    ): List<T> {
        if (items.size <= 1) return items.toList()
        val pinned = if (pinFirst) items.first() else null
        val candidates = (if (pinFirst) items.drop(1) else items).shuffled(random)
        val keyed = candidates.mapIndexed { index, item ->
            val key = titleKey(titleOf(item)).ifEmpty { "\u0000$index" }
            Candidate(item, key, index)
        }
        val queues = LinkedHashMap<String, ArrayDeque<Candidate<T>>>()
        keyed.forEach { queues.getOrPut(it.key) { ArrayDeque() }.addLast(it) }
        val gap = minGap.coerceAtLeast(0).coerceAtMost((queues.size - 1).coerceAtLeast(0))
        if (gap == 0) return (listOfNotNull(pinned) + candidates).toList()

        val available = PriorityQueue<Candidate<T>>(compareBy { it.order })
        val allRemaining = PriorityQueue<Candidate<T>>(compareBy { it.order })
        val blocked = HashMap<String, Int>()
        val recent = ArrayDeque<String>()
        val remainingCounts = queues.mapValuesTo(HashMap()) { it.value.size }
        val urgencyVersions = HashMap<String, Int>()
        val urgency = PriorityQueue<Urgency>(compareByDescending<Urgency> { it.need })
        fun blockedRemaining(key: String): Int {
            val history = recent.toList()
            val lastIndex = history.indexOfLast { it == key }
            if (lastIndex < 0) return 0
            return (gap - (history.lastIndex - lastIndex)).coerceAtLeast(0)
        }
        fun refreshUrgency(key: String) {
            val count = remainingCounts[key] ?: 0
            val version = (urgencyVersions[key] ?: 0) + 1
            urgencyVersions[key] = version
            if (count > 1) {
                val need = (count - 1) * (gap + 1) + 1 + blockedRemaining(key)
                urgency.add(Urgency(key, need, version))
            }
        }
        fun currentUrgencies(): List<Urgency> {
            val current = ArrayList<Urgency>()
            while (urgency.isNotEmpty()) {
                val entry = urgency.remove()
                val count = remainingCounts[entry.key] ?: 0
                val expected = if (count > 1) {
                    (count - 1) * (gap + 1) + 1 + blockedRemaining(entry.key)
                } else 0
                val version = urgencyVersions[entry.key]
                if (version == entry.version && expected == entry.need) current.add(entry)
            }
            urgency.addAll(current)
            return current
        }
        fun remember(key: String) {
            recent.addLast(key)
            blocked[key] = (blocked[key] ?: 0) + 1
            if (recent.size > gap) {
                val expired = recent.removeFirst()
                val count = (blocked[expired] ?: 1) - 1
                if (count == 0) {
                    blocked.remove(expired)
                    queues[expired]?.peekFirst()?.let(available::add)
                } else blocked[expired] = count
            }
        }

        (recentTitles + listOfNotNull(pinned).map(titleOf)).takeLast(gap)
            .map { titleKey(it) }.filter { it.isNotEmpty() }.forEach(::remember)
        queues.forEach { (key, queue) ->
            queue.peekFirst()?.let { candidate ->
                allRemaining.add(candidate)
                if (blocked[key] == null) available.add(candidate)
                refreshUrgency(key)
            }
        }

        val result = ArrayList<T>(items.size)
        if (pinned != null) result.add(pinned)
        while (result.size < items.size) {
            val slotsRemaining = items.size - result.size
            var urgentKey: String? = null
            var urgencyIndex = 0
            for (entry in currentUrgencies()) {
                if (entry.need + urgencyIndex >= slotsRemaining && blocked[entry.key] == null) {
                    urgentKey = entry.key
                    break
                }
                urgencyIndex++
            }
            var next = urgentKey?.let { queues[it]?.peekFirst() }
            if (next == null) {
                var eligible: Candidate<T>? = null
                while (available.isNotEmpty() && eligible == null) {
                    val head = available.remove()
                    if (queues[head.key]?.peekFirst() === head && blocked[head.key] == null) eligible = head
                }
                next = eligible
            }
            if (next == null) {
                // No eligible key remains: make best effort by choosing the earliest blocked key.
                var candidate: Candidate<T>? = null
                while (allRemaining.isNotEmpty() && candidate == null) {
                    val head = allRemaining.remove()
                    if (queues[head.key]?.peekFirst() === head) candidate = head
                }
                next = candidate
            }
            val chosen = next ?: break
            val queue = queues.getValue(chosen.key)
            if (queue.peekFirst() === chosen) queue.removeFirst()
            queue.peekFirst()?.let(allRemaining::add)
            remainingCounts[chosen.key] = (remainingCounts[chosen.key] ?: 1) - 1
            result.add(chosen.item)
            val affectedKeys = (recent.toList() + chosen.key).toSet()
            remember(chosen.key)
            (affectedKeys + recent.toList()).forEach(::refreshUrgency)
            if (blocked[chosen.key] == null) queue.peekFirst()?.let(available::add)
        }
        return result
    }

    private data class Candidate<T>(val item: T, val key: String, val order: Int)
    private data class Urgency(val key: String, val need: Int, val version: Int)
}
