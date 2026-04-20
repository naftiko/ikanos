# OpenAPI Specification Interoperability
## Import OAS into Consumes, Export OAS from REST Exposes — Java Core + Backstage Extension

**Status**: Proposal  
**Date**: April 10, 2026  
**Spec Version**: `1.0.0-alpha2`  
**Key Concept**: Bidirectional interoperability between OpenAPI Specification (OAS 3.0/3.1) and Naftiko capabilities — **import** an OAS document to scaffold an HTTP `consumes` adapter, and **export** an OAS document from a REST `exposes` adapter. Both directions are available via the Naftiko CLI and a Backstage plugin for platform-wide REST API reuse and catalog registration.

**Related blueprints**:
- [App Port Strategy](app-port-strategy.md) — §8 first introduced `naftiko generate openapi` as a one-way export; this proposal replaces §8.1 with a deeper, bidirectional design
- [Consumes Adapter Reuse](consumes-adapter-reuse.md) — standalone consumes files that OAS import produces
- [Exposes Adapter Reuse](exposes-adapter-reuse.md) — standalone exposes files that OAS export describes
- [Kubernetes & Backstage Governance](kubernetes-backstage-governance.md) — Backstage entity model that the OAS export feeds into

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Goals and Non-Goals](#2-goals-and-non-goals)
3. [OAS-to-Naftiko Mapping Reference](#3-oas-to-naftiko-mapping-reference)
4. [Import: OAS → HTTP Consumes Adapter](#4-import-oas--http-consumes-adapter)
5. [Export: REST Exposes Adapter → OAS](#5-export-rest-exposes-adapter--oas)
6. [CLI Surface](#6-cli-surface)
7. [Java Implementation — Swagger Parser](#7-java-implementation--swagger-parser)
8. [Backstage Integration](#8-backstage-integration)
9. [Schema Changes](#9-schema-changes)
10. [Validation Rules](#10-validation-rules)
11. [Testing Strategy](#11-testing-strategy)
12. [Implementation Roadmap](#12-implementation-roadmap)
13. [Design Decisions & Rationale](#13-design-decisions--rationale)
14. [Risks and Mitigations](#14-risks-and-mitigations)
15. [Acceptance Criteria](#15-acceptance-criteria)

---

## 1. Executive Summary

### What This Proposes

Two interconnected features that make Naftiko a first-class citizen in the OpenAPI ecosystem:

1. **OAS Import** (`naftiko import openapi`) — Parse an OpenAPI 3.0/3.1 document (YAML or JSON, local file or URL) using the Swagger Parser Java library, and generate a standalone Naftiko `consumes` YAML file with HTTP adapter definitions: `baseUri`, `namespace`, `resources`, `operations`, `inputParameters`, `outputParameters`, and `authentication`. This lets teams bootstrap a capability's consumes layer from an existing API spec instead of writing it by hand.

2. **OAS Export** (`naftiko export openapi`) — Read a Naftiko capability YAML file and generate an OpenAPI 3.1 document describing the REST `exposes` adapter: `paths`, `operations`, `parameters`, `requestBody`, `responses`, `securitySchemes`, and `components/schemas`. This unlocks client codegen, API gateway import, interactive documentation (Swagger UI / Redoc), and catalog registration.

3. **Backstage Plugin** (`@naftiko/backstage-plugin-openapi`) — A Backstage backend module that:
   - **Scaffolder action** (`naftiko:import:openapi`) — Backstage Scaffolder templates can invoke OAS import to bootstrap new capabilities from cataloged APIs
   - **Catalog processor** (`NaftikoOpenApiProcessor`) — Automatically registers the exported OAS document as a Backstage `API` entity (`spec.type: openapi`) linked to the capability `Component` entity via `providesApis`
   - **API page** — Frontend tab on the capability entity page rendering the exported OAS via Swagger UI

### What This Does NOT Do

- **No runtime OAS serving** — The OAS export is a build-time/CLI artifact, not a live endpoint served by the Naftiko REST adapter at runtime.
- **No OAS 2.0 (Swagger) support** — Only OAS 3.0+ is supported. The Swagger Parser library handles 2.0 but converts it to 3.0 internally; the import path normalizes on 3.0+ structures.
- **No GraphQL/gRPC/SSE export** — This proposal covers REST adapters only. The App Port Strategy covers AsyncAPI for SSE/WebSocket adapters.
- **No consumer-side code generation** — OAS export produces the spec; `openapi-generator` or `oapi-codegen` produce client SDKs. That tooling chain is external.
- **No automatic capability creation** — OAS import produces only the `consumes` section. The user still authors the `exposes` section, `aggregates`, and orchestration logic. A future Scaffolder template may compose both steps.
- **No cherry-picking of OAS paths** — Import converts the entire OAS document. Selective import (specific paths or tags) is a future iteration.

### Why This Matters

| Persona | Without OAS Interop | With OAS Interop |
|---------|---------------------|------------------|
| **Capability author** | Manually reads API docs, writes `consumes` YAML by hand, types every `inputParameter` and `outputParameter` | Runs `naftiko import openapi petstore.yaml`, gets a complete `consumes` YAML in seconds |
| **API consumer** | Reads Naftiko YAML to understand the REST surface, manually configures Postman/Insomnia collections | Runs `naftiko export openapi capability.yml`, imports OAS into Postman/API gateway/codegen tool |
| **Platform engineer** | Manually creates Backstage `API` entities for each capability's REST surface | Capability's OAS is auto-registered in Backstage catalog, linked to the capability `Component` |
| **oRPC / client SDK developer** | Writes TypeScript contract types by hand from Naftiko YAML | Generates typed client from the exported OAS via `openapi-ts`, `orval`, or `openapi-generator` |

---

## 2. Goals and Non-Goals

### Goals

| # | Goal | Measured By |
|---|------|-------------|
| G-1 | Import OAS 3.0/3.1 into a valid standalone Naftiko `consumes` YAML | Imported file passes `naftiko validate` and Spectral lint |
| G-2 | Export Naftiko REST `exposes` adapter into a valid OAS 3.1 document | Exported file passes OAS 3.1 schema validation |
| G-3 | Both features accessible via CLI commands | `naftiko import openapi` and `naftiko export openapi` execute successfully |
| G-4 | Java implementation uses the official Swagger Parser/Core libraries | No custom OAS parsing; `io.swagger.parser.v3` and `io.swagger.v3.oas.models` are the sole OAS manipulation layer |
| G-5 | Backstage Scaffolder action bootstraps capabilities from cataloged OAS | `naftiko:import:openapi` action works in Scaffolder templates |
| G-6 | Backstage catalog auto-registers exported OAS as `API` entity | Capability `Component` has `providesApis` relation to OAS `API` entity |
| G-7 | GraalVM native-image compatible | Import and export work in the native CLI binary |

### Non-Goals

| # | Non-Goal | Rationale |
|---|----------|-----------|
| NG-1 | Runtime OAS endpoint on REST adapter | Build-time CLI artifact is sufficient; runtime serving is not needed |
| NG-2 | OAS 2.0 (Swagger) import | Swagger Parser auto-upgrades 2.0 → 3.0; no separate 2.0 path needed |
| NG-3 | Selective path import | Full document import covers the primary use case; cherry-picking adds complexity |
| NG-4 | Round-trip fidelity (import → export → import = identical) | Naftiko's model is a superset (orchestration, steps, aggregates); lossless round-trip is not possible and not needed |
| NG-5 | OAS export for MCP/Skill/gRPC adapters | REST only; other protocols have their own spec formats |
| NG-6 | Automatic `exposes` generation from imported `consumes` | Would skip the orchestration design step; capability authors must design the exposed API surface |

---

## 3. OAS-to-Naftiko Mapping Reference

This section defines the canonical mapping between OAS 3.1 structures and Naftiko spec objects. Both Import and Export use this mapping as their source of truth — Import reads OAS and writes Naftiko; Export reads Naftiko and writes OAS.

### 3.1 Top-Level Structures

| OAS 3.1 | Naftiko (Consumes) | Naftiko (Exposes REST) | Notes |
|---------|-------------------|----------------------|-------|
| `info.title` | `namespace` (kebab-cased) | Capability `info.label` | Import: slugified via `toLowerCase().replaceAll("[^a-z0-9]+", "-")` |
| `info.version` | — | Capability version (if available) | Not mapped to consumes; informational |
| `info.description` | `description` | Capability `info.description` | |
| `servers[0].url` | `baseUri` | — | Import extracts scheme+host+basePath; Export not applicable (server is the Naftiko process) |
| `paths` | `resources` + `operations` | `resources` + `operations` | One resource per unique path template |
| `components.securitySchemes` | `authentication` | `authentication` | Mapped per scheme type |
| `components.schemas` | `outputParameters` (flattened) | `components.schemas` (exported) | Import flattens `$ref` chains; Export reconstructs schema objects |

### 3.2 Path & Operation Mapping

| OAS 3.1 | Naftiko Consumes | Naftiko Exposes REST | Notes |
|---------|-----------------|---------------------|-------|
| Path key (`/users/{id}`) | `resource.path` | `resource.path` | Verbatim; Mustache templates `{{id}}` replace `{id}` in consumes |
| HTTP method (`get`, `post`, ...) | `operation.method` | `operation.method` | Uppercased in Naftiko |
| `operationId` | `operation.name` (kebab-cased) | `operation.name` | If absent, synthesized from method + path |
| `summary` | `operation.label` | `operation.description` | |
| `description` | `operation.description` | `operation.description` | |
| `tags[0]` | `resource.name` (kebab-cased) | `resource.name` | Groups operations into resources by first tag |

### 3.3 Parameter Mapping

| OAS 3.1 Parameter | Naftiko `ConsumedInputParameter` | Naftiko `ExposedInputParameter` |
|-------------------|--------------------------------|-------------------------------|
| `name` | `name` (kebab-cased if needed) | `name` |
| `in: query` | `in: query` | `in: query` |
| `in: header` | `in: header` | `in: header` |
| `in: path` | `in: path` | `in: path` |
| `in: cookie` | `in: cookie` | `in: cookie` |
| `required` | — (always present for path) | `required` (on exposed params) |
| `schema.type` | — (no type on consumed input) | `type` (string, number, integer, boolean, array, object) |
| `schema.description` | — | `description` |

### 3.4 Request Body Mapping

| OAS 3.1 | Naftiko Consumes | Notes |
|---------|-----------------|-------|
| `requestBody.content["application/json"].schema` | `operation.body` or `inputParameters` with `in: body` | Import creates body parameters from schema properties |
| `requestBody.required` | — (body presence implies required) | |
| `requestBody.content["application/xml"]` | `outputRawFormat: xml` | Sets format accordingly |
| `requestBody.content["multipart/form-data"]` | `operation.body` · `inputParameters` with `in: body` (per form field) | Import creates one body parameter per top-level schema property. File uploads map to parameters with `type: file` (or `type: string` · `format: binary` until a dedicated file type exists). See §4.2 (Conversion Rules) for content-type-specific handling. |

### 3.5 Response & Output Parameter Mapping

| OAS 3.1 | Naftiko `ConsumedOutputParameter` | Notes |
|---------|--------------------------------|-------|
| `responses["200"].content["application/json"].schema` | `outputParameters[]` | Import walks the schema tree and generates `name` + `mapping` (JsonPath) pairs |
| Schema `type: object` with `properties` | Nested `outputParameters` with `type: object` | Each property becomes a child output parameter |
| Schema `type: array` with `items` | `outputParameters` with `type: array` + `items` | Array items define the element schema |
| Schema `$ref: "#/components/schemas/X"` | Resolved inline | Swagger Parser dereferences `$ref`; Naftiko flattens |

### 3.6 Authentication Mapping

| OAS 3.1 `securitySchemes` | Naftiko `authentication` | Notes |
|--------------------------|-------------------------|-------|
| `type: apiKey`, `in: header`, `name: X-API-Key` | `type: apikey`, `in: header`, `name: X-API-Key` | Direct mapping |
| `type: http`, `scheme: basic` | `type: basic` | |
| `type: http`, `scheme: bearer` | `type: bearer` | |
| `type: http`, `scheme: digest` | `type: digest` | |
| `type: oauth2` | — | Not yet supported in Naftiko; Import emits a warning comment |
| `type: openIdConnect` | — | Not yet supported; Import emits a warning comment |

---

## 4. Import: OAS → HTTP Consumes Adapter

### 4.1 Pipeline Architecture

```
┌────────────┐     ┌──────────────┐     ┌────────────────┐     ┌──────────────┐
│  OAS Input │────►│ Swagger      │────►│  Naftiko IR    │────►│  YAML        │
│  (YAML/    │     │ Parser       │     │  (ConsumesSpec │     │  Emitter     │
│   JSON /   │     │ (resolve +   │     │   object tree) │     │  (Jackson    │
│   URL)     │     │  validate)   │     │                │     │   YAML)      │
└────────────┘     └──────────────┘     └────────────────┘     └──────────────┘
                          │                      │
                    Swagger v3 models      OasImportConverter
                    (OpenAPI POJO)         (mapping logic)
```

### 4.2 Conversion Rules

**Namespace derivation:**
- Source: `info.title`
- Transform: `toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "")`
- Override: `--namespace <name>` CLI flag

**BaseUri derivation:**
- Source: `servers[0].url`
- Transform: Strip trailing slash; resolve relative paths against import context
- If `servers` is empty: emit placeholder `https://api.example.com` with a Spectral warning

**Resource grouping:**
- Primary: Group operations by first `tag` → one `ConsumedHttpResource` per tag
- Fallback (no tags): Group by first path segment → `/users/{id}` and `/users` share resource `users`
- Resource `name`: first tag (kebab-cased) or first path segment
- Resource `path`: common path prefix for the group (or `/` if no common prefix)

**Operation mapping:**
- `name`: `operationId` (kebab-cased) or synthesized `{method}-{path-slug}`
- `method`: HTTP method (uppercased)
- `label`: `summary` (if present)
- `description`: `description` (if present)

**Input parameter mapping:**
- Path parameters → `in: path`; name is kebab-cased
- Query parameters → `in: query`
- Header parameters → `in: header`
- Cookie parameters → `in: cookie`
- Request body properties → `in: body` (one parameter per top-level body property) or a single body parameter for non-object schemas

**Output parameter mapping (from success response schema):**
- Scalar properties → `ConsumedOutputParameter` with `name`, `type`, `mapping: "$.{propertyName}"`
- Object properties → nested `ConsumedOutputParameter` with `type: object` and child `outputParameters`
- Array properties → `ConsumedOutputParameter` with `type: array`, `items` describing the element schema
- `$ref` chains are pre-resolved by Swagger Parser; no `$ref` appears in Naftiko output
- Properties with `readOnly: true` are included (they are API output)
- Properties with `writeOnly: true` are excluded (they are API input only)

**Authentication mapping:**
- First matching `securitySchemes` entry is mapped (single auth per consumed adapter)
- Unsupported types (`oauth2`, `openIdConnect`) emit a YAML comment: `# TODO: oauth2 authentication not yet supported — configure manually`

**Binds / secret injection:**
- OAS import does **not** auto-generate `binds` entries. OpenAPI documents describe *protocol + contract* and typically cannot (and should not) declare credential sources.
- Instead, the importer leaves credential values blank/placeholder and expects the user to wire them via `binds` (e.g., `token: "NOTION_TOKEN"`, `X-API-Key: "API_KEY"`).
- Future enhancement: optional heuristics to propose `binds` keys based on detected auth schemes / common header names, behind a flag (e.g., `--emit-binds`).

### 4.3 Output Format

The import produces a **standalone consumes YAML file** matching the format defined in [consumes-adapter-reuse.md](consumes-adapter-reuse.md):

```yaml
naftiko: "1.0.0-alpha2"
consumes:
  namespace: petstore
  type: http
  description: "Imported from Petstore API (OAS 3.0)"
  baseUri: "https://petstore.swagger.io/v2"
  authentication:
    type: apikey
    in: header
    name: api_key
  resources:
    - name: pets
      path: /pets
      operations:
        - name: list-pets
          method: GET
          label: List all pets
          description: Returns all pets from the store
          inputParameters:
            - name: limit
              in: query
          outputParameters:
            - name: id
              type: number
              mapping: "$[*].id"
            - name: name
              type: string
              mapping: "$[*].name"
            - name: tag
              type: string
              mapping: "$[*].tag"
        - name: create-pet
          method: POST
          label: Create a pet
          body:
            type: json
          inputParameters:
            - name: name
              in: body
            - name: tag
              in: body
    - name: pet-details
      path: "/pets/{{pet-id}}"
      operations:
        - name: get-pet-by-id
          method: GET
          label: Info for a specific pet
          inputParameters:
            - name: pet-id
              in: path
          outputParameters:
            - name: id
              type: number
              mapping: "$.id"
            - name: name
              type: string
              mapping: "$.name"
            - name: tag
              type: string
              mapping: "$.tag"
```

### 4.4 Edge Cases

| Scenario | Behavior |
|----------|----------|
| OAS uses `$ref` to external files | Swagger Parser resolves external refs; import works transparently |
| OAS has no `servers` | `baseUri` set to `https://api.example.com`; Spectral `naftiko-baseuri-not-example` will warn |
| OAS has multiple servers | First server used; others listed in a YAML comment |
| Operation has no `operationId` | Synthesized: `{method}-{pathSlug}` (e.g., `get-users-by-id`) |
| Response schema uses `allOf` / `oneOf` / `anyOf` | `allOf` → merged properties; `oneOf`/`anyOf` → first variant with a YAML comment noting alternatives |
| Response is non-JSON (`text/plain`, `application/xml`) | `outputRawFormat` set accordingly; output parameters derived from schema if present |
| Circular `$ref` | Swagger Parser handles cycles; import truncates at depth 5 with a YAML comment |
| `additionalProperties: true` | Emitted as `type: object` with no child output parameters; YAML comment notes dynamic shape |
| Enum values on parameters | Preserved in YAML comment for documentation; not enforced in Naftiko schema |

---

## 5. Export: REST Exposes Adapter → OAS

### 5.1 Pipeline Architecture

```
┌────────────────┐     ┌──────────────────┐     ┌──────────────┐     ┌───────────┐
│  Capability    │────►│  Naftiko IR      │────►│  Swagger     │────►│  OAS      │
│  YAML          │     │  (RestServerSpec │     │  Core Models │     │  Output   │
│                │     │   object tree)   │     │  (OpenAPI    │     │  (YAML /  │
│                │     │                  │     │   POJO)      │     │   JSON)   │
└────────────────┘     └──────────────────┘     └──────────────┘     └───────────┘
                              │                        │
                        Jackson YAML             OasExportBuilder
                        deserialization          (mapping logic)
```

### 5.2 Conversion Rules

**Info block:**
- `info.title`: from capability `info.label`
- `info.version`: from spec version `naftiko` field or `"1.0.0"` default
- `info.description`: from capability `info.description`

**Server block:**
- `servers[0].url`: `http://{address}:{port}` from the REST adapter's `address` and `port`
- If `address` is `0.0.0.0`: use `http://localhost:{port}` in the spec (practical for local dev)

**Paths:**
- One `PathItem` per `ExposedResource`
- Path template: resource `path` verbatim (Naftiko already uses `{param}` syntax for path params)
- Each `ExposedOperation` becomes a method entry on the `PathItem`

**Operations:**
- `operationId`: operation `name`
- `summary`/`description`: from operation `description`
- `tags`: `[resource.name]`

**Parameters (from `ExposedInputParameter`):**
- `in: query` → OAS `Parameter` with `in: query`
- `in: path` → OAS `Parameter` with `in: path`, `required: true`
- `in: header` → OAS `Parameter` with `in: header`
- `in: cookie` → OAS `Parameter` with `in: cookie`
- `in: body` → contributes to `requestBody` (see below)
- `schema.type`: from parameter `type`
- `schema.description`: from parameter `description`

**Request Body (from body-located input parameters):**
- If any `inputParameter` has `in: body`, a `requestBody` is generated
- For `POST`/`PUT`/`PATCH` methods: body parameters become properties of a `requestBody` JSON schema
- Content type: `application/json` (default)

**Responses (from `outputParameters`):**
- `2XX` success response with `application/json` content (covers `200`, `201`, etc.)
- Schema built from `outputParameters` tree:
  - Each `MappedOutputParameter` or `OrchestratedOutputParameter` becomes a schema property
  - `type: object` with nested `outputParameters` → nested `properties`
  - `type: array` with `items` → `array` schema with `items`
- If there are no output parameters, emit `204` (no content) for success where appropriate
- Error responses: `400` (validation), `500` (internal) with generic schemas

**Security (from `authentication`):**
- `type: apikey` → `securitySchemes.apiKey` with `in` and `name`
- `type: basic` → `securitySchemes.basicAuth` with `scheme: basic`
- `type: bearer` → `securitySchemes.bearerAuth` with `scheme: bearer`
- `type: digest` → `securitySchemes.digestAuth` with `scheme: digest`
- Applied globally via top-level `security` array

**Aggregate resolution:**
- Operations using `ref` to aggregate functions: the function's `inputParameters`, `outputParameters`, `steps`, and `description` are resolved before export
- The exported OAS reflects the fully-resolved operation surface, not the `ref` indirection

### 5.3 Output Example

```yaml
openapi: "3.1.0"
info:
  title: "Notion Page Creator"
  version: "1.0.0-alpha2"
  description: "Creates and manages Notion pages with rich content formatting"
servers:
  - url: "http://localhost:8080"
paths:
  /pages:
    post:
      operationId: create-page
      summary: "Create a Notion page"
      tags:
        - pages
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                title:
                  type: string
                  description: "Page title"
                content:
                  type: string
                  description: "Page content in Markdown"
              required:
                - title
      responses:
        "200":
          description: "Successful response"
          content:
            application/json:
              schema:
                type: object
                properties:
                  id:
                    type: string
                  url:
                    type: string
                  createdTime:
                    type: string
        "400":
          description: "Validation error"
        "500":
          description: "Internal server error"
  /users:
    get:
      operationId: list-users
      summary: "List all users in the workspace"
      tags:
        - users
      parameters:
        - name: limit
          in: query
          schema:
            type: integer
          description: "Maximum number of results"
      responses:
        "200":
          description: "Successful response"
          content:
            application/json:
              schema:
                type: object
                properties:
                  data:
                    type: array
                    items:
                      type: object
                      properties:
                        id:
                          type: string
                        name:
                          type: string
                        email:
                          type: string
components:
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
security:
  - bearerAuth: []
```

### 5.4 Edge Cases

| Scenario | Behavior |
|----------|----------|
| Capability has multiple REST adapters | One OAS document per REST adapter; `--adapter <namespace>` CLI flag selects which |
| Operation uses `steps` (orchestrated) | Final `outputParameters` are exported; intermediate step details are not exposed |
| Output parameter uses `value` (mock) | Exported as schema `default` value |
| Output parameter has no explicit `type` | Inferred as `string` (Naftiko default) |
| Operation has no output parameters | Response `200` with empty schema `{}` |
| Forward-mode operation | Exported as pass-through; response schema is `{}` (dynamic) |
| `ref` to aggregate function | Fully resolved — exported OAS shows the concrete parameters |

---

## 6. CLI Surface

### 6.1 Import Command

```
naftiko import openapi [OPTIONS] <source>

Arguments:
  <source>      Path or URL to the OAS document (YAML or JSON)

Options:
  --output, -o <file>       Output file path (default: ./<namespace>-consumes.yml)
  --namespace, -n <name>    Override the derived namespace
  --format, -f <yaml|json>  Output format (default: yaml)
  --auth-type <type>        Override authentication type detection
  --help, -h                Show help
```

**Examples:**

```bash
# Import from local file
naftiko import openapi ./petstore.yaml

# Import from URL
naftiko import openapi https://petstore3.swagger.io/api/v3/openapi.json

# Import with custom namespace and output path
naftiko import openapi ./github-api.yaml -n github -o ./consumes/github.yml
```

**Exit codes:**
- `0` — Import successful
- `1` — OAS parse error (invalid document)
- `2` — OAS validation warnings (document imported with warnings printed to stderr)

### 6.2 Export Command

```
naftiko export openapi [OPTIONS] <capability>

Arguments:
  <capability>  Path to the Naftiko capability YAML file

Options:
  --output, -o <file>       Output file path (default: ./openapi.yaml)
  --adapter, -a <namespace> Select REST adapter by namespace (required if multiple REST adapters)
  --format, -f <yaml|json>  Output format (default: yaml)
  --server-url <url>        Override the server URL in the OAS output
  --help, -h                Show help
```

**Examples:**

```bash
# Export from capability
naftiko export openapi ./notion-capability.yml

# Export as JSON with custom server URL
naftiko export openapi ./notion-capability.yml -f json --server-url https://api.mycompany.com

# Export specific adapter when multiple REST adapters exist
naftiko export openapi ./multi-adapter.yml -a notion-rest -o ./notion-openapi.yaml
```

**Exit codes:**
- `0` — Export successful
- `1` — Capability parse error or no REST adapter found
- `2` — Export completed with warnings (e.g., unresolvable `ref`)

### 6.3 CLI Command Hierarchy

Current structure:

```
naftiko
├── create
│   └── capability
└── validate
```

Proposed structure:

```
naftiko
├── create
│   └── capability
├── validate
├── import                    ← NEW command group
│   └── openapi               ← OAS import subcommand
└── export                    ← NEW command group
    └── openapi               ← OAS export subcommand
```

The `import` and `export` groups are extensible — future subcommands like `import asyncapi`, `export asyncapi`, `export proto` can be added without restructuring the CLI.

> **Note on `generate` vs `export`:** The App Port Strategy uses `naftiko generate openapi`. This proposal uses `naftiko export openapi` to signal **bidirectionality** — `import` and `export` are symmetric operations on the same OAS format. The `generate` verb is reserved for code generation commands (`generate n8n`, `generate zapier`) that produce executable artifacts rather than specification documents.

---

## 7. Java Implementation — Swagger Parser

### 7.1 Dependencies

```xml
<!-- OpenAPI: Swagger Parser (reads OAS 3.0/3.1, resolves $ref, validates) -->
<dependency>
    <groupId>io.swagger.parser.v3</groupId>
    <artifactId>swagger-parser</artifactId>
    <version>2.1.25</version>
</dependency>

<!-- OpenAPI: Swagger Core Models (OAS POJO model for building OAS documents) -->
<!-- Transitive from swagger-parser, but declared explicitly for export usage -->
<dependency>
    <groupId>io.swagger.core.v3</groupId>
    <artifactId>swagger-models</artifactId>
    <version>2.2.28</version>
</dependency>
```

**Swagger Parser** (`io.swagger.parser.v3:swagger-parser`) is the official SmartBear library for parsing, validating, and resolving OpenAPI documents. It handles:
- YAML and JSON input formats
- Local file and URL resolution
- `$ref` dereferencing (internal and external)
- OAS 2.0 → 3.0 automatic upgrade
- OAS 3.0 and 3.1 native parsing

**Swagger Core Models** (`io.swagger.core.v3:swagger-models`) provides the POJO model (`OpenAPI`, `PathItem`, `Operation`, `Schema`, etc.) for programmatic construction of OAS documents — used by the export path.

### 7.2 Class Diagram

```
io.naftiko.openapi
├── OasImportConverter             ← OAS → Naftiko consumes
│   ├── convert(OpenAPI) → HttpClientSpec
│   ├── mapPaths(Paths) → List<HttpClientResourceSpec>
│   ├── mapOperation(PathItem.HttpMethod, Operation) → HttpClientOperationSpec
│   ├── mapParameters(List<Parameter>) → List<ConsumedInputParameterSpec>
│   ├── mapResponseSchema(ApiResponse) → List<ConsumedOutputParameterSpec>
│   └── mapAuthentication(SecurityScheme) → AuthenticationSpec
│
├── OasExportBuilder               ← Naftiko exposes → OAS
│   ├── build(CapabilitySpec, String adapterNamespace) → OpenAPI
│   ├── buildPaths(List<RestServerResourceSpec>) → Paths
│   ├── buildOperation(RestServerOperationSpec) → Operation
│   ├── buildParameters(List<ExposedInputParameterSpec>) → List<Parameter>
│   ├── buildResponseSchema(List<OutputParameterSpec>) → Schema
│   └── buildSecuritySchemes(AuthenticationSpec) → SecurityScheme
│
├── OasImportResult                ← Import result with warnings
│   ├── consumesSpec: HttpClientSpec
│   └── warnings: List<String>
│
├── OasExportResult                ← Export result with warnings
│   ├── openApi: OpenAPI
│   └── warnings: List<String>
│
└── OasYamlWriter                  ← Serializes OpenAPI POJO to YAML/JSON
    ├── writeYaml(OpenAPI, Path)
    └── writeJson(OpenAPI, Path)
```

### 7.3 Import Implementation Sketch

```java
package io.naftiko.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

class OasImportConverter {

    OasImportResult convert(String source) {
        SwaggerParseResult parseResult = new OpenAPIParser().readLocation(source, null, null);
        OpenAPI openApi = parseResult.getOpenAPI();
        List<String> warnings = new ArrayList<>(parseResult.getMessages());

        if (openApi == null) {
            throw new IllegalArgumentException("Failed to parse OAS: " + warnings);
        }

        HttpClientSpec consumes = new HttpClientSpec();
        consumes.setNamespace(deriveNamespace(openApi.getInfo()));
        consumes.setType("http");
        consumes.setDescription("Imported from " + openApi.getInfo().getTitle() + " (OAS)");
        consumes.setBaseUri(deriveBaseUri(openApi.getServers(), warnings));
        consumes.setAuthentication(mapAuthentication(openApi, warnings));
        consumes.setResources(mapPaths(openApi.getPaths()));

        return new OasImportResult(consumes, warnings);
    }

    // ... mapping methods per §3 and §4.2
}
```

### 7.4 Export Implementation Sketch

```java
package io.naftiko.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.media.Schema;

class OasExportBuilder {

    OasExportResult build(CapabilitySpec capability, String adapterNamespace) {
        RestServerSpec restAdapter = findRestAdapter(capability, adapterNamespace);
        List<String> warnings = new ArrayList<>();

        OpenAPI openApi = new OpenAPI();
        openApi.setInfo(buildInfo(capability));
        openApi.setServers(buildServers(restAdapter));
        openApi.setPaths(buildPaths(restAdapter.getResources()));
        openApi.setComponents(buildComponents(restAdapter, warnings));
        applySecurity(openApi, restAdapter.getAuthentication());

        return new OasExportResult(openApi, warnings);
    }

    // ... builder methods per §3 and §5.2
}
```

### 7.5 GraalVM Native-Image Considerations

Swagger Parser uses Jackson internally, which is already in the Naftiko dependency tree with GraalVM reflection configuration. Key concerns:

| Library | GraalVM Risk | Mitigation |
|---------|-------------|------------|
| `swagger-parser` | Uses `ServiceLoader` for plugin discovery | Add `META-INF/native-image` resource config for service implementations |
| `swagger-models` | POJO model — no reflection issues | Safe |
| Jackson (transitive) | Already configured for GraalVM in Naftiko | No change needed |
| URL resolution (for remote OAS) | Uses `java.net.URL` — safe in native image | No change needed |

The native-image reflection configuration will be validated during the testing phase (§12).

---

## 8. Backstage Integration

### 8.1 Architecture

```
┌───────────────────────────────────────────────────────────────────────┐
│                           BACKSTAGE                                   │
│                                                                       │
│  ┌─────────────────────────┐   ┌──────────────────────────────────┐  │
│  │  Scaffolder             │   │  Catalog                          │  │
│  │                         │   │                                    │  │
│  │  Template:              │   │  ┌─────────────┐  providesApis    │  │
│  │  "Create capability     │   │  │ Component   │───────────────►  │  │
│  │   from OpenAPI"         │   │  │ (capability)│  ┌────────────┐  │  │
│  │                         │   │  └─────────────┘  │ API entity │  │  │
│  │  Steps:                 │   │                    │ (openapi)  │  │  │
│  │  1. fetch:plain (OAS)   │   │                    └────────────┘  │  │
│  │  2. naftiko:import:oas  │   │                          ▲         │  │
│  │  3. publish:github      │   │                          │         │  │
│  │                         │   │            NaftikoOpenApiProcessor  │  │
│  └─────────────────────────┘   └──────────────────────────────────┘  │
│                                                                       │
│  ┌──────────────────────────────────────────────────────────────────┐ │
│  │  Entity Page — API Tab                                            │ │
│  │  ┌──────────────────────────────────────────────────────────┐     │ │
│  │  │  Swagger UI (embedded)                                    │     │ │
│  │  │  Rendered from the registered OAS API entity               │     │ │
│  │  └──────────────────────────────────────────────────────────┘     │ │
│  └──────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────┘
```

### 8.2 Scaffolder Action: `naftiko:import:openapi`

A custom Backstage Scaffolder action that wraps the OAS import logic. This runs in the Backstage backend (Node.js), calling the Naftiko CLI binary (`naftiko import openapi`) via `child_process.execFile`.

**TypeScript action definition:**

```typescript
import { createTemplateAction } from '@backstage/plugin-scaffolder-node';

export const naftikoImportOpenApiAction = createTemplateAction<{
  source: string;        // URL or inline OAS content
  namespace?: string;    // Override namespace
  outputPath?: string;   // Output file path in workspace
}>({
  id: 'naftiko:import:openapi',
  description: 'Import an OpenAPI spec into a Naftiko consumes adapter YAML',
  schema: {
    input: {
      type: 'object',
      required: ['source'],
      properties: {
        source: { type: 'string', description: 'URL or path to OAS document' },
        namespace: { type: 'string', description: 'Override namespace' },
        outputPath: { type: 'string', default: 'consumes.yml' },
      },
    },
  },
  async handler(ctx) {
    // Call Naftiko CLI to perform import
    // Write result to ctx.workspacePath / outputPath
  },
});
```

**Scaffolder template using the action:**

```yaml
apiVersion: scaffolder.backstage.io/v1beta3
kind: Template
metadata:
  name: naftiko-capability-from-openapi
  title: Create Naftiko Capability from OpenAPI
  description: Bootstrap a capability's consumes adapter from a cataloged API's OpenAPI spec
  tags:
    - naftiko
    - openapi
    - api-reuse
spec:
  owner: platform-team
  type: capability
  parameters:
    - title: Select API
      properties:
        apiEntityRef:
          title: API
          type: string
          ui:field: EntityPicker
          ui:options:
            catalogFilter:
              kind: API
              spec.type: openapi
        capabilityName:
          title: Capability Name
          type: string
        exposedPort:
          title: Exposed Port
          type: integer
          default: 8080
  steps:
    - id: fetch-oas
      name: Fetch OpenAPI spec from catalog
      action: catalog:fetch
      input:
        entityRef: ${{ parameters.apiEntityRef }}
    - id: import-oas
      name: Import OAS into Naftiko consumes
      action: naftiko:import:openapi
      input:
        source: ${{ steps['fetch-oas'].output.definition }}
        namespace: ${{ parameters.capabilityName }}
    - id: scaffold-capability
      name: Scaffold capability YAML
      action: fetch:template
      input:
        url: ./skeleton
        values:
          name: ${{ parameters.capabilityName }}
          port: ${{ parameters.exposedPort }}
          consumesFile: ${{ steps['import-oas'].output.file }}
    - id: publish
      name: Publish to GitHub
      action: publish:github:pull-request
      input:
        repoUrl: github.com?owner=myorg&repo=capabilities
        title: "feat: add ${{ parameters.capabilityName }} capability"
        branchName: feat/${{ parameters.capabilityName }}
```

### 8.2.1 Consuming multiple catalog APIs into one capability (no OpenAPI merge)

This proposal can support bootstrapping a single capability that **consumes multiple external REST APIs** sourced from the Backstage catalog.

**Principle:** Do **not** merge OpenAPI documents. Instead, import each selected API into its **own** standalone Naftiko `consumes` HTTP adapter YAML (one file per API), and let the capability orchestrate across them as needed.

Two implementation options are supported:

1. **Multiple steps (no new Naftiko CLI surface)**
   - In the Scaffolder template, allow selecting multiple Backstage `API` entities.
   - For each selected API: fetch its OAS definition, then run `naftiko import openapi` to generate a distinct consumes file (e.g., `<api-namespace>-consumes.yml`).
   - The capability skeleton references/includes all generated consumes files.

2. **Batch import action (Backstage-only convenience)**
   - Extend the Backstage action to accept `sources: string[]` (or `apiEntityRefs: string[]`) and return `files: string[]`.
   - Internally, the action loops and invokes the existing CLI once per source.
   - No change is required to the Naftiko CLI; it remains `naftiko import openapi <source>`.

**Notes / constraints:**

- Each imported API retains its own `baseUri`, `authentication`, and namespace.
- Name collisions are avoided by keeping each API in its own consumes adapter namespace.
- This feature is intentionally separate from any future "merge multiple OpenAPI specs" functionality, which is out of scope.

### 8.3 Catalog Processor: `NaftikoOpenApiProcessor`

A Backstage catalog processor that automatically creates `API` entities from capabilities that expose REST adapters:

**Processing flow:**

1. When a Naftiko `Component` entity is processed (discovered via CRD or Git)
2. Check if the capability has an `exposes` adapter with `type: rest`
3. If yes, run OAS export (via CLI or cached artifact)
4. Emit an `API` entity with `spec.type: openapi` and the exported OAS as `spec.definition`
5. Link to the `Component` via `providesApis` relation

**Emitted API entity:**

```yaml
apiVersion: backstage.io/v1alpha1
kind: API
metadata:
  name: notion-page-creator-rest-api
  description: "Auto-generated OpenAPI spec for Notion Page Creator REST adapter"
  annotations:
    naftiko.io/capability: notion-page-creator
    naftiko.io/adapter-namespace: notion-rest
  tags:
    - naftiko
    - auto-generated
spec:
  type: openapi
  lifecycle: production
  owner: platform-team
  definition: |
    openapi: "3.1.0"
    info:
      title: "Notion Page Creator"
      ...
```

### 8.4 API Page — Swagger UI Tab

The existing Backstage `@backstage/plugin-api-docs` frontend plugin renders OAS entities via Swagger UI. No custom frontend code is needed — registering the `API` entity with `spec.type: openapi` is sufficient.

The capability entity page gains:

- **"APIs" tab** — lists the `providesApis` relations (auto-populated by the processor)
- **Click-through** — clicking an API entity opens the Swagger UI rendering
- **Topology** — the API entity appears in dependency graphs, showing which capabilities provide which REST APIs

### 8.5 REST API Reuse via Scaffolding

The Backstage integration closes the loop on REST API reuse:

```
┌──────────────┐                    ┌──────────────┐
│  Team A      │                    │  Team B      │
│  publishes   │                    │  discovers   │
│  capability  │                    │  API in      │
│  with REST   │───── export ──────►│  Backstage   │
│  adapter     │      (auto)        │  catalog     │
└──────────────┘                    └──────┬───────┘
                                           │
                                    scaffold from
                                    cataloged API
                                           │
                                    ┌──────▼───────┐
                                    │  Team B's    │
                                    │  new          │
                                    │  capability   │
                                    │  (consumes    │
                                    │   Team A's    │
                                    │   API)        │
                                    └──────────────┘
```

1. **Team A** publishes a capability with a REST adapter → OAS is auto-registered as an `API` entity
2. **Team B** discovers Team A's API in the Backstage catalog
3. **Team B** uses the "Create Capability from OpenAPI" Scaffolder template, selecting Team A's API
4. The Scaffolder runs `naftiko:import:openapi` → produces a `consumes` YAML pre-configured for Team A's REST adapter
5. **Team B** authors their `exposes` logic on top of the imported `consumes`

This is **spec-driven API reuse** — no manual URL copying, no Postman collection sharing, no Slack messages asking "what's the endpoint for..."

---

## 9. Schema Changes

### 9.1 No Changes to `naftiko-schema.json`

OAS interoperability is a **tooling feature**, not a spec feature. The OAS import produces standard `consumes` YAML; the OAS export reads standard `exposes` YAML. No new schema types are required.

---

## 10. Validation Rules

### 10.1 Import Validation

The imported consumes YAML is validated using the existing validation pipeline:

1. **JSON Schema validation** — `naftiko validate <imported-file>` against `naftiko-schema.json`
2. **Spectral linting** — existing rules apply (`naftiko-baseuri-not-example`, `naftiko-consumes-description`, etc.)

No new Spectral rules are needed for the import feature itself.

### 10.2 Export Validation

The exported OAS document is validated using:

1. **OAS 3.1 schema validation** — the Swagger Parser library validates the constructed `OpenAPI` POJO before serialization
2. **Semantic validation** — custom checks:
   - Every path has at least one operation
   - Every path parameter appears in the path template
   - `operationId` values are unique across the document
   - Security scheme references exist in `components.securitySchemes`

### 10.3 Backstage-Side Validation

The `NaftikoOpenApiProcessor` validates the exported OAS before emitting an `API` entity:
- If OAS validation fails, the processor logs a warning and skips `API` entity emission (the `Component` entity is still emitted)
- This prevents broken OAS specs from entering the Backstage catalog

---

## 11. Testing Strategy

### 11.1 Unit Tests — Import

| Test | Class | What It Verifies |
|------|-------|------------------|
| `importShouldDeriveNamespaceFromTitle` | `OasImportConverterTest` | `info.title: "Pet Store"` → `namespace: pet-store` |
| `importShouldExtractBaseUriFromFirstServer` | `OasImportConverterTest` | `servers[0].url` → `baseUri` |
| `importShouldUsePlaceholderWhenNoServers` | `OasImportConverterTest` | Empty servers → `https://api.example.com` + warning |
| `importShouldMapPathParametersToMustacheTemplate` | `OasImportConverterTest` | `/pets/{petId}` → `/pets/{{pet-id}}` |
| `importShouldGroupOperationsByTag` | `OasImportConverterTest` | Operations tagged `pets` grouped into one resource |
| `importShouldFallbackToPathSegmentGrouping` | `OasImportConverterTest` | No tags → first path segment as resource name |
| `importShouldMapQueryParameters` | `OasImportConverterTest` | OAS query param → `in: query` |
| `importShouldMapRequestBodyToBodyParameters` | `OasImportConverterTest` | `requestBody` schema → `in: body` parameters |
| `importShouldMapResponseSchemaToOutputParameters` | `OasImportConverterTest` | 200 response schema → `outputParameters` with JsonPath `mapping` |
| `importShouldHandleNestedObjectSchemas` | `OasImportConverterTest` | Nested `$ref` → nested output parameters |
| `importShouldHandleArraySchemas` | `OasImportConverterTest` | Array response → `type: array` with `items` |
| `importShouldMapApiKeyAuthentication` | `OasImportConverterTest` | `securitySchemes.apiKey` → `authentication.type: apikey` |
| `importShouldMapBasicAuthentication` | `OasImportConverterTest` | `http/basic` → `type: basic` |
| `importShouldMapBearerAuthentication` | `OasImportConverterTest` | `http/bearer` → `type: bearer` |
| `importShouldWarnOnOauth2Authentication` | `OasImportConverterTest` | `oauth2` → warning in result |
| `importShouldSynthesizeOperationIdWhenMissing` | `OasImportConverterTest` | No `operationId` → `get-pets-by-id` pattern |
| `importShouldHandleAllOfByMergingProperties` | `OasImportConverterTest` | `allOf` → merged output parameters |
| `importShouldHandleOneOfByPickingFirstVariant` | `OasImportConverterTest` | `oneOf` → first variant + warning |

### 11.2 Unit Tests — Export

| Test | Class | What It Verifies |
|------|-------|------------------|
| `exportShouldSetInfoFromCapability` | `OasExportBuilderTest` | `info.label` → `info.title`, `info.description` → `info.description` |
| `exportShouldBuildServerFromAdapterAddress` | `OasExportBuilderTest` | `address: 0.0.0.0`, `port: 8080` → `http://localhost:8080` |
| `exportShouldBuildPathsFromResources` | `OasExportBuilderTest` | REST resources → OAS `paths` |
| `exportShouldBuildOperationsWithCorrectMethods` | `OasExportBuilderTest` | `method: POST` → OAS `post` on path item |
| `exportShouldMapQueryInputToOasParameter` | `OasExportBuilderTest` | `in: query` → OAS `Parameter(in=query)` |
| `exportShouldMapPathInputToRequiredOasParameter` | `OasExportBuilderTest` | `in: path` → OAS `Parameter(in=path, required=true)` |
| `exportShouldMapBodyInputToRequestBody` | `OasExportBuilderTest` | `in: body` → OAS `requestBody` schema |
| `exportShouldBuildResponseSchemaFromOutputParameters` | `OasExportBuilderTest` | Output params → 200 response schema |
| `exportShouldBuildNestedObjectSchema` | `OasExportBuilderTest` | Nested output → nested OAS `properties` |
| `exportShouldBuildArraySchema` | `OasExportBuilderTest` | Array output → OAS `array` with `items` |
| `exportShouldMapApiKeyAuthentication` | `OasExportBuilderTest` | `type: apikey` → OAS `securitySchemes` |
| `exportShouldMapBearerAuthentication` | `OasExportBuilderTest` | `type: bearer` → OAS `http/bearer` scheme |
| `exportShouldResolveAggregateRefs` | `OasExportBuilderTest` | `ref: ns.func` → resolved params in OAS |
| `exportShouldSelectAdapterByNamespace` | `OasExportBuilderTest` | Multiple REST adapters → correct one selected |
| `exportShouldProduceValidOas31` | `OasExportBuilderTest` | Exported `OpenAPI` POJO passes Swagger validation |

### 11.3 Integration Tests

| Test | Class | What It Verifies |
|------|-------|------------------|
| `importPetstoreShouldProduceValidConsumes` | `OasImportIntegrationTest` | Petstore OAS → consumes YAML → passes `naftiko validate` |
| `importGitHubApiShouldHandleComplexSchemas` | `OasImportIntegrationTest` | Complex OAS with nested objects, arrays, `allOf` → valid consumes |
| `exportNotionCapabilityShouldProduceValidOas` | `OasExportIntegrationTest` | Notion capability → OAS 3.1 → passes OAS schema validation |
| `exportCapabilityWithAggregatesShouldResolveRefs` | `OasExportIntegrationTest` | Aggregate-using capability → fully resolved OAS |
| `roundTripShouldPreserveOperationStructure` | `OasRoundTripIntegrationTest` | Export → Import → compare: operations, parameters, and auth match |

### 11.4 Test Fixtures

New test resources under `src/test/resources/`:

| File | Purpose |
|------|---------|
| `openapi/petstore-3.0.yaml` | Standard Petstore OAS 3.0 for import tests |
| `openapi/petstore-3.1.yaml` | Petstore OAS 3.1 (JSON Schema vocabulary) for import tests |
| `openapi/complex-api.yaml` | OAS with `allOf`, `oneOf`, nested objects, arrays, multiple auth schemes |
| `openapi/no-servers.yaml` | OAS with empty `servers` array (edge case) |
| `openapi/no-operation-ids.yaml` | OAS without `operationId` fields (edge case) |
| `openapi/expected-petstore-consumes.yml` | Expected Naftiko consumes output for Petstore import |
| `openapi/expected-notion-openapi.yaml` | Expected OAS output for Notion capability export |

---

## 12. Implementation Roadmap

### Phase 1: OAS Import — Core Converter + CLI

**Dependency:** None (uses existing schema, parser, and CLI infrastructure).

1. Add `swagger-parser` and `swagger-models` dependencies to `pom.xml`
2. Implement `OasImportConverter` — OAS `OpenAPI` POJO → `HttpClientSpec`
3. Implement `OasImportResult` — result container with warnings
4. Implement namespace derivation, baseUri extraction, resource grouping
5. Implement parameter mapping (path, query, header, cookie, body)
6. Implement output parameter mapping from response schemas (scalar, object, array)
7. Implement authentication mapping (apiKey, basic, bearer, digest)
8. Implement edge cases: no servers, no operationId, allOf/oneOf, circular refs
9. Write unit tests (`OasImportConverterTest`) — all tests from §11.1
10. Write integration test — Petstore OAS → valid consumes YAML
11. Implement `ImportCommand` (Picocli) → `naftiko import openapi`
12. Implement `ImportOpenApiCommand` (subcommand) with options from §6.1
13. Wire into CLI: `Cli.java` registers `ImportCommand`
14. Validate GraalVM native-image build with swagger-parser
15. Add test fixture files (§11.4)

### Phase 2: OAS Export — Core Builder + CLI

**Dependency:** Phase 1 (shared Swagger model dependency; round-trip test).

1. Implement `OasExportBuilder` — `CapabilitySpec` + `RestServerSpec` → `OpenAPI` POJO
2. Implement `OasExportResult` — result container with warnings
3. Implement info block, server block, path/operation mapping
4. Implement parameter export (query, path, header, cookie → OAS Parameter; body → requestBody)
5. Implement response schema construction from output parameters
6. Implement authentication export (apiKey, basic, bearer, digest → securitySchemes)
7. Implement aggregate `ref` resolution before export
8. Implement `OasYamlWriter` — serializes `OpenAPI` POJO to YAML/JSON using Jackson
9. Write unit tests (`OasExportBuilderTest`) — all tests from §11.2
10. Write integration test — Notion capability → valid OAS 3.1
11. Implement `ExportCommand` (Picocli) → `naftiko export openapi`
12. Implement `ExportOpenApiCommand` (subcommand) with options from §6.2
13. Wire into CLI: `Cli.java` registers `ExportCommand`
14. Write round-trip integration test (§11.3)

### Phase 3: Backstage Plugin — Scaffolder Action

**Dependency:** Phase 1 (import converter); CLI binary available.

1. Create `@naftiko/backstage-plugin-scaffolder-openapi` package
2. Implement `naftiko:import:openapi` Scaffolder action (§8.2)
3. Create "Create Capability from OpenAPI" Scaffolder template (§8.2)
4. Test with Backstage dev instance: select cataloged API → scaffold capability
5. Publish to internal npm registry

### Phase 4: Backstage Plugin — Catalog Processor

**Dependency:** Phase 2 (export builder); cached artifacts.

1. Add `NaftikoOpenApiProcessor` to `@naftiko/backstage-plugin-backend` (§8.3)
2. Implement API entity emission from capability Component entities
3. Implement `providesApis` relation linking
4. Test with Backstage dev instance: deploy capability → API entity appears in catalog
5. Verify Swagger UI rendering on API entity page (uses existing `@backstage/plugin-api-docs`)

### Phase 5: Documentation & Examples

1. CLI usage guide: `naftiko import openapi` and `naftiko export openapi`
2. Tutorial: "Bootstrap a capability from a public API's OpenAPI spec"
3. Tutorial: "Generate an OpenAPI spec from your capability for client codegen"
4. Backstage guide: "Reuse REST APIs across capabilities via the Backstage catalog"
5. Update wiki Specification with OAS interoperability section
6. Add examples to `src/main/resources/schemas/examples/`

---

## 13. Design Decisions & Rationale

### D-1: Why `import`/`export` instead of `generate`

The App Port Strategy §8 uses `naftiko generate openapi` for one-way export. This proposal introduces bidirectionality, making `import`/`export` the natural verb pair:

| Verb | Semantics | Used For |
|------|-----------|----------|
| `import` | External format → Naftiko | `import openapi` (OAS → consumes) |
| `export` | Naftiko → external format | `export openapi` (exposes → OAS) |
| `generate` | Naftiko → executable artifact | `generate n8n`, `generate zapier` (code generators) |

`generate` produces artifacts that **run** (npm packages, CLI apps). `export` produces artifacts that **describe** (specs, schemas). The distinction matters for user mental models.

### D-2: Why Swagger Parser over other Java OAS libraries

| Library | Assessment |
|---------|-----------|
| **Swagger Parser** (`io.swagger.parser.v3`) | **Chosen.** Official SmartBear library. Supports OAS 2.0/3.0/3.1. Full `$ref` resolution. Active maintenance (v2.1.x). Used by OpenAPI Generator, Swagger UI, and most JVM-based OAS tools. |
| **OpenAPI4J** (`org.openapi4j`) | Archived (2021). No OAS 3.1 support. |
| **KaiZen OpenAPI Parser** (`com.reprezen.kaizen`) | RepreZen-maintained. Less ecosystem adoption. Swagger Parser covers the same features. |
| **Manual Jackson parsing** | Would require reimplementing `$ref` resolution, OAS validation, and 2.0→3.0 upgrade. Not viable. |

Swagger Parser is the de facto standard. It is used by >90% of JVM OAS tooling. The quality of `$ref` resolution alone justifies the dependency.

### D-3: Why OAS export is build-time, not runtime

| Approach | Pros | Cons |
|----------|------|------|
| **Build-time** (CLI command) | Simple, predictable, no runtime overhead, works with GraalVM native image | OAS can drift from running capability if YAML changes |
| **Runtime** (future endpoint) | Always in sync, live documentation | Requires runtime server, adds startup cost, runtime dependency |

Build-time is chosen for this proposal because:
1. OAS documents are consumed by external tools (codegen, gateways) that work with files, not live endpoints
2. CI/CD pipelines can regenerate the OAS on every commit, preventing drift
3. The converter classes are designed with no I/O coupling — runtime serving can be added later without a rewrite

### D-4: Why import produces `consumes` only, not a full capability

Generating a full capability from an OAS would require:
1. **Choosing an exposes adapter** — REST? MCP? Both? The tool cannot infer the user's intent.
2. **Designing orchestration** — Simple call? Steps? Aggregates? This requires domain knowledge.
3. **Mapping output parameters** — OAS response schemas describe the API's output, but the capability author chooses which fields to expose and how to project them.

Producing only the `consumes` section is the **honest** conversion: the OAS describes a consumed API, and the Naftiko consumes adapter describes how to call that API. The exposed surface is a design decision that belongs to the capability author.

The Backstage Scaffolder template (§8.2) composes OAS import with a capability skeleton template to get closer to full automation, but the user still makes the design choices.

### D-5: Why resource grouping uses OAS tags as primary strategy

OAS operations can be grouped by:
1. **Tags** — the standard OAS mechanism for grouping operations
2. **Path prefix** — first segment of the URL path
3. **`x-` extensions** — vendor-specific grouping hints

Tags are preferred because:
- Tag-based grouping is how API authors **intended** operations to be organized
- Swagger UI and Redoc use tags for section headings — the grouping is validated by documentation tools
- Path-based grouping can produce odd results (e.g., `/v2/users` and `/v2/databases` share prefix `v2`)

Path prefix is the fallback when no tags are present — it works for most RESTful APIs where the first path segment corresponds to the resource name.

### D-6: Why the Backstage plugin calls the CLI instead of embedding Java logic

The Backstage plugin runs in Node.js. The OAS conversion logic is in Java. Three integration options:

| Option | Pros | Cons |
|--------|------|------|
| **CLI binary invocation** | Works without a running instance, uses the same tested Java code, simple integration | Slower (JVM startup, or native binary availability), environment-dependent |
| **Rewrite in TypeScript** | No external dependency | Duplicates logic, two codebases to maintain, divergence risk |

The preferred path is **CLI invocation — never TypeScript rewrite**. The conversion logic exists once (in Java) and is accessed via its published interface. The Backstage plugin is a thin orchestration layer.

### D-7: Why OAS 3.1 for export (not 3.0)

OAS 3.1 aligns JSON Schema vocabularies with JSON Schema 2020-12 (the same used by `naftiko-schema.json`). This means:
- `type: ["string", "null"]` replaces `nullable: true` — simpler mapping from Naftiko's type system
- `const`, `if/then/else`, `$dynamicRef` are available for advanced schemas
- `webhooks` top-level object is available for future webhook adapter export

OAS 3.0 consumers (e.g., older Swagger UI versions) can still read 3.1 documents with minor degradation. The Swagger Parser library reads both 3.0 and 3.1.

---

## 14. Risks and Mitigations

| # | Risk | Likelihood | Impact | Mitigation |
|---|------|-----------|--------|------------|
| R-1 | Swagger Parser dependency conflicts with existing Jackson version | Medium | High | Pin Swagger Parser version that uses compatible Jackson. Test dependency tree with `mvn dependency:tree`. |
| R-2 | GraalVM native-image fails with Swagger Parser | Medium | Medium | Swagger Parser is pure Java + Jackson. Add reflection config early. If blocked, import/export features are CLI-only (not native binary). |
| R-3 | Complex OAS documents produce invalid Naftiko consumes | Medium | Medium | Extensive edge-case tests (§11.1). Import always produces a valid structure; unrepresentable constructs become warnings. |
| R-4 | OAS export drifts from actual runtime behavior | Low | Medium | CI step regenerates OAS on every commit. |
| R-5 | Backstage Scaffolder adoption is slow | Low | Low | The CLI is the primary interface. Backstage is an accelerator, not a requirement. |
| R-6 | OAuth 2.0 / OIDC authentication not mapped | High (many APIs use it) | Medium | Warn on import; add OAuth 2.0 support to Naftiko authentication (blocked by [token-refresh-authentication](token-refresh-authentication.md) blueprint). Once added, mapping is straightforward. |

---

## 15. Acceptance Criteria

### Phase 1 (Import)

- [ ] `naftiko import openapi petstore.yaml` produces a valid standalone consumes YAML
- [ ] Imported file passes `naftiko validate`
- [ ] Imported file passes Spectral lint (no errors, warnings acceptable for placeholders)
- [ ] Path parameters converted to Mustache template syntax (`{id}` → `{{id}}`)
- [ ] Operations grouped by tag (or path segment fallback)
- [ ] Authentication mapped for apiKey, basic, bearer, digest
- [ ] Unsupported auth types produce a warning (not an error)
- [ ] Import from URL works (`naftiko import openapi https://...`)
- [ ] All unit tests pass
- [ ] GraalVM native-image build succeeds with swagger-parser dependency

### Phase 2 (Export)

- [ ] `naftiko export openapi capability.yml` produces a valid OAS 3.1 document
- [ ] Exported OAS passes OAS 3.1 schema validation
- [ ] All REST resources and operations appear in `paths`
- [ ] Input parameters correctly mapped to OAS parameters and requestBody
- [ ] Output parameters correctly mapped to response schemas
- [ ] Authentication correctly mapped to `securitySchemes`
- [ ] Aggregate `ref` operations fully resolved in export
- [ ] `--adapter` flag selects correct REST adapter when multiple exist
- [ ] `--format json` produces valid JSON output
- [ ] Round-trip test: export → import → structure comparison passes

### Phase 3 (Backstage Scaffolder)

- [ ] `naftiko:import:openapi` action registered in Scaffolder
- [ ] Scaffolder template selects cataloged API entity and produces capability skeleton
- [ ] Produced consumes YAML is valid

### Phase 4 (Backstage Catalog)

- [ ] `NaftikoOpenApiProcessor` emits `API` entity for capabilities with REST adapters
- [ ] `Component` entity has `providesApis` relation to `API` entity
- [ ] Swagger UI renders the exported OAS on the API entity page
