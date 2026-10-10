package com.aurora.music.playback

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartShuffleTest {
    @Test
    fun alwaysReturnsPermutationForEdgeCasesAndRandomInputs() {
        val cases = listOf(
            emptyList(),
            listOf("one"),
            List(20) { "same-$it" },
            List(100) { "song-$it" },
        )
        cases.forEachIndexed { caseIndex, items ->
            repeat(10) { seed ->
                assertEquals(items.groupingBy { it }.eachCount(), SmartShuffle.shuffle(
                    items, { it }, Random(caseIndex * 100 + seed),
                ).groupingBy { it }.eachCount())
            }
        }
    }

    @Test
    fun separatesVersionsAcrossConfiguredGap() {
        val versions = listOf("Versioned Song", "Versioned Song (Live)", "Versioned Song - Remastered", "Versioned Song (Instrumental)")
        val uniqueNames = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Gamma", "Hotel",
            "India", "Juliet", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa")
        val songs = versions + uniqueNames.mapIndexed { index, name -> "$name Song ${index + 1}" }
        repeat(30) { seed ->
            val result = SmartShuffle.shuffle(songs, { it }, Random(seed))
            val versionPositions = result.mapIndexedNotNull { index, title -> index.takeIf { title.startsWith("Versioned Song") } }
            for (i in versionPositions.indices) {
                for (j in i + 1 until versionPositions.size) {
                    assertTrue("seed=$seed positions=${versionPositions[i]},${versionPositions[j]}",
                        versionPositions[j] - versionPositions[i] > SmartShuffle.DEFAULT_MIN_GAP)
                }
            }
        }

        val fillerNames = listOf(
            "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Gamma", "Hotel", "India", "Juliet",
            "Kilo", "Lima", "Mike", "November", "Oscar", "Papa", "Quebec", "Romeo", "Sierra", "Tango",
        )
        val twoGroups = listOf(
            "First Tune", "First Tune (Live)", "First Tune - Remastered",
            "Second Tune", "Second Tune (Live)", "Second Tune - Remastered",
        ) + fillerNames.mapIndexed { index, name -> "$name Song ${index + 1}" }
        repeat(30) { seed ->
            val result = SmartShuffle.shuffle(twoGroups, { it }, Random(100 + seed))
            listOf("First Tune", "Second Tune").forEach { prefix ->
                val positions = result.mapIndexedNotNull { index, title ->
                    index.takeIf { title.startsWith(prefix) }
                }
                for (i in positions.indices) {
                    for (j in i + 1 until positions.size) {
                        assertTrue("seed=${100 + seed} $prefix positions=${positions[i]},${positions[j]}",
                            positions[j] - positions[i] > SmartShuffle.DEFAULT_MIN_GAP)
                    }
                }
            }
        }
    }

    @Test
    fun separatesSeveralTightGroupsAcrossConfiguredGap() {
        val groups = mapOf(
            "Amber Tune" to 4,
            "Birch Tune" to 3,
            "Cedar Tune" to 3,
        )
        val versions = groups.flatMap { (title, count) ->
            List(count) { index -> if (index == 0) title else "$title (Version $index)" }
        }
        val fillerNames = listOf(
            "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Gamma", "Hotel", "India", "Juliet",
            "Kilo", "Lima", "Mike", "November", "Oscar", "Papa", "Quebec", "Romeo", "Sierra", "Tango",
            "Uniform", "Victor", "Whiskey", "Xray", "Yankee", "Zulu", "Copper", "Silver", "Golden", "Purple",
        )
        val songs = versions + fillerNames.mapIndexed { index, name -> "$name Track ${index + 1}" }

        repeat(50) { seed ->
            val result = SmartShuffle.shuffle(songs, { it }, Random(seed), minGap = 5)
            groups.keys.forEach { group ->
                val positions = result.mapIndexedNotNull { index, title ->
                    index.takeIf { title.startsWith(group) }
                }
                for (i in positions.indices) {
                    for (j in i + 1 until positions.size) {
                        assertTrue("seed=$seed $group positions=${positions[i]},${positions[j]}",
                            positions[j] - positions[i] > 5)
                    }
                }
            }
        }
    }

    @Test
    fun titleKeysIgnoreSuffixPunctuationAndCaseAndSupportUnicode() {
        assertEquals(SmartShuffle.titleKey("Song (Live)"), SmartShuffle.titleKey("Song - Remastered"))
        assertTrue(SmartShuffle.titleKey("Different Song") != SmartShuffle.titleKey("Song (Live)"))
        assertEquals(SmartShuffle.titleKey("A-B_C"), SmartShuffle.titleKey("abc"))
        assertEquals("花火", SmartShuffle.titleKey("花火 (Live)"))
        assertEquals(SmartShuffle.titleKey("花火 (Live)"), SmartShuffle.titleKey("花火 - 再録"))
        assertEquals("song", SmartShuffle.titleKey("Song (Live)"))
        assertEquals(SmartShuffle.titleKey("Song (Live)"), SmartShuffle.titleKey("Song - Remastered"))
        assertEquals(SmartShuffle.titleKey("カタカナ (Live)"), SmartShuffle.titleKey("カタカナ - Remix"))
        assertEquals(SmartShuffle.titleKey("Песня (Live)"), SmartShuffle.titleKey("Песня - Remaster"))
    }

    @Test
    fun impossibleSpacingStillReturnsPermutation() {
        val songs = List(10) { "Same Song version $it" } + listOf("Other One", "Other Two")
        val shuffled = SmartShuffle.shuffle(songs, { it }, Random(42))
        assertEquals(songs.groupingBy { it }.eachCount(), shuffled.groupingBy { it }.eachCount())
    }

    @Test
    fun pinnedFirstItemStaysFirstAndCountsTowardGap() {
        val songs = listOf("Versioned Song (Live)", "Versioned Song", "Alpha01", "Bravo02", "Charlie03", "Delta04", "Echo05", "Foxtrot06")
        val shuffled = SmartShuffle.shuffle(songs, { it }, Random(7), pinFirst = true)
        assertEquals(songs.first(), shuffled.first())
        val secondVersion = shuffled.indexOf("Versioned Song")
        assertTrue(secondVersion > SmartShuffle.DEFAULT_MIN_GAP)
    }
}
