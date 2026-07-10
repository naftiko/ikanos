# Ikanos — Agent Guidelines

## Project Context

**Ikanos** is the engine for [Spec-Driven Integration](https://shipyard.naftiko.io/docs/1.0.0-alpha3/concepts/spec-driven-integration/). Capabilities are declared entirely in YAML — no Java required. The framework parses them and exposes them via MCP, SKILL, or REST servers.

- **Language**: Java 21, Maven build system (multi-module: `ikanos-spec`, `ikanos-engine`, `ikanos-cli`, `ikanos-docs`)
- **Specification**: `modules/ikanos-spec/src/main/resources/schemas/ikanos-schema.json` — keep this as first-class citizen in your context
- **Shipyard**: https://shipyard.naftiko.io/docs/1.0.0-alpha3/ikanos/ (Specification, Tutorials, Use Cases, FAQ)

## Key Files

| Path | Purpose |
|---|---|
| `modules/ikanos-spec/src/main/resources/schemas/ikanos-schema.json` | Ikanos JSON Schema (source of truth) |
| `modules/ikanos-spec/src/main/resources/schemas/examples/` | Capability examples (`cir.yml`, `notion.yml`, `skill-adapter.yml`, ...) |
| `modules/ikanos-spec/src/main/resources/rules/ikanos-rules.yml` | Polychro ruleset (cross-object consistency, quality, security) |
| `modules/ikanos-docs/tutorial/` | Shipyard Track tutorial (`step-1-shipyard-` to `step-10-shipyard-`) |
| `modules/ikanos-engine/src/test/resources/` and `modules/ikanos-cli/src/test/resources/` | Test fixtures (not examples) |
| `scripts/pr-check-wind.ps1` | Local pre-PR validation (Windows) |
| `scripts/pr-check-mac-linux.sh` | Local pre-PR validation (Unix/macOS) |
| `CONTRIBUTING.md` | Full contribution workflow |

## Build & Test

All commands must be run from the repository root (`ikanos/`).

```bash
# Run unit tests (standard local workflow — requires JDK 21)
mvn clean test --no-transfer-progress

# Run a single module's tests after a clean (must also build upstream modules it depends on)
# `clean` wipes the reactor outputs, so `-pl modules/ikanos-engine` alone fails with
# "Could not find artifact io.ikanos:ikanos-spec" — add `-am` (also-make) to rebuild deps.
# `-am` is precisely what pulls in ikanos-spec, so `-pl modules/ikanos-engine -am` alone is enough;
# listing modules/ikanos-spec explicitly is redundant but harmless (kept here for readability):
mvn clean test -pl modules/ikanos-spec,modules/ikanos-engine -am --no-transfer-progress

# Build Docker image (Maven runs inside Docker — no local Maven needed)
docker build -f deployment/Dockerfile -t ikanos .

# Build native CLI binary (requires GraalVM 21 — triggered by version tags in CI)
mvn -B clean package -Pnative -pl modules/ikanos-cli -am

# Pre-PR validation (Windows)
.\scripts\pr-check-wind.ps1

# Pre-PR validation (Unix)
bash ./scripts/pr-check-mac-linux.sh
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

## Working on Windows

The primary development environment is Windows with PowerShell. These rules are
**environment hazards** that apply to *any* file you touch — Java, YAML capabilities,
JSON schemas, design docs, Markdown, anything — not just code. They are hard rules.

### Terminal & file I/O (PowerShell)

**Never pipe or redirect file *content* through PowerShell.** A pipe (`|`), a redirect
(`>`, `>>`), `Out-File`, or `Set-Content` decodes the byte stream through the console
encoding (CP850 / Windows-1252), **not** UTF-8 — every multi-byte character (`—`, `→`,
accented letters, emojis like 🔴🟡🔵) is mangled into mojibake (`ÔÇö`, `├ö├ç├Â`) **before**
it is written. Adding `-Encoding utf8NoBOM` does **not** help: it re-encodes an
already-corrupted string. This corrupts capability YAML, schema JSON, design docs, and docs
just as easily as source code.

**Do — to materialise a Git blob, ref, or any file content into a file:**
- Use `git checkout <ref> -- <path>` — Git writes the raw bytes, PowerShell never touches them. This is the safest option.
- If the content is untracked in a stash (e.g. `stash@{0}^3:path`), use `cmd /c 'git show "<ref>:<path>" > <file>"'` — the OS-level redirect keeps the bytes raw.
- When the change is small, prefer restoring the clean file with `git checkout HEAD -- <path>` and re-applying the edits with the file-edit tool (it handles UTF-8 outside PowerShell).

**Do — to create or overwrite a file from a string in PowerShell** (rare; prefer the file-creation tool):
- Use `[System.IO.File]::WriteAllText(path, content, (New-Object System.Text.UTF8Encoding($false)))` — UTF-8 **without** BOM. The plain `WriteAllText(path, content)` and `[Text.Encoding]::UTF8` both emit a BOM (`EF BB BF`) that breaks JSON/YAML/Java tooling.

**Do — to read or inspect a file's *bytes* (e.g. to judge whether it is valid UTF-8):**
- Read the raw bytes outside the console codec. Use the file-read tool, or decode the blob from the API directly: `gh api "repos/<owner>/<repo>/contents/<path>?ref=<sha>" --jq '.content'` returns base64 → `[System.Convert]::FromBase64String((... -replace '\s', ''))` gives the raw bytes.
- Confirm UTF-8 by counting **byte** sequences, not rendered characters: `E2 80 94` = `—`, `E2 86 92` = `→`, `F0 9F 94 B4` = `🔴`.

**Don't:**
- Don't use `git show <ref>:<path> | Out-File`, `... > file`, or `... | Set-Content` — guaranteed mojibake on any non-ASCII file.
- Don't trust a `-Encoding utf8NoBOM` on a piped stream to fix encoding — the damage is already done upstream of the write.
- Don't construct multiline `gh` issue/PR bodies as a terminal string — write a temp `.md` with the file-creation tool and pass `--body-file` (see Contribution Workflow).

> **When in doubt, the file-creation / edit tools are always safe** — they write UTF-8 correctly and bypass PowerShell entirely. Reach for the terminal only when a Git command can write the file itself.

## Parallel Agent Workflows

When multiple agents work on the same repository simultaneously, each agent must be fully isolated from the others. Use `git worktree` to achieve this — one worktree per issue, per agent.

### For the user (setting up parallel agents)

```bash
# Create an isolated worktree for each issue, branching from origin/main
git fetch origin main
git worktree add ../ikanos-issue-<N> -b feat/issue-<N>-short-description origin/main

# Open the worktree as an additional root in VS Code (required — agents only see open workspace folders)
code --add ../ikanos-issue-<N>

# Remove when the branch is merged
git worktree remove ../ikanos-issue-<N>
# If the worktree has local modifications, use: git worktree remove --force ../ikanos-issue-<N>
```

### For agents working in a worktree

- **Your working directory is the worktree root** (e.g. `../ikanos-issue-<N>/`) — treat it as the repository root for all commands
- **Never run `git checkout main`** inside a worktree — `main` is already checked out in the primary clone and Git will refuse. To create a new branch from up-to-date `main`, use: `git fetch origin main && git checkout -b fix/<name> origin/main`
- **Never stash, reset, or switch branches** in a worktree that belongs to another agent — each worktree is exclusively owned by one agent for the duration of the issue
- **`mvn clean test`** runs normally from the worktree root — the shared `~/.m2` cache is read-only-safe for parallel builds

### Invariant

> One worktree = one issue = one branch = one agent.

The primary clone (`ikanos/`) owns `main` and handles fetch/push. Agents never check out `main` directly.

### Inter-agent communication

Session memory (`/memories/session/`) is scoped to one conversation and is invisible to other agents. To pass information between agents working in parallel (e.g. review findings, task handoff), write to `/memories/repo/` — it is readable by any agent that has the repository open in its workspace.

**Pattern:**
1. Agent A (reviewer) writes findings to `/memories/repo/<topic>.md`
2. Agent B (fixer) reads it with `memory view /memories/repo/<topic>.md` and applies the changes
3. Agent B deletes the file after committing the fixes

**Naming convention:** use predictable paths so the receiving agent can find them without being told the exact filename. For PR reviews: `/memories/repo/pr-review-<PR>.md` where `<PR>` is the pull request number. The receiving agent lists `/memories/repo/` to discover pending handoffs.

Example: a PR-review agent writes findings to `/memories/repo/pr-review-<PR>.md`; the agent working on the branch reads it and commits the fixes directly, without GitHub round-trip.

> This pattern is called **Mode B** in the `pr-review` skill (synced from the `agents-shared`
> capability — see `.github/instructions/agents-shared.instructions.md`).
> Mode A posts findings as inline comments directly on GitHub instead.

## Code Style

**Java** — follows Google Style. Configure VS Code with `Language Support for Java by Red Hat` and apply settings from [naftiko/golden-repo-naftiko — java](https://github.com/naftiko/golden-repo-naftiko/tree/main/java).

**Method visibility** — prefer package-private (no modifier) over `private` for methods that implement non-trivial logic. This allows direct unit testing from the same package without reflection. Reserve `private` for truly internal helpers that are trivially covered by public API tests (e.g. one-liner formatters, simple getters).

**Visibility reduction — verify callers first** — before *narrowing* a method's visibility (e.g. `public` → package-private, in response to a review finding or the rule above), confirm the method is not called from another package. Use `vscode_listCodeUsages` (or grep the fully-qualified call sites) — a method invoked cross-package **must** stay `public`, and a finding that suggests otherwise is wrong. (Discovered on #548: a "make `populateMdc` package-private" suggestion was declined because it is called from both the `mcp` and `rest` packages.)

**Field type migration** — when changing the type of a field (e.g. `T` → `AtomicReference<T>`, plain `int` → `AtomicInteger`), grep the entire owning class for direct field accesses (`fieldName.someMethod()`, `fieldName.length`, etc.) before declaring the migration done. Updating only the getter and setter is not enough — every read inside the class becomes a compile error otherwise. The IDE may not flag these when the field name is also reused as a method parameter (shadowing). For long methods, snapshot the new wrapper into a local variable of the original type at the top of the method so the rest of the body stays unchanged and observes a consistent view for the call duration.

**Cross-cutting fixes — fix all sites in one pass** — when fixing a shared concern (logging, tracing, MDC, error handling, auth, a shared header), `grep` for **every** site that exhibits the pattern before coding — not just the one in the bug report — and fix them together. Each site you deliberately leave out must carry an explicit written justification (which becomes a PR comment). A reviewer can always find the Nth site you missed, so find it first; "the report only mentioned X and Y" is not a reason to skip the third site. (Discovered on #548: a first fix wired MDC pairing into the REST and MCP SERVER adapters but missed the third, `SkillServerResource`, drawing the PR's only MEDIUM review finding.)

Never modify CI/CD workflows (`.github/workflows/`), security configs, or branch protection rules.

## Test Writing Rules

When writing or generating tests, follow these rules:

**Do:**
- Before writing any test setup code, identify the test type: **unit test** (isolated, in-process, no external I/O) or **integration test** (exercises the full engine stack against real or shared remote endpoints). If the type is ambiguous, **ask the user before writing any code**
- In integration tests, let the YAML capability file and the CI environment own all endpoint configuration — if an endpoint is unreachable or incorrect, fix the YAML or the shared fixture, not the test
- Test behavior through the public API — assert observable outcomes, not implementation details
- When a method is not accessible from a test, make it package-private in the production code (remove `private`) rather than using reflection — this is the correct fix
- Write one focused assertion per test, or group only closely related assertions in a single test
- Name tests in the form `methodShouldDoSomethingWhenCondition`
- After renaming any string constant that tests assert against (OTel span names, error messages, log markers), run a codebase-wide grep for the old string across **all** test sources — not just the test class that was directly updated. Compilation failures in unrelated tests do not exempt this search from being exhaustive
- **Spec version in inline YAML inside Java sources** (text blocks **and** ordinary `String` literals — e.g. `Files.writeString(p, "ikanos: \"...\"\n")`) — never hardcode the version, not even in a one-line `writeString` or a negative-path fixture. Inject it via `io.ikanos.spec.util.VersionHelper.getSchemaVersion()` and `String.formatted(...)`, e.g. `private static final String IKANOS = VersionHelper.getSchemaVersion();` then `"ikanos: \"%s\"\n".formatted(IKANOS)` (or `"""ikanos: "%s" ... """.formatted(IKANOS)` for a block). Rationale: `VersionHelper` reads the Maven-filtered `version.properties` so the value follows `pom.xml` automatically; a hardcoded version silently rots and breaks the build at the next bump (discovered on the beta1 bump: `ValidateCommandTest` pinned `"1.0.0-alpha4"` in three `writeString` calls). Do **not** put placeholders in the published schema JSON — it must remain a ready-to-consume artifact
- **Spec version in YAML / JSON test fixtures** (files under `src/test/resources/`, examples, tutorial capabilities) keeps a real version string. When you add a new fixture location or file extension that contains `ikanos: <version>`, update `scripts/sync-schema-version.py` (and `scripts/ikanos_version.py` if the pattern needs extending) in the same PR so the next version bump picks it up. See `CONTRIBUTING.md` → *Writing tests — handling the spec version* for details

**Don't:**
- Mix unit test patterns (local mock servers, in-process stubs, hardcoded XML/JSON payloads) into integration test classes — each test class must be one type, not both
- Override `baseUri` or any YAML-declared value in Java test code as a workaround for a broken or outdated YAML file — that hides the real problem and propagates incorrect patterns to future tests
- Treat a working workaround as a pattern to copy — if existing test code overrides configuration in ways that contradict these rules, do not reproduce it; flag it instead
- Use `getDeclaredMethod` / `setAccessible(true)` to access non-public methods
- Write tests whose only purpose is to reach a coverage threshold — every test must document a real behavior or guard against a real regression
- Name tests `shouldCoverXxxBranches` or similar — names must describe behavior, not implementation structure
- Group unrelated scenarios in a single test method — split them into separate `@Test` methods
- Load a `src/main/resources/` artifact as a **test fixture** — fixtures go under `src/test/resources/` (copy it there). Reaching into `src/main` couples prod to test data and escapes the fixture sync, so a prod edit silently breaks the test. Only exception: asserting the *shipped artifact itself* is valid — and the name + `@DisplayName` must say so. (Beta1 bump: `TunnelSchemaValidationTest.schemaShouldAcceptBundledReverseTunnelExample` broke on a stale `reverse-tunnel-ziti.yml`.)

## Capability Design Rules

When designing or modifying a Capability:

**Do:**
- Keep the [Ikanos Specification](modules/ikanos-spec/src/main/resources/schemas/ikanos-schema.json) and the [Ikanos Rules](modules/ikanos-spec/src/main/resources/rules/ikanos-rules.yml) as first-class citizens — the schema enforces structure, the rules enforce cross-object consistency, quality, and security
- Look at `modules/ikanos-spec/src/main/resources/schemas/examples/` for patterns before writing new capabilities
- When renaming a consumed field for a lookup `match`, also add a `ConsumedOutputParameter` on the consumed operation to map the raw field name to a kebab-case name — otherwise the lookup has nothing to match against
- Use `aggregates` to define reusable domain flows when the same operation is exposed through multiple adapters (REST and MCP) — this follows the DDD Aggregate pattern: one definition, multiple projections
- Declare `semantics` (safe, idempotent, cacheable) on aggregate flows to describe domain behavior — the engine derives MCP `hints` automatically
- Override only adapter-specific fields when using `ref` (e.g., `method` for REST, `hints` for MCP) — let the rest be inherited from the flow

**Don't:**
- Expose an `inputParameter` that is not used in any step
- Declare consumed `outputParameters` that are not used in the exposed part
- Prefix variables with the capability/namespace/resource name — they are already scoped, unless disambiguation is strictly needed
- Set a type property for `inputParameter` in a rest consumes bloc
- Use an `integer` type instead of a `number` type for `outputParameters` in a mcp exposes bloc
- Bind two `exposes` adapters (e.g. `skill` and `rest`) to the same port
- Use `items:` or nested `type:` on `McpToolInputParameter` for array-typed parameters — only `name`, `type`, `description`, and `required` are allowed
- Use YAML list syntax (`- type: object`) for `items` in `MappedOutputParameterArray` — `items` is a single `MappedOutputParameter` object, not an array
- Use snake_case identifiers where the schema expects `IdentifierKebab` (e.g. `match`, `name`, `namespace`) — use kebab-case
- Use `operation` instead of `call` in steps — `operation` is not a valid property in `OperationStepCall`, only `call` is
- Use `MappedOutputParameter` (with `mapping`, no `name`) when the tool/operation uses `steps` — use `OrchestratedOutputParameter` (with `name`, no `mapping`) instead
- Use typed objects for lookup step `outputParameters` — they are plain string arrays of field names to extract (e.g. `- "fullName"`)
- Put a `path` property on an `ExposedOperation` — extract multi-step operations with a different path into their own `ExposedResource`
- Duplicate a full flow definition inline on both MCP tools and REST operations — use `aggregates` + `ref` instead
- Chain `ref` through multiple levels of aggregates — `ref` resolves to a flow in a single aggregate, not transitively

## Contribution Workflow

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full workflow. Key rules:

- **Track every change with an Issue; reuse the one that already covers it.** This applies to every change (feat, fix, chore, skill update, doc edit). If the work is already covered by an existing issue — the user names it (e.g. "issue #529"), or the current branch/PR references one — that issue satisfies this rule; **do not propose creating another**. Only when **no** tracking issue exists should you propose opening one (using the matching template) and wait for the user's confirmation before writing any code or modifying any file. The user may explicitly waive the issue step; only then proceed without one.
- **All GitHub interactions must be in English** — issues, PR titles/bodies, inline review comments, and commit messages. The codebase and its community are English-first.
- Branch from `main`: `feat/`, `fix/`, or `chore/` prefix
- Use [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `chore:` — no scopes for now
- AGENTS.md improvements are `feat:`, not `chore:` — they add value to the agent workflow
- Rebase on `main` before PR — linear history, no merge commits
- One logical change per PR — keep it atomic
- CI must be green (build, tests, schema validation, Trivy, Gitleaks)
- Always read the repository templates before creating issues or PRs:
  - Issues: `.github/ISSUE_TEMPLATE/` — use the matching template and fill in all required fields
  - PRs: `.github/PULL_REQUEST_TEMPLATE.md` — follow the structure exactly, do not improvise
- When creating issues or PRs with multiline bodies via `gh`, **never construct the body as a string in the terminal** — PowerShell here-strings and multiline variable assignments hang or corrupt content. Always write the body to a temp `.md` file using the file creation tool (outside the terminal), then pass it via `--body-file "/path/to/file.md"` (see also *Working on Windows → Terminal & file I/O*)
- When asked to review a PR, load and follow the `pr-review` skill via the `agents-shared`
  capability — see `.github/instructions/agents-shared.instructions.md` for the discovery
  and sync procedure. The skill is served from `golden-repo-naftiko/agents-shared` (naftiko/shipyard#23).
- When asked to fix a bug, investigate an issue, or address PR review feedback, load and follow the `bugfix` skill via the `agents-shared` capability — see `.github/instructions/agents-shared.instructions.md` for the discovery and sync procedure — before doing anything else. It targets **Java/Maven** repositories (its build/test core assumes `mvn`); fixing a bash script or CI workflow that lives inside such a repo is in scope, but non-Java/Maven application code (C++, TypeScript, …) is not. It does **not** hardcode the repository name — it derives `<owner/repo>` from the working directory, so it is reusable across Java/Maven repos
- When resuming after a context compaction (conversation summary), always re-read any active skill's `SKILL.md` before continuing — compaction erases step formalism, workflow constraints, and all details defined in the skill
- When editing documentation, skill, or instruction files (`.md`, `SKILL.md`, `AGENTS.md`), re-read the **entire file** after applying edits and before committing — to catch terminology drift, broken cross-references, and inconsistencies between sections that targeted edits cannot detect
- Do **not** use `git push --force` — use `--force-with-lease`
- When the user corrects a mistake, note it immediately so the insight is not lost — see [Self-Improvement](#self-improvement)
- When the workflow is complete, review any noted corrections and propose rule updates if warranted

## Bug Workflow (mandatory)

When you identify a bug — whether discovered during development, debugging, or user-reported — follow these steps **in order** before writing any fix:

### 1. Open an Issue

If the bug is already covered by an existing issue (the user names it, or the branch/PR references one), reuse it and skip to step 2 — do not open another.

Otherwise, create a GitHub issue using the **Bug Report** template (`.github/ISSUE_TEMPLATE/bug_report.yml`).
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
git fetch origin main
git checkout -b fix/<short-description> origin/main
```

Never run `git checkout main` — if you are working in a worktree, `main` is checked out in the primary clone and the command will fail. Always branch directly from `origin/main` after a fetch (see *Parallel Agent Workflows* above).
Never start a fix branch from a feature branch or a stale local `main`.
When the fix is merged, remind the user to switch back to their original branch and restore the stash if needed.

### 3. Write non-regression tests before committing the fix

For every bug fix, two tests are required:

**Unit test** — targets the smallest unit of code that contains the bug (method or class level). Place it in the test class corresponding to the fixed class (e.g. `ConverterTest`, `ResolverTest`). If the class has no test file yet, create one. If a test already covers the scenario but is wrong, fix the test first and explain why in a comment.

**Integration test** — validates the fix end-to-end, typically loading a YAML capability fixture and exercising the full chain (deserialization → engine → output). Place the fixture under the appropriate module (`modules/ikanos-engine/src/test/resources/` or `modules/ikanos-cli/src/test/resources/`) and the test class in the package closest to the integration point (e.g. `io.ikanos.engine.exposes.mcp`).

Run the full test suite before committing:

```bash
mvn test
```

**Ordering:**
1. Write the tests first — only modify files under `src/test/` (and `src/test/resources/`) of the relevant module
2. Run `mvn test` and confirm the new tests **fail** (proving the bug exists)
3. Only then implement the fix in `src/main/` of the relevant module
4. Run `mvn test` again and confirm all tests **pass**

Do not edit production code (`src/main/`) and test code (`src/test/`) in the same phase.

All existing tests must stay green. If a pre-existing test fails, investigate before touching it.

When the user corrects a workflow step, note it immediately so the insight is not lost — see [Self-Improvement](#self-improvement).

### 4. Review noted corrections

Once the fix is merged (or the PR is open and CI is green), review any corrections the user made during the workflow and evaluate them against the [Self-Improvement](#self-improvement) criteria. Propose rule updates only at this point.

## Self-Improvement

When a user corrects agent-generated code or workflow, **note it immediately** so the insight is not lost, then **resume the current workflow** without interruption. Do not propose a rule change mid-workflow — wait until the workflow is complete (see Bug Workflow Step 4, Contribution Workflow end-of-list).

Suggest an AGENTS.md update **only** when all three conditions are met:

1. The corrected code or action was **generated by the agent** (not pre-existing code being refactored)
2. The correction is **structural** — it targets a convention, pattern, or style choice (e.g. visibility, naming, test design, workflow step) — not a one-off logic bug or domain-specific mistake
3. The correction is **generalizable** — the same mistake could plausibly recur in a different file or context

When all three conditions are met, propose the specific Do/Don't entry and the section it belongs to. Do not apply it — let the user decide.

When the conditions are **not** met, do not propose anything — avoid noise. Most corrections are one-off and do not need a rule.

**Tightening over appending; never delete autonomously.** When a rule causes friction, prefer *tightening* the existing rule over appending a new exception below it — a well-bounded rule needs no patches. You may **flag** a rule that looks duplicated or obsolete (state which one and why), but **never remove, merge, or rewrite a rule on your own**: a rule often encodes a regression it silently prevents, and the cost of losing it far outweighs the cost of keeping a slightly redundant line. Deletion and consolidation are always the user's call.

For reference, the Test Writing Rules, Method Visibility, Visibility Reduction, Field Type Migration, Cross-cutting Fixes, and Working on Windows sections in this file were all added through this process.
