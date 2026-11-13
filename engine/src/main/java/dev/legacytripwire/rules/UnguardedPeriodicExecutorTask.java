package dev.legacytripwire.rules;

import dev.legacytripwire.Files2;
import dev.legacytripwire.Finding;
import dev.legacytripwire.Rule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LT-S08 — a periodic task on a bare {@code ScheduledExecutorService} is cancelled, permanently
 * and silently, the first time it throws.
 *
 * <p>Symptom: someone notices a job "didn't run this month". It did not run last month either,
 * and it will not run again. There is no error in the logs, no alert, and no failed health check.
 * {@code ScheduledThreadPoolExecutor} documents this: <i>"If any execution of the task encounters
 * an exception, subsequent executions are suppressed."</i> The throwable is parked in the
 * {@code ScheduledFuture} that nobody kept, and the process stays up with every other task
 * running.
 *
 * <p><b>This rule was re-scoped on 2026-11-13.</b> It previously matched framework annotations
 * ({@code @Scheduled}, {@code @XxlJob}, {@code @ElasticJob}) and claimed the same consequence.
 * That was measured and is false: Spring wraps a repeating task through
 * {@code TaskUtils.decorateTaskWithErrorHandler(task, null, true)} with
 * {@code LOG_AND_SUPPRESS_ERROR_HANDLER}, so the throw is caught inside
 * {@code DelegatingErrorHandlingRunnable}, the task keeps running, and a stack trace is logged
 * every time. XXL-Job and ElasticJob likewise catch and report. The cancel-and-say-nothing
 * behaviour belongs to the JDK executor alone, so that is the only thing this check now matches.
 *
 * <p>Published criterion: a call to {@code scheduleAtFixedRate} or {@code scheduleWithFixedDelay}
 * <b>on a receiver this file declares as a JDK scheduled executor</b>, where the task body has no
 * outermost {@code catch (Throwable)}. Catching {@code Exception} is not enough — a
 * {@code NoClassDefFoundError} from a lazily loaded class suppresses the schedule just as
 * permanently.
 *
 * <p>False-positive boundary: Spring's {@code TaskScheduler} family exposes the same two method
 * names and is <em>not</em> affected, because Spring decorates the task. A receiver declared as
 * {@code TaskScheduler} / {@code ThreadPoolTaskScheduler} / {@code ConcurrentTaskScheduler} is
 * therefore skipped. A receiver this file does not declare at all is reported
 * {@link Finding#unknown} — it may be either, and guessing CLEAR would reproduce the exact
 * failure this catalogue is about.
 *
 * <p>⛔ Not covered: {@code java.util.Timer}. A {@code TimerTask} that throws kills the timer
 * thread and every other task on it — a worse variant of the same trap, and a separate rule.
 */
public final class UnguardedPeriodicExecutorTask implements Rule {

    /** Receivers whose periodic tasks are cancelled on throw. */
    private static final Pattern JDK_DECL = Pattern.compile(
            "\\b(?:ScheduledExecutorService|ScheduledThreadPoolExecutor)\\s+(\\w+)\\s*[=;,)]");

    /** {@code var pool = Executors.newScheduledThreadPool(...)} — the type is not written out. */
    private static final Pattern JDK_FACTORY = Pattern.compile(
            "(\\w+)\\s*=\\s*Executors\\s*\\.\\s*"
                    + "(?:newScheduledThreadPool|newSingleThreadScheduledExecutor)\\s*\\(");

    /** Spring's scheduler family: same method names, but the task is decorated, so it survives. */
    private static final Pattern SPRING_DECL = Pattern.compile(
            "\\b(?:TaskScheduler|ThreadPoolTaskScheduler|ConcurrentTaskScheduler"
                    + "|ThreadPoolTaskExecutor)\\s+(\\w+)\\s*[=;,)]");

    private static final Pattern PERIODIC = Pattern.compile(
            "\\b(\\w+)\\s*\\.\\s*(scheduleAtFixedRate|scheduleWithFixedDelay)\\s*\\(");

    private static final Pattern CATCH_THROWABLE = Pattern.compile(
            "catch\\s*\\(\\s*(final\\s+)?(java\\.lang\\.)?Throwable\\s");

    @Override
    public String id() {
        return "LT-S08";
    }

    @Override
    public String title() {
        return "A periodic task on a bare ScheduledExecutorService is cancelled on the first throw,"
                + " silently";
    }

    @Override
    public String scope(Path root) {
        int n = 0;
        int unreadable = 0;
        for (Path f : sources(root)) {
            String src = read(f);
            if (src == null) {
                unreadable++;
                continue;
            }
            Matcher m = PERIODIC.matcher(mask(src));
            while (m.find()) {
                n++;
            }
        }
        // This line is what someone reads to decide whether "no findings" means anything.
        return n + " periodic schedulings"
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
                // ⛔ Never skip quietly. Legacy trees hold GBK/Shift-JIS sources, and the files
                // this rule wants are named *Task.java / *Scheduler.java — the ones most likely
                // to predate the move to UTF-8. Dropping them would make this checker fail open.
                out.add(Finding.unknown(id(), f,
                        "not readable as UTF-8 — cannot tell whether it schedules an unguarded"
                                + " periodic task; re-run after confirming the file's encoding"));
                continue;
            }
            out.addAll(inspectFile(f, src));
        }
        return out;
    }

    private List<Finding> inspectFile(Path file, String src) {
        List<Finding> out = new ArrayList<>();
        String masked = mask(src);
        Set<String> jdk = names(masked, JDK_DECL);
        jdk.addAll(names(masked, JDK_FACTORY));
        Set<String> spring = names(masked, SPRING_DECL);

        Matcher m = PERIODIC.matcher(masked);
        while (m.find()) {
            String receiver = m.group(1);
            String method = m.group(2);
            int line = lineOf(src, m.start());

            if (spring.contains(receiver) && !jdk.contains(receiver)) {
                continue;
            }
            if (!jdk.contains(receiver)) {
                out.add(Finding.unknown(id(), file,
                        receiver + "." + method + "(...) at line " + line + " — this file does not"
                                + " declare " + receiver + ", so it cannot be told apart from a"
                                + " Spring TaskScheduler, which decorates the task and is not"
                                + " affected"));
                continue;
            }

            int openParen = masked.indexOf('(', m.end() - 1);
            int[] arg = firstArgument(masked, openParen);
            if (arg == null) {
                out.add(Finding.unknown(id(), file,
                        "could not delimit the task argument of " + receiver + "." + method
                                + "(...) at line " + line));
                continue;
            }
            Body body = taskBody(masked, arg[0], arg[1]);
            if (body == Body.UNRESOLVED) {
                out.add(Finding.unknown(id(), file,
                        "the task passed to " + receiver + "." + method + "(...) at line " + line
                                + " is not an inline lambda or Runnable and its method is not"
                                + " declared in this file — cannot tell whether it is guarded"));
                continue;
            }
            if (body.guarded) {
                continue;
            }
            out.add(Finding.tripped(id(), file, line,
                    body.detail + " — " + receiver + "." + method + "(...) is a JDK scheduled"
                            + " executor, so the first throw suppresses every later execution"
                            + " and nothing is logged"));
        }
        return out;
    }

    private static Set<String> names(String masked, Pattern p) {
        Set<String> out = new HashSet<>();
        Matcher m = p.matcher(masked);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** Offsets of the first argument of a call whose '(' is at {@code openParen}. */
    private static int[] firstArgument(String masked, int openParen) {
        if (openParen < 0 || openParen >= masked.length() || masked.charAt(openParen) != '(') {
            return null;
        }
        int depth = 0;
        for (int i = openParen; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    return new int[]{openParen + 1, i};
                }
            } else if (c == ',' && depth == 1) {
                return new int[]{openParen + 1, i};
            }
        }
        return null;
    }

    /** What the scheduled task's body looks like, and whether it is guarded end to end. */
    private record Body(boolean guarded, String detail) {
        static final Body UNRESOLVED = new Body(false, null);
    }

    private static Body taskBody(String masked, int from, int to) {
        String raw = masked.substring(from, to);
        // ⚠️ strip() for matching, but every offset handed back to `masked` must add the leading
        // whitespace back. Losing this shifts the block boundary by however many spaces the call
        // site happened to use — wrong only sometimes, which is the worst kind of wrong.
        int lead = 0;
        while (lead < raw.length() && Character.isWhitespace(raw.charAt(lead))) {
            lead++;
        }
        String expr = raw.strip();

        int arrow = indexOfArrow(expr);
        if (arrow >= 0) {
            String rhs = expr.substring(arrow + 2).strip();
            if (rhs.startsWith("{")) {
                return judge(blockOf(masked, from + lead + expr.indexOf('{', arrow)));
            }
            // () -> doWork() — a single expression cannot carry a try/catch at all.
            return new Body(false, "the task is a single-expression lambda, so it has no guard");
        }
        if (expr.startsWith("new ") && expr.contains("{")) {
            int runAt = expr.indexOf("run");
            if (runAt < 0) {
                return Body.UNRESOLVED;
            }
            int brace = expr.indexOf('{', runAt);
            if (brace < 0) {
                return Body.UNRESOLVED;
            }
            return judge(blockOf(masked, from + lead + brace));
        }
        if (expr.startsWith("new ")) {
            // new StatusCheckRunnable() — a named Runnable. Very common, and when the class is an
            // inner class of this same file its run() is right there; answering UNKNOWN for
            // something readable would be its own kind of giving up.
            Matcher ctor = Pattern.compile("^new\\s+([\\w.]+)\\s*\\(").matcher(expr);
            if (!ctor.find()) {
                return Body.UNRESOLVED;
            }
            String type = ctor.group(1);
            String simple = type.substring(type.lastIndexOf('.') + 1);
            Matcher cls = Pattern.compile("\\bclass\\s+" + Pattern.quote(simple) + "\\b")
                    .matcher(masked);
            if (!cls.find()) {
                return Body.UNRESOLVED;
            }
            int classOpen = masked.indexOf('{', cls.end());
            if (classOpen < 0) {
                return Body.UNRESOLVED;
            }
            int classClose = matching(masked, classOpen);
            if (classClose < 0) {
                return Body.UNRESOLVED;
            }
            Matcher run = Pattern.compile("\\brun\\s*\\(\\s*\\)\\s*\\{")
                    .matcher(masked.substring(classOpen, classClose));
            if (!run.find()) {
                return Body.UNRESOLVED;
            }
            return judge(blockOf(masked, classOpen + run.end() - 1));
        }
        // this::doWork / doWork / someField — resolve a method of that name in this file.
        String name = expr.contains("::")
                ? expr.substring(expr.lastIndexOf("::") + 2).strip()
                : expr;
        if (!name.matches("\\w+")) {
            return Body.UNRESOLVED;
        }
        Matcher decl = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\([^()]*\\)\\s*\\{")
                .matcher(masked);
        while (decl.find()) {
            // Skip the scheduling call site itself.
            if (decl.start() >= from && decl.end() <= to) {
                continue;
            }
            return judge(blockOf(masked, masked.indexOf('{', decl.end() - 1)));
        }
        return Body.UNRESOLVED;
    }

    /** The arrow of a lambda, at paren depth 0 — not one inside a nested call. */
    private static int indexOfArrow(String expr) {
        int depth = 0;
        for (int i = 0; i + 1 < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && c == '-' && expr.charAt(i + 1) == '>') {
                return i;
            }
        }
        return -1;
    }

    private static String blockOf(String masked, int open) {
        if (open < 0) {
            return null;
        }
        int close = matching(masked, open);
        return close < 0 ? null : masked.substring(open + 1, close);
    }

    private static Body judge(String bodyMasked) {
        if (bodyMasked == null) {
            return Body.UNRESOLVED;
        }
        if (bodyMasked.isBlank()) {
            return new Body(true, null);
        }
        if (CATCH_THROWABLE.matcher(bodyMasked).find() && wrapsWholeBody(bodyMasked)) {
            return new Body(true, null);
        }
        return new Body(false, bodyMasked.contains("catch")
                ? "the task's outermost level catches something narrower than Throwable, and an"
                + " Error suppresses the schedule just the same"
                : "the task body has no top-level try/catch (Throwable)");
    }

    /**
     * True when the body is a single try statement spanning everything.
     *
     * <p>A {@code catch (Throwable)} around only the second half still leaves the first half able
     * to suppress the schedule, so the position matters, not just the presence.
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
        int lastBrace = tail.lastIndexOf('}');
        return lastBrace < 0 || tail.substring(lastBrace + 1).isBlank();
    }

    private static String read(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
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
     * inside a message string silently shifts every boundary after it.
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
