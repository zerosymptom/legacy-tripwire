package dev.legacytripwire;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Small filesystem helpers. Nothing clever — kept here so the rules stay readable. */
public final class Files2 {

    /** Directories never worth walking into. Keeps scans fast and findings relevant. */
    private static final Set<String> SKIP = Set.of(
            ".git", ".svn", "target", "build", "out", "dist", "node_modules", ".idea", ".gradle");

    private Files2() {
    }

    /** Name of the opt-out file. Read from the scan root; see {@link #ignores(Path)}. */
    public static final String IGNORE_FILE = ".tripwireignore";

    public static List<Path> walk(Path root, Predicate<Path> keep) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<String> ignores = ignores(root);
        try (Stream<Path> s = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) {
            return s.filter(p -> !isSkipped(root, p))
                    .filter(p -> !isIgnored(root, p, ignores))
                    .filter(Files::isRegularFile)
                    .filter(keep)
                    .sorted()
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
    }

    /**
     * Path prefixes the scan skips, one per line, relative to the root. {@code #} starts a comment.
     *
     * <p>Real projects contain deliberately broken samples — test fixtures, vendored copies,
     * archived code. Without a way to say so, those produce findings nobody will ever act on,
     * and a tool whose output is routinely ignored stops being read at all.
     *
     * <p>⛔ The exclusions are <b>printed on every run</b>. Silently dropping files is exactly
     * the shape of failure this catalogue is about; if something is skipped, it has to be visible.
     */
    public static List<String> ignores(Path root) {
        Path f = root.resolve(IGNORE_FILE);
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        try {
            return Files.readAllLines(f).stream()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .map(l -> l.endsWith("/") ? l.substring(0, l.length() - 1) : l)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean isIgnored(Path root, Path p, List<String> ignores) {
        if (ignores.isEmpty()) {
            return false;
        }
        String rel = root.relativize(p).toString().replace('\\', '/');
        for (String prefix : ignores) {
            if (rel.equals(prefix) || rel.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSkipped(Path root, Path p) {
        for (Path part : root.relativize(p)) {
            if (SKIP.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    public static boolean nameIs(Path p, String name) {
        return p.getFileName() != null && p.getFileName().toString().equals(name);
    }

    public static boolean under(Path root, Path p, String dirName) {
        for (Path part : root.relativize(p)) {
            if (part.toString().equals(dirName)) {
                return true;
            }
        }
        return false;
    }
}
