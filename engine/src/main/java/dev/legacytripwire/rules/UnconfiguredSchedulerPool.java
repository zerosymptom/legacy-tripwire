package dev.legacytripwire.rules;

import dev.legacytripwire.Files2;
import dev.legacytripwire.Finding;
import dev.legacytripwire.JavaSource;
import dev.legacytripwire.Rule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LT-S11 — every scheduled method in the application shares one thread, by default.
 *
 * <p>Spring's default {@code TaskScheduler} has a pool size of <b>one</b>. With two or more
 * {@code @Scheduled} methods and no pool configured, a single slow run blocks every other job
 * behind it. Nothing errors. The delayed jobs simply fire late, or appear to have skipped a
 * tick — and the one you would suspect is the slow job, which is the only one working fine.
 *
 * <p>It compounds with LT-S08: a job that hangs rather than throws takes the others with it.
 *
 * <p>Published criterion: two or more scheduled entry points exist and no scheduler pool is
 * configured anywhere in the checkout.
 *
 * <p>False-positive boundary: a single {@code @Scheduled} method cannot starve anything, so one
 * is never reported. Any of the usual ways to size the pool counts as configured, including the
 * property form — this rule asks whether the question was answered, not how.
 */
public final class UnconfiguredSchedulerPool implements Rule {

    private static final Pattern SCHEDULED = Pattern.compile("@(Scheduled|Schedules)\\b");

    private static final Pattern POOL_CONFIGURED = Pattern.compile(
            "ThreadPoolTaskScheduler"
                    + "|ScheduledThreadPoolExecutor"
                    + "|SchedulingConfigurer"
                    + "|setPoolSize"
                    + "|spring\\.task\\.scheduling\\.pool\\.size"
                    + "|task-executor\\s*=");

    private static final List<String> CONFIG_SUFFIXES =
            List.of(".java", ".xml", ".properties", ".yml", ".yaml");

    @Override
    public String id() {
        return "LT-S11";
    }

    @Override
    public String title() {
        return "Two or more scheduled methods with an unsized pool all queue behind one thread";
    }

    private static int countScheduled(Path root) {
        int n = 0;
        for (Path f : JavaSource.files(root)) {
            String src = JavaSource.read(f);
            if (src != null) {
                Matcher m = SCHEDULED.matcher(JavaSource.mask(src));
                while (m.find()) {
                    n++;
                }
            }
        }
        return n;
    }

    @Override
    public String scope(Path root) {
        return countScheduled(root) + " scheduled methods, "
                + configFiles(root).size() + " files that could size the pool";
    }

    private static List<Path> configFiles(Path root) {
        return Files2.walk(root, p -> {
            String n = p.getFileName().toString();
            return CONFIG_SUFFIXES.stream().anyMatch(n::endsWith);
        });
    }

    @Override
    public List<Finding> check(Path root) {
        int scheduled = countScheduled(root);
        if (scheduled < 2) {
            return List.of();
        }
        for (Path f : configFiles(root)) {
            String text;
            try {
                text = Files.readString(f, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                // One unreadable file could be the one that sizes the pool. Saying CLEAR here
                // would be a guess dressed as an answer.
                return List.of(Finding.unknown(id(), f,
                        "not readable as UTF-8 — it may or may not size the scheduler pool, so"
                                + " whether " + scheduled + " scheduled methods share one thread"
                                + " cannot be decided"));
            }
            if (POOL_CONFIGURED.matcher(text).find()) {
                return List.of();
            }
        }
        return List.of(Finding.tripped(id(), null, 0,
                scheduled + " scheduled methods and no scheduler pool configured anywhere —"
                        + " Spring's default TaskScheduler runs one thread, so a single slow run"
                        + " delays every other job behind it, with nothing logged and no error"));
    }
}
