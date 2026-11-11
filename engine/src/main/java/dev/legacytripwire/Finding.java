package dev.legacytripwire;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One result from one rule.
 *
 * @param ruleId  e.g. {@code LT-S01}, matching the entry in RULES.md
 * @param verdict see {@link Verdict}
 * @param where   file the finding points at; {@code null} when the rule is repo-wide
 * @param line    1-based line number, or 0 when not applicable
 * @param detail  what was found, in plain words — this is what a human reads first
 */
public record Finding(String ruleId, Verdict verdict, Path where, int line, String detail) {

    public Finding {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(detail, "detail");
    }

    public static Finding tripped(String ruleId, Path where, int line, String detail) {
        return new Finding(ruleId, Verdict.TRIPPED, where, line, detail);
    }

    /** Use this whenever the rule hit something it cannot evaluate. Do not return CLEAR instead. */
    public static Finding unknown(String ruleId, Path where, String detail) {
        return new Finding(ruleId, Verdict.UNKNOWN, where, 0, detail);
    }

    public String render(Path root) {
        String icon = switch (verdict) {
            case TRIPPED -> "TRIPPED";
            case UNKNOWN -> "UNKNOWN";
            case CLEAR -> "CLEAR  ";
        };
        String loc = where == null ? "" : "  " + rel(root) + (line > 0 ? ":" + line : "");
        return icon + "  " + ruleId + loc + "\n          " + detail;
    }

    private String rel(Path root) {
        try {
            return root.relativize(where).toString();
        } catch (IllegalArgumentException e) {
            return where.toString();
        }
    }
}
