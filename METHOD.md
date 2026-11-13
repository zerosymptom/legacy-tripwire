# How the catalogue was built

Languages: English · [简体中文](METHOD.zh-CN.md)

Six steps, in order, with what went wrong at each. The failures are the useful part — every
one of them produced a confident, plausible, wrong answer first.

You need: a corpus of your own agent sessions, a text editor, and the willingness to publish a
result that makes your project smaller.

---

## 1. Sample — and write down what you sampled

Pull sessions from your own logs. Three exclusions, all of which cost something to learn:

- **Exclude anything a previous round already labelled.** Otherwise you measure the same
  material twice and call it reproducibility.
- **Exclude sessions about the analysis itself.** Once you start studying how the agent fails,
  your sessions are *about* failure, and sampling them biases straight toward the categories
  you just invented.
- **Record the exact session ids you picked.** Not optional: between two rounds a month apart,
  **2 of 30 previously-sampled sessions had vanished** from the pool. Without a manifest the
  first round would not have been reconstructible at all.

Sample by session, count by turn. The turn count is the denominator of every rate you will
quote, so decide it now and never adjust it afterwards.

## 2. Label blind — no categories, no suggestions

Free text only: *what did the agent get wrong here, and how did you correct it?* No list of
types on screen, no pre-filled values, no "suggested" column.

⛔ This is not a style preference. An earlier round of this work displayed suggested values
next to each row. The resulting labels differed from the suggestions **in 2 entries out of
100** — the annotator had, in effect, pressed Accept. That data could not be used as an
independent second opinion, and finding out cost a full relabelling pass.

One binary question per turn, plus one independent flag:

| | |
|---|---|
| **misaligned** | this turn contains you correcting, contradicting, or pointing out a mistake |
| **fine** | you were pushing forward, asking, informing, changing topic |
| *(separate flag)* **surfaced later** | you did not notice at the time; you found out afterwards |

The second flag matters more than it looks. It splits the data into *what a transcript can
see* and *what it cannot* — and published baselines only measure the first.

## 3. Map to a published taxonomy — by reading its contents, not its names

Label first, map second. Never the other way round.

Then find published work measuring the same thing and map your labels onto **its** categories,
so your number means something next to theirs.

🔴 **Read what is actually inside each category, not the one-line name.** This project
published a claim that the reference taxonomy had no slot for *fabricating a fact inside
produced content*. It does. Its dataset contains **96 episodes** of exactly that, filed under
self-reporting. The claim came from reading a category title and inferring its boundary, and
the dataset that refutes it was a twenty-minute download that nearly got skipped in favour of
emailing the authors.

Whatever will not map is your residual. That residual is the only part that is yours — and it
is worth far less than it feels, until step 6.

## 4. Turn criteria into checks — and let density veto them

A rule earns a place in the gate only if a command can decide it. Everything else stays
documentation.

Then apply the test that is easy to skip: **run it at scale before shipping it.**

`LT-S10` in this repo is implemented, tested, and deliberately **not registered**. It is
correct — it found 771 real instances across 538 files. That is exactly why it does not ship.
The construct is deliberate, it works wherever the key is set, and 771 findings against code
that is fine today is not a gate, it is a wall of text people learn to scroll past. An agent
handed 771 of them goes and edits 771 correct call sites.

Density turns a true positive into noise. Check for it before you ship, not after.

## 5. Replay cases, and a check that the check fails

Every rule ships with one input that must trip it and one that must not. The build fails
without both, so "I'll add the test later" cannot quietly become permanent.

**Then verify the test fails without the fix.** Revert the implementation, run the test, see it
red, restore. A test that passes for the wrong reason is indistinguishable from one that works
— which is the subject of this entire catalogue.

Two more things this project learned the hard way:

- **Print what each check examined, on every run, including clean ones.** "No findings" reads
  identically whether a rule looked at forty files or zero, and the second is the case that
  silently stops protecting you.
- **Never exit zero when you could not reach a conclusion.** A checker that reports success
  when it could not tell is one more instance of the bug it was written to catch. Here,
  "cannot decide" is a non-zero exit code.

🔴 **The hole this step does not cover, added 2026-11-13 after it cost us a rule.**

A replay fixture proves the checker **detects the pattern**. It says nothing about whether the
**consequence attached to that pattern is real** — and the consequence is the entire reason
anyone would act on a finding.

LT-S08 had both fixtures, passed its tests, was re-derived by a second independently written
checker that agreed 55/55, and had shipped 67 findings before anyone ran the obvious experiment:
write a scheduled method that throws, and watch whether it stops. It does not. Spring catches and
logs it; only a bare `ScheduledExecutorService` behaves as the rule claimed. Two checkers
agreeing is two measurements of the same thing — the match — and no measurement at all of the
claim.

So, as a step of its own: **for any rule that asserts "and then X happens", build the smallest
program that would show X, and run it.** Minutes, in this case. If X cannot be demonstrated,
the rule may still be worth keeping as a pattern, but ⛔ its consequence must be written down as
untested rather than as fact.

⚠️ Note the asymmetry with step 4: there, density vetoes a *criterion* that matches too much.
There was no equivalent gate on a *claim* that explains too much, and claims are what readers
act on.

## 6. Run it on material you do not control — and publish the zeros

Everything up to here is one person's corpus. The number that convinces nobody is the one
nobody can check.

So run the same checks against public repositories, pin every row to a commit, and publish the
table whole. Before running, **write down how you will read each outcome, and what you predict
— then hash the file.** Doing it afterwards is not interpretation, it is fitting.

That pre-registration caught two wrong predictions in this project's own run, both now printed
next to the results in [`engine/README.md`](engine/README.md).

**The best result of that run was a zero.** `LT-S04` found nothing across ten public
repositories, every one of which has test sources — a zero with the precondition satisfied ten
times over, so it carries information. The same check found twelve instances in the commercial
estate. The rule does not describe Java; it describes a particular engineering situation.

Publishing that made the catalogue narrower, and narrower is more honest.

---

## The parts you can reuse

| | |
|---|---|
| The label data and a self-contained script that recomputes every figure | [tripwire-corpus](https://github.com/zerosymptom/tripwire-corpus) |
| Rule format: symptom / mechanical check / why an agent misses it / false-positive boundary / verified date | [`RULES.md`](RULES.md) |
| Engine, replay fixtures, three-state exit codes | [`engine/`](engine/) |
| Results on ten public repositories, pinned to commits | [`engine/README.md`](engine/README.md) |
| Method write-up, including the retractions | [`docs/article.md`](docs/article.md) |

## What this method does not give you

- **Rates.** Single samples, one estate, one annotator pair. Agreement was 0.813 and Cohen's κ
  was 0.296 on the same 166 items; the κ is the honest one.
- **Generality.** Everything originates in one commercial system, and that bias cannot be
  removed by effort — only by other people running it on theirs.
- **A finished taxonomy.** Of this project's residual classes, six of eight rest on a single
  observation. One observation is an anecdote.
