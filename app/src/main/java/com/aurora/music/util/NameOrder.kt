package com.aurora.music.util

import java.text.Normalizer
import java.util.Locale

/** Comparator used for the user-facing alphabetical order of music names. */
val nameOrderComparator: Comparator<String> = Comparator { left, right ->
    compareNameOrder(left, right)
}

fun compareNameOrder(left: String, right: String): Int {
    val a = normalizeName(left)
    val b = normalizeName(right)
    if (a.isEmpty() || b.isEmpty()) {
        return when {
            a.isEmpty() && b.isEmpty() -> 0
            a.isEmpty() -> 1
            else -> -1
        }
    }

    val groupComparison = nameGroup(a).compareTo(nameGroup(b))
    return if (groupComparison != 0) groupComparison else a.compareTo(b)
}

private fun normalizeName(value: String): String {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trimStart()
    return buildString(normalized.length) {
        var index = 0
        while (index < normalized.length) {
            val codePoint = normalized.codePointAt(index)
            appendCodePoint(if (codePoint in 0x30A1..0x30F6) codePoint - 0x60 else codePoint)
            index += Character.charCount(codePoint)
        }
    }.lowercase(Locale.ROOT)
}

private fun nameGroup(value: String): Int {
    val codePoint = value.codePointAt(0)
    return when {
        codePoint in 0x30A0..0x30FF || codePoint in 0x3040..0x309F -> 3 // kana
        Character.isDigit(codePoint) -> 1
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN -> 2
        isPunctuationOrSymbol(codePoint) -> 0
        Character.isLetter(codePoint) -> 4 // kanji and other scripts
        else -> 0
    }
}

private fun isPunctuationOrSymbol(codePoint: Int): Boolean {
    return when (Character.getType(codePoint)) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        Character.MATH_SYMBOL.toInt(),
        Character.CURRENCY_SYMBOL.toInt(),
        Character.MODIFIER_SYMBOL.toInt(),
        Character.OTHER_SYMBOL.toInt() -> true
        else -> false
    }
}
