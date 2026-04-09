# Linter Integration for Naftiko Capabilities — Proposal

Version: 0.5
Created by: Jerome Louvel
Category: ADR
Last updated time: March 26, 2026
Status: To Review

# Integrate a Meta-Linter for Naftiko Capability Linting

> 🧭 This proposal targets the CI/CD pipeline and developer experience for validating Naftiko Capability YAML documents at both the **JSON Schema** and **Spectral Rules** levels using a meta-linter ([super-linter](https://github.com/super-linter/super-linter) or [MegaLinter](https://github.com/oxsecurity/megalinter)).

---

## Context and Problem Statement

### Background

Naftiko capabilities are declared entirely in YAML. Two complementary layers validate these documents today:

1. **JSON Schema** (`naftiko-schema.json`, Draft 2020-12) — structural validity enforced via `ajv` in the `validate-schemas.yml` workflow.
2. **Spectral Rules** (`naftiko-rules.yml`, 12 rules + 1 custom function) — cross-object consistency, style hygiene, and security checks. Currently tested only in a JUnit harness (`NaftikoSpectralRulesetTest.java`) that is **optional** (skipped when Node.js is absent).

### Problem

| Issue | Impact |
|---|---|
| Spectral rules have **no dedicated CI enforcement** | A PR can merge with namespace collisions, `<script>` tags in descriptions, or missing descriptions — undetected |
| The JUnit Spectral test is opt-in (`Assumptions.assumeTrue`) | Contributors without Node.js never exercise the ruleset |
| Each validation concern is its own bespoke workflow | `validate-schemas.yml` (ajv), `validate-json-structure.yml` (jstruct), quality-gate.yml (SonarQube/Trivy/Gitleaks) — no single linting umbrella |
| No local linting config at repo root | IDE/editor integrations (VS Code Spectral extension) don't auto-discover the ruleset without a root `.spectral.yml` |

### Goal

Adopt [super-linter](https://github.com/super-linter/super-linter) to provide a **single, standardized CI action** that enforces Spectral rules on every push and PR against capability YAML files. Evaluate whether JSON Schema validation can also be absorbed or should remain separate.

### Scope

- Add a super-linter workflow that runs Spectral against capability YAML files
- Create a root `.spectral.yml` that extends the existing ruleset
- Evaluate JSON Schema validation integration options
- Keep the existing `ajv`-based workflow for JSON Schema (recommended)

### Out of Scope

- Changing the Spectral ruleset content (rules, severities, custom functions)
- Replacing Trivy, Gitleaks, SonarQube, or any non-YAML linter
- Java code linting (Google Style is enforced separately)

---

## Analysis: Super-Linter Capabilities

Super-linter v7 bundles **70+ linters** in a single Docker image. The two relevant to Naftiko:

| Linter | Super-Linter Env Var | What It Does | Naftiko Use Case |
|---|---|---|---|
| **Spectral** | `VALIDATE_SPECTRAL` | Runs `@stoplight/spectral-cli lint` against target files using a `.spectral.yml` config | Enforces `naftiko-rules.yml` (cross-object consistency, security, quality) |
| **yamllint** | `VALIDATE_YAML` | Generic YAML syntax and style checks (indentation, line length, trailing spaces) | Optional — complementary to Spectral |

Super-linter does **not** include:
- `ajv` or any custom JSON Schema validator for YAML documents
- Draft 2020-12 schema validation

This constrains the integration design.

---

## Analysis: MegaLinter as Alternative

[MegaLinter](https://github.com/oxsecurity/megalinter) (v9.4.0, maintained by OX Security) is a Python-based hard fork of super-linter with significantly more features. It bundles **134 linters** in the default flavor and offers purpose-built Docker flavors (including a `java` flavor with 56 linters).

### MegaLinter supports both validation layers natively

| Linter | MegaLinter Var | Bundled Version | Naftiko Use Case |
|---|---|---|---|
| **Spectral** | `API_SPECTRAL` | 6.15.0 | Enforces `naftiko-rules.yml` — same as super-linter |
| **v8r** | `YAML_V8R` | 6.0.0 | **Validates YAML files against JSON Schema** — could replace the `ajv` workflow |
| **yamllint** | `YAML_YAMLLINT` | — | Generic YAML syntax/style checks |

### v8r: JSON Schema validation for YAML

[v8r](https://github.com/chris48s/v8r) validates JSON/YAML files against JSON Schema. It discovers the applicable schema via:

1. **`# yaml-language-server: $schema=...` comments** — Naftiko capability files **already include these** (e.g., `# yaml-language-server: $schema=../naftiko-schema.json`)
2. **Explicit `-s` flag** — point to a local schema path
3. **SchemaStore.org** — auto-detect by filename pattern (not applicable for Naftiko)

This means v8r could **replace the custom `ajv` workflow** for JSON Schema validation — a significant advantage over super-linter.

### Spectral file detection caveat

MegaLinter's Spectral integration (API_SPECTRAL) uses a "Smart Detection" engine. It looks for signatures like openapi: 3.0 inside files. Because Naftiko files use a custom naftiko: key, they are ignored by the default linter, even if included in the regex.

Solution: Use POST_COMMANDS to bypass the identification engine and force Spectral execution via the CLI already bundled in the MegaLinter image.

**Workaround:** 
```yaml
POST_COMMANDS:
  - name: Naftiko Spectral Validation
    command: "spectral lint -r .spectral.yaml 'src/main/resources/schemas/examples/*.yml' 'src/main/resources/tutorial/**/*.yml' 'src/test/resources/*.yaml'"
    continue_on_error: false
```

### v8r and Draft 2020-12

v8r v6.0.0 uses `ajv` internally. The Naftiko schema (`naftiko-schema.json`) declares `$schema: "https://json-schema.org/draft/2020-12/schema"` — v8r should handle this, but **needs verification** since the `# yaml-language-server: $schema=` comment uses a relative path that may resolve differently inside MegaLinter's Docker container. An explicit `.v8rrc.yml` config with an absolute schema path would mitigate this.

### MegaLinter vs Super-Linter comparison

| Dimension | Super-Linter | MegaLinter |
|---|---|---|
| **JSON Schema validation** | Not supported | Supported via v8r (YAML_V8R) |
| **Spectral** | Supported (VALIDATE_SPECTRAL) | Supported (API_SPECTRAL) |
| **Performance** | Sequential (Bash) | Parallel (Python multiprocessing) |
| **Docker image size** | ~2 GB (single image) | ~2 GB default, **~800 MB** with `java` flavor |
| **File detection (Spectral)** | Extension-based | Content-based regex (requires override for Naftiko) |
| **Per-linter filtering** | Global `FILTER_REGEX_INCLUDE` only | Per-linter `*_FILTER_REGEX_INCLUDE` |
| **PR comment reports** | GitHub Status only | GitHub PR comments, SARIF, Markdown summary, etc. |
| **Auto-fix & push** | Not supported | Supported (can push fixes or open PR) |
| **Security** | Passes all env vars to linters | Hides env vars from linters by default |
| **Config file** | Env vars in workflow | `.mega-linter.yml` at repo root |
| **License** | MIT | AGPL-3.0 |
| **Local runner** | Docker only | `mega-linter-runner` (npm) + Docker |

---

## Proposed Design

### Option 1 — MegaLinter (recommended)

MegaLinter covers **both** validation layers (Spectral + JSON Schema) in a single tool, eliminating the need to keep the `ajv` workflow separate.

#### 1.1 Root `.spectral.yml`

Create a thin wrapper at the repo root that delegates to the existing ruleset:

```yaml
extends:
  - ./src/main/resources/rules/naftiko-rules.yml
```

**Why a wrapper?** MegaLinter (and the VS Code Spectral extension) look for `.spectral.yaml` at the repo root by convention. The actual rules stay in `src/main/resources/rules/` as the source of truth. Spectral resolves `functionsDir` relative to the *extended* file, so the custom `unique-namespaces.js` function loads correctly without duplication.

#### 1.2 Config file: `.mega-linter.yml`

MegaLinter uses a repo-root config file instead of inline env vars:

```yaml
# .mega-linter.yml
APPLY_FIXES: none
ENABLE_LINTERS:
  - YAML_V8R

# ── v8r (JSON Schema validation) ──
YAML_V8R_FILTER_REGEX_INCLUDE: "src/(main/resources/(schemas/examples|tutorial)|test/resources)/.*\\.(yaml|yml)$"
YAML_V8R_ARGUMENTS: "--schema src/main/resources/schemas/naftiko-schema.json"

# ── Spectral (Naftiko Business Rules) ──
POST_COMMANDS:
  - name: Naftiko Spectral Validation
    command: "spectral lint -r .spectral.yaml 'src/main/resources/schemas/examples/*.yml' 'src/main/resources/tutorial/**/*.yml' 'src/test/resources/*.yaml'"
    continue_on_error: false
```

**Key design choices:**
- `ENABLE_LINTERS` — only Spectral and v8r run; all 132 other linters are implicitly disabled
- Per-linter `FILTER_REGEX_INCLUDE` — each linter scopes to capability YAML files only
- v8r auto-discovers the schema via the `# yaml-language-server: $schema=` comment already present in all capability files

#### 1.3 Workflow: `.github/workflows/mega-linter.yml`

```yaml
name: Lint Naftiko Capabilities

on:
  push:
    paths:
      - 'src/main/resources/schemas/**'
      - 'src/main/resources/rules/**'
      - 'src/test/resources/**'
      - '.spectral.yaml'
      - '.mega-linter.yml'
  pull_request:
    paths:
      - 'src/main/resources/schemas/**'
      - 'src/main/resources/rules/**'
      - 'src/test/resources/**'
      - '.spectral.yaml'
      - '.mega-linter.yml'

permissions:
  contents: read

jobs:
  lint:
    name: MegaLinter
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - uses: oxsecurity/megalinter@v9
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          VALIDATE_ALL_CODEBASE: true
```

#### 1.4 Local Validation

Via the MegaLinter runner (npm):

```bash
npx mega-linter-runner --flavor java
```

Or directly via Docker:

```bash
docker run --rm \
  -v "$(pwd):/tmp/lint" \
  oxsecurity/megalinter-java:v9
```

Or directly via Spectral CLI:

```bash
npx @stoplight/spectral-cli lint \
  src/main/resources/schemas/examples/*.yml \
  --ruleset src/main/resources/rules/naftiko-rules.yml
```

---

### Option 2 — Super-Linter (Spectral only)

#### 2.1 Root `.spectral.yml`

Same as Option 1 — create a thin wrapper at the repo root that delegates to the existing ruleset:

```yaml
extends:
  - ./src/main/resources/rules/naftiko-rules.yml
```

#### 2.2 Workflow: `.github/workflows/super-linter.yml`

```yaml
name: Lint Naftiko Capabilities

on:
  push:
    paths:
      - 'src/main/resources/schemas/**'
      - 'src/main/resources/rules/**'
      - 'src/test/resources/**'
      - '.spectral.yml'
  pull_request:
    paths:
      - 'src/main/resources/schemas/**'
      - 'src/main/resources/rules/**'
      - 'src/test/resources/**'
      - '.spectral.yml'

permissions:
  contents: read
  statuses: write

jobs:
  lint:
    name: Super-Linter
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - uses: super-linter/super-linter@v7
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

          # ── Enable only Spectral ──
          VALIDATE_SPECTRAL: true

          # ── Disable everything else ──
          VALIDATE_YAML: false
          VALIDATE_JSON: false
          VALIDATE_MARKDOWN: false
          VALIDATE_JAVA: false
          VALIDATE_JAVASCRIPT_ES: false
          VALIDATE_NATURAL_LANGUAGE: false

          # ── Scope to capability YAML files only ──
          FILTER_REGEX_INCLUDE: >-
            src/main/resources/schemas/(examples|tutorial)/.*\.(yaml|yml)$

          # ── Lint all matching files, not just changed ones ──
          VALIDATE_ALL_CODEBASE: true

          # ── Spectral config ──
          SPECTRAL_CONFIG_FILE: .spectral.yml
```

**Key design choices:**
- `VALIDATE_ALL_CODEBASE: true` — ensures all capability files are always validated, not just changed ones (small file count makes this cheap)
- `FILTER_REGEX_INCLUDE` — restricts Spectral to capability YAML files only; prevents false positives on CI configs, `pom.xml`, Docker files, etc.
- All non-Spectral linters explicitly disabled — avoids unexpected failures from unrelated linters

#### 2.3 Local Validation

Contributors can run super-linter locally via Docker:

```bash
docker run --rm \
  -e RUN_LOCAL=true \
  -e VALIDATE_SPECTRAL=true \
  -e FILTER_REGEX_INCLUDE="src/main/resources/schemas/(examples|tutorial)/.*\.(yaml|yml)$" \
  -v "$(pwd):/tmp/lint" \
  ghcr.io/super-linter/super-linter:v7
```

Or directly via Spectral CLI (already used in the JUnit test):

```bash
npx @stoplight/spectral-cli lint \
  src/main/resources/schemas/examples/*.yml \
  --ruleset src/main/resources/rules/naftiko-rules.yml
```

#### 2.4 JSON Schema Validation (must remain separate)

Super-linter has **no built-in JSON Schema validator for YAML documents**. The existing `validate-schemas.yml` workflow must remain as-is:

- Uses `ajv` with `--spec=draft2020` — full Draft 2020-12 support
- Validates tutorial, example, and test-fixture YAML files
- Is well-scoped and battle-tested

This is the key limitation of super-linter compared to MegaLinter: **two separate CI checks** are required instead of one.

---

## File Changes Summary

### Option 1 — MegaLinter

| File | Action | Purpose |
|---|---|---|
| `.spectral.yaml` | **Create** | Root config extending `src/main/resources/rules/naftiko-rules.yml` |
| `.mega-linter.yml` | **Create** | MegaLinter config (enable Spectral + v8r, scoping filters) |
| `.github/workflows/mega-linter.yml` | **Create** | MegaLinter workflow for Spectral + JSON Schema |
| `.github/workflows/validate-schemas.yml` | **Retire** | Replaced by v8r inside MegaLinter |
| `src/main/resources/rules/naftiko-rules.yml` | **No change** | Remains the Spectral ruleset source of truth |

### Option 2 — Super-Linter

| File | Action | Purpose |
|---|---|---|
| `.spectral.yml` | **Create** | Root config extending `src/main/resources/rules/naftiko-rules.yml` |
| `.github/workflows/super-linter.yml` | **Create** | Super-linter workflow for Spectral linting only |
| `.github/workflows/validate-schemas.yml` | **No change** | Still required — super-linter has no JSON Schema support |
| `src/main/resources/rules/naftiko-rules.yml` | **No change** | Remains the Spectral ruleset source of truth |

---

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Spectral version in meta-linter differs from local/test version | Medium | Rules may pass locally but fail in CI (or vice versa) | Pin version (`@v7` or `@v9`); document expected Spectral version; verify in the JUnit test |
| `functionsDir` resolution fails when `.spectral.yml` extends a nested ruleset | Low | `unique-namespaces` rule would not load → CI error on first run | Spectral resolves `functionsDir` relative to the extended file; verify in local Docker run before merging |
| MegaLinter Spectral auto-detection misses Naftiko files (no `openapi:` keyword) | High | Spectral would not lint any files without explicit filter config | Use `API_SPECTRAL_FILTER_REGEX_INCLUDE` to explicitly target capability YAML directories |
| v8r `# yaml-language-server: $schema=` relative path resolves incorrectly in Docker | Medium | Schema validation fails or silently skips files | Verify v8r resolution in Docker; fall back to `.v8rrc.yml` with explicit schema path if needed |
| v8r Draft 2020-12 support incomplete | Low | Schema validation may not catch all constraint violations | Verify v8r against known-invalid fixtures; keep `ajv` workflow as fallback until v8r is proven |
| `FILTER_REGEX_INCLUDE` too narrow — misses new YAML directories | Low | New capability directories are not linted | Update regex when adding new YAML directories; document the convention |
| CI time increases due to Docker image pull (~2 GB for super-linter, ~800 MB for MegaLinter java flavor) | Medium | Slower PR feedback loop | Acceptable trade-off; image is cached after first pull; MegaLinter `java` flavor is smaller |
| AGPL-3.0 license of MegaLinter | Low | May conflict with organizational licensing policies | MegaLinter runs as a CI tool only (not embedded in product code); AGPL concerns typically apply to distributed software, not CI tooling |

---

## Future Enhancements

| Enhancement | Description | Priority |
|---|---|---|
| **Enable `VALIDATE_YAML`** | Add a `.yamllint.yml` for generic YAML style checks (indentation, line length) alongside Spectral's semantic checks | Low |
| **Diff-based linting** | Set `VALIDATE_ALL_CODEBASE: false` to lint only changed files on PRs (faster CI) | Low |
| **Lint test fixtures** | Add `src/test/resources/.*\.(yaml\|yml)$` to `FILTER_REGEX_INCLUDE` if test fixtures should also pass Spectral rules | Medium |
| **Enable `VALIDATE_JSON`** | Lint `naftiko-schema.json` itself for JSON syntax and style | Low |
| **Retire optional JUnit test** | Once CI Spectral enforcement is in place, consider removing `NaftikoSpectralRulesetTest.java` or making it mandatory | Low |
| **GitHub Status Checks** | Configure branch protection to require the super-linter check to pass before merge | High |

---

## Decision

**Recommended approach:** Option 1 — **MegaLinter**.

MegaLinter covers both validation layers in a single tool:

| Level | Tool inside MegaLinter | Replaces |
|---|---|---|
| **Spectral Rules** | `API_SPECTRAL` (Spectral 6.15.0) | Optional JUnit test (`NaftikoSpectralRulesetTest.java`) |
| **JSON Schema** | `YAML_V8R` (v8r 6.0.0) | `validate-schemas.yml` (`ajv`) |

Advantages over super-linter:
- **Single CI check** for both Spectral and JSON Schema (no need to keep `ajv` workflow separate)
- **Parallel execution** (faster CI)
- **Per-linter filtering** (Spectral and v8r can target different file sets if needed)
- **Smaller Docker image** via `java` flavor (~800 MB vs ~2 GB)
- **PR comment reports** (richer CI feedback)

Trade-offs:
- AGPL-3.0 license (acceptable for CI tooling)
- Spectral file auto-detection requires explicit filter override (one-time config)
- v8r Draft 2020-12 support should be verified before retiring the `ajv` workflow

Total new files: **3** (`.spectral.yaml` + `.mega-linter.yml` + `.github/workflows/mega-linter.yml`)
Retired files: **1** (`validate-schemas.yml` — after v8r verification)
