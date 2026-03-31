---
name: chain-api-calls-reference
description: >
  “Chain API calls” reference: use this when the user wants to implement a single
  exposed operation (REST) or tool (MCP) that performs multiple dependent calls in sequence
  using orchestration (`steps` + `mappings`). This pattern is for workflows like
  “create then fetch”, “lookup then call”, “fan-out then aggregate”, or “enrich result
  with additional calls”, where step outputs become inputs for subsequent steps.

---

## When to activate this reference

Use this reference when the user wants to:

- chain multiple consumed operations into one exposed operation (REST) or one exposed tool (MCP);
- handle dependencies between calls (e.g., step 2 needs an `id` from step 1);
- perform an “orchestrated” operation that cannot be expressed as a single `call`;
- add a `lookup` step to match values from a previous step’s response.

Do not use it if:

- the exposed operation can be expressed as a single `call` (+ `with`) (prefer simple mode);
- the goal is only to “compose several independent calls into one response” with no ordering/dependency (still possible here, but `reusable-capability` is the better story framing).

## Prerequisites (schema & rules)

Before writing chaining logic:

- Namespaces must be globally unique across all adapters and bindings:
    - root `consumes`, `capability.consumes`,
    - `capability.exposes`,
    - root `binds` and `capability.binds`.
- For HTTP consumes:
    - `baseUri` must not end with `/` (Spectral warns otherwise).
    - consumed resource `path` must not contain a query string (`?`).
- For exposed REST resources:
    - `path` must not contain a query string and must not end with `/`.
- All markdown fields must be safe:
    - no `<script` tags
    - no `eval(` calls
- Recommended hygiene:
    - each `consumes` entry should have a `description` (Spectral warns otherwise).

## Core concept: orchestrated mode for exposed operations/tools

Chaining is implemented in orchestrated mode:

- `steps`: ordered list of steps (min 1)
- optional `mappings`: map step outputs to:
    - later step inputs (`with` values), and/or
    - final exposed outputs
- `outputParameters`: the final, typed output contract

Important:

- Do not mix modes within one exposed operation/tool:
    - simple mode uses `call` (operation-level) + optional `with`
    - orchestrated mode uses `steps` (and no operation-level `call`)
- Step `name` should be stable and referenceable (recommended pattern: `^[A-Za-z0-9_-]+$`).

## Step types

### Step type: `call`

Use `type: call` to execute a consumed operation:

- must include `call: {namespace}.{operationName}`
- may include `with` (inject inputs)

### Step type: `lookup`

Use `type: lookup` to resolve values from a previous step’s output treated as an index/table:

- `index`: name of a previous `call` step
- `match`: key field name in the index entries
- `lookupValue`: JsonPath resolving to the value(s) to match
- `outputParameters`: list of fields to extract from matched entries

Use lookup when:

- you first fetch a collection/list (step A),
- then need to select one element based on a value coming from another step or from inputs.

## Constraints (chain-specific)

1. The exposed operation/tool must be orchestrated: it must define `steps`.
2. If the intent is “chain calls”, it should have at least 2 steps.
3. Steps are executed in order; dependencies must respect ordering:
    - if step B depends on step A, B must come after A.
4. If step B depends on step A, B must inject at least one value originating from A:
    - either via `with`, or via intermediate mapping strategy (implementation detail),
    - but always explicitly captured in the YAML configuration.
5. Any value reused from a consumed call should be extracted explicitly:
    - define `outputParameters` on the consumed operation (JsonPath) for fields you intend to reuse downstream.
6. Final `outputParameters` must be populated:
    - each final output should be set by at least one mapping from step outputs.
7. Keep paths clean:
    - do not put query strings in any resource `path`;
    - represent query params via `inputParameters` (`in: query`) + `with`.

## Recommended workflow (recipe)

### Step 0 — Decide the exposed contract first

- What is the exposed operation/tool supposed to return?
- Which inputs does it require?
- Which intermediate IDs/values are needed between calls?

Then declare:

- exposed `inputParameters` (typed + described)
- final `outputParameters` (typed)

### Step 1 — Define the minimal chain (happy path)

- Start with the smallest number of steps that produces the desired result.
- Use descriptive step names:
    - `createX`, `getX`, `listX`, `lookupX`, `updateX`, etc.

### Step 2 — Add extraction points on consumed operations

For every downstream dependency, ensure the upstream consumed operation exposes:

- `outputParameters[]` with:
    - `name`
    - `type`
    - `value` (JsonPath)

This is the cleanest way to avoid “magic” coupling to raw payload shapes.

### Step 3 — Wire dependencies explicitly

For each dependent step:

- inject inputs with `with`, using:
    - exposed inputs (public API inputs),
    - extracted outputs from previous steps,
    - injected secrets via `binds` if needed (never inline secrets).

### Step 4 — Map results into final output contract

- For orchestrated output, declare named output parameters (typed).
- Use `mappings` to populate those outputs from step results.

## Common chaining patterns

1) Create → Get details

- Step 1: create resource (extract created ID)
- Step 2: fetch full resource by ID
- Output: return the “details” response, not the create response

2) Search → Lookup → Get

- Step 1: search/list (index)
- Step 2: lookup to find the best match
- Step 3: get details for matched item

3) Fan-out then aggregate (careful)

- Multiple call steps using different inputs
- Final mappings aggregate selected fields
- Prefer keeping the number of steps small to avoid complexity

## Validation checklist (fast)

Before considering “chain-api-calls” complete:

- Namespaces are globally unique (consumes/exposes/binds).
- `baseUri` has no trailing slash.
- No consumed/exposed paths contain `?`; exposed REST paths don’t end with `/`.
- Descriptions are safe (no `<script`, no `eval(`).
- Each step has a stable `name` matching the recommended pattern.
- Any value reused across steps is explicitly extracted (consumed `outputParameters`).
- Final `outputParameters` are fully populated by mappings.

## References

- Naftiko JSON Schema: `src/main/resources/schemas/naftiko-schema.json`
- Spectral Rules: `src/main/resources/rules/naftiko-rules.yml`