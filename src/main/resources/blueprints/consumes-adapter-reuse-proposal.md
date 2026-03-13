### Global import of consumed HTTP adapters with unified JSON Schema

**Status** : Draft

**Version** : 0.6

**Date** : March 11, 2026

**Author** : @Thomas Eskenazi

**Parent proposal** : [External Capability Reference Proposal](https://www.notion.so/External-Capability-Reference-Proposal-7575c829c70a4443ac1d5afc0cd58781?pvs=21) (Stalled — out of scope for alpha)

---

## Table of Contents

1. Executive Summary
2. Standalone Consumes File Format
3. Import Mechanism
4. Schema Architecture
5. Unified Schema — Full JSON Schema Source
6. DX & Tooling
7. Validation Rules (Spectral)
8. Design Decisions & Rationale

---

## 1. Executive Summary

### What This Proposes

Two interconnected changes to the Naftiko specification:

1. **`consumes` becomes a standalone object** — with its own file format (`.yml`), its own JSON Schema definition, and the ability to be validated independently.
2. **Inline import mechanism** — a consumed resource from an external consumes file can be imported directly at point of use within a `consumes` block, using `import` and `as` fields. Operations are explicitly declared (imported or local).

### What This Does NOT Do

- **No cherry-picking** (yet) — you import an entire namespace with all its resources and operations. Selective import may be added as a future iteration if use cases emerge.
- **No override** — imported adapters are used as-is (address, authentication, resource definitions). If a modified version is needed, define a local adapter instead.
- **No transitive import** — the source consumes file must be self-sufficient.

### Schema Architecture Change

The consumes types and the capability types are unified into a **single JSON Schema file** (`naftiko-schema.json`). The root is a **flat object** with a `oneOf` on `required` to discriminate between a capability document and a standalone consumes document — no wrapper types, no cross-file references, no compilation step. All `$defs` live in one namespace.

```jsx
schemas/
└── naftiko-schema.json     ← single schema with oneOf at root
```

---

## 2. Standalone Consumes File Format

A new top-level YAML file format for standalone consumes definitions:

```yaml
# shared-notion-api.yml
naftiko: "0.5"

info:
  label: "Notion API Consumes block"
  description: "Shared consumes block for Notion REST API v1"

consumes:
  - namespace: "notion"
    type: "http"
    address: "https://api.notion.com"
    authentication:
      type: "bearer"
      token: "$secrets.notion_token"
    resources:
      - name: "databases"
        path: "/v1/databases"
        operations:
          - method: "POST"
            name: "query-database"
            path: "/{databaseId}/query"
            description: "Query a Notion database with filters and sorts"
            requestBody:
              type: "json"
            outputParameters:
              - name: "results"
                type: "array"
          - method: "GET"
            name: "get-database"
            path: "/{databaseId}"
      - name: "pages"
        path: "/v1/pages"
        operations:
          - method: "GET"
            name: "get-page"
            path: "/{pageId}"
          - method: "POST"
            name: "create-page"
            requestBody:
              type: "json"
```

### Key points

- Same `naftiko` version header as capabilities
- `info` block for metadata (label, description)
- `consumes` array at the root — **identical structure** as inside a capability today
- No `capability` block, no `exposes` — purely a consumes definition

### Detection and disambiguation

Both capabilities and standalone consumes use `.yml` files. Disambiguation is **content-based**:

- Has `capability` key → capability file
- Has `consumes` key at root without `capability` → standalone consumes file

---

## 3. Import Mechanism

### 3.1 Core concept

A consumed adapter within a capability's `consumes` block can be **globally imported** from an external standalone consumes file. The discriminant is the presence of the `location` field on the `consumes[]` entry itself.

```yaml
capability:
  consumes:
    # Local adapter (no location → defined here)
    - namespace: "my-api"
      type: "http"
      address: "http://localhost:8080"
      resources:
        - name: "hello"
          path: "/hello"
          operations:
            - method: "GET"
              name: "fetch"

    # Imported adapter (location present → from external file)
    - location: "./shared-notion-api.yml"
      import: "notion"
```

### 3.2 Consumes-level import

| **Field** | **Type** | **Required** | **Description** |
| --- | --- | --- | --- |
| `location` | string (URI) | **Yes** | Path to the source consumes file. Relative paths resolved from the importing file's directory. |
| `import` | string | **Yes** | Namespace identifier in the source consumes file |
| `as` | string | No | Local alias for the imported namespace. If omitted, the source namespace is used. |

### 3.3 Runtime context: address and authentication

When an adapter is imported globally, its runtime context (`address`, `authentication`) comes from the **source file** — not from the importing capability. The import brings the complete adapter definition including its runtime configuration.

This is intentional for the alpha:

- Global import is a convenience for **reusing an entire adapter** — the source file fully defines the contract and the runtime
- If different address or auth is needed, define a local adapter instead
- Address/auth override on global imports may be added as a future evolution if use cases emerge

### 3.5 Examples

#### Source files

Two standalone consumes adapters with **identical namespace, resource names, and operation names** — one in English, one in French:

```yaml
# hello-world-en-consumes.yml
naftiko: "0.5"
info:
  label: "Hello World Adapter (EN)"
consumes:
  - namespace: "hello-world"
    type: "http"
    address: "http://localhost:8080"
    resources:
      - name: "hello"
        path: "/hello"
        operations:
          - method: "GET"
            name: "say-hello"
            outputParameters:
              - type: "string"
                const: "Hello, World!"
          - method: "POST"
            name: "say-hello-custom"
            requestBody:
              type: "json"
```

```yaml
# hello-world-fr-consumes.yml
naftiko: "0.5"
info:
  label: "Hello World Adapter (FR)"
consumes:
  - namespace: "hello-world"
    type: "http"
    address: "http://localhost:8081"
    resources:
      - name: "hello"
        path: "/hello"
        operations:
          - method: "GET"
            name: "say-hello"
            outputParameters:
              - type: "string"
                const: "Bonjour, le monde !"
```

#### Example A — Collision management with `as`

Two sources share the same namespace `"hello-world"` → `as` disambiguates:

```yaml
# my-multilang-capability.yml
naftiko: "0.5"
capability:
  consumes:
    - location: "./hello-world-en-consumes.yml"
      import: "hello-world"
      as: "hello-world-en"
    - location: "./hello-world-fr-consumes.yml"
      import: "hello-world"
      as: "hello-world-fr"

  exposes:
    - type: "api"
      port: 8082
      namespace: "proxy"
      resources:
        - path: "/greet/en"
          operations:
            - method: "GET"
              call: "hello-world-en.say-hello"
        - path: "/greet/fr"
          operations:
            - method: "GET"
              call: "hello-world-fr.say-hello"
```

> **Why `as` is needed here**: Both source files define namespace `"hello-world"`. Importing both without aliasing would create a namespace collision. `as: "hello-world-en"` / `as: "hello-world-fr"` gives each import a unique local namespace so that `call` references resolve unambiguously.
> 

### 3.6 Constraints

- **No override** — an imported adapter is used exactly as defined in the source (address, authentication, resources, operations). If you need a modified version, define a local adapter instead.
- **No cherry-picking** — you import an entire namespace, not individual resources or operations. Selective import may be considered as a future iteration if use cases emerge.
- **No transitive import** — the source consumes file must be self-sufficient (no imports within imports).

> 📝 Cherry-picking and override are recognized as potentially valuable features. They are deliberately out of scope for the alpha to keep the import mechanism simple and predictable. If these needs emerge, they will be treated as **incremental evolutions** of the current global import model.
> 

---

## 4. Schema Architecture

### 4.1 Unified schema with `oneOf` at root

| **Before (1 file)** | **After (1 file, unified)** |
| --- | --- |
| `capability-schema.json` — capability types only | `naftiko-schema.json` — unified schema with `oneOf` at root, all `$defs` in one namespace |

The schema root is a **flat object** — all properties are declared directly at root, with a `oneOf` on `required` to discriminate between document types. No wrapper types (`CapabilityDocument`, `ConsumesDocument`):

```json
{
  "$id": "https://naftiko.io/schemas/v0.5/naftiko.json",
  "type": "object",
  "properties": {
    "naftiko":    { "type": "string", "const": "0.5" },
    "info":       { "$ref": "#/$defs/Info" },
    "capability": { "$ref": "#/$defs/Capability" },
    "consumes": {
      "type": "array",
      "items": {
        "oneOf": [
          { "$ref": "#/$defs/ConsumesHttp" },
          { "$ref": "#/$defs/ImportedConsumesHttp" }
        ]
      },
      "minItems": 1
    },
    "externalRefs": { "type": "array", "items": { "$ref": "#/$defs/ExternalRef" } }
  },
  "oneOf": [
    { "required": ["naftiko", "capability"] },
    { "required": ["naftiko", "consumes"] }
  ],
  "additionalProperties": false
}
```

Discrimination via `required`:

- `{ "required": ["naftiko", "capability"] }` → capability document
- `{ "required": ["naftiko", "consumes"] }` → standalone consumes document

`info` is **optional in both branches** — may be omitted in any document type.

### 4.2 New types

- `ImportedConsumesHttp` — schema definition for the global import mechanism (discriminated by the `location` field)

---

## 5. Schema Changes

This section describes only the **changes** to the existing `capability-schema.json` (renamed to `naftiko-schema.json`). All existing `$defs` (`ConsumesHttp`, `ConsumedHttpResource`, `ConsumedHttpOperation`, `Authentication`, etc.) remain unchanged.

### 5.1 Root — add `oneOf` discriminator

The schema root becomes a **flat object** with all properties declared directly and a `oneOf` on `required` for discrimination — no wrapper types:

```json
{
  "$id": "https://naftiko.io/schemas/v0.5/naftiko.json",
  "type": "object",
  "properties": {
    "naftiko":    { "type": "string", "const": "0.5" },
    "info":       { "$ref": "#/$defs/Info" },
    "capability": { "$ref": "#/$defs/Capability" },
    "consumes": {
      "type": "array",
      "items": {
        "oneOf": [
          { "$ref": "#/$defs/ConsumesHttp" },
          { "$ref": "#/$defs/ImportedConsumesHttp" }
        ]
      },
      "minItems": 1
    },
    "externalRefs": { "type": "array", "items": { "$ref": "#/$defs/ExternalRef" } }
  },
  "oneOf": [
    { "required": ["naftiko", "capability"] },
    { "required": ["naftiko", "consumes"] }
  ],
  "additionalProperties": false
}
```

### 5.2 Removed — `ConsumesDocument` and `ConsumesInfo`

These wrapper types are no longer needed. Discrimination between capability and standalone consumes documents is handled by `oneOf` on `required` at the root — no separate `$def` per document type.

The existing `Info` `$def` is reused for the optional `info` property in both document types.

### 5.4 New `$def` — `ImportedConsumesHttp`

```json
"ImportedConsumesHttp": {
  "type": "object",
  "description": "A globally imported consumed HTTP adapter. Discriminant: 'location' field present.",
  "properties": {
    "location": {
      "type": "string",
      "description": "URI to the source consumes file"
    },
    "import": {
      "$ref": "#/$defs/IdentifierKebab",
      "description": "Namespace in the source consumes file"
    },
    "as": {
      "$ref": "#/$defs/IdentifierKebab",
      "description": "Optional local alias for the imported namespace"
    }
  },
  "required": ["location", "import"],
  "additionalProperties": false
}
```

### 5.5 Modified `$def` — `Capability`

The `consumes` items change from a direct `ConsumesHttp` reference to a `oneOf` accepting both local and imported adapters:

```json
"Capability": {
  "properties": {
    "consumes": {
      "type": "array",
      "items": {
        "oneOf": [
          { "$ref": "#/$defs/ConsumesHttp" },
          { "$ref": "#/$defs/ImportedConsumesHttp" }
        ]
      }
    }
  }
}
```

> 📝 All other existing `$defs` (`ConsumesHttp`, `ConsumedHttpResource`, `ConsumedHttpOperation`, `Authentication`, `RequestBody`, identifiers, etc.) are **unchanged** and not repeated here.
> 

---

## 6. DX & Tooling

> 📝 This section describes **tooling and DX considerations** — IDE extensions and local development workflows. They are **not part of the specification itself**.
> 

### 6.1 VS Code configuration

```json
// .vscode/settings.json
{
  "yaml.schemas": {
    "schemas/naftiko-schema.json": ["**/*.capability.yml", "**/*.consumes.yml"]
  }
}
```

> ⚠️ File naming convention (`*.capability.yml` / `*.consumes.yml`) is a **DX convenience** for schema association — not a requirement. Content-based detection remains the authoritative mechanism.
> 

### 6.2 Java validation

The unified schema is validated at runtime by the framework using **networknt/json-schema-validator**. No compilation step, no build tool — the schema is a standard JSON Schema file loaded directly.

---

## 7. Validation Rules (Spectral)

JSON Schema validates the **structure** of a document — types, required fields, allowed values, patterns. But certain constraints are **semantic** and go beyond what a schema can express:

- **Contextual requirements** — `name` must be required in standalone consumes files but stays optional in inline capabilities (backward compatibility). A schema has one `required` list — it cannot vary by context.
- **Cross-field consistency** — `import` references a namespace that must exist in the source file. These are **referential integrity** checks across files, not structural checks.
- **Uniqueness within scope** — operation names unique within a resource, resource names unique within a namespace.
- **Business rules** — `as` aliases should not collide within a capability's `consumes` block.

Spectral fills this gap: it runs **custom linting rules** on the parsed YAML/JSON, with full access to the document tree and the ability to resolve cross-file references.

### 7.1 Standalone consumes files

| Rule | Severity | Description |
| --- | --- | --- |
| `consumes-resource-name-required` | error | Resources in standalone consumes files **must** have a `name` (needed for import references) |
| `consumes-operation-name-required` | error | Operations in standalone consumes files **must** have a `name` |
| `consumes-unique-resource-name` | error | Resource `name` must be unique within a namespace |
| `consumes-unique-operation-name` | error | Operation `name` must be unique within a resource |

### 7.2 Import mechanism

| Rule | Severity | Description |
| --- | --- | --- |
| `import-ref-exists` | error | The `import` reference (namespace) must resolve to an existing namespace in the source consumes file |
| `import-unique-alias` | warning | `as` aliases must be unique within the capability's `consumes[]` array |

### 7.3 Capabilities (when using inline consumes)

| Rule | Severity | Description |
| --- | --- | --- |
| `capability-resource-name-when-reused` | warning | Resources referenced by `call:` patterns should have a `name` |

> 📝 `name` is enforced by Spectral rules rather than schema `required` because existing capabilities where resources don't need names should remain valid. The schema stays permissive; Spectral layers on stricter rules contextually.
> 

---

## 8. Design Decisions & Rationale

### Decision 1 : Namespace = unit of import (no cherry-picking for alpha)

**Context** : The import granularity could be at the operation, resource, or namespace level.

**Choice** : Import at the **namespace** level. The entire consumed adapter (all resources and all operations) is imported as a unit.

**Rationale** :

- A namespace is a **cohesive unit** — address, authentication, resources, and operations form a consistent contract
- Finer-grained import (resource-level or operation-level cherry-picking) introduces resolution complexity that isn't needed for the alpha
- Simple mental model: "I import this adapter, I get everything it defines"

> 📝 Cherry-picking (selecting individual resources or operations) may be added as a **future iteration** if use cases emerge. The current global import model is designed to support this evolution without breaking changes.
> 

### Decision 2 : No override — import as-is or define locally

**Choice** : Imported adapters are used exactly as defined in the source file. No mechanism to override address, authentication, resources, or operations on import.

**Rationale** :

- **Simple and explicit** — import or define locally, no middle ground
- Override introduces complex questions (partial override? merge semantics? precedence?) for marginal gains
- If you need a modified version, define a local adapter instead — slightly more verbose but unambiguous
- Override on global imports may be added as a future evolution if use cases emerge

### Decision 3 : Address/auth from source file

**Context** : When a namespace is imported, the runtime context (address, authentication) could come from the source file or from the importing capability.

**Choice** : Address and authentication come from the **source file**.

**Rationale** :

- Global import is a convenience for **reusing an entire adapter as-is** — the source file fully defines the contract and the runtime
- The source file is the single source of truth for how to reach the service
- If different address or auth is needed, define a local adapter instead
- This keeps the import mechanism simple — no merge of runtime configuration between files

### Decision 4 : Unified JSON Schema — single file, no compilation

**Context** : The Naftiko specification needs a schema to validate YAML capability and consumes files.

**Choice** : A single `naftiko-schema.json` file authored and maintained directly as standard JSON Schema. `oneOf` at the root discriminates between capability and consumes documents. All `$defs` live in one namespace.

**Rationale** :

- **No build step** — the schema is ready to use as-is, no compilation required
- **Standard tooling** — any JSON Schema validator (networknt in Java, AJV in JS) works out-of-the-box
- **VS Code friendly** — YAML extension resolves everything from one local file
- **Simplicity** — one file, one namespace, zero cross-file references

### Decision 5 : Content-based file disambiguation

**Choice** : Capability vs standalone consumes is detected by content (`capability` key present or not), not by file extension.

**Rationale** :

- No new naming constraints on `.yml` files
- Simple to implement — check for the `capability` top-level key
- Consistent with how YAML/JSON tools typically handle polymorphic schemas
- File naming conventions (`*.capability.yml` / `*.consumes.yml`) are optional DX sugar for VS Code schema association