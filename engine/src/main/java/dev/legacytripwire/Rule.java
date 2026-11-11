package dev.legacytripwire;

import java.nio.file.Path;
import java.util.List;

/**
 * One rule from RULES.md, made executable.
 *
 * <p><b>Only {@code MECHANICAL} rules belong here.</b> The catalogue also contains rules whose
 * check is a judgement call; those stay documentation. A false positive makes an agent go and
 * "fix" correct code, which costs more than the miss it prevents — so the gate only ever
 * contains checks that a command can decide.
 */
public interface Rule {

    /** Rule id as published in RULES.md, e.g. {@code LT-S01}. */
    String id();

    /** One line, present tense, describing the pattern. */
    String title();

    /**
     * Run against a checkout.
     *
     * @return every finding; an empty list means {@link Verdict#CLEAR}.
     *         If the rule cannot decide, return {@link Finding#unknown} —
     *         <b>never</b> return empty to mean "could not tell".
     */
    List<Finding> check(Path root);

    /**
     * What this rule actually looked at, e.g. {@code "6 pom.xml"}.
     *
     * <p>Printed on every run, including clean ones. "No findings" is worth nothing on its own —
     * it reads identically whether the rule examined forty files or zero. This is the line that
     * tells the two apart, and it is the same failure the catalogue warns about: never treat
     * "no output" as "nothing wrong" without first checking the command looked where you think.
     */
    String scope(Path root);
}
