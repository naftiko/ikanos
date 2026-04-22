# Naftiko Specification - Rules

Version: {{RELEASE_TAG}}
Category: Tooling  
Last updated: March 2026

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
- [Rule Lineage Table](#rule-lineage-table)

---

## Overview

The Naftiko rules (`naftiko-rules.yml`) is a Spectral ruleset used to lint Naftiko YAML documents.

It is intentionally **complementary** to the JSON Schema, not a duplicate of it:

- JSON Schema enforces structural validity and required fields.
- Spectral enforces cross-object consistency, style hygiene, and security hygiene.

The current ruleset supports both document shapes allowed by Naftiko {{RELEASE_TAG}}:

1. Full capability documents (`naftiko` + `capability`, optionally root `consumes`)
2. Shared consumes documents (`naftiko` + root `consumes`, without `capability`)

---

## Design Principles

The ruleset follows these principles:

1. Avoid duplicating schema constraints already enforced by `naftiko-schema.json`
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
npx @stoplight/spectral-cli lint my-capability.yml --ruleset src/main/resources/rules/naftiko-rules.yml
```

Lint all YAML files:

```bash
npx @stoplight/spectral-cli lint "**/*.yml" --ruleset src/main/resources/rules/naftiko-rules.yml
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

#### `naftiko-namespaces-unique`

- Severity: `error`
- Scope: root `consumes`, `capability.consumes`, `capability.exposes`, root `binds`, and `capability.binds`
- Purpose: enforce global namespace uniqueness across all adapters and binds.

Example: using `sales` in both a consumed adapter and an exposed adapter — or in both an adapter and a bind — is invalid.

#### `naftiko-consumes-baseuri-no-trailing-slash`

- Severity: `warn`
- Scope: root `consumes` and `capability.consumes`
- Purpose: avoid `//` path joins during URI composition.

#### `naftiko-consumed-resource-no-query-in-path`

- Severity: `warn`
- Scope: root `consumes` and `capability.consumes`
- Purpose: keep query params in input parameters, not inside path strings.

Note: this check is applied to HTTP consumes entries.

#### `naftiko-rest-resource-path-no-trailing-slash`

- Severity: `warn`
- Scope: exposed REST resources
- Purpose: avoid path style ambiguity and accidental double-slash URLs.

#### `naftiko-rest-resource-path-no-query`

- Severity: `warn`
- Scope: exposed REST resources
- Purpose: enforce separation of path vs query parameters.

#### `naftiko-address-not-example`

- Severity: `warn`
- Scope: exposed adapter addresses
- Purpose: discourage placeholder hosts in production-bound documents.

---

### 2. Quality & Discoverability

These rules improve agent and human discoverability.

#### `naftiko-info-tags`

- Severity: `info`
- Purpose: encourage capability categorization for discovery/filtering.

#### `naftiko-consumes-description`

- Severity: `warn`
- Scope: root `consumes` and `capability.consumes`
- Purpose: improve clarity about external dependencies.

#### `naftiko-rest-resource-description`

- Severity: `warn`
- Purpose: improve semantic discoverability of REST resources.

#### `naftiko-rest-operation-description`

- Severity: `info`
- Purpose: improve operation-level intent clarity.

#### `naftiko-steps-name-pattern`

- Severity: `warn`
- Purpose: encourage stable step naming for template references.

---

### 3. Security

These rules reduce injection risk in rendered documentation/UIs.

#### `naftiko-no-script-tags-in-markdown`

- Severity: `error`
- Scope: description-like text fields
- Purpose: block `<script>` injection patterns.

#### `naftiko-no-eval-in-markdown`

- Severity: `error`
- Scope: description-like text fields
- Purpose: block `eval(` JavaScript injection patterns.

#### `naftiko-baseuri-not-example`

- Severity: `warn`
- Scope: root `consumes` and `capability.consumes`
- Purpose: discourage `example.com` placeholders.

---

### 4. Control Port

These rules validate the control adapter configuration for safety and consistency.

#### `naftiko-control-port-singleton-and-unique`

- Severity: `error`
- Scope: `capability.exposes`
- Purpose: at most one `type: control` adapter is allowed per capability, and its port MUST NOT collide with any business adapter port (`rest`, `mcp`, `skill`).

#### `naftiko-control-address-localhost-warning`

- Severity: `warn`
- Scope: `capability.exposes[?(@.type == 'control')].address`
- Purpose: the control adapter `address` should be `localhost` or `127.0.0.1` for security. Binding to a non-localhost address exposes management endpoints to the network.

---

### 5. Scripting

These rules validate inline script step configuration for completeness.

#### `naftiko-script-defaults-required`

- Severity: `error`
- Scope: `capability.exposes` (cross-object: script steps + control adapter)
- Purpose: script steps that omit `language` or `location` MUST have corresponding defaults (`management.scripting.defaultLanguage`, `management.scripting.defaultLocation`) configured on the Control Port. Without defaults, the engine cannot determine the script language or file location at runtime. This rule uses a custom function (`script-defaults-required.js`) that inspects all script steps across all adapters and validates against the control adapter's scripting configuration.

---

## Rule Lineage Table

| Naftiko Rule | Severity | Inspired By |
|---|---|---|
| `naftiko-namespaces-unique` | error | `arazzo-workflowId-unique`, `operation-operationId-unique` |
| `naftiko-consumes-baseuri-no-trailing-slash` | warn | `oas2-host-trailing-slash`, `oas3-server-trailing-slash` |
| `naftiko-consumed-resource-no-query-in-path` | warn | `path-not-include-query` |
| `naftiko-rest-resource-path-no-trailing-slash` | warn | `path-keys-no-trailing-slash` |
| `naftiko-rest-resource-path-no-query` | warn | `path-not-include-query` |
| `naftiko-address-not-example` | warn | `oas3-server-not-example.com` |
| `naftiko-info-tags` | info | `openapi-tags` |
| `naftiko-consumes-description` | warn | `info-description` |
| `naftiko-rest-resource-description` | warn | `tag-description` |
| `naftiko-rest-operation-description` | info | `operation-description` |
| `naftiko-steps-name-pattern` | warn | `arazzo-step-stepId` |
| `naftiko-no-script-tags-in-markdown` | error | `no-script-tags-in-markdown`, `arazzo-no-script-tags-in-markdown` |
| `naftiko-no-eval-in-markdown` | error | `no-eval-in-markdown` |
| `naftiko-baseuri-not-example` | warn | `oas2-host-not-example`, `oas3-server-not-example.com` |
| `naftiko-control-port-singleton-and-unique` | error | — (Naftiko-specific) |
| `naftiko-control-address-localhost-warning` | warn | — (Naftiko-specific) |
| `naftiko-script-defaults-required` | error | — (Naftiko-specific) |

---

## Notes

- This page documents the current lean ruleset and intentionally does not list schema-duplicated checks.
- For mandatory structure/required fields, rely on `src/main/resources/schemas/naftiko-schema.json`.