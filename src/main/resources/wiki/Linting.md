# Linting Capabilities

## Table of Contents

- [Overview](#overview)
- [Quick Start — Spectral CLI](#quick-start--spectral-cli)
- [MegaLinter Setup](#megalinter-setup)
  - [Prerequisites](#prerequisites)
  - [Configuration Files](#configuration-files)
  - [Run Locally](#run-locally)
  - [GitHub Actions Workflow](#github-actions-workflow)
- [What Gets Validated](#what-gets-validated)
- [Extending the Naftiko Ruleset](#extending-the-naftiko-ruleset)
  - [Adding Custom Rules](#adding-custom-rules)
  - [Overriding Built-in Rules](#overriding-built-in-rules)
  - [Adding Custom Functions](#adding-custom-functions)
  - [Full Example](#full-example)
- [Troubleshooting](#troubleshooting)

---

## Overview

Naftiko capabilities are declared in YAML. Two complementary validation layers ensure your capability documents are correct and follow best practices:

| Layer | Tool | What it checks |
|---|---|---|
| **JSON Schema** | [v8r](https://github.com/chris48s/v8r) | Structural validity — required fields, types, allowed values, enum constraints |
| **Spectral Rules** | [Spectral CLI](https://github.com/stoplightio/spectral) | Cross-object consistency, style hygiene, security (XSS/injection) |

Both tools can be run standalone or orchestrated together via [MegaLinter](https://github.com/oxsecurity/megalinter), which combines them in a single Docker-based CI check.

> **JSON Schema** catches "is this valid YAML?", **Spectral** catches "is this *good* YAML?"

For a full reference of all Spectral rules, see the [Ruleset](https://github.com/naftiko/framework/wiki/Ruleset) page.

---

## Quick Start — Spectral CLI

If you just want to lint a capability file right now:

```bash
# Install Spectral CLI (one-time)
npm install -g @stoplight/spectral-cli

# Lint a single file against the Naftiko ruleset
npx @stoplight/spectral-cli lint my-capability.yml \
  --ruleset https://raw.githubusercontent.com/naftiko/framework/main/src/main/resources/rules/naftiko-rules.yml
```

If you have cloned the Naftiko Framework repository locally:

```bash
npx @stoplight/spectral-cli lint my-capability.yml \
  --ruleset path/to/framework/src/main/resources/rules/naftiko-rules.yml
```

---

## MegaLinter Setup

[MegaLinter](https://megalinter.io) orchestrates both Spectral and v8r (JSON Schema validation) in a single run. It works as a Docker container and integrates with GitHub Actions, GitLab CI, Azure Pipelines, and more.

### Prerequisites

- [Node.js](https://nodejs.org/) 18+ (for local use via `npx`)
- [Docker](https://docs.docker.com/get-docker/) (for MegaLinter container)

### Configuration Files

Create two files at the root of your project:

#### `.spectral.yaml`

This file tells Spectral where to find the Naftiko ruleset. If the Naftiko Framework repository is a sibling directory or submodule:

```yaml
extends:
  - ./path/to/framework/src/main/resources/rules/naftiko-rules.yml
```

Or reference the ruleset directly from GitHub:

```yaml
extends:
  - https://raw.githubusercontent.com/naftiko/framework/main/src/main/resources/rules/naftiko-rules.yml
```

#### `.mega-linter.yml`

This file configures MegaLinter to only run the two linters relevant to Naftiko capabilities:

```yaml
# .mega-linter.yml

# Only enable Spectral (semantic rules) and v8r (JSON Schema validation)
ENABLE_LINTERS:
  - API_SPECTRAL
  - YAML_V8R

# ── Spectral ──
API_SPECTRAL_CONFIG_FILE: .spectral.yaml
API_SPECTRAL_FILTER_REGEX_INCLUDE: "capabilities/.*\\.(yaml|yml)$"

# ── v8r (JSON Schema validation) ──
YAML_V8R_FILTER_REGEX_INCLUDE: "capabilities/.*\\.(yaml|yml)$"
YAML_V8R_ARGUMENTS: "-s path/to/naftiko-schema.json"

# General settings
APPLY_FIXES: none
VALIDATE_ALL_CODEBASE: true
```

> **Adapt the filter regex** to match the directory where you store your capability YAML files. The example above assumes a `capabilities/` directory.

> **v8r requires an explicit schema path** via the `-s` flag. v8r does not auto-detect schemas from `# yaml-language-server: $schema=...` comments. You must point it to a local copy of `naftiko-schema.json` or use a URL.

### Run Locally

#### Using MegaLinter Runner (recommended)

```bash
# Install and run in one command
npx mega-linter-runner
```

This reads your `.mega-linter.yml` and runs the configured linters inside Docker.

#### Using Docker directly

```bash
docker run --rm -v "$(pwd):/tmp/lint" oxsecurity/megalinter:v9
```

#### Using the individual tools (no Docker)

```bash
# Spectral only
npx @stoplight/spectral-cli lint "capabilities/*.yml" --ruleset .spectral.yaml

# v8r only (JSON Schema)
npx v8r "capabilities/*.yml" -s path/to/naftiko-schema.json
```

### GitHub Actions Workflow

Add this workflow to `.github/workflows/lint-capabilities.yml`:

```yaml
name: Lint Capabilities

on:
  push:
    paths:
      - 'capabilities/**'
      - '.spectral.yaml'
      - '.mega-linter.yml'
  pull_request:
    paths:
      - 'capabilities/**'
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
```

MegaLinter reads the `.mega-linter.yml` file automatically. No inline `env:` configuration is needed in the workflow.

---

## What Gets Validated

### JSON Schema (v8r)

Catches structural issues such as:

- Missing required fields (`naftiko`, `info`, `capability`)
- Invalid types (e.g., `port` must be an integer)
- Invalid enum values (e.g., `method` must be GET, POST, PUT, PATCH, or DELETE)
- Invalid patterns (e.g., `namespace` must match `^[a-zA-Z0-9-]+$`)
- Invalid `$schema` version

### Spectral Rules

Catches semantic and style issues such as:

| Category | Example |
|---|---|
| **Consistency** | Duplicate namespaces across adapters and binds |
| **URL hygiene** | Trailing slashes on `baseUri`, query strings in `path` fields |
| **Quality** | Missing `description` on consumes entries, REST resources, or operations |
| **Security** | `<script>` tags or `eval(` in description fields |

For the full list, see the [Ruleset](https://github.com/naftiko/framework/wiki/Ruleset) page.

---

## Extending the Naftiko Ruleset

The Naftiko ruleset covers the specification's common pitfalls. You can extend it with your own rules to enforce organization-specific conventions — without forking the original ruleset.

### Adding Custom Rules

Create your own `.spectral.yaml` that extends the Naftiko ruleset and adds new rules:

```yaml
extends:
  - https://raw.githubusercontent.com/naftiko/framework/main/src/main/resources/rules/naftiko-rules.yml

rules:
  # Enforce that all capabilities have at least two tags
  my-org-min-tags:
    message: "Capabilities must have at least 2 tags for discoverability."
    severity: warn
    given: "$.info.tags"
    then:
      function: length
      functionOptions:
        min: 2

  # Enforce a naming convention on exposed resource paths
  my-org-path-prefix:
    message: "All REST resource paths must start with /api/"
    severity: warn
    given: "$.capability.exposes[?(@.type == 'rest')].resources[*].path"
    then:
      function: pattern
      functionOptions:
        match: "^/api/"

  # Require a stakeholder with role "owner"
  my-org-owner-required:
    message: "At least one stakeholder with role 'owner' is recommended."
    severity: info
    given: "$.info.stakeholders[*].role"
    then:
      function: enumeration
      functionOptions:
        values:
          - owner
          - editor
          - viewer
```

### Overriding Built-in Rules

You can change the severity of any Naftiko rule, or disable it entirely:

```yaml
extends:
  - https://raw.githubusercontent.com/naftiko/framework/main/src/main/resources/rules/naftiko-rules.yml

rules:
  # Promote trailing slash from warning to error
  naftiko-consumes-baseuri-no-trailing-slash: error

  # Disable the info-tags rule (your org doesn't use tags)
  naftiko-info-tags: off

  # Downgrade missing REST operation description from info to hint
  naftiko-rest-operation-description: hint
```

### Adding Custom Functions

For advanced validation logic (like the built-in `unique-namespaces` function), you can write custom JavaScript functions:

1. Create a `functions/` directory next to your `.spectral.yaml`:

```
my-project/
├── .spectral.yaml
├── functions/
│   └── check-binds-location-scheme.js
└── capabilities/
    └── my-capability.yml
```

2. Write the function in `functions/check-binds-location-scheme.js`:

```javascript
module.exports = function checkBindsLocationScheme(targetVal) {
  const results = [];
  const allowedSchemes = ["file://", "vault://", "github-secrets://", "k8s-secret://"];

  if (typeof targetVal !== "string") return results;

  const hasAllowedScheme = allowedSchemes.some(scheme => targetVal.startsWith(scheme));
  if (!hasAllowedScheme) {
    results.push({
      message: `Bind location "${targetVal}" must use one of: ${allowedSchemes.join(", ")}`,
    });
  }

  return results;
};
```

3. Reference the function in your `.spectral.yaml`:

```yaml
extends:
  - https://raw.githubusercontent.com/naftiko/framework/main/src/main/resources/rules/naftiko-rules.yml

functionsDir: ./functions

functions:
  - check-binds-location-scheme

rules:
  my-org-binds-location-scheme:
    message: "Bind locations must use an approved URI scheme."
    severity: error
    given:
      - "$.binds[*].location"
      - "$.capability.binds[*].location"
    then:
      function: check-binds-location-scheme
```

### Full Example

Here is a complete `.spectral.yaml` that extends the Naftiko ruleset with organization-specific rules:

```yaml
# .spectral.yaml — ACME Corp capability linting
extends:
  - https://raw.githubusercontent.com/naftiko/framework/main/src/main/resources/rules/naftiko-rules.yml

functionsDir: ./functions

functions:
  - check-binds-location-scheme

rules:
  # ── Override built-in severities ──
  naftiko-consumes-baseuri-no-trailing-slash: error
  naftiko-info-tags: off

  # ── Organization rules ──
  acme-min-tags:
    message: "Capabilities must have at least 2 tags."
    severity: warn
    given: "$.info.tags"
    then:
      function: length
      functionOptions:
        min: 2

  acme-binds-location-scheme:
    message: "Bind locations must use an approved URI scheme."
    severity: error
    given:
      - "$.binds[*].location"
      - "$.capability.binds[*].location"
    then:
      function: check-binds-location-scheme
```

Run it:

```bash
npx @stoplight/spectral-cli lint "capabilities/*.yml" --ruleset .spectral.yaml
```

---

## Troubleshooting

### Spectral: "No results with a severity of 'error' found!"

This is a success message — your file has no errors. Warnings and info messages are still printed above this line.

### v8r: "Could not find a schema to validate"

v8r does not auto-detect schemas from `# yaml-language-server: $schema=...` comments. You must pass the schema explicitly:

```bash
npx v8r my-capability.yml -s path/to/naftiko-schema.json
```

Or in `.mega-linter.yml`:

```yaml
YAML_V8R_ARGUMENTS: "-s path/to/naftiko-schema.json"
```

### MegaLinter: Spectral finds no files

MegaLinter's Spectral integration auto-detects files by looking for OpenAPI/AsyncAPI content keywords (`openapi:`, `swagger:`, `asyncapi:`). Naftiko files use `naftiko:` instead, so they are **not auto-detected**.

Fix: Set `API_SPECTRAL_FILTER_REGEX_INCLUDE` in `.mega-linter.yml` to explicitly target your capability YAML directory.

### Spectral: "Unable to run custom function"

Custom functions referenced via `functionsDir` are resolved relative to the ruleset file that declares them.

- If you `extends` the Naftiko ruleset from a URL, Naftiko's built-in custom functions (like `unique-namespaces`) load from the GitHub-hosted `functions/` directory.
- Your own custom functions must be in a `functions/` directory relative to *your* `.spectral.yaml`.

### MegaLinter: Docker image is too large

Use the `java` flavor for a smaller image (~800 MB instead of ~2 GB):

```bash
docker run --rm -v "$(pwd):/tmp/lint" oxsecurity/megalinter-java:v9
```

In GitHub Actions:

```yaml
- uses: oxsecurity/megalinter/flavors/java@v9
```
