package dev.legacytripwire;

/**
 * Three states, on purpose.
 *
 * <p>The whole premise of this project is that silent failures look exactly like success.
 * A checker that can only say yes/no inherits that problem: when it cannot reach a
 * conclusion it has to pick one, and "no problem found" is the tempting answer.
 *
 * <p>So {@link #UNKNOWN} is a first-class outcome and it is <b>treated as failure</b>
 * by the exit code. Not reaching a conclusion is never the same as passing.
 */
public enum Verdict {
    /** The rule ran and found nothing. */
    CLEAR,
    /** The rule ran and the pattern is present. */
    TRIPPED,
    /** The rule could not reach a conclusion. Never report this as passing. */
    UNKNOWN
}
