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
 * LT-S09 — a {@code static final} field read from the config service can never be updated.
 *
 * <p>Symptom: someone changes a value in the configuration UI, publishes it, and <b>nothing
 * happens</b>. The value took effect at class-load time and is now a compile-time-shaped
 * constant; only a restart will move it. There is no error, no warning, and the config service
 * cheerfully reports the new value as live — the application just isn't reading it any more.
 *
 * <p>These frozen values are often used to decide "only run on this host", so the config
 * changes, the job keeps running on the old host, and the only trace is one line at WARN.
 *
 * <p>Published criterion: the initialiser of a {@code static final} field calls a configuration
 * read API.
 *
 * <p>False-positive boundary: the receiver must <em>look like</em> configuration — its name
 * contains config / apollo / nacos / setting / propert. {@code LoggerFactory.getLogger(...)} is
 * the canonical {@code static final} method call and is explicitly excluded; freezing a logger
 * is correct. A non-final {@code static} field is not this rule either — it can be refreshed.
 */
public final class StaticFinalFromConfig implements Rule {

    private static final Pattern FIELD = Pattern.compile(
            "\\bstatic\\s+final\\s+[\\w.<>\\[\\],\\s]+?\\s+(\\w+)\\s*=\\s*(\\w+)\\s*\\.\\s*(\\w+)\\s*\\(");

    private static final Pattern CONFIGISH = Pattern.compile(
            "config|apollo|nacos|setting|propert", Pattern.CASE_INSENSITIVE);

    /**
     * A read that returns a <em>value</em>. {@code getInstance} / {@code getBean} are accessors
     * for a singleton or a container, and freezing one of those in a static final is correct —
     * flagging them sent 42 findings at a single module whose code was fine.
     */
    private static final Pattern READER = Pattern.compile(
            "^(get|read|load|fetch)(String|Int|Integer|Long|Boolean|Double|Float|Value|Config"
                    + "|Property|Properties|Setting|Settings|Str|Num)\\w*$");

    @Override
    public String id() {
        return "LT-S09";
    }

    @Override
    public String title() {
        return "A static final field read from config can never be hot-updated, and says nothing";
    }

    @Override
    public String scope(Path root) {
        int unreadable = 0;
        int files = 0;
        for (Path f : JavaSource.files(root)) {
            if (JavaSource.read(f) == null) {
                unreadable++;
            } else {
                files++;
            }
        }
        return files + " java sources"
                + (unreadable > 0 ? " (+" + unreadable + " not readable as UTF-8)" : "");
    }

    @Override
    public List<Finding> check(Path root) {
        List<Finding> out = new ArrayList<>();
        for (Path f : JavaSource.files(root)) {
            String src = JavaSource.read(f);
            if (src == null) {
                out.add(Finding.unknown(id(), f,
                        "not readable as UTF-8 — cannot tell whether it freezes a config value"));
                continue;
            }
            Matcher m = FIELD.matcher(JavaSource.mask(src));
            while (m.find()) {
                String field = m.group(1);
                String receiver = m.group(2);
                String method = m.group(3);
                if (!CONFIGISH.matcher(receiver).find() || !READER.matcher(method).matches()) {
                    continue;
                }
                out.add(Finding.tripped(id(), f, JavaSource.lineOf(src, m.start()),
                        field + " is static final and initialised from " + receiver + "."
                                + method + "(...) — the value freezes at class load, so changing"
                                + " it in the config service has no effect until a restart, and"
                                + " nothing reports that it was ignored"));
            }
        }
        return out;
    }
}
