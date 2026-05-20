# Ikanos Specification - Rules

Version: {{RELEASE_TAG}}
Category: Tooling  
Last updated: {{CURRENT_DATE}}

---

## Table of Contents

- [Overview](#overview)
- [Design Principles](#design-principles)
- [Sources of Inspiration](#sources-of-inspiration)
- [Using the Ruleset](#using-the-ruleset)
- [Severity Levels](#severity-levels)
- [Rule Reference](#rule-reference)
  - [1. Structure & Consistency](#1-structure--consistency)
  - [2. Quality & Discoverability](#2-quality--discoverability)
  - [3. Security](#3-security)
  - [4. Control Port](#4-control-port)
  - [5. Scripting](#5-scripting)
  - [6. Imports](#6-imports)
- [Rule Lineage Table](#rule-lineage-table)

---

## Overview

The Ikanos rules (`ikanos-rules.yml`) is a Spectral ruleset used to lint Ikanos YAML documents.

It is intentionally **complementary** to the JSON Schema, not a duplicate of it:

- JSON Schema enforces structural validity and required fields.
- Spectral enforces cross-object consistency, style hygiene, and security hygiene.

The current ruleset supports all document shapes allowed by Ikanos {{RELEASE_TAG}}:

1. Full capability documents (`ikanos` + `capability`, optionally root `consumes`, `binds`)
2. Shared section documents (`ikanos` + root `consumes`, `exposes`, `aggregates`, or `binds`, without `capability`)
3. Import directives within capability documents (`from`/`import`/`as`)

---

## Design Principles

The ruleset follows these principles:

1. Avoid duplicating schema constraints already enforced by `ikanos-schema.json`
2. Keep value-added lint only (consistency, discoverability, security)
3. Support root-level `consumes` and capability-local `consumes`
4. Keep severities pragmatic (`error` for safety/consistency breakage, `warn`/`info` for quality)

---

## Sources of Inspiration

The ruleset is adapted from Spectral built-ins:

- OpenAPI rules: https://github.com/stoplightio/spectral/blob/develop/docs/reference/openapi-rules.md
- Arazzo rules: https://github.com/stoplightio/spectral/blob/develop/docs/reference/arazzo-rules.md

Examples of borrowed intent:

- Path/query hygiene
- Namespace uniqueness
- Description quality for discovery
- Markdown/script injection protections

---

## Using the Ruleset

Install Spectral CLI:

```bash
npm install -g @stoplight/spectral-cli
```

Lint a file:

```bash
npx @stoplight/spectral-cli lint my-capability.yml --ruleset src/main/resources/rules/ikanos-rules.yml
```

Lint all YAML files:

```bash
npx @stoplight/spectral-cli lint "**/*.yml" --ruleset src/main/resources/rules/ikanos-rules.yml
```

---

## Severity Levels

| Level | Meaning |
|---|---|
| `error` | Strong consistency or security issue that should be fixed |
| `warn` | Recommended practice not followed |
| `info` | Optional quality guidance |

---

## Rule Reference

### 1. Structure & Consistency

These rules validate cross-object consistency and URL/path hygiene not fully covered by schema-only validation.

#### `ikanos-namespaces-unique`

- Severity: `error`
- Scope: root `consumes`, `capability.consumes`, `capability.exposes`, root `binds`, and `capability.binds`
- Purpose: enforce global namespace uniqueness across all adapters and binds.

Example: using `sales` in both a consumed adapter and an exposed adapter — or in both an adapter and a bind — is invalid.

#### `ikanos-consumes-baseuri-no-trailing-slash`

- Severity: `warn`
- Scope: root `consumes` and `capability.consumes`
- Purpose: avoid `//` path joins during URI composition.

#### `ikanos-consumed-resource-no-query-in-path`

- Severity: `warn`
- Scope: root `consumes` and `capability.consumes`
- Purpose: keep query params in input parameters, not inside path strings.

Note: this check is applied to HTTP consumes entries.

#### `ikanos-rest-resource-path-no-trailing-slash`

- Severity: `warn`
- Scope: exposed REST resources
- Purpose: avoid path style ambiguity and accidental double-slash URLs.

#### `Ikanos-rest-resource-path-no-query`

- Severity: `warn`
- Scope: exposed REST resources
- Purpose: enforce separation of path vs query parameters.

#### `Ikanos-address-not-example`

- Severity: `warn`
- Scope: exposed adapter addresses
- Purpose: discourage placeholder hosts in production-bound documents.

---

### 2. Quality & Discoverability

These rules improve agent and human discoverability.

#### `Ikanos-info-tags`

- Severity: `info`
- Purpose: encourage capability categorization for discovery/filtering.

#### `ikanos-consumes-description`

- Severity: `warn`
- Scope: root `consumes` and `capability.consumes`
- Purpose: improve clarity about external dependencies.

#### `Ikanos-rest-resource-description`

- Severity: `warn`
- Purpose: improve semantic discoverability of REST resources.

#### `Ikanos-rest-operation-description`

- Severity: `info`
- Purpose: improve operation-level intent clarity.

#### `Ikanos-steps-name-pattern`

- Severity: `warn`
- Purpose: encourage stable step naming for template references.

---

### 3. Security

These rules reduce injection risk in rendered documentation/UIs.

#### `ikanos-no-script-tags-in-markdown`

- Severity: `error`
- Scope: description-like text fields
- Purpose: block `<script>` injection patterns.

#### `Ikanos-no-eval-in-markdown`

- Severity: `error`
- Scope: description-like text fields
- Purpose: block `eval(` JavaScript injection patterns.

#### `ikanos-baseuri-not-example`

- Severity: `warn`
- Scope: root `consumes` and `capability.consumes`
- Purpose: discourage `example.com` placeholders.

---

### 4. Control Port

These rules validate the control adapter configuration for safety and consistency.

#### `ikanos-control-port-singleton-and-unique`

- Severity: `error`
- Scope: `capability.exposes`
- Purpose: at most one `type: control` adapter is allowed per capability, and its port MUST NOT collide with any business adapter port (`rest`, `mcp`, `skill`).

#### `ikanos-control-address-localhost-warning`

- Severity: `warn`
- Scope: `capability.exposes[?(@.type == 'control')].address`
- Purpose: the control adapter `address` should be `localhost` or `127.0.0.1` for security. Binding to a non-localhost address exposes management endpoints to the network.

---

### 5. Scripting

These rules validate inline script step configuration for completeness.

#### `ikanos-script-defaults-required`

- Severity: `error`
- Scope: `capability.exposes` (cross-object: script steps + control adapter)
- Purpose: script steps that omit `language` or `location` MUST have corresponding defaults (`management.scripting.defaultLanguage`, `management.scripting.defaultLocation`) configured on the Control Port. Without defaults, the engine cannot determine the script language or file location at runtime. This rule uses a custom function (`script-defaults-required.js`) that inspects all script steps across all adapters and validates against the control adapter's scripting configuration.

---

### 6. Imports

These rules validate the unified import directive (`from`/`import`/`as`) introduced in the [unified import mechanism blueprint](https://github.com/naftiko/blueprints/blob/main/unified-import-mechanism.md). Cross-file reference integrity (§11.2) is enforced by the engine resolver at runtime, not by Spectral.

#### `ikanos-import-from-required`

- Severity: `error`
- Scope: capability `consumes`, `exposes`, `aggregates`, `binds` entries with `from`
- Purpose: every import entry must have both `from` (source file) and `import` (namespace) fields. An entry with only one is invalid.

#### `ikanos-import-import-required`

- Severity: `error`
- Scope: capability `consumes`, `exposes`, `aggregates`, `binds` entries with `import` but no `type`
- Purpose: complement of `ikanos-import-from-required` — catches entries with `import` but missing `from`.

#### `ikanos-import-unique-alias`

- Severity: `error`
- Scope: all import entries across the four sections of a capability
- Purpose: the effective namespace of each imported entry (`as` alias, or `import` when no `as`) must be unique within its section. Duplicates would cause ambiguous resolution.

#### `ikanos-import-from-not-self`

- Severity: `warn`
- Scope: `from` fields of import entries
- Purpose: a bare `.` as the `from` path is always an authoring error.

#### `ikanos-standalone-no-imports`

- Severity: `error`
- Scope: standalone section documents (no `capability` key)
- Purpose: standalone files are leaves in the import DAG — they must not contain import entries (entries with `from`). Only capability documents may import. Also enforced by JSON Schema `oneOf`.

#### `ikanos-exposes-namespace-required`

- Severity: `error`
- Scope: root-level `exposes` entries in source files
- Purpose: without a `namespace`, the import directive cannot reference the adapter.

#### `ikanos-aggregates-namespace-required`

- Severity: `error`
- Scope: root-level `aggregates` entries in source files
- Purpose: without a `namespace`, the import directive cannot reference the aggregate.

#### `ikanos-aggregates-unique-function-name`

- Severity: `error`
- Scope: `functions` arrays in aggregates (both standalone and capability)
- Purpose: duplicate function names within a single aggregate would cause ambiguous `call` and `ref` resolution.

---

## Rule Lineage Table

| Ikanos rule | Severity | Inspired By |
|---|---|---|
| `ikanos-namespaces-unique` | error | `arazzo-workflowId-unique`, `operation-operationId-unique` |
| `ikanos-consumes-baseuri-no-trailing-slash` | warn | `oas2-host-trailing-slash`, `oas3-server-trailing-slash` |
| `ikanos-consumed-resource-no-query-in-path` | warn | `path-not-include-query` |
| `ikanos-rest-resource-path-no-trailing-slash` | warn | `path-keys-no-trailing-slash` |
| `ikanos-rest-resource-path-no-query` | warn | `path-not-include-query` |
| `ikanos-address-not-example` | warn | `oas3-server-not-example.com` |
| `ikanos-info-tags` | info | `openapi-tags` |
| `ikanos-consumes-description` | warn | `info-description` |
| `ikanos-rest-resource-description` | warn | `tag-description` |
| `ikanos-rest-operation-description` | info | `operation-description` |
| `ikanos-steps-name-pattern` | warn | `arazzo-step-stepId` |
| `ikanos-no-script-tags-in-markdown` | error | `no-script-tags-in-markdown`, `arazzo-no-script-tags-in-markdown` |
| `ikanos-no-eval-in-markdown` | error | `no-eval-in-markdown` |
| `ikanos-baseuri-not-example` | warn | `oas2-host-not-example`, `oas3-server-not-example.com` |
| `ikanos-control-port-singleton-and-unique` | error | — (Ikanos-specific) |
| `ikanos-control-address-localhost-warning` | warn | — (Ikanos-specific) |
| `ikanos-script-defaults-required` | error | — (Ikanos-specific) |
| `ikanos-import-from-required` | error | ES module `import … from` pattern |
| `ikanos-import-import-required` | error | ES module `import … from` pattern |
| `ikanos-import-unique-alias` | error | — (Ikanos-specific) |
| `ikanos-import-from-not-self` | warn | — (Ikanos-specific) |
| `ikanos-standalone-no-imports` | error | — (Ikanos-specific, §11.3 leaf enforcement) |
| `ikanos-exposes-namespace-required` | error | — (Ikanos-specific) |
| `ikanos-aggregates-namespace-required` | error | — (Ikanos-specific) |
| `ikanos-aggregates-unique-function-name` | error | — (Ikanos-specific) |

---

## Notes

- This page documents the current lean ruleset and intentionally does not list schema-duplicated checks.
- For mandatory structure/required fields, rely on `ikanos-spec/src/main/resources/schemas/ikanos-schema.json`.
- For the import guide (how imports work across all four sections), see the [Importing Guide](https://github.com/naftiko/ikanos/wiki/Guide-%E2%80%90-Importing).