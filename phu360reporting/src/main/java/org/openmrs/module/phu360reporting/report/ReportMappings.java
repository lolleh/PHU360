package org.openmrs.module.phu360reporting.report;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The data mapping of every REPORT TYPE, read from {@code module/reportmappings/}
 * in the omod.
 *
 * <p>One file per report type, and an index listing them in the order the
 * dashboard's filter shows them. The mappings are data rather than code so that
 * adding an encounter type to a report - or giving one of the HF summaries a
 * definition - is an edit to a file in this repository, not a change to a Java
 * array that has to be recompiled before anyone can see it.
 *
 * <p>XML because the module is already built around it ({@code config.xml},
 * {@code liquibase.xml}) and the JDK parses it with nothing added to the class
 * path. The alternative, a JSON parser, would be a dependency the module
 * otherwise does without.
 *
 * <p>A file that is missing or malformed is an error, not a skipped report: half
 * a catalog would show up as reports quietly missing from the filter, and a
 * report whose mapping failed to load has to be visible at startup rather than
 * discovered on a dashboard.
 */
public final class ReportMappings {

    private static final String ROOT = "reportmappings/";

    /**
     * The only shape of table name a mapping may declare. Enforced rather than
     * trusted because the table name is interpolated into SQL, and the SQL is
     * only safe because the report key arriving in a request is looked up in a
     * map rather than used as an identifier - a mapping file must not be a way
     * around that.
     */
    private static final String TABLE_PREFIX = "phu360_report_";

    private ReportMappings() {
    }

    /** Every configured report, in the order {@code index.xml} lists them. */
    static List<ReportCatalog.Report> load() {
        Map<String, ReportCatalog.Report> byKey = new LinkedHashMap<String, ReportCatalog.Report>();
        for (String key : readIndex()) {
            ReportCatalog.Report report = loadOne(key);
            if (byKey.put(key, report) != null) {
                throw new IllegalStateException("Report mapping listed twice in index.xml: " + key);
            }
        }
        return new ArrayList<ReportCatalog.Report>(byKey.values());
    }

    private static List<String> readIndex() {
        Document doc = parse("index.xml", "reportCatalog");
        List<String> keys = new ArrayList<String>();
        for (Element e : children(doc.getDocumentElement(), "report")) {
            keys.add(e.getTextContent().trim());
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException("reportmappings/index.xml lists no reports");
        }
        return keys;
    }

    private static ReportCatalog.Report loadOne(String key) {
        Document doc = parse(key + ".xml", "reportMapping");
        Element root = doc.getDocumentElement();
        // The filename and index are what decide this report's key, so a <key>
        // that disagrees with them is a file that would report one thing and
        // filter as another.
        String declared = requiredText(root, "key", key);
        if (!declared.equals(key)) {
            throw new IllegalStateException("Report mapping " + key + ".xml declares <key>" + declared
                    + "</key>, but it is loaded as " + key + " because of its name and index.xml");
        }
        String label = requiredText(root, "label", key);
        String table = requiredText(root, "table", key);
        if (!table.startsWith(TABLE_PREFIX)) {
            throw new IllegalStateException("Report mapping " + key + " declares table " + table
                    + ", which is not one of this module's " + TABLE_PREFIX + "* tables");
        }
        List<String> types = new ArrayList<String>();
        for (Element e : children(root, "encounterTypes", "encounterType")) {
            types.add(e.getTextContent().trim());
        }
        List<Integer> concepts = new ArrayList<Integer>();
        for (Element e : children(root, "obsConcepts", "obsConcept")) {
            try {
                concepts.add(Integer.valueOf(e.getTextContent().trim()));
            } catch (NumberFormatException nfe) {
                throw new IllegalStateException("Report mapping " + key + " has a non-numeric obs concept id: "
                        + e.getTextContent().trim());
            }
        }
        int obsValue = intText(root, "obsValue", key);
        return new ReportCatalog.Report(key, label, boolText(root, "mapped"), types, concepts, obsValue, table);
    }

    private static Document parse(String file, String rootElement) {
        InputStream in = ReportMappings.class.getClassLoader().getResourceAsStream(ROOT + file);
        if (in == null) {
            throw new IllegalStateException("Report mapping not found on the module classpath: " + ROOT + file);
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // The files ship in the omod, but a report mapping is not worth
            // reaching the filesystem or the network over if a copy were ever
            // substituted for it.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(in);
            if (!rootElement.equals(doc.getDocumentElement().getNodeName())) {
                throw new IllegalStateException(ROOT + file + " should have a <" + rootElement + "> root element, not a <"
                        + doc.getDocumentElement().getNodeName() + ">");
            }
            return doc;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Report mapping " + ROOT + file + " could not be read: " + e.getMessage(), e);
        } finally {
            close(in);
        }
    }

    private static String requiredText(Element root, String tag, String key) {
        String value = text(root, tag);
        if (value.length() == 0) {
            throw new IllegalStateException("Report mapping " + key + " has no <" + tag + ">");
        }
        return value;
    }

    private static String text(Element root, String tag) {
        List<Element> found = children(root, tag);
        return found.isEmpty() ? "" : found.get(0).getTextContent().trim();
    }

    private static int intText(Element root, String tag, String key) {
        String value = text(root, tag);
        if (value.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException nfe) {
            throw new IllegalStateException("Report mapping " + key + " has a non-numeric <" + tag + ">: " + value);
        }
    }

    /** A boolean element, defaulting to false when absent. */
    private static boolean boolText(Element root, String tag) {
        return "true".equalsIgnoreCase(text(root, tag));
    }

    /** The named child elements of {@code parent}, optionally narrowed to one further tag. */
    private static List<Element> children(Element parent, String tag) {
        return children(parent, tag, null);
    }

    private static List<Element> children(Element parent, String tag, String childTag) {
        List<Element> out = new ArrayList<Element>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE || !tag.equals(node.getNodeName())) {
                continue;
            }
            if (childTag == null) {
                out.add((Element) node);
            } else {
                out.addAll(children((Element) node, childTag));
            }
        }
        return out;
    }

    private static void close(InputStream in) {
        try {
            in.close();
        } catch (java.io.IOException e) {
            // Nothing useful to do; the mapping either loaded or did not.
        }
    }
}
