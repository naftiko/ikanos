---
name: proxy-then-customize-reference
description: >
  “Proxy then customize” reference: use this when the user wants to ship an API wrapper
  quickly by initially exposing a consumed HTTP API with minimal transformation, and then
  iteratively refine the wrapper (naming, input/output contracts, orchestration, security,
  and agent ergonomics) without rewriting everything. This pattern applies to REST
  expositions (`type: rest`) and can be adapted to MCP (`type: mcp`) when the target
  consumers are agents.

---

## When to activate this reference

Use this reference when the user wants to:

- expose an existing API *as-is* (or nearly as-is) to get value quickly;
- start with a small subset of endpoints and grow coverage over time;
- progressively add:
    - input parameter normalization,
    - response shaping via `outputParameters`,
    - orchestration (`steps` + `mappings`),
    - authentication and secret injection (`binds`),
    - better descriptions for agent discovery and documentation quality.

Do not use it if:

- the user already knows the final contract and wants a “designed API” from day one (you can still use it, but skip the proxy phase);
- the user needs only pure resource forwarding (`forward`) with no intent to customize.

## Prerequisites (schema & rules)

Before shipping even the “proxy” phase, ensure:

- Namespaces are globally unique across all adapters and bindings (consumes/exposes/binds).
- For HTTP `consumes`:
    - `baseUri` must not end with `/`.
    - consumed resource `path` must not contain query strings (`?`).
- For REST `exposes` resources:
    - `path` must not end with `/`.
    - `path` must not contain query strings (`?`).
- Descriptions must be safe: no `<script` tags and no `eval(` in markdown fields.
- Recommended hygiene:
    - each `consumes` entry should have a meaningful `description` (Spectral warns otherwise).

## Core idea: evolve from “pass-through” to “productized API”

This story promotes a staged approach:

### Phase 1 — Proxy (ship fast)

Goal: expose working endpoints quickly with minimal transformation.

Typical characteristics:

- 1 exposed REST adapter (`capability.exposes[].type: rest`)
- 1 consumed HTTP adapter (`consumes[]` or `capability.consumes[]`)
- Exposed operations are mostly simple mode:
    - `call: {namespace}.{operationName}`
    - optional `with` for wiring exposed inputs to consumed inputs

Notes:

- Even in proxy mode, keep the public surface clean:
    - do not leak awkward upstream parameter names if they harm usability;
    - do not bake query strings into paths (use `inputParameters`).
- Provide meaningful `description` fields: agent discovery and maintainability depend on it.

### Phase 2 — Stabilize contract (names + parameters)

Goal: make the wrapper predictable and agent-friendly.

Actions:

- Add/clean `label` and `description` for:
    - exposed resources,
    - exposed operations,
    - consumed adapters (recommended).
- Define `inputParameters` on exposed resources/operations so the public API is explicit:
    - name, `in` (query/path/header/body/cookie), type, description
- Wire parameters via `with`:
    - pass-through when appropriate,
    - or normalize (rename, default, constrain) at the exposed boundary.

### Phase 3 — Shape output (typed results)

Goal: return a stable, typed response contract that hides upstream noise.

Actions:

- For simple operations (single call):
    - define `outputParameters` as typed mappings (JsonPath extraction) so clients/agents get stable fields.
- Avoid returning raw upstream payloads when they are huge, unstable, or contain sensitive fields.

### Phase 4 — Customize behavior (orchestration)

Goal: add value beyond proxying by chaining calls, enriching responses, or applying routing logic.

Switch selected operations to orchestrated mode:

- use `steps` (call + optional lookup)
- use `mappings` to populate final `outputParameters`

Constraints:

- Do not mix simple-mode and orchestrated-mode fields in the same exposed operation.
- If a step output is used later, ensure the consumed operation defines `outputParameters` (JsonPath) for the needed values.

### Phase 5 — Harden (security, secrets, environments)

Goal: move from “works locally” to “safe in prod”.

Actions:

- Introduce `binds` to inject secrets via external sources:
    - use explicit URI schemes (e.g. `file://`, `vault://`, `k8s-secret://`, `github-secrets://`)
    - keep variable names SCREAMING_SNAKE_CASE as required by the schema for binding keys.
- Avoid placing secrets directly in capability files.
- Ensure all markdown fields stay free of `<script` and `eval(`.

## Recommended constraints (pragmatic)

1. Start small: ship 1 resource with 1–3 operations first.
2. Keep namespaces stable:
    - consumed API namespace rarely changes;
    - exposed namespace should be treated like a public API identifier.
3. Every exposed operation must clearly pick one mode:
    - simple: `call` (+ `with`) and optionally `outputParameters`
    - orchestrated: `steps` (+ `mappings`) and `outputParameters`
4. Avoid “proxying everything” by default:
    - prefer a curated surface to reduce maintenance and improve agent usability.
5. Avoid query strings in `path` (consumed or exposed). Use `inputParameters` + `with`.

## Validation checklist (fast)

Before merging a “proxy then customize” iteration:

- Namespaces are globally unique.
- `baseUri` has no trailing slash.
- No consumed/exposed paths contain `?`; exposed paths don’t end with `/`.
- All descriptions are safe (no `<script`, no `eval(`).
- Each `consumes` entry has a `description` (recommended).
- Exposed operations:
    - are either simple or orchestrated (never mixed),
    - have clear input parameters and wiring (`with`),
    - have stable output contracts (at least for “public” operations).

## References

- Naftiko JSON Schema: `src/main/resources/schemas/naftiko-schema.json`
- Spectral Rules: `src/main/resources/rules/naftiko-rules.yml`