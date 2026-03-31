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

## References

- Naftiko JSON Schema: `src/main/resources/schemas/naftiko-schema.json`
- Spectral Rules: `src/main/resources/rules/naftiko-rules.yml`