CONTRIBUTING.md — Naftiko Framework
Contributing to Naftiko Framework
> We welcome **all** contributions to Naftiko Framework, from the smallest to the largest — they all make a positive impact. This guide applies to both human developers and AI-assisted coding agents.
> 
---
TL;DR
Open an Issue before starting work
Fork the repo and branch from `main` (`feat/`, `fix/`, `chore/`)
Keep PRs atomic, rebased on `main`, with CI green
A maintainer will review and merge your PR
All contributions are accepted under the Apache 2.0 License
---
Bugs & Features
Report bugs and suggest features in the Issue Tracker
Please search existing issues before creating a new one to avoid duplicates
When opening an issue, select the appropriate template — GitHub will guide you through the required fields:
Bug Report — for unexpected behavior or broken functionality
Feature Request — for new capabilities or improvements
Discuss the issue directly in the thread before starting implementation
---
Code Contributions
1. Fork & branch
Fork the repository and create a branch from `main`
Follow branch naming conventions:
Prefix	Purpose
`feat/`	New feature or capability
`fix/`	Bug fix
`chore/`	Maintenance, deps, refactoring
2. Develop
Follow Conventional Commits for commit messages:
```
feat: add OAuth login
fix: handle null customer ID
chore: bump dependencies
```
Rebase on `main` before requesting a review — we maintain a linear history (no merge commits)
Use `git push --force-with-lease` (never `--force`)
Run the local validation script before opening your PR:
```bash
# Unix/macOS
./scripts/pr-check.sh

# Windows (PowerShell)
.\scripts\pr-check.ps1
```
3. Open a Pull Request
Submit your work via a Pull Request:
[ ]  Fill in the PR template (`.github/PULL_REQUEST_TEMPLATE.md`, loaded automatically by GitHub)
[ ]  Link the related Issue
[ ]  Ensure CI is green (build, tests, schema validation, security scans)
[ ]  Keep it small and focused — one concern per PR
4. Review & merge
A maintainer will be assigned automatically, or you can mention `@naftiko/core`
Expect feedback within a few business days
Address requested changes by pushing new commits
Once approved, a maintainer will handle the merge — you don't need to do anything else
<aside>
💡
First-time contributors: don't hesitate to open a draft PR early if you want guidance before your work is complete.
</aside>
---
For AI Agents
This section provides machine-readable guidance for AI coding agents contributing to this repository.
Repository context
Language: Java 21 (Maven build system)
Specification: The Naftiko Specification defines the capability schema. See `src/main/resources/schemas/naftiko-schema.json` for the latest JSON Schema.
Examples: `src/main/resources/schemas/examples/` contains capability examples. `src/main/resources/schemas/tutorial/` contains step-by-step tutorial capabilities.
Test fixtures: `src/test/resources/` contains YAML capabilities used for unit tests.
Wiki: the project wiki contains the full specification, tutorial, FAQ, and use cases.
Agent contribution rules
Follow all human contribution rules above — no exceptions
Branch, commit, and PR naming conventions are mandatory
PRs must pass all CI checks (build, tests, Trivy, Gitleaks, schema validation)
Keep changes atomic: one logical change per PR
Always include a clear PR description explaining the problem and solution
Do not modify CI/CD workflows, security configs, or branch protection rules
Keep the Naftiko Specification as a first-class citizen in your context
Key files for agent context
File / Path	Purpose
`src/main/resources/schemas/naftiko-schema.json`	Naftiko Specification JSON Schema (latest)
`src/main/resources/schemas/examples/`	Capability examples: `cir.yml`, `notion.yml`, `skill-adapter.yml`, `multi-consumes-*.yml`…
`src/main/resources/schemas/tutorial/`	Step-by-step tutorial capabilities (`step-1-` → `step-6-`)
`src/test/resources/`	Test fixtures (not examples)
`.github/workflows/`	CI/CD pipelines
`scripts/pr-check.sh`	Local pre-PR validation script
`CONTRIBUTING.md`	This file
---
License
All contributions are accepted under the Apache 2.0 License.
<aside>
⚠️
You must ensure you have full rights on the code you are submitting, for example from your employer.
</aside>