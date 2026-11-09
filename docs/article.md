# I reviewed 1,829 notes from a year of working with AI agents. It keeps falling into the same nine traps.

> Companion list: [RULES.md](../RULES.md) — 40 rules, each with a mechanical check.
> All cases de-identified: the pattern is kept, the scene removed.

## One emoji, three hours

A test environment went down. The symptom: **every path returned 500** — including `/favicon.ico`,
including paths that didn't exist. And the application produced **not one line of log output**.

Every link in the chain was green. Build succeeded. Image pushed. Container `Up`.
Unpacking the archive showed every class and resource present. The full local Spring context test
suite was green.

Three hours later: there was a 🔴 in `web.xml`.

That container's XML parser only handles 3-byte UTF-8. An emoji is four bytes.
Parse failure → the webapp never configures → every path 500 → the logging framework never
initialises, which is why there were no logs.

I wrote it down. It was note number 1,312.

## Why keep notes at all

For the past year I've used AI agents to build on an old Java system.
Spring MVC + Dubbo + MyBatis, MySQL 5.6 and 8.0 side by side, some modules still on `source=1.7`.

I noticed something early: **AI fails differently than people do.**

People get stuck. People say "I'm not sure." The most common failure mode for an agent in this
system was something else entirely — **it tells you confidently that it's done, and every word it
says is true.**

So I started keeping notes. Every time I found a case of "it thought it succeeded but nothing
happened", I wrote down the phenomenon, the mechanism, and what to do next time.
A year later: 1,829 notes.

## They all have the same shape

Reading back through them, most of them have the same face. I call it **silent failure**:

> **The success signal is real. So is the fact that nothing happened.**

A few:

- `BUILD SUCCESS` is real. The build file just hardcodes a literal skip-tests flag, so **zero tests ran**.
  Passing `-DskipTests=false` cannot override it — POM literals outrank the command line.
- The tests were written, ran, passed, and got committed. Except `.gitignore` excluded the whole test
  directory, so `git status` never showed them. **After the commit, they didn't exist. Zero symptoms.**
- A batch test run whose first output line is `Could not find class: ...`, followed by dozens of
  perfectly clean class names — it looks exactly like a normal run list — ending in `OK (0 tests)`.
  Cause: zsh doesn't word-split unquoted expansions (bash does), so the whole list became one argument.
- A set of verification commands, "no output means it passed", all passing.
  Except they were written as `git diff origin/master...HEAD` while the work was still uncommitted.
  `A...HEAD` only compares committed content, so **the diff was empty** —
  and an empty diff makes every "expect no output" check pass, with output identical to a real pass.

In each of these, what the agent can observe is exactly the part that's true.
**It isn't insufficiently smart. The feedback signal itself is lying.**

These traps have another nasty property: **they don't come after you on the day they fail.
They wait until you've moved on, and then start charging interest.**

## So I tried to verify something

If these traps really share a shape, they should be **mechanically** decidable — expressible as a
grep, a command, a CI check that runs before the agent starts and before the commit lands.

But before actually committing to it, there was one number I needed:

> What fraction of these 1,829 notes **can** be turned into a machine-checkable assertion?

I pre-registered a kill switch: **below 15%, abandon it.** Too low and this is a memoir, not a system.

The method was the dumb one: sample 100 (all 58 personal notes + 42 sampled from the agent corpus)
and label each. To avoid fooling myself, a model and I labeled **independently**, then reconciled
entry by entry.

Three axes (later four): ① is this a trap ② which category ③ can it be mechanically asserted.

## The first number was off by 3×

I came out at **20.7%**. The model came out at **48.3% – 60.3%**. Both clear 15%, so "do it" was
never really in doubt — but a 3× gap is more interesting than the verdict.

Going entry by entry, the disagreements nearly all pointed one way: the model thought something
was mechanizable and I didn't. Looks like plain over-optimism.

Then I ran it again over only the entries **both of us called a trap**:
**70.4% vs 77.6% — seven points apart.**

So it isn't optimism. On "can this be mechanized" we barely disagree.
**The argument is one question earlier: what counts as a trap at all.**
Out of 100, I said 27; the model said 85.

The embarrassing part is why. In the table I used for labeling, picking "not a trap" on the first
axis auto-filled the other two with "other / N/A" — 73 entries, no exceptions. So my 20.7% is really
"how many I think are traps" × "how many traps are mechanizable."
**I only ever judged the third axis 27 times.**

A form's UI contaminated the conclusion drawn with it. That one belongs on the list as entry 41.

## My own diagnosis got overturned too

So why did I only call 27?

My first explanation was that I'd been reading "background knowledge" as "something I already knew."
**That explanation was wrong.** The real criterion was different: anything welded to specific
business logic — anything you wouldn't hit at another company — I filed as background.

Then I noticed that criterion measures something else entirely. Of the 33 portable traps, **79%**
are mechanizable. Of the 52 business-bound ones, **77%**. Practically the same.

**"Can someone else use this" and "can this be a mechanical check" are unrelated questions.**
A trap that only happens inside your company's system can still be written as a grep — you just
can't hand it to anyone. I'd folded both into one axis, diluting the answer with a variable that had
nothing to do with it. The number got smaller; I didn't learn anything.

So I split them: **① is only about whether the thing bites, ④ only about whether you'd hit it
somewhere else.** After that, the entries I'd been agonizing over sorted themselves out.

One unexpected payoff: I'd written off the business-bound notes as unusable.
**They're not waste, they're the evidence.** "This method caught real bugs in a real old system"
is something only they can prove. Useless for a public rule pack; the most useful thing I have
for making the case.

## One piece of hard evidence

Of the 65 entries I labeled "pure background / process preference", **37 of them (57%) literally
contain the words** "zero symptoms", "false green", "silent", "no error", or "wrong conclusion" in
their own body text.

One of them reads: *"four lines of dead stub … not one message gets sent — zero symptoms."*

**I labeled that as background.**

A few months, and my judgement of my own writing had drifted that far from the reason I wrote it.

Which is exactly the argument for mechanizing it: not because people aren't smart enough,
but because people don't remember why something mattered.

## Nine categories

| Category | Meaning |
|---|---|
| `SILENT` | It ran, nothing errored, nothing happened |
| `CONTRACT` | Implied semantics are the opposite of intuition |
| `PLATFORM` | A hard runtime limit — touch it and everything fails at once |
| `ENV_MIS` | Local / container / toolchain state invalidates the conclusion |
| `FALSE_ALARM` | A tool reports a problem that doesn't exist, or claims it did something it didn't |
| `TOPOLOGY` | There are N paths; you only saw one |
| `SEM_INV` | A method or config name means the opposite of what it says |
| `NO_SUBSYS` | A subsystem you assumed exists simply doesn't here |
| `DEAD_SRC` | You're reading a source that stopped being updated long ago |

Seven became nine during reconciliation — two more clustered out of "other", raising coverage from
88% to about 96%. The newcomers were `FALSE_ALARM` (the most expensive class: you go fix something
that was never broken) and `TOPOLOGY` (five writers, four trigger paths, and you saw one).

The 40 de-identified rules are [here](../RULES.md), each with a mechanical check and a
false-positive boundary.

## If you're shipping code with an AI today

Three things you can use immediately:

**1. Never treat "no output" as a pass.**

This is the single most recurrent shape in the corpus. An empty check script, a grep with no hits,
a clean `git status`, an empty diff — before using any of those as evidence, verify that the command
is actually looking at what you think it's looking at.
Cheapest version: confirm it **does** produce output in a case where it should.

**2. Keep mechanical and human checks strictly apart.**

Only one-command-decidable checks belong in a gate. Semantic judgements ("this table name shouldn't
appear here") can only be approximated by string matching,
**and a false positive makes the agent go change code that was already correct — which costs more
than the miss.**

This trade-off differs from classic static analysis. A human reading a linter false positive shrugs.
An agent reading one will actually rewrite the file.

**3. Date every piece of knowledge.**

My corpus already contains a rule that upstream fixed after it was written — the missing check it
described was later added. **An expired rule is worse than no rule**, because it gets believed.
Any rule set needs one self-test: do the symbols it references still exist?

## This is not an anti-AI piece

The opposite. Most of those 1,829 traps **were found by the AI** — it moves faster than I do,
so it hits these things sooner and more often than I ever would.

The real problem isn't the model's ability to write code. It's that **legacy systems emit untrustworthy
feedback.** A person who has lived in one grows a layer of instinct: seeing `BUILD SUCCESS`, they
glance at the test count; hearing "all tests green", they first check the tests are actually tracked.

**An agent has no such layer — and it doesn't grow one from getting burned, because the next session
starts fresh.**

So the fix isn't "make the AI smarter". It's to **externalise that instinct into something it can look
up every time.**

That list is the first attempt. Forty entries, documentation only, no code.
If you also work in a legacy system alongside an agent, contributions welcome —
bring the **check** and the **false-positive boundary**. A symptom alone is far less useful.

---

*Methodological disclosure: the sample is one person on one system. It's biased, and effort doesn't
fix that. The human pass was contaminated by the labeling tool's UI (see above).
A third label set exists but is "wholesale adoption of the model's suggestions plus two edits" —
⛔ it must not be cited as an independent data point.*
