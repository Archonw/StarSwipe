package io.github.archonw.starswipe.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Jede Sprache hat dieselben Texte mit denselben Platzhaltern wie Englisch (Grundsprache). */
class TranslationsTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private data class Item(val name: String, val placeholders: Set<String>)

    private fun load(dir: String): Map<String, Item> {
        val f = File(res, "$dir/strings.xml")
        assertTrue("fehlt: $f", f.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val out = mutableMapOf<String, Item>()
        fun add(key: String, text: String) {
            out[key] = Item(key, Regex("%\\d+\\$[sd]").findAll(text).map { it.value }.toSet())
        }
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val e = strings.item(i) as org.w3c.dom.Element
            if (e.getAttribute("translatable") == "false") continue
            add(e.getAttribute("name"), e.textContent)
        }
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val e = plurals.item(i) as org.w3c.dom.Element
            val items = e.getElementsByTagName("item")
            val other = (0 until items.length).map { items.item(it) as org.w3c.dom.Element }.first { it.getAttribute("quantity") == "other" }
            add("plural:" + e.getAttribute("name"), other.textContent)
        }
        return out
    }

    private val base by lazy { load("values") }

    @Test fun englishHasTexts() = assertTrue(base.size > 50)
    @Test fun germanComplete() = compare("values-de")
    @Test fun spanishComplete() = compare("values-es")

    private fun compare(dir: String) {
        val other = load(dir)
        assertEquals("$dir: Schlüssel", base.keys.sorted(), other.keys.sorted())
        base.forEach { (k, v) -> assertEquals("$dir: Platzhalter in $k", v.placeholders, other[k]!!.placeholders) }
    }
}
