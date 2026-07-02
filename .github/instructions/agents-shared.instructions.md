---
description: "Use when: reviewing a PR, reviewing a pull request, doing a code review, posting inline comments on a PR, reviewing PR #<number>, fixing PR comments, addressing PR feedback, fixing PR #<number>, addressing review comments, fixing Copilot comments, resolving PR threads; OR fixing a bug, investigating an issue (bugfix skill); OR building an Ikanos capability / MCP server (ikanos-capability skill). Tells the agent how to fetch a shared skill from the agents-shared capability before following it."
name: "agents-shared skill discovery"
---
# Shared agent skills — `agents-shared` capability

The transversal skills served to this repository — `pr-review`, `bugfix`, and
`ikanos-capability` — are **not** committed here. They are versioned once in the
[`naftiko-golden-repo`](https://github.com/naftiko/naftiko-golden-repo) golden repo and served
read-only by the `agents-shared` Ikanos capability (Skill Server, `type: skill`).

## How to use a skill

1. **Check for a local copy first.** If `.agents/skills/<skill>/SKILL.md` exists in this
   repository (where `<skill>` is `pr-review`, `bugfix`, or `ikanos-capability`), read it and
   follow it — it is a synced, git-ignored copy.

   > **Detecting a stale copy.** `SKILL.md` carries a `version` field in its front matter.
   > To avoid relying solely on a user-driven refresh, compare the local copy's `version`
   > against the server's (`GET {server}/skills/<skill>` exposes the published version);
   > if they differ, treat the local copy as stale and re-sync via step 2.

2. **If missing (or the user asks to refresh it), sync it from the Skill Server.**
   The server URL defaults to `http://localhost:9700`; the `AGENTS_SHARED_URL` environment
   variable overrides it. Run the one-liner for the current OS (replace `<skill>` with the
   skill name):

   Windows (PowerShell):

   ```powershell
   $u = if ($env:AGENTS_SHARED_URL) { $env:AGENTS_SHARED_URL } else { "http://localhost:9700" }; $s = "<skill>"; Invoke-WebRequest "$u/skills/$s/download" -OutFile "$env:TEMP\$s.zip"; Expand-Archive "$env:TEMP\$s.zip" -DestinationPath ".agents/skills/$s" -Force
   ```

   Linux / macOS (bash):

   ```bash
   u="${AGENTS_SHARED_URL:-http://localhost:9700}"; s="<skill>"; curl -fsSL "$u/skills/$s/download" -o "/tmp/$s.zip" && mkdir -p ".agents/skills/$s" && unzip -o "/tmp/$s.zip" -d ".agents/skills/$s"
   ```

3. **Then read `.agents/skills/<skill>/SKILL.md` and follow it** for the requested task.
   Do not improvise the workflow from memory.

## If the Skill Server is not running

The capability runs from a local checkout of `naftiko-golden-repo` via `ikanos serve` — see
`agents-shared/README.md` in that repo for setup (including the absolute `location:`
rewrite) and for a `gh api` fallback that needs no server. Ask the user to start the
server or fetch the skill via the fallback; do not copy the skill into version control.

## Rules

- The synced copies under `.agents/skills/` are **git-ignored** — never commit them.
- Never edit a synced copy; improvements go to `naftiko-golden-repo` (issue-first).
