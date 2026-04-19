---
name: design-guidelines-reference
description: >
  Design guidelines for authoring Naftiko capability documents that are easy to
  maintain, safe to run, and easy for agents to discover and use. Use this reference
  when the user asks “what should I check before shipping?” or when reviewing a
  capability for quality beyond schema validity (style, consistency, ergonomics).

---

## Scope

These guidelines complement:

- the Naftiko JSON Schema (structural correctness),
- the Spectral ruleset (cross-object consistency, hygiene, and security checks).

They focus on:

- naming and consistency,
- API surface design (REST/MCP),
- orchestration readability,
- secret management,
- lint-driven workflow,
- agent discoverability via descriptions.

## Non-negotiables (baseline quality)

1. Do not include secrets in YAML.
    - Use `binds` and injected variables instead.
2. Keep markdown fields safe.
    - No `<script` tags, no `eval(` in any label/description fields.
3. Keep namespaces globally unique.
    - Across consumes, exposes, and binds (root + capability-scoped).
4. Keep paths and base URIs clean.
    - `consumes.baseUri` must not end with `/`.
    - No query strings (`?`) in consumed or exposed `path`.
    - Exposed REST resource paths should not end with `/`.

## Naming & consistency

### Namespaces

- Treat namespaces as stable identifiers: changing them is a breaking change.
- Use kebab-case and meaningful domain names:
    - consumes: `github`, `notion`, `stripe`, `internal-crm`
    - exposes (REST): `reporting-api`, `notion-proxy`
    - exposes (MCP): `notion-mcp`, `crm-mcp`
    - binds: `vault-core`, `github-secrets`, `local-dev`

### Resource and operation names

- Exposed operation `name` should be short, kebab-case, and intention-revealing:
    - `get-report`, `search-pages`, `create-ticket`
- Consumed operation names should remain close to upstream semantics but avoid leaking upstream quirks into the exposed surface.

### Step names (orchestration)

- Use stable, descriptive step names (recommended pattern: `^[A-Za-z0-9_-]+$`).
- Prefer `getUser`, `createIssue`, `listProjects`, `lookupOwner` over `step1`, `call2`.

## Descriptions (agent discoverability)

Descriptions are not “nice to have”. They are the primary interface for:

- humans scanning a capability,
- agents deciding which tool/operation/resource to use.

Guidelines:

- Every `consumes` entry should have a `description` explaining what the service is and why it is used.
- Exposed resources and operations should have:
    - what it does,
    - when to use it,
    - what the important inputs/outputs represent.
- MCP tools/resources/prompts must have strong descriptions; they are the discovery surface for agents.

Avoid:

- vague descriptions (“does stuff”, “wrapper”),
- embedding secrets or environment details.

## API surface design

### Prefer curated surfaces over “expose everything”

- Start with a minimal set of exposed operations that match user intent.
- Add endpoints/tools incrementally as needs arise (proxy-then-customize approach).

### REST design guidelines

- Keep resource paths clean (no query strings, no trailing slashes).
- Declare exposed `inputParameters` explicitly (typed + described).
- Avoid “god endpoints” with too many optional parameters; split into focused operations when needed.

### MCP design guidelines

- Use tools for actions and resources for read-only data access.
- Prefer small tools with crisp, typed `inputParameters`.
- If an MCP tool becomes complex, switch to orchestration and document it clearly.
- Use `hints` to signal tool behavior to clients:
    - Set `readOnly: true` for tools that only read data (GET-like).
    - Set `destructive: true` for tools that delete or overwrite (DELETE, PUT).
    - Set `idempotent: true` for tools safe to retry.
    - Set `openWorld: true` for tools calling external APIs; `false` for closed-domain tools (local data, caches).

## Orchestration guidelines (steps + mappings)

### Pick exactly one mode per exposed operation/tool

- Simple mode:
    - `call` + optional `with` (+ optional typed `outputParameters`)
- Orchestrated mode:
    - `steps` (+ optional `mappings`) + typed `outputParameters`

Do not mix fields from both modes in one operation/tool.

### Make dependencies explicit and readable

- If step B depends on step A, B must come after A and consume at least one value derived from A.
- Extract reusable values explicitly:
    - define consumed operation `outputParameters` (JsonPath) for the fields you reuse downstream.
- Keep `steps` short and composable; prefer multiple simple steps over one complicated step.

### Use lookup only when it clarifies the intent

- `lookup` is great for selecting items from an index returned by a previous call.
- Avoid it if a direct downstream API call can do the same more reliably.

## Output shaping guidelines

- Prefer returning stable, typed `outputParameters` over raw upstream payloads.
- Do not expose internal IDs unless they are necessary and meaningful for consumers.
- Avoid returning massive nested objects if only a few fields are needed.

## Aggregate design guidelines (DDD-inspired)

Aggregates borrow from [Domain-Driven Design](https://en.wikipedia.org/wiki/Domain-driven_design#Building_blocks): each aggregate groups related functions under a namespace that represents a single domain concept (the **Aggregate Root**). Functions within the aggregate are the operations that agents and clients can invoke.

### Define aggregate boundaries around domain concepts

- One aggregate = one domain concept (e.g., `forecast`, `ticket`, `user-profile`).
- Functions within an aggregate should operate on the same domain data — if a function feels unrelated, it likely belongs in a different aggregate.
- Keep function names intention-revealing and adapter-neutral: `get-forecast`, not `mcp-get-forecast` or `rest-forecast-query`.

### Use `ref` to share functions across adapters

- When the same domain operation is exposed via REST *and* MCP, define it once in `aggregates` and reference it with `ref` from both adapters.
- Override only the adapter-specific fields at the tool/operation level (e.g., `method` for REST, `hints` for MCP).
- Do not duplicate the full function definition inline when `ref` can carry it.

### Use `semantics` as the single source of behavioral truth

- Declare `safe`, `idempotent`, and `cacheable` on the aggregate function — they describe the domain behavior, not a transport detail.
- Let the engine derive MCP `hints` from semantics automatically. Override hints only when the derived values are insufficient (e.g., setting `openWorld`).
- Do not set `semantics` on functions that are only exposed via REST — REST has its own semantic model via HTTP methods.

### Keep aggregates lean

- Start with functions only (the "functions-first" approach). Entities, events, and other DDD stereotypes may be added in future schema versions.
- Avoid creating an aggregate for a single function that is only used in one place — aggregates pay off when sharing across adapters or when grouping related operations.

## Control port & observability

### Control port placement

- Declare at most one `type: "control"` adapter per capability.
- Assign a dedicated port that does not collide with any business adapter.
- Keep `address` as `localhost` (default) for security. Use `0.0.0.0` only inside containers where external probes (Prometheus, Kubernetes) need to reach the port.
- The control port is a management plane — it does not serve business traffic.

### Observability pairing

- Observability is **enabled by default** — adding an `observability` block (even empty) activates metrics and traces when the OTel SDK is on the classpath. Use `enabled: false` only to disable it while keeping the rest of the config.
- Use `binds` for the OTLP exporter endpoint so it can vary across environments.
- Set `traces.sampling` to a value lower than `1.0` in high-traffic production scenarios to reduce overhead.
- Prefer W3C propagation (`w3c`) unless the upstream ecosystem requires B3.

## Secret management (dev → prod)

- Use `binds` for any sensitive values or environment-dependent configuration.
- Binding variable names must be SCREAMING_SNAKE_CASE (schema constraint).
- Prefer stable variable names across environments; only provider mappings/locations change:
    - dev: `file://...`
    - prod: `vault://...` / `k8s-secret://...` / injected runtime

Never:

- hardcode tokens/passwords,
- echo secret values back in outputs.

## Lint-first workflow

- Treat lint as the authoritative “gate”:
    - fix errors immediately,
    - address warnings deliberately (don’t ignore them by default).
- Common issues to watch:
    - duplicate namespaces (error),
    - trailing slash in `baseUri` (warn),
    - query strings in `path` (warn),
    - missing `consumes.description` (warn),
    - unsafe markdown (error).

## Pre-ship checklist (quick)

Before shipping a capability:

- Schema-valid (JSON Schema passes).
- Spectral clean (no errors; warnings understood/accepted).
- Namespaces globally unique.
- No secrets in YAML; `binds` used appropriately.
- REST/MCP surface is curated, documented, and discoverable.
- Orchestration is readable: step names, explicit dependencies, explicit extracted outputs.
- Outputs are shaped and stable (typed `outputParameters`).
- Control port (if present): single adapter, no port collision, `localhost` address.
- Observability (if present): paired with a control port for `/metrics` and `/traces`.

## References

- Naftiko JSON Schema: `src/main/resources/schemas/naftiko-schema.json`
- Spectral Rules: `src/main/resources/rules/naftiko-rules.yml`