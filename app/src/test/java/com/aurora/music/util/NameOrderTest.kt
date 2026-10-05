package com.aurora.music.util

import org.junit.Assert.assertEquals
import org.junit.Test

class NameOrderTest {
    @Test fun groupsSymbolsDigitsLatinKanaAndOtherScripts() {
        val names = listOf("漢字", "かな", "A", "2", "!", " ")
        assertEquals(listOf("!", "2", "A", "かな", "漢字", " "), names.sortedWith(nameOrderComparator))
    }

    @Test fun foldsKanaAndIgnoresCaseAndLeadingWhitespace() {
        assertEquals(0, compareNameOrder(" カタカナ", "かたかな"))
        assertEquals(0, compareNameOrder("title", "TITLE"))
    }
}
