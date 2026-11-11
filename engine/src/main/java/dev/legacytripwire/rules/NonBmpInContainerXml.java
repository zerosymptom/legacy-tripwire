package dev.legacytripwire.rules;

import dev.legacytripwire.Files2;
import dev.legacytripwire.Finding;
import dev.legacytripwire.Rule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * LT-P01 — the container's XML parser only speaks 3-byte UTF-8; one emoji and the app never starts.
 *
 * <p>Symptom: any character above U+FFFF in the deployment descriptor (first byte {@code 0xF0},
 * i.e. most emoji) produces {@code CharConversionException: illegal utf8 encoding at 0xf0}.
 * The webapp never finishes configuring, <b>every</b> path returns 500 — including
 * {@code /favicon.ico} and paths that do not exist — and the application logs <b>nothing at all</b>,
 * because the logging framework never got a chance to initialise.
 *
 * <p>Why it is so hard to find: the build succeeds, the image pushes, the container reports Up,
 * every class and resource is present in the artifact, and the full local test suite is green.
 * Nothing points at the file. One real incident took three hours to locate.
 *
 * <p>Safe to use: symbols inside the BMP such as U+26D4, U+26A0, U+2605 — and all CJK text.
 *
 * <p>Scope: only the descriptor the container parses itself. XML loaded by frameworks is fine.
 */
public final class NonBmpInContainerXml implements Rule {

    @Override
    public String id() {
        return "LT-P01";
    }

    @Override
    public String title() {
        return "A character above U+FFFF in the deployment descriptor stops the whole webapp";
    }

    @Override
    public String scope(Path root) {
        return descriptors(root).size() + " deployment descriptors";
    }

    private static List<Path> descriptors(Path root) {
        return Files2.walk(root, p ->
                Files2.nameIs(p, "web.xml") || Files2.under(root, p, "WEB-INF")
                        && p.getFileName().toString().endsWith(".xml"));
    }

    @Override
    public List<Finding> check(Path root) {
        List<Path> descriptors = descriptors(root);
        List<Finding> out = new ArrayList<>();
        for (Path f : descriptors) {
            out.addAll(scan(f));
        }
        return out;
    }

    private List<Finding> scan(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of(Finding.unknown(id(), file,
                    "could not read as UTF-8 (" + e.getClass().getSimpleName()
                            + ") — cannot determine whether it contains characters above U+FFFF"));
        }
        List<Finding> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            List<String> bad = new ArrayList<>();
            line.codePoints().filter(cp -> cp > 0xFFFF)
                    .forEach(cp -> bad.add(String.format("U+%04X %s", cp, new String(Character.toChars(cp)))));
            if (!bad.isEmpty()) {
                out.add(Finding.tripped(id(), file, i + 1,
                        "character(s) above U+FFFF: " + String.join(", ", bad)
                                + " — a container whose parser only accepts 3-byte UTF-8 will fail to"
                                + " configure this webapp; every path returns 500 and nothing is logged"));
            }
        }
        return out;
    }
}
