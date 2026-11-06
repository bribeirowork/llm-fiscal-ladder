package dev.llmladder.fiscal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Extração determinística: o XML tem estrutura, então não há por que pagar um LLM para isso.
 * DOCTYPE desabilitado: XML de terceiro é entrada hostil (XXE).
 */
public final class NfeXmlParser {
    public static class InvalidXmlException extends RuntimeException {
        public InvalidXmlException(String message) {
            super(message);
        }
    }

    private NfeXmlParser() {}

    public static NfeDocument parse(String xml) {
        Document document = load(xml);
        Element infNFe = first(document, "infNFe");
        String key = infNFe != null ? infNFe.getAttribute("Id").replaceFirst("^NFe", "") : text(document, "chNFe");
        if (!AccessKey.isValid(key)) {
            throw new InvalidXmlException("chave de acesso ausente ou inválida");
        }
        return new NfeDocument(new AccessKey(key), text(document, "CNPJ"), text(document, "xNome"),
                text(document, "nNF"), text(document, "cStat"), text(document, "xMotivo"));
    }

    private static Document load(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new InvalidXmlException("XML malformado ou com DOCTYPE");
        }
    }

    private static Element first(Document document, String name) {
        NodeList nodes = document.getElementsByTagNameNS("*", name);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static String text(Document document, String name) {
        Element element = first(document, name);
        return element == null ? null : element.getTextContent().strip();
    }
}
