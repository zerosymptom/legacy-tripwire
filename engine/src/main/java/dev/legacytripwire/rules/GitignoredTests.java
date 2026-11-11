package dev.legacytripwire.rules;

import dev.legacytripwire.Files2;
import dev.legacytripwire.Finding;
import dev.legacytripwire.Rule;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * LT-S04 — version control swallows the whole test directory.
 *
 * <p>Symptom: tests compile, run and pass; {@code git status} never shows them; they are gone
 * after the commit. Zero symptoms the entire way.
 *
 * <p>Check, exactly as published: {@code git check-ignore -v <test file>} produces output.
 * We shell out to git rather than reimplementing ignore semantics — the real matcher is the
 * only one whose answer counts, and a hand-rolled approximation would produce precisely the
 * kind of confidently-wrong result this catalogue exists to prevent.
 *
 * <p>No git, or not a repository → UNKNOWN. A repository with no test sources → CLEAR
 * (there is nothing to lose).
 */
public final class GitignoredTests implements Rule {

    @Override
    public String id() {
        return "LT-S04";
    }

    @Override
    public String title() {
        return "Version control ignores the test directory; committed tests vanish silently";
    }

    @Override
    public String scope(Path root) {
        return tests(root).size() + " test sources";
    }

    private static List<Path> tests(Path root) {
        return Files2.walk(root, p ->
                p.getFileName().toString().endsWith(".java") && Files2.under(root, p, "test"));
    }

    @Override
    public List<Finding> check(Path root) {
        List<Path> tests = Files2.walk(root, p ->
                p.getFileName().toString().endsWith(".java") && Files2.under(root, p, "test"));
        if (tests.isEmpty()) {
            return List.of();                                   // nothing to lose → CLEAR
        }
        Git git = run(root, "rev-parse", "--is-inside-work-tree");
        if (!git.ok() || !git.out().startsWith("true")) {
            return List.of(Finding.unknown(id(), root,
                    tests.size() + " test sources found, but this is not a git work tree"
                            + " (or git is unavailable) — cannot tell whether they are ignored"));
        }
        List<Finding> out = new ArrayList<>();
        for (Path t : tests) {
            Git r = run(root, "check-ignore", "-v", t.toString());
            // git check-ignore: exit 0 = ignored (with -v it prints the matching rule),
            //                   exit 1 = not ignored, anything else = real error.
            if (r.code() == 0 && !r.out().isEmpty()) {
                out.add(Finding.tripped(id(), t, 0,
                        "this test file is ignored by " + r.out().split("\\s+")[0]
                                + " — it will compile, run and pass locally, then disappear on commit"));
            } else if (r.code() != 0 && r.code() != 1) {
                out.add(Finding.unknown(id(), t,
                        "git check-ignore exited " + r.code() + "; ignore status undetermined"));
            }
        }
        return out;
    }

    private record Git(int code, String out) {
        boolean ok() {
            return code == 0;
        }
    }

    private static Git run(Path cwd, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).directory(cwd.toFile())
                    .redirectErrorStream(false).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Git(-1, "");
            }
            return new Git(p.exitValue(), out);
        } catch (IOException e) {
            return new Git(-1, "");                             // git not installed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Git(-1, "");
        }
    }

    /** Exposed for the replay fixtures, which need a throwaway repository. */
    public static boolean gitAvailable() {
        try {
            return run(Path.of(System.getProperty("java.io.tmpdir")), "--version").ok();
        } catch (RuntimeException e) {
            return false;
        }
    }

}
