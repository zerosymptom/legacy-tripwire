package dev.legacytripwire;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exit code is the whole product when this runs in CI, so it gets its own tests.
 *
 * <p>The one that matters most is {@link #unknown_exits_non_zero()}: a checker that exits 0
 * when it could not reach a conclusion would be another instance of the bug this project
 * is about.
 */
class TripwireCliTest {

    private record Run(int code, String out) {
    }

    private static Run cli(String... args) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = Tripwire.run(args, new PrintStream(buf, true, StandardCharsets.UTF_8));
        return new Run(code, buf.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a clean tree exits 0 and says so without overclaiming")
    void clean_tree_exits_zero(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("README.md"), "nothing to see");
        Run r = cli(tmp.toString());
        assertEquals(0, r.code());
        assertTrue(r.out().contains("CLEAR"));
        // The wording must not imply the codebase is fine — only that these checks found nothing.
        assertTrue(r.out().contains("not a statement about the rest"), r.out());
    }

    @Test
    @DisplayName("a tripped rule exits 1")
    void tripped_exits_one() {
        Run r = cli(ReplayTest.fixture("LT-S01", "trip").toString());
        assertEquals(1, r.code(), r.out());
        assertTrue(r.out().contains("TRIPPED"));
        assertTrue(r.out().contains("LT-S01"));
    }

    @Test
    @DisplayName("UNKNOWN exits non-zero — not reaching a conclusion is never a pass")
    void unknown_exits_non_zero() {
        Run r = cli(ReplayTest.fixture("LT-S01", "profile").toString());
        assertEquals(2, r.code(), r.out());
        assertTrue(r.out().contains("UNKNOWN"));
        assertTrue(r.out().contains("Treating that as failure on purpose"), r.out());
    }

    @Test
    @DisplayName("--list prints every executable rule")
    void list_prints_rules() {
        Run r = cli("--list");
        assertEquals(0, r.code());
        Rules.all().forEach(rule -> assertTrue(r.out().contains(rule.id()), rule.id()));
    }

    @Test
    @DisplayName("bad usage exits 3 rather than pretending to have scanned something")
    void bad_usage_exits_three() {
        assertEquals(3, cli("a", "b").code());
        assertEquals(3, cli("/no/such/directory/anywhere").code());
    }

    @Test
    @DisplayName("the engine's own checkout is clean — this project eats its own dog food")
    void own_repository_is_clean() {
        // Walk up from target/test-classes to the engine module root.
        Path here = Path.of("").toAbsolutePath();
        Run r = cli(here.toString());
        assertEquals(0, r.code(),
                "tripwire must not trip on its own source tree:\n" + r.out());
    }
}
