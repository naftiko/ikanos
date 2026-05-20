---
name: pr-review
version: "2.1.0"
description: >
  On-demand skill for (A) reviewing GitHub Pull Requests and posting inline
  comments or handing off findings to a local agent, and (B) fixing / addressing
  review comments left by Copilot, bots, or human reviewers. Activate when the
  user asks to: review a PR, review a pull request, do a code review, post
  inline comments on a PR, comment on a pull request, review PR #<number>,
  fix PR comments, address PR feedback, fix PR #<number>, address review
  comments, fix Copilot comments, fix review, resolve PR threads.
allowed-tools:
  - Read
  - Bash
---

## Overview

This skill has two parts:

| Part | Purpose | Typical trigger |
|------|---------|-----------------|
| **A — Review** | Read the diff, identify issues, post a review | "review PR #N" |
| **B — Fix** | Detect all review comments (incl. Copilot / bots), apply fixes, verify | "fix PR #N", "address PR comments" |

---

# Part A — Post a PR Review

```
Step 1 — pr-context (1 click)
Step 2 — Read diff → identify issues → present list WITHOUT line numbers
Step 3 — User picks which findings to act on + chooses Mode A or Mode B
Step 4 — Resolve line numbers for picked findings only
          Short diff (<500 lines): compute from context, 0 clicks
          Long diff  (≥500 lines): pr-find-lines-batch on picked items, 1 click
Step 5 — Deliver findings
          Mode A: post review on GitHub (1 click)
          Mode B: write to /memories/repo/ for local agent fix (0 clicks)
```

**Minimum 1 click (Mode B, short diff). Maximum 3 clicks (Mode A, long diff), regardless of the
number of findings.**

Line numbers are resolved **after** the user picks — never before. There is no
point verifying a line number for a finding the user will not post.

### Helper scripts

| Script | Platform | When to use |
|--------|----------|-------------|
| `pr-context.ps1 -Pr <N>` | Windows | **Once**, Part A Step 1 |
| `pr-context.sh <N>` | Linux / macOS | Same |
| `pr-comments.ps1 -Pr <N> [-Author <user>] [-ExcludeOutdated]` | Windows | **Once**, Part B Step 1 |
| `pr-comments.sh <N> [author] [--no-outdated]` | Linux / macOS | Same |
| `pr-find-lines-batch.ps1 -Pr <N> [-InputFile <path>]` | Windows | **Once**, Part A Step 4, long diff only |
| `pr-find-lines-batch.sh <N> [input-file]` | Linux / macOS | Same |
| `pr-submit-review.ps1 -Pr <N> -InputFile <path>` | Windows | **Once**, Part A Step 5 Mode A only |
| `pr-submit-review.sh <N> <input-json>` | Linux / macOS | Same |

---

## Part A — Step 1 — Check for existing review activity

**Windows:**
```powershell
.\.agents\skills\pr-review\pr-context.ps1 -Pr <number>
```
**Linux / macOS:**
```bash
bash .agents/skills/pr-review/pr-context.sh <number>
```

The script fetches PR metadata, changed files, existing reviews (with IDs and
states), existing inline comments, and saves the diff to
`$env:TEMP\pr<number>.diff` (Windows) or `/tmp/pr<number>.diff` (Linux/macOS).

Note the **diff line count** from the output — it determines Step 4's path.

**If no reviews and no inline comments exist** → proceed directly to Step 2.

**If reviews or comments already exist**, summarize and ask the user:

> «This PR already has N review(s) and M inline comment(s) (latest state: X).
> - **Continue** — check prior `CHANGES_REQUESTED` items, skip outdated comments, add net-new findings only.
> - **Fresh start** — ignore prior activity and review the full diff from scratch.»

Wait for the user's answer before proceeding.

### If the user chooses **Continue**

The context script output already includes all review bodies and inline comment
linkage. Use it to:
- Identify `CHANGES_REQUESTED` reviews and their body text — verify whether each
  issue is addressed in the current diff
- For each inline comment whose `pull_request_review_id` matches a
  `CHANGES_REQUESTED` review ID: verify against the current diff; skip if `outdated`
- After checking those items, scan the diff for net-new findings only

### If the user chooses **Fresh start**

Ignore all prior review data. Proceed to Step 2 as if the PR had no prior activity.

---

## Part A — Step 2 — Read the diff and identify issues

Read the diff with `read_file` (use multiple calls for large diffs — `read_file`
requires no user confirmation). For each potential issue, note:

- **File path** — copy verbatim from the `+++ b/<path>` header in the diff
  (e.g. `ikanos-cli/src/test/java/io/ikanos/cli/StatusCommandTest.java`).
  This exact string is reused as-is in the Step 4 findings JSON and in the
  review payload — never shorten it to just the filename.
- **A short distinctive text snippet** from the target line (used in Step 4)
- **Severity** (🔴 HIGH · 🟡 MEDIUM · 🔵 LOW)
- **Comment text**

> **Snippet selection rules** — the snippet is used as a regex pattern by the
> batch line-resolver. Choose a fragment that:
> - contains only plain ASCII characters
> - contains no regex metacharacters: `( ) [ ] { } . + * ? ^ $ |`
> - is unique enough within the file to identify the target line
>
> If the natural target line contains Unicode or metacharacters, pick a
> neighbouring plain-ASCII keyword or identifier from the same line instead.
>
> **Note:** on Windows, `pr-context.ps1` saves the diff in UTF-8. Earlier versions
> used the system default (CP1252), which corrupted non-ASCII characters (e.g. U+2500
> `─` became `ÔöÇ`). If you see garbled characters in the diff, regenerate it with
> the current script — do not flag the source code as incorrect.
>
> **The diff is a derived artefact — the source file is always the source of truth.**
> If a character or string looks suspicious in the diff (garbled, truncated, or
> inconsistent with the surrounding code), use `read_file` on the actual file before
> drawing any conclusion. Never raise a finding based solely on what the diff shows.

Do **not** compute line numbers yet.

---

## Part A — Step 3 — Present findings and wait for the user's pick

Present findings in a working table (conversation only — never paste into GitHub):

| N | File | Snippet | Severity | Comment |
|---|------|---------|----------|---------|
| 1 | `path/to/Foo.java` | `void importShouldWrite…` | 🔴 HIGH | Comment text |
| 2 | `pom.xml` | `<jacoco.halt>true` | 🔵 LOW | Comment text |

> **Numbering rules for GitHub-bound text**
> - When the user selects a subset, **renumber sequentially from 1** in the review
>   `body` and inline comments — never carry over working-list numbers.
> - Plain integers (1, 2, 3 …) are fine in GitHub text.
> - **Never use `#N`** — GitHub renders `#N` as a hyperlink to issue/PR N.

**All comments must be written in English** — see AGENTS.md.

After presenting findings, ask the user which mode to use:

> **Mode A — Post review on GitHub** (default)
> **Mode B — Hand off to a local agent for fixes**

Wait for the user to confirm which findings to act on **and** which mode to use before proceeding.

---

## Part A — Step 4 — Resolve line numbers for picked findings only

### Short diff (< 500 lines) — 0 clicks

The diff is fully in context. Apply this algorithm to each picked finding's target
line:

1. Find the `@@` hunk containing the target line. Extract the `+N` value —
   initialize `counter = N - 1`.
2. Walk each line of the hunk:
   - `+` (added): `counter++` → valid target
   - ` ` (context): `counter++` → valid target
   - `-` (removed): do not increment — cannot be targeted
3. `counter` at the target line is its GitHub line number.

If uncertain, use `read_file` on the diff to re-read the specific hunk — no click.

### Long diff (≥ 500 lines) — 1 click

Write a findings JSON for **picked items only**:

```json
[
  { "id": 1, "file": "ikanos-cli/src/test/java/io/ikanos/cli/ImportOpenApiCommandTest.java", "pattern": "importShouldWriteDefault" },
  { "id": 2, "file": "pom.xml",                                                              "pattern": "jacoco.halt.true" }
]
```

> **`file` must be the full path** copied from the `+++ b/<path>` diff header —
> not a short filename. The script does an exact path match; a bare filename like
> `ImportOpenApiCommandTest.java` will never match and will produce a WARNING.

Save to `$env:TEMP\findings-<number>.json` (Windows) or `/tmp/findings-<number>.json`
(Linux/macOS), then run:

**Windows:**
```powershell
# Save findings (use create_file tool, not the terminal)
.\.agents\skills\pr-review\pr-find-lines-batch.ps1 -Pr <number>
```
**Linux / macOS:**
```bash
bash .agents/skills/pr-review/pr-find-lines-batch.sh <number>
```

Output: `[1] L42  [+] void importShouldWrite…` — use the `L<n>` values as line
numbers in the review payload.

> The findings JSON must be written with the `create_file` tool, not via a terminal
> command — writing files in the terminal costs an extra click.

---

## Part A — Step 5 — Deliver findings

### Mode A — Post review on GitHub

Build the review JSON with the confirmed line numbers and save it with `create_file`
to `$env:TEMP\review-<number>.json` (Windows) or `/tmp/review-<number>.json`
(Linux/macOS):

> **Always use `create_file` to write the review JSON — never `insert_edit_into_file`
> on a pre-existing file.** If `$env:TEMP\review-<number>.json` already exists from a
> previous session, delete it first with `Remove-Item` before calling `create_file`.
> Editing a stale file risks carrying over findings from a past review and posting a
> duplicate or incorrect payload.

```json
{
  "event": "REQUEST_CHANGES",
  "body": "Overall summary. (1) blocking issue must be fixed before merge.",
  "comments": [
    { "path": "path/to/Foo.java", "line": 42, "body": "Comment text." },
    { "path": "pom.xml",          "line": 92, "body": "Another comment." }
  ]
}
```

Then submit:

**Windows:**
```powershell
.\.agents\skills\pr-review\pr-submit-review.ps1 -Pr <number> -InputFile "$env:TEMP\review-<number>.json"
```
**Linux / macOS:**
```bash
bash .agents/skills/pr-review/pr-submit-review.sh <number> /tmp/review-<number>.json
```

The submit script posts via `gh api --input` (no quoting hazard), verifies
post-submission that all comments landed, and warns if any were silently dropped
by GitHub (which happens when a line number falls outside the diff).

> **Never post thread replies before the review.** Always bundle all inline
> comments — including replies to existing threads — into the single `POST /reviews`
> call. Add `"in_reply_to": <comment_id>` to the comment object; `"line"` is still
> required.

> **Silent terminal output is not a failure signal.**
> On Windows (PowerShell), a `gh api --method POST` that returns to the prompt with
> no output and no error has likely succeeded. Do **not** retry — retrying a submitted
> review creates an irrecoverable duplicate (GitHub returns HTTP 422 on `DELETE` for
> non-pending reviews). The `pr-submit-review.ps1` script handles this automatically;
> always prefer it over manual `gh api` calls on Windows.

Use `event=COMMENT` for a non-blocking review.
Use `event=REQUEST_CHANGES` when at least one finding is 🔴 HIGH.
Use `event=APPROVE` only when explicitly asked.

#### Cleanup after submission

Once the submit script confirms all comments landed, delete the temp files so they
cannot pollute a future session:

**Windows:**
```powershell
Remove-Item -Force "$env:TEMP\review-<number>.json", "$env:TEMP\findings-<number>.json" -ErrorAction SilentlyContinue
```
**Linux / macOS:**
```bash
rm -f /tmp/review-<number>.json /tmp/findings-<number>.json
```

### Mode B — Hand off to a local agent

Write the selected findings to `/memories/repo/pr-review-<PR>.md` (where `<PR>`
is the pull request number) using the `create_file` tool (not via a terminal
command) so the agent working on the branch can read them and apply fixes
directly — no GitHub round-trip. If the file already exists from a previous
review pass, use `memory delete /memories/repo/pr-review-<PR>.md` (the memory
tool) to delete the stale file before calling `create_file` — do not use
`Remove-Item` in the terminal, which costs an extra click and is unreliable on
Windows.

The handoff file must include:
- PR number and branch name
- List of changed files
- The findings table (N, file, severity, comment)
- A clear statement that the receiving agent should fix these findings

The receiving agent lists `/memories/repo/` to discover pending handoffs,
then executes the following steps **in order**:

1. Read the handoff file with `memory view /memories/repo/pr-review-<PR>.md`
2. Apply all fixes to the files listed
3. Commit and push
4. Reply to any open inline review comments on GitHub
5. If the diff reveals net-new findings not covered by the handoff (whether spotted while applying fixes or from an independent read of the diff), post them as a review comment on GitHub before closing out — do not silently drop them
6. **Delete the handoff file: `memory delete /memories/repo/pr-review-<PR>.md`**

Step 6 is mandatory — the agent must not consider the workflow complete until the handoff file is deleted.

Once written, inform the user that the handoff file is ready and which agent
should read it (see *Inter-agent communication* in `AGENTS.md`).

---

# Part B — Fix PR Comments

Systematic detection and remediation of review comments from any source —
human reviewers, Copilot, bots. Uses the GitHub REST API directly because
the VS Code `currentActivePullRequest` tool can miss comments from automated
reviewers.

```
Step 1 — pr-comments (1 click) — fetch ALL inline comments via REST API
Step 2 — Triage — group by file, classify, plan fixes
Step 3 — Implement — apply fixes file-by-file
Step 4 — Verify — compile and run tests
Step 5 — Summary — report what was fixed
```

---

## Part B — Step 1 — Fetch review comments

> **Why not `currentActivePullRequest`?** The VS Code built-in tool uses
> GraphQL `reviewThreads` which can return an empty list for comments from
> automated reviewers (Copilot, GitHub Actions bots). The REST endpoint
> `GET /repos/{owner}/{repo}/pulls/{N}/comments` is authoritative and always
> returns every inline comment.

**Windows:**
```powershell
.\.agents\skills\pr-review\pr-comments.ps1 -Pr <number>
```
**Linux / macOS:**
```bash
bash .agents/skills/pr-review/pr-comments.sh <number>
```

The script:
1. Fetches all reviews and inline comments via `gh api`
2. Maps each comment to its parent review state (`CHANGES_REQUESTED`, `COMMENTED`, etc.)
3. Groups comments by file
4. Saves structured JSON to `$env:TEMP\pr<N>-comments.json` (Windows) or
   `/tmp/pr<N>-comments.json` (Linux/macOS)

**Filtering options:**
- `-Author Copilot` — show only Copilot comments
- `-ExcludeOutdated` — hide comments on lines no longer in the diff

If no comments are found, stop — nothing to fix.

---

## Part B — Step 2 — Triage comments

Read the structured JSON (`$env:TEMP\pr<N>-comments.json`) and for each comment:

1. **Skip** if `outdated: true` — the code has already changed, the comment
   may no longer apply. Note it in the summary as "outdated, skipped".
2. **Skip** if `in_reply_to` is not null — it is a reply in a thread, not
   a top-level finding. Process only the root comment of each thread.
3. **Read the target file** at the indicated line. Verify the comment still
   applies to the current code. If the code already matches what the comment
   requests, note it as "already addressed".
4. **Classify** each remaining comment:
   - **Actionable** — a concrete code change is requested (e.g. "remove
     `type: http`", "use `assertInstanceOf`", "add null guard")
   - **Discussion** — an open question or suggestion without a clear fix.
     Note it for the summary; do not make speculative changes.

Group actionable comments by file path — this minimizes `read_file` calls
and lets you apply all fixes to a file in a single edit.

Present the triage plan before implementing:

| # | File | Line | Author | Classification | Planned action |
|---|------|------|--------|----------------|----------------|
| 1 | `path/to/Foo.java` | 46 | Copilot | Actionable | Remove `type` from YAML fixture |
| 2 | `path/to/Bar.java` | 187 | Copilot | Actionable | Replace `assertTrue(instanceof)` with `assertInstanceOf` |
| 3 | `path/to/Baz.java` | 219 | jlouv | Discussion | No code change — note in summary |

Wait for the user to confirm before proceeding.

---

## Part B — Step 3 — Implement fixes

Work through the confirmed plan file by file:

1. **Read** the file (or the relevant section around the target line)
2. **Apply** the fix with `insert_edit_into_file`
3. Do **not** refactor or modify code outside the scope of each comment
4. If a comment is ambiguous, prefer the minimal safe interpretation

> **Pattern recognition** — look for systematic issues. If one comment says
> "remove `type: http` from import fixtures" and the file has 6 similar
> fixtures, fix all of them — not just the one the comment points to. This
> is the key benefit of structured detection: comments on one instance often
> imply the same fix across all instances in the file.

---

## Part B — Step 4 — Verify

After all fixes are applied:

1. **Compile** — run `mvn compile -pl <module> -q` to catch syntax errors
2. **Run affected tests** — `mvn test -pl <module> -Dtest="<TestClass1>,<TestClass2>" -DfailIfNoTests=false`
3. If tests fail, read the failure output and fix. Do not loop more than
   3 times on the same file — ask the user instead.

---

## Part B — Step 5 — Summary

Provide a concise table:

| # | File | Author | Comment summary | Action taken |
|---|------|--------|-----------------|--------------|
| 1 | `path/Foo.java` | Copilot | Remove `type` from import fixture | ✅ Removed from 6 fixtures |
| 2 | `path/Bar.java` | Copilot | `assertTrue(instanceof)` is always true | ✅ Changed to `assertInstanceOf` |
| 3 | `path/Baz.java` | Copilot | Add null guard on stream | ✅ Added `assertNotNull` |
| 4 | `path/Old.java` | reviewer | Rename variable | ⏭️ Outdated — line no longer in diff |

Report test results: `N tests, 0 failures, BUILD SUCCESS ✅`
