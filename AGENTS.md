# Naftiko Framework — Agent Guidelines

## Project Context

**Naftiko Framework** is the engine for [Spec-Driven Integration](https://github.com/naftiko/framework/wiki/Spec%E2%80%90Driven-Integration). Capabilities are declared entirely in YAML — no Java required. The framework parses them and exposes them via MCP, SKILL, or REST servers.

- **Language**: Java 21, Maven build system
- **Specification**: `src/main/resources/schemas/naftiko-schema.json` — keep this as first-class citizen in your context
- **Wiki**: https://github.com/naftiko/framework/wiki (Specification, Tutorial, Use Cases, FAQ)

## Key Files

| Path | Purpose |
|---|---|
| `src/main/resources/schemas/naftiko-schema.json` | Naftiko JSON Schema (source of truth) |
| `src/main/resources/schemas/examples/` | Capability examples (`cir.yml`, `notion.yml`, `skill-adapter.yml`, ...) |
| `src/main/resources/schemas/tutorial/` | Step-by-step tutorial (`step-1-` to `step-6-`) |
| `src/test/resources/` | Test fixtures (not examples) |
| `src/main/resources/scripts/pr-check-wind.ps1` | Local pre-PR validation (Windows) |
| `src/main/resources/scripts/pr-check-mac-linux.sh` | Local pre-PR validation (Unix/macOS) |
| `CONTRIBUTING.md` | Full contribution workflow |

## Build & Test

All commands must be run from the repository root (`framework/`).

```bash
# Run unit tests (standard local workflow — requires JDK 21)
mvn clean test --no-transfer-progress

# Build Docker image (Maven runs inside Docker — no local Maven needed)
docker build -f src/main/resources/deployment/Dockerfile -t naftiko .

# Build native CLI binary (requires GraalVM 21 — triggered by version tags in CI)
mvn -B clean package -Pnative

# Pre-PR validation (Windows)
.\src\main\resources\scripts\pr-check-wind.ps1

# Pre-PR validation (Unix)
bash ./src/main/resources/scripts/pr-check-mac-linux.sh
```

## Local Bootstrap

Before contributing, ensure your local environment has at least JDK 21 and Maven.

**Required:** JDK 21, Maven 3.9+

```bash
java -version    # must be 21+
mvn -version     # must be 3.9+
```

Trivy and Gitleaks are **not required locally** — they run automatically in CI. The `pr-check` scripts use them if installed, but `mvn clean test` is enough to validate your changes before a PR.

If you still want to run the full pre-PR checks locally, install [Trivy](https://github.com/aquasecurity/trivy#installation) and [Gitleaks](https://github.com/zricethezav/gitleaks#installation).

## Code Style

**Java** — follows Google Style. Configure VS Code with `Language Support for Java by Red Hat` and apply settings from [naftiko/code-standards — java](https://github.com/naftiko/code-standards/tree/main/java).

Never modify CI/CD workflows (`.github/workflows/`), security configs, or branch protection rules.

## Capability Design Rules

When designing or modifying a Capability:

**Do:**
- Keep the [Naftiko Specification](src/main/resources/schemas/naftiko-schema.json) as first-class citizen
- Look at `src/main/resources/schemas/examples/` for patterns before writing new capabilities

**Don't:**
- Expose an `inputParameter` that is not used in any step
- Declare consumed `outputParameters` that are not used in the exposed part
- Prefix variables with the capability/namespace/resource name — they are already scoped, unless disambiguation is strictly needed

## Contribution Workflow

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full workflow. Key rules:

- Open an Issue before starting work
- Branch from `main`: `feat/`, `fix/`, or `chore/` prefix
- Use [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `chore:` — no scopes for now
- Rebase on `main` before PR — linear history, no merge commits
- One logical change per PR — keep it atomic
- CI must be green (build, tests, schema validation, Trivy, Gitleaks)
- Do **not** use `git push --force` — use `--force-with-lease`

## Bug Workflow (mandatory)

When you identify a bug — whether discovered during development, debugging, or user-reported — follow these steps **in order** before writing any fix:

### 1. Open an Issue

Create a GitHub issue using the **Bug Report** template (`.github/ISSUE_TEMPLATE/bug_report.yml`).
Fill in all required fields: component, description (actual vs expected), steps to reproduce, root cause if known, proposed fix.
If the PR was created or assisted by an AI agent, fill in the **Agent Context** block.

If you cannot create the issue directly (e.g. no `gh` CLI available, no API token), provide the user with all the elements needed to create it manually: suggested title, label, filled-in template body ready to paste. Do not proceed to step 2 until the user confirms the issue number.

### 2. Create a dedicated branch from up-to-date `main`

If there is any work in progress on the current branch (modified files, untracked files), save it first so nothing is lost and the user can return to it after the fix:

```bash
git stash push -m "wip: <description>" -- <only the relevant files>
# or, if everything on the branch belongs to the in-progress work:
git stash push -m "wip: <description>"
```

Note the stash ref or branch name so you can restore it later with `git stash pop` or `git checkout <branch>`.

Then create the fix branch from up-to-date `main`:

```bash
git checkout main
git pull origin main
git checkout -b fix/<short-description>
```

Never start a fix branch from a feature branch or a stale local `main`.
When the fix is merged, remind the user to switch back to their original branch and restore the stash if needed.

### 3. Write non-regression tests before committing the fix

For every bug fix, two tests are required:

**Unit test** — targets the smallest unit of code that contains the bug (method or class level). Place it in the test class corresponding to the fixed class (e.g. `ConverterTest`, `ResolverTest`). If the class has no test file yet, create one. If a test already covers the scenario but is wrong, fix the test first and explain why in a comment.

**Integration test** — validates the fix end-to-end, typically loading a YAML capability fixture and exercising the full chain (deserialization → engine → output). Place the fixture in `src/test/resources/` and the test class in the package closest to the integration point (e.g. `io.naftiko.engine.exposes.mcp`).

Run the full test suite before committing:

```bash
mvn test
```

All existing tests must stay green. If a pre-existing test fails, investigate before touching it.
