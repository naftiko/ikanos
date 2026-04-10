# gRPC Procedures Jetty Embedded Proposal
## Adding a gRPC Server Adapter with Procedure Semantics

**Status**: Proposal  
**Date**: April 10, 2026  
**Spec Version**: `1.0.0-alpha2`  
**Key Concept**: Add a new `type: grpc` exposed server adapter implemented on embedded Jetty only (no Servlet dependency), where gRPC exposes **procedures** that keep the same declarative structure, execution model, and aggregate-function integration as MCP **tools**.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Goals and Non-Goals](#goals-and-non-goals)
3. [Terminology](#terminology)
4. [Design Analogy](#design-analogy)
5. [Architecture Overview](#architecture-overview)
6. [Specification and Schema Changes](#specification-and-schema-changes)
7. [Capability YAML Examples](#capability-yaml-examples)
8. [Runtime Design](#runtime-design)
9. [Jetty-Only Transport Design](#jetty-only-transport-design)
10. [Dependency Constraints](#dependency-constraints)
11. [gRPC Status Code Mapping](#grpc-status-code-mapping)
12. [Testing Strategy](#testing-strategy)
13. [Implementation Roadmap](#implementation-roadmap)
14. [Risks and Mitigations](#risks-and-mitigations)
15. [Acceptance Criteria](#acceptance-criteria)

---

## Executive Summary

### What This Proposes

Add a new `type: grpc` server adapter in `capability.exposes` with these principles:

1. **Jetty embedded only** for runtime hosting.
2. **Zero Servlet dependency** in compile/runtime classpath.
3. gRPC surface is modeled as **procedures**.
4. Procedure shape and orchestration are **identical to MCP tools** (`call`, `with`, `steps`, `outputParameters`).
5. **Full aggregate-function support** — procedures can use `ref` to inherit from aggregate functions, just like MCP tools and REST operations.

### Why This Fits Naftiko

Naftiko already separates:

- Transport/adapter concerns (REST, MCP, Skill)
- Declarative orchestration (`call` or `steps`)
- Output mapping (`outputParameters`)
- Domain-level reuse (`aggregates` with `ref`)

This proposal extends that model without introducing a second orchestration paradigm. In particular, the DDD aggregate pattern — define once, project through multiple adapters — naturally extends to a gRPC adapter. A single aggregate function can be exposed simultaneously as an MCP tool, a REST operation, and a gRPC procedure.

### Value

- Adds first-class gRPC exposure for typed service ecosystems.
- Preserves existing capability authoring mental model from MCP tools.
- Enables `aggregates` + `ref` for gRPC — one domain function, three adapters (REST, MCP, gRPC).
- Keeps runtime footprint coherent with current Jetty usage.

---

## Goals and Non-Goals

### Goals

1. Introduce `grpc` as a new exposed adapter type.
2. Define `procedures` as the gRPC equivalent of MCP `tools`.
3. Support `ref` to aggregate functions — including semantics inheritance.
4. Reuse existing execution engine (`OperationStepExecutor`) for procedure execution.
5. Extend `AggregateRefResolver` to resolve `ref` fields in gRPC procedures.
6. Support unary RPC in first iteration.
7. Keep runtime strictly Jetty embedded, with no Servlet artifacts.

### Non-Goals (Phase 1)

1. Bidirectional/client/server streaming RPC.
2. Proto code generation from capability files.
3. TLS/mTLS configuration — phase 1 uses h2c (cleartext HTTP/2); TLS is phase 2.
4. Replacing MCP tool model; this is additive.
5. gRPC server reflection API — dynamic procedures with `application/grpc+json` codec make standard reflection impossible to implement. Clients must know the service contract.

---

## Design Analogy

### How adapters map to their primary constructs

```
REST Adapter                 MCP Adapter                   Skill Adapter              gRPC Adapter (proposed)
────────────                 ───────────                   ─────────────              ───────────────────────
ExposesRest                  ExposesMcp                    ExposesSkill               ExposesGrpc
├─ namespace                 ├─ namespace                  ├─ namespace               ├─ namespace
├─ port                      ├─ port                       ├─ port                    ├─ port
├─ address                   ├─ address                    ├─ address                 ├─ address
├─ authentication            ├─ description                ├─ description             ├─ package
│                            ├─ transport                  ├─ authentication          ├─ service
├─ resources[]               ├─ tools[]                    └─ skills[]                └─ procedures[]
│  ├─ path                   │  ├─ name                       ├─ name                    ├─ name
│  ├─ name                   │  ├─ label                      ├─ description             ├─ label
│  └─ operations[]           │  ├─ description                └─ tools[]                 ├─ description
│     ├─ method              │  ├─ ref                                                   ├─ ref
│     ├─ name                │  ├─ inputParameters[]                                     ├─ inputParameters[]
│     ├─ label               │  ├─ call / steps                                          ├─ call / steps
│     ├─ description         │  ├─ with                                                  ├─ with
│     ├─ ref                 │  ├─ hints                                                 ├─ mappings
│     ├─ inputParameters[]   │  ├─ mappings                                              └─ outputParameters[]
│     ├─ call / steps        │  └─ outputParameters[]
│     ├─ mappings
│     └─ outputParameters[]
```

### Concept-level mapping across adapter types

| Concept | REST Adapter | MCP Adapter | Skill Adapter | gRPC Adapter |
|---|---|---|---|---|
| **Entry point** | resource path | tool name | skill name | procedure name |
| **Method grouping** | `resources[]` → `operations[]` | `tools[]` | `skills[]` → `tools[]` | `procedures[]` |
| **Input** | HTTP query / path / header / body | JSON arguments map | N/A (catalog only) | gRPC request payload (JSON codec) |
| **Output** | HTTP response body | `CallToolResult` | N/A (catalog only) | gRPC response payload (JSON codec) |
| **Simple dispatch** | `call: namespace.op` | `call: namespace.op` | N/A | `call: namespace.op` |
| **Orchestration** | `steps[]` | `steps[]` | N/A | `steps[]` |
| **Static injection** | `with` | `with` | N/A | `with` |
| **Output mapping** | `outputParameters` | `outputParameters` | N/A | `outputParameters` |
| **Aggregate ref** | `ref: ns.fn` | `ref: ns.fn` | N/A | `ref: ns.fn` |
| **Semantics derivation** | N/A | `semantics` → `hints` | N/A | `semantics` → metadata (trailers) |
| **Transport** | Restlet/Jetty HTTP/1.1 | Jetty HTTP/1.1 (MCP JSON-RPC) | Jetty HTTP/1.1 | Jetty HTTP/2 h2c (gRPC unary) |
| **Execution engine** | `OperationStepExecutor` | `OperationStepExecutor` | N/A (does not execute) | `OperationStepExecutor` |

---

## Terminology

- **MCP**: exposes `tools`
- **gRPC**: exposes `procedures`
- **Aggregate function**: a reusable, adapter-independent invocable unit declared in `capability.aggregates`. Both MCP tools and gRPC procedures can reference it via `ref`.
- **Structure parity**: a gRPC procedure has the same declarative fields and execution rules as an MCP tool.

Field-level parity between `McpTool` and `GrpcProcedure`:

- `name`
- `label`
- `description`
- `ref` (aggregate function reference)
- `inputParameters`
- `call`
- `with`
- `steps`
- `mappings`
- `outputParameters`

Fields present in `McpTool` but **not** in `GrpcProcedure`:
- `hints` (MCP-specific `ToolAnnotations`; gRPC has no equivalent wire concept)

Semantics derivation: when a procedure inherits from an aggregate function with `semantics`, the engine stores the semantics as metadata on the `GrpcProcedureHandler` for logging and observability. Unlike MCP (which derives `hints`), gRPC has no standard wire protocol for behavioral annotations. Exposing semantics via custom gRPC response metadata (e.g., `x-semantics-safe: true` trailer) is deferred to phase 2.

---

## Architecture Overview

### Current

- `ServerSpec` supports `rest`, `mcp`, and `skill` via `@JsonSubTypes`.
- `Capability` creates `RestServerAdapter`, `McpServerAdapter`, or `SkillServerAdapter` by type string.
- MCP tools support `ref` to aggregate functions — `AggregateRefResolver` resolves refs at load time, then `ToolHandler` delegates execution to `AggregateFunction` instances at runtime.
- REST operations also support `ref` to aggregate functions — `AggregateRefResolver` resolves name/description inheritance.
- `AggregateRefResolver` derives MCP `hints` from aggregate `semantics` (e.g., `safe: true` → `readOnly: true, destructive: false`).
- Jetty 12.0.25 runs HTTP/1.1 only (pom has `jetty-http2-common` but no `jetty-http2-server`).

### Proposed

- Extend `ServerSpec` polymorphism with `GrpcServerSpec` (`type: grpc`).
- Extend `Capability` adapter dispatch with `GrpcServerAdapter`.
- Extend `AggregateRefResolver` to resolve `ref` fields in `GrpcProcedureSpec` — same inheritance pattern as MCP tools (name, description from function; semantics stored as metadata).
- Add `GrpcProcedureHandler` that mirrors `McpToolHandler` behavior, delegating to same `OperationStepExecutor` and supporting aggregate-function delegation.
- Add `JettyGrpcUnaryHandler extends Handler.Abstract` for gRPC transport — no Servlet.
- Extend Jetty setup with `HTTP2CServerConnectionFactory` for h2c (cleartext HTTP/2).

### Execution Flow

```
Jetty (HTTP/2 h2c)
  └── JettyGrpcUnaryHandler
        1. Verify Content-Type: application/grpc+json
        2. Decode gRPC frame (1-byte flag + 4-byte length + body)
        3. Deserialize JSON body → Map<String, Object>
        4. Route /{package}.{service}/{procedure} → GrpcProcedureHandler
              ├── ref mode   → Capability.lookupFunction(ref) → AggregateFunction
              │                 └── OperationStepExecutor
              ├── call mode  → OperationStepExecutor (direct)
              └── steps mode → OperationStepExecutor (direct)
        5. Apply outputParameters mappings
        6. Serialize result → JSON → gRPC frame
        7. Write response + grpc-status trailer
```

---

## Specification and Schema Changes

### New Exposes Type

Add `ExposesGrpc` to the `capability.exposes` oneOf list in `naftiko-schema.json`, alongside `ExposesRest`, `ExposesMcp`, and `ExposesSkill`.

### ExposesGrpc Fields

| Field | Required | Description |
|---|---|---|
| `type` | yes | Constant `"grpc"` |
| `address` | no | Host/address (default `localhost`) |
| `port` | yes | TCP port |
| `namespace` | yes | Unique identifier for this adapter (IdentifierKebab) |
| `package` | no | gRPC proto package name. When set, endpoint is `/{package}.{service}/{procedure}`; when absent, endpoint is `/{service}/{procedure}` |
| `service` | yes | gRPC service name |
| `procedures` | yes | Array of procedure definitions (min 1) |

### GrpcProcedure Definition

Define `GrpcProcedure` with the same structure and validation behavior as `McpTool`. Uses the same `anyOf` dispatch for three modes:

**Mode 1 — Simple call** (requires `name`, `description`, `call`):
- `outputParameters` items are `MappedOutputParameter`

**Mode 2 — Orchestrated steps** (requires `name`, `description`, `steps`):
- `mappings` allowed
- `outputParameters` items are `OrchestratedOutputParameter`

**Mode 3 — Aggregate ref** (requires `ref`):
- Inherits `name`, `description`, `inputParameters`, `call`/`steps`, `with`, `mappings`, `outputParameters` from the referenced aggregate function
- Explicit fields on the procedure override inherited values (same merge semantics as MCP tools)

### GrpcProcedure Fields

| Field | Description |
|---|---|
| `name` | Procedure name (IdentifierKebab). Required for `call` and `steps` modes; inherited from function in `ref` mode. |
| `label` | Human-readable display name. |
| `description` | Procedure description. Required for `call` and `steps` modes; inherited in `ref` mode. |
| `ref` | Reference to an aggregate function. Format: `{aggregate-namespace}.{function-name}`. |
| `inputParameters` | Array of `McpToolInputParameter` (same schema as MCP tools). |
| `call` | Consumed operation reference. Format: `{namespace}.{operationId}`. |
| `with` | Parameter injection map (`WithInjector`). |
| `steps` | Array of `OperationStep` (min 1). |
| `mappings` | Array of `StepOutputMapping` — maps step outputs to procedure output. |
| `outputParameters` | Output parameter array (type depends on mode). |

**Note**: `GrpcProcedure` deliberately omits `hints` — that is an MCP-specific concept (`ToolAnnotations`). gRPC has no standard equivalent. Aggregate `semantics` are preserved as runtime metadata for logging and observability.

### GrpcProcedureInputParameter

Reuse `McpToolInputParameter` directly — the schema definition is adapter-agnostic. Fields: `name`, `type`, `description`, `required`.

### AggregateRefResolver Extension

`AggregateRefResolver` must be extended to handle `GrpcProcedureSpec` refs, following the same pattern as `resolveMcpToolRef()`:
- Inherit `name` and `description` from the aggregate function (if not explicitly set)
- Store aggregate `semantics` as metadata on the procedure handler (no wire-level derivation)
- Validate `ref` resolves to an existing function

### Documentation

Update the Naftiko specification docs with:

1. New gRPC Expose section.
2. Terminology distinction: MCP tools vs gRPC procedures.
3. Explicit runtime rule: embedded Jetty only, zero Servlet dependency.
4. `aggregates` + `ref` usage with gRPC procedures.

---

## Capability YAML Examples

### Example 1 — Aggregate Function with gRPC + MCP + REST (recommended pattern)

This is the primary pattern. A single aggregate function is defined once and projected through all three adapters via `ref`.

```yaml
# yaml-language-server: $schema=../../main/resources/schemas/naftiko-schema.json
---
naftiko: "1.0.0-alpha2"
info:
  label: "Order Service (Aggregate)"
  description: >
    Demonstrates a domain function defined once in an aggregate,
    then exposed via gRPC, MCP, and REST adapters using ref.
  tags:
    - orders
    - grpc
    - aggregate
  created: "2026-04-10"
  modified: "2026-04-10"

capability:
  aggregates:
    - label: "Orders"
      namespace: "orders"
      functions:
        - name: "get-order"
          description: "Retrieve a single order by its identifier."
          semantics:
            safe: true
            idempotent: true
          inputParameters:
            - name: "order-id"
              type: "string"
              description: "Unique identifier of the order."
          call: "orders-api.get-order"
          with:
            id: "{{order-id}}"
          outputParameters:
            - name: "id"
              type: "string"
              mapping: "$.id"
            - name: "status"
              type: "string"
              mapping: "$.status"
            - name: "total"
              type: "number"
              mapping: "$.amount.total"

        - name: "get-order-with-customer"
          description: "Retrieve an order together with its associated customer details."
          semantics:
            safe: true
            idempotent: true
          inputParameters:
            - name: "order-id"
              type: "string"
              description: "Unique identifier of the order."
          steps:
            - type: "call"
              name: "fetch-order"
              call: "orders-api.get-order"
              with:
                id: "{{order-id}}"
            - type: "call"
              name: "fetch-customer"
              call: "customers-api.get-customer"
              with:
                customer-id: "{{$.fetch-order.customerId}}"
          outputParameters:
            - name: "order-id"
              type: "string"
              mapping: "{{$.fetch-order.id}}"
            - name: "customer-name"
              type: "string"
              mapping: "{{$.fetch-customer.name}}"

  exposes:
    # gRPC adapter — inherits everything from aggregate functions via ref.
    - type: "grpc"
      address: "localhost"
      port: 50051
      namespace: "orders-grpc"
      package: "io.example.orders"
      service: "OrderService"
      procedures:
        - ref: "orders.get-order"
        - ref: "orders.get-order-with-customer"

    # MCP adapter — also inherits via ref.
    # Hints are auto-derived from semantics: readOnly=true, destructive=false, idempotent=true.
    - type: "mcp"
      address: "localhost"
      port: 3000
      namespace: "orders-mcp"
      description: "MCP server exposing order management tools."
      tools:
        - ref: "orders.get-order"
        - ref: "orders.get-order-with-customer"

    # REST adapter — inherits call/steps/outputParameters, adds REST-specific fields.
    - type: "rest"
      address: "localhost"
      port: 3001
      namespace: "orders-rest"
      resources:
        - path: "/orders/{id}"
          name: "order"
          description: "Single order resource."
          operations:
            - ref: "orders.get-order"
              method: "GET"
              inputParameters:
                - name: "id"
                  in: "path"
                  type: "string"
                  description: "Order identifier."

        - path: "/orders/{id}/with-customer"
          name: "order-with-customer"
          description: "Order with customer details."
          operations:
            - ref: "orders.get-order-with-customer"
              method: "GET"
              inputParameters:
                - name: "id"
                  in: "path"
                  type: "string"
                  description: "Order identifier."

  consumes:
    - type: "http"
      namespace: "orders-api"
      description: "External orders REST API."
      baseUri: "https://api.example.com/v1"
      resources:
        - path: "orders/{{id}}"
          name: "order"
          label: "Single Order"
          operations:
            - method: "GET"
              name: "get-order"
              label: "Get Order"
              inputParameters:
                - name: "id"
                  in: "path"

    - type: "http"
      namespace: "customers-api"
      description: "External customers REST API."
      baseUri: "https://api.example.com/v1"
      resources:
        - path: "customers/{{customer-id}}"
          name: "customer"
          label: "Single Customer"
          operations:
            - method: "GET"
              name: "get-customer"
              label: "Get Customer"
              inputParameters:
                - name: "customer-id"
                  in: "path"
```

### Example 2 — Standalone gRPC (inline call/steps, no aggregates)

For capabilities that only expose gRPC — no need for aggregates when there is a single adapter.

```yaml
# yaml-language-server: $schema=../../main/resources/schemas/naftiko-schema.json
---
naftiko: "1.0.0-alpha2"
info:
  label: "Order Service gRPC Capability"
  description: "Exposes order management operations as a gRPC service backed by a REST API."
  tags:
    - orders
    - grpc
  created: "2026-04-10"
  modified: "2026-04-10"

capability:
  exposes:
    - type: "grpc"
      address: "localhost"
      port: 50051
      namespace: "orders-grpc"
      package: "io.example.orders"
      service: "OrderService"

      procedures:
        # Simple call mode
        - name: "get-order"
          description: "Retrieve a single order by its identifier."
          inputParameters:
            - name: "order-id"
              type: "string"
              description: "Unique identifier of the order."
          call: "orders-api.get-order"
          with:
            id: "{{order-id}}"
          outputParameters:
            - name: "id"
              type: "string"
              mapping: "$.id"
            - name: "status"
              type: "string"
              mapping: "$.status"
            - name: "total"
              type: "number"
              mapping: "$.amount.total"

        # Orchestrated steps mode
        - name: "get-order-with-customer"
          description: "Retrieve an order together with its associated customer details."
          inputParameters:
            - name: "order-id"
              type: "string"
              description: "Unique identifier of the order."
          steps:
            - type: "call"
              name: "fetch-order"
              call: "orders-api.get-order"
              with:
                id: "{{order-id}}"
            - type: "call"
              name: "fetch-customer"
              call: "customers-api.get-customer"
              with:
                customer-id: "{{$.fetch-order.customerId}}"
          outputParameters:
            - name: "order-id"
              type: "string"
              mapping: "{{$.fetch-order.id}}"
            - name: "customer-name"
              type: "string"
              mapping: "{{$.fetch-customer.name}}"

  consumes:
    - type: "http"
      namespace: "orders-api"
      description: "External orders REST API."
      baseUri: "https://api.example.com/v1"
      resources:
        - path: "orders/{{id}}"
          name: "order"
          label: "Single Order"
          operations:
            - method: "GET"
              name: "get-order"
              label: "Get Order"
              inputParameters:
                - name: "id"
                  in: "path"

    - type: "http"
      namespace: "customers-api"
      description: "External customers REST API."
      baseUri: "https://api.example.com/v1"
      resources:
        - path: "customers/{{customer-id}}"
          name: "customer"
          label: "Single Customer"
          operations:
            - method: "GET"
              name: "get-customer"
              label: "Get Customer"
              inputParameters:
                - name: "customer-id"
                  in: "path"
```

The gRPC endpoint for `get-order` resolves to `POST /io.example.orders.OrderService/get-order` over HTTP/2 h2c.

---

## Runtime Design

### New Spec Classes (`io.naftiko.spec.exposes`)

1. `GrpcServerSpec extends ServerSpec` — `type: grpc`, adds `package`, `service`, `procedures`
2. `GrpcServerProcedureSpec` — mirrors `McpServerToolSpec` field-for-field, including `ref`. Omits `hints` (MCP-specific).

### GrpcServerProcedureSpec Fields

| Field | Type | Description |
|---|---|---|
| `name` | `String` (volatile) | Procedure name |
| `label` | `String` (volatile, `@JsonInclude(NON_NULL)`) | Display name |
| `description` | `String` (volatile) | Procedure description |
| `ref` | `String` (volatile, `@JsonInclude(NON_NULL)`) | Aggregate function reference |
| `inputParameters` | `List<InputParameterSpec>` (CopyOnWriteArrayList) | Input parameters |
| `call` | `ServerCallSpec` (volatile, `@JsonInclude(NON_NULL)`) | Simple mode dispatch |
| `with` | `Map<String, Object>` (volatile, `@JsonInclude(NON_NULL)`) | Parameter injection |
| `steps` | `List<OperationStepSpec>` (CopyOnWriteArrayList) | Orchestrated steps |
| `mappings` | `List<StepOutputMappingSpec>` (CopyOnWriteArrayList) | Step output mappings |
| `outputParameters` | `List<OutputParameterSpec>` (CopyOnWriteArrayList) | Output parameters |

### New Engine Classes (`io.naftiko.engine.exposes.grpc`)

1. `GrpcServerAdapter extends ServerAdapter` — owns Jetty lifecycle, mirrors `McpServerAdapter`
2. `GrpcProcedureHandler` — mirrors `ToolHandler` (MCP); resolves input map → `OperationStepExecutor` → output map. Supports aggregate-function delegation via `Capability.lookupFunction(ref)`.
3. `JettyGrpcUnaryHandler extends Handler.Abstract` — gRPC framing, routing, codec

### AggregateRefResolver Changes

Extend `AggregateRefResolver.resolve()` to iterate `GrpcServerSpec.procedures` in addition to MCP tools and REST operations.

New method `resolveGrpcProcedureRef(GrpcServerProcedureSpec proc, Map<String, AggregateFunctionSpec> functionMap)`:
- Validates `ref` resolves to an existing function
- Inherits `name` and `description` from function (if not explicitly set on procedure)
- Stores `semantics` reference for runtime metadata (no wire derivation like MCP hints)
- Does **not** copy execution fields (`call`, `steps`, `with`, `inputParameters`, `outputParameters`, `mappings`) — those are delegated at runtime

### Input Parameter Mapping

gRPC request body is a flat or nested JSON object decoded from the gRPC frame. It is converted to `Map<String, Object>` and merged with the procedure's `with` map, exactly as MCP tool arguments are merged.

Procedure `inputParameters` provide the names and types for documentation and schema validation, not for extraction — matching the MCP tools convention.

### Runtime Execution Path (GrpcProcedureHandler)

Mirrors `ToolHandler.handleToolCall()`:

1. **Lookup procedure spec** by name
2. **Merge `with` parameters** — Mustache-templated parameter injectors
3. **Delegate to aggregate function** if `ref` is set:
   - Call `capability.lookupFunction(ref)` → `AggregateFunction`
   - Execute via aggregate function's `call`/`steps`/`with`
4. **OR execute directly** using:
   - Simple call: `stepExecutor.execute(proc.getCall(), steps, parameters)`
   - Orchestration: `stepExecutor.executeSteps(proc.getSteps(), parameters)`
5. **Map output** to response JSON (gRPC frame payload)

---

## Jetty-Only Transport Design

### Hard Constraint

Transport implementation must use only embedded Jetty 12.x APIs, following the `Handler.Abstract` pattern established by `JettyStreamableHandler` (the MCP Streamable HTTP handler). No Servlet container APIs.

### Protocol Scope (Phase 1)

Unary gRPC over HTTP/2 **h2c** (cleartext HTTP/2) only. This means:
- No TLS required for phase 1 (suitable for container-to-container, local, and intra-cluster use).
- `HTTP2CServerConnectionFactory` upgrades plain TCP connections to HTTP/2.
- TLS (`h2`) and ALPN negotiation are explicitly deferred to phase 2.

### Endpoint Pattern

- With `package` set: `POST /{package}.{service}/{procedure}`
- Without `package`: `POST /{service}/{procedure}`

### Codec Strategy

Use `application/grpc+json` as the content type for all phase 1 communication. This:
- Avoids all protobuf schema generation and `Struct` encoding complexity.
- Maps naturally to Naftiko's JSON-native parameter model and Jackson-based output mappings.
- Is a valid gRPC codec per the gRPC HTTP/2 specification.

The `protobuf-java` dependency remains for gRPC message framing utilities only, not for typed message schema.

### gRPC Framing Responsibilities

`JettyGrpcUnaryHandler` handles:

1. Validate `Content-Type: application/grpc+json`.
2. Read body — decode gRPC frame: 1-byte compression flag (must be `0x00`) + 4-byte big-endian length + payload bytes.
3. Deserialize JSON payload bytes → `Map<String, Object>` via Jackson.
4. Route to target `GrpcProcedureHandler` by path.
5. Execute procedure and collect result.
6. Serialize result map → JSON bytes → gRPC frame (compression flag `0x00` + length + bytes).
7. Write HTTP/2 response with `Content-Type: application/grpc+json`.
8. Write HTTP/2 trailers: `grpc-status: {code}` (and `grpc-message` if error).

---

## Dependency Constraints

### Current State (from `pom.xml`)

- `jetty-http2-common` 12.0.25 — already present (shared HTTP/2 data structures).
- `jetty-server` 12.0.25 — already present.
- `protobuf-java` 4.29.3 — already present (used by Jackson protobuf dataformat).
- `jackson-dataformat-protobuf` 2.20.2 — already present.

### Required Additions

| Artifact | Group | Reason |
|---|---|---|
| `jetty-http2-server` | `org.eclipse.jetty.http2` | HTTP/2 server connection factory (h2c). Required — `jetty-http2-common` alone does NOT provide server-side HTTP/2. Use version `${jetty.version}` (currently 12.0.25). |

No other new dependencies are required for phase 1 (h2c with JSON codec).

### Forbidden

- Any Servlet API — `javax.servlet`, `jakarta.servlet`.
- `grpc-servlet` artifacts.
- `grpc-netty` or `grpc-netty-shaded` — Netty is not in this runtime.
- Proto codegen plugins (`protoc`, `protobuf-maven-plugin`) — phase 1 does not require generated types.

### Build Guardrail

Add a CI step using `mvn dependency:tree` assertion or `mvn enforcer:enforce` with `bannedDependencies` to fail the build if any forbidden artifact is detected in the compile/runtime dependency tree.

---

## gRPC Status Code Mapping

A deterministic mapping from internal execution states to gRPC status codes:

| Internal Condition | gRPC Status Code | Notes |
|---|---|---|
| Procedure not found (unknown name) | `NOT_FOUND (5)` | Unknown procedure in path |
| Input validation failure | `INVALID_ARGUMENT (3)` | Missing required input parameters |
| Upstream HTTP 4xx | `FAILED_PRECONDITION (9)` | Upstream returned a client error |
| Upstream HTTP 401/403 | `PERMISSION_DENIED (7)` | Auth failure on consumed endpoint |
| Upstream HTTP 404 | `NOT_FOUND (5)` | Consumed resource not found |
| Upstream HTTP 5xx | `UNAVAILABLE (14)` | Upstream server error |
| Upstream connection refused / timeout | `UNAVAILABLE (14)` | Network or availability issue |
| Output mapping failure | `INTERNAL (13)` | JSONPath or mapping error |
| Unknown internal error | `INTERNAL (13)` | Catch-all for unexpected exceptions |
| Success | `OK (0)` | |

All non-OK responses populate the `grpc-message` trailer with the exception message.

---

## Testing Strategy

### Contract and Wiring Tests

1. YAML deserialization of `type: grpc` expose.
2. `ServerSpec` subtype dispatch correctness.
3. `Capability` creates `GrpcServerAdapter`.
4. `AggregateRefResolver` resolves `ref` in gRPC procedures.

### Aggregate Integration Tests

1. Procedure with `ref` inherits name, description from aggregate function.
2. Procedure with `ref` delegates execution to aggregate function at runtime.
3. Aggregate semantics are accessible as metadata on the procedure handler.
4. Explicit procedure fields override inherited aggregate fields.
5. Parity: same aggregate function exposed via MCP tool and gRPC procedure produces identical output.

### Runtime Tests

1. Adapter start/stop lifecycle on configured port.
2. Unary procedure invocation in `call` mode.
3. Unary procedure invocation in `steps` mode.
4. Unary procedure invocation in `ref` mode (aggregate delegation).
5. Output mapping parity with MCP tool behavior.
6. Error-to-gRPC-status mapping.

### Schema Tests

1. Valid `ExposesGrpc` sample passes — all three modes (call, steps, ref).
2. Invalid structures fail (missing procedures, invalid call/steps combinations, etc.).
3. `ref` format validation (`namespace.function-name` pattern).

---

## Implementation Roadmap

### Milestone 1: Contract Layer

**Goal**: A capability with `type: grpc` deserializes, instantiates the correct adapter, and passes schema validation.

| # | Task | File(s) |
|---|---|---|
| 1.1 | Add `GrpcServerSpec extends ServerSpec` with `package`, `service`, `procedures` fields | `spec/exposes/GrpcServerSpec.java` |
| 1.2 | Add `GrpcServerProcedureSpec` mirroring `McpServerToolSpec` (including `ref`, `mappings`; excluding `hints`) | `spec/exposes/GrpcServerProcedureSpec.java` |
| 1.3 | Register `@JsonSubTypes.Type(value = GrpcServerSpec.class, name = "grpc")` | `spec/exposes/ServerSpec.java` |
| 1.4 | Add `else if ("grpc"...)` branch in adapter dispatch | `Capability.java` |
| 1.5 | Add skeleton `GrpcServerAdapter` with no-op `start()`/`stop()` | `engine/exposes/grpc/GrpcServerAdapter.java` |
| 1.6 | Add `grpc-capability.yaml` test fixture (standalone, no aggregates) | `src/test/resources/grpc-capability.yaml` |
| 1.7 | Add deserialization + wiring integration tests | `CapabilityGrpcIntegrationTest.java` |

**Acceptance**: `Capability` loads the fixture, `serverAdapters.get(0)` is `GrpcServerAdapter`, spec fields are correct.

### Milestone 2: Aggregate Integration

**Goal**: `ref` in gRPC procedures resolves to aggregate functions. Procedures inherit name/description and delegate execution at runtime.

| # | Task | File(s) |
|---|---|---|
| 2.1 | Extend `AggregateRefResolver` to iterate `GrpcServerSpec.procedures` | `engine/aggregates/AggregateRefResolver.java` |
| 2.2 | Add `resolveGrpcProcedureRef()` — inherit name/description, validate ref | `engine/aggregates/AggregateRefResolver.java` |
| 2.3 | Add `grpc-aggregate-capability.yaml` fixture (aggregate + ref pattern) | `src/test/resources/grpc-aggregate-capability.yaml` |
| 2.4 | Add aggregate ref resolution tests for gRPC | `AggregateRefResolverTest.java` |
| 2.5 | Add cross-adapter parity test: same aggregate function via MCP tool and gRPC procedure | `CapabilityGrpcAggregateIntegrationTest.java` |

**Acceptance**: AggregateRefResolver resolves gRPC procedure refs. Test fixture with `ref: "orders.get-order"` loads correctly. Name and description are inherited from aggregate function.

### Milestone 3: Schema and Docs

**Goal**: Valid `type: grpc` capability passes schema; invalid shapes are rejected; spec docs are updated.

| # | Task | File(s) |
|---|---|---|
| 3.1 | Add `ExposesGrpc` definition to `exposes` oneOf | `schemas/naftiko-schema.json` |
| 3.2 | Add `GrpcProcedure` definition with `anyOf` (call, steps, ref modes) | `schemas/naftiko-schema.json` |
| 3.3 | Reuse `McpToolInputParameter` for procedure input parameters | `schemas/naftiko-schema.json` |
| 3.4 | Add gRPC Expose section to specification docs | `wiki/Specification.md` |
| 3.5 | Add `grpc-aggregate.yml` schema example demonstrating aggregate + ref pattern | `schemas/examples/grpc-aggregate.yml` |

**Acceptance**: Schema validator accepts the sample YAML (all three modes). Missing `procedures` or wrong `call`/`steps`/`ref` patterns are rejected.

### Milestone 4: Procedure Execution

**Goal**: Procedures execute with identical semantics to MCP tools (call mode, steps mode, and ref mode).

| # | Task | File(s) |
|---|---|---|
| 4.1 | Implement `GrpcProcedureHandler` delegating to `OperationStepExecutor` and supporting aggregate-function delegation | `engine/exposes/grpc/GrpcProcedureHandler.java` |
| 4.2 | Wire handler into `GrpcServerAdapter` constructor | `engine/exposes/grpc/GrpcServerAdapter.java` |
| 4.3 | Add unit tests: call mode, steps mode, ref mode, output mapping, error path | `CapabilityGrpcIntegrationTest.java` |
| 4.4 | Add cross-path parity test: `GrpcProcedureHandler` vs `ToolHandler` for identical inputs | `CapabilityGrpcIntegrationTest.java` |

**Acceptance**: Procedure handler test results match equivalent MCP tool handler test results for same inputs/outputs.

### Milestone 5: Jetty Unary gRPC Transport

**Goal**: Unary gRPC calls over h2c succeed end-to-end with correct framing, routing, and status/trailer handling.

| # | Task | File(s) |
|---|---|---|
| 5.1 | Add `jetty-http2-server` dependency (version `${jetty.version}`) | `pom.xml` |
| 5.2 | Implement `JettyGrpcUnaryHandler extends Handler.Abstract` — framing, JSON codec, routing | `engine/exposes/grpc/JettyGrpcUnaryHandler.java` |
| 5.3 | Configure `HTTP2CServerConnectionFactory` in `GrpcServerAdapter.initHttpTransport()` | `engine/exposes/grpc/GrpcServerAdapter.java` |
| 5.4 | Implement gRPC status/trailer mapping from `GrpcProcedureHandler` result | `engine/exposes/grpc/JettyGrpcUnaryHandler.java` |
| 5.5 | Add lifecycle integration test: start, invoke, stop | `CapabilityGrpcIntegrationTest.java` |

**Acceptance**: `grpcurl` (or equivalent test client with h2c + JSON) can call a procedure and receive a valid response with `grpc-status: 0`.

### Milestone 6: Hardening

**Goal**: Production-appropriate error handling, CI guardrails, and full test coverage.

| # | Task | File(s) |
|---|---|---|
| 6.1 | Add Maven Enforcer `bannedDependencies` rule for Servlet/grpc-servlet | `pom.xml` |
| 6.2 | Implement full gRPC status code mapping table | `JettyGrpcUnaryHandler.java` |
| 6.3 | Add idle timeout configuration (aligned with MCP adapter pattern — 120s default) | `GrpcServerAdapter.java` |
| 6.4 | Schema test coverage for `ExposesGrpc` (all three modes) | existing schema test suite |
| 6.5 | Add Spectral rules for gRPC (e.g., `naftiko-grpc-namespaces-unique`, procedure validation) | `schemas/naftiko-rules.yml` |

---

## Risks and Mitigations

1. **`jetty-http2-server` introduces new HTTP/2 framing complexity**
   - The current codebase has `jetty-http2-common` 12.0.25 but no server-side HTTP/2 setup (`HTTP2CServerConnectionFactory`). This is new ground.
   - Mitigation: isolate HTTP/2 connector setup in `GrpcServerAdapter.initHttpTransport()`, keep h2c only in phase 1 to avoid ALPN/TLS complexity.

2. **gRPC framing is not abstracted by Jetty**
   - Jetty handles HTTP/2 frames, but gRPC message framing (compression flag + 4-byte length) is application-level and must be implemented manually.
   - Mitigation: isolate framing in a single utility class; cover with byte-level unit tests before wiring to Jetty.

3. **Semantic drift from MCP tools**
   - Mitigation: add cross-path parity tests comparing `ToolHandler` (MCP) and `GrpcProcedureHandler` results for identical call/steps/ref/output configurations.

4. **AggregateRefResolver complexity**
   - Adding a third adapter type to the resolver increases dispatch branches.
   - Mitigation: follow the existing `resolveMcpToolRef()` / `resolveRestOperationRef()` pattern exactly. The resolver iterates server specs by type — adding `GrpcServerSpec` iteration is additive and isolated.

5. **Unintended Servlet transitive dependency**
   - Some Jetty companion modules pull in Servlet APIs as transitive dependencies.
   - Mitigation: explicit `<exclusion>` blocks in pom.xml for Servlet artifacts; `mvn enforcer` rule to fail fast.

6. **Client tooling for `application/grpc+json`**
   - Standard `grpcurl` defaults to `application/grpc+proto`. JSON codec mode requires explicit flags (`-format json`).
   - Mitigation: document this in setup guide; provide a minimal test script using `grpcurl --format json`.

7. **`package` field being optional introduces path ambiguity**
   - If two services share the same name in different packages, and package is omitted, routing collides.
   - Mitigation: enforce namespace uniqueness in schema validation; recommend always setting `package` in production.

8. **Aggregate semantics have no gRPC wire equivalent**
   - MCP tools derive `hints` from aggregate `semantics`. gRPC has no standard behavioral annotation mechanism.
   - Mitigation: store semantics as runtime metadata for logging/observability. Defer custom `x-semantics-*` trailers to phase 2.

---

## Acceptance Criteria

1. Capability spec supports `type: grpc` with `procedures`.
2. Procedures use the same declarative structure and execution semantics as MCP tools.
3. Procedures support `ref` to aggregate functions — same inheritance and delegation model as MCP tools.
4. `AggregateRefResolver` resolves `ref` fields in gRPC procedures.
5. A single aggregate function can be exposed simultaneously via MCP tool, REST operation, and gRPC procedure (three adapters, one definition).
6. Runtime uses embedded Jetty 12.x only.
7. No Servlet dependency is present in compile or runtime dependencies.
8. Unary gRPC calls execute successfully for `call`, `steps`, and `ref` procedure modes.
9. Schema, docs, and tests are updated and aligned.