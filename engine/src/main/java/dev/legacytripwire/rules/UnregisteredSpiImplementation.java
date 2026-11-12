package dev.legacytripwire.rules;

import dev.legacytripwire.Files2;
import dev.legacytripwire.Finding;
import dev.legacytripwire.Rule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LT-S12 — an SPI implementation nobody appended to the registry file is never loaded.
 *
 * <p>Symptom: a new extension point compiles, passes review, has tests, and is <b>never called</b>.
 * {@link java.util.ServiceLoader} finds implementations only through the plain-text file at
 * {@code META-INF/services/<interface fqn>}. Adding the class is a separate, forgettable edit in
 * a different source root, and nothing checks the two agree. There is no error at build time, no
 * error at startup, and no log line — the class simply does not exist as far as the loader cares.
 *
 * <p>The published criterion: a class implements an interface that has a registry file, and its
 * fully-qualified name is not listed in that file.
 *
 * <p>To stay off correct code, an implementation is only matched when the source either sits in
 * the interface's own package or imports it by name. A bare {@code implements Foo} elsewhere may
 * be a different {@code Foo}, and sending someone to "register" an unrelated class is worse than
 * missing one.
 *
 * <p>⚠️ Known miss: a class that inherits the interface through a base class is not detected.
 * Resolving that needs a type graph, and a half-resolved one would produce confident wrong
 * answers — which is the failure this catalogue documents.
 */
public final class UnregisteredSpiImplementation implements Rule {

    private static final Pattern DECL = Pattern.compile(
            "\\b(?:public\\s+|final\\s+|abstract\\s+|static\\s+)*"
                    + "(class|enum|record)\\s+(\\w+)\\b([^{;]*)");

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;",
            Pattern.MULTILINE);

    @Override
    public String id() {
        return "LT-S12";
    }

    @Override
    public String title() {
        return "An SPI implementation missing from META-INF/services is never loaded, silently";
    }

    @Override
    public String scope(Path root) {
        return registries(root).size() + " SPI registry files";
    }

    private static List<Path> registries(Path root) {
        return Files2.walk(root, p -> {
            Path parent = p.getParent();
            Path grand = parent == null ? null : parent.getParent();
            return parent != null && grand != null
                    && parent.getFileName().toString().equals("services")
                    && grand.getFileName().toString().equals("META-INF")
                    && p.getFileName().toString().contains(".");
        });
    }

    private static List<Path> sources(Path root) {
        return Files2.walk(root, p -> p.getFileName().toString().endsWith(".java"));
    }

    @Override
    public List<Finding> check(Path root) {
        List<Path> registries = registries(root);
        if (registries.isEmpty()) {
            return List.of();
        }
        List<Path> sources = sources(root);
        List<Finding> out = new ArrayList<>();
        for (Path registry : registries) {
            out.addAll(checkOne(root, registry, sources));
        }
        return out;
    }

    private List<Finding> checkOne(Path root, Path registry, List<Path> sources) {
        String iface = registry.getFileName().toString();
        int dot = iface.lastIndexOf('.');
        String simple = iface.substring(dot + 1);
        String pkg = iface.substring(0, dot);

        Set<String> listed;
        try {
            listed = new LinkedHashSet<>(Files.readAllLines(registry, StandardCharsets.UTF_8).stream()
                    .map(l -> l.replaceAll("#.*", "").trim())
                    .filter(l -> !l.isEmpty())
                    .toList());
        } catch (IOException e) {
            return List.of(Finding.unknown(id(), registry,
                    "could not read the registry (" + e.getClass().getSimpleName()
                            + ") — cannot tell which implementations are registered"));
        }

        List<Finding> out = new ArrayList<>();
        for (Path src : sources) {
            String text;
            try {
                text = Files.readString(src, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                // Same reason as LT-S08: a source this rule cannot read is a source it cannot
                // clear. Skipping quietly would make the checker fail open.
                out.add(Finding.unknown(id(), src,
                        "not readable as UTF-8 — cannot tell whether it implements " + simple
                                + " without being listed in " + root.relativize(registry)));
                continue;
            }
            String ownPackage = find(PACKAGE, text);
            boolean visible = pkg.equals(ownPackage)
                    || text.contains("import " + iface + ";")
                    || text.contains("import " + pkg + ".*;");
            if (!visible) {
                continue;
            }
            Matcher m = DECL.matcher(text);
            while (m.find()) {
                String type = m.group(2);
                String supers = m.group(3) == null ? "" : m.group(3);
                if (!mentions(supers, simple)) {
                    continue;
                }
                if (supers.contains("abstract ")) {
                    continue;
                }
                String fqn = ownPackage == null ? type : ownPackage + "." + type;
                if (!listed.contains(fqn)) {
                    out.add(Finding.tripped(id(), src, lineOf(text, m.start()),
                            fqn + " implements " + simple + " but is not listed in "
                                    + root.relativize(registry)
                                    + " — ServiceLoader will never instantiate it, and nothing"
                                    + " reports this at build time or at startup"));
                }
            }
        }
        return out;
    }

    private static boolean mentions(String supers, String simple) {
        if (!supers.contains("implements") && !supers.contains("extends")) {
            return false;
        }
        return Pattern.compile("\\b" + Pattern.quote(simple) + "\\b").matcher(supers).find();
    }

    private static String find(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static int lineOf(String src, int offset) {
        int n = 1;
        for (int i = 0; i < offset && i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }
}
