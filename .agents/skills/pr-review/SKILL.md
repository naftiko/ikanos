---
name: pr-review
version: "2.0.1"
description: >
  On-demand skill for reviewing GitHub Pull Requests and posting inline
  comments via the GitHub API. Activate when the user asks to: review a PR,
  review a pull request, do a code review, post inline comments on a PR,
  comment on a pull request, review PR #<number>.
allowed-tools:
  - Read
  - Bash
---

## Overview

This skill guides the agent through a structured PR review workflow:

```
Step 1 — pr-context (1 click)
Step 2 — Read diff → identify issues → present list WITHOUT line numbers
Step 3 — User picks which findings to post
Step 4 — Resolve line numbers for picked findings only
          Short diff (<500 lines): compute from context, 0 clicks
          Long diff  (≥500 lines): pr-find-lines-batch on picked items, 1 click
Step 5 — pr-submit (1 click)
```

**Minimum 2 clicks (short diff). Maximum 3 clicks (long diff), regardless of the
number of findings.**

Line numbers are resolved **after** the user picks — never before. There is no
point verifying a line number for a finding the user will not post.

### Helper scripts

| Script | Platform | When to use |
|--------|----------|-------------|
| `pr-context.ps1 -Pr <N>` | Windows | **Once**, Step 1 |
| `pr-context.sh <N>` | Linux / macOS | Same |
| `pr-find-lines-batch.ps1 -Pr <N> [-InputFile <path>]` | Windows | **Once**, Step 4, long diff only |
| `pr-find-lines-batch.sh <N> [input-file]` | Linux / macOS | Same |
| `pr-submit-review.ps1 -Pr <N> -InputFile <path>` | Windows | **Once**, Step 5 |
| `pr-submit-review.sh <N> <input-json>` | Linux / macOS | Same |

---

## Step 1 — Check for existing review activity

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

## Step 2 — Read the diff and identify issues

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

Do **not** compute line numbers yet.

---

## Step 3 — Present findings and wait for the user's pick

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

Wait for the user to confirm which findings to post before proceeding.

---

## Step 4 — Resolve line numbers for picked findings only

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

## Step 5 — Post the review

Build the review JSON with the confirmed line numbers and save it with `create_file`
to `$env:TEMP\review-<number>.json` (Windows) or `/tmp/review-<number>.json`
(Linux/macOS):

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
