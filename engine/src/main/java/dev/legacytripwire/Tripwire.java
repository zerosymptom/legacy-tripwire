package dev.legacytripwire;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point.
 *
 * <pre>
 *   tripwire [path]        scan a checkout (default: current directory)
 *   tripwire --list        print the rules that are executable today
 * </pre>
 *
 * <p>Exit codes — note that there is no code that means "ran but could not tell":
 * <ul>
 *   <li>{@code 0} every rule returned CLEAR</li>
 *   <li>{@code 1} at least one rule TRIPPED</li>
 *   <li>{@code 2} at least one rule returned UNKNOWN and none tripped</li>
 *   <li>{@code 3} bad usage</li>
 * </ul>
 * UNKNOWN is deliberately non-zero. Everything in the catalogue it checks for looks like success
 * while being broken; a checker that exits 0 when it could not reach a conclusion would be one
 * more instance of the same bug.
 */
public final class Tripwire {

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    static int run(String[] args, PrintStream out) {
        if (args.length == 1 && (args[0].equals("--list") || args[0].equals("-l"))) {
            for (Rule r : Rules.all()) {
                out.println(r.id() + "  " + r.title());
            }
            return 0;
        }
        if (args.length > 1) {
            out.println("usage: tripwire [path]  |  tripwire --list");
            return 3;
        }
        Path root = (args.length == 1 ? Path.of(args[0]) : Path.of(".")).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            out.println("not a directory: " + root);
            return 3;
        }

        List<Finding> findings = new ArrayList<>();
        List<String> scopes = new ArrayList<>();
        for (Rule r : Rules.all()) {
            scopes.add("  " + r.id() + "  examined " + r.scope(root));
            findings.addAll(r.check(root));
        }

        long tripped = findings.stream().filter(f -> f.verdict() == Verdict.TRIPPED).count();
        long unknown = findings.stream().filter(f -> f.verdict() == Verdict.UNKNOWN).count();

        out.println("tripwire  " + root);
        out.println("rules: " + Rules.all().size() + "   findings: " + findings.size());
        List<String> ignores = Files2.ignores(root);
        if (!ignores.isEmpty()) {
            // Always visible. An exclusion nobody can see is how a check quietly stops checking.
            out.println("excluded by " + Files2.IGNORE_FILE + ": " + String.join(", ", ignores));
        }
        scopes.forEach(out::println);
        out.println();
        for (Finding f : findings) {
            out.println(f.render(root));
            out.println();
        }
        if (findings.isEmpty()) {
            out.println("CLEAR — no rule matched.");
            out.println("(This means the checks that exist found nothing. "
                    + "It is not a statement about the rest of the codebase.)");
            return 0;
        }
        out.println("tripped: " + tripped + "   unknown: " + unknown);
        if (tripped > 0) {
            return 1;
        }
        out.println("No rule tripped, but " + unknown + " could not be decided. "
                + "Treating that as failure on purpose — see Verdict.UNKNOWN.");
        return 2;
    }
}
