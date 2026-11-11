# Legacy Tripwire

**40 silent-failure traps that AI coding agents hit over and over in legacy systems — each with a mechanical check.**

[![engine](https://github.com/zerosymptom/legacy-tripwire/actions/workflows/engine.yml/badge.svg)](https://github.com/zerosymptom/legacy-tripwire/actions/workflows/engine.yml)

Languages: English · [简体中文](README.zh-CN.md)

---

## What this is

Mostly a list: [**RULES.md**](RULES.md) — 40 rules, each with
*symptom / mechanical check / why an AI can't catch it / false-positive boundary / verified date*.

**Three of them are executable checks** ([`engine/`](engine/) — Java 17, no runtime dependencies).
The rest stay documentation: some are not written yet, and some **should never be mechanised**
(see "How to use it").

All of them come from a real legacy Java system (Spring MVC + Dubbo + MyBatis,
MySQL 5.6 and 8.0 side by side, some modules still on `source=1.7`)
after a year of AI-assisted development. **Every rule cost someone — human or agent — at least one wasted investigation.**

## Why "silent"

These 40 share one shape, and that shape is exactly why an AI can't catch them:

> **The success signal is real. So is the fact that nothing happened.**

- `BUILD SUCCESS` is real — it just ran **zero tests**, because the build file hardcodes a literal
  skip flag that the command line cannot override ([LT-S01](RULES.md#lt-s01-a-literal-skip-tests-flag-in-the-build-file-overrides-the-command-line))
- The clean list of class names above `OK (0 tests)` is real — not one of them ran
  ([LT-S05](RULES.md#lt-s05-the-shell-doesnt-word-split-and-a-batch-run-silently-becomes-one-argument))
- "Check command produced no output, therefore it passed" is real —
  the diff range simply didn't contain your changes ([LT-S06](RULES.md#lt-s06-ahead-excludes-uncommitted-work-making-every-expect-no-output-check-pass))
- The tests were written, ran, passed, and got committed — except `.gitignore` excluded the whole
  test directory, so they silently ceased to exist ([LT-S04](RULES.md#lt-s04-version-control-ignores-the-entire-test-directory))
- Build green, image pushed, container `Up`, full local context tests green —
  but one emoji in a config file meant the app never started, every path returned 500,
  and **there was not a single log line** ([LT-P01](RULES.md#lt-p01-the-containers-xml-parser-only-handles-3-byte-utf-8-one-emoji-takes-the-whole-app-down))

What the AI can observe is precisely the part that's true.
**It isn't insufficiently smart. The feedback signal itself is lying.**

## Nine categories

| Category | Meaning | Count |
|---|---|---|
| `SILENT` | It ran, nothing errored, nothing happened | 15 |
| `CONTRACT` | The implied semantics of an API or command are the opposite of intuition | 6 |
| `PLATFORM` | A hard runtime/middleware limit — touch it and everything fails at once | 11 |
| `ENV_MIS` | Local / container / toolchain state invalidates your conclusion | 3 |
| `FALSE_ALARM` | A tool reports a problem that doesn't exist, or claims it did something it didn't | 4 |
| `TOPOLOGY` | There are N paths; you only saw one | 1 |
| `SEM_INV` | A method or config name means the opposite of what it says | private corpus |
| `NO_SUBSYS` | A subsystem you assumed exists simply doesn't on this product line | private corpus |
| `DEAD_SRC` | You're reading a source that stopped being updated long ago | private corpus |

Every sample in the last three is tied to specific business logic and can't be de-identified,
so only the category definitions are published.

## How to use it

**Today:** as a checklist. Before accepting anything an agent hands you, walk the list —
especially for any conclusion shaped like *"it ran"*, *"all green"*, or *"no output, so it passed"*.

**Two kinds of checks. Do not mix them:**

- `MECHANICAL` — one command decides it. Safe for CI and commit gates.
- `MANUAL` — needs a human. ⛔ **Do not** wire these into automated blocking.
  A false positive sends the agent off to "fix" code that was already correct —
  which costs more than the miss.

That asymmetry is specific to AI-assisted work. A human reading a false positive from a linter
shrugs and moves on. **An agent reading one will actually go change the code.**

**Every rule carries a `verified` date, because rules expire.** Re-check before relying on one —
the corpus already contains a rule that upstream fixed *after* it was written down.

## Where the numbers come from

| | |
|---|---|
| Raw corpus | 1,829 AI collaboration notes (58 personal + 1,771 across several agents) |
| Sample | 100 (all 58 personal + 42 sampled) |
| Method | Human and model labeled **independently**, then reconciled entry by entry |
| Four axes | ① is it a trap ② which category ③ mechanically assertable ④ portability |
| Mechanically assertable | **20.7% – 60.3%** (lower and upper bound from the two independent passes) |
| Pre-registered kill switch | below 15% → abandon the project |

The method, the disagreements, and one place where **my own root-cause diagnosis was
empirically overturned** are written up in [**this article**](docs/article.md).

⚠️ The two passes differ by nearly 3×, and **the bottleneck is not "can it be mechanized" but
"what counts as a trap"** — restricted to entries both passes called a trap,
the mechanization rates differ by only 7 percentage points. That finding is more useful than the ratio itself.

## Then I measured against a public baseline

The second study used a different corpus: not the after-the-fact notes, but **raw session
transcripts** — 30 sessions, 185 developer turns, labeled blind first and mapped to a taxonomy
second, then compared against a public study (20k+ sessions across 1,600+ repositories,
seven symptom categories plus a per-turn misalignment baseline).

| | |
|---|---|
| In-session misalignment rate (public definition) | **0.057** vs public baseline **0.051** — on par |
| Rework rate (what the public definition cannot see) | **0.205** |
| **Share of failures that surface no symptom at the time** | **≈ 2/3** |
| Of the late-surfacing failures, share the seven categories **cannot hold** | **42%** |
| Labeling agreement / Cohen's κ | 81.3% / **0.296** |

**Two findings you can take away directly:**

1. 🔴 **Treat "0.83 inter-annotator agreement" claims with care — check which statistic it is.**
   Our raw agreement was 81.3%, essentially matching the 0.83 the public study reports;
   but κ was only 0.296. On a heavily imbalanced binary task, raw agreement is a very
   forgiving metric — and that study does not report κ.
2. 🔴 **The public taxonomy has an empty slot.** It has "misreports the status of its own work,"
   but no category covers **fabricating a fact inside the produced content**.
   The opening story in this repo's article originally cited a fabricated duration
   before being corrected — claiming done-when-not-done and inventing a number that never
   happened are two different failures.

The 42% that does not fit shares one root cause: **the information is simply not anywhere the
agent can see it** — rendered UI output, team-internal conventions and ownership, another
team's implementation in a different repository, a deployment plan that was correct when
written and stale two months later. That boundary is exactly what this repo aims at.

⚠️ Limits are in [section 9 of the article](docs/article.md). Two of them must be read together:
**the second annotator was the very AI being evaluated** (hence zero cases of "misreports its own
status" — judged a method defect, not an achievement), and **the sampling script is not
reproducible** (the session pool keeps changing; a rerun yields a different corpus, with no error).

## The part that runs

Three of the forty are **executable checks**, in [`engine/`](engine/):

```bash
cd engine && mvn -q package
java -jar target/tripwire.jar /path/to/your/checkout
```

| | what it catches |
|---|---|
| **LT-S01** | a literal skip-tests in the build config outranks the command line — `mvn test` reports success having run 0 tests |
| **LT-S04** | version control ignores the test directory — tests compile, run, pass, then vanish on commit |
| **LT-P01** | a character above U+FFFF in the deployment descriptor — the container's parser fails, every path returns 500, nothing is logged |

Two deliberate design choices:

- **Exit code `2` means "could not tell", and it is non-zero.** Everything here checks for
  conditions that look exactly like success. A checker that exits `0` when it could not reach a
  conclusion would be one more instance of the bug. There is no code meaning "ran but could not
  tell, carry on".
- **Every run prints how many files each rule examined**, clean runs included. "No findings"
  reads identically whether a rule looked at forty files or zero — and the second case is the
  one that silently stops protecting you.

⛔ Only `MECHANICAL` rules go in the engine; the judgement calls stay documentation. A false
positive sends an agent to "fix" correct code, which costs more than the miss it prevents.
A new rule must ship with one fixture that trips it and one that does not, or the build fails.

## Scope and limits

- ✅ What's published is the **pattern**, not the scene. Module names, schema names,
  config keys and business semantics have all been stripped.
- ⛔ No "bug in version X of framework Y" entries — those are solved by reading the docs once.
  This list is about situations where **the feedback signal itself lies**.
- ⛔ Not exhaustive. It's one person, one system. **The sample is biased** and no amount of
  effort removes that.

## License

- **The rule catalogue and the article**: [CC BY 4.0](LICENSE). Use it freely; just credit the source.
- **Code under `engine/`**: [MIT](engine/LICENSE).

⚠️ The split is deliberate — CC BY licences are not meant for source code.

Issues welcome — especially new silent-failure traps. Bring the **check** and the
**false-positive boundary**; a symptom alone is much less useful.
