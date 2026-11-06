package com.studysync.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class LanguageResourcesTest {
    private fun strings(qualifier: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        // Check every values XML file, including feature-specific string files.
        val files = requireNotNull(File("src/main/res/$qualifier").listFiles { _, name -> name.endsWith(".xml") })
        for (file in files.sortedBy { it.name }) {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = document.documentElement.childNodes
            for (index in 0 until nodes.length) {
                val node = nodes.item(index) as? Element ?: continue
                if (node.tagName !in setOf("string", "plurals")) continue
                if (node.getAttribute("translatable") == "false") continue
                val name = node.getAttribute("name")
                if (node.tagName == "plurals") {
                    val items = node.getElementsByTagName("item")
                    for (itemIndex in 0 until items.length) {
                        val item = items.item(itemIndex) as Element
                        val key = "$name/${item.getAttribute("quantity")}"
                        assertFalse("Duplicate resource: $key", result.containsKey(key))
                        assertTrue("Empty resource: $key", item.textContent.isNotBlank())
                        result[key] = item.textContent
                    }
                } else {
                    assertFalse("Duplicate resource: $name", result.containsKey(name))
                    assertTrue("Empty resource: $name", node.textContent.isNotBlank())
                    result[name] = node.textContent
                }
            }
        }
        return result
    }

    @Test
    fun everyEnglishResourceHasAnIsiZuluTranslation() {
        val english = strings("values")
        val zulu = strings("values-zu")
        assertTrue("The translation inventory should not be empty", english.isNotEmpty())
        assertEquals(english.keys, zulu.keys)
        for (name in english.keys) {
            val words = english.getValue(name).replace(Regex("%[0-9]+\\$[ds]"), "")
            if (words.any { it.isLetter() }) {
                assertNotEquals("Untranslated resource: $name", english[name], zulu[name])
            }
        }
    }

    @Test
    fun translatedFormatArgumentsMatchEnglish() {
        val english = strings("values")
        val zulu = strings("values-zu")
        val placeholders = Regex("%(\\d+\\$)?[ds]")
        for ((name, value) in english) {
            assertEquals(
                "Format arguments differ for $name",
                placeholders.findAll(value).map { it.value }.toList().sorted(),
                placeholders.findAll(zulu.getValue(name)).map { it.value }.toList().sorted()
            )
        }
    }
}
