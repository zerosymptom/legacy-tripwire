package dev.legacytripwire;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Just enough Java source handling for the rules that read {@code .java} files.
 *
 * <p>This is not a parser. It masks strings and comments so that brace counting and pattern
 * matching cannot be thrown off by a {@code "}"} inside a message — which is how a checker ends
 * up confidently wrong, shifting every method boundary after the first quoted brace.
 */
public final class JavaSource {

    private JavaSource() {
    }

    public static List<Path> files(Path root) {
        return Files2.walk(root, p -> p.getFileName().toString().endsWith(".java"));
    }

    /**
     * Read as UTF-8, or {@code null}.
     *
     * <p>⛔ Callers must surface {@code null} as {@link Verdict#UNKNOWN}, never skip it. A legacy
     * tree routinely holds GBK sources, and a rule that drops them quietly fails open.
     */
    public static String read(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Index of the {@code }} matching the {@code {} at {@code open}, or -1 if unbalanced. */
    public static int matchBrace(String masked, int open) {
        int depth = 0;
        for (int i = open; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    public static int lineOf(String src, int offset) {
        int n = 1;
        for (int i = 0; i < offset && i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** Replace string literals, char literals and comments with spaces, preserving offsets. */
    public static String mask(String src) {
        char[] out = src.toCharArray();
        int i = 0;
        int n = out.length;
        while (i < n) {
            char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                for (int k = 0; k < 2 && i < n; k++) {
                    out[i++] = ' ';
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out[i++] = ' ';
                while (i < n && out[i] != quote) {
                    if (out[i] == '\\' && i + 1 < n) {
                        out[i] = ' ';
                        i++;
                    }
                    if (i < n && out[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    out[i++] = ' ';
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /**
     * Enclosing method body of {@code offset}, as [openBrace, closeBrace], or null.
     *
     * <p>Walks outward from the nearest enclosing brace pair, so a statement inside an
     * {@code if} still resolves to the whole method rather than the {@code if} block.
     */
    public static int[] enclosingMethod(String masked, int offset) {
        int best = -1;
        int depth = 0;
        for (int i = offset; i >= 0; i--) {
            char c = masked.charAt(i);
            if (c == '}') {
                depth++;
            } else if (c == '{') {
                if (depth == 0) {
                    best = i;
                } else {
                    depth--;
                }
            }
            if (best >= 0 && looksLikeMethodHeader(masked, best)) {
                int close = matchBrace(masked, best);
                return close < 0 ? null : new int[] {best, close};
            }
        }
        return null;
    }

    /** A {@code (} before the brace with no {@code ;} between is a method or constructor header. */
    private static boolean looksLikeMethodHeader(String masked, int brace) {
        for (int i = brace - 1; i >= 0 && brace - i < 400; i--) {
            char c = masked.charAt(i);
            if (c == ')') {
                return true;
            }
            if (c == ';' || c == '{' || c == '}') {
                return false;
            }
        }
        return false;
    }
}
