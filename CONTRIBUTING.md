# Contributing to Ikanos

> We welcome **all** contributions to Ikanos, from the smallest to the largest — they all make a positive impact. This guide applies to both human developers and AI-assisted coding agents.

---

## TL;DR

1. Open an **Issue** before starting work
2. Fork the repo and branch from `main` (`feat/`, `fix/`, `chore/`)
3. Keep PRs **atomic**, rebased on `main`, with CI green
4. A **maintainer** will review and merge your PR
5. All contributions are accepted under the [Apache 2.0 License](https://github.com/naftiko/ikanos/blob/main/LICENSE)

---

## Local Bootstrap

Before contributing, ensure your local environment has at least JDK 21 and Maven.

**Required:** JDK 21, Maven 3.9+

```bash
java -version    # must be 21+
mvn -version     # must be 3.9+
```

Trivy and Gitleaks are **not required locally** — they run automatically in CI. The `pr-check` scripts use them if installed, but `mvn clean test` is enough to validate your changes before a PR.

If you still want to run the full pre-PR checks locally, install [Trivy](https://github.com/aquasecurity/trivy#installation) and [Gitleaks](https://github.com/zricethezav/gitleaks#installation), then run:

```bash
# Unix/macOS
bash ./scripts/pr-check-mac-linux.sh

# Windows (PowerShell)
.\scripts\pr-check-wind.ps1
```

---

## Bugs & Features

- Report bugs and suggest features in the [Issue Tracker](https://github.com/naftiko/ikanos/issues)
- Please **search existing issues** before creating a new one to avoid duplicates
- When opening an issue, select the appropriate **template** — GitHub will guide you through the required fields:
  - **Bug Report** — for unexpected behavior or broken functionality
  - **Feature Request** — for new capabilities or improvements
- Discuss the issue directly in the thread before starting implementation

---

## Code Contributions

### 1. Fork & branch

- Fork the repository and create a branch from `main`
- Follow branch naming conventions:

| Prefix | Purpose |
|--------|-------------------------------|
| `feat/` | New feature or capability |
| `fix/` | Bug fix |
| `chore/` | Maintenance, deps, refactoring |

### 2. Develop

- Follow [Conventional Commits](https://www.conventionalcommits.org/) for commit messages:

        feat: add OAuth login
        fix: handle null customer ID
        chore: bump dependencies

- **Rebase on `main`** before requesting a review — we maintain a linear history (no merge commits)
- Use `git push --force-with-lease` (never `--force`)
- Run the local validation script before opening your PR:

        # Unix/macOS
        bash ./scripts/pr-check-mac-linux.sh

        # Windows (PowerShell)
        .\scripts\pr-check-wind.ps1

### 3. Open a Pull Request

Submit your work via a [Pull Request](https://github.com/naftiko/ikanos/pulls):

- [ ] Fill in the **PR template** (`.github/PULL_REQUEST_TEMPLATE.md`, loaded automatically by GitHub)
- [ ] Link the related Issue
- [ ] Ensure **CI is green** (build, tests, schema validation, security scans)
- [ ] Keep it **small and focused** — one concern per PR

### 4. Review & merge

- A maintainer will be assigned automatically, or you can mention `@naftiko/core`
- Expect feedback within a few business days
- Address requested changes by pushing new commits
- Once approved, a **maintainer will handle the merge** — you don't need to do anything else

> 💡 **First-time contributors**: don't hesitate to open a draft PR early if you want guidance before your work is complete.

---

## For AI Agents

This section provides **machine-readable guidance** for AI coding agents contributing to this repository.

### Repository context

- **Language**: Java 21, Maven (multi-module: `ikanos-spec`, `ikanos-engine`, `ikanos-cli`, `ikanos-docs`)
- **Specification**: The Ikanos Specification defines the capability schema. See `ikanos-spec/src/main/resources/schemas/ikanos-schema.json` for the latest JSON Schema.
- **Examples**: `ikanos-spec/src/main/resources/schemas/examples/` contains capability examples. `ikanos-docs/tutorial/` contains step-by-step tutorial capabilities.
- **Test fixtures**: `ikanos-engine/src/test/resources/` and `ikanos-cli/src/test/resources/` contain YAML capabilities used for unit tests.
- **Wiki**: the [project wiki](https://github.com/naftiko/ikanos/wiki) contains the full specification, tutorial, FAQ, and use cases.

### Agent contribution rules

- Follow **all human contribution rules** above — no exceptions
- Branch, commit, and PR naming conventions are mandatory
- PRs must pass all CI checks (build, tests, Trivy, Gitleaks, schema validation)
- Keep changes **atomic**: one logical change per PR
- Always include a clear PR description explaining the problem and solution
- Do **not** modify CI/CD workflows, security configs, or branch protection rules
- Keep the Ikanos Specification as a **first-class citizen** in your context

### Key files for agent context

| File / Path | Purpose |
|---|---|
| `ikanos-spec/src/main/resources/schemas/ikanos-schema.json` | Ikanos Specification JSON Schema (latest) |
| `ikanos-spec/src/main/resources/schemas/examples/` | Capability examples: `cir.yml`, `notion.yml`, `skill-adapter.yml`, `multi-consumes-*.yml`... |
| `ikanos-spec/src/main/resources/rules/ikanos-rules.yml` | Polychro ruleset (cross-object consistency, quality, security) |
| `ikanos-docs/tutorial/` | Step-by-step tutorial capabilities (`step-1-` to `step-10-`) |
| `ikanos-engine/src/test/resources/` and `ikanos-cli/src/test/resources/` | Test fixtures (not examples) |
| `.github/workflows/` | CI/CD pipelines |
| `scripts/pr-check-wind.ps1` | Local pre-PR validation (Windows) |
| `scripts/pr-check-mac-linux.sh` | Local pre-PR validation (Unix/macOS) |
| `CONTRIBUTING.md` | This file |

---

## License

All contributions are accepted under the [Apache 2.0 License](https://github.com/naftiko/ikanos/blob/main/LICENSE).

> ⚠️ You must ensure you have **full rights** on the code you are submitting, for example from your employer.