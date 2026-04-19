# Naftiko Specification - Schema

**Version:** {{RELEASE_TAG}}

**Publication Date:** March 2026

## 1. Introduction

The Naftiko Specification defines a standard, language-agnostic interface for describing modular, composable capabilities. In short, a **capability** is a functional unit that consumes external APIs (sources) and exposes adapters that allow other systems to interact with it.

A Naftiko capability focuses on declaring the **integration intent** — what a system needs to consume and what it exposes — rather than implementation details. This higher-level abstraction makes capabilities naturally suitable for AI-driven discovery, orchestration and integration use cases, and beyond. When properly defined, a capability can be discovered, orchestrated, validated and executed with minimal implementation logic. The specification enables description of:

- **Consumed sources**: External APIs or services that the capability uses
- **Exposed adapters**: Server interfaces that the capability provides (HTTP, REST, etc.)
- **Orchestration**: How calls to consumed sources are combined and mapped to realize exposed functions
- **External references**: Variables and resources resolved from external sources

### 1.1 Schema Access

The JSON Schema for the Naftiko Specification is available in two forms:

- **Raw file** — The schema source file is hosted on GitHub: [naftiko-schema.json](https://github.com/naftiko/framework/blob/main/src/main/resources/schemas/naftiko-schema.json)
- **Interactive viewer** — A human-friendly viewer is available at: [Schema Viewer](https://naftiko.github.io/schema-viewer/)

### 1.2 Core Objects

**Capability**: The central object that defines a modular functional unit with clear input/output contracts. 

**Consumes**: External sources (APIs, services) that the capability uses to realize its operations.

**Exposes**: Server adapters that provide access to the capability's operations.

**Resources**: API endpoints that group related operations.

**Operations**: Individual HTTP operations (GET, POST, etc.) that can be performed on resources.

**Namespace**: A unique identifier for consumed sources, used for routing and mapping with the expose layer.

**MCP Server**: An exposition adapter that exposes capability operations as MCP tools, enabling AI agent integration via Streamable HTTP or stdio transport.

**Skill Server**: An exposition adapter that groups MCP tools into named skills, enabling structured discovery and invocation of capability operations by skill name.

**Control Port**: A built-in management plane adapter (`type: "control"`) that exposes engine-provided endpoints for health checks, Prometheus metrics, distributed traces, and runtime diagnostics.

**Observability**: Spec-driven OpenTelemetry configuration for distributed tracing and RED metrics (Rate, Errors, Duration). Declared on the Control adapter via the `observability` field.

**Bind**: A declaration of an external binding providing variables to the capability via a `keys` map. An optional `location` URI identifies the value provider (file, vault, runtime, etc.).

### 1.3 Related Specifications.

<aside>
💡

**Acknowledgments** — The Naftiko Specification is inspired by and builds upon foundational work from [OpenAPI](https://www.openapis.org/), [Arazzo](https://spec.openapis.org/arazzo/latest.html), and [OpenCollections](https://opencollections.io/). We gratefully credit these initiatives and their communities for the patterns and conventions that informed this specification.

</aside>

Three specifications that work better together.

|  | **OpenAPI** | **Arazzo** | **OpenCollections** | **Naftiko** |
| --- | --- | --- | --- | --- |
| **Focus** | Defines *what* your API is — the contract, the schema, the structure. | Defines *how* API calls are sequenced — the workflows between endpoints. | Defines *how* to use your API — the scenarios, the runnable collections. | Defines *what* a capability consumes and exposes — the integration intent. |
| **Scope** | Single API surface | Workflows across one or more APIs | Runnable collections of API calls | Modular capability spanning multiple APIs |
| **Key strengths** | ✓ Endpoints & HTTP methods, ✓ Request/response schemas, ✓ Authentication requirements, ✓ Data types & validation, ✓ SDK & docs generation | ✓ Multi-step sequences, ✓ Step dependencies & data flow, ✓ Success/failure criteria, ✓ Reusable workflow definitions | ✓ Runnable, shareable collections, ✓ Pre-request scripts & tests, ✓ Environment variables, ✓ Living, executable docs | ✓ Consume/expose duality, ✓ Namespace-based routing, ✓ Orchestration & forwarding, ✓ AI-driven discovery, ✓ Composable capabilities |
| **Analogy** | The *parts list* and dimensions | The *assembly sequence* between parts | The *step-by-step assembly guide* you can run | The *product blueprint* — what goes in, what comes out |
| **Best used when you need to…** | Define & document an API contract, generate SDKs, validate payloads | Describe multi-step API workflows with dependencies | Share runnable API examples, test workflows, onboard developers | Declare a composable capability that consumes sources and exposes unified interfaces |

**OpenAPI** tells you the shape of the door. **Arazzo** describes the sequence of doors to walk through. **OpenCollections** lets you actually walk through them. **Naftiko** combines the features of those 3 specs into a single, coherent spec, reducing complexity and offering consistent tooling out of the box

---

## 2. Format

Naftiko specifications can be represented in YAML format, complying with the provided Naftiko schema which is made available in both JSON Schema and [JSON Structure](https://json-structure.org/) formats.

All field names in the specification are **case-sensitive**.

Naftiko Objects expose two types of fields:

- **Fixed fields**: which have a declared name
- **Patterned fields**: which have a declared pattern for the field name

---

## 3. Objects and Fields

### 3.1 Naftiko Object

This is the root object of the Naftiko document.

#### 3.1.1 Fixed Fields

| Field Name | Type | Description |
| --- | --- | --- |
| **naftiko** | `string` | **REQUIRED**. Version of the Naftiko schema. MUST be `"0.5"` for this version. |
| **info** | `Info` | *Recommended*. Metadata about the capability. |
| **capability** | `Capability` | **REQUIRED**. Technical configuration of the capability including sources and adapters. |
| **binds** | `Bind[]` | List of external bindings for variable injection. Each entry declares injected variables via a `keys` map. |

#### 3.1.2 Rules

- The `naftiko` field MUST be present and MUST have the value `"0.5"` for documents conforming to this version of the specification.
- The `capability` object MUST be present. The `info` object is recommended.
- The `binds` field is OPTIONAL. When present, it MUST contain at least one entry.
- No additional properties are allowed at the root level.

---

### 3.2 Info Object

Provides metadata about the capability.

#### 3.2.1 Fixed Fields

| Field Name | Type | Description |
| --- | --- | --- |
| **label** | `string` | **REQUIRED**. The display name of the capability. |
| **description** | `string` | *Recommended*. A description of the capability. The more meaningful it is, the easier for agent discovery. |
| **tags** | `string[]` | List of tags to help categorize the capability for discovery and filtering. |
| **created** | `string` | Date the capability was created (format: `YYYY-MM-DD`). |
| **modified** | `string` | Date the capability was last modified (format: `YYYY-MM-DD`). |
| **stakeholders** | `Person[]` | List of stakeholders related to this capability (for discovery and filtering). |

#### 3.2.2 Rules

- The `label` field is mandatory. The `description` field is recommended to improve agent discovery.
- No additional properties are allowed.

#### 3.2.3 Info Object Example

```yaml
info:
  label: Notion Page Creator
  description: Creates and manages Notion pages with rich content formatting
  tags:
    - notion
    - automation
  created: "2026-02-17"
  modified: "2026-02-17"
  stakeholders:
    - role: owner
      fullName: "Jane Doe"
      email: "jane.doe@example.
```

---

### 3.3 Person Object

Describes a person related to the capability.

#### 3.3.1 Fixed Fields

| Field Name | Type | Description |
| --- | --- | --- |
| **role** | `string` | **REQUIRED**. The role of the person in relation to the capability. E.g. owner, editor, viewer. |
| **fullName** | `string` | **REQUIRED**. The full name of the person. |
| **email** | `string` | The email address of the person. MUST match pattern `^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$`. |

#### 3.3.2 Rules

- Both `role` and `fullName` are mandatory.
- No additional properties are allowed.

#### 3.3.3 Person Object Example

```yaml
- role: owner
  fullName: "Jane Doe"
  email: "jane.doe@example.com"
```

---

### 3.4 Capability Object

Defines the technical configuration of the capability.

#### 3.4.1 Fixed Fields

| Field Name | Type | Description |
| --- | --- | --- |
| **exposes** | `Exposes[]` | List of exposed server adapters. Each entry is a REST Expose (`type: "rest"`), an MCP Expose (`type: "mcp"`), a Skill Expose (`type: "skill"`), or a Control Expose (`type: "control"`). |
| **consumes** | `Consumes[]`  | List of consumed client adapters. |
| **binds** | `Bind[]` | List of external bindings for variable injection. Each entry declares injected variables via a `keys` map. |
| **aggregates** | `Aggregate[]` | Domain aggregates defining reusable functions. Adapter units (tools, operations) reference them via `ref`. See [3.4.5 Aggregate Object](#345-aggregate-object). |


#### 3.4.2 Rules

- At least one of `exposes` or `consumes` MUST be present.
- When present, the `exposes` array MUST contain at least one entry.
- When present, the `consumes` array MUST contain at least one entry.
- Each `consumes` entry MUST include both `baseUri` and `namespace` fields.
- There are several types of exposed adapters and consumed sources objects, all will be described in following objects.
- The `binds` field is OPTIONAL. When present, it MUST contain at least one entry.
- The `aggregates` field is OPTIONAL. When present, it MUST contain at least one entry. Aggregate namespaces MUST be unique.
- No additional properties are allowed.

#### 3.4.3 Namespace Uniqueness Rule

When multiple `consumes` entries are present:

- Each `namespace` value MUST be unique across all consumes entries.
- The `namespace` field is used for routing from the expose layer to the correct consumed source.
- Duplicate namespace values will result in ambiguous routing and are forbidden.

#### 3.4.4 Capability Object Example

```yaml
capability:
  exposes:
        - type: rest
      port: 3000
      namespace: tasks-api
      resources:
        - path: /tasks
          description: "Endpoint to create tasks via the external API"
          operations:
            - method: POST
              label: Create Task
              call: api.create-task
              outputParameters:
                - type: string
                  mapping: $.taskId
  consumes:
    - type: http
      namespace: api
      baseUri: https://api.example.com
      resources:
        - name: tasks
          label: Tasks API
          path: /tasks
          operations:
            - name: create-task
              label: Create Task
              method: POST
              inputParameters:
                - name: task_id
                  in: path
              outputParameters:
                - name: taskId
                  type: string
                  value: $.data.id
```

---

### 3.4.5 Aggregate Object

A domain aggregate in the sense of [Domain-Driven Design (DDD)](https://en.wikipedia.org/wiki/Domain-driven_design#Building_blocks). Each aggregate groups reusable **functions** — transport-neutral invocable units that adapters reference via `ref`. This factorizes domain behavior so it is defined once and reused across REST, MCP, Skill, and future adapters without duplication.

> New in schema v1.0.0-alpha1.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **label** | `string` | **REQUIRED**. Human-readable name for this aggregate (e.g. `"Forecast"`). |
| **namespace** | `string` | **REQUIRED**. Machine-readable qualifier (`IdentifierKebab`). Used as the first segment in `ref` values (`{aggregate-namespace}.{function-name}`). |
| **functions** | `AggregateFunction[]` | **REQUIRED**. Reusable invocable units within this aggregate (minimum 1). |

**Rules:**

- All three fields are mandatory.
- The `namespace` MUST be unique across all aggregates.
- No additional properties are allowed.

#### 3.4.5.1 AggregateFunction Object

A reusable invocable unit within an aggregate. Adapter units (MCP tools, REST operations) reference it via `ref: {aggregate-namespace}.{function-name}`. Referenced fields are merged into the adapter unit; adapter-local explicit fields override inherited ones.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **name** | `string` | **REQUIRED**. Function name (`IdentifierKebab`). Combined with aggregate namespace to form the ref target. |
| **description** | `string` | **REQUIRED**. A meaningful description of the function. Inherited by adapter units that omit their own. |
| **semantics** | `Semantics` | Transport-neutral behavioral metadata. Automatically derived into adapter-specific metadata (e.g. MCP hints). See [3.4.5.2 Semantics Object](#3452-semantics-object). |
| **inputParameters** | `McpToolInputParameter[]` | Input parameters for this function. |
| **call** | `string` | **Simple mode**. Reference to consumed operation (`{namespace}.{operationId}`). |
| **with** | `WithInjector` | **Simple mode**. Parameter injection for the called operation. |
| **steps** | `OperationStep[]` | **Orchestrated mode**. Sequence of calls to consumed operations (minimum 1). |
| **mappings** | `StepOutputMapping[]` | **Orchestrated mode**. Maps step outputs to function output parameters. |
| **outputParameters** (simple) | `MappedOutputParameter[]` | **Simple mode**. Output params mapped from consumed operation response. |
| **outputParameters** (orchestrated) | `OrchestratedOutputParameter[]` | **Orchestrated mode**. Named, typed output parameters. |

**Modes:**

Same two modes as McpTool and ExposedOperation:

- **Simple mode** — `call` is REQUIRED, `with` optional, `steps` MUST NOT be present.
- **Orchestrated mode** — `steps` is REQUIRED, `mappings` optional, `call` and `with` MUST NOT be present.

**Rules:**

- `name` and `description` are mandatory.
- Exactly one mode MUST be used.
- Function names MUST be unique within an aggregate.
- No chained refs — a function cannot itself use `ref`.
- No additional properties are allowed.

#### 3.4.5.2 Semantics Object

Transport-neutral behavioral metadata for an invocable unit. These properties describe the function's intent independent of any transport protocol. The framework automatically derives adapter-specific metadata from semantics — for example, MCP tool `hints` are derived from `semantics` at capability load time (see [Semantics-to-Hints derivation](#3453-semantics-to-hints-derivation)).

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **safe** | `boolean` | If `true`, the function does not modify state. Default: `false`. |
| **idempotent** | `boolean` | If `true`, repeating the call has no additional effect. Default: `false`. |
| **cacheable** | `boolean` | If `true`, the result can be cached. Default: `false`. |

**Rules:**

- All fields are optional. Omitted fields fall back to their defaults.
- No additional properties are allowed.

#### 3.4.5.3 Semantics-to-Hints Derivation

When an MCP tool references an aggregate function via `ref`, the function's `semantics` are automatically derived into MCP `hints` (`McpToolHints`). Explicit `hints` on the MCP tool override derived values field by field.

**Mapping table:**

| Aggregate `semantics` | Derived MCP `hints` | Rationale |
| --- | --- | --- |
| `safe: true` | `readOnly: true`, `destructive: false` | A safe function doesn't change state |
| `safe: false` (or absent) | `readOnly: false` | Default — may have side effects |
| `idempotent: true` | `idempotent: true` | Direct 1:1 mapping |
| `cacheable` | *(not mapped)* | No MCP equivalent; informational for future adapters |
| *(no semantic)* | `openWorld` not derived | `openWorld` is MCP-specific context; set explicitly at tool level |

**Override rule:** Each non-null field in the tool-level `hints` wins over the derived value. Absent fields in the tool-level `hints` still inherit from semantics.

#### 3.4.5.4 Aggregate Object Example

```yaml
capability:
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
            - type: "string"
              mapping: "$.forecast"

  exposes:
    - type: "mcp"
      namespace: "forecast-mcp"
      tools:
        # Minimal ref — name, description, call, params, outputs all inherited
        - ref: "forecast.get-forecast"

        # Override only the name; everything else inherited
        - ref: "forecast.get-forecast"
          name: "weather"

        # Add MCP-specific openWorld hint; readOnly/destructive/idempotent derived
        - ref: "forecast.get-forecast"
          name: "weather-open"
          hints:
            openWorld: true

    - type: "rest"
      namespace: "forecast-rest"
      resources:
        - path: "/forecast/{location}"
          name: "forecast"
          description: "Forecast resource"
          operations:
            - ref: "forecast.get-forecast"
              method: "GET"
              inputParameters:
                - name: "location"
                  in: "path"
                  type: "string"
                  description: "City name or coordinates"

  consumes:
    - type: "http"
      namespace: "weather-api"
      baseUri: "https://api.weather.example.com/v1"
      resources:
        - path: "forecast"
          name: "forecast"
          operations:
            - method: "GET"
              name: "get-forecast"
```

---

### 3.5 Exposes Object

Describes a server adapter that exposes functionality.

> Update (schema v0.5): Two exposition adapter types are now supported — **REST** (`type: "rest"`) and **MCP** (`type: "mcp"`). Legacy `httpProxy` and `api` exposition types are not part of the JSON Schema anymore.
> 
> Update (schema v1.0.0-alpha1): **Skill** (`type: "skill"`) and **Control** (`type: "control"`) adapter types were added.
> 

#### 3.5.1 REST Expose

REST exposition configuration.

> Update (schema v0.5): The Exposes object is now a discriminated union (`oneOf`) between **REST** (`type: "rest"`, this section), **MCP** (`type: "mcp"`, see §3.5.4), **Skill** (`type: "skill"`, see §3.5.9), and **Control** (`type: "control"`, see §3.5.11). The `type` field acts as discriminator.
> 

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. MUST be `"rest"`. |
| **address** | `string` | Server address. Can be a hostname, IPv4, or IPv6 address. |
| **port** | `integer` | **REQUIRED**. Port number. MUST be between 1 and 65535. |
| **authentication** | `Authentication` | Authentication configuration. |
| **namespace** | `string` | **REQUIRED**. Unique identifier for this exposed API. |
| **resources** | `ExposedResource[]` | **REQUIRED**. List of exposed resources. |

#### 3.5.2 ExposedResource Object

An exposed resource with **operations** and/or **forward** configuration.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **path** | `string` | **REQUIRED**. Path of the resource (supports `param` placeholders). |
| **description** | `string` | *Recommended*. Used to provide *meaningful* information about the resource. In a world of agents, context is king. |
| **name** | `string` | Technical name for the resource (used for references, pattern `^[a-zA-Z0-9-]+$`). |
| **label** | `string` | Display name for the resource (likely used in UIs). |
| **inputParameters** | `ExposedInputParameter[]` | Input parameters attached to the resource. |
| **operations** | `ExposedOperation[]` | Operations available on this resource. |
| **forward** | `ForwardConfig` | Forwarding configuration to a consumed namespace. |

#### 3.5.3 Rules

- The `path` field is mandatory. The `description` field is recommended to provide meaningful context for agent discovery.
- At least one of `operations` or `forward` MUST be present. Both can coexist on the same resource.
- if both `operations` or `forward` are present, in case of conflict, `operations` takes precendence on `forward`.
- No additional properties are allowed.

#### 3.5.4 MCP Expose

MCP Server exposition configuration. Exposes capability operations as MCP tools, resources, and prompt templates over Streamable HTTP or stdio transport.

> New in schema v0.5.
> 

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. MUST be `"mcp"`. |
| **transport** | `string` | Transport protocol. One of: `"http"` (default), `"stdio"`. `"http"` exposes a Streamable HTTP server; `"stdio"` uses stdin/stdout JSON-RPC for local IDE integration. |
| **address** | `string` | Server address. Can be a hostname, IPv4, or IPv6 address. |
| **port** | `integer` | **REQUIRED when transport is `"http"`**. Port number (1–65535). MUST NOT be present when transport is `"stdio"`. |
| **namespace** | `string` | **REQUIRED**. Unique identifier for this exposed MCP server. |
| **description** | `string` | *Recommended*. A meaningful description of the MCP server's purpose. Sent as server instructions during MCP initialization. |
| **tools** | `McpTool[]` | **REQUIRED**. List of MCP tools exposed by this server (minimum 1). |
| **resources** | `McpResource[]` | List of MCP resources exposed by this server. Resources provide data that agents can read. Optional (minimum 1 entry when present). |
| **prompts** | `McpPrompt[]` | List of MCP prompt templates exposed by this server. Prompts provide reusable, parameterized message templates for AI agents. Optional (minimum 1 entry when present). |

**Rules:**

- The `type` field MUST be `"mcp"`.
- The `namespace` field is mandatory and MUST be unique across all exposes entries.
- The `tools` array is mandatory and MUST contain at least one entry.
- When `transport` is `"http"` (or omitted, since `"http"` is the default), the `port` field is required.
- When `transport` is `"stdio"`, the `port` field MUST NOT be present.
- When present, the `resources` array MUST contain at least one entry.
- When present, the `prompts` array MUST contain at least one entry.
- No additional properties are allowed.

**MCP Initialize Capabilities:**

During the MCP `initialize` handshake, the Naftiko runtime advertises the server's supported capability groups to the connecting client. The advertised capabilities are derived directly from the MCP Expose configuration:

- **`tools`** — advertised when the `tools` array is present and contains at least one entry.
- **`resources`** — advertised when the `resources` array is present and contains at least one entry.
- **`prompts`** — advertised when the `prompts` array is present and contains at least one entry.

Capability groups not declared in the configuration are omitted from the `initialize` response. Clients MUST NOT assume a capability is available unless it is explicitly advertised during initialization.

#### 3.5.5 McpTool Object

An MCP tool definition. Each tool maps to one or more consumed HTTP operations, similar to ExposedOperation but adapted for the MCP protocol (no HTTP method, tool-oriented input schema).

> The McpTool supports the same two modes as ExposedOperation: **simple** (direct `call` + `with`) and **orchestrated** (multi-step with `steps` + `mappings`). Additionally, a tool can use **`ref`** to reference an aggregate function, inheriting its fields.
> 

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **name** | `string` | Technical name for the tool. Used as the MCP tool name. MUST match pattern `^[a-zA-Z0-9-]+$`. **REQUIRED** unless `ref` is used (inherited from function). |
| **label** | `string` | Human-readable display name for the tool. Mapped to MCP `title` in protocol responses. |
| **description** | `string` | A meaningful description of the tool. Essential for agent discovery. **REQUIRED** unless `ref` is used (inherited from function). |
| **ref** | `string` | Reference to an aggregate function. Format: `{aggregate-namespace}.{function-name}`. Inherited fields are merged; explicit fields override. See [3.4.5 Aggregate Object](#345-aggregate-object). |
| **hints** | `McpToolHints` | Optional behavioral hints for MCP clients. Mapped to `ToolAnnotations` in the MCP protocol. When `ref` is used, hints are automatically derived from the function's `semantics`; explicit values override derived ones. See [3.5.5.1 McpToolHints Object](#3551-mctoolhints-object). |
| **inputParameters** | `McpToolInputParameter[]` | Tool input parameters. These become the MCP tool's input schema (JSON Schema). |
| **call** | `string` | **Simple mode only**. Reference to a consumed operation. Format: `{namespace}.{operationId}`. MUST match pattern `^[a-zA-Z0-9-]+\.[a-zA-Z0-9-]+$`. |
| **with** | `WithInjector` | **Simple mode only**. Parameter injection for the called operation. |
| **steps** | `OperationStep[]` | **Orchestrated mode only. REQUIRED** (at least 1 step). Sequence of calls to consumed operations. |
| **mappings** | `StepOutputMapping[]` | **Orchestrated mode only**. Maps step outputs to the tool's output parameters. |
| **outputParameters** (simple) | `MappedOutputParameter[]` | **Simple mode**. Output parameters mapped from the consumed operation response. |
| **outputParameters** (orchestrated) | `OrchestratedOutputParameter[]` | **Orchestrated mode**. Output parameters with name and type. |

**Modes:**

**Simple mode** — direct call to a single consumed operation:

- `call` is **REQUIRED**
- `with` is optional
- `outputParameters` are `MappedOutputParameter[]`
- `steps` MUST NOT be present

**Orchestrated mode** — multi-step orchestration:

- `steps` is **REQUIRED** (at least 1 entry)
- `mappings` is optional
- `outputParameters` are `OrchestratedOutputParameter[]`
- `call` and `with` MUST NOT be present

**Ref mode** — reference to an aggregate function:

- `ref` is **REQUIRED**
- All other fields are optional — inherited from the referenced function
- Explicit fields override inherited ones (field-level merge)
- `hints` are automatically derived from the function's `semantics` (see [3.4.5.3](#3453-semantics-to-hints-derivation))

**Rules:**

- Exactly one mode MUST be used: simple (`call`), orchestrated (`steps`), or ref (`ref`).
- In simple and orchestrated modes, `name` and `description` are mandatory.
- In ref mode, `name` and `description` are optional (inherited from the function).
- In simple mode, `call` MUST follow the format `{namespace}.{operationId}` and reference a valid consumed operation.
- In orchestrated mode, the `steps` array MUST contain at least one entry.
- Input parameters are accessed via namespace-qualified references of the form `{mcpNamespace}.{paramName}`.
- No additional properties are allowed.

#### 3.5.5.1 McpToolHints Object

Optional behavioral hints describing a tool to MCP clients. All properties are advisory — clients SHOULD NOT make trust decisions based on these values from untrusted servers. Mapped to `ToolAnnotations` in the MCP protocol wire format.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **readOnly** | `boolean` | If `true`, the tool does not modify its environment. Default: `false`. |
| **destructive** | `boolean` | If `true`, the tool may perform destructive updates. Meaningful only when `readOnly` is `false`. Default: `true`. |
| **idempotent** | `boolean` | If `true`, calling the tool repeatedly with the same arguments has no additional effect. Meaningful only when `readOnly` is `false`. Default: `false`. |
| **openWorld** | `boolean` | If `true`, the tool may interact with external entities (e.g. web APIs). If `false`, the tool's domain is closed (e.g. local data). Default: `true`. |

**Rules:**

- All fields are optional. Omitted fields fall back to their defaults.
- `destructive` and `idempotent` are only meaningful when `readOnly` is `false`.
- No additional properties are allowed.

**McpToolHints Example:**

```yaml
tools:
  - name: get-current-weather
    description: "Retrieve current weather conditions"
    hints:
      readOnly: true
      openWorld: true
    call: weather-api.get-current
```

#### 3.5.6 McpToolInputParameter Object

Declares an input parameter for an MCP tool. These become properties in the tool's JSON Schema input definition.

> Unlike `ExposedInputParameter`, MCP tool parameters have no `in` field (no HTTP location concept) and include a `required` flag.
> 

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **name** | `string` | **REQUIRED**. Parameter name. Becomes a property name in the tool's input schema. MUST match pattern `^[a-zA-Z0-9-_*]+$`. |
| **type** | `string` | **REQUIRED**. Data type. One of: `string`, `number`, `integer`, `boolean`, `array`, `object`. |
| **description** | `string` | **REQUIRED**. A meaningful description of the parameter. Used for agent discovery and tool documentation. |
| **required** | `boolean` | Whether the parameter is required. Defaults to `true`. |

**Rules:**

- The `name`, `type`, and `description` fields are all mandatory.
- The `type` field MUST be one of: `"string"`, `"number"`, `"integer"`, `"boolean"`, `"array"`, `"object"`.
- The `required` field defaults to `true` when omitted.
- No additional properties are allowed.

**McpToolInputParameter Example:**

```yaml
- name: database_id
  type: string
  description: The unique identifier of the Notion database
- name: page_size
  type: number
  description: Number of results per page (max 100)
  required: false
```

#### 3.5.7 McpResource Object

An MCP resource definition. Resources expose data that agents can **read** (but not invoke like tools). Two source types are supported: **dynamic** (backed by consumed HTTP operations) and **static** (served from local files).

> New in schema v0.5.
> 

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **name** | `string` | **REQUIRED**. Technical name for the resource. MUST match pattern `^[a-zA-Z0-9-]+$`. |
| **label** | `string` | Human-readable display name. Mapped to MCP `title` in protocol responses. |
| **uri** | `string` | **REQUIRED**. The URI that identifies this resource in MCP. Can use any scheme (e.g. `config://app/current`, `docs://api/reference`). For resource templates, use `{param}` placeholders. |
| **description** | `string` | *Recommended*. A meaningful description of the resource. In a world of agents, context is king. |
| **mimeType** | `string` | MIME type of the resource content per RFC 6838 (e.g. `application/json`, `text/markdown`). Optional parameters are supported (e.g. `charset=utf-8`). |
| **call** | `string` | **Dynamic mode only**. Reference to a consumed operation. Format: `{namespace}.{operationId}`. MUST match pattern `^[a-zA-Z0-9-]+\.[a-zA-Z0-9-]+$`. |
| **with** | `WithInjector` | **Dynamic mode only**. Parameter injection for the called operation. |
| **steps** | `OperationStep[]` | **Orchestrated dynamic mode only**. Sequence of calls to consumed operations (minimum 1). |
| **mappings** | `StepOutputMapping[]` | **Orchestrated dynamic mode only**. Maps step outputs to the resource's output parameters. |
| **outputParameters** | `MappedOutputParameter[]` or `OrchestratedOutputParameter[]` | Output parameters mapped from the consumed operation response. Type depends on mode (simple vs. orchestrated). |
| **location** | `string` | **Static mode only**. A `file:///` URI pointing to a directory. Files under that directory are served as individual MCP resources with URIs auto-generated from the `uri` prefix and relative file paths. |

**Modes:**

**Dynamic mode** — backed by consumed HTTP operations (same orchestration model as McpTool):

- Uses `call`/`steps`/`with`/`mappings`/`outputParameters`
- `location` MUST NOT be present
- Two sub-modes: **simple** (`call` + optional `with`) and **orchestrated** (`steps` + optional `mappings`)

**Static mode** — served from local files:

- `location` is **REQUIRED**: a `file:///` URI pointing to a directory
- Files in the directory become individual MCP resources; URIs are auto-generated from the `uri` prefix and relative paths
- `call`, `steps`, `with`, `mappings`, and `outputParameters` MUST NOT be present

**Rules:**

- The `name` and `uri` fields are mandatory. The `description` field is recommended for agent discovery.
- Each resource `name` MUST be unique within the MCP server.
- Each resource `uri` MUST be unique within the MCP server.
- Exactly one of `call`/`steps` (dynamic) or `location` (static) MUST be present.
- In dynamic simple mode, `call` MUST follow the format `{namespace}.{operationId}` and reference a valid consumed operation.
- The `location` value MUST start with `file:///` and the resolved directory MUST exist at startup.
- No additional properties are allowed.

**McpResource Object Examples:**

```yaml
# Dynamic resource (simple mode)
resources:
  - name: current-config
    label: Current Configuration
    uri: config://app/current
    description: "Current application configuration"
    mimeType: application/json
    call: config-api.get-config

# Dynamic resource (orchestrated mode)
resources:
  - name: user-summary
    label: User Summary
    uri: data://users/summary
    description: "Aggregated user summary from multiple API calls"
    mimeType: application/json
    steps:
      - type: call
        name: fetch-users
        call: user-api.list-users
      - type: call
        name: fetch-stats
        call: analytics-api.get-stats
    outputParameters:
      - name: users
        type: array
      - name: stats
        type: object

# Static resource (local files)
resources:
  - name: api-docs
    label: API Documentation
    uri: docs://api/reference
    description: "API reference documentation served from local markdown files"
    mimeType: text/markdown
    location: file:///etc/naftiko/resources/api-docs
```

---

#### 3.5.8 McpPrompt Object

An MCP prompt template definition. Prompts provide reusable, parameterized message templates that AI agents can invoke to get pre-structured LLM conversation starters or guided interactions.

> New in schema v0.5.
> 

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **name** | `string` | **REQUIRED**. Technical name for the prompt. Used as the MCP prompt name. MUST match pattern `^[a-zA-Z0-9-]+$`. |
| **label** | `string` | Human-readable display name. Mapped to MCP `title` in protocol responses. |
| **description** | `string` | *Recommended*. A meaningful description of the prompt's purpose. Essential for agent discovery. |
| **arguments** | `McpPromptArgument[]` | List of arguments accepted by this prompt template. These become the prompt's input schema. |
| **messages** | `McpPromptMessage[]` | **REQUIRED**. List of messages that form the prompt template (minimum 1). Each message defines a role and its content. |

**Rules:**

- The `name` field is mandatory.
- Each prompt `name` MUST be unique within the MCP server.
- The `messages` array is mandatory and MUST contain at least one entry.
- No additional properties are allowed.

#### McpPromptArgument Object

Declares an argument accepted by the prompt template.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **name** | `string` | **REQUIRED**. Argument name. MUST match pattern `^[a-zA-Z0-9-_]+$`. |
| **description** | `string` | *Recommended*. A meaningful description of the argument. |
| **required** | `boolean` | Whether the argument is required. Defaults to `false`. |

**Rules:**

- The `name` field is mandatory.
- Each argument `name` MUST be unique within the prompt.
- No additional properties are allowed.

#### McpPromptMessage Object

Defines a single message in the prompt template. Messages can be static (inline `content`) or dynamic (backed by a consumed operation via `call` or `steps`).

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **role** | `string` | **REQUIRED**. The role of the message sender. One of: `"user"`, `"assistant"`. |
| **content** | `string` | **Static mode**. Inline text content of the message. Argument placeholders use `{argumentName}` syntax. |
| **call** | `string` | **Dynamic mode only**. Reference to a consumed operation. Format: `{namespace}.{operationId}`. |
| **with** | `WithInjector` | **Dynamic mode only**. Parameter injection for the called operation. |
| **steps** | `OperationStep[]` | **Orchestrated dynamic mode only**. Sequence of calls to consumed operations (minimum 1). |
| **mappings** | `StepOutputMapping[]` | **Orchestrated dynamic mode only**. Maps step outputs to the message content. |
| **outputParameters** | `MappedOutputParameter[]` or `OrchestratedOutputParameter[]` | **Dynamic mode**. Output parameters mapped from the consumed operation response. |

**Rules:**

- The `role` field is mandatory and MUST be one of `"user"` or `"assistant"`.
- Exactly one of `content` (static) or `call`/`steps` (dynamic) MUST be present.
- No additional properties are allowed.

**McpPrompt Object Example:**

```yaml
prompts:
  - name: code-review
    label: Code Review Request
    description: "Generates a structured code review prompt for a given pull request"
    arguments:
      - name: pr_title
        description: "Title of the pull request"
        required: true
      - name: language
        description: "Programming language of the code"
        required: false
    messages:
      - role: user
        content: "Please review the following pull request: {pr_title}. Focus on correctness, performance, and maintainability."
      - role: assistant
        content: "I'll review the pull request '{pr_title}' with attention to {language} best practices."
```

---

#### 3.5.9 Skill Expose

Skill Server exposition configuration. Groups MCP tools into named skills, providing a structured discovery layer for AI agents and orchestrators.

> New in schema v0.5.
> 

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. MUST be `"skill"`. |
| **namespace** | `string` | **REQUIRED**. Unique identifier for this exposed Skill Server. |
| **port** | `integer` | Port number (1–65535). Optional. |
| **description** | `string` | *Recommended*. A meaningful description of the Skill Server's purpose. |
| **skills** | `ExposedSkill[]` | **REQUIRED**. List of skills exposed by this server (minimum 1). |

**Rules:**

- The `type` field MUST be `"skill"`.
- The `namespace` field is mandatory and MUST be unique across all exposes entries.
- The `skills` array is mandatory and MUST contain at least one entry.
- No additional properties are allowed.

#### ExposedSkill Object

A named skill that groups one or more MCP tools.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **name** | `string` | **REQUIRED**. Technical name for the skill. MUST match pattern `^[a-zA-Z0-9-]+$`. |
| **label** | `string` | Human-readable display name for the skill. |
| **description** | `string` | *Recommended*. A meaningful description of the skill's purpose. Essential for agent discovery. |
| **tools** | `SkillTool[]` | **REQUIRED**. List of MCP tools included in this skill (minimum 1). |

**Rules:**

- The `name` and `tools` fields are mandatory.
- Each skill `name` MUST be unique within the Skill Server.
- The `tools` array MUST contain at least one entry.
- No additional properties are allowed.

#### SkillTool Object

A reference to an MCP tool included in a skill. By default, references a tool by name from the same capability. The optional `from` field allows referencing a tool from a different MCP server.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **name** | `string` | **REQUIRED**. Name of the MCP tool to include. MUST match the `name` of an existing `McpTool` in the referenced MCP server. |
| **from** | `SkillToolFrom` | Optional. Identifies the source MCP server when the tool originates from a different MCP expose entry. If omitted, the tool is resolved within the default MCP server namespace. |

**Rules:**

- The `name` field is mandatory.
- When `from` is present, the `namespace` within `from` MUST reference a valid MCP expose `namespace` in the same capability.
- No additional properties are allowed.

#### SkillToolFrom Object

Identifies the source MCP server for a skill tool reference.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **namespace** | `string` | The `namespace` of the MCP expose entry that owns the tool. |
| **name** | `string` | The tool name in the source MCP server. When omitted, the enclosing `SkillTool.name` is used. |

**Rules:**

- No additional properties are allowed.

**Skill Expose Example:**

```yaml
type: skill
namespace: my-skills
description: "Skill-based interface grouping Notion tools by domain"
skills:
  - name: database-ops
    label: Database Operations
    description: "Skills for reading and querying Notion databases"
    tools:
      - name: get-database
      - name: query-database
        from:
          namespace: notion-tools
  - name: page-ops
    label: Page Operations
    description: "Skills for creating and retrieving Notion pages"
    tools:
      - name: create-page
      - name: get-page
```

---

#### 3.5.10 Control Expose

Control adapter — a built-in management plane for health checks, metrics, traces, and runtime diagnostics. Unlike business adapters, the control port does not expose user-defined resources or operations. All endpoints are engine-provided.

> New in schema v1.0.0-alpha1.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. MUST be `"control"`. |
| **address** | `string` | Bind address for the control port. Defaults to `localhost` for security. |
| **port** | `integer` | **REQUIRED**. TCP port for the control adapter (1–65535). |
| **authentication** | `Authentication` | Optional authentication for the control port. Reuses the same Authentication model as business adapters. |
| **management** | `ControlManagementSpec` | Toggle individual management endpoint groups. |
| **observability** | `ObservabilitySpec` | Spec-driven observability configuration. Controls distributed tracing, metrics collection, and their local exposure on the control port. See [3.5.11 Observability Objects](#3511-observability-objects). |

**Rules:**

- The `type` field MUST be `"control"`.
- The `port` field is mandatory.
- At most **one** `type: "control"` adapter is allowed per capability.
- The control port MUST NOT share a port number with any business adapter (`rest`, `mcp`, `skill`).
- The `address` field defaults to `localhost`. Binding to a non-localhost address exposes management endpoints to the network — use authentication when doing so.
- No additional properties are allowed.

#### ControlManagementSpec Object

Toggles individual control port management endpoint groups. Does not include OTel-dependent endpoints (metrics, traces) — those are configured under observability.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **health** | `boolean` | Enable `/health/live` and `/health/ready` endpoints. Default: `true`. |
| **info** | `boolean` | Enable `/status` and `/config` endpoints. Default: `false`. |
| **reload** | `boolean` | Enable `POST /config/reload`. Default: `false`. |
| **validate** | `boolean` | Enable `POST /config/validate` (dry-run validation). Default: `false`. |
| **logs** | `boolean` or `ControlLogsEndpointSpec` | Configure `/logs` endpoints. Accepts `true` (enable all with defaults), `false`/omitted (disable all), or an object for advanced configuration. Default: `false`. |

**Rules:**

- No additional properties are allowed.

#### ControlLogsEndpointSpec Object

Advanced configuration for `/logs` endpoints (log level control and SSE streaming). Used when `logs` is an object.

| Field Name | Type | Description |
| --- | --- | --- |
| **level-control** | `boolean` | Enable `/logs` and `/logs/{logger}` endpoints for live log level control. Default: `true` (enabled when the object form is used). |
| **stream** | `boolean` | Enable `/logs/stream` SSE endpoint. Default: `false`. |
| **max-subscribers** | `integer` | Maximum concurrent SSE subscribers for log streaming (1–20). Default: `5`. |

**Shorthand expansion:**

- `logs: true` is equivalent to `logs: { level-control: true, stream: true, max-subscribers: 5 }`
- `logs: false` (or omitted) is equivalent to `logs: { level-control: false, stream: false }`

**Control Expose Example:**

```yaml
- type: control
  port: 9090
  management:
    info: true
    logs: true
  observability:
    traces:
      local:
        buffer-size: 200
```

---

#### 3.5.11 Observability Objects

Spec-driven observability configuration. Controls distributed tracing, metrics collection, and their local exposure on the control port via OpenTelemetry. All fields are optional — defaults to OTel environment variables when not specified.

> New in schema v1.0.0-alpha2.

##### ObservabilitySpec

| Field Name | Type | Description |
| --- | --- | --- |
| **enabled** | `boolean` | Enable or disable observability. Default: `true`. |
| **metrics** | `ObservabilityMetricsSpec` | Metrics collection and local exposure configuration. |
| **traces** | `ObservabilityTracesSpec` | Trace sampling, propagation, and local exposure configuration. |
| **exporters** | `ObservabilityExportersSpec` | Exporter configuration container. |

##### ObservabilityMetricsSpec

| Field Name | Type | Description |
| --- | --- | --- |
| **local** | `ObservabilityLocalEndpointSpec` | Controls the `/metrics` Prometheus scrape endpoint on the control port. Default: enabled. |

##### ObservabilityTracesSpec

| Field Name | Type | Description |
| --- | --- | --- |
| **sampling** | `number` | Trace sampling rate. `1.0` = sample all, `0.1` = sample 10%. Range: 0–1. Default: `1.0`. |
| **propagation** | `string` | Context propagation format for outgoing HTTP calls. One of: `"w3c"`, `"b3"`. Default: `"w3c"`. |
| **local** | `ObservabilityTracesLocalSpec` | Controls the `/traces` endpoint on the control port. |

##### ObservabilityLocalEndpointSpec

| Field Name | Type | Description |
| --- | --- | --- |
| **enabled** | `boolean` | Enable or disable the local endpoint. Default: `true`. |

##### ObservabilityTracesLocalSpec

| Field Name | Type | Description |
| --- | --- | --- |
| **enabled** | `boolean` | Enable the `/traces` endpoint. Default: `true`. |
| **buffer-size** | `integer` | Maximum number of completed trace summaries to retain in the ring buffer (10–10000). Default: `100`. |

##### ObservabilityExportersSpec

| Field Name | Type | Description |
| --- | --- | --- |
| **otlp** | `ObservabilityOtlpExporterSpec` | OTLP exporter endpoint configuration. |

##### ObservabilityOtlpExporterSpec

| Field Name | Type | Description |
| --- | --- | --- |
| **endpoint** | `string` | **REQUIRED**. OTLP exporter endpoint. Supports Mustache expressions for binds (e.g. `{{otel_endpoint}}`). |

**Observability Example:**

```yaml
capability:
  exposes:
    - type: control
      port: 9090
      observability:
        traces:
          sampling: 1.0
          propagation: w3c
        exporters:
          otlp:
            endpoint: "{{otel_endpoint}}"
```

---

#### 3.5.12 Address Validation Patterns

- **Hostname**: `^([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$`
- **IPv4**: `^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$`
- **IPv6**: `^([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}$`

#### 3.5.13 Exposes Object Examples

**REST Expose with operations:**

```yaml
type: rest
port: 3000
namespace: sample
resources:
  - path: /status
    description: "Health check endpoint"
    name: health
    operations:
      - name: get-status
        method: GET
        call: api.health-check
        outputParameters:
          - type: string
            mapping: $.status
```

**REST Expose with forward:**

```yaml
type: rest
port: 8080
namespace: proxy
resources:
  - path: /notion/{path}
    description: "Forward requests to the Notion API"
    forward:
      targetNamespace: notion
      trustedHeaders:
        - Notion-Version
```

**REST Expose with both operations and forward:**

```yaml
type: rest
port: 9090
namespace: hybrid
resources:
  - path: /data/{path}
    description: "Resource with orchestrated operations and pass-through forwarding"
    operations:
      - name: get-summary
        method: GET
        call: api.get-summary
    forward:
      targetNamespace: api
      trustedHeaders:
        - Authorization
```

**MCP Expose with a single tool:**

```yaml
type: mcp
port: 3001
namespace: tools
description: "AI-facing tools for database operations"
tools:
  - name: get-database
    description: "Retrieve metadata about a database by its ID"
    inputParameters:
      - name: database_id
        type: string
        description: "The unique identifier of the database"
    call: api.get-database
    with:
      database_id: "tools.database_id"
```

---

### 3.6 Consumes Object

Describes a client adapter for consuming external APIs.

> Update (schema v0.5): `targetUri` is now `baseUri`. The `headers` field has been removed — use `inputParameters` with `in: "header"` instead.
> 

#### 3.6.1 Fixed Fields

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. Type of consumer. Valid values: `"http"`. |
| **namespace** | `string` | Path suffix used for routing from exposes. MUST match pattern `^[a-zA-Z0-9-]+$`. |
| **baseUri** | `string` | **REQUIRED**. Base URI for the consumed API. Must be a valid http(s) URL (no `path` placeholder in the schema). |
| **authentication** | Authentication Object | Authentication configuration. Defaults to `"inherit"`. |
| **description** | `string` | *Recommended*. A description of the consumed API. The more meaningful it is, the easier for agent discovery. |
| **inputParameters** | `ConsumedInputParameter[]` | Input parameters applied to all operations in this consumed API. |
| **resources** | [ConsumedHttpResource Object] | **REQUIRED**. List of API resources. |

#### 3.6.2 Rules

- The `type` field MUST be `"http"`.
- The `baseUri` field is required.
- The `namespace` field is required and MUST be unique across all consumes entries.
- The `namespace` value MUST match the pattern `^[a-zA-Z0-9-]+$` (alphanumeric and hyphens only).
- The `description` field is recommended to improve agent discovery.
- The `resources` array is required and MUST contain at least one entry.

#### 3.6.3 Base URI Format

The `baseUri` field MUST be a valid `http://` or `https://` URL, and may optionally include a base path.

Example: `https://api.github.com` or `https://api.github.com/v3`

#### 3.6.4 Consumes Object Example

```yaml
type: http
namespace: github
baseUri: https://api.github.com
authentication:
  type: bearer
  token: "{{github_token}}"
inputParameters:
  - name: Accept
    in: header
    value: "application/vnd.github.v3+json"
resources:
  - name: users
    label: Users API
    path: /users/{username}
    operations:
      - name: get-user
        label: Get User
        method: GET
        inputParameters:
          - name: username
            in: path
        outputParameters:
          - name: userId
            type: string
            value: $.id
  - name: repos
    label: Repositories API
    path: /users/{username}/repos
    operations:
      - name: list-repos
        label: List Repositories
        method: GET
        inputParameters:
          - name: username
            in: path
        outputParameters:
          - name: repos
            type: array
            value: $
```

---

### 3.7 ConsumedHttpResource Object

Describes an API resource that can be consumed from an external API.

#### 3.7.1 Fixed Fields

| Field Name | Type | Description |
| --- | --- | --- |
| **name** | `string` | **REQUIRED**. Technical name for the resource. MUST match pattern `^[a-zA-Z0-9-]+$`. |
| **label** | `string` | Display name of the resource. |
| **description** | `string` | Description of the resource. |
| **path** | `string` | **REQUIRED**. Path of the resource, relative to the consumes `baseUri`. |
| **inputParameters** | `ConsumedInputParameter[]` | Input parameters for this resource. |
| **operations** | `ConsumedHttpOperation[]` | **REQUIRED**. List of operations for this resource. |

#### 3.7.2 Rules

- The `name` field MUST be unique within the parent consumes object's resources array.
- The `name` field MUST match the pattern `^[a-zA-Z0-9-]+$` (alphanumeric and hyphens only).
- The `path` field will be appended to the parent consumes object's `baseUri`.
- The `operations` array MUST contain at least one entry.
- No additional properties are allowed.

#### 3.7.3 ConsumedHttpResource Object Example

```yaml
name: users
label: Users API
path: /users/{username}
inputParameters:
  - name: username
    in: path
operations:
  - name: get-user
    label: Get User
    method: GET
    outputParameters:
      - name: userId
        type: string
        value: $.id
```

---

### 3.8 ConsumedHttpOperation Object

Describes an operation that can be performed on a consumed resource.

#### 3.8.1 Fixed Fields

| Field Name | Type | Description |
| --- | --- | --- |
| **name** | `string` | **REQUIRED**. Technical name for the operation. MUST match pattern `^[a-zA-Z0-9-]+$`. |
| **label** | `string` | Display name of the operation. |
| **description** | `string` | A longer description of the operation for documentation purposes. |
| **method** | `string` | **REQUIRED**. HTTP method. One of: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`. Default: `GET`. |
| **inputParameters** | `ConsumedInputParameter[]` | Input parameters for the operation. |
| **outputRawFormat** | `string` | The raw format of the response. One of: `json`, `xml`, `avro`, `protobuf`, `csv`, `tsv`, `psv`, `yaml`, `html`, `markdown`. Delimited formats: `csv` (comma), `tsv` (tab), `psv` (pipe). Default: `json`. |
| **outputSchema** | `string` | Optional format-specific schema or selector. Used by `avro` and `protobuf` for schema file paths. For `html`, may contain a CSS selector used to scope table extraction. For `markdown`, may contain a heading prefix used to filter sections. |
| **outputParameters** | `ConsumedOutputParameter[]` | Output parameters extracted from the response via JsonPath. |
| **body** | `RequestBody` | Request body configuration. |

#### 3.8.2 Rules

- The `name` field MUST be unique within the parent resource's operations array.
- The `name` field MUST match the pattern `^[a-zA-Z0-9-]+$` (alphanumeric and hyphens only).
- Both `name` and `method` are mandatory.
- No additional properties are allowed.

#### 3.8.3 ConsumedHttpOperation Object Example

```yaml
name: get-user
label: Get User Profile
method: GET
inputParameters:
  - name: username
    in: path
outputParameters:
  - name: userId
    type: string
    value: $.id
  - name: username
    type: string
    value: $.login
  - name: email
    type: string
    value: $.email
```

---

### 3.9 ExposedOperation Object

Describes an operation exposed on an exposed resource.

> Update (schema v0.5): ExposedOperation now supports two modes via `oneOf` — **simple** (direct call with mapped output) and **orchestrated** (multi-step with named operation). The `call` and `with` fields are new. The `name` and `steps` fields are only required in orchestrated mode.
>
> Update (schema v1.0.0-alpha1): A third **ref mode** allows referencing an aggregate function, inheriting its fields. See [3.4.5 Aggregate Object](#345-aggregate-object).
> 

#### 3.9.1 Fixed Fields

All fields available on ExposedOperation:

| Field Name | Type | Description |
| --- | --- | --- |
| **method** | `string` | **REQUIRED**. HTTP method. One of: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`. |
| **name** | `string` | Technical name for the operation (pattern `^[a-zA-Z0-9-]+$`). **REQUIRED in orchestrated mode.** Optional when `ref` is used (inherited from function). |
| **label** | `string` | Display name for the operation (likely used in UIs). |
| **description** | `string` | A longer description of the operation. Useful for agent discovery and documentation. Optional when `ref` is used (inherited from function). |
| **ref** | `string` | Reference to an aggregate function. Format: `{aggregate-namespace}.{function-name}`. Inherited fields are merged; explicit fields override. See [3.4.5 Aggregate Object](#345-aggregate-object). |
| **inputParameters** | `ExposedInputParameter[]` | Input parameters attached to the operation. |
| **call** | `string` | **Simple mode only**. Direct reference to a consumed operation. Format: `{namespace}.{operationId}`. MUST match pattern `^[a-zA-Z0-9-]+\.[a-zA-Z0-9-]+$`. |
| **with** | `WithInjector` | **Simple mode only**. Parameter injection for the called operation. |
| **outputParameters** (simple) | `MappedOutputParameter[]` | **Simple mode**. Output parameters mapped from the consumed operation response. |
| **steps** | `OperationStep[]` | **Orchestrated mode only. REQUIRED** (at least 1 step). Sequence of calls to consumed operations. |
| **outputParameters** (orchestrated) | `OrchestratedOutputParameter[]` | **Orchestrated mode**. Output parameters with name and type. |
| **mappings** | `StepOutputMapping[]` | **Orchestrated mode only**. Maps step outputs to the operation's output parameters at the operation level. |

#### 3.9.2 Modes (oneOf)

**Simple mode** — A direct call to a single consumed operation:

- `call` is **REQUIRED**
- `with` is optional (inject parameters into the call)
- `outputParameters` are `MappedOutputParameter[]` (type + mapping)
- `steps` MUST NOT be present
- `name` is optional

**Orchestrated mode** — A multi-step orchestration:

- `name` is **REQUIRED**
- `steps` is **REQUIRED** (at least 1 entry)
- `mappings` is optional — maps step outputs to the operation's output parameters at the operation level
- `outputParameters` are `OrchestratedOutputParameter[]` (name + type)
- `call` and `with` MUST NOT be present

**Ref mode** — reference to an aggregate function:

- `ref` is **REQUIRED**
- `method` is still **REQUIRED** (transport-specific)
- All other fields are optional — inherited from the referenced function
- Explicit fields override inherited ones (field-level merge)
- REST-specific fields like `inputParameters` with `in` location can be added to specialize the function for HTTP

#### 3.9.3 Rules

- Exactly one of the three modes MUST be used (simple, orchestrated, or ref).
- In simple mode, `call` MUST follow the format `{namespace}.{operationId}` and reference a valid consumed operation.
- In orchestrated mode, the `steps` array MUST contain at least one entry. Each step references a consumed operation using `{namespace}.{operationName}`.
- In ref mode, `ref` MUST resolve to an existing aggregate function at capability load time.
- The `method` field is always required regardless of mode.

#### 3.9.4 ExposedOperation Object Examples

**Simple mode (direct call):**

```yaml
method: GET
label: Get User Profile
call: github.get-user
with:
  username: sample.username
outputParameters:
  - type: string
    mapping: $.login
  - type: number
    mapping: $.id
```

**Orchestrated mode (multi-step):**

```yaml
name: get-db
method: GET
label: Get Database
inputParameters:
  - name: database_id
    in: path
    type: string
    description: The ID of the database to retrieve
steps:
  - type: call
    name: fetch-db
    call: notion.get-database
    with:
      database_id: "sample.database_id"
mappings:
  - targetName: db_name
    value: "$.dbName"
outputParameters:
  - name: db_name
    type: string
  - name: Api-Version
    type: string
```

---

### 3.10 RequestBody Object

Describes request body configuration for consumed operations. `RequestBody` is a `oneOf` — exactly one of five subtypes must be used.

#### 3.10.1 Subtypes

**RequestBodyJson** — JSON body

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. MUST be `"json"`. |
| **data** | `string` | `object` | `array` | **REQUIRED**. The JSON payload. Can be a raw JSON string, an inline object, or an array. |

**RequestBodyText** — Plain text, XML, or SPARQL body

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. One of: `"text"`, `"xml"`, `"sparql"`. |
| **data** | `string` | **REQUIRED**. The text payload. |

**RequestBodyFormUrlEncoded** — URL-encoded form body

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. MUST be `"formUrlEncoded"`. |
| **data** | `string` | `object` | **REQUIRED**. Either a raw URL-encoded string or an object whose values are strings. |

**RequestBodyMultipartForm** — Multipart form body

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. MUST be `"multipartForm"`. |
| **data** | `RequestBodyMultipartFormPart[]` | **REQUIRED**. Array of form parts. Each part has: `name` (required), `value` (required), `filename` (optional), `contentType` (optional). |

**RequestBodyRaw** — Raw string body

`RequestBody` can also be a plain `string`. When used with a YAML block scalar (`|`), the string is sent as-is. Interpreted as JSON by default.

#### 3.10.2 Rules

- Exactly one of the five subtypes must be used.
- For structured subtypes, both `type` and `data` are mandatory.
- No additional properties are allowed on any subtype.

#### 3.10.3 RequestBody Examples

**JSON body (object):**

```yaml
body:
  type: json
  data:
    hello: "world"
```

**JSON body (string):**

```yaml
body:
  type: json
  data: '{"key": "value"}'
```

**Text body:**

```yaml
body:
  type: text
  data: "Hello, world!"
```

**Form URL-encoded body:**

```yaml
body:
  type: formUrlEncoded
  data:
    username: "admin"
    password: "secret"
```

**Multipart form body:**

```yaml
body:
  type: multipartForm
  data:
    - name: "file"
      value: "base64content..."
      filename: "document.pdf"
      contentType: "application/pdf"
    - name: "description"
      value: "My uploaded file"
```

**Raw body:**

```yaml
body: |
  {
    "filter": {
      "property": "Status",
      "select": { "equals": "Active" }
    }
  }
```

---

### 3.11 InputParameter Objects

> Update (schema v0.5): The single `InputParameter` object has been split into two distinct types: **ConsumedInputParameter** (used in consumes) and **ExposedInputParameter** (used in exposes, with additional `type` and `description` fields required).
> 

#### 3.11.1 ConsumedInputParameter Object

Used in consumed resources and operations.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **name** | `string` | **REQUIRED**. Parameter name. MUST match pattern `^[a-zA-Z0-9-*]+$`. |
| **in** | `string` | **REQUIRED**. Parameter location. Valid values: `"query"`, `"header"`, `"path"`, `"cookie"`, `"body"`. |
| **value** | `string` | Value or JSONPath reference. |

**Rules:**

- Both `name` and `in` are mandatory.
- The `name` field MUST match the pattern `^[a-zA-Z0-9-*]+$`.
- The `in` field MUST be one of: `"query"`, `"header"`, `"path"`, `"cookie"`, `"body"`.
- A unique parameter is defined by the combination of `name` and `in`.
- No additional properties are allowed.

**ConsumedInputParameter Example:**

```yaml
- name: username
  in: path
- name: page
  in: query
- name: Authorization
  in: header
  value: "Bearer token"
```

#### 3.11.2 ExposedInputParameter Object

Used in exposed resources and operations. Extends the consumed variant with `type` (required) and `description` (recommended) for agent discoverability, plus an optional `pattern` for validation.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **name** | `string` | **REQUIRED**. Parameter name. MUST match pattern `^[a-zA-Z0-9-*]+$`. |
| **in** | `string` | **REQUIRED**. Parameter location. Valid values: `"query"`, `"header"`, `"path"`, `"cookie"`, `"body"`. |
| **type** | `string` | **REQUIRED**. Data type of the parameter. One of: `string`, `number`, `integer`, `boolean`, `object`, `array`. |
| **description** | `string` | *Recommended*. Human-readable description of the parameter. Provides valuable context for agent discovery. |
| **pattern** | `string` | Optional regex pattern for parameter value validation. |
| **value** | `string` | Default value or JSONPath reference. |

**Rules:**

- The `name`, `in`, and `type` fields are mandatory. The `description` field is recommended for agent discovery.
- The `name` field MUST match the pattern `^[a-zA-Z0-9-*]+$`.
- The `in` field MUST be one of: `"query"`, `"header"`, `"path"`, `"cookie"`, `"body"`.
- The `type` field MUST be one of: `"string"`, `"number"`, `"integer"`, `"boolean"`, `"object"`, `"array"`.
- No additional properties are allowed.

**ExposedInputParameter Example:**

```yaml
- name: database_id
  in: path
  type: string
  description: The unique identifier of the Notion database
  pattern: "^[a-f0-9-]+$"
- name: page_size
  in: query
  type: number
  description: Number of results per page (max 100)
```

---

### 3.12 OutputParameter Objects

> Update (schema v0.5): The single `OutputParameter` object has been split into three distinct types: **ConsumedOutputParameter** (used in consumed operations), **MappedOutputParameter** (used in simple-mode exposed operations), and **OrchestratedOutputParameter** (used in orchestrated-mode exposed operations).
> 

#### 3.12.1 ConsumedOutputParameter Object

Used in consumed operations to extract values from the raw API response.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **name** | `string` | **REQUIRED**. Output parameter name. MUST match pattern `^[a-zA-Z0-9-_]+$`. |
| **type** | `string` | **REQUIRED**. Data type. One of: `string`, `number`, `boolean`, `object`, `array`. |
| **value** | `string` | **REQUIRED**. JsonPath expression to extract value from consumed function response. |

**Rules:**

- All three fields (`name`, `type`, `value`) are mandatory.
- The `name` field MUST match the pattern `^[a-zA-Z0-9-_*]+$`.
- The `value` field MUST start with `$`.
- No additional properties are allowed.

**ConsumedOutputParameter Example:**

```yaml
outputParameters:
  - name: dbName
    type: string
    value: $.title[0].text.content
  - name: dbId
    type: string
    value: $.id
```

#### 3.12.2 MappedOutputParameter Object

Used in **simple mode** exposed operations. Maps a value from the consumed response using `type` and `mapping`, or provides a static value using `type` and `value`.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. Data type. One of: `string`, `number`, `boolean`, `object`, `array`. |
| **mapping** | `string` \| `object` | **Conditionally required**. JsonPath expression (scalar types) or recursive structure (`object`/`array`). Required unless `value` is provided. |
| **value** | `string` | **Conditionally required**. Static value or Mustache template injected at runtime. Required unless `mapping` is provided. Supports `{{paramName}}` placeholders resolved against input parameters. Used for mock responses and prototyping without a `consumes` block. |

**Subtypes by type:**

- **`string`**, **`number`**, **`boolean`**: `mapping` is a JSONPath string (e.g. `$.login`), or `value` is a static string or Mustache template (e.g. `"Hello, {{name}}!"`)
- **`object`**: `mapping` is `{ properties: { key: MappedOutputParameter, ... } }` — recursive
- **`array`**: `mapping` is `{ items: MappedOutputParameter }` — recursive

**Rules:**

- `type` is mandatory. Either `mapping` or `value` must be provided, but not both.
- No additional properties are allowed.

**MappedOutputParameter Examples:**

```yaml
# Scalar mapping
outputParameters:
  - type: string
    mapping: $.login
  - type: number
    mapping: $.id

# Static value (mock / prototyping)
outputParameters:
  - type: string
    value: "Hello, World!"

# Dynamic value using Mustache template
outputParameters:
  - type: string
    value: "Hello, {{name}}!"

# Object mapping (recursive)
outputParameters:
  - type: object
    mapping:
      properties:
        username:
          type: string
          mapping: $.login
        userId:
          type: number
          mapping: $.id

# Array mapping (recursive)
outputParameters:
  - type: array
    mapping:
      items:
        type: object
        mapping:
          properties:
            name:
              type: string
              mapping: $.name
```

#### 3.12.3 OrchestratedOutputParameter Object

Used in **orchestrated mode** exposed operations. Declares an output by `name` and `type` (the value is populated via step mappings).

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **name** | `string` | **REQUIRED**. Output parameter name. |
| **type** | `string` | **REQUIRED**. Data type. One of: `string`, `number`, `boolean`, `object`, `array`. |

**Subtypes by type:**

- **`string`**, **`number`**, **`boolean`** (scalar): only `name` and `type`
- **`array`**: adds `items` (recursive OrchestratedOutputParameter without `name`)
- **`object`**: adds `properties` (map of name → recursive OrchestratedOutputParameter without `name`)

**Rules:**

- Both `name` and `type` are mandatory.
- No additional properties are allowed.

**OrchestratedOutputParameter Example:**

```yaml
outputParameters:
  - name: db_name
    type: string
  - name: Api-Version
    type: string
  - name: results
    type: array
    items:
      type: object
      properties:
        id:
          type: string
        title:
          type: string
```

#### 3.12.4 JSONPath roots (extensions)

In a consumed resource, **`$`** refers to the *raw response payload* of the consumed operation (after decoding based on `outputRawFormat`). The root `$` gives direct access to the JSON response body.

Example, if you consider the following JSON response :

```json
{
  "id": "154548",
  "titles": [
    {
      "text": {
        "content": "This is title[0].text.content",
        "author": "user1"
      }
    }
  ],
  "created_time": "2024-06-01T12:00:00Z"
}
```

- `$.id` is `154548`
- `$.titles[0].text.content` is `This is title[0].text.content`

#### 3.12.5 Common patterns

- `$.fieldName` — accesses a top-level field
- `$.data.user.id` — accesses nested fields
- `$.items[0]` — accesses array elements
- `$.items[*].id` — accesses all ids in an array

---

### 3.13 OperationStep Object

Describes a single step in an orchestrated operation. `OperationStep` is a `oneOf` between two subtypes: **OperationStepCall** and **OperationStepLookup**, both sharing a common **OperationStepBase**.

> Update (schema v0.5): OperationStep is now a discriminated union (`oneOf`) with a required `type` field (`"call"` or `"lookup"`) and a required `name` field. `OperationStepCall` uses `with` (WithInjector) instead of `inputParameters`. `OperationStepLookup` is entirely new.
> 

#### 3.13.1 OperationStepBase (shared fields)

All operation steps share these base fields:

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. Step type discriminator. One of: `"call"`, `"lookup"`. |
| **name** | `string` | **REQUIRED**. Technical name for the step (pattern `^[a-zA-Z0-9-]+$`). Used as namespace for referencing step outputs in mappings and expressions. |

#### 3.13.2 OperationStepCall

Calls a consumed operation.

**Fixed Fields** (in addition to base):

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. MUST be `"call"`. |
| **name** | `string` | **REQUIRED**. Step name (from base). |
| **call** | `string` | **REQUIRED**. Reference to consumed operation. Format: `{namespace}.{operationId}`. MUST match pattern `^[a-zA-Z0-9-]+\.[a-zA-Z0-9-]+$`. |
| **with** | `WithInjector` | Parameter injection for the called operation. Keys are parameter names, values are strings or numbers (static values or namespace-qualified references, e.g. `{namespace}.{paramName}`). |

**Rules:**

- `type`, `name`, and `call` are mandatory.
- The `call` field MUST follow the format `{namespace}.{operationName}`.
- The `namespace` portion MUST correspond to a namespace defined in one of the capability's consumes entries.
- The `operationName` portion MUST correspond to an operation `name` defined in the consumes entry identified by the namespace.
- `with` uses the same `WithInjector` object as simple-mode ExposedOperation (see §3.18).
- No additional properties are allowed.

#### 3.13.3 OperationStepLookup

Performs a lookup against the output of a previous call step, matching values and extracting fields.

**Fixed Fields** (in addition to base):

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. MUST be `"lookup"`. |
| **name** | `string` | **REQUIRED**. Step name (from base). |
| **index** | `string` | **REQUIRED**. Name of a previous call step whose output serves as the lookup table. MUST match pattern `^[a-zA-Z0-9-]+$`. |
| **match** | `string` | **REQUIRED**. Name of the key field in the index to match against. MUST match pattern `^[a-zA-Z0-9-]+$`. |
| **lookupValue** | `string` | **REQUIRED**. JSONPath expression resolving to the value(s) to look up. |
| **outputParameters** | `string[]` | **REQUIRED**. List of field names to extract from the matched index entries (minimum 1 entry). |

**Rules:**

- `type`, `name`, `index`, `match`, `lookupValue`, and `outputParameters` are all mandatory.
- `outputParameters` MUST contain at least one entry.
- The `index` value MUST reference the `name` of a previous `call` step in the same orchestration.
- No additional properties are allowed.

#### 3.13.4 Call Reference Resolution

The `call` value on an `OperationStepCall` is resolved as follows:

1. Split the value on the `.` character into namespace and operationName
2. Find the consumes entry with matching `namespace` field
3. Within that consumes entry's resources, find the operation with matching `name` field
4. Execute that operation as part of the orchestration sequence

#### 3.13.5 OperationStep Object Examples

**Call step with parameter injection:**

```yaml
steps:
  - type: call
    name: fetch-db
    call: notion.get-database
    with:
      database_id: sample.database_id
```

**Lookup step (match against a previous call's output):**

```yaml
steps:
  - type: call
    name: list-users
    call: github.list-users
  - type: lookup
    name: find-user
    index: list-users
    match: email
    lookupValue: sample.user_email
    outputParameters:
      - login
      - id
```

**Multi-step orchestration (call + lookup):**

```yaml
steps:
  - type: call
    name: get-entries
    call: api.list-entries
  - type: lookup
    name: resolve-entry
    index: get-entries
    match: entry_id
    lookupValue: sample.target_id
    outputParameters:
      - title
      - status
  - type: call
    name: post-result
    call: slack.post-message
    with:
      text: sample.title
```

---

### 3.14 StepOutputMapping Object

Describes how to map the output of an operation step to the input of another step or to the output of the exposed operation.

#### 3.14.1 Fixed Fields

| Field Name | Type | Description |
| --- | --- | --- |
| **targetName** | `string` | **REQUIRED**. The name of the parameter to map to. It can be an input parameter of a next step or an output parameter of the exposed operation. |
| **value** | `string` | **REQUIRED**. A JSONPath reference to the value to map from. E.g. `$.get-database.database_id`. |

#### 3.14.2 Rules

- Both `targetName` and `value` are mandatory.
- No additional properties are allowed.

#### 3.14.3 How mappings wire steps to exposed outputs

A StepOutputMapping connects the **output parameters of a consumed operation** (called by the step) to the **output parameters of the exposed operation** (or to input parameters of subsequent steps).

- **`targetName`** — refers to the `name` of an output parameter declared on the exposed operation, or the `name` of an input parameter of a subsequent step. The target parameter receives its value from the mapping.
- **`value`** — a JSONPath expression where **`$`** is the root of the consumed operation's output parameters. The syntax `$.{outputParameterName}` references a named output parameter of the consumed operation called in this step.

#### 3.14.4 End-to-end example

Consider a consumed operation `notion.get-database` that declares:

```yaml
# In consumes → resources → operations
name: "get-database"
outputParameters:
  - name: "dbName"
    value: "{{$.title[0].text.content}}"
```

And the exposed side of the capability:

```yaml
# In exposes
exposes:
  - type: "rest"
    address: "localhost"
    port: 9090
    namespace: "sample"
    resources:
      - path: "/databases/{database_id}"
        name: "db"
        label: "Database resource"
        description: "Retrieve information about a Notion database"
        inputParameters:
          - name: "database_id"
            in: "path"
            type: "string"
            description: "The unique identifier of the Notion database"
        operations:
          - name: "get-db"
            method: "GET"
            label: "Get Database"
            outputParameters:
              - name: "db_name"
                type: "string"
            steps:
              - type: "call"
                name: "fetch-db"
                call: "notion.get-database"
                with:
                  database_id: "sample.database_id"
            mappings:
              - targetName: "db_name"
                value: "{{$.dbName}}"
```

Here is what happens at orchestration time:

1. The step `fetch-db` calls `notion.get-database`, which extracts `dbName` and `dbId` from the raw response via its own output parameters.
2. The `with` injector passes `database_id` from the exposed input parameter (`sample.database_id`) to the consumed operation.
3. The mapping `targetName: "db_name"` refers to the exposed operation's output parameter `db_name`.
4. The mapping `value: "$.dbName"` resolves to the value of the consumed operation's output parameter named `dbName`.
5. As a result, the exposed output `db_name` is populated with the value extracted by `$.dbName` (i.e. `title[0].text.content` from the raw Notion API response).

#### 3.14.5 StepOutputMapping Object Example

```yaml
mappings:
  - targetName: "db_name"
    value: "{{$.dbName}}"
```

---

### 3.15 Namespace Context Reference

Describes how namespace-qualified references work in `with` (WithInjector) and other expression contexts.

> Update (schema v0.5): The former `OperationStepParameter` object (with `name` and `value` fields) has been replaced by `WithInjector` (see §3.18). The former `$this` expression root has been removed — exposed input parameters are now referenced directly using a namespace-qualified path.
> 

#### 3.15.1 Namespace-qualified references

In a `with` (WithInjector) value — whether on an ExposedOperation (simple mode) or an OperationStepCall — exposed input parameters are referenced using the path `{exposeNamespace}.{inputParameterName}`. This allows a step or a simple-mode call to receive values provided by the caller of the exposed operation.

- **`{exposeNamespace}.{paramName}`** — accesses an input parameter of the exposed resource or operation identified by its namespace.
- The `{exposeNamespace}` corresponds to the `namespace` of the exposed API.
- The `{paramName}` corresponds to the `name` of an input parameter declared on the exposed resource or operation.

#### 3.15.2 Example

If the exposed API has namespace `sample` and an input parameter `database_id` declared on its resource:

**Usage in a WithInjector:**

```yaml
call: notion.get-database
with:
  database_id: sample.database_id
```

---

### 3.16 Authentication Object

Defines authentication configuration. Four types are supported: basic, apikey, bearer, and digest.

#### 3.16.1 Basic Authentication

HTTP Basic Authentication.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. MUST be `"basic"`. |
| **username** | `string` | Username for basic auth. |
| **password** | `string` | Password for basic auth. |

**Example:**

```yaml
authentication:
  type: basic
  username: admin
  password: "secret_password"
```

#### 3.16.2 API Key Authentication

API Key authentication via header or query parameter.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. MUST be `"apikey"`. |
| **key** | `string` | API key name (header name or query parameter name). |
| **value** | `string` | API key value. |
| **placement** | `string` | Where to place the key. Valid values: `"header"`, `"query"`. |

**Example:**

```yaml
authentication:
  type: apikey
  key: X-API-Key
  value: "{{api_key}}"
  placement: header
```

#### 3.16.3 Bearer Token Authentication

Bearer token authentication.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. MUST be `"bearer"`. |
| **token** | `string` | Bearer token value. |

**Example:**

```yaml
authentication:
  type: bearer
  token: "bearer_token"
```

#### 3.16.4 Digest Authentication

HTTP Digest Authentication.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. MUST be `"digest"`. |
| **username** | `string` | Username for digest auth. |
| **password** | `string` | Password for digest auth. |

**Example:**

```yaml
authentication:
  type: digest
  username: admin
  password: "secret_password"
```

#### 3.16.5 Rules

- Only one authentication type can be used per authentication object.
- The `type` field determines which additional fields are required or allowed.
- Authentication can be specified at multiple levels (exposes, consumes) with inner levels overriding outer levels.

---

### 3.17 ForwardConfig Object

Defines forwarding configuration for an exposed resource to pass requests through to a consumed namespace.

> Update (schema v0.5): Renamed from `ForwardHeaders` to `ForwardConfig`. The `targetNamespaces` array has been replaced by a single `targetNamespace` string.
> 

#### 3.17.1 Fixed Fields

| Field Name | Type | Description |
| --- | --- | --- |
| **targetNamespace** | `string` | **REQUIRED**. The consumer namespace to forward requests to. MUST match pattern `^[a-zA-Z0-9-]+$`. |
| **trustedHeaders** | [`string`] | **REQUIRED**. List of headers allowed to be forwarded (minimum 1 entry). No wildcards supported. |

#### 3.17.2 Rules

- The `targetNamespace` field is mandatory and MUST reference a valid namespace from one of the capability's consumes entries.
- The `trustedHeaders` array is mandatory and MUST contain at least one entry.
- Header names in `trustedHeaders` are case-insensitive (following HTTP header conventions).
- Only headers listed in `trustedHeaders` will be forwarded to the consumed source.
- No additional properties are allowed.

#### 3.17.3 ForwardConfig Object Example

```yaml
forward:
  targetNamespace: notion
  trustedHeaders:
    - Authorization
    - Notion-Version
```

---

### 3.18 WithInjector Object

Defines parameter injection for simple-mode exposed operations. Used with the `with` field on an ExposedOperation to inject values into the called consumed operation.

> New in schema v0.5.
> 

#### 3.18.1 Shape

`WithInjector` is an object whose keys are parameter names and whose values are static values or references.

- Each key corresponds to a parameter `name` in the consumed operation's `inputParameters`.
- Each value is a `string` or a `number`: either a static value or a namespace-qualified reference of the form `{namespace}.{paramName}`.

#### 3.18.2 Rules

- The keys MUST correspond to valid parameter names in the consumed operation being called.
- Values can be strings or numbers.
- String values can reference exposed input parameters using namespace-qualified expressions of the form `{exposeNamespace}.{paramName}`.
- No additional constraints.

#### 3.18.3 WithInjector Object Example

```yaml
call: github.get-user
with:
  username: sample.username
  Accept: "application/json"
  maxRetries: 3
```

---

### 3.19 Bind Object

> **Updated**: The former `ExternalRef` discriminated union (file-resolved / runtime-resolved) has been replaced by a single **`Bind`** object. The `name` field is now `namespace`, `type` and `resolution` have been removed, and `uri` has been replaced by the optional `location` field. Variable names (keys in the `keys` map) now follow `SCREAMING_SNAKE_CASE` convention.
> 

Declares an external binding that provides variables to the capability. Bindings are declared at the root level of the Naftiko document via the `binds` array.

#### 3.19.1 Fixed Fields

| Field Name | Type | Description |
| --- | --- | --- |
| **namespace** | `string` | **REQUIRED**. Unique identifier for this binding (kebab-case). MUST match pattern `^[a-zA-Z0-9-]+$`. |
| **description** | `string` | *Recommended*. A meaningful description of the binding. In a world of agents, context is king. |
| **location** | `UriLocation` | Optional. A URI identifying the value provider (e.g. `file:///path/to/env.json`, `vault://secrets/myapp`). When omitted, values are injected by the runtime environment. |
| **keys** | `BindingKeys` | **REQUIRED**. Map of `SCREAMING_SNAKE_CASE` variable names to keys in the resolved provider. |

#### 3.19.2 Rules

- `namespace` and `keys` are mandatory. `description` is recommended.
- Each `namespace` MUST be unique across all `binds` entries.
- The `namespace` MUST NOT collide with any `consumes` namespace to avoid ambiguity.
- The `keys` map MUST contain at least one entry.
- Variable names (keys in the `keys` map) MUST follow `SCREAMING_SNAKE_CASE` (pattern `^[A-Z][A-Z0-9_]*$`).
- Variable names SHOULD be unique across all `binds` entries. If the same variable name appears in multiple entries, use the qualified form `\{\{namespace.VARIABLE_NAME\}\}` to disambiguate.
- No additional properties are allowed.

#### 3.19.3 BindingKeys Object

A map of key-value pairs that declare the variables to be injected from the binding.

- Each **key** is the variable name (`SCREAMING_SNAKE_CASE`) used for injection (available as `\{\{VARIABLE_NAME\}\}` in the capability definition)
- Each **value** is the corresponding key in the resolved provider (file, vault, runtime context, etc.)

Example: `{"NOTION_TOKEN": "NOTION_INTEGRATION_TOKEN"}` means the value of `NOTION_INTEGRATION_TOKEN` in the provider will be injected as `\{\{NOTION_TOKEN\}\}` in the capability definition.

**Schema:**

```json
{
  "type": "object",
  "additionalProperties": { "type": "string" }
}
```

#### 3.19.4 UriLocation

A URI string that identifies the value provider for the binding. The scheme determines how the binding is resolved:

- `file:///path/to/file.json` — local JSON file (for **development only**)
- `vault://secrets/myapp` — HashiCorp Vault
- `k8s://secrets/myapp` — Kubernetes Secrets
- Any URI scheme recognized by the runtime environment

When `location` is omitted, values are expected to be injected directly by the execution environment (e.g. environment variables, CI/CD secrets, platform secrets managers).

<aside>
⚠️

**Security recommendation** — `file:///` locations are intended for **local development only**. File-based resolution exposes secret locations in the capability document and creates a risk of accidental secret leakage (e.g. committing `.env` files to version control). Always prefer runtime injection or a dedicated secrets manager in production.

</aside>

#### 3.19.5 Bind Object Examples

**File location (development):**

```yaml
binds:
  - namespace: "notion-env"
    description: "Notion API credentials for local development."
    location: "file:///path/to/notion_env.json"
    keys:
      NOTION_TOKEN: "NOTION_INTEGRATION_TOKEN"
      PROJECTS_DB_ID: "PROJECTS_DATABASE_ID"
      TIME_TRACKER_DB_ID: "TIME_TRACKER_DATABASE_ID"
```

**Runtime injection (production — no location):**

```yaml
binds:
  - namespace: "secrets"
    keys:
      NOTION_TOKEN: "NOTION_INTEGRATION_TOKEN"
      GITHUB_TOKEN: "GITHUB_TOKEN"
```

**Multiple bindings:**

```yaml
binds:
  - namespace: "notion-secrets"
    description: "Notion API credentials"
    keys:
      NOTION_TOKEN: "NOTION_INTEGRATION_TOKEN"
  - namespace: "github-secrets"
    description: "GitHub API credentials"
    keys:
      GITHUB_TOKEN: "GITHUB_TOKEN"
```

---

### 3.20 Expression Syntax

Variables declared in `binds` via the `keys` map are injected into the capability document using mustache-style `\{\{VARIABLE_NAME\}\}` expressions.

#### 3.20.1 Format

The expression format is `\{\{VARIABLE_NAME\}\}`, where `VARIABLE_NAME` is a key declared in the `keys` map of a `binds` entry (SCREAMING_SNAKE_CASE).

Expressions can appear in any `string` value within the document, including authentication tokens, header values, and input parameter values.

#### 3.20.2 Resolution

At runtime, expressions are resolved as follows:

1. Find the `binds` entry whose `keys` map contains the referenced variable name
2. Look up the corresponding source key in the `keys` map
3. Resolve the source key value using the configured `location` (file-based lookup or runtime injection)
4. Replace the `\{\{VARIABLE_NAME\}\}` expression with the resolved value

If a referenced variable is not declared in any `binds` entry's `keys`, the document MUST be considered invalid.

#### 3.20.3 Relationship with namespace-qualified references

`\{\{VARIABLE_NAME\}\}` expressions and namespace-qualified references serve different purposes:

- `\{\{VARIABLE_NAME\}\}` resolves **static configuration** from bindings (secrets, environment variables) declared via `keys`
- `{exposeNamespace}.{paramName}` resolves **runtime orchestration** values from the expose layer's input parameters

The two expression systems are independent and MUST NOT be mixed.

#### 3.20.4 Expression Examples

```yaml
# Authentication token from bind
authentication:
  type: bearer
  token: "NOTION_TOKEN"

# Input parameter with header value from bind
inputParameters:
  - name: Notion-Version
    in: header
    value: "API_VERSION"

# Corresponding binds declaration
binds:
  - namespace: "secrets"
    keys:
      NOTION_TOKEN: "NOTION_TOKEN"
      API_VERSION: "NOTION_VERSION"
```

---

## 4. Complete Examples

This section provides progressive examples — from the simplest capability to a full-featured one — to illustrate the main patterns of the specification. All examples are pseudo-functional and use realistic API shapes.

### 4.1 Forward-only capability (proxy)

The simplest capability: forward incoming requests to a consumed API without any transformation.

```yaml
---
naftiko: "0.5"
info:
  label: "Notion Proxy"
  description: "Pass-through proxy to the Notion API for development and debugging"
  tags:
    - proxy
    - notion
  created: "2026-02-01"
  modified: "2026-02-01"

capability:
  exposes:
    - type: "rest"
      port: 8080
      namespace: "proxy"
      resources:
        - path: "/notion/{path}"
          description: "Forwards all requests to the Notion API"
          forward:
            targetNamespace: "notion"
            trustedHeaders:
              - "Authorization"
              - "Notion-Version"

  consumes:
    - type: "http"
      namespace: "notion"
      description: "Notion public API"
      baseUri: "https://api.notion.com/v1"
      resources:
        - name: "all"
          path: "/{{path}}"
          operations:
            - name: "any"
              method: "GET"
```

### 4.2 Simple-mode capability (direct call)

A single exposed operation that directly calls a consumed operation, maps parameters with `with`, and extracts output.

```yaml
---
naftiko: "0.5"
binds:
  - namespace: "env"
    keys:
      GITHUB_TOKEN: "GITHUB_TOKEN"
info:
  label: "GitHub User Lookup"
  description: "Exposes a simplified endpoint to retrieve GitHub user profiles"
  tags:
    - github
    - users
  created: "2026-02-01"
  modified: "2026-02-01"

capability:
  exposes:
    - type: "rest"
      port: 3000
      namespace: "app"
      resources:
        - path: "/users/{{username}}"
          description: "Look up a GitHub user by username"
          name: "user"
          inputParameters:
            - name: "username"
              in: "path"
              type: "string"
              description: "The GitHub username to look up"
          operations:
            - method: "GET"
              label: "Get User"
              call: "github.get-user"
              with:
                username: "app.username"
              outputParameters:
                - type: "string"
                  mapping: "{{$.login}}"
                - type: "string"
                  mapping: "{{$.email}}"
                - type: "number"
                  mapping: "{{$.id}}"

  consumes:
    - type: "http"
      namespace: "github"
      description: "GitHub REST API v3"
      baseUri: "https://api.github.com"
      authentication:
        type: "bearer"
        token: "{{github_token}}"
      resources:
        - name: "users"
          path: "/users/{username}"
          label: "Users"
          operations:
            - name: "get-user"
              label: "Get User"
              method: "GET"
              inputParameters:
                - name: "username"
                  in: "path"
              outputParameters:
                - name: "login"
                  type: "string"
                  value: "{{$.login}}"
                - name: "email"
                  type: "string"
                  value: "{{$.email}}"
                - name: "id"
                  type: "number"
                  value: "{{$.id}}"
```

### 4.3 Orchestrated capability (multi-step call)

An exposed operation that chains two consumed operations using named steps and `with`.

```yaml
---
naftiko: "0.5"
binds:
  - namespace: "env"
    keys:
      NOTION_TOKEN: "NOTION_TOKEN"
info:
  label: "Database Inspector"
  description: "Retrieves a Notion database then queries its contents in a single exposed operation"
  tags:
    - notion
    - orchestration
  created: "2026-02-10"
  modified: "2026-02-10"

capability:
  exposes:
    - type: "rest"
      port: 9090
      namespace: "inspector"
      resources:
        - path: "/databases/{database_id}/summary"
          description: "Returns database metadata and first page of results"
          name: "db-summary"
          inputParameters:
            - name: "database_id"
              in: "path"
              type: "string"
              description: "The Notion database ID"
          operations:
            - name: "get-summary"
              method: "GET"
              label: "Get Database Summary"
              steps:
                - type: "call"
                  name: "fetch-db"
                  call: "notion.get-database"
                  with:
                    database_id: "inspector.database_id"
                - type: "call"
                  name: "query-db"
                  call: "notion.query-database"
                  with:
                    database_id: "inspector.database_id"
              mappings:
                - targetName: "db_name"
                  value: "{{$.fetch-db.dbName}}"
                - targetName: "row_count"
                  value: "{{$.query-db.resultCount}}"
              outputParameters:
                - name: "db_name"
                  type: "string"
                - name: "row_count"
                  type: "number"

  consumes:
    - type: "http"
      namespace: "notion"
      description: "Notion public API"
      baseUri: "https://api.notion.com/v1"
      authentication:
        type: "bearer"
        token: "{{notion_token}}"
      inputParameters:
        - name: "Notion-Version"
          in: "header"
          value: "2022-06-28"
      resources:
        - name: "databases"
          path: "/databases/{{database_id}}"
          label: "Databases"
          operations:
            - name: "get-database"
              label: "Get Database"
              method: "GET"
              inputParameters:
                - name: "database_id"
                  in: "path"
              outputParameters:
                - name: "dbName"
                  type: "string"
                  value: "{{$.title[0].text.content}}"
                - name: "dbId"
                  type: "string"
                  value: "{{$.id}}"
        - name: "queries"
          path: "/databases/{{database_id}}/query"
          label: "Database queries"
          operations:
            - name: "query-database"
              label: "Query Database"
              method: "POST"
              inputParameters:
                - name: "database_id"
                  in: "path"
              outputParameters:
                - name: "resultCount"
                  type: "number"
                  value: "{{$.results.length()}}"
                - name: "results"
                  type: "array"
                  value: "{{$.results}}"
```

### 4.4 Orchestrated capability with lookup step

Demonstrates a `lookup` step that cross-references the output of a previous call to enrich data.

```yaml
---
naftiko: "0.5"
binds:
  - namespace: "env"
    keys:
      HR_API_KEY: "HR_API_KEY"
info:
  label: "Team Member Resolver"
  description: "Resolves team member details by matching email addresses from a project tracker"
  tags:
    - hr
    - lookup
  created: "2026-02-15"
  modified: "2026-02-15"

capability:
  exposes:
    - type: "rest"
      port: 4000
      namespace: "team"
      resources:
        - path: "/resolve/{email}"
          description: "Finds a team member by email and returns their profile"
          name: "resolve"
          inputParameters:
            - name: "email"
              in: "path"
              type: "string"
              description: "Email address to look up"
          operations:
            - name: "resolve-member"
              method: "GET"
              label: "Resolve Team Member"
              steps:
                - type: "call"
                  name: "list-members"
                  call: "hr.list-employees"
                - type: "lookup"
                  name: "find-member"
                  index: "list-members"
                  match: "email"
                  lookupValue: "team.email"
                  outputParameters:
                    - "fullName"
                    - "department"
                    - "role"
              mappings:
                - targetName: "name"
                  value: "$.find-member.fullName"
                - targetName: "department"
                  value: "$.find-member.department"
                - targetName: "role"
                  value: "$.find-member.role"
              outputParameters:
                - name: "name"
                  type: "string"
                - name: "department"
                  type: "string"
                - name: "role"
                  type: "string"

  consumes:
    - type: "http"
      namespace: "hr"
      description: "Internal HR system API"
      baseUri: "https://hr.internal.example.com/api"
      authentication:
        type: "apikey"
        key: "X-Api-Key"
        value: "{{hr_api_key}}"
        placement: "header"
      resources:
        - name: "employees"
          path: "/employees"
          label: "Employees"
          operations:
            - name: "list-employees"
              label: "List All Employees"
              method: "GET"
              outputParameters:
                - name: "email"
                  type: "string"
                  value: "$.items[*].email"
                - name: "fullName"
                  type: "string"
                  value: "$.items[*].name"
                - name: "department"
                  type: "string"
                  value: "$.items[*].department"
                - name: "role"
                  type: "string"
                  value: "$.items[*].role"
```

### 4.5 Full-featured capability (mixed modes)

Combines forward proxy, simple-mode operations, orchestrated multi-step with lookup, and multiple consumed sources.

```yaml
---
naftiko: "0.5"
binds:
  - namespace: "env"
    keys:
      NOTION_TOKEN: "NOTION_TOKEN"
      GITHUB_TOKEN: "GITHUB_TOKEN"
info:
  label: "Project Dashboard"
  description: "Aggregates project data from Notion and GitHub into a unified API, with a pass-through proxy for direct access"
  tags:
    - dashboard
    - notion
    - github
  created: "2026-02-20"
  modified: "2026-02-20"
  stakeholders:
    - role: "owner"
      fullName: "Jane Doe"
      email: "jane.doe@example.com"
    - role: "editor"
      fullName: "John Smith"
      email: "john.smith@example.com"

capability:
  exposes:
    - type: "rest"
      port: 9090
      namespace: "dashboard"
      resources:
        # --- Forward proxy (simplest) ---
        - path: "/github/{path}"
          description: "Direct pass-through to the GitHub API for debugging"
          forward:
            targetNamespace: "github"
            trustedHeaders:
              - "Authorization"

        # --- Simple mode (direct call) ---
        - path: "/repos/{owner}/{repo}"
          description: "Retrieve a GitHub repository summary"
          name: "repo"
          inputParameters:
            - name: "owner"
              in: "path"
              type: "string"
              description: "Repository owner (user or organization)"
            - name: "repo"
              in: "path"
              type: "string"
              description: "Repository name"
          operations:
            - method: "GET"
              label: "Get Repository"
              call: "github.get-repo"
              with:
                owner: "dashboard.owner"
                repo: "dashboard.repo"
              outputParameters:
                - type: "string"
                  mapping: "$.full_name"
                - type: "number"
                  mapping: "$.stargazers_count"
                - type: "string"
                  mapping: "$.language"

        # --- Orchestrated mode (multi-step call + lookup) ---
        - path: "/projects/{database_id}/contributors"
          description: "Lists project tasks from Notion and enriches each assignee with GitHub profile data"
          name: "contributors"
          inputParameters:
            - name: "database_id"
              in: "path"
              type: "string"
              description: "Notion database ID for the project tracker"
          operations:
            - name: "list-contributors"
              method: "GET"
              label: "List Project Contributors"
              steps:
                - type: "call"
                  name: "query-tasks"
                  call: "notion.query-database"
                  with:
                    database_id: "dashboard.database_id"
                - type: "call"
                  name: "list-github-users"
                  call: "github.list-org-members"
                  with:
                    org: "naftiko"
                - type: "lookup"
                  name: "match-contributors"
                  index: "list-github-users"
                  match: "login"
                  lookupValue: "$.query-tasks.assignee"
                  outputParameters:
                    - "login"
                    - "avatar_url"
                    - "html_url"
              mappings:
                - targetName: "contributors"
                  value: "$.match-contributors"
              outputParameters:
                - name: "contributors"
                  type: "array"
                  items:
                    type: "object"
                    properties:
                      login:
                        type: "string"
                      avatar_url:
                        type: "string"
                      html_url:
                        type: "string"

  consumes:
    - type: "http"
      namespace: "notion"
      description: "Notion public API for database and page operations"
      baseUri: "https://api.notion.com/v1"
      authentication:
        type: "bearer"
        token: "{{notion_token}}"
      inputParameters:
        - name: "Notion-Version"
          in: "header"
          value: "2022-06-28"
      resources:
        - name: "db-query"
          path: "/databases/{database_id}/query"
          label: "Database Query"
          operations:
            - name: "query-database"
              label: "Query Database"
              method: "POST"
              inputParameters:
                - name: "database_id"
                  in: "path"
              outputParameters:
                - name: "assignee"
                  type: "string"
                  value: "$.results[*].properties.Assignee.people[0].name"
                - name: "taskName"
                  type: "string"
                  value: "$.results[*].properties.Name.title[0].text.content"

    - type: "http"
      namespace: "github"
      description: "GitHub REST API for repository and user operations"
      baseUri: "https://api.github.com"
      authentication:
        type: "bearer"
        token: "{{github_token}}"
      resources:
        - name: "repos"
          path: "/repos/{owner}/{repo}"
          label: "Repositories"
          operations:
            - name: "get-repo"
              label: "Get Repository"
              method: "GET"
              inputParameters:
                - name: "owner"
                  in: "path"
                - name: "repo"
                  in: "path"
              outputParameters:
                - name: "full_name"
                  type: "string"
                  value: "$.full_name"
                - name: "stargazers_count"
                  type: "number"
                  value: "$.stargazers_count"
                - name: "language"
                  type: "string"
                  value: "$.language"
        - name: "org-members"
          path: "/orgs/{org}/members"
          label: "Organization Members"
          operations:
            - name: "list-org-members"
              label: "List Organization Members"
              method: "GET"
              inputParameters:
                - name: "org"
                  in: "path"
              outputParameters:
                - name: "login"
                  type: "string"
                  value: "$[*].login"
                - name: "avatar_url"
                  type: "string"
                  value: "$[*].avatar_url"
                - name: "html_url"
                  type: "string"
                  value: "$[*].html_url"
```

---

### 4.6 MCP capability (tool exposition)

Exposes a single MCP tool over Streamable HTTP that calls a consumed operation, making it discoverable by AI agents via the MCP protocol.

```yaml
---
naftiko: "0.5"
binds:
  - namespace: "env"
    keys:
      NOTION_TOKEN: "NOTION_TOKEN"
info:
  label: "Notion MCP Tools"
  description: "Exposes Notion database retrieval as an MCP tool"

capability:
  exposes:
    - type: "mcp"
      port: 3001
      namespace: "notion-tools"
      description: "Notion database tools for AI agents"
      tools:
        - name: "get-database"
          description: "Retrieve metadata about a Notion database by its ID"
          inputParameters:
            - name: "database_id"
              type: "string"
              description: "The unique identifier of the Notion database"
          call: "notion.get-database"
          with:
            database_id: "notion-tools.database_id"
          outputParameters:
            - type: "string"
              mapping: "$.dbName"

  consumes:
    - type: "http"
      namespace: "notion"
      description: "Notion public API"
      baseUri: "https://api.notion.com/v1"
      authentication:
        type: "bearer"
        token: "{{notion_token}}"
      inputParameters:
        - name: "Notion-Version"
          in: "header"
          value: "2022-06-28"
      resources:
        - name: "databases"
          path: "/databases/{database_id}"
          operations:
            - name: "get-database"
              method: "GET"
              inputParameters:
                - name: "database_id"
                  in: "path"
              outputParameters:
                - name: "dbName"
                  type: "string"
                  value: "$.title[0].text.content"
```

---

## 5. Versioning

The Naftiko Specification uses semantic versioning. The `naftiko` field in the Naftiko Object specifies the exact version of the specification (e.g., `"0.5"`). 

Tools processing Naftiko documents MUST validate this field to ensure compatibility with the specification version they support.

---

This specification defines how to describe modular, composable capabilities that consume multiple sources and expose unified interfaces, supporting orchestration, authentication, and flexible routing patterns.