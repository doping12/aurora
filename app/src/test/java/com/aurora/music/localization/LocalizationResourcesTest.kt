package com.aurora.music.localization

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationResourcesTest {
    @Test fun japaneseResourcesAreCompleteAndHaveUniqueNames() {
        data class Resources(
            val strings: Map<String, String>,
            val plurals: Map<String, Map<String, String>>,
        ) {
            val names get() = strings.keys + plurals.keys
        }

        fun resources(directory: String): Resources {
            val strings = mutableMapOf<String, String>()
            val plurals = mutableMapOf<String, Map<String, String>>()
            val names = mutableSetOf<String>()
            File("src/main/res/$directory").listFiles()!!.filter { it.extension == "xml" }.forEach { file ->
                val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                val stringNodes = document.getElementsByTagName("string")
                for (index in 0 until stringNodes.length) {
                    val node = stringNodes.item(index) as Element
                    val name = node.getAttribute("name")
                    assertTrue("Duplicate $directory/$name", names.add(name))
                    strings[name] = node.textContent
                }
                val pluralNodes = document.getElementsByTagName("plurals")
                for (index in 0 until pluralNodes.length) {
                    val node = pluralNodes.item(index) as Element
                    val name = node.getAttribute("name")
                    assertTrue("Duplicate $directory/$name", names.add(name))
                    val items = node.getElementsByTagName("item")
                    val quantities = mutableMapOf<String, String>()
                    for (itemIndex in 0 until items.length) {
                        val item = items.item(itemIndex) as Element
                        quantities[item.getAttribute("quantity")] = item.textContent
                    }
                    plurals[name] = quantities
                }
            }
            return Resources(strings, plurals)
        }

        val english = resources("values")
        val japanese = resources("values-ja")
        assertEquals(english.names - "app_name", japanese.names)
        val format = Regex("(?<!%)%(?:[0-9]+\\$)?[-+0-9.]*[sdf]")
        japanese.strings.forEach { (name, text) ->
            assertEquals(name, format.findAll(english.strings.getValue(name)).map { it.value }.sorted().toList(),
                format.findAll(text).map { it.value }.sorted().toList())
        }
        english.plurals.forEach { (name, englishItems) ->
            val englishOther = englishItems["other"]
            assertNotNull("English plurals/$name has no other item", englishOther)
            val japaneseItems = japanese.plurals.getValue(name)
            assertTrue("Japanese plurals/$name has no other item", japaneseItems.containsKey("other"))
            japaneseItems.forEach { (quantity, text) ->
                assertEquals("$name/$quantity", format.findAll(englishOther!!).map { it.value }.sorted().toList(),
                    format.findAll(text).map { it.value }.sorted().toList())
            }
        }
    }
}
