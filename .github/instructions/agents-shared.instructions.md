---
description: "Use when: reviewing a PR, reviewing a pull request, doing a code review, posting inline comments on a PR, reviewing PR #<number>, fixing PR comments, addressing PR feedback, fixing PR #<number>, addressing review comments, fixing Copilot comments, resolving PR threads; OR fixing a bug, investigating an issue (bugfix skill); OR building an Ikanos capability / MCP server (ikanos-capability skill); OR drafting release notes, writing release notes for a tag (release-notes-drafting skill). Tells the agent how to fetch a shared skill from the agents-shared capability before following it."
name: "agents-shared skill discovery"
---
# Shared agent skills — `agents-shared` capability

The transversal skills served to this repository — `pr-review`, `bugfix`,
`ikanos-capability`, and `release-notes-drafting` — are **not** committed here. They are
versioned once in the
[`golden-repo-naftiko`](https://github.com/naftiko/golden-repo-naftiko) golden repo and served
read-only by the `agents-shared` Ikanos capability, as Agent Skills over MCP (SEP-2640).

## How to use a skill

`<skill>` below is the skill name: `pr-review`, `bugfix`, `ikanos-capability`, or
`release-notes-drafting`.

1. **Check for a local copy first.** If `.agents/skills/<skill>/SKILL.md` exists in this
   repository, it is a synced, git-ignored copy. To detect a stale copy, run (from the
   repository root):

   ```bash
   python .github/instructions/agents-shared-sync.py --check <skill>
   ```

   Exit code 0 means up to date: read the local `SKILL.md` and follow it. Exit code 1 means
   missing or stale: sync it (step 2). Exit code 2 means an error (see stderr). If the server
   is unreachable, use the local copy if there is one and tell the user it may be stale;
   otherwise see *If the server is not running* below.

2. **Sync it from the agents-shared server.** The server URL defaults to
   `http://localhost:9700`; the `AGENTS_SHARED_URL` environment variable overrides it. The
   same command works on Windows, Linux and macOS (use `python3` where `python` is not on
   PATH):

   ```bash
   python .github/instructions/agents-shared-sync.py <skill>
   ```

   The helper (Python 3.8+, standard library only) fetches every file of the skill over MCP,
   verifies each one against the digest the server announces, and writes them under
   `.agents/skills/<skill>/`. `--list` shows the skills the server publishes.

3. **Then read `.agents/skills/<skill>/SKILL.md` and follow it** for the requested task.
   Do not improvise the workflow from memory.

## If the server is not running

The capability runs from a local checkout of `golden-repo-naftiko` via
`ikanos serve agents-shared.yml` in its `agents-shared/` directory — see
`agents-shared/README.md` in that repo for setup and for a `gh api` fallback that needs no
server. Ask the user to start the server or fetch the skill via the fallback; do not copy the
skill into version control.

## Rules

- The synced copies under `.agents/skills/` are **git-ignored** — never commit them.
- Never edit a synced copy; improvements go to `golden-repo-naftiko` (issue-first).
- Never edit `agents-shared-sync.py` here either: it is a copy of
  `agents-shared/instructions/agents-shared-sync.py` in `golden-repo-naftiko`.
