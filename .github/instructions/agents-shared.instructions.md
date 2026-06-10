---
description: "Use when: reviewing a PR, reviewing a pull request, doing a code review, posting inline comments on a PR, reviewing PR #<number>, fixing PR comments, addressing PR feedback, fixing PR #<number>, addressing review comments, fixing Copilot comments, resolving PR threads. Tells the agent how to fetch the shared pr-review skill from the agents-shared capability before following it."
name: "agents-shared skill discovery"
---
# Shared agent skills — `agents-shared` capability

The canonical `pr-review` skill (and, over time, other transversal skills) is **not**
committed to this repository. It is versioned once in the
[`code-standards`](https://github.com/naftiko/code-standards) golden repo and served
read-only by the `agents-shared` Ikanos capability (Skill Server, `type: skill`).

## How to use the skill

1. **Check for a local copy first.** If `.agents/skills/pr-review/SKILL.md` exists in this
   repository, read it and follow it — it is a synced, git-ignored copy.

2. **If missing (or the user asks to refresh it), sync it from the Skill Server.**
   The server URL defaults to `http://localhost:9700`; the `AGENTS_SHARED_URL` environment
   variable overrides it. Run the one-liner for the current OS:

   Windows (PowerShell):

   ```powershell
   $u = if ($env:AGENTS_SHARED_URL) { $env:AGENTS_SHARED_URL } else { "http://localhost:9700" }; Invoke-WebRequest "$u/skills/pr-review/download" -OutFile "$env:TEMP\pr-review.zip"; Expand-Archive "$env:TEMP\pr-review.zip" -DestinationPath ".agents/skills/pr-review" -Force
   ```

   Linux / macOS (bash):

   ```bash
   u="${AGENTS_SHARED_URL:-http://localhost:9700}"; curl -fsSL "$u/skills/pr-review/download" -o /tmp/pr-review.zip && mkdir -p .agents/skills/pr-review && unzip -o /tmp/pr-review.zip -d .agents/skills/pr-review
   ```

3. **Then read `.agents/skills/pr-review/SKILL.md` and follow it** for the requested task
   (review or fix). Do not improvise the workflow from memory.

## If the Skill Server is not running

The capability runs from a local checkout of `code-standards` via `ikanos serve` — see
`agents-shared/README.md` in that repo for setup (including the absolute `location:`
rewrite) and for a `gh api` fallback that needs no server. Ask the user to start the
server or fetch the skill via the fallback; do not copy the skill into version control.

## Rules

- The synced copy under `.agents/skills/` is **git-ignored** — never commit it.
- Never edit the synced copy; improvements go to `code-standards` (issue-first).
