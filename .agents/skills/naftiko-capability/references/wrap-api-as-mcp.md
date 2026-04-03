---
name: wrap-api-as-mcp-reference
description: >
  “Wrap API as MCP” reference: use this when the user wants to expose an existing
  consumed HTTP API as an MCP server (`exposes.type: mcp`) by turning selected
  consumed operations into MCP tools, resources, and/or prompts. This pattern is
  for agent-facing access (tools/resources/prompts) rather than human-facing REST
  endpoints.

---

## When to activate this reference

Use this reference when the user wants to:

- expose a consumed API to agents as MCP tools (actions), resources (readable data), or prompts (templates);
- design an MCP server wrapper around 1+ consumed HTTP APIs;
- keep the consumed API definition stable and evolve the MCP “surface” independently (tool naming, input schema, outputs).

Do not use it if:

- the user wants HTTP endpoints for humans/services (use `exposes.type: rest`);
- the user only needs simple request forwarding (REST `forward`) without agent tooling.

## Prerequisites (schema & rules)

Before writing MCP exposition:

- Namespaces must be globally unique across:
    - root `consumes`, `capability.consumes`,
    - `capability.exposes`,
    - root `binds` and `capability.binds`.
- For every HTTP consumes:
    - `baseUri` must not end with `/`.
    - consumed resource `path` must not contain a query string (`?`).
- For any text/markdown fields (labels/descriptions):
    - must not contain `<script` tags;
    - must not contain `eval(` calls.
- Recommended hygiene:
    - every `consumes` entry should have a meaningful `description` (helps discovery and avoids Spectral warnings).

## Core concept: MCP exposition in Naftiko

MCP exposition is declared under:

- `capability.exposes[]` with `type: mcp`
- `tools[]` (required by schema for MCP; at least 1)
- optionally `resources[]` and `prompts[]`

An MCP tool/resource/prompt can be:

- simple mode: `call` + optional `with`
- orchestrated mode: `steps` + optional `mappings` + `outputParameters`

Do not mix simple and orchestrated fields in the same tool/resource (choose one).

## Mapping strategy (recommended)

When wrapping an API as MCP, design in this order:

1) Decide what agents should do:

- “search”, “list”, “get details”, “create/update”, “summarize”, “validate”, etc.

2) Choose MCP surface type:

- Tool: agent triggers an action and gets structured output
- Resource: agent reads data identified by a URI (often read-only)
- Prompt: reusable prompt template for consistent instructions

3) For each MCP Tool/Resource:

- decide which consumed operation(s) it calls (single call vs orchestration)
- design `inputParameters` (typed + described) so agents know what to pass
- design output contract:
    - simple mode: inline typed output parameters (`MappedOutputParameter`)
    - orchestrated mode: named typed outputs (`OrchestratedOutputParameter`) plus `mappings`

## Constraints (aligned with schema + rules)

### Global constraints

1. All namespaces must be unique (adapters + bindings).
2. MCP server must define:
    - `type: mcp`
    - `namespace`
    - `tools` (at least one tool)
3. `transport`:
    - if `transport: stdio`, do not set `port`;
    - if `transport: http` (default), `port` is required.
4. All descriptions must be safe (no `<script`, no `eval(`).

### MCP Tool constraints (schema-driven)

For each MCP tool:

1. `name` (kebab-case / IdentifierKebab) is required and must be stable (used as the MCP tool name).
2. `description` is required (agent discovery depends on it).
3. `hints` is optional — declares behavioral hints mapped to MCP `ToolAnnotations`:
    - `readOnly` (bool) — tool does not modify its environment (default: false)
    - `destructive` (bool) — tool may perform destructive updates (default: true, meaningful only when readOnly is false)
    - `idempotent` (bool) — repeating the call has no additional effect (default: false, meaningful only when readOnly is false)
    - `openWorld` (bool) — tool interacts with external entities (default: true)
4. If tool is simple:
    - must define `call: {namespace}.{operationName}`
    - may define `with`
    - should define `outputParameters` (typed) when you want structured results.
5. If tool is orchestrated:
    - must define `steps` (min 1), each step has `name`
    - may define `mappings`
    - `outputParameters` must use orchestrated output parameter objects (named + typed)
6. Tool `inputParameters`:
    - each parameter must have `name`, `type`, `description`
    - set `required: false` explicitly for optional params (default is true)

### MCP Resource constraints (schema-driven)

For each MCP resource:

1. `name`, `label`, `uri`, `description` are required.
2. Resource can be:
    - dynamic (use `call` or `steps`) to fetch data from consumed APIs, or
    - static (use `location: file:///...`) to serve files
3. If using `location`, do not also define `call`/`steps`.

### MCP Prompt constraints (schema-driven)

For each MCP prompt:

1. `name`, `label`, `description` are required.
2. Prompt can be:
    - inline `template` (array of messages), or
    - file-based `location: file:///...` (single user message)
3. If using arguments:
    - arguments are strings (MCP convention)
    - provide `description` for each argument (discovery)

## Recommended naming conventions (pragmatic)

- MCP server `namespace`: mirrors the capability domain (e.g., `notion-mcp`, `github-mcp`, `crm-mcp`)
- Tool `name`: verb-noun in kebab-case (e.g., `search-pages`, `get-database`, `create-ticket`)
- Resource `uri`: choose a clear scheme and stable paths:
    - `docs://...`, `data://...`, `config://...`
    - use `{param}` placeholders only if your MCP client expects templates
- Keep tool input parameter names aligned with consumed parameter intent, but do not leak internal step names.

## Design checklist (practical)

Before considering the MCP wrapper complete:

- The API consumes definitions exist and have unique namespaces.
- No `baseUri` ends with `/`.
- No consumed/exposed paths contain query strings.
- MCP server has at least one tool with a strong description.
- Every tool/resource/prompt has safe descriptions (no `<script`, no `eval(`).
- Each tool input parameter has: name, type, description; optional params set `required: false`.
- For orchestrated tools/resources:
    - step names are stable and match the recommended pattern `^[A-Za-z0-9_-]+$`
    - outputs needed across steps are exposed via consumes `outputParameters` (JsonPath extraction)
    - `mappings` point to real values and populate the declared final outputs.

## Example scaffolding (structure-level, not a full spec)

- Add `capability.exposes[]`:
    - `type: mcp`
    - `namespace`
    - `description`
    - `tools[]`:
        - each tool maps to one consumed operation (`call`) or multiple (`steps`)
        - declare `inputParameters` and typed outputs
    - optionally `resources[]` for read-only “views” over the API
    - optionally `prompts[]` for reusable guidance to agents

## References

- Naftiko JSON Schema: `src/main/resources/schemas/naftiko-schema.json`
- Spectral Rules: `src/main/resources/rules/naftiko-rules.yml`