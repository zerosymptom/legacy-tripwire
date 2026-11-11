package dev.legacytripwire.rules;

import dev.legacytripwire.Files2;
import dev.legacytripwire.Finding;
import dev.legacytripwire.Rule;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LT-S01 — a literal "skip tests" in the build config outranks the command line.
 *
 * <p>Symptom: {@code mvn test} prints BUILD SUCCESS, runs <b>0</b> tests, writes no reports.
 *
 * <p>Check: a <b>literal</b> {@code <skipTests>true</skipTests>} or {@code <skip>true</skip>}
 * inside the test plugin's configuration.
 *
 * <p>False-positive boundary, straight from the catalogue: a property reference such as
 * {@code <skipTests>${skipTests}</skipTests>} is <b>not</b> this rule — that one can still be
 * overridden by {@code -DskipTests=false}. The line is <b>literal vs property reference</b>,
 * not "is it in {@code <properties>}". Defaults declared in {@code <properties>} are therefore
 * ignored here on purpose.
 *
 * <p>Inside a {@code <profile>} the answer is UNKNOWN: whether that profile activates cannot be
 * decided from the file alone, and guessing "clear" is exactly the failure this project is about.
 */
public final class SkipTestsLiteral implements Rule {

    private static final List<String> TEST_PLUGINS =
            List.of("maven-surefire-plugin", "maven-failsafe-plugin");

    @Override
    public String id() {
        return "LT-S01";
    }

    @Override
    public String title() {
        return "A literal skip-tests in the build config outranks -DskipTests=false";
    }

    @Override
    public String scope(Path root) {
        return poms(root).size() + " pom.xml";
    }

    private static List<Path> poms(Path root) {
        return Files2.walk(root, p -> p.getFileName().toString().equals("pom.xml"));
    }

    @Override
    public List<Finding> check(Path root) {
        List<Finding> out = new ArrayList<>();
        for (Path pom : Files2.walk(root, p -> p.getFileName().toString().equals("pom.xml"))) {
            out.addAll(checkOne(pom));
        }
        return out;
    }

    private List<Finding> checkOne(Path pom) {
        Document doc;
        try {
            doc = builder().parse(pom.toFile());
        } catch (Exception e) {
            // Unreadable or malformed POM: we genuinely do not know. Say so.
            return List.of(Finding.unknown(id(), pom,
                    "could not parse this pom (" + e.getClass().getSimpleName()
                            + "), so the skip-tests state is undetermined"));
        }
        List<Finding> out = new ArrayList<>();
        for (String tag : List.of("skipTests", "skip")) {
            NodeList nodes = doc.getElementsByTagName(tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                Element el = (Element) nodes.item(i);
                String text = el.getTextContent() == null ? "" : el.getTextContent().trim();
                if (!text.equalsIgnoreCase("true")) {
                    continue;                       // property reference or false → not this rule
                }
                if (ancestor(el, "properties") != null) {
                    continue;                       // a default, overridable by -D → not this rule
                }
                Element plugin = ancestor(el, "plugin");
                if (plugin == null || !isTestPlugin(plugin)) {
                    continue;                       // <skip> belongs to some other plugin
                }
                String artifact = childText(plugin, "artifactId");
                if (ancestor(el, "profile") != null) {
                    out.add(Finding.unknown(id(), pom,
                            "<" + tag + ">true</" + tag + "> in " + artifact
                                    + " sits inside a <profile>; whether it activates cannot be decided"
                                    + " from this file — resolve the effective POM before trusting a test run"));
                } else {
                    out.add(Finding.tripped(id(), pom, 0,
                            "<" + tag + ">true</" + tag + "> is hard-coded in " + artifact
                                    + " — `mvn test` will report success having run 0 tests,"
                                    + " and -DskipTests=false will not override it"));
                }
            }
        }
        return out;
    }

    private static boolean isTestPlugin(Element plugin) {
        String artifact = childText(plugin, "artifactId").toLowerCase(Locale.ROOT);
        return TEST_PLUGINS.contains(artifact);
    }

    private static String childText(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(tag)) {
                return n.getTextContent() == null ? "" : n.getTextContent().trim();
            }
        }
        return "";
    }

    private static Element ancestor(Element el, String tag) {
        for (Node n = el.getParentNode(); n != null; n = n.getParentNode()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(tag)) {
                return (Element) n;
            }
        }
        return null;
    }

    private static DocumentBuilder builder() throws ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }
}
