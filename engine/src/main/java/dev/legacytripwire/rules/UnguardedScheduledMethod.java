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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LT-S08 — a scheduled method that throws is removed from the schedule, permanently, in silence.
 *
 * <p>Symptom: someone notices a job "didn't run this month". It did not run last month either,
 * and it will not run again. There is no error in the logs, no alert, and no failed health check:
 * {@code ScheduledThreadPoolExecutor} cancels a periodic task the first time it throws, and says
 * nothing. The process is still up; every other job in the application keeps running.
 *
 * <p>Published criterion: <b>the outermost level of a scheduled entry method has no
 * {@code catch (Throwable)}</b>. Catching {@code Exception} is not enough — a
 * {@code NoClassDefFoundError} from a lazily-loaded class, or an {@code OutOfMemoryError} in one
 * oversized batch, cancels the task just as permanently.
 *
 * <p>The fix is a top-level {@code try/catch (Throwable)} that logs and ⛔ does not rethrow.
 *
 * <p>When the method body cannot be delimited with confidence — unbalanced braces after
 * stripping strings and comments — this reports {@link Finding#unknown} rather than guessing.
 * Guessing CLEAR here would reproduce the exact failure the rule is about.
 */
public final class UnguardedScheduledMethod implements Rule {

    /** Annotations that hand a method to a scheduler which cancels on throw. */
    private static final Pattern ENTRY = Pattern.compile(
            "@(Scheduled|Schedules|XxlJob|ElasticJob)\\b");

    private static final Pattern CATCH_THROWABLE = Pattern.compile(
            "catch\\s*\\(\\s*(final\\s+)?(java\\.lang\\.)?Throwable\\s");

    @Override
    public String id() {
        return "LT-S08";
    }

    @Override
    public String title() {
        return "A scheduled method that throws is dropped from the schedule for good, silently";
    }

    @Override
    public String scope(Path root) {
        int n = 0;
        int unreadable = 0;
        for (Path f : sources(root)) {
            String src = read(f);
            if (src == null) {
                unreadable++;
            } else {
                n += entries(src).size();
            }
        }
        // The unreadable count belongs on the scope line, not only in the findings: this line is
        // what someone reads to decide whether "no findings" means anything.
        return n + " scheduled methods"
                + (unreadable > 0 ? " (+" + unreadable + " source files not readable as UTF-8)" : "");
    }

    private static List<Path> sources(Path root) {
        return Files2.walk(root, p -> p.getFileName().toString().endsWith(".java"));
    }

    @Override
    public List<Finding> check(Path root) {
        List<Finding> out = new ArrayList<>();
        for (Path f : sources(root)) {
            String src = read(f);
            if (src == null) {
                // ⛔ Never skip quietly. A legacy tree routinely holds GBK/Shift-JIS sources, and
                // the ones this rule most wants are named *Task.java / *Schedule.java — exactly
                // the files most likely to predate the move to UTF-8. Dropping them would make
                // this checker fail open, which is LT-S14, which is in the catalogue above.
                out.add(Finding.unknown(id(), f,
                        "not readable as UTF-8 — cannot tell whether it contains an unguarded"
                                + " scheduled method; re-run after confirming the file's encoding"));
                continue;
            }
            for (int at : entries(src)) {
                out.addAll(inspect(f, src, at));
            }
        }
        return out;
    }

    private static String read(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Offsets of scheduling annotations, outside strings and comments. */
    private static List<Integer> entries(String src) {
        List<Integer> out = new ArrayList<>();
        if (src == null) {
            return out;
        }
        String masked = mask(src);
        Matcher m = ENTRY.matcher(masked);
        while (m.find()) {
            out.add(m.start());
        }
        return out;
    }

    private List<Finding> inspect(Path file, String src, int annotationAt) {
        String masked = mask(src);
        int open = masked.indexOf('{', annotationAt);
        if (open < 0) {
            return List.of(unknownAt(file, src, annotationAt, "no method body found after the annotation"));
        }
        // An abstract/interface declaration has a ';' before any '{'.
        int semi = masked.indexOf(';', annotationAt);
        if (semi >= 0 && semi < open) {
            return List.of();
        }
        int close = matching(masked, open);
        if (close < 0) {
            return List.of(unknownAt(file, src, annotationAt,
                    "method body braces do not balance — cannot tell whether it is guarded"));
        }
        String body = src.substring(open + 1, close);
        String bodyMasked = masked.substring(open + 1, close);

        if (bodyMasked.isBlank()) {
            return List.of();
        }
        if (CATCH_THROWABLE.matcher(bodyMasked).find() && wrapsWholeBody(bodyMasked)) {
            return List.of();
        }
        String detail = bodyMasked.contains("catch")
                ? "the outermost level catches something narrower than Throwable — an Error"
                + " (NoClassDefFoundError, OutOfMemoryError) still cancels this task permanently"
                : "no top-level try/catch (Throwable) — the first throw removes this task from the"
                + " scheduler for the life of the process, with nothing logged";
        return List.of(Finding.tripped(id(), file, lineOf(src, annotationAt),
                detail + "; method body starts at line " + lineOf(src, open)));
    }

    /**
     * True when the body is a single try statement spanning everything.
     *
     * <p>A {@code catch (Throwable)} around only the second half still leaves the first half able
     * to cancel the task, so the position matters, not just the presence.
     */
    private static boolean wrapsWholeBody(String bodyMasked) {
        String s = bodyMasked.strip();
        if (!s.startsWith("try")) {
            return false;
        }
        int open = s.indexOf('{');
        if (open < 0) {
            return false;
        }
        int close = matching(s, open);
        if (close < 0) {
            return false;
        }
        String tail = s.substring(close + 1);
        // Everything after the try block must be catch/finally clauses, not more statements.
        int lastBrace = tail.lastIndexOf('}');
        return lastBrace < 0 || tail.substring(lastBrace + 1).isBlank();
    }

    private Finding unknownAt(Path file, String src, int at, String why) {
        return Finding.unknown(id(), file, why + " (line " + lineOf(src, at) + ")");
    }

    private static int matching(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
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

    /**
     * Blank out string literals, char literals and comments, keeping offsets and newlines.
     *
     * <p>Brace counting over raw source is how a checker ends up confidently wrong: a {@code "}"}
     * inside a message string silently shifts every method boundary after it.
     */
    private static String mask(String src) {
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
                    if (out[i] != '\n') {
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
}
