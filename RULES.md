# Rules

> 40 silent-failure rules from real production work. Each one cost at least one wasted investigation.
> Languages: English · [简体中文](RULES.zh-CN.md)
>
> **Two kinds of checks.** `MECHANICAL` — one command decides it; safe for a gate.
> `MANUAL` — human judgement only; ⛔ **never** wire these into automated blocking.
> A false positive sends an agent off to "fix" correct code, which costs more than the miss.
>
> **`verified` is when it was last confirmed, not when it was written.** Rules expire —
> a referenced symbol may be gone. Re-check before relying on one.

## Index

| Category | Meaning | Count |
|---|---|---|
| [`SILENT`](#silent--it-ran-nothing-happened) | It ran, nothing errored, nothing happened | 15 |
| [`CONTRACT`](#contract--the-manual-says-something-else) | Implied semantics are the opposite of intuition | 6 |
| [`PLATFORM`](#platform--hard-limits) | Hard runtime/middleware limits | 11 |
| [`ENV_MIS`](#env_mis--environment-mismatch) | Local state invalidates the conclusion | 3 |
| [`FALSE_ALARM`](#false_alarm--the-tool-is-lying-to-you) | A problem that doesn't exist | 4 |
| [`TOPOLOGY`](#topology--you-only-saw-one-path) | N paths, you saw one | 1 |

---

## SILENT · it ran, nothing happened

The shared shape: **the success signal is real, and so is the fact that nothing happened.**
This is the hardest class for an agent to self-check — all it can see is an exit code and `BUILD SUCCESS`.

### LT-S01 A literal skip-tests flag in the build file overrides the command line

- **Symptom**: `mvn test` prints `BUILD SUCCESS` and runs **zero tests**. No report files produced.
- **Check** `MECHANICAL`: the test plugin config contains a **literal** `<skipTests>true</skipTests>`
  or `<skip>true</skip>`.
- **False-positive boundary**: `<skipTests>${skipTests}</skipTests>` (a property reference) is **not**
  this rule — that one *can* be overridden by `-DskipTests=false`.
  The dividing line is *literal vs. property reference*, **not** the default in `<properties>`.
- **Why an AI can't catch it**: POM literals outrank the command line, and `BUILD SUCCESS` is genuinely true.
- **Cost**: in one real case 13 test classes were written, compiled, and "passed" — none had ever executed.
- `verified` 2026-09-04 · Maven 3.x + maven-surefire-plugin

### LT-S02 The test summary counts skipped as passed

- **Symptom**: a class skipped wholesale by a conditional flag reports `pass_rate=100.0`.
- **Check** `MECHANICAL`: summary says `pass_rate == 100` while the raw
  `Tests run: N, Failures: F, Errors: E, Skipped: S` line has `S > 0`.
  **`pass_rate` is only trustworthy when `Skipped: 0`.**
- **Why an AI can't catch it**: the summary is the line it reads; the raw line is buried in build output.
- **Typical trigger**: integration tests against a real database are usually gated behind a system
  property and skipped by default — so "never ran" reads as "ran and green", and the quality gate is void.
- `verified` 2026-09-05

### LT-S03 Filtering by class name runs zero tests when they all live in nested classes

- **Symptom**: when every `@Test` sits inside `@Nested` inner classes, an exact class-name filter runs
  **nothing** — the output doesn't even contain a `Tests run: … in FooTest` line — yet the class still
  counts toward "test classes" and the pass rate stays 100%.
- **Check** `MECHANICAL`: count of `Tests run:.*- in ` lines ≠ expected number of test classes.
- **Variant the line-count misses**: when a class has *both* top-level `@Test` methods *and* `@Nested`
  ones, you get a `Tests run: 1` line while dozens of inner cases never ran.
  ⇒ For every class matching `grep -l '@Nested'`, re-run with a **wildcard** filter (`FooTest*`) and compare counts.
- **Cost**: 31 classes produced 30 result lines; 52 test cases silently never ran.
- `verified` 2026-10-26 · JUnit 5

### LT-S04 Version control ignores the entire test directory

- **Symptom**: tests compile, run, and pass. `git status` never shows them. After committing they're gone.
  **Zero symptoms throughout.**
- **Check** `MECHANICAL`: `git check-ignore -v <test file path>` produces output.
- **Reverse use**: if tests "were definitely written but aren't in the diff", check `.gitignore`
  before suspecting the agent didn't write them.
- **Fix**: `git add -f` for the single file. ⛔ Don't unilaterally edit `.gitignore` — it affects the whole repo.
- `verified` 2026-08-15

### LT-S05 The shell doesn't word-split, and a batch run silently becomes one argument

- **Symptom**: under zsh, an unquoted variable expansion holding many class names is passed as **one**
  argument. The output's first line is `Could not find class: <something>`, followed by dozens of
  perfectly clean class names — **it looks exactly like a normal run list** — ending in `OK (0 tests)`.
- **Check** `MECHANICAL`: the runner's arguments come from an **unquoted** variable expansion under zsh.
- **Why zsh-specific**: bash word-splits unquoted expansions; zsh **does not**. The same line is correct under bash.
- **Fix**: pipe through `xargs` — shell-independent.
- **Reading rule**: the `N` in `OK (N tests)` must be the right order of magnitude.
  `OK (0 tests)` should always make you suspect this rule first.
- `verified` 2026-08-06

### LT-S06 `A...HEAD` excludes uncommitted work, making every "expect no output" check pass

- **Symptom**: a check written as `git diff origin/master...HEAD` while the work is still uncommitted →
  empty diff → every "no output means pass" check passes. **The output is identical to a real pass.**
- **Check** `MECHANICAL`: before any check that extracts added lines from a diff, run
  `git diff <range> | grep -cE '^\+'` and require `> 0`. Zero means the range is wrong —
  ⛔ do not treat that "no output" as a pass.
- **Root cause**: `A...HEAD` compares **committed** content only. Whoever wrote the command assumed a
  post-commit reader.
- ⛔ **Don't substitute your own looser grep for the original command** — in practice a hand-written
  pattern matched text inside a comment and produced a false violation. **Fix the range, copy the pattern verbatim.**
- `verified` 2026-11-01

### LT-S07 A NON_NULL serializer makes null fields disappear entirely

- **Symptom**: `result.put("someField", null)` produces a response where **the key is absent**,
  not `"someField": null`.
- **Check** `MECHANICAL`: serializer config contains `NON_NULL` / a non-default `serializationInclusion`.
- **The real damage**: it makes "config was empty so the method returned null" and "the code was never
  written" **look identical** to the caller — both are "the API is missing a field".
  Status code normal, no exception, compiles fine.
- **Investigation order**: on "endpoint X doesn't return field Y", diff the code **and** trace the
  getter for an early `if (isBlank(cfg)) return null;`.
- `verified` 2026-09-09

### LT-S08 An exception in a scheduled method stops that task forever

- **Symptom**: a scheduled job "didn't run this month", and never runs again. **No log output at all.**
- **Check** `MECHANICAL`: the scheduled entry method has no outermost `catch (Throwable)`.
- **Root cause**: standard `ScheduledThreadPoolExecutor` behaviour — a throwing task is removed from
  the schedule, silently.
- **Fix**: outermost `try/catch(Throwable)` + `log.error`. ⛔ Never rethrow.
- `verified` 2026-09-11

### LT-S09 A `static final` field reading remote config makes hot reload permanently ineffective

- **Symptom**: you change the value in the config centre, publish it, and **nothing happens**.
  It only takes effect after a process restart.
- **Check** `MECHANICAL`: a `static final` field whose initializer calls a config-read API.
- **Knock-on**: such values are often used to decide "only run on this one host", so the config change
  appears to do nothing while the job keeps running on the old host — with a single warn line as evidence.
- `verified` 2026-09-11

### LT-S10 `CONSTANT.equals(maybeNull)` turns "run on one host" into "skip on every host"

- **Symptom**: a job configured to run only on a specific host IP. When the config key is unset the value
  is `null`, `serverIp.equals(null)` is **always false**, so **every host returns early** —
  the job has never run, anywhere.
- **Check** `MECHANICAL`: a host/environment gate compares against a config-sourced value without a null check first.
- **Why it's hard to find**: no exception, no error log, nothing in monitoring. Someone has to notice
  that a thing didn't happen.
- `verified` 2026-09-11

### LT-S11 One scheduling block equals one thread; a long task freezes its neighbours

- **Symptom**: a multi-minute task dropped into an existing scheduling block **blocks the 10-second
  job in the same block for minutes, with zero errors.**
- **Check** `MECHANICAL`: the XML scheduling block has no `scheduler` attribute; or under Spring Boot
  there's neither a custom `TaskScheduler` nor `spring.task.scheduling.pool.size`
  (default pool size is **1** — every scheduled task in the module shares one thread).
- **Fix**: give new timers their own block. Bonus: single-threading guarantees the task never overlaps itself.
- `verified` 2026-09-11

### LT-S12 SPI registration lives in a plain text file; forget to append and the class never loads

- **Symptom**: a newly written initializer / extension compiles, is correct, and **is never called**.
- **Check** `MECHANICAL`: a new implementation of an SPI interface exists but
  `META-INF/services/<fully.qualified.Interface>` wasn't updated.
- **Compounding trap**: these `init()` methods often declare `throws Exception`. If your new
  implementation doesn't catch internally, one malformed config entry prevents **the pre-existing rules
  from registering too** — wiping out capability that already worked.
- `verified` 2026-09-21

### LT-S13 An un-stubbed method under static mocking returns the type default, so you test the wrong branch

- **Symptom**: with `MockedStatic` active on a utility class, any `boolean` method you **didn't** stub
  returns `false`. The test labelled "overseas branch" actually exercises the domestic branch — and passes.
- **Check** `MECHANICAL`: in a test with `MockedStatic<X>`, a method of `X` that the branch depends on
  never appears in a `when(...)`.
- **Idiom**: wrap it in a package-private non-static method and `spy` it, rather than mocking the static utility.
- `verified` 2026-10-12

### LT-S14 A fail-open plugin (exit 0 on error) silently disables every rule you wrote

- **Symptom**: the hook's import fails every time, but the exit code is 0, so nothing is ever blocked.
  You just get one error line per invocation. **None of your rules take effect, and nothing reports it.**
- **Check** `MECHANICAL`: the hook entry point catches an exception and then exits successfully.
- **How to verify**: don't read logs — **construct an operation that should be blocked** and see whether it is.
- `verified` 2026-10-25

### LT-S15 Incremental compilation keys off mtime, so a signature change yields "Nothing to compile"

- **Symptom**: after changing a method signature, `test-compile` reports `BUILD SUCCESS` with a single
  line `Nothing to compile - all classes are up to date`, while test code still calls the old signature.
- **Check** `MANUAL` (requires knowing whether a signature changed): any public/package signature change
  must be verified with a `clean` build. Body-only edits don't need it.
- **Root cause**: incremental checks compare source and class mtimes, which frequently decide "unchanged"
  in multi-module and cloned-workspace setups.
- `verified` 2026-10-19

---

## CONTRACT · the manual says something else

The real semantics differ from what the name suggests. These aren't bugs — you misread the contract.
The consequences are the same.

### LT-C01 The serializer drops keys whose value is null

- **Symptom**: the caller receives a **missing key**, not `null`.
- **Check** `MECHANICAL`: the response goes through a default-config `toString()` / `toJSONString()`,
  and the field table contains anything nullable.
- **Compounding**: with pass-through layers it's dropped **twice** — enabling "write nulls" on the inner
  layer alone isn't enough.
- **Fix**: emit `{}` / `""`, or explicitly flag "is a missing key acceptable?" as an open question.
- `verified` 2026-10-08

### LT-C02 The compatibility layer only has the static overload, not the instance one

- **Symptom**: writing the literal "add a write-nulls feature to `toJSONString`" **doesn't compile** —
  or the implementer quietly drops the feature flag, and null keys vanish again (back to LT-C01).
- **Check** `MECHANICAL`: `javap <Class> | grep <method>` to confirm the overload exists.
  **Don't go from memory of the API.**
- **Root cause**: a post-major-version compatibility shim retains only some signatures.
- `verified` 2026-10-20

### LT-C03 `java_home -v N` means "N or higher"

- **Symptom**: with **no JDK 21 installed at all**, `/usr/libexec/java_home -v 21` returns a JDK 26 path
  and **exits 0**. Using it as a "JDK N is ready" check yields a false positive.
- **Check** `MECHANICAL`: only accept a line in `java_home -V` that **starts with the target version**.
  ⛔ Never use the exit code of `-v N`.
- **Damage**: the entire build silently runs on the wrong JDK, and failures surface as weird
  bytecode-level errors with nothing pointing back here.
- **Note**: `-V` does discover user-level `~/Library/Java/JavaVirtualMachines/`, so a sudo-free
  tarball install is still found.
- `verified` 2026-11-04 · macOS

### LT-C04 A `D` in `git diff A..B` does not mean "should be deleted"

- **Symptom**: copying a directory wholesale from a branch that's hundreds of commits behind onto a
  shared branch deletes hundreds of other people's files. One real run: `+8189 / −15336`, where all
  15,336 deletions were content others had added in the interim.
- **Check** `MECHANICAL`: after any "sync a directory" operation, the deletion column of
  `git diff --numstat` **must be 0**.
- **Root cause**: `D` only means "not present on B". A branch that's behind naturally lacks what was added later.
- **Correct approach**: classify via `--name-status` and port **only `A`**; append to index files rather
  than overwriting; three-way merge anything both sides changed.
- `verified` 2026-10-30

### LT-C05 `git checkout <ref> -- <file>` also writes the staging area

- **Symptom**: you wanted one file in the working tree to look at; you contaminated the commit you were preparing.
- **Check** `MECHANICAL`: fetch single-file content with `git show <ref>:<file> > <file>`,
  ⛔ not `checkout -- `.
- `verified` 2026-10-30

### LT-C06 IDs round-trip through a JSON cache as Integer, not Long

- **Symptom**: written as `Long`, read back by `parseObject` as `Integer`, so
  `someLong.equals(obj.get("id"))` is **always false**.
- **Check** `MECHANICAL`: code paths that look up by ID in a JSON cache (or build a `Set<Long>`)
  using a bare `get(...)` instead of `getLong(...)`.
- **Fix**: always `getLong`, in assertions too.
- `verified` 2026-10-08

---

## PLATFORM · hard limits

Runtime or middleware limits. They fail **all at once**, and the failure point is far from the symptom.

### LT-P01 The container's XML parser only handles 3-byte UTF-8; one emoji takes the whole app down

- **Symptom**: any character beyond the BMP (U+10000 and up, lead byte `0xF0` — i.e. most emoji) in
  `web.xml` → `CharConversionException: illegal utf8 encoding at 0xf0` → the webapp never configures →
  **every path returns 500** (including `/favicon.ico` and paths that don't exist) →
  **not one line of application log** (the logging framework never initialises).
- **Check** `MECHANICAL`:
  `python3 -c "print([c for c in open('web.xml',encoding='utf-8').read() if ord(c)>0xFFFF])"` is non-empty.
- **Why it's so hard to locate**: build succeeds, image pushes, container reports `Up`, the archive
  contains every class and resource, and **full local Spring context tests are green**.
  Nothing points at this file. One real incident ran **19 hours**.
- **Safe to use**: BMP symbols like `⛔` U+26D4, `⚠` U+26A0, `★` U+2605, and CJK text.
- **Scope**: only the descriptor the container itself parses. Framework-loaded XML is unaffected.
- `verified` 2026-10-16 · Resin 4.0.38

### LT-P02 With a catch-all MAX partition present, `ADD PARTITION` always fails

- **Symptom**: when the table ends with `VALUES LESS THAN MAXVALUE`,
  `ALTER TABLE … ADD PARTITION` returns error **1481**.
- **Check** `MECHANICAL`: `SHOW CREATE TABLE` contains `MAXVALUE` while the script uses `ADD PARTITION`.
- **Fix**: `REORGANIZE PARTITION p_max INTO (missing partitions…, new p_max)`.
  **Forgetting to recreate `p_max` leaves the table with no catch-all** — and that omission doesn't error.
- `verified` 2026-09-21 · MySQL

### LT-P03 A sparse gap quietly destroys "one partition per month"

- **Symptom**: with `p203012` present, creating `p203103` directly means that partition absorbs
  **three months** of data. MySQL says nothing.
- **Check** `MECHANICAL`: the new partition boundary isn't **contiguous** with the current maximum.
- **Fix**: generate contiguously from "current max + 1" rather than only filling the ones you need.
- `verified` 2026-09-21 · MySQL

### LT-P04 A JSON column normalises on insert; you can't get the original bytes back

- **Symptom**: a payload stored in a `JSON` column has its keys reordered, whitespace stripped, and
  duplicate keys collapsed to the last one. **Recomputing a signature or hash for comparison becomes
  impossible** — and that investigative path closes silently.
- **Check** `MECHANICAL`: column type is `JSON` and its purpose includes "preserve verbatim" /
  "recompute signature" / "byte-for-byte comparison".
- **Fix**: use `MEDIUMTEXT`. On 8.0, `JSON_EXTRACT(col,'$.x')` casts implicitly, so query power is retained.
- `verified` 2026-09-21 · MySQL 8.0

### LT-P05 `TEXT` tops out at 65,535 bytes — exactly one byte short of 64 KB

- **Check** `MECHANICAL`: column type `TEXT` with a stated 64 KB business limit.
- **Fix**: `MEDIUMTEXT`. This is frequently chosen by mistake as the "step back" from LT-P04.
- `verified` 2026-09-21 · MySQL

### LT-P06 `DEFAULT NULL` on a BLOB/TEXT column errors with 1101

- **Symptom**: a tool (or an agent) "helpfully" adds `DEFAULT NULL` and the DDL stops working.
- **Check** `MECHANICAL`: `CREATE TABLE` has `DEFAULT NULL` on a BLOB/TEXT-family column.
- **Fix**: write only `NULL`. ⛔ Don't let a formatter "complete" it.
- `verified` 2026-09-21 · MySQL

### LT-P07 A Base64 primary key needs explicit `ascii_bin`, or two distinct keys collide

- **Symptom**: the default `utf8mb4` `_ci` collation **ignores case** when enforcing key uniqueness,
  so two genuinely different Base64 keys are judged identical → the new row hits a duplicate key →
  the idempotency branch **returns someone else's record**. No error, no log.
- **Check** `MECHANICAL`: a primary/unique key column holds Base64 or case-sensitive identifiers
  without an explicit `COLLATE *_bin`.
- `verified` 2026-09-21 · MySQL

### LT-P08 `lock_wait_timeout` defaults to 31,536,000 seconds — one year

- **Symptom**: once a DDL enters the metadata-lock queue, **every** scheduled task sharing that
  connection pool stops silently, instead of timing out with an error.
- **Check** `MECHANICAL`: `SELECT @@lock_wait_timeout`; application paths that run DDL don't first
  `SET SESSION lock_wait_timeout = 10` on the same connection.
- **Compounding**: if the module's scheduler pool size is 1 (see LT-S11), the blast radius is the whole module.
- `verified` 2026-09-21 · MySQL 5.6

### LT-P09 Older mock frameworks cannot stub static methods

- **Symptom**: the test can't be written — or, to work around it, the logic under test gets rewritten
  into a "test-friendly" copy nobody calls.
- **Check** `MECHANICAL`: `mockito-core < 3.4` with no PowerMock ⇒ statics can't be stubbed.
- **Fix**: design the decision logic as **private static pure functions** and test those.
  ⚠️ Don't write an equivalent copy on another layer that "tests itself" — that tests the copy, not the implementation.
- `verified` 2026-10-12

### LT-P10 The file-writing tool turns an escape sequence into a real 0x00 byte

- **Symptom**: documenting a "NUL separator" by writing the full escape sequence causes the tool to
  **interpret** it and write a real `0x00`. `file` then reports `data`, and **`grep` under a UTF-8 locale
  skips the entire file as binary** — so the document becomes invisible to every grep-based check and
  looks like it was never written.
- **Check** `MECHANICAL`: run `file -b <path>` after writing any document; expect `UTF-8 text`.
  `data` means you hit this.
- **Locating it**: read bytes in Python (`d.count(b'\x00')` plus per-line `repr`).
  ⛔ Not grep — it skips binaries outright.
- `verified` 2026-09-09

### LT-P11 Rate-limit thresholds are per-instance, and cluster mode degrades silently

- **Symptom**: a threshold configured as "site-wide QPS" is actually **per-instance × instance count**.
  Setting `clusterMode=true` without a cluster server **silently degrades to per-instance**, with no error.
- **Check** `MECHANICAL`: no cluster-server artifact among the dependencies while rules set `clusterMode=true`.
- **Get the production instance count before setting any threshold.**
- `verified` 2026-09-21 · Sentinel

---

## ENV_MIS · environment mismatch

Local, container, or toolchain state invalidates your conclusion. The symptom usually points somewhere unrelated.

### LT-E01 The tool's working directory persists across calls, and commands land in the wrong repo

- **Symptom**: in a "one main repo plus several independent git repos" layout, a single `cd` means every
  later `git stash pop` / build / relative path lands in the **wrong repository**.
  One real incident popped an unrelated five-month-old stash and produced conflicts.
- **Check** `MECHANICAL`: a git command without `-C <absolute path>`, or a build command without a
  `cd` in the same invocation.
- **Knock-on**: the same cause makes "re-run just this one test class" produce
  `Could not find class` → `OK (0 tests)` — **symptomatically identical to LT-S05**. Don't confuse them.
- ⛔ **If a stash popped into the wrong repo**: the conflict keeps the stash entry, so confirm it's still
  there first. **Never** clean up with `git checkout -- .` — it wipes the user's own uncommitted work too.
- `verified` 2026-08-09

### LT-E02 A single SNAPSHOT slot shared across branches: "does not override abstract method" is a baseline mismatch, not a code bug

- **Symptom**: compilation reports `XxxServiceImpl is not abstract and does not override abstract method yyy()`.
  **It looks like missing code. It's actually that the dependency jar in your local repository came from
  a different branch than your source.**
- **Check** `MECHANICAL`: decide whether interface and implementation are self-consistent with
  `git grep -c <method> <baseline branch> -- <api module> <service module>`. ⛔ Don't edit the implementation.
- **Root cause**: `1.0.0-SNAPSHOT` is a **single slot** shared by every branch; last publisher wins.
  The convention is "feature branches install locally, never publish" — the moment someone breaks it,
  code pulled from another branch fails to compile on day one.
- **⛔ The most dangerous move** is "I'll just install the api locally" — that removes members other
  modules are currently using. Before installing, compute the delta: **which members disappear, and who calls them.**
  A caller query returning *empty* and one returning *no source root was scanned* are different things;
  the latter must never be treated as safe.
- `verified` 2026-09-09

### LT-E03 `all_proxy=socks5://` makes a build subprocess report a missing package, blaming the wrong package

- **Symptom**: `pip install` on a package that needs a source build fails with
  `ImportError: Using SOCKS proxy, but the 'socksio' package is not installed` → `metadata-generation-failed`.
  **The error names the package being installed, so it looks like that package's problem. It's the proxy.**
- **Check** `MECHANICAL`: `env | grep -i '^all_proxy\|^ALL_PROXY'` is non-empty with a `socks5://` value.
- **Fix**: `env -u ALL_PROXY -u all_proxy <original command>`.
  ⛔ **Don't also drop the http(s) proxy** — with no proxy at all, even the package index fails to resolve.
- `verified` 2026-10-25

---

## FALSE_ALARM · the tool is lying to you

A tool reports a problem that doesn't exist, or claims it did something it didn't.
This class is the most expensive: you go fix something that was never broken.

### LT-F01 Node's global `fetch` ignores `http_proxy`, and the error never mentions proxies

- **Symptom**: a domain `curl` reaches fine makes every node tool report `fetch failed`.
  The message contains **no mention of a proxy**, so it reads as "the service is down".
- **Check** `MECHANICAL`: for the same URL, `curl -o /dev/null -w "%{http_code}"` returns 200 while
  `node -e "fetch('<url>')"` throws `fetch failed` ⇒ this rule.
- **Note**: `NODE_USE_ENV_PROXY` only exists in Node 24; setting it on Node 22 does nothing.
- `verified` 2026-10-25 · Node 22.19

### LT-F02 A mutant file named differently from its public class fails to compile, falls back to the original, and "survives"

- **Symptom**: in offline mutation testing, saving the mutated copy as `M1_OrderController.java` makes
  `javac` fail with "class OrderController is public, should be declared in a file named OrderController.java",
  leaving the output directory empty. The runner's output filter hid that error, so the classpath fell
  back to the **original** class → **every mutant "survived"**. The apparent conclusion was
  "the tests catch nothing"; the truth was that no mutation ever took effect.
- **Check** `MECHANICAL`: mutant filename ≠ `<PublicClassName>.java`, or the compile step's stderr is filtered.
- **Meta-rule**: **"all mutants survived" should first be treated as broken tooling** (compile failure,
  classpath order, class not shadowed) — not as "the tests are worthless".
- **Fix**: one directory per mutant, `mut/M1/<OriginalName>.java`; don't filter compiler output.
  After fixing, all 9 mutants in the same batch were killed by their corresponding tests.
- `verified` 2026-09-04

### LT-F03 The code-graph tool falsely claims it already sent you the source

- **Symptom**: the response says *"Already sent earlier in this conversation: `<file>` L38-86 … Do NOT
  Read this file"* — when the session had **never** received it. The lines you actually need are omitted
  as "already sent", leaving a disconnected fragment, **with no error of any kind**.
- **Check** `MANUAL`: when you see that claim, confirm the source really did appear in your context.
  If it didn't, just read the file.
- **Boundary**: the tool's call graph, blast radius, and caller lists remain trustworthy.
  Only the "you already have the source" assertion is not.
- `verified` 2026-10-20

### LT-F04 A single-file compile pre-check reports errors that a full build doesn't have

- **Symptom**: `javac -sourcepath src/main/java` on one file reports two "pre-existing errors",
  attributed to "the local dependency jar being older than production".
  The full build then reports **BUILD SUCCESS — neither error exists.**
- **Check** `MANUAL`: a single-file javac lacks the full classpath, can't resolve symbols from
  `provided` or transitive jars, and reports "not found" as a code error.
- **Rule**: treat single-file pre-checks as a **coarse filter only** (unbalanced brackets, obvious typos).
  **Any "doesn't compile" conclusion must be confirmed by a full build.**
- `verified` 2026-08 · JDK 8

---

## TOPOLOGY · you only saw one path

There are N paths or N writers; you (or the agent) saw one.

### LT-T01 Porting a feature by grepping for keywords systematically misses in-place edits

- **Symptom**: porting a feature by grepping the source repository for feature keywords
  **misses an entire class of changes.**
- **Two kinds of hunk**:
  1. **Named** — new classes, methods, fields, tables. Keyword grep finds all of them; in one audit, zero missed.
  2. **Unnamed** — a couple of cache-eviction lines added inside a method that already existed, or
     `type < 2000` becoming `type != 16 && type < 2000`.
     **The hunk contains no feature keyword at all.** Guaranteed miss.
- **The second kind has zero symptoms**: it compiles, logs nothing, writes the database normally.
  Only a cache that doesn't refresh or a condition that doesn't match — surfacing when some endpoint
  returns one field short. In one case that was over a week after the port.
- **Check** `MECHANICAL`: find the merge point, take `git diff <merge>^1 <merge>`, and substring-match
  every added line (whitespace-stripped) against the target repository. ⛔ Do not grep by keyword.
- **Know your false positives** (in one audit, 205 of 5,376 added lines were "not found" but only
  about 10 were real misses): subsystems that don't exist on the target, table-name case adaptation,
  i18n strings, and dead code on the source side.
- `verified` 2026-09-02

---

## Pending (no mechanical check yet)

Real traps, currently human-judgement only. ⛔ Not for gates. Listed so they aren't lost.

| ID | One line | Why it can't be mechanized yet |
|---|---|---|
| LT-X01 | The container unescapes `\\` to `\` in config files — validating a regex against the **file text** wrongly concludes "dead rule" | Deciding whether a rule is live requires a real request; static analysis can't |
| LT-X02 | The URL field in the log platform is stored **after** urldecoding — don't use it to judge whether the client percent-encoded | Decoded text is byte-identical to "never encoded"; indistinguishable |
| LT-X03 | Typed getters on optional fields **throw** on a non-numeric value instead of returning null — one poison message redelivers forever | Requires judging whether a field is optional *and* from an untrusted source |
| LT-X04 | Partition pruning silently stops applying across a type mismatch — full scan, no error | Needs an execution plan to confirm |

---

## Self-consistency

This list follows the rules it advocates:

- **Every entry carries a `verified` date**, because rules expire.
- **`MECHANICAL` and `MANUAL` are strictly separated**, and `MANUAL` entries never gate anything.
- **One known-expired sample exists**: the corpus contains a rule that upstream fixed after it was
  written. ⇒ Any checker built on this list needs a self-test for *does the symbol this rule
  references still exist* — otherwise the rules quietly become noise.
