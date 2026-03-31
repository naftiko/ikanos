# Guide - Use Cases

Here is an overview of typical use cases where Naftiko Framework can help developers and supporting features available. Additional features are being added as described in the [roadmap](https://github.com/naftiko/framework/wiki/Roadmap).

## 1. AI integration

Connect AI assistants to your systems through capabilities, so they can access trusted business data and actions without custom glue code.

How Naftiko achieves this technically:
- Declare upstream systems in `capability.consumes` (typically `type: http`) with `namespace`, `baseUri`, authentication, headers, and operation contracts.
- Expose the same domain as `type: mcp` tools and/or `type: api` resources in `capability.exposes`, so both AI agents and traditional clients use one integration layer.
- Use `call`, `with`, `steps`, and JSONPath `mapping` in `outputParameters` to return normalized, task-ready payloads instead of raw provider responses.

![Integrate AI](https://naftiko.github.io/docs/images/technology/use_case_AI_integration.png)

## 2. Rightsize AI context

Expose only the context an AI task needs, reducing noise, improving relevance, and keeping prompts efficient.

How Naftiko achieves this technically:
- Shape response payloads with typed `outputParameters` (string, object, array) and fine-grained JSONPath `mapping` expressions.
- Keep only relevant fields in exposed tool/resource schemas, while hiding irrelevant upstream fields from the AI surface.
- Attach meaningful `info.description`, tool descriptions, and tags so discovery is semantic and context quality stays high.

![Rightsize AI context](https://naftiko.github.io/docs/images/technology/use_case_context_engineering_rightsize_ai_context.png)

#### Key features
- [x] Declarative applied capability exposing MCP              
  - [x] Declarative applied capabilities with reused source capabilities
- [x] Support for key MCP transport protocols
  - [x] MCP for Streaming HTTP (for remote MCP clients)
  - [x] MCP for Standard IO (for local MCP clients)

## 3. Elevate an existing API

Wrap a current API as a capability to make it easier to discover, reuse, and consume across teams and channels.

How Naftiko achieves this technically:
- Model the legacy/existing API once in `consumes.resources.operations` with explicit methods, paths, parameters, and body formats.
- Add a stable capability namespace and expose curated resource paths/tools that are easier to consume than vendor-native endpoints.
- Enforce schema-based validation (`capability-schema.json`) so the elevated contract remains consistent and machine-checkable.

![Elevate existing APIs](https://naftiko.github.io/docs/images/technology/use_case_api_reusability_elevate_existing_apis.png)

#### Key features
- [x] Pass thru source capability
  - [x] One source HTTP adapter per capability
  - [x] Multiple source HTTP adapters per capability
- [x] Declarative source HTTP adapter and target REST adapter
  - [x] Focus on source APIs with JSON payloads
  - [x] Change HTTP methods to expose proper semantics
    - E.g. Adapt read-only POST queries into cacheable GET queries
  - [x] Convert XML, Avro, CSV payloads to JSON

## 4. Elevate Google Sheets API

Wrap Google Sheets as a capability so spreadsheet rows become a reusable, domain-specific API for traditional clients and AI agents.

How Naftiko achieves this technically:
- Consume the Google Sheets values endpoint in `consumes.resources.operations`, with templated `spreadsheet_id` and `range` path parameters plus API key injection through `binds`.
- Transform raw row arrays from `$.values` into named objects using positional JSONPath mappings such as `$[0]`, `$[1]`, and `$[2]` inside typed `outputParameters`.
- Expose the normalized result through `type: rest`, `type: mcp`, or both, so one spreadsheet integration serves both application and agent use cases.

![Elevate GSheets API](https://naftiko.github.io/docs/images/technology/use_case_api_reusability_elevate_gsheets_api.png)

#### Key features
- [x] Declarative Google Sheets API consumption
  - [x] Templated spreadsheet ID and A1 range parameters
  - [x] API key injection via externalized bind values
- [x] Declarative row-to-object transformation
  - [x] Positional JSONPath mapping from array columns to named fields
  - [x] Typed JSON array exposure for downstream consumers
- [x] Dual-channel exposure from one capability
  - [x] REST resource exposure for conventional clients
  - [x] MCP tool exposure for AI agents

## 5. Compose AI context

Combine data from multiple APIs into one capability to deliver richer, task-ready context to AI clients.

How Naftiko achieves this technically:
- Register multiple consumed APIs with unique namespaces, then orchestrate cross-source calls using ordered `steps`.
- Bridge calls through step `mappings` and per-step input injection, so outputs from one source feed inputs of the next.
- Return a single composed output model via mapped `outputParameters`, giving AI clients one coherent result.

![Rightsize AI context](https://naftiko.github.io/docs/images/technology/use_case_context_engineering_compose_ai_context.png)

#### Key features
- [x] Declarative source HTTP adapter and target MCP adapter
- [x] Support for source capabilities
  - [x] Reuse consistent APIs and JSON payloads

## 6. Rightsize a set of microservices

Create a simpler capability layer over many microservices to reduce client complexity and improve consistency.

How Naftiko achieves this technically:
- Aggregate multiple microservice endpoints under one exposed namespace and a small set of business-oriented resources/tools.
- Standardize auth/header behavior and parameter handling at capability level instead of duplicating logic in every client.
- Use orchestration and output shaping to hide service fragmentation and return consistent contracts.

![Rightsize microservices](https://naftiko.github.io/docs/images/technology/use_case_api_reusability_rightsize_microservices.png)

#### Key features
- [x] Declarative source HTTP adapter and target REST adapter
  - [x] Finish support for source APIs with JSON payloads
    - [x] Convert YAML, Protobuf, payloads to JSON
    - [x] Support sequence of steps with calls

## 7. Rightsize a monolith API

Extract focused capabilities from a broad monolith API so consumers get only what they need for each use case.

How Naftiko achieves this technically:
- Select only required monolith operations in `consumes` and remap them to narrower exposed resources/tools.
- Use output filtering/mapping to publish smaller, purpose-built payloads for each consumer scenario.
- Optionally forward selected routes with `forward.targetNamespace` to keep passthrough paths where full transformation is not needed.

![Rightsize monolith APIs](https://naftiko.github.io/docs/images/technology/use_case_api_reusability_rightsize_monolith_apis.png)

## 8. Capability-first context engineering

Design capabilities first for MCP clients, then map them to underlying APIs for a clean AI-native integration model.

How Naftiko achieves this technically:
- Define `type: mcp` exposure with server-level description and tool-level contracts (name, description, input/output parameters).
- Support MCP transports (`http` or `stdio`) so the same capability can run in remote server mode or local IDE/agent mode.
- Wire tools to one or many consumed operations via `call` or orchestrated `steps`, without changing upstream APIs.

![Capability-first approach](https://naftiko.github.io/docs/images/technology/use_case_context_engineering_capability_first.png)

#### Key features
- [x] Declarative target API contract with mocking mode
  - [x] Allow REST server adapter with no source HTTP adapter
  - [x] Use static value of output parameters as sample

## 9. Capability-first API reusability

Start from existing APIs and define reusable capabilities on top, so API investments can power new AI and app experiences.

How Naftiko achieves this technically:
- Begin with consumed API declarations (`baseUri`, auth, resources, operations), then incrementally add exposed REST/MCP adapters.
- Reuse existing security and configuration using `binds` with file or runtime injection and injected variables (e.g., `{{API_TOKEN}}`).
- Add format-aware parsing and mapping (JSON, YAML, XML, CSV, Avro, Protobuf support in the framework) to normalize diverse backends.

![Capability-first approach](https://naftiko.github.io/docs/images/technology/use_case_api_reusability_capability_first.png)
