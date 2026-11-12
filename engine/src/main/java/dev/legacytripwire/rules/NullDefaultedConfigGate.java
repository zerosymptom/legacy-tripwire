package dev.legacytripwire.rules;

import dev.legacytripwire.Finding;
import dev.legacytripwire.JavaSource;
import dev.legacytripwire.Rule;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LT-S10 — a host gate fed by a config value defaulting to null skips on <i>every</i> host.
 *
 * <p>The shape: read a value from configuration with {@code null} as the fallback, then use it
 * to decide whether this machine should do the work.
 *
 * <pre>{@code
 * String host = Config.getString("scheduler.host", null);
 * if (!localAddresses.contains(host)) return;     // host == null -> false on every machine
 * }</pre>
 *
 * <p>While the key is set this is correct and runs on exactly one host, which is the intent.
 * Unset the key, rename it, or have the config read time out, and {@code contains(null)} is
 * false everywhere — so the job stops running on <b>every</b> machine at once. Nothing throws.
 * The only symptom is that something quietly stopped happening.
 *
 * <p>⚠️ This reports a <b>latent</b> condition, not a present defect. The construct is working
 * as intended wherever the key is currently set. What it says is that the failure mode exists
 * and is silent, and that one absent key takes out every host rather than one.
 *
 * <p>False-positive boundary: a null check on the value between the read and the gate clears it
 * — that is the fix, and the rule recognises it. An explicit non-null default is not this rule.
 */
public final class NullDefaultedConfigGate implements Rule {

    private static final Pattern READ = Pattern.compile(
            "(\\w+)\\s*=\\s*(\\w*(?:[Cc]onfig|[Aa]pollo|[Nn]acos|[Ss]etting|[Pp]ropert)\\w*)"
                    + "\\s*\\.\\s*(get\\w*)\\s*\\([^;{}]*?,\\s*null\\s*\\)");

    @Override
    public String id() {
        return "LT-S10";
    }

    @Override
    public String title() {
        return "A host gate on a null-defaulted config value skips on every host, not one";
    }

    @Override
    public String scope(Path root) {
        int reads = 0;
        int unreadable = 0;
        for (Path f : JavaSource.files(root)) {
            String src = JavaSource.read(f);
            if (src == null) {
                unreadable++;
                continue;
            }
            Matcher m = READ.matcher(JavaSource.mask(src));
            while (m.find()) {
                reads++;
            }
        }
        return reads + " null-defaulted config reads"
                + (unreadable > 0 ? " (+" + unreadable + " not readable as UTF-8)" : "");
    }

    @Override
    public List<Finding> check(Path root) {
        List<Finding> out = new ArrayList<>();
        for (Path f : JavaSource.files(root)) {
            String src = JavaSource.read(f);
            if (src == null) {
                out.add(Finding.unknown(id(), f,
                        "not readable as UTF-8 — cannot tell whether a null-defaulted config"
                                + " value reaches a host gate"));
                continue;
            }
            String masked = JavaSource.mask(src);
            Matcher m = READ.matcher(masked);
            while (m.find()) {
                String var = m.group(1);
                int[] body = JavaSource.enclosingMethod(masked, m.start());
                if (body == null) {
                    continue;
                }
                String after = masked.substring(m.end(), body[1]);
                if (guarded(after, var)) {
                    continue;
                }
                Matcher gate = Pattern.compile(
                        "\\.(equals|contains|equalsIgnoreCase)\\s*\\(\\s*" + Pattern.quote(var)
                                + "\\s*\\)").matcher(after);
                if (gate.find()) {
                    out.add(Finding.tripped(id(), f, JavaSource.lineOf(src, m.start()),
                            var + " defaults to null and reaches " + gate.group(0).trim()
                                    + " with no null check in between — while the key is set this"
                                    + " runs on one host as intended; if it is ever unset, renamed,"
                                    + " or the read times out, the gate is false on every host and"
                                    + " the work silently stops everywhere"));
                }
            }
        }
        return out;
    }

    /** A null check on {@code var} anywhere before the gate is the documented fix. */
    private static boolean guarded(String after, String var) {
        String v = Pattern.quote(var);
        return Pattern.compile(
                v + "\\s*[!=]=\\s*null"
                        + "|null\\s*[!=]=\\s*" + v
                        + "|is(Empty|Blank|NotEmpty|NotBlank)\\s*\\(\\s*" + v + "\\s*\\)"
                        + "|Objects\\s*\\.\\s*\\w+\\s*\\(\\s*" + v).matcher(after).find();
    }
}
