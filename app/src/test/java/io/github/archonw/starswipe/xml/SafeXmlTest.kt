package io.github.archonw.starswipe.xml

import org.junit.Assert.assertEquals
import org.junit.Test
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/** Android-Parser werfen bei setFeature(...) für unbekannte Features eine Ausnahme. */
class StrictFactory : DocumentBuilderFactory() {
    private val inner = DocumentBuilderFactory.newInstance()
    override fun newDocumentBuilder(): DocumentBuilder { inner.isNamespaceAware = isNamespaceAware; return inner.newDocumentBuilder() }
    override fun setAttribute(name: String?, value: Any?) = throw IllegalArgumentException(name)
    override fun getAttribute(name: String?) = throw IllegalArgumentException(name)
    override fun setFeature(name: String?, value: Boolean) = throw ParserConfigurationException(name)
    override fun getFeature(name: String?) = throw ParserConfigurationException(name)
}

class SafeXmlTest {
    @Test fun parsesWhenFeaturesUnsupported() {
        val doc = SafeXml.parse("""<a xmlns="urn:x"><b/></a>""", StrictFactory())
        assertEquals("urn:x", doc.documentElement.namespaceURI)
    }
}
