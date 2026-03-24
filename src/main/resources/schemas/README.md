# Naftiko Specification

**Version 0.4**

**Publication Date:** February 2026

---

- **Table of Contents**

## 1. Introduction

The Naftiko Specification defines a standard, language-agnostic interface for describing modular, composable capabilities. In short, a **capability** is a functional unit that consumes external APIs (sources) and exposes adapters that allow other systems to interact with it.

A Naftiko capability focuses on declaring the **integration intent** — what a system needs to consume and what it exposes — rather than implementation details. This higher-level abstraction makes capabilities naturally suitable for AI-driven discovery, orchestration and integration use cases, and beyond. When properly defined, a capability can be discovered, orchestrated, validated and executed with minimal implementation logic. The specification enables description of:

- **Consumed sources**: External APIs or services that the capability uses
- **Exposed adapters**: Server interfaces that the capability provides (HTTP, REST, etc.)
- **Orchestration**: How calls to consumed sources are combined and mapped to realize exposed functions
- **External references**: Variables and resources resolved from external sources

### 1.1 Schema Access

The JSON Schema for the Naftiko Specification is available in two forms:

- **Raw file** — The schema source file is hosted on GitHub: [capability-schema.json](https://github.com/naftiko/framework/blob/main/src/main/resources/schemas/capability-schema.json)
- **Interactive viewer** — A human-friendly viewer is available at: [Schema Viewer](https://naftiko.github.io/schema-viewer/)

### 1.2 Core Objects

**Capability**: The central object that defines a modular functional unit with clear input/output contracts. 

**Consumes**: External sources (APIs, services) that the capability uses to realize its operations.

**Exposes**: Server adapters that provide access to the capability's operations.

**Resources**: API endpoints that group related operations.

**Operations**: Individual HTTP operations (GET, POST, etc.) that can be performed on resources.

**Namespace**: A unique identifier for consumed sources, used for routing and mapping with the expose layer.

**Bind**: A declaration that the capability binds to an external source of variables. The `location` URI identifies the provider (file, vault, GitHub secrets, etc.). When `location` is omitted, values are injected by the runtime environment. Variables are explicitly declared via a `keys` map using SCREAMING_SNAKE_CASE names.

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
| **Key strengths** | ✓ Endpoints & HTTP methods<br />✓ Request/response schemas<br />✓ Authentication requirements<br />✓ Data types & validation<br />✓ SDK & docs generation | ✓ Multi-step sequences<br />✓ Step dependencies & data flow<br />✓ Success/failure criteria<br />✓ Reusable workflow definitions | ✓ Runnable, shareable collections<br />✓ Pre-request scripts & tests<br />✓ Environment variables<br />✓ Living, executable docs | ✓ Consume/expose duality<br />✓ Namespace-based routing<br />✓ Orchestration & forwarding<br />✓ AI-driven discovery<br />✓ Composable capabilities |
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
| **naftiko** | `string` | **REQUIRED**. Version of the Naftiko schema. MUST be `"0.4"` for this version. |
| **info** | `Info` | **REQUIRED**. Metadata about the capability. |
| **capability** | `Capability` | **REQUIRED**. Technical configuration of the capability including sources and adapters. |
| **binds** | `Bind[]` | List of external sources the capability binds to for variable injection. Each entry declares injected variables via a `keys` map. |

#### 3.1.2 Rules

- The `naftiko` field MUST be present and MUST have the value `"0.4"` for documents conforming to this version of the specification.
- Both `info` and `capability` objects MUST be present.
- The `binds` field is OPTIONAL. When present, it MUST contain at least one entry.
- No additional properties are allowed at the root level.

---

### 3.2 Info Object

Provides metadata about the capability.

#### 3.2.1 Fixed Fields

| Field Name | Type | Description |
| --- | --- | --- |
| **label** | `string` | **REQUIRED**. The display name of the capability. |
| **description** | `string` | **REQUIRED**. A description of the capability. The more meaningful it is, the easier for agent discovery. |
| **tags** | `string[]` | List of tags to help categorize the capability for discovery and filtering. |
| **created** | `string` | Date the capability was created (format: `YYYY-MM-DD`). |
| **modified** | `string` | Date the capability was last modified (format: `YYYY-MM-DD`). |
| **stakeholders** | `Person[]` | List of stakeholders related to this capability (for discovery and filtering). |

#### 3.2.2 Rules

- Both `label` and `description` are mandatory.
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
| **exposes** | `Exposes[]` | **REQUIRED**. List of exposed server adapters. |
| **consumes** | `Consumes[]`  | **REQUIRED**. List of consumed client adapters. |

#### 3.4.2 Rules

- The `exposes` array MUST contain at least one entry.
- The `consumes` array MUST contain at least one entry.
- Each `consumes` entry MUST include both `baseUri` and `namespace` fields.
- There are several types of exposed adapters and consumed sources objects, all will be described in following objects.
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
    - type: api
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

### 3.5 Exposes Object

Describes a server adapter that exposes functionality.

> Update (schema v0.4): the exposition adapter is **API** with `type: "api"` (and a required `namespace`). Legacy `httpProxy` / `rest` exposition types are not part of the JSON Schema anymore.
> 

#### 3.5.1 API Expose

API exposition configuration.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. MUST be `"api"`. |
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
| **description** | `string` | **REQUIRED**. Used to provide *meaningful* information about the resource. In a world of agents, context is king. |
| **name** | `string` | Technical name for the resource (used for references, pattern `^[a-zA-Z0-9-]+$`). |
| **label** | `string` | Display name for the resource (likely used in UIs). |
| **inputParameters** | `ExposedInputParameter[]` | Input parameters attached to the resource. |
| **operations** | `ExposedOperation[]` | Operations available on this resource. |
| **forward** | `ForwardConfig` | Forwarding configuration to a consumed namespace. |

#### 3.5.3 Rules

- Both `description` and `path` are mandatory.
- At least one of `operations` or `forward` MUST be present. Both can coexist on the same resource.
- if both `operations` or `forward` are present, in case of conflict, `operations` takes precendence on `forward`.
- No additional properties are allowed.

#### 3.5.4 Address Validation Patterns

- **Hostname**: `^([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$`
- **IPv4**: `^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$`
- **IPv6**: `^([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}$`

#### 3.5.5 Exposes Object Examples

**API Expose with operations:**

```yaml
type: api
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

**API Expose with forward:**

```yaml
type: api
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

**API Expose with both operations and forward:**

```yaml
type: api
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

---

### 3.6 Consumes Object

Describes a client adapter for consuming external APIs.

> Update (schema v0.4): `targetUri` is now `baseUri`. The `headers` field has been removed — use `inputParameters` with `in: "header"` instead.
> 

#### 3.6.1 Fixed Fields

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. Type of consumer. Valid values: `"http"`. |
| **namespace** | `string` | Path suffix used for routing from exposes. MUST match pattern `^[a-zA-Z0-9-]+$`. |
| **baseUri** | `string` | **REQUIRED**. Base URI for the consumed API. Must be a valid http(s) URL (no `path` placeholder in the schema). |
| **authentication** | Authentication Object | Authentication configuration. Defaults to `"inherit"`. |
| **description** | `string` | **REQUIRED**. A description of the consumed API. The more meaningful it is, the easier for agent discovery. |
| **inputParameters** | `ConsumedInputParameter[]` | Input parameters applied to all operations in this consumed API. |
| **resources** | [ConsumedHttpResource Object] | **REQUIRED**. List of API resources. |

#### 3.6.2 Rules

- The `type` field MUST be `"http"`.
- The `baseUri` field is required.
- The `namespace` field is required and MUST be unique across all consumes entries.
- The `namespace` value MUST match the pattern `^[a-zA-Z0-9-]+$` (alphanumeric and hyphens only).
- The `description` field is required.
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
  token: "{{GITHUB_TOKEN}}"
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
| **outputRawFormat** | `string` | The raw format of the response. One of: `json`, `xml`, `avro`, `protobuf`,  `csv`,  `yaml` . Default: `json`. |
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

> Update (schema v0.4): ExposedOperation now supports two modes via `oneOf` — **simple** (direct call with mapped output) and **orchestrated** (multi-step with named operation). The `call` and `with` fields are new. The `name` and `steps` fields are only required in orchestrated mode.
> 

#### 3.9.1 Fixed Fields

All fields available on ExposedOperation:

| Field Name | Type | Description |
| --- | --- | --- |
| **method** | `string` | **REQUIRED**. HTTP method. One of: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`. |
| **name** | `string` | Technical name for the operation (pattern `^[a-zA-Z0-9-]+$`). **REQUIRED in orchestrated mode only.** |
| **label** | `string` | Display name for the operation (likely used in UIs). |
| **description** | `string` | A longer description of the operation. Useful for agent discovery and documentation. |
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

#### 3.9.3 Rules

- Exactly one of the two modes MUST be used (simple or orchestrated).
- In simple mode, `call` MUST follow the format `{namespace}.{operationId}` and reference a valid consumed operation.
- In orchestrated mode, the `steps` array MUST contain at least one entry. Each step references a consumed operation using `{namespace}.{operationName}`.
- The `method` field is always required regardless of mode.

#### 3.9.4 ExposedOperation Object Examples

**Simple mode (direct call):**

```yaml
method: GET
label: Get User Profile
call: github.get-user
with:
  username: $this.sample.username
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
      database_id: "$this.sample.database_id"
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

> Update (schema v0.4): The single `InputParameter` object has been split into two distinct types: **ConsumedInputParameter** (used in consumes) and **ExposedInputParameter** (used in exposes, with additional `type` and `description` fields required).
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

Used in exposed resources and operations. Extends the consumed variant with `type` and `description` (both required) for agent discoverability, plus an optional `pattern` for validation.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **name** | `string` | **REQUIRED**. Parameter name. MUST match pattern `^[a-zA-Z0-9-*]+$`. |
| **in** | `string` | **REQUIRED**. Parameter location. Valid values: `"query"`, `"header"`, `"path"`, `"cookie"`, `"body"`. |
| **type** | `string` | **REQUIRED**. Data type of the parameter. One of: `string`, `number`, `boolean`, `object`, `array`. |
| **description** | `string` | **REQUIRED**. Human-readable description of the parameter. Essential for agent discovery. |
| **pattern** | `string` | Optional regex pattern for parameter value validation. |
| **value** | `string` | Default value or JSONPath reference. |

**Rules:**

- All of `name`, `in`, `type`, and `description` are mandatory.
- The `name` field MUST match the pattern `^[a-zA-Z0-9-*]+$`.
- The `in` field MUST be one of: `"query"`, `"header"`, `"path"`, `"cookie"`, `"body"`.
- The `type` field MUST be one of: `"string"`, `"number"`, `"boolean"`, `"object"`, `"array"`.
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

> Update (schema v0.4): The single `OutputParameter` object has been split into three distinct types: **ConsumedOutputParameter** (used in consumed operations), **MappedOutputParameter** (used in simple-mode exposed operations), and **OrchestratedOutputParameter** (used in orchestrated-mode exposed operations).
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

Used in **simple mode** exposed operations. Maps a value from the consumed response using `type` and `mapping`.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **type** | `string` | **REQUIRED**. Data type. One of: `string`, `number`, `boolean`, `object`, `array`. |
| **mapping** | `string` | `object` | **REQUIRED**. For scalar types (`string`, `number`, `boolean`): a JsonPath string. For `object`: an object with `properties` (recursive MappedOutputParameter map). For `array`: an object with `items` (recursive MappedOutputParameter). |

**Subtypes by type:**

- **`string`**, **`number`**, **`boolean`**: `mapping` is a JsonPath string (e.g. `$.login`)
- **`object`**: `mapping` is `{ properties: { key: MappedOutputParameter, ... } }` — recursive
- **`array`**: `mapping` is `{ items: MappedOutputParameter }` — recursive

**Rules:**

- Both `type` and `mapping` are mandatory.
- No additional properties are allowed.

**MappedOutputParameter Examples:**

```yaml
# Scalar mapping
outputParameters:
  - type: string
    mapping: $.login
  - type: number
    mapping: $.id

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

#### 3.12.4 JsonPath roots (extensions)

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

> Update (schema v0.4): OperationStep is now a discriminated union (`oneOf`) with a required `type` field (`"call"` or `"lookup"`) and a required `name` field. `OperationStepCall` uses `with` (WithInjector) instead of `inputParameters`. `OperationStepLookup` is entirely new.
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
| **with** | `WithInjector` | Parameter injection for the called operation. Keys are parameter names, values are strings or numbers (static values or `$this` references). |

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
| **lookupValue** | `string` | **REQUIRED**. JsonPath expression resolving to the value(s) to look up. |
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
      database_id: $this.sample.database_id
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
    lookupValue: $this.sample.user_email
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
    lookupValue: $this.sample.target_id
    outputParameters:
      - title
      - status
  - type: call
    name: post-result
    call: slack.post-message
    with:
      text: $this.sample.title
```

---

### 3.14 StepOutputMapping Object

Describes how to map the output of an operation step to the input of another step or to the output of the exposed operation.

#### 3.14.1 Fixed Fields

| Field Name | Type | Description |
| --- | --- | --- |
| **targetName** | `string` | **REQUIRED**. The name of the parameter to map to. It can be an input parameter of a next step or an output parameter of the exposed operation. |
| **value** | `string` | **REQUIRED**. A JsonPath reference to the value to map from. E.g. `$.get-database.database_id`. |

#### 3.14.2 Rules

- Both `targetName` and `value` are mandatory.
- No additional properties are allowed.

#### 3.14.3 How mappings wire steps to exposed outputs

A StepOutputMapping connects the **output parameters of a consumed operation** (called by the step) to the **output parameters of the exposed operation** (or to input parameters of subsequent steps).

- **`targetName`** — refers to the `name` of an output parameter declared on the exposed operation, or the `name` of an input parameter of a subsequent step. The target parameter receives its value from the mapping.
- **`value`** — a JsonPath expression where **`$`** is the root of the consumed operation's output parameters. The syntax `$.{outputParameterName}` references a named output parameter of the consumed operation called in this step.

#### 3.14.4 End-to-end example

Consider a consumed operation `notion.get-database` that declares:

```yaml
# In consumes → resources → operations
name: "get-database"
outputParameters:
  - name: "dbName"
    value: "$.title[0].text.content"
```

And the exposed side of the capability:

```yaml
# In exposes
exposes:
  - type: "api"
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
                  database_id: "$this.sample.database_id"
            mappings:
              - targetName: "db_name"
                value: "$.dbName"
```

Here is what happens at orchestration time:

1. The step `fetch-db` calls `notion.get-database`, which extracts `dbName` and `dbId` from the raw response via its own output parameters.
2. The `with` injector passes `database_id` from the exposed input parameter (`$this.sample.database_id`) to the consumed operation.
3. The mapping `targetName: "db_name"` refers to the exposed operation's output parameter `db_name`.
4. The mapping `value: "$.dbName"` resolves to the value of the consumed operation's output parameter named `dbName`.
5. As a result, the exposed output `db_name` is populated with the value extracted by `$.dbName` (i.e. `title[0].text.content` from the raw Notion API response).

#### 3.14.5 StepOutputMapping Object Example

```yaml
mappings:
  - targetName: "db_name"
    value: "$.dbName"
```

---

### 3.15 `$this` Context Reference

Describes how `$this` references work in `with` (WithInjector) and other expression contexts.

> Update (schema v0.4): The former `OperationStepParameter` object (with `name` and `value` fields) has been replaced by `WithInjector` (see §3.18). This section now documents the `$this` expression root, which is used within `WithInjector` values.
> 

#### 3.15.1 The `$this` root

In a `with` (WithInjector) value — whether on an ExposedOperation (simple mode) or an OperationStepCall — the **`$this`** root references the *current capability execution context*, i.e. values already resolved during orchestration.

**`$this`** navigates the expose layer's input parameters using the path `$this.{exposeNamespace}.{inputParameterName}`. This allows a step or a simple-mode call to receive values that were provided by the caller of the exposed operation.

- **`$this.{exposeNamespace}.{paramName}`** — accesses an input parameter of the exposed resource or operation identified by its namespace.
- The `{exposeNamespace}` corresponds to the `namespace` of the exposed API.
- The `{paramName}` corresponds to the `name` of an input parameter declared on the exposed resource or operation.

#### 3.15.2 Example

If the exposed API has namespace `sample` and an input parameter `database_id` declared on its resource, then:

- `$this.sample.database_id` resolves to the value of `database_id` provided by the caller.

**Usage in a WithInjector:**

```yaml
call: notion.get-database
with:
  database_id: $this.sample.database_id
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

> Update (schema v0.4): Renamed from `ForwardHeaders` to `ForwardConfig`. The `targetNamespaces` array has been replaced by a single `targetNamespace` string.
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

> New in schema v0.4.
> 

#### 3.18.1 Shape

`WithInjector` is an object whose keys are parameter names and whose values are static values or `$this` references.

- Each key corresponds to a parameter `name` in the consumed operation's `inputParameters`.
- Each value is a `string` or a `number`: either a static value or a `$this.{namespace}.{paramName}` reference.

#### 3.18.2 Rules

- The keys MUST correspond to valid parameter names in the consumed operation being called.
- Values can be strings or numbers.
- String values can use the `$this` root to reference exposed input parameters (same as in OperationStepParameter).
- No additional constraints.

#### 3.18.3 WithInjector Object Example

```yaml
call: github.get-user
with:
  username: $this.sample.username
  Accept: "application/json"
  maxRetries: 3
```

---

### 3.19 Bind Object

> **Updated**: `Bind` replaces the former `ExternalRef` discriminated union. The `type` and `resolution` fields have been removed. A single optional `location` URI field determines the provider. Variable names (left side of `keys`) use SCREAMING_SNAKE_CASE for visual distinction from declared parameters.
> 

Declares that the capability binds to an external source of variables. Bindings are declared at the root level of the Naftiko document via the `binds` array, or as a child of the `capability` object.

**Fixed Fields:**

| Field Name | Type | Description |
| --- | --- | --- |
| **namespace** | `string` | **REQUIRED**. Unique identifier for this binding (kebab-case). Used as qualifier in expressions for disambiguation. MUST match pattern `^[a-zA-Z0-9-]+$`. |
| **description** | `string` | *Recommended*. A meaningful description of the binding's purpose. In a world of agents, context is king. |
| **location** | `string` (UriLocation) | URI identifying the value provider. The URI scheme expresses the resolution strategy (`file://`, `vault://`, `github-secrets://`, `k8s-secret://`, etc.). When omitted, values are injected by the runtime environment. MUST match pattern `^[a-zA-Z][a-zA-Z0-9+.-]*://` (RFC 3986 scheme). |
| **keys** | `BindingKeys` | **REQUIRED**. Map of variable names (SCREAMING_SNAKE_CASE, `^[A-Z][A-Z0-9_]*$`) to source keys in the provider. |

**Rules:**

- `namespace` and `keys` are mandatory.
- `location` and `description` are optional.
- No additional properties are allowed.

Typical production providers include:

- **HashiCorp Vault** — centralized secrets management (`vault://`)
- **Kubernetes Secrets** / **ConfigMaps** — native K8s secret injection (`k8s-secret://`)
- **AWS Secrets Manager** / **AWS SSM Parameter Store** (`aws-ssm://`)
- **GitHub Actions Secrets** (`github-secrets://`)
- **CI/CD pipeline variables** — runtime injection (location omitted)

#### 3.19.1 BindingKeys Object

A map of key-value pairs that define the variables to be injected from the binding.

- Each **key** is the variable name used for injection (SCREAMING_SNAKE_CASE, available as `\{\{KEY\}\}` in the capability definition)
- Each **value** is the corresponding key in the resolved source or runtime context

Example: `{"NOTION_TOKEN": "NOTION_INTEGRATION_TOKEN"}` means the value of `NOTION_INTEGRATION_TOKEN` in the source will be injected as `{{NOTION_TOKEN}}` in the capability definition.

**Schema:**

```json
{
  "type": "object",
  "propertyNames": { "pattern": "^[A-Z][A-Z0-9_]*$" },
  "additionalProperties": { "type": "string" }
}
```

#### 3.19.2 Rules

- Each `namespace` value MUST be unique across all `binds` entries.
- The `namespace` value MUST NOT collide with any `consumes` or `exposes` namespace to avoid ambiguity in expression resolution.
- The `keys` map MUST contain at least one entry.
- When `location` is present, it MUST be a valid URI matching the `UriLocation` pattern.
- When `location` is omitted, the runtime environment is responsible for injecting the values.
- No additional properties are allowed.

<aside>
⚠️

**Security recommendation** — Never use `location: "file://..."` in production environments. File-based resolution exposes secret locations in the capability document and creates a risk of accidental secret leakage (e.g. committing `.env` files). Always omit `location` in production, with secrets injected by a dedicated secrets manager.

</aside>

#### 3.19.3 Bind Object Examples

**File-based binding (development):**

```yaml
binds:
  - namespace: "notion-env"
    description: "Notion API credentials for local development."
    location: "file:///path/to/notion_env.json"
    keys:
      NOTION_TOKEN: "NOTION_INTEGRATION_TOKEN"
      NOTION_PROJECTS_DB_ID: "PROJECTS_DATABASE_ID"
      NOTION_TIME_TRACKER_DB_ID: "TIME_TRACKER_DATABASE_ID"
```

**Runtime injection (production — location omitted):**

```yaml
binds:
  - namespace: "secrets"
    keys:
      NOTION_TOKEN: "NOTION_INTEGRATION_TOKEN"
      GITHUB_TOKEN: "GITHUB_TOKEN"
```

**Minimal binding (runtime injection):**

```yaml
binds:
  - namespace: "env"
    keys:
      API_KEY: "API_KEY"
```

---

### 3.20 Expression Syntax

Variables declared in `binds` via the `keys` map are injected into the capability document using mustache-style `\{\{variable\}\}` expressions.

#### 3.20.1 Format

The expression format is `\{\{KEY\}\}`, where `KEY` is a variable name (SCREAMING_SNAKE_CASE) declared in the `keys` map of a `binds` entry.

Expressions can appear in any `string` value within the document, including authentication tokens, header values, and input parameter values.

#### 3.20.2 Resolution

At runtime, expressions are resolved as follows:

1. Find the `binds` entry whose `keys` map contains the referenced variable name
2. Look up the corresponding source key in the `keys` map
3. Resolve the source key value using the strategy defined by the `location` (file lookup if present, runtime injection if absent)
4. Replace the `\{\{KEY\}\}` expression with the resolved value

If a referenced variable is not declared in any `binds` entry's `keys`, the document MUST be considered invalid.

#### 3.20.3 Relationship with `$this`

`\{\{variable\}\}` expressions and `$this` references serve different purposes:

- `\{\{variable\}\}` resolves **static configuration** from external references (secrets, environment variables) declared via `keys`
- `$this.{exposeNamespace}.{paramName}` resolves **runtime orchestration** values from the expose layer's input parameters

The two expression systems are independent and MUST NOT be mixed.

#### 3.20.4 Expression Examples

```yaml
# Authentication token from binding
authentication:
  type: bearer
  token: "{{NOTION_TOKEN}}"

# Input parameter with header value from binding
inputParameters:
  - name: Authorization
    in: header
    value: "Bearer {{API_KEY}}"

# Corresponding binds declaration
binds:
  - namespace: "env"
    keys:
      NOTION_TOKEN: "NOTION_TOKEN"
      API_KEY: "API_KEY"
```

---

## 4. Complete Examples

This section provides progressive examples — from the simplest capability to a full-featured one — to illustrate the main patterns of the specification. All examples are pseudo-functional and use realistic API shapes.

### 4.1 Forward-only capability (proxy)

The simplest capability: forward incoming requests to a consumed API without any transformation.

```yaml
---
naftiko: "0.4"
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
    - type: "api"
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
          path: "/{path}"
          operations:
            - name: "any"
              method: "GET"
```

### 4.2 Simple-mode capability (direct call)

A single exposed operation that directly calls a consumed operation, maps parameters with `with`, and extracts output.

```yaml
---
naftiko: "0.4"
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
    - type: "api"
      port: 3000
      namespace: "app"
      resources:
        - path: "/users/{username}"
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
                username: "$this.app.username"
              outputParameters:
                - type: "string"
                  mapping: "$.login"
                - type: "string"
                  mapping: "$.email"
                - type: "number"
                  mapping: "$.id"

  consumes:
    - type: "http"
      namespace: "github"
      description: "GitHub REST API v3"
      baseUri: "https://api.github.com"
      authentication:
        type: "bearer"
        token: "{{GITHUB_TOKEN}}"
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
                  value: "$.login"
                - name: "email"
                  type: "string"
                  value: "$.email"
                - name: "id"
                  type: "number"
                  value: "$.id"
```

### 4.3 Orchestrated capability (multi-step call)

An exposed operation that chains two consumed operations using named steps and `with`.

```yaml
---
naftiko: "0.4"
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
    - type: "api"
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
                    database_id: "$this.inspector.database_id"
                - type: "call"
                  name: "query-db"
                  call: "notion.query-database"
                  with:
                    database_id: "$this.inspector.database_id"
              mappings:
                - targetName: "db_name"
                  value: "$.fetch-db.dbName"
                - targetName: "row_count"
                  value: "$.query-db.resultCount"
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
        token: "{{NOTION_TOKEN}}"
      inputParameters:
        - name: "Notion-Version"
          in: "header"
          value: "2022-06-28"
      resources:
        - name: "databases"
          path: "/databases/{database_id}"
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
                  value: "$.title[0].text.content"
                - name: "dbId"
                  type: "string"
                  value: "$.id"
        - name: "queries"
          path: "/databases/{database_id}/query"
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
                  value: "$.results.length()"
                - name: "results"
                  type: "array"
                  value: "$.results"
```

### 4.4 Orchestrated capability with lookup step

Demonstrates a `lookup` step that cross-references the output of a previous call to enrich data.

```yaml
---
naftiko: "0.4"
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
    - type: "api"
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
                  lookupValue: "$this.team.email"
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
        value: "{{HR_API_KEY}}"
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
naftiko: "0.4"
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
    - type: "api"
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
                owner: "$this.dashboard.owner"
                repo: "$this.dashboard.repo"
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
                    database_id: "$this.dashboard.database_id"
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
        token: "{{NOTION_TOKEN}}"
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
        token: "{{GITHUB_TOKEN}}"
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

## 5. Versioning

The Naftiko Specification uses semantic versioning. The `naftiko` field in the Naftiko Object specifies the exact version of the specification (e.g., `"0.4"`). 

Tools processing Naftiko documents MUST validate this field to ensure compatibility with the specification version they support.

---

This specification defines how to describe modular, composable capabilities that consume multiple sources and expose unified interfaces, supporting orchestration, authentication, and flexible routing patterns.