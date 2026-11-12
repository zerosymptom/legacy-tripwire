package dev.legacytripwire;

import dev.legacytripwire.rules.GitignoredTests;
import dev.legacytripwire.rules.NonBmpInContainerXml;
import dev.legacytripwire.rules.SkipTestsLiteral;
import dev.legacytripwire.rules.StaticFinalFromConfig;
import dev.legacytripwire.rules.UnguardedScheduledMethod;
import dev.legacytripwire.rules.UnconfiguredSchedulerPool;
import dev.legacytripwire.rules.UnregisteredSpiImplementation;

import java.util.List;

/**
 * Every executable rule, in one place.
 *
 * <p>⛔ LT-S10 (a null-defaulted config value reaching a host gate) was written, tested, and
 * then <b>not registered</b>. The check is correct — it found 771 real instances across 538
 * files — but the idiom is used deliberately and works wherever the key is set, so every one
 * of those findings points at code that is fine today. An agent handed 771 of them would go
 * and "fix" 771 correct call sites. Density turned a true positive into noise, which is the
 * one failure mode this gate must not have. It stays in RULES.md as MANUAL.
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
                new NonBmpInContainerXml(),
                new UnguardedScheduledMethod(),
                new UnregisteredSpiImplementation(),
                new StaticFinalFromConfig(),
                new UnconfiguredSchedulerPool());
    }
}
