---
name: reusable-capability-reference
description: >
  “Reusable Capability” reference: use this when the user wants to compose multiple
  consumed APIs (or multiple operations from the same API) into a single exposed
  operation (REST or MCP) using orchestration `steps` + `mappings`. Typical
  cases: aggregating data, enriching a response, running sequential calls, or
  combining results.

---

## When to activate this reference

Use this reference when the user wants to:

- aggregate multiple HTTP calls into a single exposed endpoint (REST);
- create an MCP tool that chains multiple operations (a “multi-call” tool);
- enrich a response by calling API A then API B (or using a lookup step);
- orchestrate dependencies between calls (e.g., fetch an `id`, then reuse it).

Do not use it if:

- the exposed operation is a simple proxy to a single operation (prefer the simple mode: `call` + `with`);
- the need is only REST resource forwarding (see `forward` / `ForwardConfig` on REST resources).

## Prerequisites (schema & rules)

Before orchestrating, check:

- The target `consumes` entries exist and each has a unique `namespace`.
- `consumes.baseUri` has no trailing slash (e.g., `https://api.example.com`, not `.../`).
- `consumes.resources[*].path` does not include a query string (no `?foo=bar` in the path).
- `capability.exposes[rest].resources[*].path` has neither `?` nor a trailing slash.
- Markdown fields (label/description) do not contain `<script` or `eval(`.
- Descriptions are present (at minimum on each `consumes`, and ideally on exposed resources/operations too).

Note: namespace uniqueness (across consumes / exposes / binds) is a global constraint validated by the rules.

## Core concept: orchestration = `steps` + `mappings`

A “reusable-capability” exposed operation uses orchestrated mode:

- `steps`: a list of named steps (at least two), typically of type `call` (and sometimes `lookup`).
- `mappings`: maps values extracted from step outputs (JsonPath) to:
    - either input parameters of later steps (via their `with`),
    - or the exposed operation’s `outputParameters` (its structured response).

In orchestrated mode:

- do not mix “simple mode” and “orchestrated mode” fields in the same exposed operation.
    
    Concretely: an orchestrated operation should not define `call` at the operation level; it defines `steps`.
    
- each `step` should have a stable, referenceable `name` (recommended pattern: `^[A-Za-z0-9_-]+$`).

## Constraints

1. The exposed operation must use orchestrated mode and define `steps` (min 2).
2. Each `call` step must reference an existing consumed operation using `call: {namespace}.{operationName}`.
3. Steps may be independent (no dependency) or dependent.
4. If step Y depends on step X, then Y must appear after X in the `steps` list.
5. If step Y depends on step X, then Y’s `with` must include at least one value coming from step X (directly or via a mapping).
6. Any value coming from step X and used by step Y must be exposed by X as an `outputParameters` field on the consumed operation being called (JsonPath extraction). Without `outputParameters` on the consumes side, orchestration cannot reliably “name” reusable intermediate data.
7. `mappings` must use valid JsonPath expressions and must reference fields that actually exist in the referenced step output (raw or extracted).
8. Avoid unnecessary duplication: only map the fields needed by later steps and/or the final result.
9. Ensure the exposed operation’s orchestrated `outputParameters` define a useful contract (stable names, types), and that each `outputParameter.name` is populated by at least one mapping.
10. Do not put query params in any `path` (consumed or exposed). Use `inputParameters` (`in: query`) and pass values via `with`.

## Recommended pattern (recipe)

### Step 0 — Define intent and the output contract

- Which APIs / operations are required?
- What is the final output contract of the exposed operation?
- Which intermediate fields must flow between steps?

Then:

- Declare (or complete) `outputParameters` on the consumed operations to extract the fields you need.
- Declare orchestrated `outputParameters` on the exposed operation, then write `mappings` to populate them.

### Step 1 — Write the `steps`

- Name steps with stable technical identifiers (e.g., `getUser`, `getOrders`, `lookupCountry`).
- Prefer several small, readable calls over one overly “magical” call.
- In `with`, include only what is needed, using:
    - static values (string/number);
    - or expressions coming from the execution context (exposed inputs, previous step outputs, injected variables via binds).

### Step 2 — Add an optional `lookup`

The schema supports a `type: lookup` step to do “table lookup” resolution based on a previous call step result.

Use it when:

- you already loaded an index (a list/array) via a `call` step,
- you want to select an entry matching a key (`match`) and extract fields.

### Step 3 — Map to the final output

Use `mappings` to:

- map step outputs to another step’s input parameters (if you centralize injection),
- and/or map to the exposed operation’s final `outputParameters`.

## Example (pseudo-structure, to be adapted)

Goal: expose a REST operation that (1) fetches a user, (2) fetches that user’s orders, then (3) returns an aggregated response.

- Step A (call) `users.get-user` → extracts `userId`, `email`
- Step B (call) `orders.list-orders` with `with` using `userId`
- Mappings → fills final `outputParameters`: `user`, `orders`, `orderCount`

Key points:

- `orders.list-orders` must accept a parameter (query/path) to filter by userId.
- `users.get-user` must define an `outputParameters` entry for `userId` (JsonPath) so it can be reused.
- Paths remain “clean” (no inline query string).

## Validation checklist (practical)

Before considering the composition complete:

- Namespaces are unique (consumes/exposes/binds).
- No `baseUri` ends with `/`.
- No `path` (consumed/exposed) contains `?`; no exposed path ends with `/`.
- Each `consumes` entry has a `description`.
- Each step has a `name` matching the recommended pattern.
- Any inter-step dependency is explicit (ordering + `with`/mappings).
- Extracted fields (JsonPath) are declared via `outputParameters` on the consumes side.
- Descriptions do not contain dangerous content (`<script`, `eval(`).

## References

- Naftiko JSON Schema: `src/main/resources/schemas/naftiko-schema.json`
- Spectral Rules: `src/main/resources/rules/naftiko-rules.yml`