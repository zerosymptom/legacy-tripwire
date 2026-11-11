package dev.legacytripwire;

import dev.legacytripwire.rules.GitignoredTests;
import dev.legacytripwire.rules.NonBmpInContainerXml;
import dev.legacytripwire.rules.SkipTestsLiteral;

import java.util.List;

/**
 * Every executable rule, in one place.
 *
 * <p>Adding one here is not enough to ship it: {@code ReplayFixturesTest} fails the build unless
 * the rule also has a tripping fixture and a clean fixture. A rule nobody can reproduce is a
 * claim, not a check.
 */
public final class Rules {

    private Rules() {
    }

    public static List<Rule> all() {
        return List.of(
                new SkipTestsLiteral(),
                new GitignoredTests(),
                new NonBmpInContainerXml());
    }
}
