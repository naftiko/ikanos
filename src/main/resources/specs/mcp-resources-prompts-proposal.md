# MCP Resources & Prompt Templates Support Proposal
## Extending the MCP Server Adapter with Resources and Prompts

**Status**: Proposal  
**Date**: March 5, 2026  
**Key Concept**: Add MCP resources and prompt templates to the existing `mcp` server adapter, aligning with the MCP specification while maintaining consistency with the existing `api` adapter patterns and the Agent Skills proposal's `location`-based file serving.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Architecture Overview](#architecture-overview)
3. [Design Analogy](#design-analogy)
4. [MCP Resources](#mcp-resources)
5. [MCP Prompt Templates](#mcp-prompt-templates)
6. [Schema Amendments](#schema-amendments)
7. [Protocol Changes](#protocol-changes)
8. [Implementation Examples](#implementation-examples)
9. [Security Considerations](#security-considerations)
10. [Validation Rules](#validation-rules)
11. [Implementation Roadmap](#implementation-roadmap)
12. [Backward Compatibility](#backward-compatibility)

---

## Executive Summary

### What This Proposes

Extend the current `mcp` server adapter — which today only supports **tools** — with two additional MCP primitives:

1. **Resources** — Expose data and content that agents can read. Two source types:
   - **Dynamic** (`call`/`steps`): Resources backed by consumed HTTP operations, using the same orchestration model as tools
   - **Static** (`location`): Resources served from local files, aligned with the Agent Skills proposal's `location`-based file serving pattern

2. **Prompt Templates** — Expose reusable prompt templates with typed arguments that agents can discover and render. Two source types:
   - **Inline** (`template`): Prompt content declared directly in YAML
   - **File-based** (`location`): Prompt content loaded from a local file

### Why Extend the MCP Adapter?

The [MCP specification](https://spec.modelcontextprotocol.io/) defines three core server primitives:

| Primitive | Purpose | Current Support |
|-----------|---------|-----------------|
| **Tools** | Model-controlled functions agents can invoke | **Supported** |
| **Resources** | Application-controlled data agents can read | **Not supported** |
| **Prompts** | User-controlled templates agents can render | **Not supported** |

Supporting all three primitives makes Naftiko a complete MCP server implementation. Resources and prompts are purely additive — they do not change tool execution.

### Business Value

| Benefit | Impact | Users |
|---------|--------|-------|
| **Complete MCP compliance** | Full server primitive coverage (tools + resources + prompts) | Developers |
| **Data exposure** | Expose configuration, documentation, and API responses as readable resources | AI Agents |
| **Prompt standardization** | Distribute reusable prompt templates through MCP protocol | Prompt Engineers |
| **File serving** | Serve local files as MCP resources, consistent with the `skill` adapter's `location` pattern | Organizations |
| **Agent context** | Agents read resources for context before invoking tools | AI Applications |

### Key Design Decisions

1. **Same orchestration model**: Dynamic resources use `call`/`steps`/`with` exactly like tools — no new execution paradigm. Agents already understand this pattern.

2. **Static resources from local files**: The `location` property uses a `file:///` URI pointing to a directory, consistent with `ExposedSkill.location` in the Agent Skills proposal. Files under that directory are served as individual MCP resources.

3. **Prompt templates are declarative**: Prompts declare arguments and content — the MCP server renders them. No orchestration needed.

4. **MCP protocol methods**: New JSON-RPC methods (`resources/list`, `resources/read`, `resources/templates/list`, `prompts/list`, `prompts/get`) follow the MCP specification exactly.

5. **Capability advertisement**: The `initialize` response advertises `resources` and/or `prompts` capabilities only when they are declared in the spec.

6. **Tools remain required for now**: The `tools` array remains required on `ExposesMcp`. A future schema revision may relax this to allow resource-only or prompt-only MCP servers.

### Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| **Path traversal** (static resources) | Medium | High | Strict path validation, resolved path containment check |
| **Large file serving** | Low | Medium | Size limits, streaming |
| **Schema complexity** | Low | Low | Additive — new optional arrays alongside existing `tools` |
| **MCP version drift** | Low | Medium | Pin to MCP protocol version `2025-03-26` |

**Overall Risk**: **LOW** — Purely additive; tools behavior unchanged

---

## Architecture Overview

### Current State

```
ExposesMcp
├── type: "mcp"
├── transport: "http" | "stdio"
├── namespace
├── description
└── tools[]                          ← only primitive today
    ├── name, description
    ├── inputParameters[]
    ├── call / steps / with
    └── outputParameters[]
```

### Proposed State

```
ExposesMcp
├── type: "mcp"
├── transport: "http" | "stdio"
├── namespace
├── description
├── tools[]                          ← unchanged
│   ├── name, description
│   ├── inputParameters[]
│   ├── call / steps / with
│   └── outputParameters[]
├── resources[]                      ← NEW
│   ├── name, uri, description, mimeType
│   ├── Dynamic: call / steps / with
│   └── Static:  location
└── prompts[]                        ← NEW
    ├── name, description
    ├── arguments[]
    └── template / location
```

---

## Design Analogy

### How the three primitives relate across adapters

```
API Adapter                   MCP Adapter (current)          MCP Adapter (proposed)
─────────────                 ─────────────────────          ──────────────────────
ExposesApi                    ExposesMcp                     ExposesMcp
├─ resources[]                ├─ tools[]                     ├─ tools[]
│  ├─ path                    │  ├─ name                     │  ├─ name
│  ├─ description             │  ├─ description              │  ├─ description
│  ├─ operations[]            │  ├─ inputParameters[]        │  ├─ inputParameters[]
│  │  ├─ method               │  ├─ call / steps             │  ├─ call / steps
│  │  ├─ call / steps         │  ├─ with                     │  ├─ with
│  │  └─ outputParameters[]   │  └─ outputParameters[]       │  └─ outputParameters[]
│  └─ forward                 │                              │
│                             │                              ├─ resources[]          ← NEW
│                             │                              │  ├─ name, uri
│                             │                              │  ├─ description
│                             │                              │  ├─ mimeType
│                             │                              │  ├─ call / steps      (dynamic)
│                             │                              │  └─ location          (static)
│                             │                              │
│                             │                              └─ prompts[]            ← NEW
│                             │                                 ├─ name, description
│                             │                                 ├─ arguments[]
│                             │                                 └─ template / location
```

### Conceptual mapping: API adapter ↔ MCP adapter

| API Adapter concept | MCP Tool (existing) | MCP Resource (new) | MCP Prompt (new) |
|---------------------|--------------------:|-------------------:|------------------:|
| Resource path | Tool name | Resource URI | Prompt name |
| Operation (GET/POST) | `call`/`steps` | `call`/`steps` or `location` | `template`/`location` |
| inputParameters | inputParameters | — (resources are parameterless in MCP) | arguments |
| outputParameters | outputParameters | Content (text/blob) | Messages |
| Forward | — | Static `location` | File-based `location` |

---

## MCP Resources

MCP resources expose data that agents can **read** (but not invoke like tools). Resources are identified by URI and return typed content.

### Two Source Types

#### 1. Dynamic Resources (`call`/`steps`)

Dynamic resources are backed by consumed HTTP operations. They use the same orchestration model as tools:

```yaml
resources:
  - name: "current-config"
    uri: "config://app/current"
    description: "Current application configuration"
    mimeType: "application/json"
    call: "config-api.get-config"
```

When an agent reads this resource, the MCP server executes the consumed operation and returns the response as resource content.

**With steps (orchestrated):**

```yaml
resources:
  - name: "user-summary"
    uri: "data://users/summary"
    description: "Aggregated user summary from multiple API calls"
    mimeType: "application/json"
    steps:
      - type: "call"
        name: "fetch-users"
        call: "user-api.list-users"
      - type: "call"
        name: "fetch-stats"
        call: "analytics-api.get-stats"
    mappings:
      - targetName: users
        value: "$.fetch-users.data"
      - targetName: stats
        value: "$.fetch-stats.summary"
    outputParameters:
      - name: users
        type: array
      - name: stats
        type: object
```

#### 2. Static Resources (`location`)

Static resources are served from local files. The `location` property is a `file:///` URI pointing to a directory — consistent with the `location` pattern in the Agent Skills proposal's `ExposedSkill`:

```yaml
resources:
  - name: "api-docs"
    uri: "docs://api/reference"
    description: "API reference documentation"
    mimeType: "text/markdown"
    location: "file:///etc/naftiko/resources/api-docs"
```

**Expected directory structure at the location:**
```
/etc/naftiko/resources/api-docs/
├── index.md
├── endpoints/
│   ├── users.md
│   └── orders.md
└── schemas/
    └── response.json
```

Each file under the location directory becomes a separate MCP resource. The server auto-generates URIs based on the resource's `uri` prefix and relative file paths:

| File | Generated MCP Resource URI |
|------|---------------------------|
| `index.md` | `docs://api/reference/index.md` |
| `endpoints/users.md` | `docs://api/reference/endpoints/users.md` |
| `schemas/response.json` | `docs://api/reference/schemas/response.json` |

If a `location` is specified without sub-files, the directory itself is the resource and the `uri` resolves directly to its content.

### Resource URI Schemes

MCP resources use URIs to identify content. The URI is declared by the capability author:

```yaml
# Custom scheme (recommended for clarity)
uri: "config://app/current"
uri: "docs://api/reference"
uri: "data://users/summary"

# HTTPS scheme (for resources that mirror external URLs)
uri: "https://api.example.com/config"
```

The URI is an identifier — it does not imply how the resource is fetched. Dynamic resources execute consumed operations; static resources read local files.

### Resource Template URIs

For dynamic resources, the URI can contain parameters using the `{param}` placeholder syntax (consistent with the MCP spec's resource templates):

```yaml
resources:
  - name: "user-profile"
    uri: "data://users/{userId}/profile"
    description: "User profile by ID"
    mimeType: "application/json"
    call: "user-api.get-user"
    with:
      user_id: "{{userId}}"
```

Resource templates are advertised via `resources/templates/list` and resolved when agents call `resources/read` with a concrete URI.

---

## MCP Prompt Templates

MCP prompts are reusable templates with typed arguments that agents can discover and render into structured messages.

### Two Source Types

#### 1. Inline Prompts (`template`)

Prompt content declared directly in YAML. Arguments are injected via `{{arg}}` placeholders:

```yaml
prompts:
  - name: "summarize-data"
    description: "Summarize API response data for the user"
    arguments:
      - name: "data"
        description: "The raw API response data to summarize"
        required: true
      - name: "format"
        description: "Desired output format (bullet-points, paragraph, table)"
        required: false
    template:
      - role: "user"
        content: "Summarize the following data in {{format}} format:\n\n{{data}}"
```

#### 2. File-Based Prompts (`location`)

Prompt content loaded from a local file. Consistent with the `location` pattern used by static resources and the Agent Skills proposal:

```yaml
prompts:
  - name: "code-review"
    description: "Structured code review prompt with context"
    arguments:
      - name: "language"
        description: "Programming language"
        required: true
      - name: "code"
        description: "Code to review"
        required: true
    location: "file:///etc/naftiko/prompts/code-review.md"
```

The file at the location contains the prompt template with `{{arg}}` placeholders. The MCP server reads the file, substitutes arguments, and returns the rendered messages.

**File content (`code-review.md`):**
```markdown
Review the following {{language}} code for:
- Correctness
- Performance
- Security
- Readability

```{{language}}
{{code}}
```

Provide specific, actionable feedback.
```

When a file-based prompt is rendered, its content becomes a single `user` role message by default.

### Prompt Arguments

Arguments are typed parameters that agents provide when rendering a prompt:

```yaml
arguments:
  - name: "topic"
    description: "The topic to analyze"
    required: true
  - name: "depth"
    description: "Analysis depth: brief, standard, or deep"
    required: false
```

Arguments follow the same conventions as `McpToolInputParameter` but are simpler — they only have `name`, `description`, and `required`. No `type` field is needed because prompt arguments are always strings (per MCP spec).

### Prompt Messages

Inline prompts declare messages as an array of `{role, content}` objects. The `role` must be one of `"user"` or `"assistant"`:

```yaml
template:
  - role: "user"
    content: "You are an expert in {{domain}}. Analyze the following:\n\n{{input}}"
  - role: "assistant"
    content: "I'll analyze this from the perspective of {{domain}}. Let me examine the key aspects."
  - role: "user"
    content: "Focus specifically on: {{focus_area}}"
```

---

## Schema Amendments

### Amendment 1: Update `ExposesMcp` — Add `resources` and `prompts`

Add two optional arrays to the existing `ExposesMcp` definition:

```json
{
  "ExposesMcp": {
    "type": "object",
    "description": "MCP Server exposition configuration. Exposes tools, resources and prompts over MCP transport (Streamable HTTP or stdio).",
    "properties": {
      "type": {
        "type": "string",
        "const": "mcp"
      },
      "transport": {
        "type": "string",
        "enum": ["http", "stdio"],
        "default": "http",
        "description": "The MCP transport to use. 'http' (default) exposes a Streamable HTTP server; 'stdio' uses stdin/stdout JSON-RPC for local IDE integration."
      },
      "address": {
        "$ref": "#/$defs/Address"
      },
      "port": {
        "type": "integer",
        "minimum": 1,
        "maximum": 65535
      },
      "namespace": {
        "$ref": "#/$defs/IdentifierKebab",
        "description": "Unique identifier for this exposed MCP server"
      },
      "description": {
        "type": "string",
        "description": "A meaningful description of this MCP server's purpose. Used as the server instructions sent during MCP initialization."
      },
      "tools": {
        "type": "array",
        "description": "List of MCP tools exposed by this server",
        "items": {
          "$ref": "#/$defs/McpTool"
        },
        "minItems": 1
      },
      "resources": {
        "type": "array",
        "description": "List of MCP resources exposed by this server. Resources provide data that agents can read.",
        "items": {
          "$ref": "#/$defs/McpResource"
        },
        "minItems": 1
      },
      "prompts": {
        "type": "array",
        "description": "List of MCP prompt templates exposed by this server. Prompts are reusable templates with typed arguments.",
        "items": {
          "$ref": "#/$defs/McpPrompt"
        },
        "minItems": 1
      }
    },
    "required": [
      "type",
      "namespace",
      "tools"
    ],
    "oneOf": [
      {
        "properties": {
          "transport": { "const": "stdio" }
        },
        "required": ["transport"],
        "not": { "required": ["port"] }
      },
      {
        "properties": {
          "transport": { "const": "http" }
        },
        "required": ["port"]
      }
    ],
    "additionalProperties": false
  }
}
```

**Changes from current schema:**
- Description updated to mention resources and prompts
- Added `resources` (optional array of `McpResource`)
- Added `prompts` (optional array of `McpPrompt`)
- `tools` remains required (future revision may relax this)
- Transport rules unchanged

---

### Amendment 2: New `McpResource` Definition

```json
{
  "McpResource": {
    "type": "object",
    "description": "An MCP resource definition. Exposes data that agents can read. Either dynamic (backed by consumed HTTP operations via call/steps) or static (served from local files via location).",
    "properties": {
      "name": {
        "$ref": "#/$defs/IdentifierKebab",
        "description": "Technical name for the resource. Used as identifier in MCP resource listings."
      },
      "uri": {
        "type": "string",
        "description": "The URI that identifies this resource in MCP. Can use any scheme (e.g. config://, docs://, data://). For resource templates, use {param} placeholders."
      },
      "description": {
        "type": "string",
        "description": "A meaningful description of the resource. Used for agent discovery. In a world of agents, context is king."
      },
      "mimeType": {
        "type": "string",
        "description": "MIME type of the resource content (e.g. application/json, text/markdown, text/plain)."
      },
      "call": {
        "type": "string",
        "description": "For dynamic resources: reference to the consumed operation that produces the resource content. Format: {namespace}.{operationId}.",
        "pattern": "^[a-zA-Z0-9-]+\\.[a-zA-Z0-9-]+$"
      },
      "with": {
        "$ref": "#/$defs/WithInjector"
      },
      "steps": {
        "type": "array",
        "items": {
          "$ref": "#/$defs/OperationStep"
        },
        "minItems": 1
      },
      "mappings": {
        "type": "array",
        "description": "Maps step outputs to the resource content.",
        "items": {
          "$ref": "#/$defs/StepOutputMapping"
        }
      },
      "outputParameters": {
        "type": "array"
      },
      "location": {
        "type": "string",
        "format": "uri",
        "pattern": "^file:///",
        "description": "For static resources: file:/// URI pointing to a directory whose files are served as MCP resources. Consistent with ExposedSkill.location."
      }
    },
    "required": [
      "name",
      "uri",
      "description"
    ],
    "oneOf": [
      {
        "required": ["call"],
        "type": "object",
        "properties": {
          "outputParameters": {
            "type": "array",
            "items": {
              "$ref": "#/$defs/MappedOutputParameter"
            }
          }
        },
        "not": { "required": ["location"] }
      },
      {
        "required": ["steps"],
        "type": "object",
        "properties": {
          "mappings": true,
          "outputParameters": {
            "type": "array",
            "items": {
              "$ref": "#/$defs/OrchestratedOutputParameter"
            }
          }
        },
        "not": { "required": ["location"] }
      },
      {
        "required": ["location"],
        "not": {
          "anyOf": [
            { "required": ["call"] },
            { "required": ["steps"] }
          ]
        }
      }
    ],
    "additionalProperties": false
  }
}
```

**Design notes:**
- `name`, `uri`, `description` are always required
- Exactly one source: `call` (simple dynamic), `steps` (orchestrated dynamic), or `location` (static)
- Dynamic resources reuse `WithInjector`, `OperationStep`, `StepOutputMapping`, `MappedOutputParameter`, and `OrchestratedOutputParameter` — no new execution types
- `location` uses `file:///` URI with the same pattern as `ExposedSkill.location` in the Agent Skills proposal
- `mimeType` is optional — inferred from content or file extension when absent
- Resource template URIs (with `{param}` placeholders) are supported via the `uri` field

---

### Amendment 3: New `McpPrompt` Definition

```json
{
  "McpPrompt": {
    "type": "object",
    "description": "An MCP prompt template definition. Prompts are reusable templates with typed arguments that agents can discover and render.",
    "properties": {
      "name": {
        "$ref": "#/$defs/IdentifierKebab",
        "description": "Technical name for the prompt. Used as identifier in MCP prompt listings."
      },
      "description": {
        "type": "string",
        "description": "A meaningful description of the prompt and when to use it. Used for agent discovery."
      },
      "arguments": {
        "type": "array",
        "description": "Typed arguments for this prompt template. Arguments are substituted into the template via {{arg}} placeholders.",
        "items": {
          "$ref": "#/$defs/McpPromptArgument"
        },
        "minItems": 1
      },
      "template": {
        "type": "array",
        "description": "Inline prompt template as an array of messages. Each message has a role and content with {{arg}} placeholders.",
        "items": {
          "$ref": "#/$defs/McpPromptMessage"
        },
        "minItems": 1
      },
      "location": {
        "type": "string",
        "format": "uri",
        "pattern": "^file:///",
        "description": "File-based prompt: file:/// URI pointing to a file containing the prompt template with {{arg}} placeholders. Content becomes a single 'user' message. Consistent with ExposedSkill.location and McpResource.location."
      }
    },
    "required": [
      "name",
      "description"
    ],
    "oneOf": [
      {
        "required": ["template"],
        "not": { "required": ["location"] }
      },
      {
        "required": ["location"],
        "not": { "required": ["template"] }
      }
    ],
    "additionalProperties": false
  }
}
```

---

### Amendment 4: New `McpPromptArgument` Definition

```json
{
  "McpPromptArgument": {
    "type": "object",
    "description": "An argument for an MCP prompt template. Arguments are always strings per MCP spec.",
    "properties": {
      "name": {
        "$ref": "#/$defs/IdentifierExtended",
        "description": "Argument name. Becomes a {{name}} placeholder in the template."
      },
      "description": {
        "type": "string",
        "description": "A meaningful description of the argument. Used for agent discovery."
      },
      "required": {
        "type": "boolean",
        "description": "Whether the argument is required. Defaults to true.",
        "default": true
      }
    },
    "required": [
      "name",
      "description"
    ],
    "additionalProperties": false
  }
}
```

**Design notes:**
- Follows the same pattern as `McpToolInputParameter` but without `type` (prompt arguments are always strings per MCP spec)
- Same `required` field semantics with `default: true`

---

### Amendment 5: New `McpPromptMessage` Definition

```json
{
  "McpPromptMessage": {
    "type": "object",
    "description": "A message in an inline MCP prompt template. Supports {{arg}} placeholders for argument substitution.",
    "properties": {
      "role": {
        "type": "string",
        "enum": ["user", "assistant"],
        "description": "The role of the message sender."
      },
      "content": {
        "type": "string",
        "description": "The message content. Supports {{arg}} placeholders for argument substitution."
      }
    },
    "required": ["role", "content"],
    "additionalProperties": false
  }
}
```

---

## Protocol Changes

### Updated `initialize` Response

The `initialize` response must advertise `resources` and/or `prompts` capabilities when they are declared:

```json
{
  "protocolVersion": "2025-03-26",
  "capabilities": {
    "tools": {},
    "resources": {},
    "prompts": {}
  },
  "serverInfo": {
    "name": "weather-mcp",
    "version": "1.0.0"
  }
}
```

Only advertise capabilities that are configured:
- `tools` — always (tools remain required)
- `resources` — only when `resources[]` is non-empty on the spec
- `prompts` — only when `prompts[]` is non-empty on the spec

### New JSON-RPC Methods

| Method | Purpose | Request Params | Response |
|--------|---------|----------------|----------|
| `resources/list` | List all resources | — | `{ resources: McpResourceDescriptor[] }` |
| `resources/read` | Read resource content | `{ uri: string }` | `{ contents: [{ uri, mimeType?, text? , blob? }] }` |
| `resources/templates/list` | List resource templates | — | `{ resourceTemplates: McpResourceTemplateDescriptor[] }` |
| `prompts/list` | List all prompts | — | `{ prompts: McpPromptDescriptor[] }` |
| `prompts/get` | Render a prompt | `{ name: string, arguments?: object }` | `{ messages: [{ role, content: { type, text } }] }` |

### `resources/list` Response

```json
{
  "resources": [
    {
      "uri": "config://app/current",
      "name": "current-config",
      "description": "Current application configuration",
      "mimeType": "application/json"
    },
    {
      "uri": "docs://api/reference/index.md",
      "name": "api-docs",
      "description": "API reference documentation",
      "mimeType": "text/markdown"
    }
  ]
}
```

For static resources with `location`, each file in the directory is listed as a separate resource with an auto-generated URI.

### `resources/read` Response

```json
{
  "contents": [
    {
      "uri": "config://app/current",
      "mimeType": "application/json",
      "text": "{\"version\": \"2.1\", \"environment\": \"production\"}"
    }
  ]
}
```

For binary content, use `blob` (base64-encoded) instead of `text`.

### `resources/templates/list` Response

Resources whose `uri` contains `{param}` placeholders are advertised as templates:

```json
{
  "resourceTemplates": [
    {
      "uriTemplate": "data://users/{userId}/profile",
      "name": "user-profile",
      "description": "User profile by ID",
      "mimeType": "application/json"
    }
  ]
}
```

### `prompts/list` Response

```json
{
  "prompts": [
    {
      "name": "summarize-data",
      "description": "Summarize API response data for the user",
      "arguments": [
        {
          "name": "data",
          "description": "The raw API response data to summarize",
          "required": true
        },
        {
          "name": "format",
          "description": "Desired output format",
          "required": false
        }
      ]
    }
  ]
}
```

### `prompts/get` Response

```json
{
  "messages": [
    {
      "role": "user",
      "content": {
        "type": "text",
        "text": "Summarize the following data in bullet-points format:\n\n{\"users\": 42, \"active\": 38}"
      }
    }
  ]
}
```

### Updated `McpProtocolDispatcher.dispatch()` Switch

```java
switch (rpcMethod) {
    case "initialize":          return handleInitialize(idNode);
    case "notifications/initialized": return null;
    case "tools/list":          return handleToolsList(idNode);
    case "tools/call":          return handleToolsCall(idNode, params);
    case "resources/list":      return handleResourcesList(idNode);           // NEW
    case "resources/read":      return handleResourcesRead(idNode, params);   // NEW
    case "resources/templates/list": return handleResourcesTemplatesList(idNode); // NEW
    case "prompts/list":        return handlePromptsList(idNode);             // NEW
    case "prompts/get":         return handlePromptsGet(idNode, params);      // NEW
    case "ping":                return buildJsonRpcResult(idNode, mapper.createObjectNode());
    default:                    return buildJsonRpcError(idNode, -32601, "Method not found: " + rpcMethod);
}
```

---

## Implementation Examples

### Example 1: Weather Capability with Resources and Prompts

```yaml
naftiko: "0.5"

info:
  label: "Weather Intelligence"
  description: "Weather data with tools, readable resources, and prompt templates"

capability:
  consumes:
    - type: "http"
      namespace: "weather-api"
      description: "OpenWeather API"
      baseUri: "https://api.openweathermap.org/data/2.5/"
      resources:
        - name: "weather"
          path: "weather"
          operations:
            - name: "get-current"
              method: "GET"
              inputParameters:
                - name: "q"
                  in: "query"
              outputParameters:
                - name: "temp"
                  type: "number"
                  value: "$.main.temp"

  exposes:
    - type: "mcp"
      transport: "http"
      address: "0.0.0.0"
      port: 9091
      namespace: "weather-mcp"
      description: "Weather MCP server with tools, resources, and prompts"

      # ── Tools (existing) ──
      tools:
        - name: "get-weather"
          description: "Get current weather for a city"
          inputParameters:
            - name: "city"
              type: "string"
              description: "City name"
          call: "weather-api.get-current"
          with:
            q: "{{city}}"
          outputParameters:
            - type: "number"
              mapping: "$.main.temp"

      # ── Resources (NEW) ──
      resources:
        # Dynamic: backed by consumed operation
        - name: "current-weather"
          uri: "weather://cities/{city}/current"
          description: "Current weather data for a city"
          mimeType: "application/json"
          call: "weather-api.get-current"
          with:
            q: "{{city}}"

        # Static: served from local files
        - name: "weather-guide"
          uri: "docs://weather/guide"
          description: "Guide to interpreting weather data and units"
          mimeType: "text/markdown"
          location: "file:///etc/naftiko/resources/weather-guide"

      # ── Prompts (NEW) ──
      prompts:
        # Inline template
        - name: "forecast-summary"
          description: "Generate a natural-language weather summary"
          arguments:
            - name: "city"
              description: "City name for the forecast"
              required: true
            - name: "data"
              description: "Raw weather data JSON"
              required: true
          template:
            - role: "user"
              content: "Summarize the weather for {{city}} based on this data:\n\n{{data}}\n\nProvide temperature, conditions, and a brief recommendation."

        # File-based template
        - name: "weather-report"
          description: "Detailed weather report prompt for multiple cities"
          arguments:
            - name: "cities"
              description: "Comma-separated list of cities"
              required: true
          location: "file:///etc/naftiko/prompts/weather-report.md"
```

### Example 2: Documentation Server (Resources + Prompts, No Dynamic Data)

```yaml
naftiko: "0.5"

info:
  label: "API Documentation Server"
  description: "Serve API docs as MCP resources with analysis prompts"

capability:
  consumes:
    - type: "http"
      namespace: "placeholder"
      description: "Placeholder consumed API (required by schema)"
      baseUri: "https://httpbin.org"
      resources:
        - name: "health"
          path: "/get"
          operations:
            - name: "ping"
              method: "GET"

  exposes:
    - type: "mcp"
      transport: "stdio"
      namespace: "docs-mcp"
      description: "API documentation server with readable docs and analysis prompts"

      tools:
        - name: "ping"
          description: "Health check"
          call: "placeholder.ping"

      resources:
        - name: "api-reference"
          uri: "docs://api/reference"
          description: "Complete API reference documentation"
          mimeType: "text/markdown"
          location: "file:///etc/naftiko/docs/api-reference"

        - name: "changelog"
          uri: "docs://api/changelog"
          description: "API changelog and release notes"
          mimeType: "text/markdown"
          location: "file:///etc/naftiko/docs/changelog"

      prompts:
        - name: "analyze-endpoint"
          description: "Analyze an API endpoint for best practices"
          arguments:
            - name: "endpoint"
              description: "The endpoint path (e.g., /users/{id})"
              required: true
            - name: "method"
              description: "HTTP method (GET, POST, etc.)"
              required: true
          template:
            - role: "user"
              content: "Analyze the {{method}} {{endpoint}} endpoint for:\n- RESTful design compliance\n- Error handling completeness\n- Security considerations\n- Performance implications"
```

### Example 3: Notion Capability Extended (Adding Resources and Prompts to Existing)

Shows how the existing Notion example can be non-disruptively extended:

```yaml
naftiko: "0.5"

info:
  label: "Notion Integration"
  description: "Notion with MCP tools, resources, and prompts"

capability:
  consumes:
    - type: "http"
      namespace: "notion"
      description: "Notion API v1"
      baseUri: "https://api.notion.com/v1/"
      authentication:
        type: "bearer"
        token: "{{notion_api_key}}"
      resources:
        - path: "databases/{{datasource_id}}/query"
          name: "query"
          operations:
            - method: "POST"
              name: "query-db"
              body: |
                {
                  "filter": {
                    "property": "Participation Status",
                    "select": { "equals": "Committed" }
                  }
                }

  exposes:
    - type: "mcp"
      address: "localhost"
      port: 9091
      namespace: "notion-mcp"
      description: "Notion MCP server"

      # Existing tools — unchanged
      tools:
        - name: "query-database"
          description: "Query Notion pre-release participants"
          call: "notion.query-db"
          with:
            datasource_id: "2fe4adce-3d02-8028-bec8-000bfb5cafa2"
          outputParameters:
            - type: "array"
              mapping: "$.results"
              items:
                - type: "object"
                  properties:
                    name:
                      type: "string"
                      mapping: "$.properties.Name.title[0].text.content"

      # NEW: Resources
      resources:
        - name: "database-schema"
          uri: "notion://databases/pre-release/schema"
          description: "Schema of the pre-release participants database"
          mimeType: "application/json"
          call: "notion.query-db"
          with:
            datasource_id: "2fe4adce-3d02-8028-bec8-000bfb5cafa2"

      # NEW: Prompts
      prompts:
        - name: "participant-outreach"
          description: "Draft outreach message to pre-release participants"
          arguments:
            - name: "participant_name"
              description: "Name of the participant"
              required: true
            - name: "product_name"
              description: "Name of the product"
              required: true
          template:
            - role: "user"
              content: "Draft a personalized outreach email to {{participant_name}} about the upcoming {{product_name}} pre-release. Be professional but friendly."
```

---

## Security Considerations

### Static Resource Path Validation

Static resources served from `location` directories must enforce strict path validation to prevent directory traversal:

1. **`location` URI scheme**: Only `file:///` is accepted
2. **Resolved path containment**: The resolved absolute path of any requested file must be within the `location` directory
3. **Path segment validation**: Each path segment must match `^[a-zA-Z0-9._-]+$` (no `..`, no special characters)
4. **Symlink resolution**: Resolve symlinks before containment check

These rules are identical to the security model described in the Agent Skills proposal for `ExposedSkill.location`.

### Prompt Template Injection

Prompt argument values are substituted into templates literally. The MCP server does not interpret argument values as templates — `{{nested}}` in an argument value is treated as literal text, not as a placeholder.

### Resource URI Validation

- Resource `uri` values are identifiers — they do not control file system access
- The `{param}` placeholder syntax in resource template URIs must match `^[a-zA-Z0-9_]+$`
- Resource URIs are validated at capability load time

---

## Validation Rules

### Resource Validation

| Rule | Scope | Description |
|------|-------|-------------|
| **Unique name** | resources[] | Each resource `name` MUST be unique within the MCP server |
| **Unique URI** | resources[] | Each resource `uri` MUST be unique within the MCP server |
| **Single source** | McpResource | Exactly one of `call`, `steps`, or `location` MUST be present |
| **Call reference** | McpResource.call | MUST reference a valid `{namespace}.{operationId}` in consumes |
| **Location scheme** | McpResource.location | MUST start with `file:///` |
| **Location exists** | McpResource.location | The resolved directory MUST exist at startup |

### Prompt Validation

| Rule | Scope | Description |
|------|-------|-------------|
| **Unique name** | prompts[] | Each prompt `name` MUST be unique within the MCP server |
| **Single source** | McpPrompt | Exactly one of `template` or `location` MUST be present |
| **Location scheme** | McpPrompt.location | MUST start with `file:///` |
| **Location exists** | McpPrompt.location | The resolved file MUST exist at startup |
| **Placeholder coverage** | McpPrompt | Every `{{arg}}` placeholder in the template SHOULD correspond to a declared argument |

### Cross-Validation with Agent Skills Proposal

When a `skill` adapter derives a tool `from` an `mcp` adapter, only tools are derivable — not resources or prompts. The Agent Skills proposal's `SkillTool.from.action` maps to tool names, not resource names or prompt names.

---

## Implementation Roadmap

### Phase 1: Schema & Spec
- Add `McpResource`, `McpPrompt`, `McpPromptArgument`, `McpPromptMessage` definitions to `capability-schema.json`
- Update `ExposesMcp` with optional `resources` and `prompts` arrays
- Update specification document (README.md) with new object sections

### Phase 2: Spec Classes
- Create `McpServerResourceSpec.java` (parallel to `McpServerToolSpec`)
- Create `McpServerPromptSpec.java`
- Update `McpServerSpec.java` with `List<McpServerResourceSpec> resources` and `List<McpServerPromptSpec> prompts`

### Phase 3: Protocol Handlers
- Add `resources/list`, `resources/read`, `resources/templates/list` to `McpProtocolDispatcher`
- Add `prompts/list`, `prompts/get` to `McpProtocolDispatcher`
- Update `handleInitialize()` to advertise `resources`/`prompts` capabilities conditionally

### Phase 4: Resource Execution
- Create `McpResourceHandler.java` (parallel to `McpToolHandler`)
  - Dynamic: reuse `OperationStepExecutor` (same as tools)
  - Static: file reader with path validation and MIME type detection

### Phase 5: Prompt Rendering
- Create `McpPromptHandler.java`
  - Inline: argument substitution in message templates
  - File-based: file reader + argument substitution

### Phase 6: Testing
- Unit tests for each new spec class (round-trip serialization)
- Integration tests for each resource type (dynamic, static)
- Integration tests for each prompt type (inline, file-based)
- Security tests for path traversal and prompt injection

---

## Backward Compatibility

This proposal is **fully backward compatible**:

1. **`resources` is optional** — existing MCP adapters without resources continue to work unchanged
2. **`prompts` is optional** — existing MCP adapters without prompts continue to work unchanged
3. **`tools` unchanged** — no modifications to `McpTool`, `McpToolInputParameter`, or tool execution
4. **Protocol backward compatible** — existing `tools/list` and `tools/call` methods unchanged; new methods return `-32601` (method not found) only if the client calls them on a server that doesn't support them
5. **`initialize` additive** — capabilities object adds `resources`/`prompts` alongside existing `tools`; clients that don't understand them ignore them

### Consistency with Agent Skills Proposal

| Pattern | Agent Skills Proposal | This Proposal |
|---------|----------------------|---------------|
| `location` URI | `file:///` → directory with supporting files | `file:///` → directory (resources) or file (prompts) |
| File serving | `/contents/{file}` REST endpoint | `resources/read` MCP method |
| Path validation | Regex + containment check | Same regex + containment check |
| Metadata-first | Skills describe, don't execute | Resources describe content source |
| No new execution model | Derived tools use existing adapters | Dynamic resources use existing `call`/`steps` |
