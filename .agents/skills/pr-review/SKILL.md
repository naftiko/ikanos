---
name: pr-review
version: "1.0.0"
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
fetch the diff → compute line numbers accurately → verify each line →
present findings → post only after explicit user confirmation.

---

## Step 1 — Fetch and save the diff

Save the diff to a temp file to enable repeated querying without extra API calls.

```powershell
# Windows (PowerShell)
gh pr diff <number> | Out-File -FilePath "$env:TEMP/pr<number>.diff" -Encoding utf8
```

```bash
# Linux / macOS
gh pr diff <number> > /tmp/pr<number>.diff
```

Then list the changed files for an overview:

```powershell
# Windows (PowerShell)
gh pr view <number> --json files | ConvertFrom-Json |
  Select-Object -ExpandProperty files |
  Select-Object path, additions, deletions, status
```

```bash
# Linux / macOS
gh pr view <number> --json files | python3 -c \
  "import json,sys; [print(f['path'], f['additions'], f['deletions']) for f in json.load(sys.stdin)['files']]"
```

---

## Step 2 — Compute line numbers for inline comments

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

## Step 3 — Verify each line number before reporting

**Always run the algorithm in a terminal** for each finding. Do not estimate or compute mentally.

```powershell
# Windows (PowerShell)
$diff = Get-Content "$env:TEMP/pr<number>.diff" -Encoding utf8
$inFile = $false; $inHunk = $false; $lineNum = 0
foreach ($line in $diff) {
    if ($line -match "^diff --git") {
        $inFile = $line -match "YourFile\.java"
        $inHunk = $false
        $lineNum = 0
    }
    if ($inFile) {
        if ($line -match "^@@") {
            $inHunk = $true
            $m = [regex]::Match($line, '\+(\d+)')
            if ($m.Success) { $lineNum = [int]$m.Groups[1].Value - 1 }
        } elseif ($inHunk -and $line -match "^\+") { $lineNum++ }
        elseif ($inHunk -and $line -match "^ ")  { $lineNum++ }
        # -: do not increment
        if ($inHunk -and $line -notmatch "^-" -and $line -match "pattern to find") {
            Write-Host "L$lineNum [$line]"
        }
    }
}
```

```bash
# Linux / macOS (POSIX awk — compatible with macOS BSD awk and gawk)
awk '
  /^diff --git/ { in_file = ($0 ~ "YourFile\\.java"); in_hunk = 0; line_num = 0 }
  in_file && /^@@/ {
    if (match($0, /\+[0-9]+/)) line_num = substr($0, RSTART + 1, RLENGTH - 1) - 1
    in_hunk = 1
  }
  in_file && in_hunk && /^\+/ { line_num++ }
  in_file && in_hunk && /^ /  { line_num++ }
  in_file && in_hunk && !/^-/ && /pattern to find/ { print "L" line_num " [" $0 "]" }
' /tmp/pr<number>.diff
```

**Before including any line in the findings table**: confirm the output shows a `+` or ` `
line. A `-` line or a line not present in the diff cannot be targeted — GitHub will
reject the comment silently.

---

## Step 4 — Present findings, then branch on user response

Present all findings to the user in a table:

| # | File | Line | Severity | Comment (raw) |
|---|------|------|----------|---------------|
| 1 | `path/to/File.java` | L42 | 🔴 HIGH | Comment text ready to paste |

Severity scale: 🔴 HIGH (blocking) · 🟡 MEDIUM · 🔵 LOW (nit).

**Then wait for the user's response and branch:**

### Branch A — User wants clarifications

Answer questions and refine findings. Do **not** post anything yet.
Return to this step when the user is ready.

### Branch B — User confirms: post the review

Submit all inline comments in a **single** API call. Do not post without explicit
user confirmation — this is an irreversible action on a shared system.

```bash
gh api repos/{owner}/{repo}/pulls/<number>/reviews \
  --method POST \
  --field event=COMMENT \
  --field "comments[][path]=src/main/java/io/naftiko/Foo.java" \
  --field "comments[][line]=42" \
  --field "comments[][body]=Your comment here." \
  --field "comments[][path]=src/main/resources/schemas/naftiko-schema.json" \
  --field "comments[][line]=2586" \
  --field "comments[][body]=Another comment."
```

Use `event=COMMENT` for a non-approving review.
Use `event=REQUEST_CHANGES` when at least one finding is blocking (🔴 HIGH).
Use `event=APPROVE` only when explicitly asked to approve the PR.

Retrieve owner/repo from the local git remote if not known:

```bash
gh repo view --json nameWithOwner -q .nameWithOwner
```

After posting, verify the review was accepted:

```bash
gh api repos/{owner}/{repo}/pulls/<number>/reviews --jq '.[-1] | {id, state, submitted_at, body}'
```

Then check that all expected inline comments are present:

```bash
gh api repos/{owner}/{repo}/pulls/<number>/comments --jq '[.[] | {path, line, body}]'
```

GitHub silently drops comments that target a line outside the diff or with a malformed
`path` — the review POST returns HTTP 200 even in that case. Compare the returned
comment count against the number of findings submitted. If any are missing, identify
the rejected entry (wrong line number or path mismatch) and report it to the user with
the corrected values so they can post it manually.
