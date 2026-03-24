# Rename externalRefs to binds — Proposal

Version: 0.5
Created by: Thomas Eskenazi
Category: ADR
Last updated time: March 20, 2026 5:46 PM
Reviewers: Jerome Louvel, Kin Lane
Status: To Review

# AD: Rename `externalRefs` to `binds` and simplify the object model

<aside>
🧭

This proposal targets the Naftiko Specification v0.5 and affects the root-level `externalRefs` array, its child objects, and the expression syntax that references injected variables.

</aside>

## Context and Problem Statement

### Background

The current `externalRefs` block was originally designed to serve two purposes:

1. **Import** reusable fragments from external files
2. **Declare variables** to be injected at runtime

The import mechanism has since been dropped from the specification scope. Today, `externalRefs` **only** declares variables to be injected — it no longer references or imports anything.

### Problem

The name `externalRefs` carries the wrong semantics:

- **"External"** is interpreted as *external to the business domain* (e.g. a third-party API), not *external to the file* as originally intended.
- **"Refs"** implies referencing or importing content, which the block no longer does.
- The `type: "environment"` field is redundant — there is no other type.
- The `resolution` field (`"file"` / `"runtime"`) conflates *how* values are resolved with *where* they come from. The `uri` field is only present in file mode, creating an asymmetric schema.

### Scope

- Rename `externalRefs` → `binds`
- Remove the `type` field (always `"environment"`, adds no information)
- Replace `resolution` + `uri` with a single `location` field (URI-based, scheme expresses the provider)
- Rename `name` → `namespace` for consistency with `consumes` and `exposes` identifiers
- Enforce SCREAMING_SNAKE_CASE on binding variable names (`keys` left side) for visual distinction from declared parameters
- Use verb form `binds` (not noun `bindings`) for consistency with `consumes` and `exposes`
- Place `binds` following the same rule as `consumes`: child of `capability` when a capability is declared, root-level only when standalone
- Update expression syntax documentation accordingly

### Out of scope

- Changing the expression syntax itself (`\{\{variable\}\}` remains unchanged)
- Adding new provider schemes (can be done incrementally)
- Changing `keys` semantics
- Extracting a shared `FileLocation` pattern (existing `^file:///` usages in McpResource, McpPrompt, ExposedSkill — separate initiative)
- Import mechanism for bindings (planned for a later release, see dedicated section below)

---

<aside>
✅

**Decision statement:** We will rename `externalRefs` to `binds`, rename `name` to `namespace`, and collapse `resolution` + `uri` into a single `location` field, because the current naming is semantically misleading and the object model carries unnecessary complexity.

</aside>

## Decision Drivers

Prioritized criteria:

1. **Semantic clarity** — the name must accurately describe what the block does: binding variables from an external source to the capability
2. **Familiarity** — the naming should be recognizable to Cloud Native / K8s practitioners
3. **Schema simplicity** — fewer fields, less branching logic, easier validation
4. **Expressiveness of `location`** — the URI scheme should clearly identify the provider (file, vault, GitHub secrets, K8s secrets, etc.)
5. **Backward compatibility** — migration path from `externalRefs` must be straightforward

## Considered Options

### Option 0: Keep `externalRefs` as-is

- **What it is**: No change.
- **Pros**: Zero migration effort.
- **Cons**: Semantically misleading, carries dead weight (`type` field), asymmetric schema between file and runtime resolution.
- **Verdict**: Rejected — the name actively confuses users and reviewers.

### Option 1: Rename to `vars`

- **What it is**: Use `vars` as the root-level key.
- **Pros**: Short, immediately understood by developers. Feels natural in a YAML document — `vars` is a familiar pattern in CI/CD tools (GitHub Actions, GitLab CI, Ansible). Low cognitive overhead.
- **Cons**: Too close to `inputParameters` and other parameter declarations in the spec — risks confusion between externally injected variables and locally declared parameters. The name speaks to developers but not to integrators, who are the primary audience for bindings configuration.
- **Verdict**: Rejected — `binds` better serves the integrator persona and avoids ambiguity with the existing parameter vocabulary.

### Option 3: Rename to `params`

- **What it is**: Use `params` as the root-level key.
- **Pros**: Universal, short, unambiguous.
- **Cons**: Too generic — `params` could refer to input parameters, query parameters, etc. Overlap risk with `inputParameters` in consumes/exposes.
- **Verdict**: Rejected — collision risk with existing spec vocabulary.

### Option 4: Rename to `binds` ✅

- **What it is**: Use `binds` as the root-level key. Each entry declares that the capability *binds* to an external source of values.
- **Pros**:
    - Well-established pattern in the K8s ecosystem (Service Binding Specification)
    - Accurately describes the mechanism: a *binding* connects a consumer (capability) to a provider (secret store, file, runtime)
    - `location` as a URI naturally expresses the provider via scheme (`file://`, `vault://`, `github-secrets://`, etc.)
    - Absence of `location` = runtime injection (clean default)
    - Verb form aligns with `consumes` and `exposes` — the spec consistently uses action-oriented / intentional semantics for root-level blocks
- **Cons**:
    - Slightly more abstract than `vars` or `env` — requires minimal context to understand
- **Verdict**: Recommended.

### Option 5: Rename to `inputs`

- **What it is**: Use `inputs` as the root-level key.
- **Pros**: Conveys that these are required inputs for the capability to function.
- **Cons**: Ambiguous — `inputs` could also refer to HTTP inputs, tool inputs, etc. Less specific than `bindings`.
- **Verdict**: Rejected — too broad.

## Decision Outcome

### Decision

Rename `externalRefs` to **`binds`**, rename `name` to **`namespace`**, remove `type`, and collapse `resolution` + `uri` into `location`.

### Rationale

1. **Semantic alignment** — a binding is a declarative link between a consumer and a provider, which is exactly what this block does.
2. **Ecosystem familiarity** — the K8s Service Binding specification uses the same metaphor for the same purpose (projecting secrets into workloads).
3. **Grammatical consistency** — `binds` follows the same verb-at-3rd-person pattern as `consumes` and `exposes`, reinforcing the intentional / action-oriented semantics of the spec.
4. **Schema simplification** — from 6 fields (name, type, resolution, uri, description, keys) to 4 fields (namespace, location, description, keys).
5. **Naming consistency** — across the spec, `namespace` is the identifier used when a field serves as a cross-cutting qualifier in expressions (`$this.{namespace}.{param}`, `\{\{namespace.variable\}\}`). Using `name` for bindings would break this convention.

---

## Proposed Schema

### Bind Object — Fixed Fields

| Field Name | Type | Description |
| --- | --- | --- |
| **namespace** | `string` | **REQUIRED**. Unique identifier used as qualifier in `\{\{namespace.variable\}\}` expressions. MUST match pattern `^[a-zA-Z0-9-]+$`. |
| **description** | `string` | *Recommended*. A meaningful description of the binding's purpose. |
| **location** | `string` (`UriLocation`) | URI identifying the provider. Scheme expresses the resolution strategy. MUST match pattern `^[a-zA-Z][a-zA-Z0-9+.-]*://` (RFC 3986 scheme). When omitted, values are injected by the runtime environment (default). |
| **keys** | `BindingKeys` | **REQUIRED**. Map of variable names (SCREAMING_SNAKE_CASE, `^[A-Z][A-Z0-9_]*$`) to source keys (`IdentifierExtended`, `^[a-zA-Z0-9-_*]+$`). |

### Location URI Schemes

| Scheme | Provider | Example |
| --- | --- | --- |
| `file://` | Local file (dev only) | `file:///path/to/env.json` |
| `vault://` | HashiCorp Vault | `vault://secret/data/naftiko/prod` |
| `github-secrets://` | GitHub Actions Secrets | `github-secrets://naftiko/framework` |
| `k8s-secret://` | Kubernetes Secret | `k8s-secret://namespace/secret-name` |
| `aws-ssm://` | AWS SSM Parameter Store | `aws-ssm:///naftiko/prod/` |
| *(omitted)* | Runtime injection (default) | — |

> The list of supported schemes is open and implementation-dependent. The specification defines the `location` contract, not an exhaustive list of providers.
> 

### Why `namespace` and not `name`

In the Naftiko Specification, `namespace` is consistently used for identifiers that act as **cross-cutting qualifiers** in expressions:

| Object | Field | Used in expressions as |
| --- | --- | --- |
| `consumes` | `namespace` | `{namespace}.{operationId}` in `call` |
| `exposes` (REST, MCP, Skill) | `namespace` | `$this.{namespace}.{param}` |
| `binds` (proposed) | `namespace` | `\{\{namespace.variable\}\}` for disambiguation |

Conversely, `name` is used for **local identifiers** scoped to a parent (resources, operations, steps) that are qualified *by* a namespace when referenced externally.

Since the binding identifier is used as a **qualifier in the expression syntax** (`\{\{namespace.variable\}\}`) and must be **unique at the root level** (same tier as `consumes` and `exposes`), `namespace` is the correct choice.

This also prepares for a **future import mechanism** where bindings could be imported from external files, analogous to how consumes adapters may be reused:

```yaml
# Future potential — binding import
binds:
  - namespace: "notion-env"
    import: "./shared/notion-bindings.yaml"
  - namespace: "ci-secrets"
    location: "github-secrets://naftiko/framework"
    keys:
      GITHUB_TOKEN: "GITHUB_TOKEN"
```

### Placement

`binds` follows the same placement rule as `consumes`:

- When a `capability` object is declared, `binds` is a **child** of `capability` — alongside `exposes` and `consumes`.
- `binds` appears at the **root level only** when the document has no `capability` object (standalone binding declaration).

This keeps the document structure consistent: a capability *binds* external sources, *consumes* APIs, and *exposes* resources — all at the same level.

### Rules

- Each `namespace` MUST be unique across all `binds` entries.
- The `namespace` value MUST NOT collide with any `consumes` or `exposes` namespace to avoid ambiguity in expression resolution.
- The `keys` map MUST contain at least one entry.
- When `location` is present, it MUST be a valid URI matching the `UriLocation` pattern (`^[a-zA-Z][a-zA-Z0-9+.-]*://`).
- When `location` is omitted, the runtime environment is responsible for injecting the values.
- No additional properties are allowed.

### Examples

```yaml
# Development — file-based
binds:
  - namespace: "notion-env"
    description: "Notion API credentials for local development"
    location: "file:///path/to/notion_env.json"
    keys:
      NOTION_TOKEN: "NOTION_INTEGRATION_TOKEN"
      NOTION_DB_ID: "PROJECTS_DATABASE_ID"
```

```yaml
# Production — GitHub Secrets
binds:
  - namespace: "ci-secrets"
    description: "CI/CD secrets managed by GitHub Actions"
    location: "github-secrets://naftiko/framework"
    keys:
      NOTION_TOKEN: "NOTION_TOKEN"
      GITHUB_TOKEN: "GITHUB_TOKEN"
```

```yaml
# Production — HashiCorp Vault
binds:
  - namespace: "vault-secrets"
    description: "Production secrets from HashiCorp Vault"
    location: "vault://secret/data/naftiko/prod"
    keys:
      API_KEY: "API_KEY"
```

```yaml
# Minimal — runtime injection (location omitted)
binds:
  - namespace: "env"
    keys:
      API_KEY: "API_KEY"
```

```yaml
# Disambiguation in expressions
authentication:
  type: bearer
  token: "notion-env.NOTION_TOKEN"
```

---

## Schema Change

> Source file: [`src/main/resources/schemas/naftiko-schema.json`](https://github.com/naftiko/framework/blob/main/src/main/resources/schemas/naftiko-schema.json)
> 

### Root object — property rename

**Before:**

```json
{
  "properties": {
    "externalRefs": {
      "type": "array",
      "items": { "$ref": "#/$defs/ExternalRef" },
      "minItems": 1,
      "description": "List of external references for variable injection and resource linking."
    }
  }
}
```

**After:**

```json
{
  "properties": {
          "binds": {
      "type": "array",
      "items": { "$ref": "#/$defs/Bind" },
      "minItems": 1,
      "description": "List of external sources the capability binds to for variable injection."
  }
}
```

### `Capability` object — add `binds` property

`binds` follows the same placement rule as `consumes`: it is a child of `capability` when a capability is declared, and root-level only when standalone.

**Before** (no `binds` in `Capability`):

```json
{
  "Capability": {
    "type": "object",
    "properties": {
      "exposes": { ... },
      "consumes": { ... }
    }
  }
}
```

**After** (`binds` added alongside `consumes` and `exposes`):

```json
{
  "Capability": {
    "type": "object",
    "properties": {
      "binds": {
        "type": "array",
        "items": { "$ref": "#/$defs/Bind" },
        "minItems": 1,
        "description": "External sources the capability binds to for variable injection."
      },
            "exposes": { ... },
      "consumes": { ... }
    }
  }
}
```

### `$defs/ExternalRef` → `$defs/Bind`

The current schema defines `ExternalRef` as a `oneOf` with two discriminated variants:

- **File-resolved** — `type: "environment"`, `resolution: "file"`, requires `uri`
- **Runtime-resolved** — `type: "variables"`, `resolution: "runtime"`, no `uri`

**Before** (`ExternalRef` — file-resolved variant):

```json
{
  "type": "object",
  "description": "File-resolved external reference",
  "properties": {
    "name":        { "$ref": "#/$defs/IdentifierKebab" },
    "description": { "type": "string" },
    "type":        { "type": "string", "enum": ["environment"] },
    "resolution":  { "type": "string", "const": "file" },
    "uri":         { "type": "string" },
    "keys":        { "$ref": "#/$defs/ExternalRefKeys" }
  },
  "required": ["name", "type", "resolution", "uri", "keys"],
  "additionalProperties": false
}
```

**Before** (`ExternalRef` — runtime-resolved variant):

```json
{
  "type": "object",
  "description": "Runtime-resolved external reference (default)",
  "properties": {
    "name":        { "$ref": "#/$defs/IdentifierKebab" },
    "description": { "type": "string" },
    "type":        { "type": "string", "enum": ["variables"] },
    "resolution":  { "type": "string", "const": "runtime" },
    "keys":        { "$ref": "#/$defs/ExternalRefKeys" }
  },
  "required": ["name", "type", "resolution", "keys"],
  "additionalProperties": false
}
```

**After** (`Bind` — single unified object, no more `oneOf`):

```json
{
  "type": "object",
  "description": "Declares that the capability binds to an external source of variables. Variables declared via 'keys' are injected using mustache-style expressions.",
  "properties": {
    "namespace": {
      "$ref": "#/$defs/IdentifierKebab",
      "description": "Unique identifier for this binding. Used as qualifier in expressions for disambiguation."
    },
    "description": {
      "type": "string",
      "description": "A meaningful description of the binding's purpose. In a world of agents, context is king."
    },
    "location": {
      "$ref": "#/$defs/UriLocation",
      "description": "URI identifying the value provider. The URI scheme expresses the resolution strategy (file://, vault://, github-secrets://, k8s-secret://, etc.). When omitted, values are injected by the runtime environment."
    },
    "keys": {
      "$ref": "#/$defs/BindingKeys",
      "description": "Map of variable names to keys in the resolved source. Each key is the variable name used for injection, each value is the corresponding key in the provider."
    }
  },
  "required": ["namespace", "keys"],
  "additionalProperties": false
}
```

### New `$defs/UriLocation`

A reusable pattern for URI fields that require an explicit scheme. Follows RFC 3986 scheme syntax (`ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )`).

```json
{
  "UriLocation": {
    "type": "string",
    "pattern": "^[a-zA-Z][a-zA-Z0-9+.-]*://",
    "description": "A URI with an explicit scheme identifying the resource provider."
  }
}
```

> `ImportedConsumesHttp.location` currently has no pattern validation. It should also be updated to reference `UriLocation` for consistency — but that change is outside this proposal's scope.
> 

### New `$defs/BindingVariableName`

Variable names (left side of `keys`) use SCREAMING_SNAKE_CASE. This is a deliberate choice: bindings inject values that originate from **external sources** (secrets, environment, vaults), as opposed to parameters declared locally via `inputParameters`. Enforcing SCREAMING_SNAKE_CASE makes injected variables **visually distinct** in the capability document — when a reader encounters `\{\{NOTION_TOKEN\}\}` instead of `\{\{notion_token\}\}`, it is immediately clear that the value comes from an external binding, not from a declared parameter.

```json
{
  "BindingVariableName": {
    "type": "string",
    "pattern": "^[A-Z][A-Z0-9_]*$",
    "description": "Variable name for binding injection (SCREAMING_SNAKE_CASE). Enforced for visual distinction from declared parameters in expressions."
  }
}
```

### `$defs/ExternalRefKeys` → `$defs/BindingKeys`

Rename + tighten the left side: `propertyNames` uses `BindingVariableName` (SCREAMING_SNAKE_CASE). The right side keeps `IdentifierExtended` — source key formats depend on the external provider and cannot be constrained (Vault paths, K8s secret keys like `tls.crt`, etc.).

**Before:**

```json
{
  "ExternalRefKeys": {
    "type": "object",
    "propertyNames": { "$ref": "#/$defs/IdentifierExtended" },
    "additionalProperties": { "$ref": "#/$defs/IdentifierExtended" }
  }
}
```

**After:**

```json
{
  "BindingKeys": {
    "type": "object",
    "description": "Map of variable names (SCREAMING_SNAKE_CASE) to source keys in the provider.",
    "propertyNames": { "$ref": "#/$defs/BindingVariableName" },
    "additionalProperties": { "$ref": "#/$defs/IdentifierExtended" }
  }
}
```

### Summary of `$defs` changes

| Action | Before | After |
| --- | --- | --- |
| **Rename** | `$defs/ExternalRef` | `$defs/Bind` |
| **Rename + tighten** | `$defs/ExternalRefKeys` | `$defs/BindingKeys` (propertyNames → `BindingVariableName`) |
| **Add** | — | `$defs/UriLocation` — reusable URI pattern (RFC 3986 scheme) |
| **Add** | — | `$defs/BindingVariableName` — SCREAMING_SNAKE_CASE for variable names |
| **Remove** | `oneOf` (2 variants: file / runtime) | Single object |
| **Remove field** | `type` (`"environment"` | `"variables"`) | — |
| **Remove field** | `resolution` (`"file"` | `"runtime"`) | — |
| **Remove field** | `uri` | — |
| **Add field** | — | `location` (optional URI) |
| **Rename field** | `name` | `namespace` |

---

## Comparison with K8s Service Binding

| Aspect | K8s ServiceBinding | Naftiko `binds` |
| --- | --- | --- |
| **Role** | Binds a workload to a backing service | The capability binds to a source of variables |
| **Contains values?** | No — points to a Secret/CR | No — points to a `location` |
| **Source identification** | `.spec.service` (apiGroup + kind + name) | `location` (URI scheme) |
| **Key projection** | Files mounted in `$SERVICE_BINDING_ROOT` | `\{\{key\}\}` expressions in the document |
| **Default behavior** | No fallback | `location` omitted = runtime injection |
| **Unique naming** | `metadata.name` | `namespace` field |

**Key similarity**: both are **declarative contracts** — they describe *what* to bind, not *how* to resolve. The runtime handles resolution.

**Key difference**: K8s binds two live objects (workload ↔ service). Naftiko binds a static document to a value source — simpler, more static.

---

## Migration Path

| Before (v0.4 / current v0.5) | After (proposed) |
| --- | --- |
| `externalRefs` | `binds` |
| `name` | `namespace` |
| `type: "environment"` | *(removed)* |
| `resolution: "file"`  • `uri: "file:///..."` | `location: "file:///..."` |
| `resolution: "runtime"` (or omitted) | `location` omitted |

The migration is a **mechanical rename + field collapse** — no semantic change in behavior.

---

## Consequences

### Positive

- Clearer mental model for spec readers and tooling authors
- Reduced schema surface (2 fewer fields)
- `location` URI scheme is extensible to any future provider without schema changes
- Alignment with Cloud Native vocabulary

### Tradeoffs

- Breaking change for any existing tooling consuming `externalRefs` (mitigated by the early stage of the spec)
- URI schemes are not standardized — implementations must document supported schemes

### Follow-up work

- Update JSON Schema (`naftiko-schema.json`)
- Migrate `ImportedConsumesHttp.location` to `$ref: UriLocation` (separate initiative)
- Update JSON Structure definition
- Update Spectral / Vacuum validation rules
- Update all spec examples (§4.1 through §4.6)
- Update framework runtime to support `binds` key
- Update Schema Viewer

## Future: Import Bindings

<aside>
🔮

**Post-alpha feature.** This section specifies the import mechanism for bindings. It will not be implemented as part of the alpha release — the placement of `binds` alongside `consumes` makes this addition trivial when the time comes.

</aside>

### Rationale

Since `binds` follows the same placement rule as `consumes` (root-level when standalone, child of `capability` otherwise), the import mechanism can reuse the exact same pattern already defined for `consumes`.

A standalone `binds` document declares reusable bindings that can be imported by any capability, avoiding duplication of credentials and provider configuration across multiple Naftiko files.

### Syntax

```yaml
# Capability importing shared bindings
capability:
  binds:
    - namespace: "shared-secrets"
      import: "./shared/secrets-bindings.yaml"
    - namespace: "prod-secrets"
      import: "./shared/secrets-bindings.yaml"
      as: "prod-secrets"
    - namespace: "local-dev"
      location: "file:///path/to/dev.json"
      keys:
        DEBUG_TOKEN: "DEBUG_TOKEN"
  consumes:
    - namespace: "notion-api"
      # ...
  exposes:
    - namespace: "my-skill"
      # ...
```

```yaml
# shared/secrets-bindings.yaml — standalone binding file
binds:
  - namespace: "shared-secrets"
    description: "Production secrets shared across all capabilities"
    location: "vault://secret/data/naftiko/prod"
    keys:
      API_KEY: "API_KEY"
      NOTION_TOKEN: "NOTION_TOKEN"
```

### Rules

- The `import` field is **mutually exclusive** with `location`, `description`, and `keys` — an imported binding inherits all fields from the source file.
- By default, the `namespace` in the importing document **MUST match** the namespace declared in the imported file.
- When a namespace collision would occur (e.g. importing the same file twice, or two files declaring the same namespace), use `as` to alias the imported binding to a different namespace. The `as` value becomes the effective namespace used in expressions.
- Imported bindings follow the same uniqueness and collision rules as inline bindings (after alias resolution).
- The imported file MUST be a valid standalone Naftiko document containing only `binds`.

### Schema addition (`Bind` object)

The `import` and `as` fields are added to the `Bind` definition:

```json
{
  "import": {
    "$ref": "#/$defs/UriLocation",
    "description": "Relative or absolute path to a standalone bindings file. Mutually exclusive with location, description, and keys."
  },
  "as": {
    "$ref": "#/$defs/IdentifierKebab",
    "description": "Namespace alias for the imported binding. Overrides the original namespace to avoid collisions. Only valid when 'import' is present."
  }
}
```

Validation uses a `oneOf`:

- **Inline binding**: requires `namespace` + `keys`, optional `location` and `description`, no `import`, no `as`
- **Imported binding**: requires `namespace` + `import`, optional `as`, no other fields

---

## More Information

- [K8s Service Binding Specification](https://github.com/servicebinding/spec)
- [Naftiko Specification v0.5](https://naftiko.github.io/schema-viewer/)
- Related discussion: inline comments on §3.19 of the Naftiko Specification