# Guide — Importing

## Table of Contents

- [Overview](#overview)
- [The Import Directive](#the-import-directive)
- [Standalone Source Files](#standalone-source-files)
  - [Consumes](#consumes)
  - [Exposes](#exposes)
  - [Aggregates](#aggregates)
  - [Binds](#binds)
- [How Resolution Works](#how-resolution-works)
- [Cross-File References](#cross-file-references)
- [Alias Disambiguation](#alias-disambiguation)
- [Validation & Linting](#validation--linting)
- [Error Catalog](#error-catalog)
- [Worked Example — Full Four-Section Import](#worked-example--full-four-section-import)
- [FAQ](#faq)

---

## Overview

Ikanos supports **importing** entries from external source files into a capability's `consumes`, `exposes`, `aggregates`, and `binds` sections. This enables:

- **Modularization** — split a large capability into smaller, focused files
- **Reuse** — share adapter definitions, domain functions, or binding catalogs across multiple capabilities
- **Separation of concerns** — review and lint each section independently

The import mechanism uses a single, uniform directive — `from` / `import` / `as` — that works identically across all four sections.

> **Key principle:** A capability is the only assembly point. Source files are **leaves** — they cannot import other files. This keeps the model simple, predictable, and cycle-free.

---

## The Import Directive

Every imported entry uses the same three fields:

```yaml
- from: <path to source file>
  import: <namespace of the entry in that file>
  as: <optional alias>
  description: <optional documentation>
```

| Field | Required | Description |
|---|---|---|
| `from` | yes | Relative or absolute path to a source file. Relative paths resolve against the directory of the importing file. |
| `import` | yes | The `namespace` of the entry to take from the source file. |
| `as` | no | Renames the imported entry's namespace in the importing capability. Useful when two sources use the same namespace. |
| `description` | no | Free-form documentation for the import. |

### Example

```yaml
capability:
  consumes:
    - from: "./shared/registry.consumes.yml"
      import: registry
    - from: "./shared/legacy.consumes.yml"
      import: legacy
      as: legacy-dockyard
      description: "Legacy Dockyard adapter, imported with alias"
```

---

## Standalone Source Files

Each section can live in its own YAML file. The file has a root-level `ikanos` version and the section array at the root level (not nested under `capability`).

### Consumes

**Convention:** `*.consumes.yml`

```yaml
ikanos: "1.0.0-alpha3"
consumes:
  - type: "http"
    namespace: "registry"
    baseUri: "https://api.registry.example.com"
    resources:
      - name: ships
        path: "/ships"
        operations:
          - name: list-ships
            method: GET
```

### Exposes

**Convention:** `*.exposes.yml`

```yaml
ikanos: "1.0.0-alpha3"
exposes:
  - type: "rest"
    namespace: "weather-rest"
    address: "localhost"
    port: 8080
    resources:
      - name: forecasts
        path: "/forecasts"
        operations:
          - name: get-forecast
            method: GET
            call: weather-api.get-forecast
```

### Aggregates

**Convention:** `*.aggregates.yml`

```yaml
ikanos: "1.0.0-alpha3"
aggregates:
  - namespace: "forecast"
    label: "Forecast Domain"
    functions:
      - name: "get-forecast"
        label: "Get Forecast"
        description: "Retrieves weather forecast for a location"
        call: "weather-api.get-forecast"
```

### Binds

**Convention:** `*.binds.yml`

```yaml
ikanos: "1.0.0-alpha3"
binds:
  - namespace: "api-secrets"
    description: "API credentials"
    location: "./secrets.env"
    keys:
      required:
        - API_KEY
```

> **Note:** `BindingSpec.location` is the **runtime variable source** (where to fetch secrets). It is not related to the import directive's `from` field, which is the **parse-time source path**.

---

## How Resolution Works

The engine resolves imports **eagerly** at capability load time, before any adapter or aggregate wiring. After resolution, the rest of the engine sees only fully-materialized inline entries — no import directive escapes the constructor.

### Resolution pass order

1. **consumes** — all consumes imports are loaded
2. **aggregates** — all aggregates imports are loaded
3. **exposes** — all exposes imports are loaded
4. **binds** — all binds imports are loaded
5. **cross-file references** — `call` and `ref` targets are validated against the now-complete section lists

### What happens during resolution

For each import entry:

1. Read the `from` and `import` fields
2. Resolve the file path relative to the importing file's directory
3. Load and parse the source file (cached across sections)
4. Find the matching `namespace` in the source file's section array
5. Deep-copy the entry (to prevent cross-capability mutation)
6. Apply the `as` alias if present (replace the entry's namespace)
7. Replace the import directive with the materialized inline entry

### Caching

A shared `SourceFileLoader` caches parsed source files across the four section resolvers. If two sections import from the same file, it is parsed only once.

---

## Cross-File References

Imported entries may contain `call` and `ref` references. These references are resolved against the **importing capability's** sections, not the source file.

For example, an imported exposes adapter with `call: weather-api.get-forecast` will look for a `weather-api` namespace in the importing capability's `consumes` — not in the source file. This means:

- The source file declares the **contract shape** (what tools exist, what calls they make)
- The importing capability provides the **wiring** (the consumed APIs those calls target)

This separation is intentional: the same exposed adapter can be reused across capabilities that wire it to different consumed APIs.

---

## Alias Disambiguation

When two source files export entries with the same namespace, use `as` to give each a unique name in the importing capability:

```yaml
capability:
  consumes:
    - from: "./adapters/weather-us.consumes.yml"
      import: weather-api
      as: weather-us
    - from: "./adapters/weather-eu.consumes.yml"
      import: weather-api
      as: weather-eu
```

Without aliases, the two entries would collide and the engine would throw an `ImportException` with a "Duplicate namespace" message.

---

## Validation & Linting

Import directives are validated at three levels:

| Level | Tool | What it checks |
|---|---|---|
| **JSON Schema** | `ikanos-schema.json` | Structural validity of import entries; root-level `oneOf` prevents imports in standalone files |
| **Spectral Rules** | `ikanos-rules.yml` | Directive completeness, alias uniqueness, leaf enforcement, namespace requirements |
| **Engine Resolver** | `ImportResolver` | File existence, namespace lookup, cross-file reference integrity, deep-copy isolation |

### Key Spectral rules for imports

| Rule | Severity | What it checks |
|---|---|---|
| `ikanos-import-from-required` | error | `from` entries must also have `import` |
| `ikanos-import-import-required` | error | `import` entries (without `type`) must also have `from` |
| `ikanos-import-unique-alias` | error | Effective namespaces are unique per section |
| `ikanos-standalone-no-imports` | error | Standalone files must not contain import entries |
| `ikanos-exposes-namespace-required` | error | Source exposes entries must have `namespace` |
| `ikanos-aggregates-namespace-required` | error | Source aggregates entries must have `namespace` |

See the [Specification - Rules](https://github.com/naftiko/ikanos/wiki/Specification-%E2%80%90-Rules) page for the full rule reference.

---

## Error Catalog

All import errors share a uniform shape: `[sectionName] message`.

| Error message | Cause | Fix |
|---|---|---|
| `[consumes] Import 'from' is required` | `from` field is missing or empty | Add the `from` field pointing to the source file |
| `[consumes] Import 'import' is required` | `import` field is missing or empty | Add the `import` field with the namespace to import |
| `[consumes] Import source file not found: /path/to/file` | The `from` path does not resolve to an existing file | Check the relative path and file name |
| `[consumes] Failed to load source file: /path` | The source file is malformed YAML | Fix the YAML syntax in the source file |
| `[consumes] No consumes entries found in source file: /path` | The source file has no entries in the expected section | Verify the file has a root-level section array |
| `[consumes] Namespace 'foo' not found in source consumes file: /path` | The requested namespace does not exist in the source file | Check the spelling and verify the source file's namespaces |
| `[consumes] Duplicate namespace 'foo' after import resolution` | Two entries resolve to the same namespace | Use `as` aliases to disambiguate |

Replace `[consumes]` with any section name (`[exposes]`, `[aggregates]`, `[binds]`) — the format is identical.

---

## Worked Example — Full Four-Section Import

A capability that imports all four sections from shared files:

```yaml
ikanos: "1.0.0-alpha3"

binds:
  - from: "./shared/secrets.binds.yml"
    import: api-secrets

capability:
  consumes:
    - from: "./shared/registry.consumes.yml"
      import: registry
    - from: "./shared/legacy.consumes.yml"
      import: legacy

  aggregates:
    - from: "./shared/fleet.aggregates.yml"
      import: fleet-domain

  exposes:
    - from: "./shared/fleet.exposes.yml"
      import: fleet-rest
    - type: mcp
      namespace: fleet-mcp
      port: 3001
      tools:
        - name: list-ships
          ref: fleet-domain.list-ships
```

This capability:
- Imports two consumed adapters (`registry`, `legacy`)
- Imports a shared aggregate domain (`fleet-domain`)
- Imports a REST adapter and declares an inline MCP adapter
- Imports a binding catalog for secrets

After resolution, all entries are materialized inline — the engine sees no import directives.

---

## FAQ

### Why was `location` renamed to `from` for consumes imports?

The `binds` section already uses `location` to mean "runtime variable source" (e.g., `./secrets.env`, `vault://app/prod`). Reusing `location` for "import source file path" would create semantic ambiguity. `from` is unambiguous, short, and idiomatic (inspired by ES module syntax: `import x from './foo'`).

### Can standalone files import other files?

No. Standalone files are **leaves** in the import DAG. Only capability documents may import. This is enforced by both the JSON Schema (`oneOf`) and the Spectral rule `ikanos-standalone-no-imports`. See the [blueprint §14](https://github.com/naftiko/blueprints/blob/main/unified-import-mechanism.md#14-design-decisions--rationale) for the rationale.

### Can I import individual functions instead of a whole aggregate?

Not in 1.0. You import an entire namespaced entry. Selective (field-level) import is a candidate for a future evolution. If you need only a subset of functions, declare a local aggregate.
