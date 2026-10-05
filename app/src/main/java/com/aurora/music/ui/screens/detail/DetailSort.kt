package com.aurora.music.ui.screens.detail

import com.aurora.music.model.Song
import com.aurora.music.util.compareNameOrder

enum class DetailSort {
    ORIGINAL,
    NAME,
    RELEASE_DATE,
    ARTIST,
    ALBUM,
    DATE_ADDED,
}

/** Sorts a detail collection while retaining backend order for equal keys. */
fun sortDetailTracks(tracks: List<Song>, sort: DetailSort, descending: Boolean): List<Song> {
    if (sort == DetailSort.ORIGINAL) return if (descending) tracks.reversed() else tracks.toList()

    val comparator = Comparator<Song> { left, right ->
        when (sort) {
            DetailSort.NAME -> directionalNameCompare(left.title, right.title, descending)
            DetailSort.RELEASE_DATE -> directionalKnownLastCompare(left.releaseYear, right.releaseYear, descending)
            DetailSort.ARTIST -> {
                directionalNameCompare(left.artist, right.artist, descending)
                    .takeIf { it != 0 }
                    ?: directionalNameCompare(left.title, right.title, descending)
            }
            DetailSort.ALBUM -> directionalNameCompare(left.album, right.album, descending)
            DetailSort.DATE_ADDED -> directionalKnownLastCompare(left.dateAddedSec, right.dateAddedSec, descending)
            DetailSort.ORIGINAL -> 0
        }
    }
    return tracks.sortedWith(comparator)
}

private fun directionalNameCompare(left: String, right: String, descending: Boolean): Int {
    val leftBlank = left.trim().isEmpty()
    val rightBlank = right.trim().isEmpty()
    if (leftBlank || rightBlank) {
        return when {
            leftBlank && rightBlank -> 0
            leftBlank -> 1
            else -> -1
        }
    }
    val comparison = compareNameOrder(left, right)
    return if (descending) -comparison else comparison
}

private fun <T : Comparable<T>> directionalKnownLastCompare(left: T, right: T, descending: Boolean): Int {
    val leftUnknown = isUnknownValue(left)
    val rightUnknown = isUnknownValue(right)
    if (leftUnknown || rightUnknown) {
        return when {
            leftUnknown && rightUnknown -> 0
            leftUnknown -> 1
            else -> -1
        }
    }
    val comparison = left.compareTo(right)
    return if (descending) -comparison else comparison
}

private fun <T : Comparable<T>> isUnknownValue(value: T): Boolean = when (value) {
    is Int -> value == 0
    is Long -> value == 0L
    else -> false
}
