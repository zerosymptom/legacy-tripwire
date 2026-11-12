# tripwire — the executable part

Five of the rules in [`RULES.md`](../RULES.md), as checks you can run.
Java 17, no runtime dependencies, one jar.

```bash
cd engine
mvn -q package
java -jar target/tripwire.jar /path/to/a/checkout
```

`mvn -q package` alone is enough to see it work — the build runs the replay cases.

## What it prints

```
tripwire  /path/to/a/checkout
rules: 5   findings: 0
  LT-S01  examined 6 pom.xml
  LT-S04  examined 22 test sources
  LT-P01  examined 0 deployment descriptors
  LT-S08  examined 4 scheduled methods
  LT-S12  examined 1 SPI registry files

CLEAR — no rule matched.
(This means the checks that exist found nothing. It is not a statement about the rest of the codebase.)
```

The `examined N` lines are not decoration. "No findings" reads identically whether a rule
looked at forty files or zero, and the second case is the one that silently stops protecting
you. That is the same failure the catalogue is about — so the scope is printed on every run,
including clean ones.

## Exit codes

| | |
|---|---|
| `0` | every rule returned CLEAR |
| `1` | at least one rule TRIPPED |
| `2` | at least one rule returned **UNKNOWN**, none tripped |
| `3` | bad usage |

**`UNKNOWN` is deliberately non-zero.** Everything here checks for conditions that look exactly
like success while being broken. A checker that exits `0` when it could not reach a conclusion
would be one more instance of the bug. There is no exit code meaning "ran but could not tell,
carry on".

You can see it: `java -jar target/tripwire.jar src/test/resources/replay/LT-S01/profile`
returns `2`, because a literal `<skipTests>true</skipTests>` inside a `<profile>` cannot be
resolved from the file alone.

## The rules that ship today

| | what it catches | how |
|---|---|---|
| **LT-S01** | a literal skip-tests in the build config outranks `-DskipTests=false`; `mvn test` reports success having run 0 tests | parses every `pom.xml`; literal `true` inside the surefire/failsafe configuration. A property reference (`${skipTests}`) or a `<properties>` default is **not** this rule — those stay overridable |
| **LT-S04** | version control ignores the test sources; they compile, run, pass, and vanish on commit | shells out to `git check-ignore -v` for each test source. We do not reimplement ignore semantics — an approximation would produce exactly the confidently-wrong answer this project exists to prevent |
| **LT-P01** | a character above U+FFFF in the deployment descriptor; the container's parser rejects it, every path returns 500, and nothing is logged | scans `web.xml` and `WEB-INF/*.xml` for code points `> 0xFFFF`. BMP symbols (`⛔ ⚠ ★`) and CJK are fine |
| **LT-S08** | a scheduled method whose body has no top-level `catch (Throwable)`; the first throw removes the task from the scheduler for the life of the process, logging nothing | masks strings and comments, then brace-matches the method body. A `catch` that covers only part of the body, or catches narrower than `Throwable`, still trips — an `Error` cancels the task just as permanently |
| **LT-S12** | a class implementing an SPI interface that nobody added to `META-INF/services/<fqn>`; it compiles, it reviews, and `ServiceLoader` never instantiates it | reads each registry file and matches implementations that are in the interface's package or import it by name. ⚠️ A class inheriting the interface through a base class is **not** detected — resolving that needs a type graph |

## Adding a rule

Two things, both enforced by the build:

1. Implement `Rule` and register it in `Rules.all()`.
2. Add `src/test/resources/replay/<ID>/trip/` and `.../clear/` fixtures.

`ReplayTest.every_rule_has_a_tripping_and_a_clean_fixture` fails the build without them.
A rule nobody can reproduce is a claim, not a check — and "I'll add the test later" is how a
claim becomes permanent.

Only `MECHANICAL` rules belong here. The catalogue also carries rules whose check is a
judgement call; those stay documentation. A false positive sends an agent to "fix" correct
code, which costs more than the miss it prevents.

## `.tripwireignore`

One path prefix per line, relative to the scan root; `#` comments. Real projects contain
deliberately broken samples — fixtures, vendored copies, archives — and a tool whose output is
routinely ignored stops being read at all.

**Exclusions are printed on every run.** Silently dropping files is the shape of failure this
catalogue documents; if something is skipped, it has to be visible. This repo's own file
excludes only the replay fixtures, and nothing else.

## Licence

Code in `engine/` is [MIT](LICENSE). The rule catalogue and the article are
[CC BY 4.0](../LICENSE) — see the note in the top-level README.
