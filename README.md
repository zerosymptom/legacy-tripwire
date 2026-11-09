# Legacy Tripwire

**40 silent-failure traps that AI coding agents hit over and over in legacy systems — each with a mechanical check.**

Languages: English · [简体中文](README.zh-CN.md)

---

## What this is

Not a framework. Not a tool. **There is no code here yet.**

It is a list: [**RULES.md**](RULES.md) — 40 rules, each with
*symptom / mechanical check / why an AI can't catch it / false-positive boundary / verified date*.

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

## Scope and limits

- ✅ What's published is the **pattern**, not the scene. Module names, schema names,
  config keys and business semantics have all been stripped.
- ⛔ No "bug in version X of framework Y" entries — those are solved by reading the docs once.
  This list is about situations where **the feedback signal itself lies**.
- ⛔ Not exhaustive. It's one person, one system. **The sample is biased** and no amount of
  effort removes that.

## License

Documentation under [CC BY 4.0](LICENSE). Use it freely; just credit the source.

Issues welcome — especially new silent-failure traps. Bring the **check** and the
**false-positive boundary**; a symptom alone is much less useful.
