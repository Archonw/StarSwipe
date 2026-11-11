package io.github.archonw.starswipe.xml

import org.w3c.dom.Document
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Namespace-fähiger DOM-Parser. Schutz-Features werden gesetzt, wo der Parser sie kennt;
 * der Android-Parser lehnt sie ab (und lädt ohnehin keine externen Entities).
 */
object SafeXml {
    fun parse(xml: String, factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()): Document {
        factory.isNamespaceAware = true
        for (f in listOf(XMLConstants.FEATURE_SECURE_PROCESSING, "http://apache.org/xml/features/disallow-doctype-decl")) {
            try { factory.setFeature(f, true) } catch (_: Exception) {}
        }
        try { factory.isExpandEntityReferences = false } catch (_: Exception) {}
        return factory.newDocumentBuilder().apply { setErrorHandler(null) }
            .parse(InputSource(StringReader(xml.removePrefix("﻿"))))
    }
}
