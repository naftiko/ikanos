# Agent Skills Specification Integration Proposal
## Skill Metadata & Catalog Adapter Architecture

**Status**: Current implementation  
**Date**: March 24, 2026  
**Key Concept**: Dedicated `skill` server adapter — skills declare tools derived from sibling `rest` and `mcp` adapters or defined as local file instructions. AI clients invoke adjacent adapters directly for derived tools.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Architecture Overview](#architecture-overview)
3. [Design Analogy](#design-analogy)
4. [Skill Definition](#skill-definition)
5. [Schema Structure](#schema-structure)
6. [Predefined REST Endpoints](#predefined-rest-endpoints)
7. [Visual Architecture](#visual-architecture)
8. [Implementation Examples](#implementation-examples)
9. [Security Considerations](#security-considerations)
10. [Derivation Validation Rules](#derivation-validation-rules)
11. [Implementation Roadmap](#implementation-roadmap)
12. [Backward Compatibility](#backward-compatibility)

---

## Executive Summary

### What This Proposes

Introduce a **dedicated `skill` server adapter** (alongside existing `rest` and `mcp` adapters) enabling Naftiko capabilities to **describe skills and declare supporting tools**:

1. **Describe** skills with full [Agent Skills Spec](https://agentskills.io/specification) frontmatter metadata
2. **Declare tools** — each tool is either derived from a sibling `rest`/`mcp` adapter operation, or defined as a local file instruction
3. **Distribute** skills through predefined GET endpoints for discovery, download, and file browsing
4. **Locate** supporting files (SKILL.md, README, schemas) via a `location` property

Skills can be **purely descriptive** (metadata + supporting files only), declare **derived tools** (from sibling adapters), declare **instruction tools** (from local files), or mix all three. The skill adapter does not execute derived tools — AI clients invoke the sibling REST API or MCP adapters directly.

### Why a Dedicated Adapter?

Just as the `mcp` adapter provides protocol-specific features despite HTTP being technically possible within `rest`, the `skill` adapter provides:

- **Catalog Model**: Describe skills that declare tools from sibling adapters or local instructions, giving agents a unified discovery surface
- **Agent Skills Spec Alignment**: Full frontmatter support (name, description, license, compatibility, allowed-tools, argument-hint, invocation controls)
- **Supporting Files**: `location` property links to SKILL.md and supporting documentation accessible via REST endpoints
- **Focused Responsibility**: Skill metadata concerns separate from tool execution (which stays with `rest` and `mcp` adapters)

### Business Value

| Benefit | Impact | Users |
|---------|--------|-------|
| **Skill Cataloging** | Describe and organize tools from sibling REST/MCP adapters and local instructions into discoverable skills | Developers |
| **Agent Discovery** | Agents discover skill tools with rich metadata, then invoke sibling adapters directly or read instruction files | AI Agents |
| **No Duplication** | Derive tools from existing adapters without redefining tool logic | Architects |
| **Distribution** | Predefined REST endpoints for discovery, download, and file browsing | Organizations |
| **Enterprise Control** | Host skill catalogs internally with auth, metadata governance, and supporting docs | InfoSec Teams |

### Key Design Decisions

1. **Metadata-First**: Skills describe and declare tools — they do not execute them. Tool execution stays with the `rest` and `mcp` adapters that own the tools.

2. **Per-Tool Declaration**: Each skill declares its tools individually via `tools[]`. Each tool specifies its source: `from` (derived from a sibling adapter) or `instruction` (a local file).

3. **Derived Tools**: A tool with `from` references a specific operation (api) or tool (mcp) in a sibling adapter. Each derived tool includes an `invocationRef` so agents know where to invoke the tool directly.

4. **Instruction Tools**: A tool with `instruction` references a file relative to the skill’s `location` directory. The instruction content is served through the `/contents` endpoint.

5. **Purely Descriptive**: A skill can also stand alone with no tools — just metadata + `location` supporting files.

6. **Full Agent Skills Spec Frontmatter**: Every property from the [Agent Skills Spec](https://agentskills.io/specification) YAML frontmatter is declarable on `ExposedSkill` — name, description, license, compatibility, metadata, allowed-tools, argument-hint, user-invocable, disable-model-invocation.

7. **`location` for Supporting Files**: A `file:///` URI pointing to a directory containing SKILL.md and supporting files/folders, served through the `/contents` and `/download` endpoints.

8. **No Recursive Derivation**: Only sibling `rest` or `mcp` adapters can be `from` sources — no derivation from other skill adapters.

### Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| **Path traversal attacks** | Medium | High | Strict path validation regex, character whitelist |
| **Location URI validation** | Medium | Medium | Only `file:///` scheme; validate resolved path stays within allowed directories |
| **Schema complexity** | Low | Low | ExposedSkill adds tools[] with clear from/instruction sources — no new execution paradigms |
| **Performance (ZIP generation)** | Low | Medium | Streaming, size limits, caching |

**Overall Risk**: **LOW** — Purely additive metadata layer; no execution complexity

---

## Architecture Overview

### Current State: Existing Server Adapters in Naftiko

| Adapter | Purpose | Responsibility |
|---------|---------|-----------------|
| **`rest`** | REST API Server | HTTP endpoints, resource operations, tool execution via REST |
| **`mcp`** | MCP Protocol Server | MCP tools, stdio/HTTP transport, tool execution via MCP protocol |
| **`skill`** (NEW) | Skill Catalog & Distribution | Agent skill metadata, tools (derived + instruction), supporting files |

Each server adapter has:
- Distinct `type` field in `exposes` blocks
- Focused endpoint responsibility
- Clear separation of concerns

### Proposed Architecture

The `skill` adapter is a **metadata and catalog layer** — it describes skills that declare tools from sibling `rest` and `mcp` adapters or from local file instructions:

```yaml
capability:
  consumes:
    # Consumed HTTP APIs (backing operations)
    - type: "http"
      namespace: "weather-api"
      baseUri: "https://api.weather.com/v1"
      resources:
        - name: "forecast"
          path: "forecast/{{location}}"
          inputParameters:
            - name: "location"
              in: "path"
          operations:
            - method: "GET"
              name: "get-forecast"
              outputParameters:
                - name: "forecast"
                  type: "object"
                  value: "$.forecast"

    - type: "http"
      namespace: "geocoding-api"
      baseUri: "https://geocode.example.com"
      resources:
        - name: "resolve"
          path: "resolve/{{query}}"
          inputParameters:
            - name: "query"
              in: "path"
          operations:
            - method: "GET"
              name: "resolve-location"
              outputParameters:
                - name: "coordinates"
                  type: "object"
                  value: "$.coordinates"
                  properties:
                    lat:
                      type: "number"
                      value: "$.lat"
                    lon:
                      type: "number"
                      value: "$.lon"

  exposes:
    # REST adapter — owns tool execution via REST
    - type: "rest"
      address: "0.0.0.0"
      port: 9090
      namespace: "weather-rest"
      resources:
        - path: "/forecast/{{city}}"
          operations:
            - method: "GET"
              name: "get-forecast"
              inputParameters:
                - name: "city"
                  in: "path"
                  type: "string"
                  description: "City name (e.g. 'London', 'New York')"
              call: "weather-api.get-forecast"
              with:
                location: "{{city}}"
              outputParameters:
                - name: "forecast"
                  type: "object"
                  mapping: "$.forecast"

    # MCP adapter — owns tool execution via MCP protocol
    - type: "mcp"
      transport: "http"
      address: "0.0.0.0"
      port: 9091
      namespace: "weather-mcp"
      tools:
        - name: "resolve-and-forecast"
          description: "Resolve a place name to coordinates, then fetch forecast"
          inputParameters:
            - name: "place"
              type: "string"
              description: "Place name to resolve"
          steps:
            - type: "call"
              name: "geo"
              call: "geocoding-api.resolve-location"
              with:
                query: "{{place}}"
            - type: "call"
              name: "weather"
              call: "weather-api.get-forecast"
              with:
                location: "{{geo.coordinates.lat}},{{geo.coordinates.lon}}"
          mappings:
            - targetName: "location"
              value: "$.geo.coordinates"
            - targetName: "forecast"
              value: "$.weather.forecast"
          outputParameters:
            - name: "location"
              type: "object"
            - name: "forecast"
              type: "object"

    # Skill adapter — metadata/catalog layer (no execution)
    - type: "skill"
      address: "0.0.0.0"
      port: 8080
      namespace: "weather-skills"

      skills:
        - name: "weather-forecast"
          description: "Look up weather forecasts by location name or coordinates"
          license: "MIT"
          compatibility: "Requires network access to weather and geocoding APIs"
          argument-hint: "Describe the location you want a forecast for"
          location: "file:///etc/naftiko/skills/weather-forecast"
          metadata:
            author: "weather-team"
            category: "weather"

          tools:
            - name: "get-forecast"
              description: "Get weather forecast for a city"
              from:
                sourceNamespace: "weather-rest"     # Sibling REST adapter
                action: "get-forecast"              # Operation name
            - name: "resolve-and-forecast"
              description: "Resolve a place name to coordinates, then fetch forecast"
              from:
                sourceNamespace: "weather-mcp"      # Sibling MCP adapter
                action: "resolve-and-forecast"      # Tool name
            - name: "interpret-weather"
              description: "Guide for reading and interpreting weather data"
              instruction: "interpret-weather.md"   # Local file in location dir
```

**How agents use this:**
1. Agent calls `GET /skills/weather-forecast` → receives tool catalog
2. Agent sees `get-forecast` (derived) with `invocationRef: { targetNamespace: "weather-rest", mode: "rest" }` → invokes `GET http://host:9090/forecast/London`
3. Agent sees `resolve-and-forecast` (derived) with `invocationRef: { targetNamespace: "weather-mcp", mode: "mcp" }` → invokes via MCP protocol on port 9091
4. Agent sees `interpret-weather` (instruction) → reads instruction content via `GET /skills/weather-forecast/contents/interpret-weather.md`

---

## Design Analogy

```
REST Adapter                   MCP Adapter                   SKILL Adapter
─────────────                 ─────────────                 ──────────────
ExposesApi                    ExposesMcp                    ExposesSkill
├─ resources[]                ├─ tools[]                    ├─ skills[]
│  ├─ path                    │  ├─ name                    │  ├─ name
│  ├─ inputParameters[]       │  ├─ description             │  ├─ description
│  └─ operations[]            │  ├─ inputParameters[]       │  ├─ frontmatter metadata
│     ├─ method               │  ├─ call / steps            │  ├─ location
│     ├─ call / steps         │  ├─ with                    │  └─ tools[]
│     ├─ with                 │  └─ outputParameters[]      │     ├─ name
│     ├─ inputParameters[]    │                             │     ├─ description
│     └─ outputParameters[]   │                             │     ├─ from { ns, action }
                              │                             │     └─ instruction
```

| Adapter | First-class construct | Actionable units | Execution |
|---------|----------------------|-----------------|-----------|
| **`rest`** | Resources | Operations | HTTP endpoints (call/steps/with) |
| **`mcp`** | (flat) | Tools | MCP protocol (call/steps/with) |
| **`skill`** | Skills | Tools (derived + instruction) | **None** — agents invoke sibling adapters or read instruction files |

---

## Skill Definition

Skills provide rich metadata and a unified discovery surface. Each skill can declare one or more **tools**, where each tool is either **derived** from a sibling `rest` or `mcp` adapter, or defined as a local **instruction** file. Skills can also stand alone as purely descriptive (no tools).

### Declaring Tools

Each skill declares tools individually via `tools[]`. Each tool has a `name`, `description`, and exactly one source:

- **`from`** — derives the tool from a sibling adapter operation (api) or tool (mcp)
- **`instruction`** — references a local file relative to the skill's `location` directory

```yaml
skills:
  - name: "order-management"
    description: "Manage orders through the public API"
    license: "Apache-2.0"
    argument-hint: "Describe the order operation you need"
    location: "file:///etc/naftiko/skills/order-management"

    tools:
      # Derived from sibling REST adapter
      - name: "list-orders"
        description: "List all customer orders"
        from:
          sourceNamespace: "public-api"
          action: "list-orders"
      - name: "create-order"
        description: "Create a new customer order"
        from:
          sourceNamespace: "public-api"
          action: "create-order"
      # Derived from sibling MCP adapter
      - name: "summarize-order"
        description: "Generate an AI summary of an order"
        from:
          sourceNamespace: "assistant-mcp"
          action: "summarize-order"
      # Local file instruction
      - name: "order-policies"
        description: "Order processing policies and business rules"
        instruction: "instructions/order-policies.md"
```

### Derived Tools (`from`)

A tool with `from` references a specific operation or tool in a sibling adapter:

**Tool declaration rules:**
1. `from.sourceNamespace` must resolve to a sibling `exposes[]` entry of type `rest` or `mcp`
2. `from.action` must match an operation name (api) or tool name (mcp) in the resolved adapter
3. Adapter type is inferred from the resolved target
4. Each derived tool includes an `invocationRef` in the response so agents can invoke the source adapter directly

**Derived tool response (returned by `GET /skills/{name}`):**
```json
{
  "name": "list-orders",
  "description": "List all customer orders",
  "type": "derived",
  "invocationRef": {
    "targetNamespace": "public-api",
    "action": "list-orders",
    "mode": "rest"
  },
  "inputSchema": {
    "type": "object",
    "properties": {},
    "description": "Copied from source operation input parameters"
  }
}
```

### Instruction Tools (`instruction`)

A tool with `instruction` references a file relative to the skill's `location` directory. The skill must have a `location` configured:

```yaml
skills:
  - name: "coding-guidelines"
    description: "Company coding guidelines for AI agents"
    location: "file:///etc/naftiko/skills/coding-guidelines"

    tools:
      - name: "naming-conventions"
        description: "Naming conventions for variables, functions, and classes"
        instruction: "naming-conventions.md"
      - name: "error-handling"
        description: "Error handling patterns and best practices"
        instruction: "error-handling.md"
      - name: "testing-strategy"
        description: "Unit and integration testing guidelines"
        instruction: "instructions/testing-strategy.md"
```

**Instruction tool response (returned by `GET /skills/{name}`):**
```json
{
  "name": "naming-conventions",
  "description": "Naming conventions for variables, functions, and classes",
  "type": "instruction",
  "instruction": "naming-conventions.md"
}
```

Agents read instruction content via `GET /skills/{name}/contents/{file}`.

### Mixed Tools (Derived + Instruction)

A single skill can mix derived and instruction tools:

```yaml
skills:
  - name: "data-intelligence"
    description: "Unified data tools from REST and MCP adapters plus local instructions"
    allowed-tools: "run-analysis quick-stats interpret-data"
    location: "file:///etc/naftiko/skills/data-intelligence"

    tools:
      - name: "run-analysis"
        description: "Run a full data analysis"
        from:
          sourceNamespace: "analytics-rest"
          action: "run-analysis"
      - name: "quick-stats"
        description: "Run quick statistical analysis"
        from:
          sourceNamespace: "analytics-mcp"
          action: "quick-stats"
      - name: "interpret-data"
        description: "Guide for interpreting analysis results"
        instruction: "instructions/interpret-data.md"
```

### Purely Descriptive Skills

A skill with no tools — just metadata and supporting files:

```yaml
skills:
  - name: "onboarding-guide"
    description: "New developer onboarding guide"
    license: "proprietary"
    location: "file:///etc/naftiko/skills/onboarding"
    metadata:
      author: "platform-team"
      category: "onboarding"
```

The `location` directory contains the SKILL.md and any supporting files. These are served through the `/contents` and `/download` endpoints for distribution.

### Agent Skills Spec Frontmatter Properties

Every `ExposedSkill` supports the full [Agent Skills Spec](https://agentskills.io/specification) YAML frontmatter:

| Property | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `name` | string | **Yes** | — | 1–64 chars, kebab-case. Skill identifier. |
| `description` | string | **Yes** | — | Max 1024 chars. What the skill does and when to use it. Used for agent discovery. |
| `license` | string | No | — | License identifier (e.g., "MIT", "Apache-2.0") |
| `compatibility` | string | No | — | Max 500 chars. Compatibility notes and requirements. |
| `metadata` | object | No | — | Arbitrary key-value pairs (author, category, tags, ecosystem, etc.) |
| `allowed-tools` | string | No | — | Space-delimited list of pre-approved tool names |
| `argument-hint` | string | No | — | Hint text shown when agents invoke via slash command |
| `user-invocable` | boolean | No | `true` | Whether agents can invoke this skill as a slash command |
| `disable-model-invocation` | boolean | No | `false` | Whether to prevent auto-loading this skill based on context |

### The `location` Property

The `location` property provides a `file:///` URI pointing to a directory containing SKILL.md and supporting files. The skill server serves these files through the `/contents` and `/download` endpoints:

```yaml
skills:
  - name: "weather-forecast"
    description: "Weather forecasting tools"
    location: "file:///etc/naftiko/skills/weather-forecast"
    tools:
      - name: "get-forecast"
        description: "Get weather forecast"
        from:
          sourceNamespace: "weather-rest"
          action: "get-forecast"
      - name: "interpret-weather"
        description: "Guide for interpreting weather data"
        instruction: "interpret-weather.md"
```

**Expected directory structure at the location:**
```
/etc/naftiko/skills/weather-forecast/
├── SKILL.md              # Skill documentation with frontmatter
├── README.md             # Additional documentation
├── examples/
│   ├── basic-usage.md
│   └── advanced.md
└── schemas/
    └── weather-response.json
```

The SKILL.md file at the location can contain the same frontmatter properties as declared on the `ExposedSkill`. The capability YAML declaration is the source of truth; the SKILL.md frontmatter is informational for file-based consumers.

---

## Schema Structure

### ExposesSkills (New Type)

```json
{
  "type": "object",
  "description": "Skill server adapter — skills declare tools derived from sibling adapters or as local file instructions. Metadata and catalog layer peer to api and mcp.",
  "properties": {
    "type": {
      "const": "skill",
      "description": "Fixed value for skill server adapter"
    },
    "address": {
      "$ref": "#/$defs/Address",
      "description": "Listen address (0.0.0.0, localhost, hostname, etc.)"
    },
    "port": {
      "type": "integer",
      "minimum": 1,
      "maximum": 65535,
      "description": "Listen port"
    },
    "namespace": {
      "$ref": "#/$defs/IdentifierKebab",
      "description": "Unique identifier for this skill server"
    },
    "description": {
      "type": "string",
      "description": "Description of this skill server's purpose"
    },
    "authentication": {
      "$ref": "#/$defs/Authentication",
      "description": "Optional authentication for the skill server"
    },
    "skills": {
      "type": "array",
      "description": "Array of skill definitions. Each skill declares tools (derived from sibling adapters or local file instructions), or stands alone as purely descriptive.",
      "items": {
        "$ref": "#/$defs/ExposedSkill"
      },
      "minItems": 1
    }
  },
  "required": ["type", "port", "namespace", "skills"],
  "additionalProperties": false
}
```

### ExposedSkill (New Type)

```json
{
  "type": "object",
  "description": "A skill definition. Declares tools derived from sibling rest or mcp adapters or defined as local file instructions. Can also stand alone as purely descriptive (no tools). Supports full Agent Skills Spec frontmatter metadata. Skills describe tools — they do not execute them.",
  "properties": {
    "name": {
      "$ref": "#/$defs/IdentifierKebab",
      "description": "Skill identifier (kebab-case)"
    },
    "description": {
      "type": "string",
      "maxLength": 1024,
      "description": "What the skill does and when to use it. Used for agent discovery."
    },
    "license": {
      "type": "string",
      "description": "License identifier (e.g., MIT, Apache-2.0)"
    },
    "compatibility": {
      "type": "string",
      "maxLength": 500,
      "description": "Compatibility notes and requirements"
    },
    "metadata": {
      "type": "object",
      "additionalProperties": { "type": "string" },
      "description": "Arbitrary key-value metadata (author, category, tags, ecosystem, etc.)"
    },
    "allowed-tools": {
      "type": "string",
      "maxLength": 1024,
      "description": "Space-delimited list of pre-approved tool names (Agent Skills Spec)"
    },
    "argument-hint": {
      "type": "string",
      "description": "Hint text shown when agents invoke this skill via slash command (Agent Skills Spec)"
    },
    "user-invocable": {
      "type": "boolean",
      "default": true,
      "description": "Whether agents can invoke this skill as a slash command (Agent Skills Spec)"
    },
    "disable-model-invocation": {
      "type": "boolean",
      "default": false,
      "description": "Whether to prevent auto-loading this skill based on context (Agent Skills Spec)"
    },
    "location": {
      "type": "string",
      "format": "uri",
      "pattern": "^file:///",
      "description": "file:/// URI to a directory containing SKILL.md and supporting files"
    },
    "tools": {
      "type": "array",
      "description": "Tools provided by this skill. Each tool is derived from a sibling adapter or defined as a local file instruction.",
      "items": { "$ref": "#/$defs/SkillTool" },
      "minItems": 1
    }
  },
  "required": ["name", "description"],
  "additionalProperties": false
}
```

### SkillTool (New Type)

```json
{
  "type": "object",
  "description": "A tool declared within a skill. Derived from a sibling rest or mcp adapter via 'from', or defined as a local file instruction.",
  "properties": {
    "name": {
      "$ref": "#/$defs/IdentifierKebab",
      "description": "Tool identifier (kebab-case)"
    },
    "description": {
      "type": "string",
      "description": "What the tool does. Used for agent discovery."
    },
    "from": {
      "type": "object",
      "description": "Derive this tool from a sibling rest or mcp adapter.",
      "properties": {
        "sourceNamespace": {
          "type": "string",
          "description": "Sibling exposes[].namespace (must be type rest or mcp)"
        },
        "action": {
          "type": "string",
          "description": "Operation name (api) or tool name (mcp) in the source adapter"
        }
      },
      "required": ["sourceNamespace", "action"],
      "additionalProperties": false
    },
    "instruction": {
      "type": "string",
      "description": "File path relative to the skill's location directory containing the tool instruction"
    }
  },
  "required": ["name", "description"],
  "oneOf": [
    { "required": ["from"] },
    { "required": ["instruction"] }
  ],
  "additionalProperties": false
}
```

---

## Predefined REST Endpoints

The `skill` server adapter automatically provides these **predefined** GET-only endpoints for discovery and distribution:

### Endpoint Summary

| HTTP | Path | Purpose |
|------|------|---------|
| GET | `/skills` | List all skills with tool summaries |
| GET | `/skills/{name}` | Skill metadata + tool catalog (derived with invocation refs, instruction with file paths) |
| GET | `/skills/{name}/download` | Skill package (ZIP) from `location` |
| GET | `/skills/{name}/contents` | File listing from `location` |
| GET | `/skills/{name}/contents/{file}` | Individual file from `location` |

### 1. List All Skills

```
GET /skills
Response: application/json
{
  "count": 2,
  "skills": [
    {
      "name": "weather-forecast",
      "description": "Look up weather forecasts by location name or coordinates",
      "license": "MIT",
      "tools": ["get-forecast", "resolve-and-forecast"]
    },
    {
      "name": "order-management",
      "description": "Manage orders through the public API",
      "tools": ["list-orders", "create-order", "cancel-order"]
    }
  ]
}
```

### 2. Get Skill Metadata (including tool catalog)

```
GET /skills/{name}
Response: application/json
{
  "name": "weather-forecast",
  "description": "Look up weather forecasts by location name or coordinates",
  "license": "MIT",
  "compatibility": "Requires network access to weather and geocoding APIs",
  "argument-hint": "Describe the location you want a forecast for",
  "metadata": {
    "author": "weather-team",
    "category": "weather"
  },
  "tools": [
    {
      "name": "get-forecast",
      "description": "Get weather forecast for a city",
      "type": "derived",
      "invocationRef": {
        "targetNamespace": "weather-rest",
        "action": "get-forecast",
        "mode": "rest"
      },
      "inputSchema": {
        "type": "object",
        "properties": {
          "city": { "type": "string", "description": "City name" }
        },
        "required": ["city"]
      }
    },
    {
      "name": "resolve-and-forecast",
      "description": "Resolve a place name to coordinates, then fetch forecast",
      "type": "derived",
      "invocationRef": {
        "targetNamespace": "weather-mcp",
        "action": "resolve-and-forecast",
        "mode": "mcp"
      },
      "inputSchema": {
        "type": "object",
        "properties": {
          "place": { "type": "string", "description": "Place name to resolve" }
        },
        "required": ["place"]
      }
    },
    {
      "name": "interpret-weather",
      "description": "Guide for reading and interpreting weather data",
      "type": "instruction",
      "instruction": "interpret-weather.md"
    }
  ]
}
```

For **derived tools**, agents use `invocationRef` to invoke the tool directly through the source adapter. For **instruction tools**, agents read the instruction content via `GET /skills/{name}/contents/{file}`. The skill server does not proxy or execute tools.

### 3–5. Download and File Browsing

Files served from the skill's `location` directory:

```
GET /skills/{name}/download
→ ZIP archive of the location directory

GET /skills/{name}/contents
→ { "name": "weather-forecast", "files": [
    { "path": "SKILL.md", "size": 2048, "type": "text/markdown" },
    { "path": "README.md", "size": 1024, "type": "text/markdown" },
    { "path": "examples/basic-usage.md", "size": 512, "type": "text/markdown" }
  ]}

GET /skills/{name}/contents/{file}
→ File content (MIME type based on extension)
```

If no `location` is configured, the download and contents endpoints return 404.

---

## Visual Architecture

### High-Level System Architecture

```
+-------------------------------------------------------------------+
|                     NAFTIKO CAPABILITY                             |
+-------------------------------------------------------------------+
|                                                                   |
|  CONSUMES (Backing HTTP APIs)                                    |
|  +-----------------------------------------------------------+  |
|  |  ConsumesHttp                                              |  |
|  |  -- namespace: "weather-api"                               |  |
|  |  -- baseUri: "https://..."                                 |  |
|  |  -- resources/operations                                   |  |
|  +-----------------------------------------------------------+  |
|                                                                   |
|  EXPOSES                                                         |
|  +---------------------------+  +---------------------------+    |
|  | ExposesApi                |  | ExposesMcp                |    |
|  | type: "rest"               |  | type: "mcp"               |    |
|  | port: 9090                |  | port: 9091                |    |
|  | namespace: "weather-rest" |  | namespace: "weather-mcp"  |    |
|  | resources / operations    |  | tools (call/steps/with)   |    |
|  | (EXECUTES tools)          |  | (EXECUTES tools)          |    |
|  +---------------------------+  +---------------------------+    |
|        ^                              ^                          |
|        |  tools[].from                |  tools[].from            |
|        |                              |                          |
|  +-----------------------------------------------------------+  |
|  | ExposesSkill              (DESCRIBES tools)                |  |
|  | type: "skill"                                              |  |
|  | port: 8080                                                 |  |
|  | namespace: "weather-skills"                                |  |
|  | skills: [ tools: derived + instruction ]                   |  |
|  | location: "file:///..."                                    |  |
|  +-----------------------------------------------------------+  |
|                                                                   |
+-------------------------------------------------------------------+
                           |
               Predefined GET Endpoints
               (discovery + distribution only)
```

### Three Adapter Types Comparison

```
+---------------------------+  +---------------------------+  +---------------------------+
|     REST Adapter           |  |     MCP Adapter           |  |     SKILL Adapter         |
|     (EXECUTES)            |  |     (EXECUTES)            |  |     (DESCRIBES)           |
+---------------------------+  +---------------------------+  +---------------------------+
| ExposesApi                |  | ExposesMcp                |  | ExposesSkill              |
| +- resources[]            |  | +- tools[]                |  | +- skills[]               |
| |  +- path                |  | |  +- name                |  | |  +- name                |
| |  +- operations[]        |  | |  +- description         |  | |  +- description         |
| |     +- method           |  | |  +- inputParameters[]   |  | |  +- frontmatter props   |
| |     +- call / steps     |  | |  +- call / steps        |  | |  +- location            |
| |     +- with             |  | |  +- with                |  | |  +- tools[]             |
| |     +- inputParams[]    |  | |  +- outputParameters[]  |  | |     +- name             |
| |     +- outputParams[]   |  | |                         |  | |     +- description      |
|                           |  |                           |  | |     +- from {ns, action}|
| Execution: HTTP endpoints |  | Execution: MCP protocol  |  | |     +- instruction      |
+---------------------------+  +---------------------------+  |                           |
                                                              | (agents invoke adjacent   |
                                                              |  adapters directly)       |
                                                              +---------------------------+
```

### Discovery & Invocation Flow

```
AI Agent / Client
     |
     |  1. GET /skills/weather-forecast
     |     (discover tools — derived + instruction)
     |
     v
+-------------------------------------------+
| Skill Server (port 8080)                  |
| Returns tool catalog:                     |
|  - get-forecast          (derived)        |
|    invocationRef: weather-rest (api)      |
|  - resolve-and-forecast  (derived)        |
|    invocationRef: weather-mcp (mcp)       |
|  - interpret-weather     (instruction)    |
|    instruction: interpret-weather.md      |
+-------------------------------------------+
     |
     |  2. Agent reads catalog, decides which tool to use
     |
     v
+-------------------------------------------+
| Agent invokes derived tools DIRECTLY      |
| through the source adapter.               |
| Agent reads instruction tools via         |
| GET /skills/{name}/contents/{file}        |
+-------------------------------------------+
     |                               |
     |  API mode:                    |  MCP mode:
     |  GET http://host:9090/        |  MCP call to host:9091
     |    forecast/London            |    tool: resolve-and-forecast
     |                               |    args: { place: "London" }
     v                               v
+---------------------+    +---------------------+
| REST Adapter (9090)  |    | MCP Adapter (9091)  |
| Executes via        |    | Executes via        |
| call/steps/with     |    | call/steps/with     |
+---------------------+    +---------------------+
     |                               |
     v                               v
+-------------------------------------------+
| Consumed HTTP APIs                        |
| (weather-api, geocoding-api)              |
+-------------------------------------------+
```

### Tool Resolution Flow

```
+---------------------------+    +---------------------------+
| Sibling: ExposesApi       |    | Sibling: ExposesMcp      |
| namespace: "public-api"   |    | namespace: "mcp-tools"   |
|                           |    |                           |
| resources:                |    | tools:                    |
|   /orders                 |    |   - summarize-order       |
|     GET  list-orders      |    |   - format-report         |
|     POST create-order     |    |                           |
|   /orders/{{id}}          |    |                           |
|     DELETE cancel-order   |    |                           |
+---------------------------+    +---------------------------+
            ^                                ^
            |                                |
            |   tools[].from                 |   tools[].from
            |   ns: "public-api"             |   ns: "mcp-tools"
            |                                |
+---------------------------------------------------+
| ExposesSkill                                      |
| namespace: "order-skills"                         |
|                                                   |
| skills:                                           |
|   - name: "order-management"                      |
|     description: "Manage customer orders"         |
|     license: "Apache-2.0"                         |
|     location: "file:///etc/naftiko/skills/orders"  |
|                                                   |
|     tools:                                        |
|       - name: "list-orders"       (derived)       |
|         from: { ns: public-api }                  |
|       - name: "create-order"      (derived)       |
|         from: { ns: public-api }                  |
|       - name: "summarize-order"   (derived)       |
|         from: { ns: mcp-tools }                   |
|       - name: "order-guidelines"  (instruction)   |
|         instruction: order-guidelines.md          |
+---------------------------------------------------+
+---------------------------------------------------+
```

### Endpoint Structure

```
                     Skill Server (port 8080)
                     GET-only endpoints
                            |
        +-------------------+-------------------+
        |                                       |
    +------------------------------------------------+
    |           GET /skills                          |
    |  Returns: All skills with tool summaries       |
    |  {                                             |
    |    "count": 2,                                 |
    |    "skills": [                                 |
    |      { name, description, tools: [...] },     |
    |      ...                                      |
    |    ]                                           |
    |  }                                             |
    +------------------------------------------------+
        |                                       |
        +-- /weather-forecast                   +-- /order-management
        |   +- metadata + tool catalog          |   +- metadata + tool catalog
        |   +- download (from location)         |   +- download (from location)
        |   +- contents (from location)         |   +- contents (from location)
        |                                       |
        |  All tool invocations go DIRECTLY     |
        |  to the source adapter, NOT through   |
        |  the skill server.                    |
```

---

## Implementation Examples

### Example 1: Weather Intelligence Skills

**Scenario**: Catalog weather tools from REST API and MCP adapters as a discoverable skill.

```yaml
naftiko: "0.5"

info:
  label: "Weather Intelligence Skills"
  description: "Skills for weather forecasting — tools executed via adjacent adapters"

binds:
  - namespace: "api-keys"
    description: "Runtime variables for weather API authentication."
    keys:
      WEATHER_API_KEY: "WEATHER_API_KEY"

capability:
  consumes:
    - type: "http"
      namespace: "weather-api"
      baseUri: "https://api.weather.com/v1"
      authentication:
        type: "apikey"
        key: "X-API-Key"
        value: "{{WEATHER_API_KEY}}"
        placement: "header"
      resources:
        - path: "forecast/{{location}}"
          name: "forecast"
          inputParameters:
            - name: "location"
              in: "path"
          operations:
            - method: "GET"
              name: "get-forecast"
              outputParameters:
                - name: "forecast"
                  type: "object"
                  value: "$.forecast"

    - type: "http"
      namespace: "geocoding-api"
      baseUri: "https://geocode.example.com"
      resources:
        - path: "search/{{query}}"
          name: "search"
          inputParameters:
            - name: "query"
              in: "path"
          operations:
            - method: "GET"
              name: "resolve-location"
              outputParameters:
                - name: "coordinates"
                  type: "object"
                  value: "$.coordinates"
                  properties:
                    lat:
                      type: "number"
                      value: "$.lat"
                    lon:
                      type: "number"
                      value: "$.lon"

  exposes:
    # REST adapter — executes the forecast tool via REST
    - type: "rest"
      address: "0.0.0.0"
      port: 9090
      namespace: "weather-rest"
      resources:
        - path: "/forecast/{{city}}"
          operations:
            - method: "GET"
              name: "get-forecast"
              inputParameters:
                - name: "city"
                  in: "path"
                  type: "string"
                  description: "City name (e.g. 'London', 'New York')"
              call: "weather-api.get-forecast"
              with:
                location: "{{city}}"
              outputParameters:
                - name: "forecast"
                  type: "object"
                  mapping: "$.forecast"

    # MCP adapter — executes multi-step tools via MCP protocol
    - type: "mcp"
      transport: "http"
      address: "0.0.0.0"
      port: 9091
      namespace: "weather-mcp"
      tools:
        - name: "resolve-and-forecast"
          description: "Resolve a place name to coordinates, then fetch forecast"
          steps:
            - type: "call"
              name: "geo"
              call: "geocoding-api.resolve-location"
              with:
                query: "{{place}}"
            - type: "call"
              name: "weather"
              call: "weather-api.get-forecast"
              with:
                location: "{{geo.coordinates.lat}},{{geo.coordinates.lon}}"
          inputParameters:
            - name: "place"
              type: "string"
              description: "Place name to resolve (e.g. 'Eiffel Tower')"
          mappings:
            - targetName: "location"
              value: "$.geo.coordinates"
            - targetName: "forecast"
              value: "$.weather.forecast"
          outputParameters:
            - name: "location"
              type: "object"
            - name: "forecast"
              type: "object"

    # Skill adapter — catalogs tools from both adapters above
    - type: "skill"
      address: "0.0.0.0"
      port: 8080
      namespace: "weather-skills"
      description: "Weather intelligence skills for AI agents"

      skills:
        - name: "weather-forecast"
          description: "Look up weather forecasts by location name or coordinates"
          license: "MIT"
          compatibility: "Requires network access to weather and geocoding APIs"
          argument-hint: "Describe the location you want a forecast for"
          location: "file:///etc/naftiko/skills/weather-forecast"
          metadata:
            author: "weather-team"
            category: "weather"

          tools:
            - name: "get-forecast"
              description: "Get weather forecast for a city"
              from:
                sourceNamespace: "weather-rest"
                action: "get-forecast"
            - name: "resolve-and-forecast"
              description: "Resolve a place name to coordinates, then fetch forecast"
              from:
                sourceNamespace: "weather-mcp"
                action: "resolve-and-forecast"
            - name: "interpret-weather"
              description: "Guide for reading and interpreting weather data"
              instruction: "interpret-weather.md"
```

#### Test Commands
```bash
# List all skills
curl http://localhost:8080/skills | jq '.'

# Get skill metadata with tool catalog
curl http://localhost:8080/skills/weather-forecast | jq '.'

# Browse supporting files (served from location)
curl http://localhost:8080/skills/weather-forecast/contents | jq '.'

# Read instruction tool content
curl http://localhost:8080/skills/weather-forecast/contents/interpret-weather.md

# Agent invokes derived tools DIRECTLY through source adapter:
# Via REST REST adapter:
curl http://localhost:9090/forecast/London | jq '.'

# Via MCP adapter (using MCP protocol, not curl):
# mcp call weather-mcp resolve-and-forecast '{"place": "Eiffel Tower"}'
```

#### Expected Responses

**GET /skills:**
```json
{
  "count": 1,
  "skills": [
    {
      "name": "weather-forecast",
      "description": "Look up weather forecasts by location name or coordinates",
      "license": "MIT",
      "tools": ["get-forecast", "resolve-and-forecast", "interpret-weather"]
    }
  ]
}
```

**GET /skills/weather-forecast:**
```json
{
  "name": "weather-forecast",
  "description": "Look up weather forecasts by location name or coordinates",
  "license": "MIT",
  "compatibility": "Requires network access to weather and geocoding APIs",
  "argument-hint": "Describe the location you want a forecast for",
  "metadata": {
    "author": "weather-team",
    "category": "weather"
  },
  "tools": [
    {
      "name": "get-forecast",
      "description": "Get weather forecast for a city",
      "type": "derived",
      "invocationRef": {
        "targetNamespace": "weather-rest",
        "action": "get-forecast",
        "mode": "rest"
      },
      "inputSchema": {
        "type": "object",
        "properties": {
          "city": { "type": "string", "description": "City name (e.g. 'London', 'New York')" }
        },
        "required": ["city"]
      }
    },
    {
      "name": "resolve-and-forecast",
      "description": "Resolve a place name to coordinates, then fetch forecast",
      "type": "derived",
      "invocationRef": {
        "targetNamespace": "weather-mcp",
        "action": "resolve-and-forecast",
        "mode": "mcp"
      },
      "inputSchema": {
        "type": "object",
        "properties": {
          "place": { "type": "string", "description": "Place name to resolve (e.g. 'Eiffel Tower')" }
        },
        "required": ["place"]
      }
    },
    {
      "name": "interpret-weather",
      "description": "Guide for reading and interpreting weather data",
      "type": "instruction",
      "instruction": "interpret-weather.md"
    }
  ]
}
```

---

### Example 2: Order Management — Tools from REST Adapter

**Scenario**: Catalog existing API operations as discoverable skill tools.

```yaml
naftiko: "0.5"

info:
  label: "Order Management Platform"
  description: "Skills derived from existing REST adapters"

capability:
  consumes:
    - type: "http"
      namespace: "orders-backend"
      baseUri: "https://api.company.com/v2"
      resources:
        - path: "orders"
          name: "orders"
          operations:
            - method: "GET"
              name: "list-orders"
              outputParameters:
                - name: "orders"
                  type: "array"
                  value: "$.orders"
            - method: "POST"
              name: "create-order"
              outputParameters:
                - name: "order"
                  type: "object"
                  value: "$.order"
        - path: "orders/{{id}}"
          name: "order"
          operations:
            - method: "GET"
              name: "get-order"
              outputParameters:
                - name: "order"
                  type: "object"
                  value: "$.order"
            - method: "DELETE"
              name: "cancel-order"

  exposes:
    # Sibling REST adapter — executes tools
    - type: "rest"
      address: "0.0.0.0"
      port: 9090
      namespace: "public-api"
      resources:
        - path: "/orders"
          operations:
            - method: "GET"
              name: "list-orders"
              call: "orders-backend.list-orders"
            - method: "POST"
              name: "create-order"
              call: "orders-backend.create-order"
        - path: "/orders/{{id}}"
          operations:
            - method: "GET"
              name: "get-order"
              call: "orders-backend.get-order"
            - method: "DELETE"
              name: "cancel-order"
              call: "orders-backend.cancel-order"

    # Skill adapter — catalogs tools from REST adapter
    - type: "skill"
      address: "0.0.0.0"
      port: 8080
      namespace: "order-skills"
      description: "Order management skills"

      skills:
        - name: "order-management"
          description: "Manage customer orders through the API"
          license: "Apache-2.0"
          location: "file:///etc/naftiko/skills/order-management"
          tools:
            - name: "list-orders"
              description: "List all customer orders"
              from:
                sourceNamespace: "public-api"
                action: "list-orders"
            - name: "get-order"
              description: "Get details of a specific order"
              from:
                sourceNamespace: "public-api"
                action: "get-order"
            - name: "create-order"
              description: "Create a new customer order"
              from:
                sourceNamespace: "public-api"
                action: "create-order"

        - name: "order-admin"
          description: "Administrative order operations"
          user-invocable: false
          tools:
            - name: "cancel-order"
              description: "Cancel an existing order"
              from:
                sourceNamespace: "public-api"
                action: "cancel-order"
```

#### Expected GET /skills/order-management Response
```json
{
  "name": "order-management",
  "description": "Manage customer orders through the API",
  "license": "Apache-2.0",
  "tools": [
    {
      "name": "list-orders",
      "description": "List all customer orders",
      "type": "derived",
      "invocationRef": {
        "targetNamespace": "public-api",
        "action": "list-orders",
        "mode": "rest"
      }
    },
    {
      "name": "get-order",
      "description": "Get details of a specific order",
      "type": "derived",
      "invocationRef": {
        "targetNamespace": "public-api",
        "action": "get-order",
        "mode": "rest"
      }
    },
    {
      "name": "create-order",
      "description": "Create a new customer order",
      "type": "derived",
      "invocationRef": {
        "targetNamespace": "public-api",
        "action": "create-order",
        "mode": "rest"
      }
    }
  ]
}
```

---

### Example 3: Multi-Adapter Tools (API + MCP + Instruction)

**Scenario**: Declare tools from both REST API and MCP adapters plus a local instruction in one skill.

```yaml
naftiko: "0.5"

info:
  label: "Data Intelligence Platform"
  description: "Skills with tools from REST and MCP adapters plus instructions"

capability:
  consumes:
    - type: "http"
      namespace: "analytics-api"
      baseUri: "https://analytics.example.com"
      resources:
        - path: "analyze"
          name: "analyze"
          operations:
            - method: "POST"
              name: "run-analysis"
              outputParameters:
                - name: "analysis"
                  type: "object"
                  value: "$.result"

  exposes:
    # REST REST adapter
    - type: "rest"
      address: "0.0.0.0"
      port: 9090
      namespace: "analytics-rest"
      resources:
        - path: "/analyze"
          operations:
            - method: "POST"
              name: "run-analysis"
              call: "analytics-api.run-analysis"

    # MCP adapter
    - type: "mcp"
      transport: "stdio"
      namespace: "analytics-mcp"
      tools:
        - name: "quick-stats"
          description: "Run quick statistical analysis"
          call: "analytics-api.run-analysis"
          inputParameters:
            - name: "data"
              type: "object"
              description: "Data to analyze"

    # Skill adapter — tools from both adapters + local instruction
    - type: "skill"
      address: "0.0.0.0"
      port: 8080
      namespace: "data-skills"
      description: "Data intelligence skills"

      skills:
        - name: "data-analysis"
          description: "Data analysis tools from REST and MCP"
          license: "MIT"
          allowed-tools: "run-analysis quick-stats"
          location: "file:///etc/naftiko/skills/data-analysis"
          tools:
            - name: "run-analysis"
              description: "Run a full data analysis via REST"
              from:
                sourceNamespace: "analytics-rest"
                action: "run-analysis"
            - name: "quick-stats"
              description: "Run quick statistical analysis via MCP"
              from:
                sourceNamespace: "analytics-mcp"
                action: "quick-stats"
            - name: "analysis-methodology"
              description: "Guide for choosing the right analysis approach"
              instruction: "methodology.md"
```

---

### Example 4: Kubernetes Deployment

**Scenario**: Production deployment of skill server alongside API and MCP adapters.

#### Docker Compose
```yaml
version: '3.8'

services:
  naftiko-platform:
    image: naftiko:latest
    ports:
      - "8080:8080"   # Skill catalog
      - "9090:9090"   # REST API (tool execution)
      - "9091:9091"   # MCP (tool execution)
    environment:
      WEATHER_API_KEY: ${WEATHER_API_KEY}
    volumes:
      - ./capability.yaml:/etc/naftiko/capability.yaml
      - ./skills/:/etc/naftiko/skills/    # Skill supporting files
    command: naftiko run /etc/naftiko/capability.yaml
```

#### Kubernetes Deployment
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: naftiko-skill-server
  namespace: production
spec:
  replicas: 3
  selector:
    matchLabels:
      app: skill-server
  template:
    metadata:
      labels:
        app: skill-server
    spec:
      containers:
      - name: naftiko
        image: naftiko:v1.5.0
        ports:
        - containerPort: 8080
          name: skill-catalog
        - containerPort: 9090
          name: rest-api
        - containerPort: 9091
          name: mcp
        env:
        - name: NAFTIKO_CONFIG
          value: /etc/naftiko/capability.yaml
        - name: WEATHER_API_KEY
          valueFrom:
            secretKeyRef:
              name: api-credentials
              key: weather-api-key
        volumeMounts:
        - name: config
          mountPath: /etc/naftiko
        - name: skills
          mountPath: /etc/naftiko/skills
        livenessProbe:
          httpGet:
            path: /skills
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 30
        readinessProbe:
          httpGet:
            path: /skills
            port: 8080
          initialDelaySeconds: 5
          periodSeconds: 10
        resources:
          requests:
            memory: "256Mi"
            cpu: "250m"
          limits:
            memory: "512Mi"
            cpu: "500m"
      volumes:
      - name: config
        configMap:
          name: skill-server-config
      - name: skills
        persistentVolumeClaim:
          claimName: skill-files
---
apiVersion: v1
kind: Service
metadata:
  name: skill-server
  namespace: production
spec:
  selector:
    app: skill-server
  ports:
  - port: 80
    targetPort: 8080
    name: skill-catalog
  - port: 9090
    targetPort: 9090
    name: rest-api
  - port: 9091
    targetPort: 9091
    name: mcp
  type: ClusterIP
```

---

## Security Considerations

### Input Validation
- `{name}` parameter: `^[a-zA-Z0-9_-]+$`
- `{file}` path: validated against path traversal (no `../`)
- All path segments restricted by character whitelist

### Location URI Validation
- Only `file:///` scheme is permitted
- Resolved path must stay within allowed base directories
- Symlinks resolved and validated against directory boundaries
- No relative path components (`..`) allowed in resolved URI

### Authentication
- API Key (header, query)
- Bearer token
- Basic authentication
- OAuth2

### File Access Control
- Restrict file access to skill `location` directory trees
- No parent directory access (`../`)
- Validate all file paths for traversal attacks

---

## Tool Validation Rules

1. `tools` is optional — a skill can be purely descriptive (metadata + `location` only, no tools)
2. Each tool must specify exactly one source: `from` (derived) or `instruction` (local file)
3. For derived tools (`from`), `sourceNamespace` must resolve to exactly one sibling `exposes[].namespace` of type `rest` or `mcp`
4. Referencing a `skill`-type adapter from `from.sourceNamespace` is invalid (no recursive derivation)
5. For derived tools, `action` must exist as an operation name (api) or tool name (mcp) in the resolved adapter
6. For instruction tools, the skill must have a `location` configured — the instruction path is resolved relative to it
7. Tool `name` values must be unique within a skill

---

## Implementation Roadmap

| Phase | Deliverable |
|-------|-------------|
| Phase 1 | Core schema + ExposedSkill + SkillTool types + validation |
| Phase 2 | Discovery endpoints (GET /skills, GET /skills/{name}) + tool resolution |
| Phase 3 | Location support + file browsing + download endpoints |
| Phase 4 | Auth, caching, testing, documentation, examples |

---

## Backward Compatibility

- No breaking changes
- Purely additive to Naftiko 0.5+
- Existing capabilities unaffected
- New `type: "skill"` in exposes union

---

## Why This Architecture Works

1. **Clear Separation of Concerns**: Skills describe; `rest` and `mcp` adapters execute. Each adapter does one thing well.

2. **No Duplication**: Derived tools reference operations already defined in adjacent adapters — no need to redefine tool logic. Instruction tools add knowledge without duplicating execution.

3. **Agent Skills Spec Alignment**: Full frontmatter metadata (name, description, license, compatibility, argument-hint, invocation controls) aligned with the [Agent Skills Spec](https://agentskills.io/specification).

4. **Supporting Files**: `location` property provides SKILL.md and supporting documentation, served through REST endpoints.

5. **Direct Invocation**: Agents discover tools through the skill catalog, then invoke the source adapter directly — no proxy overhead, no execution complexity in the skill layer.

6. **Composable**: A skill can declare derived tools from multiple sibling adapters (both `rest` and `mcp`) and instruction tools from local files, providing a unified discovery surface.

7. **Enterprise Ready**: Auth, metadata governance, and file distribution endpoints support internal hosting and access control.
