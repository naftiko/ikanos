---
name: naftiko-capability
version: "1.0.0-alpha1"
description: >
  Skill for authoring, validating, and debugging Naftiko Capability YAML files
  (spec v1.0.0-alpha1). Activate when the user wants to: write a new capability
  document, add or change authentication on a consumed API, configure orchestration
  steps or parameter mappings, set up a forward proxy, expose an MCP server or Skill
  server, configure external references for secrets, or run the Spectral linter.
  The Naftiko Specification defines modular, composable capabilities that consume
  external APIs and expose REST, MCP, or Skill adapters.
allowed-tools:
  - Read
  - Write
  - Bash
  - Glob
---

## Overview

The Naftiko Framework lets you declare **capabilities** — functional units that
**consume** external APIs and **expose** adapters (REST, MCP, Skill). A capability
is a single YAML file validated against the Naftiko JSON Schema (v1.0.0-alpha1).

Key spec objects you will work with:

- **Info** — metadata: label, description, tags, stakeholders
- **Capability** — root technical config; contains `exposes` and `consumes`
- **Consumes** — HTTP client adapter: baseUri, namespace, resources, operations
- **Exposes** — server adapter: REST (`type: rest`), MCP (`type: mcp`), or Skill (`type: skill`)
- **Binds** — variable injection from file (dev) or runtime (prod)
- **Namespace** — unique identifier linking exposes to consumes via routing

Canonical sources (read these, never duplicate them):

- Specification: `src/main/resources/wiki/Specification.md`
- JSON Schema: `src/main/resources/schemas/capability-schema.json`
- Spectral Ruleset: `src/main/resources/schemas/naftiko-rules.yml`

## Decision Framework

Match the user's situation to a story reference. Each story explains *why*
(the user's problem), *what* (the Naftiko pattern), and points to the spec
for *how*.

| Situation | Action |
|---|---|
| "I want to combine several APIs into a single reusable service" | Read `references/reusable-capability.md` |
| "I want to expose this API as an MCP server, including tools, resources, and prompts" | Read `references/wrap-api-as-mcp.md` |
| "I want to proxy an API today and encapsulate it incrementally" | Read `references/proxy-then-customize.md` |
| "I want to chain multiple HTTP calls to consumed APIs and expose the result into a single REST operation" | Read `references/chain-api-calls.md` |
| "I need to go from local test credentials to production secrets" | Read `references/dev-to-production.md` |
| "I want to build a full-featured capability that does all of the above" | Read all stories in order, then use `assets/capability-example.yml` as structural reference |
| "I have a YAML validation error" | Run `scripts/lint-capability.sh` — see **Lint workflow** below |
| "I'm done writing — what should I check before shipping?" | Read `references/design-guidelines.md`, then run lint |

If the user's intent does not match any story, read the canonical
Specification directly.

## Workflows

### Edit a Capability

1. **Analyze and propose.** Infer the story from context (user prompt, open
   files, existing capabilities). Read that story file, then present a
   capability outline for the user to validate. Only ask what you cannot infer.
2. **Scaffold.** Copy `assets/capability-template.yml`. The document must
   begin with `naftiko: "1.0.0-alpha1"`.
3. **Fill exposes.** Choose the adapter type (REST, MCP, or Skill) and
   follow the pattern from the story. For REST operations, use `call` +
   `with` (simple) or `steps` + `mappings` (orchestrated) — never both.
4. **Fill consumes.** For each external API: set `type: "http"`, a unique
   `namespace` (kebab-case), `baseUri` (no trailing slash), `resources`,
   and `operations`. Add `authentication` if needed.
5. **Add `binds`** if secrets or environment variables are needed.
   Use `location: "file:"` for dev; remove it for prod.
6. **Review.** Read `references/design-guidelines.md` and check your
   document against the design guidelines.
7. **Validate.** Run `bash scripts/lint-capability.sh path/to/capability.yml`.
   Fix any errors, then re-lint until clean.

### Lint a Capability

1. Run the lint script:
       bash scripts/lint-capability.sh path/to/capability.yml
   Do NOT regenerate or modify this script.
2. Spectral reports errors and warnings with rule names. Common rules:
   - `naftiko-namespaces-unique` (error) — duplicate namespace
   - `naftiko-consumes-baseuri-no-trailing-slash` (warn) — trailing `/`
   - `naftiko-consumed-resource-no-query-in-path` (warn) — query in path
   - `naftiko-rest-resource-path-no-trailing-slash` (warn)
   - `naftiko-baseuri-not-example` (warn) — placeholder URI
   - `naftiko-no-script-tags-in-markdown` (error) — XSS in descriptions
   - `naftiko-consumes-description` (warn) — missing description
   For the full rule list, read the Spectral ruleset file directly.
3. Fix and re-lint. Repeat until clean.

## Hard Constraints

1. The root field `naftiko` must be `"1.0.0-alpha1"` — no other version string is
   valid for this spec revision.
2. Every `consumes` entry must have both `baseUri` and `namespace`.
3. Every `exposes` entry must have a `namespace`.
4. Namespaces must be unique across all `exposes`, `consumes` and `binds` objects.
5. At least one of `exposes` or `consumes` must be present in `capability`.
6. `call` references must follow `{namespace}.{operationName}` and point
   to a valid consumed operation.
7. `{exposeNamespace}.{paramName}` is the only syntax for referencing
   exposed input parameters in `with` injectors — do not invent alternatives.
8. `variable` expressions resolve from `binds` keys.
9. `ForwardConfig` requires `targetNamespace` (single string, not array)
   and `trustedHeaders` (at least one entry).
10. MCP tools must have `name` and `description`. MCP tool input parameters
    must have `name`, `type`, and `description`. Tools may declare optional
    `hints` (readOnly, destructive, idempotent, openWorld) — these map to
    MCP `ToolAnnotations` on the wire.
11. ExposedOperation supports exactly two modes (oneOf): simple (`call` +
    optional `with`) or orchestrated (`steps` + optional `mappings`). Never
    mix fields from both modes.
12. Do not modify `scripts/lint-capability.sh` unless explicitly asked —
    it wraps Spectral with the correct ruleset and flags.
13. Do not add properties that are not in the JSON Schema — the schema
    uses `additionalProperties: false` on most objects.
14. Every exposed `inputParameter` must be referenced in at least one
    step's `with` block or mapping — orphan parameters bloat the API
    surface and confuse consumers.
15. Every consumed `outputParameter` must be referenced in the exposed
    part (via mappings, or `outputParameters`) — unreferenced
    outputs are dead declarations that add noise without value.
16. Do not prefix variable names with the capability, namespace, or
    resource name — variables are already scoped to their context.
    Redundant prefixes reduce readability without adding disambiguation.