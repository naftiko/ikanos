---
name: ikanos-capability
version: "1.0.0-alpha1"
description: >
  Skill for authoring, validating, and debugging Ikanos Capability YAML files
  (spec v1.0.0-alpha1). Activate when the user wants to: write a new capability
  document, add or change authentication on a consumed API, configure orchestration
  steps or parameter mappings, set up a forward proxy, expose an MCP server or Skill
  server, configure external references for secrets, add a control port for health
  checks and metrics, enable OpenTelemetry observability, or run the Spectral linter.
  The Ikanos Specification defines modular, composable capabilities that consume
  external APIs and expose REST, MCP, Skill, or Control adapters.
allowed-tools:
  - Read
  - Write
  - Bash
  - Glob
---

## Overview

Ikanos lets you declare **capabilities** — functional units that
**consume** external APIs and **expose** adapters (REST, MCP, Skill). A capability
is a single YAML file validated against the Ikanos JSON Schema (v1.0.0-alpha1).

Key spec objects you will work with:

- **Info** — metadata: label, description, tags, stakeholders
- **Capability** — root technical config; contains `exposes`, `consumes`, and `aggregates`
- **Consumes** — HTTP client adapter: baseUri, namespace, resources, operations
- **Exposes** — server adapter: REST (`type: rest`), MCP (`type: mcp`), Skill (`type: skill`), or Control (`type: control`)
- **Aggregates** — DDD-inspired domain building blocks; each aggregate groups reusable functions under a namespace. Tools and operations reference functions via `ref`
- **Observability** — optional OTel configuration on the control adapter: trace sampling, propagation format, OTLP exporter endpoint
- **Script Steps** — embed JavaScript, Python, or Groovy transformations between API calls using sandboxed GraalVM engines. Scripts read step results via bound variables and produce output through a `result` variable
- **Binds** — variable injection from file (dev) or runtime (prod)
- **Namespace** — unique identifier linking exposes to consumes via routing

Canonical sources (read these, never duplicate them):

- Specification: `ikanos-docs/wiki/Specification.md`
- JSON Schema: `ikanos-spec/src/main/resources/schemas/ikanos-schema.json`
- Polychro Ruleset: `ikanos-spec/src/main/resources/rules/ikanos-rules.yml`

## Decision Framework

Match the user's situation to a story reference. Each story explains *why*
(the user's problem), *what* (the Ikanos pattern), and points to the spec
for *how*.

| Situation | Action |
|---|---|
| "I want to combine several APIs into a single reusable service" | Read `references/reusable-capability.md` |
| "I want to expose this API as an MCP server, including tools, resources, and prompts" | Read `references/wrap-api-as-mcp.md` |
| "I want to proxy an API today and encapsulate it incrementally" | Read `references/proxy-then-customize.md` |
| "I want to chain multiple HTTP calls to consumed APIs and expose the result into a single REST operation" | Read `references/chain-api-calls.md` |
| "I need to go from local test credentials to production secrets" | Read `references/dev-to-production.md` |
| "I want to define a domain function once and expose it via both REST and MCP" | Use `aggregates` with `ref` — read `references/design-guidelines.md` (Aggregate Design Guidelines) |
| "I want to add health checks, Prometheus metrics, or trace inspection" | Read `references/control-port-observability.md` |
| "I want to enable OpenTelemetry distributed tracing and RED metrics" | Read `references/control-port-observability.md` |
| "I want to transform data between API calls using JavaScript, Python, or Groovy" | Read `references/inline-script-step.md` |
| "I want to prototype a tool or endpoint before the backend exists" or "I want to return static or dynamic mock data" | Read `references/mock-capability.md` |
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
   begin with `ikanos: "1.0.0-alpha1"`.
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
   - `ikanos-namespaces-unique` (error) — duplicate namespace
   - `ikanos-consumes-baseuri-no-trailing-slash` (warn) — trailing `/`
   - `ikanos-consumed-resource-no-query-in-path` (warn) — query in path
   - `ikanos-rest-resource-path-no-trailing-slash` (warn)
   - `ikanos-baseuri-not-example` (warn) — placeholder URI
   - `ikanos-no-script-tags-in-markdown` (error) — XSS in descriptions
   - `ikanos-consumes-description` (warn) — missing description
   - `ikanos-control-port-singleton-and-unique` (error) — more than one control adapter, or port collision
   - `ikanos-control-address-localhost-warning` (warn) — control port bound to non-localhost
   For the full rule list, read the Spectral ruleset file directly.
3. Fix and re-lint. Repeat until clean.

### Mock a Capability

Use mock mode when the upstream API does not exist yet, or you want to
prototype a contract-first design. Read `references/mock-capability.md`
before writing any mock output parameters.

**Key rules:**

1. Omit `call`, `steps`, and the entire `consumes` block — they are not
   needed for a pure mock capability.
2. Use `value` on `MappedOutputParameter` for static strings
   (`value: "Hello"`) or Mustache templates (`value: "Hello, {{name}}!"`).
   Do NOT use `const` — it is schema-only and is never used at runtime.
3. Mustache placeholders in `value` are resolved against the tool's or
   endpoint's input parameters by name. Only top-level input parameter
   names are in scope — no nesting, no `with` remapping.
4. `value` and `mapping` are mutually exclusive on a scalar output
   parameter — never set both.
5. Object and array type output parameters in mock mode must carry
   `value` on each leaf scalar descendant; the container itself has
   no `value`.
6. When the mock is ready to be wired to a real API, add `consumes`,
   replace `value` with `mapping`, and add `call` or `steps` — the
   exposed contract does not change.
   and `trustedHeaders` (at least one entry).
10. MCP tools must have `name` and `description` (unless using `ref`, in which
    case they are inherited from the referenced aggregate function). MCP tool input
    parameters must have `name`, `type`, and `description`. Tools may declare optional
    `hints` (readOnly, destructive, idempotent, openWorld) — these map to
    MCP `ToolAnnotations` on the wire.
11. ExposedOperation supports three modes (oneOf): simple (`call` +
    optional `with`), orchestrated (`steps` + optional `mappings`), or
    ref (`ref` to an aggregate function). Never mix fields from
    incompatible modes.
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
17. When using `ref` on MCP tools or REST operations, the `ref` value must
    follow the format `{aggregate-namespace}.{function-name}` and resolve
    to an existing function in the capability's `aggregates` array.
18. Do not chain `ref` through multiple levels of aggregates — `ref`
    resolves to a function in a single aggregate, not transitively.
19. Aggregate functions can declare `semantics` (safe, idempotent, cacheable).
    When exposed via MCP, the engine auto-derives `hints` from semantics.
    Explicit `hints` on the MCP tool override derived values.
20. Do not duplicate a full function definition inline on both MCP tools
    and REST operations — use `aggregates` + `ref` instead.
21. In mock mode, use `value` on output parameters — never `const`.
    `const` is a JSON Schema keyword retained for validation and linting
    only; it has no effect at runtime.
22. In mock mode, Mustache templates in `value` fields resolve only against
    top-level input parameter names. Do not reference `with`-remapped
    consumed parameter names — those are not in scope for output resolution.
23. At most one `type: "control"` adapter is allowed per capability. Its
    port must not collide with any business adapter port.
24. The control port `address` should be `localhost` or `127.0.0.1` for
    security. Binding to `0.0.0.0` exposes management endpoints externally.
25. The `/metrics` and `/traces` control port endpoints are configured under
    `observability.metrics.local` and `observability.traces.local` respectively.
    Observability is **enabled by default** — all `enabled` fields default to
    `true`. Set `enabled: false` to disable specific endpoints or observability
    entirely. They return 503 when the OTel SDK is not on the classpath.
26. When adding a control port, also add `observability` on the control
    adapter if you want metrics and traces to produce data. Without it,
    the local endpoints return empty or 503 responses.
27. The `observability.exporters.otlp.endpoint` field supports Mustache
    expressions for binds (e.g. `"{{OTEL_ENDPOINT}}"`). Use binds to
    keep exporter URLs environment-specific.
28. Script steps require a `file` property. `language` and `location` are
    optional when Control Port defaults are configured via
    `management.scripting.defaultLanguage` and `management.scripting.defaultLocation`.
29. Script `dependencies` are pre-evaluated in order before the main script.
    Shared logic (helpers, constants) goes in dependencies.
30. The script must assign to the `result` variable to produce output.
    Previous step results are bound as variables matching step names.
31. When `management.scripting` is configured on the Control Port,
    `defaultLanguage` and `defaultLocation` serve as fallbacks — step-level
    values take precedence.
32. `allowedLanguages` restricts which languages script steps may use.
    If omitted, all three languages (`javascript`, `python`, `groovy`) are
    allowed.