# tripwire — the executable part

Seven of the rules in [`RULES.md`](../RULES.md), as checks you can run.
**JDK 17 or newer** (17, 21 and 26 are tested), no runtime dependencies, one jar.

> On JDK 8 or 11 the build stops with an explicit version message. Without that guard
> Maven reports *"No compiler is provided… perhaps you are running on a JRE"*, which is
> untrue and sends you after the wrong problem — the failure mode this repo is about.

```bash
cd engine
mvn -q package
java -jar target/tripwire.jar /path/to/a/checkout
```

`mvn -q package` alone is enough to see it work — the build runs the replay cases.

## What it prints

```
tripwire  /path/to/a/checkout
rules: 7   findings: 0
  LT-S01  examined 6 pom.xml
  LT-S04  examined 22 test sources
  LT-P01  examined 0 deployment descriptors
  LT-S08  examined 4 scheduled methods
  LT-S12  examined 1 SPI registry files
  LT-S09  examined 180 java sources
  LT-S11  examined 4 scheduled methods, 61 files that could size the pool

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
| **LT-S08** | a scheduled method whose body has no top-level `catch (Throwable)`. ⚠️ The consequence this rule used to state — removed from the scheduler for good, logging nothing — is **retracted for Spring `@Scheduled`, which is what this check matches**; see the retraction below | masks strings and comments, then brace-matches the method body. A `catch` that covers only part of the body, or catches narrower than `Throwable`, still trips |
| **LT-S12** | a class implementing an SPI interface that nobody added to `META-INF/services/<fqn>`; it compiles, it reviews, and `ServiceLoader` never instantiates it | reads each registry file and matches implementations that are in the interface's package or import it by name. ⚠️ A class inheriting the interface through a base class is **not** detected — resolving that needs a type graph |
| **LT-S09** | a `static final` field initialised from the config service; changing the value in the UI does nothing until a restart, and nothing says so | matches a config-shaped receiver plus a value-returning read. ⚠️ `getInstance` / `getBean` are excluded — freezing a singleton is correct, and including them produced 42 findings against code that was fine |
| **LT-S11** | two or more scheduled methods with an unsized pool; Spring's default `TaskScheduler` runs **one** thread, so one slow run delays all the others | counts scheduled entry points, then looks for any way the pool is sized. One finding per checkout, not per method. Never fires on a single job |

## What they found on a real estate

Run across **23 modules** of a commercial Java estate (four product lines, roughly a decade old),
2026-11-12:

| | findings | distinct code locations | verdict |
|---|---:|---:|---|
| **LT-S01** | 17 | 17 | builds that would report success having run zero tests |
| **LT-S04** | 12 | 12 | test files that compile, pass, and vanish on commit — all in one module |
| **LT-S08** | 55 | **34** | 43 of the 55 have no `try` at all; the other 12 catch narrower than `Throwable` |
| **LT-P01** | 0 | 0 | — |
| **LT-S09** | 28 | 28 | config values frozen at class load (gj + kx only; the sweep timed out before vv) |
| **LT-S11** | 5 | 5 | one per module — up to 11 scheduled methods sharing a single thread |
| **LT-S12** | 0 | 0 | ⚠️ see below |

20 of 23 modules tripped something. **Zero `UNKNOWN`.** All 55 LT-S08 findings were re-derived by
a second, independently written checker: **55/55 agreed, no false positives**.

⚠️ **Retracted in part, 2026-11-13 — what those two checkers agreed on was the pattern, not the
consequence.** LT-S08 matches Spring `@Scheduled` methods, and Spring does **not** cancel a
repeating task that throws: `ThreadPoolTaskScheduler` / `ConcurrentTaskScheduler` wrap it via
`TaskUtils.decorateTaskWithErrorHandler(task, null, true)` with `LOG_AND_SUPPRESS_ERROR_HANDLER`,
so the throw is caught inside `DelegatingErrorHandlingRunnable` and never reaches the executor.

Measured, Spring 5.3.39 / JDK 21, a `@Scheduled(fixedDelay = 300)` throwing on every invocation,
observed 3 seconds:

| scheduling mechanism | executions in 3s | logged? |
|---|---:|---|
| Spring `@Scheduled(fixedDelay)` | **10** | yes — `SEVERE` + stack trace, every time |
| bare `ScheduledExecutorService.scheduleWithFixedDelay` | **1** | no |

The "removed for the life of the process, in silence" behaviour belongs to the bare executor.
So all LT-S08 counts in this file — **55 here and 12 on the public repositories** — are
pattern-present counts whose attached consequence does not apply. The methods really do lack a
top-level guard, and guarding plus logging is still the better shape, but ⛔ these numbers must
not be quoted as jobs that stopped. Re-pointing the check at bare `ScheduledExecutorService`
is open work and has not been done.

A second checker reproducing the first does not test the claim; it tests the match. That gap is
process-level, not specific to this rule.

⚠️ **Two numbers, not one.** The 55 and the 34 differ because three product lines ship copies of
the same files; each copy is a separate running service, so 55 is the operational count and 34
the distinct-defect count. Quoting 55 as "distinct bugs" would be inflation, and the inflation
is 62%.

⚠️ **LT-S12 has never matched outside its own fixtures.** Zero hits across all 23 modules. The
pattern it encodes is real — it cost a real investigation, which is why it is in the catalogue —
but **the check itself is unproven in the field**, and it should not be read as carrying the same
weight as LT-S01. (It was compared against LT-S08 here before LT-S08's own consequence was
retracted — LT-S08 is no longer the right benchmark.) LT-P01 also scored zero here; that one has a known field incident
behind it, the condition simply is not present in these modules today.

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

## And on ten public repositories

The numbers above come from one commercial estate, described generically because it has to be.
Nobody can check them. So the same seven checks were run against **ten public Java
applications** — pick any row, clone at that commit, and get the same answer in two minutes.

Selection was fixed before the run: deployable applications (not libraries — five of the seven
rules look for application patterns, so a sample of libraries would score zero and mean
nothing), Maven, maintained, with four projects first committed 2012–2015 as an age contrast.

| repository | commit | S01 | S04 | P01 | S08 | S12 | S09 | S11 |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| [PowerJob/PowerJob](https://github.com/PowerJob/PowerJob) | `d92ad66277` | 0 | 0 | – | **3** | – | 0 | 0 |
| [YunaiV/ruoyi-vue-pro](https://github.com/YunaiV/ruoyi-vue-pro) | `3e13d8ea90` | 0 | 0 | – | **3** | 0 | 0 | 0 |
| [apache/dolphinscheduler](https://github.com/apache/dolphinscheduler) | `bae5e00691` | 0 | 0 | – | – | – | **7** | – |
| [apache/zeppelin](https://github.com/apache/zeppelin) | `95b0ca5d2f` | 0 | 0 | 0 | – | – | 0 | – |
| [codecentric/spring-boot-admin](https://github.com/codecentric/spring-boot-admin) | `dbb8872310` | 0 | 0 | – | – | – | 0 | – |
| [elunez/eladmin](https://github.com/elunez/eladmin) | `646c58c30f` | **2** | 0 | – | – | – | 0 | – |
| [linlinjava/litemall](https://github.com/linlinjava/litemall) | `a1ef964a71` | 0 | 0 | – | **4** | – | 0 | **1** |
| [macrozheng/mall](https://github.com/macrozheng/mall) | `dcaa93b315` | 0 | 0 | – | **1** | – | 0 | – |
| [openmrs/openmrs-core](https://github.com/openmrs/openmrs-core) | `6a2048e644` | 0 | 0 | 0 | **1** | 0 | 0 | – |
| [spring-projects/spring-petclinic](https://github.com/spring-projects/spring-petclinic) | `500158f732` | 0 | 0 | – | – | – | – | – |
| **total** / repos with the precondition | | **2**/10 | **0**/10 | **0**/2 | **12**/5 | **0**/2 | **7**/9 | **1**/3 |

**`–` means the precondition is absent**, not clean: no `@Scheduled` in the tree, no
`META-INF/services`, no deployment descriptor. Those cells carry no information and are not
counted. `**n**` is a finding.

### Three that were checked by hand against the source

- **`elunez/eladmin`** (21.9k stars) — `<skipTests>true</skipTests>` sits inside the
  surefire configuration in `eladmin-system/pom.xml`. `mvn test` reports success having run
  zero tests, and `-DskipTests=false` does not override it.
- **`apache/dolphinscheduler`** — `private static final String AZURE_CLIENT_ID =
  PropertyUtils.getString(...)`, and three more beside it. Cloud credentials frozen at class
  load; rotating them in configuration changes nothing until a restart, and nothing says so.
- **`linlinjava/litemall`** — `@Scheduled` `checkCouponExpired()` queries the database with no
  top-level `catch (Throwable)`. One throw and coupon expiry stops running for the life of the
  process.

### The result that matters most is a zero

**LT-S04 found nothing, in all ten repositories, every one of which has test sources.** That is
a zero with the precondition present ten times over — it carries information. In the commercial
estate the same check found **12 test files** that compile, pass, and disappear on commit.

So this rule is not describing Java. It is describing a particular engineering situation, and
publishing the zero is what makes the rest of the catalogue narrower and more honest.

### What was predicted, and what was wrong

The interpretation rules were written and hashed **before the run**, including a prediction, so
that nothing here could be decided after seeing the numbers. Two predictions were wrong:

- *"LT-S09's precondition will be rare — open source rarely uses a config service."* It was
  present in **9 of 10**.
- *"LT-S11 will fire more widely than LT-S08."* It fired **once**; LT-S08 fired twelve times.

Both errors ran the same way: the preconditions were far more common than expected. The
overall shape — "two or three untested, one or two clean, two or three firing" — was also
wrong; **nothing** was untested.

### ⚠️ What this cannot support

- **n = 10.** No significance is claimed and none should be read in.
- The four older projects produced 1 finding and the six newer ones 21, which is the
  **opposite** of the "legacy code is worse" direction. ⛔ Do not read that as an age effect:
  the groups differ in governance, project type and review culture as well as age, and nothing
  here isolates any of them.
- **LT-S12 has now scored zero across 33 repositories** (23 commercial + these 10). It has
  never matched outside its own fixtures.
- Findings are not bugs until someone reads them. Three were checked by hand; the other
  nineteen were not.

## The one we wrote and did not ship

`LT-S10` is implemented, has both fixtures, and passes its tests. It is **not registered**, so
it never runs.

The check is correct. Pointed at the same 23 modules it found **771 instances across 538
files** — every one a genuine case of the pattern it looks for: a config value defaulting to
null feeding a "only run on this host" gate, where one unset key takes out every host at once
rather than one.

And that is exactly why it does not ship. The idiom is used deliberately, it works wherever the
key is set, and 771 findings against code that is fine today is not a gate — it is a wall of
text someone learns to scroll past. The rule right above this file says a false positive costs
more than a miss, because an agent reading one goes and changes correct code. **Seven hundred
of them would be seven hundred edits nobody asked for.**

Density turned a true positive into noise. It stays in `RULES.md` as `MANUAL`, where a human
can weigh it, and the code stays here so the next person does not rediscover it from scratch.

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
