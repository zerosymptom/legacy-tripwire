package dev.legacytripwire;

import dev.legacytripwire.rules.GitignoredTests;
import dev.legacytripwire.rules.NonBmpInContainerXml;
import dev.legacytripwire.rules.SkipTestsLiteral;
import dev.legacytripwire.rules.UnguardedScheduledMethod;
import dev.legacytripwire.rules.UnregisteredSpiImplementation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replay cases. Every rule gets at least one input that must trip it and one that must not.
 *
 * <p>This is the project's own red line: a rule with no reproducible case does not ship.
 * {@link #every_rule_has_a_tripping_and_a_clean_fixture()} enforces it mechanically, so
 * "I'll add the test later" fails the build rather than quietly becoming permanent.
 */
class ReplayTest {

    static Path fixture(String rule, String variant) {
        try {
            return Path.of(ReplayTest.class.getResource("/replay/" + rule + "/" + variant).toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new AssertionError("missing fixture: replay/" + rule + "/" + variant, e);
        }
    }

    static List<Finding> of(List<Finding> findings, Verdict v) {
        return findings.stream().filter(f -> f.verdict() == v).toList();
    }

    // ---------------------------------------------------------------- LT-S01

    @Test
    @DisplayName("LT-S01 trips on a literal <skipTests>true</skipTests> in surefire")
    void s01_trips_on_literal() {
        List<Finding> f = new SkipTestsLiteral().check(fixture("LT-S01", "trip"));
        assertEquals(1, of(f, Verdict.TRIPPED).size(), f.toString());
        assertTrue(f.get(0).detail().contains("maven-surefire-plugin"));
    }

    @Test
    @DisplayName("LT-S01 stays clear on a property reference, a <properties> default, and another plugin's <skip>")
    void s01_respects_the_false_positive_boundary() {
        // The published boundary is literal-vs-property-reference, and <properties> defaults are
        // explicitly NOT this rule. All three shapes live in one fixture so a regression on any of
        // them fails here rather than on someone else's repository.
        List<Finding> f = new SkipTestsLiteral().check(fixture("LT-S01", "clear"));
        assertTrue(f.isEmpty(), "expected no findings, got " + f);
    }

    @Test
    @DisplayName("LT-S01 returns UNKNOWN inside a <profile> rather than guessing")
    void s01_profile_is_unknown() {
        List<Finding> f = new SkipTestsLiteral().check(fixture("LT-S01", "profile"));
        assertEquals(1, of(f, Verdict.UNKNOWN).size(), f.toString());
        assertTrue(f.get(0).detail().contains("<profile>"));
    }

    // ---------------------------------------------------------------- LT-P01

    @Test
    @DisplayName("LT-P01 trips on a character above U+FFFF in the descriptor")
    void p01_trips_on_astral_character() {
        List<Finding> f = new NonBmpInContainerXml().check(fixture("LT-P01", "trip"));
        assertEquals(1, of(f, Verdict.TRIPPED).size(), f.toString());
        assertTrue(f.get(0).detail().contains("U+1F534"), f.get(0).detail());
        assertEquals(2, f.get(0).line(), "should point at the offending line");
    }

    @Test
    @DisplayName("LT-P01 stays clear on BMP symbols and CJK — those are safe in the same file")
    void p01_bmp_symbols_are_fine() {
        List<Finding> f = new NonBmpInContainerXml().check(fixture("LT-P01", "clear"));
        assertTrue(f.isEmpty(), "expected no findings, got " + f);
    }

    // ---------------------------------------------------------------- LT-S04

    @Test
    @DisplayName("LT-S04 trips when git actually ignores the test sources")
    void s04_trips_when_git_ignores_tests(@TempDir Path tmp) throws Exception {
        Path repo = gitRepoFrom(fixture("LT-S04", "trip"), tmp);
        List<Finding> f = new GitignoredTests().check(repo);
        assertEquals(1, of(f, Verdict.TRIPPED).size(), f.toString());
        assertTrue(f.get(0).detail().contains("disappear on commit"));
    }

    @Test
    @DisplayName("LT-S04 stays clear when the tests are tracked")
    void s04_clear_when_tests_tracked(@TempDir Path tmp) throws Exception {
        Path repo = gitRepoFrom(fixture("LT-S04", "clear"), tmp);
        assertTrue(new GitignoredTests().check(repo).isEmpty());
    }

    @Test
    @DisplayName("LT-S04 returns UNKNOWN outside a git work tree — never CLEAR")
    void s04_unknown_outside_a_repo(@TempDir Path tmp) throws Exception {
        Path plain = copy(fixture("LT-S04", "clear"), tmp.resolve("plain"));
        Files.deleteIfExists(plain.resolve("gitignore.template"));
        List<Finding> f = new GitignoredTests().check(plain);
        assertEquals(1, of(f, Verdict.UNKNOWN).size(),
                "a directory that is not a repo must be UNKNOWN, not CLEAR: " + f);
    }

    // ---------------------------------------------------------------- LT-S08

    @Test
    @DisplayName("LT-S08 trips on a scheduled method with no top-level guard")
    void s08_trips_on_unguarded_job() {
        List<Finding> f = new UnguardedScheduledMethod().check(fixture("LT-S08", "trip"));
        assertEquals(1, of(f, Verdict.TRIPPED).size(), f.toString());
        assertTrue(f.get(0).detail().contains("removes this task from the scheduler"),
                f.get(0).detail());
    }

    @Test
    @DisplayName("LT-S08 stays clear on catch (Throwable), on unscheduled methods, and on a brace in a string")
    void s08_respects_the_false_positive_boundary() {
        // All three shapes share one fixture so a regression on any of them fails here rather
        // than on someone else's repository. The brace-in-a-string case is the one that would
        // silently shift every later method boundary if the masking broke.
        List<Finding> f = new UnguardedScheduledMethod().check(fixture("LT-S08", "clear"));
        assertTrue(f.isEmpty(), "expected no findings, got " + f);
    }

    @Test
    @DisplayName("LT-S08 counts the scheduled methods it examined, not the files")
    void s08_reports_its_scope() {
        assertEquals("1 scheduled methods", new UnguardedScheduledMethod().scope(fixture("LT-S08", "trip")));
    }

    @Test
    @DisplayName("LT-S08 returns UNKNOWN on a source it cannot decode — never a quiet skip")
    void s08_unreadable_source_is_unknown(@TempDir Path tmp) throws Exception {
        // Found on a real tree: a GBK-encoded *Schedule.java in a task/timer package was dropped
        // silently, so the rule reported CLEAR for the module most likely to need it. That is
        // LT-S14 (a checker that fails open) committed by this checker.
        Path dir = Files.createDirectories(tmp.resolve("legacy"));
        Files.write(dir.resolve("TaskSchedule.java"),
                new byte[] {'@', 'S', 'c', 'h', 'e', 'd', 'u', 'l', 'e', 'd', '\n', (byte) 0xC4, (byte) 0xE3});
        List<Finding> f = new UnguardedScheduledMethod().check(dir);
        assertEquals(1, of(f, Verdict.UNKNOWN).size(), "must be UNKNOWN, not a silent skip: " + f);
        assertTrue(new UnguardedScheduledMethod().scope(dir).contains("not readable as UTF-8"),
                "the scope line has to admit what it could not read");
    }

    // ---------------------------------------------------------------- LT-S12

    @Test
    @DisplayName("LT-S12 trips on an implementation missing from META-INF/services")
    void s12_trips_on_unregistered_implementation() {
        List<Finding> f = new UnregisteredSpiImplementation().check(fixture("LT-S12", "trip"));
        assertEquals(1, of(f, Verdict.TRIPPED).size(), f.toString());
        assertTrue(f.get(0).detail().contains("com.example.spi.RangeValidator"), f.get(0).detail());
        assertTrue(f.get(0).detail().contains("never instantiate it"), f.get(0).detail());
    }

    @Test
    @DisplayName("LT-S12 stays clear when every implementation is listed — and never flags the interface itself")
    void s12_clear_when_all_registered() {
        List<Finding> f = new UnregisteredSpiImplementation().check(fixture("LT-S12", "clear"));
        assertTrue(f.isEmpty(), "expected no findings, got " + f);
    }

    @Test
    @DisplayName("LT-S12 finds nothing to say in a tree with no registry files")
    void s12_silent_without_registries() {
        assertTrue(new UnregisteredSpiImplementation().check(fixture("LT-S08", "clear")).isEmpty());
        assertEquals("0 SPI registry files",
                new UnregisteredSpiImplementation().scope(fixture("LT-S08", "clear")));
    }

    @Test
    @DisplayName("LT-S12 never matches one module's implementation against another's registry")
    void s12_does_not_cross_module_boundaries() {
        // Found by running the tool on its own checkout: a class under .../clear/ was reported
        // as missing from the registry under .../trip/. In a real multi-module build that is
        // every module accusing every other module, which is precisely the false-positive
        // class this project refuses to ship.
        List<Finding> f = new UnregisteredSpiImplementation().check(fixture("LT-S12", "twomodules"));
        assertTrue(f.isEmpty(), "each module registers its own implementation: " + f);
    }

    // ---------------------------------------------------------------- the red line

    @Test
    @DisplayName("every registered rule has both a tripping and a clean fixture")
    void every_rule_has_a_tripping_and_a_clean_fixture() {
        for (Rule r : Rules.all()) {
            for (String variant : List.of("trip", "clear")) {
                Path p = fixture(r.id(), variant);
                assertTrue(Files.isDirectory(p), r.id() + " is missing its '" + variant + "' fixture");
            }
        }
    }

    @Test
    @DisplayName("rule ids are unique and look like the published catalogue")
    void rule_ids_are_sane() {
        List<String> ids = Rules.all().stream().map(Rule::id).toList();
        assertEquals(ids.size(), ids.stream().distinct().count(), "duplicate rule id");
        ids.forEach(id -> assertTrue(id.matches("LT-[A-Z]\\d{2}"), "odd id: " + id));
    }

    // ---------------------------------------------------------------- helpers

    private static Path copy(Path src, Path dest) throws IOException {
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : s.toList()) {
                Path target = dest.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
        return dest;
    }

    /**
     * Materialise a fixture as a real repository. The fixture ships {@code gitignore.template}
     * rather than {@code .gitignore} on purpose — a literal dotfile checked in here would change
     * the ignore rules of the repository that contains it.
     */
    private static Path gitRepoFrom(Path fixture, Path tmp) throws Exception {
        Path repo = copy(fixture, tmp.resolve("repo"));
        Path tpl = repo.resolve("gitignore.template");
        if (Files.exists(tpl)) {
            Files.move(tpl, repo.resolve(".gitignore"));
        }
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "replay@example.invalid");
        git(repo, "config", "user.name", "replay");
        return repo;
    }

    private static void git(Path cwd, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).start();
        assertTrue(p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "git hung: " + List.of(args));
        assertFalse(p.exitValue() != 0, "git " + String.join(" ", args) + " exited " + p.exitValue());
    }
}
