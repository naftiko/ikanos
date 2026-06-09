---
name: bugfix
version: "1.0.0"
description: >
  Bimodal bugfix workflow skill for a Java/Maven repository. Mode A — start from a
  fresh issue, e.g. "fix bug #<number>" (reproduce → failing test → fix → PR).
  Mode B — resume an existing PR, e.g. "address review feedback on PR #<number>"
  (Copilot, bot, or human comments). Maven-based; fixing a bash script or CI
  workflow inside such a repo is in scope. Do NOT use for non-Java/Maven repos
  (e.g. C++, TypeScript), feature work, refactors, or dependency upgrades.
allowed-tools:
  - Read
  - Bash
  - Edit
  - runSubagent          # Phase 5 — spawn the self-review subagent
  - vscode_listCodeUsages # Phase 1B — verify cross-package callers before narrowing visibility
  - lsp_java_findSymbol   # Phase 1 — locate Java symbols when scoping the bug
  - memory                # Phases 1B/5/6/8 — read & delete handoff / self-review findings
---

## Overview

This skill turns a bug report into a merged fix with a non-regression test, while
applying the same quality bar as the `pr-review` skill — but **at generation time**,
not only at review time (the goal of issue #552).

It is built around a continuous-improvement loop. Each use should produce **cleaner
first-draft code than the last**, so the self-review subagent finds **fewer** findings
over time and token cost **drops** per use:

```
CODE   — apply the Pre-Code Checklist WHILE writing      → prevent findings up front
MEASURE— self-review the LOCAL diff via an independent subagent (🔴/🟡 only)
FIX    — fix residual 🔴/🟡, loop until zero (cap 3)
LEARN  — propose avoidable+generalizable findings as new upstream rules (user decides)
```

> **The self-review subagent is a thermometer and a learning source — never a safety
> net.** A use where the subagent surfaces many 🔴/🟡 findings is a *signal that the
> Code phase did not apply the rules*, not merely something to silently fix. The fix
> is to strengthen the upstream rules (Phase 7), so the next use starts cleaner.

---

## Two modes — pick one before Phase 0

Like `pr-review` (Mode A inline / Mode B handoff), this skill is **bimodal**. Decide which
entry point applies, then follow the phase path for that mode.

| | **Mode A — From scratch** | **Mode B — Resume after PR feedback** |
|---|---|---|
| **Trigger** | "fix bug", "fix issue #N", "reproduce defect" | "address PR feedback", "fix review comments on PR #N", "resume #N after review" |
| **Starting point** | An open issue, no code yet | An open PR with review comments (Copilot, bot, or human) |
| **Entry phase** | Phase 0 → 1 → 2 (write the failing test) → 3 … | Phase 0 → **1B** (ingest the PR + comments) → 3 … |
| **Test-first?** | Yes — failing test proves the bug (Phase 2) | Only if a comment reveals a **missing** test; the original failing test usually already exists in the branch |
| **Branch** | New `fix/<desc>` from `origin/main` | The **existing** PR branch (checkout, then rebase on `origin/main` if stale) |
| **Exit** | Phase 8 opens the PR | Phase 8 **pushes** to the existing PR and replies to the threads |

Both modes share the core loop (Phases 3→7) — the only difference is the entry (how the diff
and the test get there) and the exit (open a PR vs. push to an existing one). When in doubt
about which mode applies, ask the user.

---

## Phase 0 — Prerequisites

Before any action:

1. Re-read the **Bug Workflow** section of `AGENTS.md`. Every rule there applies exactly;
   this skill is tactical guidance layered on top, it never overrides AGENTS.md.
2. Read the **Pre-Code Checklist** at the bottom of this file. You will apply it *while*
   writing code in Phase 3 — not after.

> **This workflow assumes an _interactive_ executor** — Phases 1B (triage), 6 (LOW
> handling), and 7 (capitalize) all ask the user to decide. Only **Phase 5** is delegated
> to an autonomous, read-only subagent (it writes findings to memory and decides nothing).
> If *you* are a non-interactive subagent asked to run this skill end-to-end, stop and
> hand back: produce the diff + a findings report and let an interactive agent (or the
> user) make the Phase 1B/6/7 decisions.

> If you are resuming after a context compaction (conversation summary), re-read this
> entire `SKILL.md` before continuing — compaction erases step formalism and the
> checklist.

---

## Phase 1 — Understand the bug · **Mode A only**

1. Read the issue: `gh issue view <N> --repo <owner/repo>`

   > **Derive `<owner/repo>` — never hardcode it.** This skill targets any Java/Maven repo,
   > so resolve the repository from the working directory instead of naming it: run
   > `gh repo view --json nameWithOwner -q .nameWithOwner` (or simply drop `--repo` and let
   > `gh` infer it from the `origin` remote of the current folder). In a multi-root workspace,
   > derive it from the folder that owns the failing code.

2. Identify the **component** (Core Engine, CLI, Spec, …) and separate **symptom** from
   **root cause** — they are rarely the same place in the code.
3. Locate the relevant class(es). For Java, prefer `lsp_java_findSymbol` over `grep_search`.
4. Do **not** write any code yet.

> If the issue number is ambiguous or the reproduction steps are unclear, ask the user
> before proceeding. A fix for the wrong root cause is more expensive than a question.

> **Enumerate the blast radius — before any code.** If the bug is a cross-cutting concern
> (logging, tracing, MDC, error handling, auth, a shared header), `grep` for **every** site
> that exhibits the pattern — not just the one in the report — and list them. The fix must
> cover all of them in one pass, or each omission must carry an explicit written justification
> (which becomes a PR comment). *"The report only mentioned X and Y" is not a reason to skip
> the third site.* (Lesson from #548: the first fix covered the REST + MCP adapters but missed
> the third SERVER adapter, `SkillServerResource`, drawing the only MEDIUM review finding.)

---

## Phase 1B — Ingest the PR and its review feedback · **Mode B only**

This is the Mode B entry point. The fix branch and (usually) the failing test already exist;
you are resuming because a reviewer — **Copilot, a bot, or a human** — left comments.

1. Check out the PR branch and bring it up to date:
   ```
   gh pr checkout <N> --repo <owner/repo>
   git fetch origin main && git rebase origin/main      # resolve conflicts if stale
   ```
2. Read the PR and **all** its review comments:
   ```
   gh pr view <N> --repo <owner/repo> --comments
   gh api repos/<owner/repo>/pulls/<N>/comments --jq '.[] | {path, line, body, user: .user.login}'
   ```

   **Findings can arrive from three sources — none is inherently primary:**
   - **GitHub review** — the `gh` commands above (Copilot, bot, or human inline comments).
   - **Memory handoff** — a `/memories/repo/pr-review-<N>.md` file left by the `pr-review`
     skill (its Mode B). Read it with `memory view /memories/repo/pr-review-<N>.md`. In the
     in-house flow this is **often the primary source** and GitHub may be empty.
     > **Constraint:** `/memories/repo/` is scoped to **one local repo/worktree**. The
     > handoff is visible only if the reviewer ran in the **same workspace**. If the review
     > happened elsewhere, the file will not be there — ask the user to paste the findings.
   - **The user directly** — findings dictated in the prompt, with no GitHub review and no
     handoff file. This is a valid source; triage them exactly like the other two.

   Use whichever source(s) exist; do not assume GitHub is populated.
3. Triage every comment into actionable findings, classified 🔴 HIGH / 🟡 MEDIUM / 🔵 LOW.
   Separate **"change requested"** from **"question / nit"** — questions get a reply, not
   necessarily a code change.
4. If a comment reveals a **missing test** (a behavior the original fix did not cover), write
   that failing test now — same rule as Phase 2: it must fail before you fix it.
5. Re-run the suite to confirm the current branch state: `mvn clean test --no-transfer-progress`.

> **Do not blindly apply every comment.** A reviewer can be wrong, or a nit can be out of
> scope for an atomic bug fix. For each comment you decline, note why — you will surface it
> as a reply thread in Phase 8. Disagreement is a conversation, not a silent skip.

> **A finding can be plain wrong.** Before acting on a *visibility-reduction* suggestion
> ("make this package-private / private"), verify the real callers with
> `vscode_listCodeUsages` — a method called from another package **must** stay `public`.
> (Lesson from #548: a "make `populateMdc` package-private" finding was declined because it
> is called from both the `mcp` and `rest` packages.)

> **A finding about one site implies a blast-radius check.** When a reviewer flags a missing
> fix at one site of a cross-cutting concern, do not patch only that site — `grep` for **every**
> site exhibiting the pattern and fix them together, so the *next* reviewer cannot find an
> (N+1)th one. (Lesson from #548: the first fix covered REST + MCP, a human reviewer flagged the
> missing `SkillServerResource`; the proper response was to enumerate all three SERVER adapters
> at once.) See the Pre-Code Checklist "fix all call sites" entry.

> From here, Mode B rejoins the shared path at **Phase 3** (apply the fixes), then Phase 4
> (green suite), Phase 5 (self-review the **updated** diff), Phase 6 (loop), Phase 7
> (capitalize), and Phase 8 (push to the existing PR + reply to threads).

---

## Phase 2 — Reproduce the bug with a failing test · **Mode A only**

A fix you cannot prove is a fix you do not have.

1. Find the existing test class closest to the bug (same package / feature area). If none
   exists, create one named after the class under test.
2. Write a test whose assertion message **references the issue number** and states *why*
   it fails (what is missing or wrong), not just that it failed.
3. Run it — it **must FAIL**. If it passes, it does not reproduce the bug; rewrite it.
4. Only then move to Phase 3.

> **Config-file bugs** (e.g. `logback.xml`, `pom.xml`) — the test must exercise the
> **runtime path driven by that config**, not mock it away. A test that passes with a
> mocked config does not prove the real config is correct.

**Decision tree — unit vs integration:**

```
Bug in a single class/method?              → unit test in the matching *Test.java
Bug needs the full engine stack            → integration test, load a YAML fixture
  (YAML → Capability → Adapter → HTTP)?       from src/test/resources/
Bug in a config file?                      → test the runtime behavior the config drives
Bug in a bash script or CI workflow?       → exercise the script/workflow, not a JUnit test
  (.sh, .github/workflows/*.yml)              (see note below)
```

> **Bash / CI fixes** — this skill targets Java/Maven repos, but a bug can live in a shell
> script or a CI workflow *inside* such a repo. There the regression guard is not a JUnit
> test: reproduce the failure by running the script (`bash -x script.sh`) or by asserting the
> workflow's logic (e.g. a small shell assertion, a `shellcheck` clean run, or a dry-run of
> the workflow step). The test-first discipline still holds — reproduce red, fix, prove green
> — only the test *medium* changes. Do **not** force a `mvn` test where a shell check is the
> honest reproduction. Out of scope: non-Java/Maven application code (C++, TypeScript, …).

---

## Phase 3 — Code the fix, applying the upstream rules ⭐

This is the phase that raises first-draft quality. Apply the **Pre-Code Checklist** as
you write — do not defer it to the self-review.

1. Edit only `src/main/` (or resources). The test already exists from Phase 2.
2. Fix the **minimal** thing that makes the failing test pass. No opportunistic refactors.
3. If the same root cause exists elsewhere (grep the pattern), fix all instances — note
   them so the self-review can confirm coverage.
4. Re-run the failing test — it must now **PASS**.

> Do not edit production code (`src/main/`) and test code (`src/test/`) in the same step.
> Phase 2 owns the test; Phase 3 owns the fix. This keeps the "test fails first, then
> passes" proof intact.

> **Finish all edits to a file before building.** A build run *between* a series of edits
> to the same file can report transient "cannot find symbol" / "Unresolved compilation"
> errors from a half-edited state. After editing, run `get_errors` on the file, then a
> single `mvn clean test`. Likewise, do not trust a truncated `grep` line (e.g.
> `Assertions.*;` shown as `Assertions;`) as evidence of a broken import. (Lesson from #548.)

---

## Phase 4 — Green suite

Run the full suite — no regressions:

```
mvn clean test --no-transfer-progress
```

> Use `clean`. A Surefire **"Unresolved compilation problem"** with no preceding `javac`
> error almost always means stale compiled test classes — `mvn clean test` fixes it.
> (Lesson from issue #548.)

> If a SLF4J **"multiple providers"** warning appears, note which one is the *Actual
> provider*. A logback-based fix is silently ineffective if Logback is not the active
> provider. (Lesson from issue #548.)

If any pre-existing test fails, investigate before touching it — never weaken an existing
assertion to go green.

---

## Phase 5 — Self-review the local diff (the measure) ⭐

Run an **independent subagent** as a PR reviewer over the **local diff only**. This is the
"did we generate clean code?" gate, scoped tightly to keep token cost low.

> **Model rule — review never delegates downward.** The agent executing this skill can be a
> standard code model (e.g. Sonnet); the workflow is explicit enough to follow without a
> reasoning model. But the Phase 5 reviewer subagent must be **at least as capable as the
> agent being reviewed — preferably a reasoning model (e.g. Opus)**. A weaker reviewer gives
> a false sense of safety: few findings then means a weak review, not clean code. Pass the
> reviewer model explicitly via `runSubagent` (do not inherit the caller's model by default).

1. Confirm the diff is ready: `git diff origin/main...HEAD` (fix + test, suite green).
2. Launch a subagent (`runSubagent`, agent `Explore`) with a read-only review mission:
   - Review **only** `git diff origin/main...HEAD` — not the whole repo.
   - Apply the quality bar from `AGENTS.md` (Test Writing Rules, Method Visibility, spec
     version rules) and the severity model from `.agents/skills/pr-review/SKILL.md`.
   - Classify findings 🔴 HIGH / 🟡 MEDIUM / 🔵 LOW.
   - **Write findings to `/memories/repo/selfreview-<id>.md`** (memory-handoff style,
     no GitHub round-trip — this is internal to the skill and unrelated to the skill's
     Mode A/B entry choice). `<id>` is the issue number in Mode A, or the PR number in
     Mode B when no distinct issue exists.
3. Read the findings file with `memory view /memories/repo/selfreview-<id>.md`.

> **Scope discipline.** The subagent writes findings to memory only. Do not let it
> fetch a GitHub PR, post comments, or scan unrelated files — that is wasted token budget.

---

## Phase 6 — Fix residual findings (bounded loop)

Work the findings, but **only 🔴 HIGH and 🟡 MEDIUM**. Note 🔵 LOW for the user; do not
chase them in the loop.

```
repeat:
  fix all 🔴/🟡 findings
  mvn clean test            # stay green
  re-run Phase 5 self-review
until: zero 🔴/🟡 findings
cap:   3 iterations, then STOP and ask the user
```

> **Bounded by design.** If the loop has not converged after 3 iterations, stop and ask
> the user rather than burning tokens. Non-convergence is itself a signal worth surfacing.

> **Cheap 🔵 LOW are worth offering.** LOW findings are not chased in the loop, but a
> 0-🔴/0-🟡 self-review can still surface genuine LOWs in the *same diff* (doc wording, an
> over-permissive config value). When they are cheap and in scope, **offer** them to the
> user rather than only noting them — they cost little and keep the diff clean. (Lesson from
> #548: two LOW doc/config findings were fixed this way.)

After the loop, delete the findings file: `memory delete /memories/repo/selfreview-<id>.md`.

---

## Phase 7 — Capitalize: propose new rules (the learning) ⭐

This is what makes the skill better on every use. For each finding that was **avoidable**
(the agent generated it) **and generalizable** (could recur in another context):

> **Capitalize on TWO sources of findings — not just the self-review.**
> - **Mode A:** the source is the Phase 5 self-review output.
> - **Mode B:** the source is the Phase 5 self-review output **and** the **human/reviewer
>   findings you addressed in this run**. The findings you are *fixing* are the highest-signal
>   training data the skill will ever get — a human already proved they were avoidable. Run the
>   same "avoidable + generalizable?" test on each addressed comment and propose a rule for it.
>   Do not consume reviewer comments as mere work items and discard them; feed them back into
>   the checklist. (Without this, the loop is blind to findings on the *first* commit, because
>   the self-review only ever sees the *correcting* commit, which is clean by construction.)

1. Draft a concrete Do/Don't entry and identify where it belongs:
   - **tactical, bugfix-specific** → this skill's Pre-Code Checklist
   - **cross-cutting / transverse** → `AGENTS.md` (the relevant section)
2. **Present the candidate rules to the user and let them pick.** Show what would be added
   and where; the user chooses which to keep, which to drop, and which scope.

> **Never edit instructions unilaterally.** The agent proposes; the user decides — exactly
> like the `pr-review` skill lets the user pick which findings to post, and like the
> AGENTS.md Self-Improvement section ("propose the entry… let the user decide"). Do not
> apply a rule the user did not approve.

> Most findings are one-off and need **no** rule. Proposing noise erodes the checklist.
> Only propose when avoidable **and** generalizable are both true.

---

## Phase 8 — Commit & PR

### Mode A — open a new PR

1. Commit the test and the fix together:
   ```
   fix: <short description> (closes #<issue>)
   ```
2. Read `.github/PULL_REQUEST_TEMPLATE.md` before writing the PR body.
3. Write the body to a temp file, pass it via `--body-file` (never inline multiline in
   PowerShell — here-strings hang or corrupt content).
4. Confirm to the user that the template was followed section by section.
5. Delete the temp file after the PR is created.

### Mode B — push to the existing PR and answer the reviewers

1. Commit the changes with a message that references what was addressed:
   ```
   fix: address review feedback on #<issue>
   ```
   (Group logically; do not amend the original commit unless the user asks — keep the
   review history readable.)
2. Push to the PR branch with `--force-with-lease` only if you rebased; otherwise a plain
   `git push`. Never `git push --force`.
3. **Reply to each resolved GitHub thread** so reviewers see the resolution — briefly state
   what changed (or why a comment was declined, per the Phase 1B triage note). All replies in
   English. *(Skip for findings that had no GitHub thread — e.g. a memory handoff or
   user-dictated findings; confirm those directly to the user in step 5 instead.)*
4. Delete any consumed handoff file after pushing. There may be up to two, depending on how
   the run started:
   - the incoming `pr-review` handoff, if the feedback came from one:
     `memory delete /memories/repo/pr-review-<N>.md` (`<N>` is the PR number).
   - the internal self-review findings file from Phase 5, if it still exists:
     `memory delete /memories/repo/selfreview-<id>.md` — `<id>` is the issue number in Mode A,
     or the PR number in Mode B when no distinct issue exists. Use the exact filename you
     created in Phase 5 (Phase 6 normally deletes it after the fix loop; this is a safety net).
5. Confirm to the user which comments were addressed and which were declined (with reasons).

---

## Pre-Code Checklist (apply in Phase 3, before writing)

These are the rules that keep findings out of the diff in the first place. They grow over
time via Phase 7 — always with the user's approval.

**Do:**
- Before fixing a shared / cross-cutting concern, `grep` for **all** call sites of the
  pattern and fix them in one pass — a reviewer can always find the Nth site you missed, so
  find it first. List the sites up front (Phase 1 / 1B "enumerate the blast radius") and
  justify in writing any you deliberately leave out. *(Lesson from #548: the first fix covered
  the REST + MCP SERVER adapters but missed the third, `SkillServerResource`, drawing the only
  MEDIUM review finding.)*
- Extract string values that tests will assert against (MDC keys, span names, error
  messages, HTTP headers) into **production constants before writing the assertions** —
  so a later rename is greppable and cannot drift between code and tests.
- Use the **minimal visibility** that lets the test work: package-private over `public`
  for methods only used inside the engine; never widen visibility just for a test.
- Prefer making a method **package-private** over reflection (`getDeclaredMethod` /
  `setAccessible(true)`) when a test needs to reach it.
- Assert **observable behavior** (output, side effects, logs, spans, MDC state), not
  internal fields.
- Name tests `methodShouldDoSomethingWhenCondition`.
- Inject the spec version via `VersionHelper.getSchemaVersion()` + `String.formatted(...)`
  in inline YAML text blocks — never hardcode it (AGENTS.md).
- When a test needs mid-execution observation without reflection, document the pattern
  (e.g. an anonymous subclass override) so future tests reuse it instead of reinventing it.
- When a fix makes a successful lookup depend on **two components that independently
  produce the same key/path** (e.g. a map indexed by producer A, queried with a path built
  by producer B), write a **round-trip test** proving the two strings coincide exactly — and
  that **distinct inputs which could collapse to the same key don't silently alias** (e.g. a
  flat `"a.b"` key vs nested `a: { b }`) — including edge cases (array indices, the root `$`,
  keys with special characters). A one-character divergence makes the lookup fail
  **silently** (null result, no error) and turns the fix into an invisible no-op. *(Lesson
  from polychro #32: `JacksonSourceMap` indexes ranges by dot-notation path while
  `JsonPathEvaluator.toDotNotation` builds the lookup path; any disagreement makes
  `sourceMap.resolve(...)` return null and the diagnostic silently loses its range. PR #33:
  a dotted-key test passed by coincidence — pin the collision, don't assume uniqueness.)*
- **Scan loops must reach the boundary.** When searching a sequence for a delimiter, never
  bound at `< length() - 1` (it skips the last element); test the marker as the **first** and
  **last** character, not just mid-string. *(PR #33: `endOfPlainScalar` missed a terminal `#`.)*

**Don't:**
- Don't write the fix before the failing test exists and is confirmed red.
- Don't leave explanatory comments (in code or tests) that describe an **earlier** hypothesis
  of the root cause. When your understanding of the cause changes mid-fix, re-read every comment
  you wrote earlier in the session and update it to the final root cause. *(Lesson from #548: a
  test comment kept blaming "OpenTelemetryAppender missing from logback.xml" after the cause was
  re-understood as "populateMdc not called within the span scope.")*
- Don't leave an **unused setup variable or fixture** in a test — it signals an incomplete
  refactor; delete it or move it to a shared `@BeforeEach`.
- Don't override a YAML-declared value (e.g. `baseUri`) in test code to work around a
  broken fixture — fix the fixture (AGENTS.md).
- Don't copy a pattern from existing test code that contradicts AGENTS.md — flag it instead.
- Don't refactor unrelated code in a bug fix — keep the PR atomic.
- Don't use `insert_edit_into_file` on large JSON or XML configuration files (e.g.
  `reflect-config.json`, `pom.xml`, `logback.xml`). The tool can silently truncate content
  beyond the edit point, discarding the rest of the file. Instead, use a targeted Python
  `str.replace()` on a unique anchor string — this is atomic, leaves the rest of the file
  intact, and fails loudly (`print("ERROR: anchor not found")`) if the anchor is not found.

---

## Checklist before committing

**Mode A — from scratch:**
- [ ] Failing test written and confirmed to fail **before** the fix (Phase 2)
- [ ] Fix applied — test now passes (Phase 3)

**Mode B — resume after PR feedback:**
- [ ] PR checked out, rebased on `origin/main` if stale (Phase 1B)
- [ ] Every review comment triaged; declined ones have a reason for the reply (Phase 1B)
- [ ] Any missing test revealed by a comment written red-first (Phase 1B step 4)

**Both modes:**
- [ ] Full suite green: `mvn clean test` (Phase 4)
- [ ] Self-review run; zero 🔴/🟡 residual, or user consulted at the cap (Phases 5–6)
- [ ] Findings file deleted from `/memories/repo/`
- [ ] Capitalization proposed to the user, if any avoidable+generalizable finding (Phase 7)
- [ ] **Mode A:** commit `fix: … (closes #N)`; PR body follows the template; temp file deleted
- [ ] **Mode B:** pushed with `--force-with-lease` (if rebased); GitHub threads replied (if any); handoff file deleted
