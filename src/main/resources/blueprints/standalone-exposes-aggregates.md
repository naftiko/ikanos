### Standalone exposes and aggregates files

**Status** : Proposal

**Version** : 1.0.0-alpha1

**Date** : April 4, 2026

**Related** : [consumes-adapter-reuse.md](consumes-adapter-reuse.md) — the consumes import mechanism this proposal extends

---

## Table of Contents

1. Executive Summary
2. Motivation
3. Standalone File Formats
4. Import Mechanism — Exposes
5. Import Mechanism — Aggregates
6. Transitive Imports & Cycle Detection
7. Schema Changes
8. Java Implementation
9. Validation Rules (Spectral)
10. Design Decisions & Rationale
11. Migration & Backward Compatibility
12. Future Evolutions

---

## 1. Executive Summary

### What This Proposes

Three interconnected changes to the Naftiko specification, extending the import pattern already established by [consumes-adapter-reuse](consumes-adapter-reuse.md):

1. **`exposes` becomes a standalone document** — with its own file format (`.yml`), validated against the unified `naftiko-schema.json`, editable, lintable, and reviewable independently of any capability.
2. **`aggregates` becomes a standalone document** — same treatment: domain aggregates (functions, semantics, parameter shapes) can live in their own file.
3. **Import mechanism** — a capability can import exposed adapters and aggregates from external files using `location`, `import`, and optional `as` fields.

The first goal is **modularization** — extracting any of the three capability sections (`consumes`, `exposes`, `aggregates`) into its own file so that each concern can be authored, reviewed, and validated in isolation. The second goal is **contract reuse** — when multiple capabilities must expose the same predefined REST or MCP interface or share the same domain functions, they import from a single source of truth instead of duplicating.

### What This Does NOT Do

- **No cherry-picking** — you import an entire exposed adapter or an entire aggregate (all functions). Selective import may be added as a future iteration.
- **No override** — imported objects are used as-is. If a modified version is needed, define a local one instead.
- **Optional transitive imports** — standalone aggregates files *can* import their own consumes dependencies. This is entirely optional — standalone files without transitive imports remain valid and resolve their references against the importing document's context, exactly as in the base proposal. Standalone exposes files do not support transitive imports — use a full capability when you need to wire exposes with aggregates and consumes together. Cycle detection prevents infinite resolution chains.
- **Layered reference resolution** — when a standalone aggregates file declares transitive consumes imports, `call` references resolve first against the file's own imports, then fall through to the importing document's context. When no transitive imports are present, all references fall through — maintaining full backward compatibility.

### How the three document types compose

| Document type | Contains | Can import | References |
|---|---|---|---|
| Standalone **consumes** | HTTP client adapters (namespace, baseUri, resources, operations) | Nothing — leaf of the DAG | Nothing — self-contained |
| Standalone **aggregates** | Domain functions (name, semantics, call, steps, parameters) | Optionally: standalone **consumes** files | `call` → consumed namespaces (resolved from own imports if present, otherwise from importing document) |
| Standalone **exposes** | Server adapters (REST resources, MCP tools, Skill skills) | Nothing — references resolve in the importing document | `call` → consumed namespaces, `ref` → aggregate functions (resolved in importing document) |

A fully modularized capability imports all three side by side and wires them together:

```yaml
naftiko: "1.0.0-alpha1"
capability:
  consumes:
    - location: "./weather-api.yml"
      import: "weather-api"
  aggregates:
    - location: "./forecast-domain.yml"
      import: "forecast"
  exposes:
    - location: "./weather-mcp.yml"
      import: "weather-mcp"
```

This **side-by-side** pattern is the default — each standalone file is a simple, single-concern document, and the capability is the wiring point. It works for any project size and requires no transitive imports.

**Optionally**, standalone aggregates files can use **transitive imports** to declare their own consumes dependencies. A standalone aggregates file can import the consumes it needs, making it a self-contained domain package:

```yaml
# forecast-domain.yml — self-contained aggregates file with transitive consumes
naftiko: "1.0.0-alpha1"

consumes:
  - location: "./weather-api.yml"
    import: "weather-api"

aggregates:
  - label: "Forecast"
    namespace: "forecast"
    functions:
      - name: "get-forecast"
        call: "weather-api.get-forecast"
        with:
          location: "location"
        inputParameters:
          - name: "location"
            type: "string"
        outputParameters:
          - type: "object"
```

When the full dependency chain needs to be packaged together (exposes + aggregates + consumes), use a **capability** — that is the document type designed for wiring all three sections:

```yaml
# weather-capability.yml — wires exposes, aggregates, and consumes together
naftiko: "1.0.0-alpha1"
capability:
  consumes:
    - location: "./weather-api.yml"
      import: "weather-api"
  aggregates:
    - location: "./forecast-domain.yml"
      import: "forecast"
  exposes:
    - location: "./weather-mcp.yml"
      import: "weather-mcp"
```

Both patterns are first-class. The side-by-side pattern keeps standalone files simple and the capability explicit; the transitive pattern on aggregates reduces consumes wiring boilerplate when the same domain functions are reused across many capabilities.

---

## 2. Motivation

### 2.1 Modularization — separation of concerns

As capabilities grow, the three sections — `consumes`, `aggregates`, `exposes` — can each become large. Keeping them all inline creates two problems:

- **Cognitive load** — a single YAML file mixes adapter contract concerns (paths, tools, parameters), domain logic (aggregate functions, semantics, orchestration steps), and external API wiring (baseUri, authentication, resources). Reviewers must context-switch across all three.
- **Independent validation** — teams want to lint and review each concern on its own, without loading the full capability. Today that requires manually extracting the block.

Standalone files let each concern live in its own document — authored, version-controlled, and validated independently, then wired into a capability via import. The consumes import mechanism already proved this pattern; this proposal extends it to exposes and aggregates.

### 2.2 Contract reuse — predefined interfaces and shared domain logic

Once exposes and aggregates are standalone documents, a natural second benefit emerges: **reuse**.

**Exposes reuse** — organizations often define a standard REST or MCP interface that multiple services must implement:

- A **compliance REST API** — every microservice must expose `/health`, `/info`, and `/metrics` with the same schema
- A **shared MCP tool catalog** — an AI platform team publishes a set of tools (search, summarize, classify) that multiple capabilities must expose identically
- A **partner integration contract** — a predefined REST interface that several backend capabilities must expose for external consumers

**Aggregates reuse** — domain functions that embody business logic can be shared:

- A **forecast domain** — the same aggregate functions (get-forecast, get-alerts) are used by capabilities that expose them via different adapters (REST for partners, MCP for AI agents)
- A **shared orchestration pattern** — a multi-step function (validate → enrich → persist) is defined once and referenced by capabilities across teams
- A **domain function library** — a team publishes reusable aggregate functions that other teams import instead of reimplementing

### 2.3 Transitive imports — optional layered composition

As capabilities mature, they naturally settle into a layered architecture: **consumes** at the bottom (raw HTTP wiring), **aggregates** in the middle (domain functions), **exposes** at the top (adapters for consumers). Each layer depends on the one below it — aggregates call consumed operations, exposes reference aggregate functions.

The side-by-side import pattern — where the capability imports consumes, aggregates, and exposes independently — works well and remains the default for most projects. However, at scale it creates friction:

- **Duplicated wiring** — when 10 capabilities import the same aggregates file, each must independently wire the same consumes imports. The boilerplate grows linearly with adoption.
- **Implicit contracts** — a standalone aggregates file declares `call: "weather-api.get-forecast"`, but the importing capability must know which consumes file provides `weather-api`. The dependency is undocumented.
- **Fragile integration** — changing the `call` targets inside an aggregates file silently breaks all importing capabilities that don't also update their consumes block.

Transitive imports are an **optional** extension that solves these problems by letting standalone files declare their own dependencies:

- A standalone **aggregates** file *can* import the **consumes** files it needs → the `call` targets resolve locally, the file is self-contained
When the full dependency chain needs to be packaged together (exposes + aggregates + consumes), that is what **capabilities** are for — they are the document type designed to wire all three sections together.

Transitive imports follow the natural abstraction layer: consumes (leaf) → aggregates. Standalone exposes files do not support transitive imports because they sit at the top of the stack — wiring them with aggregates and consumes is the capability's job.

Standalone files without transitive imports continue to work exactly as before — their references fall through to the importing capability's context.

### 2.4 Precedent

The consumes import mechanism (`location` + `import` + `as`) already solved the modularization and reuse problem for consumed HTTP adapters. This proposal applies the same pattern — and the same mental model — to exposed adapters and aggregates. The transitive extension follows the same `location`-as-discriminant syntax, applied recursively within standalone files.

### 2.5 Use cases

| Scenario | Primary driver | Value |
|---|---|---|
| A 200-line capability split into exposes + aggregates + consumes files | Modularization | Smaller files, independent review, clearer separation of concerns |
| An exposes contract reviewed and linted before being wired to any capability | Modularization | Standalone validation catches contract issues early |
| Aggregate functions reviewed independently from adapter definitions | Modularization | Domain logic reviewed by domain experts, adapter contracts by platform team |
| A standard MCP tool catalog imported by 5 microservices | Contract reuse | Author once, import everywhere — updates propagate from the source file |
| A compliance REST adapter with mandatory health endpoints | Contract reuse | Teams import the standard adapter instead of re-declaring it |
| A shared forecast aggregate used across REST and MCP capabilities | Domain reuse | Domain functions defined once, imported by multiple capabilities |
| A skill catalog adapter shared between staging and production capabilities | Contract reuse | Same tools and metadata, different capabilities bind different secrets |
| A self-contained aggregates file that imports its own consumes | Transitive import | The aggregates file is a complete domain package — importers don't wire consumes manually |
| A capability wires together imported exposes, aggregates, and consumes | Composition | The capability is the natural place to assemble the full dependency chain |

---

## 3. Standalone File Formats

### 3.1 Standalone Exposes File

A new top-level YAML file format for standalone exposes definitions:

```yaml
# shared-weather-mcp.yml
naftiko: "1.0.0-alpha1"

info:
  label: "Weather MCP Server"
  description: "Shared MCP adapter for weather forecast tools"

exposes:
  - type: "mcp"
    port: 3000
    namespace: "weather-mcp"
    description: "Weather tools for AI agents"
    tools:
      - name: "get-forecast"
        description: "Fetch current weather forecast for a location"
        hints:
          readOnly: true
          openWorld: true
        inputParameters:
          - name: "location"
            type: "string"
            description: "City name or coordinates (lat,lon)"
        call: "weather-api.get-forecast"
        with:
          location: "$this.weather-mcp.location"
        outputParameters:
          - type: "object"
            mapping: "$.forecast"
            properties:
              temperature: { type: "number", mapping: "$.temperature" }
              condition: { type: "string", mapping: "$.condition" }
```

### Key points

- Same `naftiko` version header as capabilities
- `info` block for metadata (label, description) — optional, as in other document types
- `exposes` array at the root — **identical structure** as inside a capability
- No `capability` block, no `consumes`, no `aggregates` — purely an exposes definition
- `call` and `ref` references in the standalone file target consumed namespaces and aggregate functions that must be provided by the importing document (capability or another standalone file)

### Detection and disambiguation

Disambiguation is **content-based**, extending the existing `oneOf` at the schema root:

| Present at root | Document type |
|---|---|
| `capability` | Capability file |
| `consumes` (without `capability`, `aggregates`, `exposes`) | Standalone consumes file |
| `aggregates` (without `capability`, `exposes`) | Standalone aggregates file (may include `consumes` for transitive imports) |
| `exposes` (without `capability`, `consumes`, `aggregates`) | Standalone exposes file |

### 3.2 Standalone Aggregates File

A new top-level YAML file format for standalone aggregate definitions:

```yaml
# forecast-domain.yml
naftiko: "1.0.0-alpha1"

info:
  label: "Forecast Domain"
  description: "Reusable domain functions for weather forecasting"

aggregates:
  - label: "Forecast"
    namespace: "forecast"
    functions:
      - name: "get-forecast"
        description: "Fetch current weather forecast for a location."
        semantics:
          safe: true
          idempotent: true
        inputParameters:
          - name: "location"
            type: "string"
            description: "City name or coordinates (e.g. 'Paris' or '48.8566,2.3522')."
        call: "weather-api.get-forecast"
        with:
          location: "location"
        outputParameters:
          - type: "object"
            mapping: "$.forecast"
            properties:
              temperature:
                type: "number"
                mapping: "$.temperature"
              condition:
                type: "string"
                mapping: "$.condition"
```

### Key points

- Same `naftiko` version header as capabilities
- `info` block for metadata (label, description) — optional
- `aggregates` array at the root — **identical structure** as inside a capability
- No `capability` block, no `exposes` — purely an aggregates definition (optionally with `consumes` for transitive imports)
- `call` references in the standalone file target consumed namespaces — resolved from the file's own `consumes` imports if present, otherwise from the importing document's context

### Optional transitive dependencies in standalone aggregates

A standalone aggregates file *can optionally* import **consumes** files to make itself self-contained. This is not required — aggregates files without a `consumes` block are valid and resolve their `call` references against the importing document's context.

When transitive imports are used, the `consumes` block in an aggregates file accepts **only import entries** (`location` present) — not inline consumes definitions:

```yaml
# forecast-domain.yml — self-contained with transitive consumes
naftiko: "1.0.0-alpha1"

info:
  label: "Forecast Domain"
  description: "Self-contained domain functions — imports its own consumed API"

consumes:
  - location: "./weather-api.yml"
    import: "weather-api"

aggregates:
  - label: "Forecast"
    namespace: "forecast"
    functions:
      - name: "get-forecast"
        description: "Fetch current weather forecast for a location."
        call: "weather-api.get-forecast"
        with:
          location: "location"
        inputParameters:
          - name: "location"
            type: "string"
        outputParameters:
          - type: "object"
            mapping: "$.forecast"
            properties:
              temperature: { type: "number", mapping: "$.temperature" }
              condition: { type: "string", mapping: "$.condition" }
```

When a capability imports this aggregates file, the `weather-api` namespace comes along — the capability does not need to provide it separately. If the aggregates file had no `consumes` block, the capability would provide `weather-api` via its own `consumes` imports (the side-by-side pattern).

---

## 4. Import Mechanism — Exposes

### 4.1 Core concept

Once an adapter is defined in a standalone exposes file, it can be **imported** into a capability's `exposes` block. This serves both goals: modularization (split a large capability into smaller files) and contract reuse (import a predefined interface into multiple capabilities). The discriminant is the presence of the `location` field on the `exposes[]` entry — the same pattern used for consumes imports.

```yaml
capability:
  exposes:
    # Local adapter (no location → defined here)
    - type: "rest"
      port: 8080
      namespace: "my-api"
      resources:
        - path: "/health"
          operations:
            - method: "GET"
              call: "internal.health-check"

    # Imported adapter (location present → from external file)
    - location: "./shared-weather-mcp.yml"
      import: "weather-mcp"

  consumes:
    - type: "http"
      namespace: "weather-api"
      baseUri: "https://api.weather.example/v1"
      resources:
        - path: "forecast"
          name: "forecast"
          operations:
            - method: "GET"
              name: "get-forecast"
```

### 4.2 Import fields

| Field | Type | Required | Description |
|---|---|---|---|
| `location` | string (URI) | **Yes** | Path to the source exposes file. Relative paths resolved from the importing file's directory. |
| `import` | IdentifierKebab | **Yes** | Namespace identifier in the source exposes file |
| `as` | IdentifierKebab | No | Local alias for the imported namespace. If omitted, the source namespace is used. |

### 4.3 Port and address from source

When an adapter is imported, its runtime context (`port`, `address`, `authentication`) comes from the **source file** — not from the importing capability. The import brings the complete adapter definition including its runtime configuration.

This serves both use cases:

- **Modularization** — the standalone file is the single source of truth for the adapter contract, including its runtime binding. The capability does not repeat or override these details.
- **Contract reuse** — when multiple capabilities import a predefined interface, they all inherit the same port and address, ensuring consistency across deployments.

If different port or address is needed, define a local adapter instead. This is consistent with the consumes import design.

### 4.4 Cross-file `call` and `ref` resolution

Imported exposes adapters may contain `call` references to consumed namespaces or `ref` references to aggregate functions. Standalone exposes files do not support transitive imports, so all references resolve in the **importing document's** context:

- **`call` references** — the consumed namespace (e.g. `weather-api`) must be defined or imported in the importing document's `consumes` block
- **`ref` references** — the aggregate function (e.g. `forecast.get-forecast`) must be defined or imported in the importing document's `aggregates` block

This means the standalone exposes file declares **what it calls and references**, but the importing capability provides the actual implementations. If a reference cannot be resolved, the engine raises a validation error at capability load time.

### 4.5 Examples

#### Example A — Modularize a large capability

A capability with many MCP tools becomes easier to manage when the exposes contract lives in its own file:

**Extracted file** (`./weather-mcp.yml`):

```yaml
naftiko: "1.0.0-alpha1"
info:
  label: "Weather MCP Server"

exposes:
  - type: "mcp"
    port: 3000
    namespace: "weather-mcp"
    description: "Weather tools for AI agents"
    tools:
      - name: "get-forecast"
        description: "Fetch current weather forecast"
        call: "weather-api.get-forecast"
        with:
          location: "$this.weather-mcp.location"
        inputParameters:
          - name: "location"
            type: "string"
            description: "City name or coordinates"
        outputParameters:
          - type: "object"
            mapping: "$.forecast"
            properties:
              temperature: { type: "number", mapping: "$.temperature" }
              condition: { type: "string", mapping: "$.condition" }
```

**Capability** (now focused on domain wiring):

```yaml
naftiko: "1.0.0-alpha1"
info:
  label: "My Weather Capability"

capability:
  exposes:
    - location: "./weather-mcp.yml"
      import: "weather-mcp"

  consumes:
    - type: "http"
      namespace: "weather-api"
      baseUri: "https://api.weather.example/v1"
      resources:
        - path: "forecast"
          name: "forecast"
          operations:
            - method: "GET"
              name: "get-forecast"
```

The exposes file can be reviewed, linted, and version-controlled independently. The capability file is smaller and focused on wiring the adapter to the consumed API.

#### Example B — Predefined contract reused by multiple capabilities

An organization publishes a standard MCP interface. Two backend teams import it, each wiring it to a different consumed API:

**Shared contract** (`./standard-weather-mcp.yml`):

```yaml
naftiko: "1.0.0-alpha1"
info:
  label: "Standard Weather MCP Contract"
  description: "Organization-wide weather tool interface — all weather capabilities must expose this"

exposes:
  - type: "mcp"
    port: 3000
    namespace: "weather-mcp"
    description: "Weather tools for AI agents"
    tools:
      - name: "get-forecast"
        description: "Fetch current weather forecast for a location"
        hints:
          readOnly: true
          openWorld: true
        inputParameters:
          - name: "location"
            type: "string"
            description: "City name or coordinates (lat,lon)"
        call: "weather-api.get-forecast"
        with:
          location: "$this.weather-mcp.location"
        outputParameters:
          - type: "object"
            mapping: "$.forecast"
            properties:
              temperature: { type: "number", mapping: "$.temperature" }
              condition: { type: "string", mapping: "$.condition" }
```

**Team A** — consumes OpenWeather:

```yaml
naftiko: "1.0.0-alpha1"
capability:
  exposes:
    - location: "./standard-weather-mcp.yml"
      import: "weather-mcp"
  consumes:
    - type: "http"
      namespace: "weather-api"
      baseUri: "https://api.openweathermap.org/data/3.0"
      resources:
        - path: "forecast"
          name: "forecast"
          operations:
            - method: "GET"
              name: "get-forecast"
```

**Team B** — consumes WeatherStack:

```yaml
naftiko: "1.0.0-alpha1"
capability:
  exposes:
    - location: "./standard-weather-mcp.yml"
      import: "weather-mcp"
  consumes:
    - type: "http"
      namespace: "weather-api"
      baseUri: "https://api.weatherstack.com/v1"
      resources:
        - path: "forecast"
          name: "forecast"
          operations:
            - method: "GET"
              name: "get-forecast"
```

Both capabilities expose the exact same MCP interface to consumers. The contract is maintained in one place; the backend wiring differs per team.

#### Example C — Collision management with `as`

Two source files share the same namespace → `as` disambiguates:

```yaml
naftiko: "1.0.0-alpha1"
capability:
  exposes:
    - location: "./weather-mcp-v1.yml"
      import: "weather-mcp"
      as: "weather-mcp-v1"
    - location: "./weather-mcp-v2.yml"
      import: "weather-mcp"
      as: "weather-mcp-v2"

  consumes:
    - type: "http"
      namespace: "weather-api"
      baseUri: "https://api.weather.example/v1"
      resources: []
```

#### Example D — Mix inline and imported adapters

```yaml
naftiko: "1.0.0-alpha1"
capability:
  exposes:
    # Imported MCP adapter
    - location: "./shared-weather-mcp.yml"
      import: "weather-mcp"

    # Local REST adapter
    - type: "rest"
      port: 8080
      namespace: "internal-api"
      resources:
        - path: "/health"
          operations:
            - method: "GET"
              call: "internal.health"

  consumes:
    - type: "http"
      namespace: "weather-api"
      baseUri: "https://api.weather.example/v1"
      resources: []
    - type: "http"
      namespace: "internal"
      baseUri: "http://localhost:9090"
      resources: []
```

#### Example E — Imported adapter using `ref` to local aggregate

```yaml
# shared-forecast-mcp.yml — uses ref, not inline call
naftiko: "1.0.0-alpha1"
exposes:
  - type: "mcp"
    port: 3000
    namespace: "forecast-mcp"
    description: "Forecast MCP server"
    tools:
      - name: "get-forecast"
        description: "Get weather forecast"
        ref: "forecast.get-forecast"
```

```yaml
# my-capability.yml — aggregate is defined here, not in the source
naftiko: "1.0.0-alpha1"
capability:
  aggregates:
    - label: "Forecast"
      namespace: "forecast"
      functions:
        - name: "get-forecast"
          description: "Fetch weather forecast"
          call: "weather-api.get-forecast"
          with:
            location: "location"
          inputParameters:
            - name: "location"
              type: "string"
              description: "City name or coordinates"
          outputParameters:
            - type: "object"
              mapping: "$.forecast"
              properties:
                temperature: { type: "number", mapping: "$.temperature" }

  exposes:
    - location: "./shared-forecast-mcp.yml"
      import: "forecast-mcp"

  consumes:
    - type: "http"
      namespace: "weather-api"
      baseUri: "https://api.weather.example/v1"
      resources:
        - path: "forecast"
          name: "forecast"
          operations:
            - method: "GET"
              name: "get-forecast"
```

### 4.6 Constraints

- **No override** — an imported adapter is used exactly as defined in the source (port, address, authentication, resources, tools, skills). If you need a modified version, define a local adapter instead.
- **No cherry-picking** — you import an entire namespace, not individual resources, tools, or skills.
- **No transitive imports** — standalone exposes files do not support transitive imports. They cannot declare their own `consumes` or `aggregates` blocks. All `call` and `ref` references resolve in the importing document's context. Use a capability to wire exposes with aggregates and consumes.
- **Cross-file references resolve at import time** — `call` and `ref` targets must exist in the importing capability, not in the source exposes file.

---

## 5. Import Mechanism — Aggregates

### 5.1 Core concept

An aggregate defined in a standalone aggregates file can be **imported** into a capability's `aggregates` block. This follows the same `location`-as-discriminant pattern. The imported aggregate brings all its functions, semantics, and parameter definitions.

```yaml
capability:
  aggregates:
    # Local aggregate (no location → defined here)
    - label: "Internal"
      namespace: "internal"
      functions:
        - name: "health-check"
          description: "Check service health"
          call: "monitoring.health"

    # Imported aggregate (location present → from external file)
    - location: "./forecast-domain.yml"
      import: "forecast"

  exposes:
    - type: "mcp"
      port: 3000
      namespace: "weather-mcp"
      tools:
        - name: "get-forecast"
          description: "Get weather forecast"
          ref: "forecast.get-forecast"

  consumes:
    - type: "http"
      namespace: "weather-api"
      baseUri: "https://api.weather.example/v1"
      resources:
        - path: "forecast"
          name: "forecast"
          operations:
            - method: "GET"
              name: "get-forecast"
```

### 5.2 Import fields

| Field | Type | Required | Description |
|---|---|---|---|
| `location` | string (URI) | **Yes** | Path to the source aggregates file. Relative paths resolved from the importing file's directory. |
| `import` | IdentifierKebab | **Yes** | Namespace identifier in the source aggregates file |
| `as` | IdentifierKebab | No | Local alias for the imported namespace. If omitted, the source namespace is used. |

### 5.3 Cross-file `call` resolution

Imported aggregate functions may contain `call` references to consumed namespaces.

**Without transitive imports** (default): all `call` references resolve against the importing document's `consumes` block — the capability provides the consumed namespaces.

**With transitive imports** (optional): references resolve using **layered resolution**:

1. **Local resolution first** — if the standalone aggregates file declares its own `consumes` imports, `call` references resolve against those first
2. **Fall-through to importing context** — any `call` reference not satisfied by the standalone file's own imports must be provided by the importing document

In both cases, the consumed namespace (e.g. `weather-api`) must be resolvable — from the aggregates file's own `consumes` imports (if present) or from the importing document's `consumes` block.

If a `call` target cannot be resolved at any level, the engine raises a validation error at capability load time.

### 5.4 Examples

#### Example A — Modularize domain logic

Extract aggregate functions into a standalone file for independent review:

**Domain file** (`./forecast-domain.yml`):

```yaml
naftiko: "1.0.0-alpha1"
info:
  label: "Forecast Domain"

aggregates:
  - label: "Forecast"
    namespace: "forecast"
    functions:
      - name: "get-forecast"
        description: "Fetch current weather forecast for a location."
        semantics:
          safe: true
          idempotent: true
        inputParameters:
          - name: "location"
            type: "string"
            description: "City name or coordinates"
        call: "weather-api.get-forecast"
        with:
          location: "location"
        outputParameters:
          - type: "object"
            mapping: "$.forecast"
            properties:
              temperature: { type: "number", mapping: "$.temperature" }
              condition: { type: "string", mapping: "$.condition" }
```

**Capability** (wires domain, adapter contract, and consumed API together):

```yaml
naftiko: "1.0.0-alpha1"
capability:
  aggregates:
    - location: "./forecast-domain.yml"
      import: "forecast"
  exposes:
    - type: "mcp"
      port: 3000
      namespace: "weather-mcp"
      tools:
        - name: "get-forecast"
          ref: "forecast.get-forecast"
    - type: "rest"
      port: 8080
      namespace: "weather-rest"
      resources:
        - path: "/forecast/{location}"
          operations:
            - ref: "forecast.get-forecast"
              method: "GET"
              inputParameters:
                - name: "location"
                  in: "path"
                  type: "string"
  consumes:
    - type: "http"
      namespace: "weather-api"
      baseUri: "https://api.weather.example/v1"
      resources:
        - path: "forecast"
          name: "forecast"
          operations:
            - method: "GET"
              name: "get-forecast"
```

#### Example B — Shared domain logic across teams

A platform team publishes reusable forecast functions. Two teams import them, each consuming a different backend:

**Shared domain** (`./standard-forecast-functions.yml`):

```yaml
naftiko: "1.0.0-alpha1"
info:
  label: "Standard Forecast Functions"
  description: "Organization-wide forecast domain functions"

aggregates:
  - label: "Forecast"
    namespace: "forecast"
    functions:
      - name: "get-forecast"
        description: "Fetch current weather forecast for a location."
        semantics:
          safe: true
          idempotent: true
        inputParameters:
          - name: "location"
            type: "string"
            description: "City name or coordinates"
        call: "weather-api.get-forecast"
        with:
          location: "location"
        outputParameters:
          - type: "object"
            mapping: "$.forecast"
            properties:
              temperature: { type: "number", mapping: "$.temperature" }
              condition: { type: "string", mapping: "$.condition" }
```

**Team A** — MCP adapter backed by OpenWeather:

```yaml
naftiko: "1.0.0-alpha1"
capability:
  aggregates:
    - location: "./standard-forecast-functions.yml"
      import: "forecast"
  exposes:
    - type: "mcp"
      port: 3000
      namespace: "weather-mcp"
      tools:
        - name: "get-forecast"
          ref: "forecast.get-forecast"
  consumes:
    - type: "http"
      namespace: "weather-api"
      baseUri: "https://api.openweathermap.org/data/3.0"
      resources:
        - path: "forecast"
          name: "forecast"
          operations:
            - method: "GET"
              name: "get-forecast"
```

**Team B** — REST adapter backed by WeatherStack:

```yaml
naftiko: "1.0.0-alpha1"
capability:
  aggregates:
    - location: "./standard-forecast-functions.yml"
      import: "forecast"
  exposes:
    - type: "rest"
      port: 8080
      namespace: "weather-rest"
      resources:
        - path: "/forecast/{location}"
          operations:
            - ref: "forecast.get-forecast"
              method: "GET"
              inputParameters:
                - name: "location"
                  in: "path"
                  type: "string"
  consumes:
    - type: "http"
      namespace: "weather-api"
      baseUri: "https://api.weatherstack.com/v1"
      resources:
        - path: "forecast"
          name: "forecast"
          operations:
            - method: "GET"
              name: "get-forecast"
```

Both capabilities share the same domain logic. The aggregate defines the function signature, semantics, and output shape; each capability provides the consumed backend.

#### Example C — Collision management with `as`

```yaml
capability:
  aggregates:
    - location: "./forecast-v1.yml"
      import: "forecast"
      as: "forecast-v1"
    - location: "./forecast-v2.yml"
      import: "forecast"
      as: "forecast-v2"
```

#### Example D — Fully modularized capability

All three sections imported from external files:

```yaml
naftiko: "1.0.0-alpha1"
info:
  label: "Weather Service"
  description: "Fully modularized — consumes, aggregates, and exposes are external files"

capability:
  consumes:
    - location: "./weather-api.yml"
      import: "weather-api"
  aggregates:
    - location: "./forecast-domain.yml"
      import: "forecast"
  exposes:
    - location: "./weather-mcp.yml"
      import: "weather-mcp"
```

### 5.5 Constraints

- **No override** — an imported aggregate is used exactly as defined in the source (functions, semantics, parameters). If you need a modified version, define a local aggregate instead.
- **No cherry-picking** — you import an entire namespace, not individual functions.
- **Transitive consumes imports are optional** — a standalone aggregates file *can* import standalone consumes files (layer below it), but not other aggregates files (same layer) or exposes files (layer above). Without transitive imports, `call` references fall through to the importing document. See section 6.
- **Layered reference resolution** — when transitive consumes imports are present, `call` references resolve first against the standalone file's own `consumes` imports, then fall through to the importing document's context. Without transitive imports, all `call` references fall through directly. See section 5.3.
- **Cycle detection** — when transitive imports are used, the engine detects and rejects circular import chains at load time. See section 6.3.

---

## 6. Transitive Imports & Cycle Detection

Transitive imports are **optional** and currently supported only for **standalone aggregates files** (importing consumes). Standalone consumes and exposes files do not support transitive imports. This section defines the rules that apply when a standalone aggregates file *does* declare transitive consumes imports.

### 6.1 The layer DAG

Naftiko standalone files form a directed acyclic graph (DAG) following the natural abstraction layers of a capability:

```
   consumes          ← leaf (no imports)
      ↑
   aggregates        ← can import consumes (optional)
      ↑
   exposes           ← no transitive imports (references resolve in importing document)
      ↑
   capability        ← can import all three (the root, wiring point)
```

Transitive imports are currently supported only at the **aggregates → consumes** edge. Standalone exposes files do not support transitive imports — when you need to wire exposes with aggregates and consumes, use a capability.

| Importing document | Can import (optional) | Cannot import |
|---|---|---|
| Standalone **consumes** | Nothing — leaf of the DAG | consumes, aggregates, exposes |
| Standalone **aggregates** | Standalone **consumes** | aggregates, exposes |
| Standalone **exposes** | Nothing — references resolve in importing document | consumes, aggregates, exposes |
| **Capability** | Standalone **consumes**, **aggregates**, **exposes** | capabilities |

### 6.2 Transitive resolution model

When the engine encounters an import, it loads the source file and recursively resolves any transitive imports within it before proceeding. If the source file has no transitive imports, the engine loads it as a simple standalone document — no recursion needed. The resolution order follows the layer DAG — bottom-up:

**Step 1 — Resolve consumes** (leaf): all `consumes` imports in the document are resolved first. Consumes files have no dependencies of their own.

**Step 2 — Resolve aggregates**: each imported aggregates file may declare its own `consumes` imports. The engine resolves those first (step 1 recursion), then resolves the aggregates themselves.

**Step 3 — Resolve exposes**: imported exposes files are loaded as-is — no transitive resolution. Their `call` and `ref` references are validated against the importing document's resolved consumes and aggregates.

**Step 4 — Resolve aggregate refs**: once all imports are flattened, `ref` references in exposes are resolved against the fully materialized aggregates.

#### Namespace merging

When transitive imports bring namespaces into a document that already defines or imports the same namespace:

- **Duplicate namespace from same source file** — deduplicated silently (idempotent). This handles the diamond case: two aggregates files both import the same consumes file.
- **Conflicting namespace from different source files** — validation error. Two different definitions for the same namespace cannot coexist.

#### Layered reference resolution

References in a standalone aggregates file resolve using a two-level strategy:

1. **Local-first** — resolve `call` references against the standalone file's own transitive consumes imports
2. **Fall-through** — if unresolved, delegate to the importing document's context

This means a standalone aggregates file that imports its own consumes is fully self-contained for those `call` targets. A standalone aggregates file that does **not** import its own consumes falls through to the capability — maintaining backward compatibility with the non-transitive model.

### 6.3 Cycle detection

Although the layer DAG structurally prevents cycles between document types, the engine must still detect and reject cycles caused by:

- **File-level self-reference** — a file that imports itself (directly or via symlink)
- **Diamond paths with conflicting content** — two import paths that load the same file with different `as` aliases, producing namespace conflicts
- **Future extensions** — if peer-to-peer imports (e.g. aggregates importing aggregates) are added later, cycles become structurally possible

#### Algorithm

The engine maintains an **import stack** (ordered set of canonical file paths) during recursive resolution. Before loading a source file:

1. Resolve the `location` to a canonical absolute path (resolving `..`, symlinks, etc.)
2. If the path is already in the import stack → **cycle detected** → raise an error with the full cycle trace
3. Push the path onto the stack
4. Recursively resolve the source file's own imports
5. Pop the path from the stack

```
Error: Circular import detected:
  capability.yml
    → ./forecast-domain.yml (aggregates)
      → ./weather-api.yml (consumes)
        → ./forecast-domain.yml ← CYCLE
```

The import stack is thread-local and scoped to a single capability load. Each import resolution starts with a fresh stack containing only the root document.

#### Layer enforcement

In addition to cycle detection, the engine validates that imports respect the layer DAG:

- If a standalone consumes file contains `consumes`, `aggregates`, or `exposes` imports → error
- If a standalone aggregates file contains `aggregates` or `exposes` imports → error
- If a standalone exposes file contains `consumes`, `aggregates`, or `exposes` imports → error

```
Error: Layer violation in forecast-domain.yml:
  Standalone aggregates files cannot import other aggregates files.
  Only consumes imports are allowed. Found: aggregates import "other-domain"
```

### 6.4 Examples

#### Example A — Self-contained aggregates file (consumes → aggregates)

A standalone aggregates file imports the consumed API it depends on:

**Consumed API** (`./weather-api.yml`):

```yaml
naftiko: "1.0.0-alpha1"
consumes:
  - type: "http"
    namespace: "weather-api"
    baseUri: "https://api.weather.example/v1"
    resources:
      - path: "forecast"
        name: "forecast"
        operations:
          - method: "GET"
            name: "get-forecast"
```

**Domain functions** (`./forecast-domain.yml`) — imports its own consumes:

```yaml
naftiko: "1.0.0-alpha1"
info:
  label: "Forecast Domain"

consumes:
  - location: "./weather-api.yml"
    import: "weather-api"

aggregates:
  - label: "Forecast"
    namespace: "forecast"
    functions:
      - name: "get-forecast"
        description: "Fetch weather forecast for a location."
        semantics:
          safe: true
          idempotent: true
        call: "weather-api.get-forecast"
        with:
          location: "location"
        inputParameters:
          - name: "location"
            type: "string"
        outputParameters:
          - type: "object"
            mapping: "$.forecast"
            properties:
              temperature: { type: "number", mapping: "$.temperature" }
              condition: { type: "string", mapping: "$.condition" }
```

**Capability** — imports the self-contained aggregates:

```yaml
naftiko: "1.0.0-alpha1"
capability:
  aggregates:
    - location: "./forecast-domain.yml"
      import: "forecast"
  exposes:
    - type: "mcp"
      port: 3000
      namespace: "weather-mcp"
      tools:
        - name: "get-forecast"
          ref: "forecast.get-forecast"
```

The capability does not need a `consumes` block — the aggregates file brought `weather-api` along transitively.

#### Example B — Capability wires exposes, aggregates, and consumes together

When the full dependency chain needs to be assembled, the **capability** is the natural place to do it. Each standalone file stays simple:

**Consumed API** (`./weather-api.yml`): *(same as Example A)*

**Domain functions** (`./forecast-domain.yml`): *(same as Example A — imports weather-api.yml transitively)*

**MCP adapter** (`./weather-mcp.yml`) — pure exposes, no transitive imports:

```yaml
naftiko: "1.0.0-alpha1"
info:
  label: "Weather MCP Server"

exposes:
  - type: "mcp"
    port: 3000
    namespace: "weather-mcp"
    description: "Weather tools for AI agents"
    tools:
      - name: "get-forecast"
        description: "Fetch current weather forecast"
        ref: "forecast.get-forecast"
```

**Capability** — wires everything together:

```yaml
naftiko: "1.0.0-alpha1"
capability:
  aggregates:
    - location: "./forecast-domain.yml"
      import: "forecast"
  exposes:
    - location: "./weather-mcp.yml"
      import: "weather-mcp"
```

Resolution trace:
1. Resolve aggregates: load `forecast-domain.yml` → finds `consumes` import for `weather-api.yml`
2. Load `weather-api.yml` → leaf (no imports) → resolve consumes
3. Back in `forecast-domain.yml` → resolve aggregates (call targets now available from transitive consumes)
4. Merge transitive consumes (`weather-api`) into capability
5. Resolve exposes: load `weather-mcp.yml` → no transitive imports → load as-is
6. Resolve aggregate refs: `forecast.get-forecast` resolves against the materialized aggregates

#### Example C — Diamond import (deduplicated)

Two aggregates files import the same consumes file:

```yaml
# capability.yml
naftiko: "1.0.0-alpha1"
capability:
  aggregates:
    - location: "./forecast-domain.yml"      # imports weather-api.yml
      import: "forecast"
    - location: "./alerts-domain.yml"         # also imports weather-api.yml
      import: "alerts"
  exposes:
    - type: "mcp"
      port: 3000
      namespace: "weather-mcp"
      tools:
        - name: "get-forecast"
          ref: "forecast.get-forecast"
        - name: "get-alerts"
          ref: "alerts.get-alerts"
```

Both `forecast-domain.yml` and `alerts-domain.yml` import `weather-api.yml`. The engine resolves `weather-api` twice but detects it is the same canonical file → deduplicates to a single consumes entry. No conflict.

#### Example D — Mixed transitive and direct imports

A capability supplements transitive dependencies with its own direct imports:

```yaml
naftiko: "1.0.0-alpha1"
capability:
  consumes:
    # Direct import — not provided transitively
    - location: "./geocoding-api.yml"
      import: "geocoding"

  aggregates:
    # Transitive — brings its own weather-api consumes
    - location: "./forecast-domain.yml"
      import: "forecast"

  exposes:
    - type: "mcp"
      port: 3000
      namespace: "weather-mcp"
      tools:
        - name: "get-forecast"
          ref: "forecast.get-forecast"
        - name: "geocode"
          description: "Resolve address to coordinates"
          call: "geocoding.geocode"
          with:
            address: "$this.weather-mcp.address"
          inputParameters:
            - name: "address"
              type: "string"
          outputParameters:
            - type: "object"
```

The `weather-api` namespace comes transitively via `forecast-domain.yml`. The `geocoding` namespace is imported directly by the capability. Both are available for reference resolution.

---

## 7. Schema Changes

This section describes only the **changes** to `naftiko-schema.json`. All existing `$defs` (`ExposesRest`, `ExposesMcp`, `ExposesSkill`, `Aggregate`, `AggregateFunction`, etc.) are **unchanged**.

### 7.1 Root — add `exposes` and `aggregates` properties, extend `oneOf`

Add `exposes` and `aggregates` as root-level properties and extend the `oneOf` discriminator. Root-level `consumes` and `aggregates` on exposes/aggregates standalone files enable transitive imports:

```json
{
  "type": "object",
  "properties": {
    "naftiko": { "type": "string", "const": "1.0.0-alpha1" },
    "info":    { "$ref": "#/$defs/Info" },
    "capability": { "$ref": "#/$defs/Capability" },
    "consumes": { ... },
    "binds": { ... },
    "exposes": {
      "type": "array",
      "description": "Standalone exposed server adapters",
      "items": {
        "oneOf": [
          { "$ref": "#/$defs/ExposesRest" },
          { "$ref": "#/$defs/ExposesMcp" },
          { "$ref": "#/$defs/ExposesSkill" }
        ]
      },
      "minItems": 1
    },
    "aggregates": {
      "type": "array",
      "description": "Standalone domain aggregates defining reusable functions.",
      "items": {
        "$ref": "#/$defs/Aggregate"
      },
      "minItems": 1
    }
  },
  "oneOf": [
    { "required": ["naftiko", "capability"] },
    { "required": ["naftiko", "consumes"],
      "not": { "anyOf": [
        { "required": ["aggregates"] },
        { "required": ["exposes"] },
        { "required": ["capability"] }
      ]}
    },
    { "required": ["naftiko", "aggregates"],
      "not": { "anyOf": [
        { "required": ["exposes"] },
        { "required": ["capability"] }
      ]}
    },
    { "required": ["naftiko", "exposes"],
      "not": { "anyOf": [
        { "required": ["consumes"] },
        { "required": ["aggregates"] },
        { "required": ["capability"] }
      ]}
    }
  ],
  "additionalProperties": false
}
```

The `oneOf` encodes the import rules:

- **Capability** — can have `consumes`, `aggregates`, `exposes` inline or imported (no restriction)
- **Standalone consumes** — `consumes` only, no `aggregates` or `exposes` (leaf)
- **Standalone aggregates** — `aggregates` required; `consumes` optional (for transitive imports); no `exposes`
- **Standalone exposes** — `exposes` only, no `consumes`, `aggregates`, or `capability` (references resolve in importing document)

A standalone file without transitive imports simply omits the optional blocks — the schema validates it the same way.

### 7.2 New `$def` — `ImportedExposes`

```json
"ImportedExposes": {
  "type": "object",
  "description": "An imported exposed server adapter. Discriminant: 'location' field present.",
  "properties": {
    "location": {
      "type": "string",
      "description": "URI to the source exposes file. Relative paths resolved from the importing file's directory."
    },
    "import": {
      "$ref": "#/$defs/IdentifierKebab",
      "description": "Namespace identifier in the source exposes file"
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

### 7.3 New `$def` — `ImportedAggregate`

```json
"ImportedAggregate": {
  "type": "object",
  "description": "An imported domain aggregate. Discriminant: 'location' field present.",
  "properties": {
    "location": {
      "type": "string",
      "description": "URI to the source aggregates file. Relative paths resolved from the importing file's directory."
    },
    "import": {
      "$ref": "#/$defs/IdentifierKebab",
      "description": "Namespace identifier in the source aggregates file"
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

### 7.4 Modified `$def` — `Capability`

The `exposes` and `aggregates` items change to `oneOf` accepting both local and imported entries:

```json
"Capability": {
  "properties": {
    "exposes": {
      "type": "array",
      "items": {
        "oneOf": [
          { "$ref": "#/$defs/ExposesRest" },
          { "$ref": "#/$defs/ExposesMcp" },
          { "$ref": "#/$defs/ExposesSkill" },
          { "$ref": "#/$defs/ImportedExposes" }
        ]
      }
    },
    "aggregates": {
      "type": "array",
      "items": {
        "oneOf": [
          { "$ref": "#/$defs/Aggregate" },
          { "$ref": "#/$defs/ImportedAggregate" }
        ]
      }
    }
  }
}
```

---

## 8. Java Implementation

### 8.1 New spec class — `ImportedExposesSpec`

Mirrors `ImportedConsumesHttpSpec`. Extends `ServerSpec`, deserialized when `location` is present.

```
src/main/java/io/naftiko/spec/exposes/ImportedExposesSpec.java
```

```java
@JsonDeserialize(using = JsonDeserializer.None.class)
public class ImportedExposesSpec extends ServerSpec {
    @JsonProperty("location")
    private volatile String location;

    @JsonProperty("import")
    private volatile String importNamespace;

    @JsonProperty("as")
    private volatile String alias;

    public String getLocation() { return location; }
    public String getImportNamespace() { return importNamespace; }
    public String getAlias() { return alias; }

    @Override
    public String getNamespace() {
        if (importNamespace == null) {
            throw new IllegalStateException(
                "Cannot get namespace before import is resolved. Location: " + location
            );
        }
        return (alias != null && !alias.isEmpty()) ? alias : importNamespace;
    }
}
```

### 8.2 Deserialization change — `ServerSpec`

Currently `ServerSpec` uses `@JsonTypeInfo` + `@JsonSubTypes` with `type` as discriminator. To support `ImportedExposesSpec` (which has no `type` field), switch to a custom `ServerSpecDeserializer`, following the same pattern as `ClientSpecDeserializer`:

```java
public class ServerSpecDeserializer extends JsonDeserializer<ServerSpec> {
    @Override
    public ServerSpec deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = ctxt.readTree(p);

        // If 'location' is present → imported adapter
        if (node.has("location")) {
            return ctxt.readTreeAsValue(node, ImportedExposesSpec.class);
        }

        // Otherwise delegate to Jackson's type-based polymorphism
        String type = node.has("type") ? node.get("type").asText() : null;
        if ("rest".equals(type)) return ctxt.readTreeAsValue(node, RestServerSpec.class);
        if ("mcp".equals(type)) return ctxt.readTreeAsValue(node, McpServerSpec.class);
        if ("skill".equals(type)) return ctxt.readTreeAsValue(node, SkillServerSpec.class);

        throw new IOException("Unknown exposes type: " + type);
    }
}
```

Replace `@JsonTypeInfo` + `@JsonSubTypes` on `ServerSpec` with:
```java
@JsonDeserialize(using = ServerSpecDeserializer.class)
public abstract class ServerSpec { ... }
```

### 8.3 New resolver — `ExposesImportResolver`

Mirrors `ConsumesImportResolver`. Resolves `ImportedExposesSpec` entries by loading the source file and replacing them with the concrete `ServerSpec` instance. No recursive resolution — standalone exposes files do not support transitive imports.

```
src/main/java/io/naftiko/engine/ExposesImportResolver.java
```

```java
public class ExposesImportResolver {

    public void resolveImports(List<ServerSpec> exposes, String capabilityDir) {
        for (int i = 0; i < exposes.size(); i++) {
            ServerSpec spec = exposes.get(i);
            if (spec instanceof ImportedExposesSpec importSpec) {
                ServerSpec resolved = resolve(importSpec, capabilityDir);
                exposes.set(i, resolved);
            }
        }
    }

    ServerSpec resolve(ImportedExposesSpec importSpec, String capabilityDir) {
        // 1. Load source file from location (relative to capabilityDir)
        // 2. Validate that the source file has no consumes or aggregates blocks
        // 3. Find the namespace matching importSpec.getImportNamespace()
        // 4. Deep-copy the resolved ServerSpec
        // 5. Apply alias if present (set namespace to importSpec.getAlias())
        // 6. Return the resolved copy
    }
}
```

### 8.4 New spec class — `ImportedAggregateSpec`

Mirrors `ImportedConsumesHttpSpec`. Wraps import metadata, deserialized when `location` is present on an `aggregates[]` entry.

```
src/main/java/io/naftiko/spec/ImportedAggregateSpec.java
```

```java
@JsonDeserialize(using = JsonDeserializer.None.class)
public class ImportedAggregateSpec {
    @JsonProperty("location")
    private volatile String location;

    @JsonProperty("import")
    private volatile String importNamespace;

    @JsonProperty("as")
    private volatile String alias;

    public String getLocation() { return location; }
    public String getImportNamespace() { return importNamespace; }
    public String getAlias() { return alias; }

    public String getEffectiveNamespace() {
        return (alias != null && !alias.isEmpty()) ? alias : importNamespace;
    }
}
```

### 8.5 Deserialization change — `aggregates` array

The `aggregates` array in `CapabilitySpec` currently deserializes directly to `List<AggregateSpec>`. To support `ImportedAggregateSpec` (which has `location` instead of `label`/`namespace`/`functions`), use a custom deserializer that checks for the `location` discriminant — the same pattern as `ServerSpecDeserializer` and `ClientSpecDeserializer`.

### 8.6 New resolver — `AggregatesImportResolver`

Mirrors `ConsumesImportResolver`. Resolves `ImportedAggregateSpec` entries by loading the source file and **recursively resolving its transitive consumes imports** before replacing the import entry.

```
src/main/java/io/naftiko/engine/AggregatesImportResolver.java
```

```java
public class AggregatesImportResolver {

    public void resolveImports(List<Object> aggregates, String capabilityDir,
                               ImportStack importStack) {
        for (int i = 0; i < aggregates.size(); i++) {
            Object entry = aggregates.get(i);
            if (entry instanceof ImportedAggregateSpec importSpec) {
                AggregateSpec resolved = resolve(importSpec, capabilityDir, importStack);
                aggregates.set(i, resolved);
            }
        }
    }

    AggregateSpec resolve(ImportedAggregateSpec importSpec, String capabilityDir,
                          ImportStack importStack) {
        // 1. Resolve location to canonical path
        // 2. Check importStack for cycle → throw if detected
        // 3. Push path onto importStack
        // 4. Load source file from location
        // 5. Recursively resolve transitive consumes imports (if any)
        // 6. Find the namespace matching importSpec.getImportNamespace()
        // 7. Collect transitive consumes for namespace merging
        // 8. Deep-copy the resolved AggregateSpec
        // 9. Apply alias if present
        // 10. Pop path from importStack
        // 11. Return the resolved copy + transitive consumes
    }
}
```

### 8.7 New utility — `ImportStack`

Tracks the import chain during recursive resolution, detecting cycles and enforcing layer constraints.

```
src/main/java/io/naftiko/engine/ImportStack.java
```

```java
public class ImportStack {
    private final LinkedHashSet<String> stack = new LinkedHashSet<>();

    /**
     * Push a canonical file path onto the stack.
     * @throws IllegalStateException if the path is already on the stack (cycle detected)
     */
    public void push(String canonicalPath) {
        if (!stack.add(canonicalPath)) {
            throw new IllegalStateException(
                "Circular import detected:\n" + formatCycle(canonicalPath)
            );
        }
    }

    public void pop(String canonicalPath) {
        stack.remove(canonicalPath);
    }

    String formatCycle(String duplicate) {
        StringBuilder sb = new StringBuilder();
        for (String path : stack) {
            sb.append("  → ").append(path).append("\n");
        }
        sb.append("  → ").append(duplicate).append(" ← CYCLE");
        return sb.toString();
    }
}
```

### 8.8 Integration point — `Capability.java`

Add aggregates and exposes import resolution in the correct order — consumes first (no dependencies), then aggregates (may transitively resolve consumes), then exposes (no transitive resolution), then aggregate ref resolution. The `ImportStack` is shared across aggregates resolution for cycle detection:

```java
// Create shared import stack, seeded with the capability file itself
ImportStack importStack = new ImportStack();
importStack.push(canonicalize(capabilityPath));

// Resolve consumes imports (leaf — no transitive dependencies)
ConsumesImportResolver consumesImportResolver = new ConsumesImportResolver();
consumesImportResolver.resolveImports(spec.getCapability().getConsumes(),
                                       capabilityDir, importStack);

// Resolve aggregates imports (may transitively resolve consumes)
if (spec.getCapability().getAggregates() != null) {
    AggregatesImportResolver aggregatesImportResolver = new AggregatesImportResolver();
    aggregatesImportResolver.resolveImports(spec.getCapability().getAggregates(),
                                             capabilityDir, importStack);
    // Merge transitive consumes into the capability's consumes list
    mergeTransitiveConsumes(spec);
}

// Resolve exposes imports (no transitive resolution — simple load)
if (spec.getCapability().getExposes() != null) {
    ExposesImportResolver exposesImportResolver = new ExposesImportResolver();
    exposesImportResolver.resolveImports(spec.getCapability().getExposes(),
                                          capabilityDir);
}

// Resolve aggregate refs (may reference imported aggregates, exposes, or consumes)
AggregateRefResolver aggregateRefResolver = new AggregateRefResolver();
aggregateRefResolver.resolve(spec);

importStack.pop(canonicalize(capabilityPath));
```

#### Namespace merging during transitive resolution

The `mergeTransitiveConsumes` method flattens transitive consumes dependencies (from aggregates imports) into the capability's top-level consumes list:

```java
void mergeTransitiveConsumes(NaftikoSpec spec) {
    // For each resolved aggregates entry that carried transitive consumes:
    //   - If the namespace already exists in spec.consumes with the same canonical source → skip (deduplicate)
    //   - If the namespace exists but from a different source → throw (conflict)
    //   - Otherwise → append to spec.consumes
}
```

---

## 9. Validation Rules (Spectral)

### 9.1 Standalone exposes files

| Rule | Severity | Description |
|---|---|---|
| `exposes-namespace-required` | error | Exposed adapters in standalone files **must** have a `namespace` |
| `exposes-unique-namespace` | error | Namespace must be unique within a standalone exposes file |

### 9.2 Standalone aggregates files

| Rule | Severity | Description |
|---|---|---|
| `aggregates-namespace-required` | error | Aggregates in standalone files **must** have a `namespace` |
| `aggregates-unique-namespace` | error | Namespace must be unique within a standalone aggregates file |
| `aggregates-function-name-required` | error | Functions in standalone aggregates files **must** have a `name` |
| `aggregates-unique-function-name` | error | Function `name` must be unique within an aggregate |

### 9.3 Exposes import mechanism

| Rule | Severity | Description |
|---|---|---|
| `exposes-import-ref-exists` | error | The `import` reference (namespace) must resolve to an existing namespace in the source exposes file |
| `exposes-import-unique-alias` | warning | `as` aliases must be unique within the capability's `exposes[]` array |
| `exposes-import-call-target-exists` | error | All `call` references in an imported adapter must resolve to a namespace in the importing document's `consumes[]` |
| `exposes-import-ref-target-exists` | error | All `ref` references in an imported adapter must resolve to a function in the importing document's `aggregates[]` |

### 9.4 Aggregates import mechanism

| Rule | Severity | Description |
|---|---|---|
| `aggregates-import-ref-exists` | error | The `import` reference (namespace) must resolve to an existing namespace in the source aggregates file |
| `aggregates-import-unique-alias` | warning | `as` aliases must be unique within the capability's `aggregates[]` array |
| `aggregates-import-call-target-exists` | error | All `call` references in an imported aggregate must resolve to a namespace in either the source file's own transitive consumes or the importing document's `consumes[]` |

### 9.5 Transitive import mechanism

| Rule | Severity | Description |
|---|---|---|
| `import-no-cycle` | error | The import graph must be acyclic — a file cannot appear twice in a single resolution chain |
| `import-layer-direction` | error | Standalone aggregates files can only import consumes files. Standalone consumes and exposes files cannot contain import entries. |
| `import-namespace-no-conflict` | error | When the same namespace arrives from multiple transitive paths, it must originate from the **same** canonical source file (diamond OK, conflict not OK) |
| `standalone-consumes-no-imports` | error | Standalone consumes files must not contain `consumes`, `aggregates`, or `exposes` import entries |
| `standalone-aggregates-no-peer-imports` | error | Standalone aggregates files must not contain `aggregates` or `exposes` import entries — only `consumes` imports allowed |
| `standalone-exposes-no-imports` | error | Standalone exposes files must not contain `consumes`, `aggregates`, or `exposes` import entries — all references resolve in the importing document |

### 9.6 Port collision

| Rule | Severity | Description |
|---|---|---|
| `exposes-port-no-collision` | error | Imported adapters must not bind to the same port as any other exposed adapter in the capability |

---

## 10. Design Decisions & Rationale

### Decision 1: Modularization first, contract reuse second

**Context**: The proposal serves two goals. Which should drive the design?

**Choice**: Modularization is the primary driver — any capability should benefit from extracting its sections into standalone files, even if they are never imported by another capability. Contract reuse is a secondary benefit that builds on the modular file format.

**Rationale**:
- Modularization delivers value immediately for any non-trivial capability: smaller files, independent review, clearer separation of concerns
- Contract reuse only applies when multiple capabilities share the same interface or domain logic — a narrower but high-impact use case
- Designing for modularization first ensures the standalone file formats are useful on their own, not only as reuse mechanisms
- The import mechanism makes reuse possible without any additional feature — it is a natural consequence of having standalone formats

### Decision 2: Consistent import syntax across all document types

**Choice**: Reuse `location` + `import` + `as` — the same three fields and the same `location`-as-discriminant pattern — for consumes, exposes, and aggregates.

**Rationale**:
- Consistency — one import pattern to learn, applied uniformly across all three document types
- Proven design — the consumes import has been validated in production
- Tooling reuse — linters, IDE extensions, and documentation apply uniformly

### Decision 3: Reference resolution strategy

**Context**: Imported exposes may contain `call` or `ref` references. Imported aggregates may contain `call` references. Where should those references resolve?

**Choice**: Two resolution strategies depending on document type:
- **Standalone exposes** — all references resolve in the **importing document's** context. The capability provides the consumed namespaces and aggregates.
- **Standalone aggregates** — `call` references resolve using **local-first, fall-through**: first against the file's own transitive consumes imports (if present), then against the importing document's context.

**Rationale**:
- Exposes sit at the top of the abstraction stack — wiring them with aggregates and consumes is the capability's job
- Aggregates sit in the middle — they have a natural, single-direction dependency on consumes that is safe to carry transitively
- A self-contained aggregates file (with its own transitive consumes) is a complete, testable domain package
- A lightweight aggregates file (without transitive imports) still works — references fall through to the capability
- Validation at import time ensures all references resolve before the engine starts

### Decision 4: No port override

**Choice**: The port declared in the source exposes file is used as-is. No override mechanism.

**Rationale**:
- Consistent with the consumes import design (no address override)
- Port conflicts are caught by the `exposes-port-no-collision` Spectral rule
- If a different port is needed, define a local adapter instead

### Decision 5: Optional transitive consumes on aggregates, capability as wiring point

**Choice**: Only standalone aggregates files can optionally import consumes (the layer below them). Standalone exposes files do not support transitive imports — when you need to wire exposes with aggregates and consumes, use a capability. When transitive imports are used, the `consumes` block in an aggregates file accepts **only import entries** (`location` present) — not inline definitions.

**Rationale**:
- **Side-by-side imports** (capability wires all three sections) remain the default and simplest pattern
- **Transitive consumes on aggregates** are an opt-in extension for teams that want self-contained domain packages
- The capability is the natural document type for wiring all three concerns together — it already has that role
- Allowing exposes to import aggregates would create a second wiring point that competes with the capability, adding complexity without clear benefit over writing a capability
- Aggregates importing consumes follows a single, clear dependency direction (domain functions call consumed APIs) that is safe to carry transitively
- The capability remains the only document type that can have all three sections inline

### Decision 6: Resolution order — consumes, aggregates, exposes, refs

**Choice**: Import resolution follows a strict order: consumes first, then aggregates (with recursive transitive consumes resolution), then exposes (simple load), then aggregate `ref` resolution.

**Rationale**:
- Consumes have no dependencies — they are self-contained (leaf)
- Aggregates depend on consumes (via `call`) — consumes must be resolved first, including any transitive consumes within the aggregates file itself
- Exposes depend on both consumes (via `call`) and aggregates (via `ref`) — both must be resolved first. Exposes are loaded as-is; no recursive resolution needed.
- Aggregate `ref` resolution runs last because it needs all three sections fully resolved

### Decision 7: Transitive imports limited to aggregates → consumes

**Context**: Should all standalone file types support transitive imports?

**Choice**: Only standalone aggregates files can import from lower layers (consumes). Standalone consumes and exposes files do not support transitive imports.

**Rationale**:
- Consumes are leaf documents — they have no dependencies to import
- Aggregates have a natural, single-direction dependency on consumes (domain functions call consumed APIs) — carrying it transitively is safe and useful
- Exposes depend on both aggregates and consumes — wiring all three is the capability's job, not the exposes file's. Allowing exposes to import aggregates would create a second wiring mechanism that competes with the capability
- Keeping the design narrow now allows extending it later if real use cases emerge — adding transitive imports to exposes would be strictly additive

### Decision 8: Cycle detection as a safety net

**Context**: If the layer DAG prevents cycles by construction, why implement cycle detection?

**Choice**: Implement cycle detection via an import stack, even though the layer DAG makes cycles structurally impossible for well-formed files.

**Rationale**:
- File system symlinks or path aliasing can create cycles invisible to the layer check
- A self-referencing file (imports itself) is a degenerate case that the layer check alone does not catch
- Future extensions (peer imports, bundled documents) may relax the layer constraint — the safety net is already in place
- The error message includes the full import chain, making debugging trivial

---

## 11. Migration & Backward Compatibility

### 11.1 No breaking changes

- Inline `exposes` and `aggregates` definitions continue to work exactly as before
- Existing capabilities are unaffected — the `ImportedExposes` and `ImportedAggregate` types are additive
- The schema root `oneOf` gains new branches; existing branches remain unchanged

### 11.2 Extraction workflow — exposes (modularization)

To extract an inline exposes adapter into a standalone file:

1. Create a new `.yml` file with the `naftiko` header and an `exposes` array
2. Move the adapter definition from the capability to the standalone file
3. Replace the inline definition with an import reference (`location` + `import`)
4. Ensure all `call` targets remain valid in the importing capability's `consumes` block
5. If the adapter uses `ref`, ensure the aggregate is defined in the importing capability
6. Lint both files independently

### 11.3 Extraction workflow — aggregates (modularization)

To extract inline aggregates into a standalone file:

1. Create a new `.yml` file with the `naftiko` header and an `aggregates` array
2. Move the aggregate definitions from the capability to the standalone file
3. Replace the inline definitions with import references (`location` + `import`)
4. Ensure all `call` targets remain valid in the importing capability's `consumes` block
5. Ensure all `ref` references in `exposes` still resolve (the namespace may have changed if `as` is used)
6. Lint both files independently

### 11.4 Contract-first workflow (reuse)

To define a predefined interface and share it across capabilities:

1. Author the standalone file (exposes or aggregates) with the standard contract
2. Define naming conventions for `call` targets (e.g. `weather-api.get-forecast`) — these are the consumed namespaces and operations that importing capabilities must provide
3. In each importing capability, add the `location` + `import` entry
4. In each importing capability, ensure `consumes` provides the namespaces and operations referenced by `call`
5. Lint each capability independently — import validation rules ensure all wiring is complete

### 11.5 Transitive extraction workflow

To make a standalone file self-contained by adding transitive imports:

1. Start with a working standalone aggregates file where `call` targets fall through to the importing capability
2. Identify which consumed namespaces the aggregate references (e.g. `weather-api`)
3. Add a `consumes` block with `location` + `import` entries pointing to standalone consumes files that provide those namespaces
4. Lint the standalone file independently — `call` targets now resolve locally
5. In importing capabilities, the `consumes` entries for those namespaces are no longer needed (transitive) — remove them unless other sections reference them directly
6. Repeat for exposes files: add `aggregates` and/or `consumes` import entries as needed

---

## 12. Future Evolutions

| Feature | Description | Prerequisite |
|---|---|---|
| **Port override** | Allow the importing capability to override the port for an imported exposes adapter | Use cases from production deployments |
| **Cherry-picking** | Import individual tools, resources, or functions instead of a full namespace | Schema extension for selective `import` syntax |
| **Exposes transitive imports** | Allow standalone exposes files to import aggregates and consumes, enabling self-contained adapter packages without a full capability | Use cases from production deployments; resolution ordering for three-level transitive chains; `mergeTransitiveAggregates` implementation |
| **Peer-to-peer imports** | Allow imports within the same layer (e.g. aggregates importing other aggregates) for composing modules horizontally | Cycle detection within same-layer graphs; resolution ordering rules for peer dependencies |
| **Bundled document** | A single file that defines multiple sections (e.g. aggregates + exposes) for cohesive domain packages | New document type or extended `oneOf` at root |
| **Override on transitive imports** | Allow the importing document to override specific properties (e.g. `baseUri`, `port`) on transitively imported entries | Override semantics and conflict resolution rules |
