---
name: pr-review
version: "1.3.1"
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
check for existing reviews → offer Continue/Fresh start choice → fetch the diff →
compute line numbers accurately → verify each line number →
present findings → post only after explicit user confirmation.

### Helper scripts (Windows / PowerShell)

Three reusable scripts live in this skill's directory to minimize the number of
terminal commands the user must confirm. **Use them instead of the manual ad-hoc
commands shown in each step.**

| Script | Platform | Replaces | When to use |
|--------|----------|----------|-------------|
| `pr-context.ps1 -Pr <N>` | Windows (PowerShell) | Steps 1 + 2 (5+ commands) | Once, at the start of every review |
| `pr-context.sh <N>` | Linux / macOS | Steps 1 + 2 (5+ commands) | Once, at the start of every review |
| `pr-find-lines.ps1 -Pr <N> -File <glob> -Pattern <regex>` | Windows (PowerShell) | Steps 3 + 4 (one script per finding) | Once per finding, after context is loaded |
| `pr-find-lines.sh <N> <file-pattern> <line-pattern>` | Linux / macOS | Steps 3 + 4 (one script per finding) | Once per finding, after context is loaded |
| `pr-submit-review.ps1 -Pr <N> -InputFile <path>` | Windows (PowerShell) | Step 5 Branch B + verification | Once, after the user confirms the findings |
| `pr-submit-review.sh <N> <input-json>` | Linux / macOS | Step 5 Branch B + verification | Once, after the user confirms the findings |

---

## Step 1 — Check for existing review activity

**Windows (PowerShell) — run the context script:**

```powershell
.\.agents\skills\pr-review\pr-context.ps1 -Pr <number>
```

**Linux / macOS — run the context script:**

```bash
bash .agents/skills/pr-review/pr-context.sh <number>
```

Both scripts fetch PR metadata, changed files, existing reviews (with IDs and states),
existing inline comments (with `pull_request_review_id` linkage), and save the diff to
`$env:TEMP\pr<number>.diff` (Windows) or `/tmp/pr<number>.diff` (Linux/macOS).
Read the output to determine whether prior review activity exists.

**If no reviews and no inline comments exist** → proceed directly to Step 2.

**If reviews or comments already exist**, summarize what was found and ask the user:

> «This PR already has N review(s) and M inline comment(s) (latest state: X, by [reviewers]).
> How do you want to proceed?
> - **Continue** — check `CHANGES_REQUESTED` review bodies and their linked inline comments first, skip `outdated` inline comments, then add net-new findings only.
> - **Fresh start** — ignore prior review activity and review the full diff from scratch.»

Wait for the user's answer before proceeding.

### If the user chooses **Continue**

The context script output already includes all review bodies and inline comment
linkage. Use it to:
- Identify `CHANGES_REQUESTED` reviews and their body feedback — verify whether each issue is fixed in the current diff
- For each inline comment whose `pull_request_review_id` matches a `CHANGES_REQUESTED` review ID: verify against the current diff; skip if `outdated`
- After checking those items, scan the diff for net-new findings only

### If the user chooses **Fresh start**

Ignore all prior review data. Proceed to Step 2 as if the PR had no prior activity.

---

## Step 2 — Fetch and save the diff

Already done by the context script — the diff is at:
- Windows: `$env:TEMP\pr<number>.diff`
- Linux / macOS: `/tmp/pr<number>.diff`

---

## Step 3 — Compute line numbers for inline comments

GitHub inline comments require the **line number in the resulting file** (after the
diff is applied). The algorithm:

1. When a `@@` hunk header is encountered, extract the `+N` value — the first line of
   the hunk in the new file. Initialize `counter = N - 1`.
2. For each subsequent line in the hunk:
   - Line starts with `+` (added): `counter++` → **valid target**
   - Line starts with ` ` (context): `counter++` → **valid target**
   - Line starts with `-` (removed): **do not increment** — this line no longer exists
     in the new file and **cannot** be targeted by an inline comment.
3. The counter value after processing a line is that line's number in the resulting file.

---

## Step 4 — Verify each line number before reporting

**Windows (PowerShell) — use the find-lines script (one call per finding):**

```powershell
.\.agents\skills\pr-review\pr-find-lines.ps1 -Pr <number> -File "YourFile.java" -Pattern "pattern to find"
```

**Linux / macOS — use the find-lines script (one call per finding):**

```bash
bash .agents/skills/pr-review/pr-find-lines.sh <number> "YourFile.java" "pattern to find"
```

Both scripts print `L<n> [+] <line>` for every match. Use the `L<n>` values directly
in the findings table and in the review input JSON.

**Before including any line in the findings table**: confirm the output shows a `+` or ` `
line. A `-` line or a line not present in the diff cannot be targeted — GitHub will
reject the comment silently.

---

## Step 5 — Present findings, then branch on user response

Present all findings to the user in a **working table** (conversation only — do not paste this table into GitHub):

| N | File | Line | Severity | Comment (raw) |
|---|------|------|----------|---------------|
| 1 | `path/to/File.java` | L42 | 🔴 HIGH | Comment text ready to paste |

Severity scale: 🔴 HIGH (blocking) · 🟡 MEDIUM · 🔵 LOW (nit).

> **Numbering rules for GitHub-bound text**
> - When the user selects a subset of findings to post, **renumber them sequentially
>   from 1** in the review `body` and inline comments — never carry over the
>   working-list numbers, which would create confusing gaps (e.g. "findings 2 and 5").
> - Plain integers (1, 2, 3 …) are fine in GitHub text to cross-reference findings.
> - **Never use `#N`** — GitHub renders `#N` as a hyperlink to issue or PR number N.

**All comments must be written in English**, regardless of the language used in the conversation with the user — see AGENTS.md.

**Then wait for the user's response and branch:**

### Branch A — User wants clarifications

Answer questions and refine findings. Do **not** post anything yet.
Return to this step when the user is ready.

### Branch B — User confirms: post the review

> **Pre-submission guard — always run this first, even on a retry**
> Before writing or posting any payload, check whether a review from you already exists:
> ```powershell
> gh api repos/{owner}/{repo}/pulls/<number>/reviews --jq '[.[] | {id, state, submitted_at, body: .body[:80]}]'
> ```
> - If a review with `state = CHANGES_REQUESTED` or `COMMENT` from `eskenazit` already exists → **do not post a new review**. Instead, inspect its inline comments with `gh api repos/{owner}/{repo}/pulls/<number>/comments` and report the current state to the user. Any missing inline comment can only be added as a follow-up `COMMENT`-event review, not as a duplicate `REQUEST_CHANGES`.
> - If no review exists (empty array or only PENDING) → proceed with submission below.
>
> This guard prevents irrecoverable duplicates when a previous submission appeared to fail (silent exit, timeout, ^C) but actually succeeded.

Submit all inline comments in a **single** API call. Do not post without explicit
user confirmation — this is an irreversible action on a shared system.

**Windows (PowerShell) — write a JSON input file, then run the submit script:**

```powershell
# 1. Write the review payload to a temp file (edit paths, lines, and bodies as needed)
$review = @{
    event    = "REQUEST_CHANGES"   # or COMMENT, APPROVE
    body     = "Overall summary — see inline comments. (1) blocking issue must be fixed before merge."
    comments = @(
        @{ path = "ikanos-engine/src/main/java/io/ikanos/Foo.java"; line = 42;   body = "Comment text." }
        @{ path = "src/.../Bar.java";                  line = 17;   body = "Another comment." }
    )
} | ConvertTo-Json -Depth 5
Set-Content -Path "$env:TEMP\review-<number>.json" -Encoding utf8 -Value $review

# 2. Submit (includes post-submission verification automatically)
.\.agents\skills\pr-review\pr-submit-review.ps1 -Pr <number> -InputFile "$env:TEMP\review-<number>.json"
```

**Linux / macOS — write a JSON input file, then run the submit script:**

```bash
# 1. Write the review payload to a temp file
cat > /tmp/review-<number>.json <<'EOF'
{
  "event": "REQUEST_CHANGES",
  "body": "Overall summary — see inline comments. (1) blocking issue must be fixed before merge.",
  "comments": [
    { "path": "ikanos-engine/src/main/java/io/ikanos/Foo.java", "line": 42, "body": "Comment text." },
    { "path": "src/.../Bar.java", "line": 17, "body": "Another comment." }
  ]
}
EOF

# 2. Submit (includes post-submission verification automatically)
bash .agents/skills/pr-review/pr-submit-review.sh <number> /tmp/review-<number>.json
```

Both scripts post via `gh api --input -` (no quoting hazard), run the verification GET
immediately, and warn if any comment was silently dropped by GitHub.

> **Never post thread replies before the review**
> Posting individual replies via `pulls/comments/{id}/replies` before the main review
> creates a separate "ghost" review per reply on GitHub. Always bundle all inline
> comments — including follow-up answers to existing threads — into a **single**
> `POST /reviews` call. To reply to an existing thread, add `in_reply_to` to the
> comment object in the JSON:
>
> ```powershell
> @{ path = "src/.../Foo.java"; line = 42; in_reply_to = <existing_comment_id>; body = "Reply." }
> ```
>
> The `line` field is still required even when using `in_reply_to`.

Use `event=COMMENT` for a non-approving review.
Use `event=REQUEST_CHANGES` when at least one finding is blocking (🔴 HIGH).
Use `event=APPROVE` only when explicitly asked to approve the PR.

> **Silent terminal output is not a failure signal**
> On Windows (PowerShell), a `gh api --method POST` command that returns to the prompt
> with **no output and no error** has likely succeeded — PowerShell does not always echo
> HTTP responses unless `--jq` or `-q` is used. Do **not** retry the submission based on
> a silent exit. Instead, immediately run the verification step below. Retrying a
> submitted review creates an irrecoverable duplicate (GitHub returns HTTP 422 on
> `DELETE` for non-pending reviews). The `pr-submit-review.ps1` script handles this
> automatically — prefer it over manual `gh api` calls on Windows.

After posting manually, verify the review was accepted:
- Windows: `gh api repos/{owner}/{repo}/pulls/<number>/reviews --jq '.[-1] | {id, state, submitted_at, body}'
- Linux / macOS: `gh api repos/{owner}/{repo}/pulls/<number>/reviews --jq '.[-1] | {id, state, submitted_at, body}'

Then check that all expected inline comments are present:

```bash
gh api repos/{owner}/{repo}/pulls/<number>/comments --jq '[.[] | {path, line, body}]'
```

GitHub silently drops comments that target a line outside the diff or with a malformed
`path` — the review POST returns HTTP 200 even in that case. Compare the returned
comment count against the number of findings submitted. If any are missing, identify
the rejected entry (wrong line number or path mismatch) and report it to the user with
the corrected values so they can post it manually.
