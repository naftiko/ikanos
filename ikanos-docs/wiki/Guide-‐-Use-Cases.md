# Guide - Use Cases

Here is an overview of typical use cases where Naftiko Framework can help developers and supporting features available. Additional features are being added as described in the [roadmap](https://github.com/naftiko/framework/wiki/Roadmap).

## 1. AI integration

Connect AI assistants to your systems through capabilities, so they can access trusted business data and actions without custom glue code.

How Naftiko achieves this technically:
- Declare upstream systems in `capability.consumes` (typically `type: http`) with `namespace`, `baseUri`, authentication, headers, and operation contracts.
- Expose the same domain as `type: mcp` tools and/or `type: api` resources in `capability.exposes`, so both AI agents and traditional clients use one integration layer.
- Use `call`, `with`, `steps`, and JSONPath `mapping` in `outputParameters` to return normalized, task-ready payloads instead of raw provider responses.

![Integrate AI](https://naftiko.github.io/docs/images/technology/use_case_AI_integration.png)

#### Key features
- [x] Declarative HTTP consumption with namespace-scoped adapters
  - [x] Bearer, API key, basic, and digest authentication
  - [x] Templated baseUri and path parameters
- [x] Polyglot exposure from one capability
  - [x] Agent skills exposure for AI agents
  - [x] MCP tool exposure for AI agents
  - [x] REST resource exposure for conventional clients
- [x] Output shaping with typed parameters and JSONPath mapping
  - [x] Nested object and array output parameters
  - [x] Static values for computed fields
- [x] Externalized secrets via `binds` (file, vault, environment)

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
- [x] Output shaping with typed parameters and JSONPath mapping
  - [x] Fine-grained field selection and nested object mapping
  - [x] Static values to inject context
- [x] Typed MCP tool input parameters with descriptions
  - [x] Required/optional parameter declarations for agent discovery

## 3. Elevate an existing API

Wrap a current API as a capability to make it easier to discover, reuse, and consume across teams and channels.

How Naftiko achieves this technically:
- Model the legacy/existing API once in `consumes.resources.operations` with explicit methods, paths, parameters, and body formats.
- Add a stable capability namespace and expose curated resource paths/tools that are easier to consume than vendor-native endpoints.
- Reshape not just data but also operation semantics: declare `semantics` (safe, idempotent, cacheable) on aggregate functions so the engine derives correct HTTP methods for REST adapters and `hints` for MCP tools automatically.
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
  - [x] Reshape operation semantics via `aggregates`
    - Declare safe, idempotent, cacheable on domain functions
    - Engine derives REST methods and MCP tool `hints` automatically
  - [x] Convert XML, Avro, CSV, TSV, PSV, HTML, Markdown payloads to JSON
- [x] Schema-based validation with Spectral rules
  - [x] Namespace uniqueness, path conventions, and security checks
- [x] Forward proxy for selective pass-through routing
  - [x] Trusted header forwarding

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
- [x] Support for multiple consumed adapters with unique namespaces
  - [x] Reuse consistent APIs and JSON payloads
- [x] Multi-step orchestration with ordered steps
  - [x] Call steps to invoke consumed operations
  - [x] Lookup steps for in-memory data joining
  - [x] Step output bridging via `with` and `mappings`
- [x] Composed output model from cross-source data
  - [x] Named orchestrated output parameters
  - [x] Nested objects and typed arrays

## 6. Rightsize a set of microservices

Create a simpler capability layer over many microservices to reduce client complexity and improve consistency.

How Naftiko achieves this technically:
- Aggregate multiple microservice endpoints under one exposed namespace and a small set of business-oriented resources/tools.
- Standardize auth/header behavior and parameter handling at capability level instead of duplicating logic in every client.
- Use orchestration and output shaping to hide service fragmentation and return consistent contracts.

![Rightsize microservices](https://naftiko.github.io/docs/images/technology/use_case_api_reusability_rightsize_microservices.png)

#### Key features
- [x] Declarative source HTTP adapter and target REST adapter
  - [x] Support for source APIs with JSON payloads
  - [x] Convert YAML, Protobuf, XML, Avro, and CSV payloads to JSON
  - [x] Support sequence of steps with calls
- [x] Multiple consumed adapters per capability
  - [x] Namespace-scoped auth, headers, and base URI per adapter
- [x] Unified parameter handling across microservices
  - [x] Input parameters in query, header, path, cookie, and body
  - [x] Regex pattern validation on string inputs

## 7. Rightsize a monolith API

Extract focused capabilities from a broad monolith API so consumers get only what they need for each use case.

How Naftiko achieves this technically:
- Select only required monolith operations in `consumes` and remap them to narrower exposed resources/tools.
- Use output filtering/mapping to publish smaller, purpose-built payloads for each consumer scenario.
- Optionally forward selected routes with `forward.targetNamespace` to keep passthrough paths where full transformation is not needed.

![Rightsize monolith APIs](https://naftiko.github.io/docs/images/technology/use_case_api_reusability_rightsize_monolith_apis.png)

#### Key features
- [x] Selective operation exposure from a broad API
  - [x] Remap consumed operations to narrower exposed resources
  - [x] Output filtering via typed parameters and JSONPath mapping
- [x] Forward proxy for pass-through routes
  - [x] `forward.targetNamespace` for direct routing
  - [x] Trusted header forwarding for security
- [x] Dual-channel exposure from one capability
  - [x] REST resource exposure for conventional clients
  - [x] MCP tool exposure for AI agents

## 8. Capability-first context engineering

Design capabilities first for MCP clients, then map them to underlying APIs for a clean AI-native integration model.

How Naftiko achieves this technically:
- Define `type: mcp` exposure with server-level description and tool-level contracts (name, description, input/output parameters).
- Support MCP transports (`http` or `stdio`) so the same capability can run in remote server mode or local IDE/agent mode.
- Wire tools to one or many consumed operations via `call` or orchestrated `steps`, without changing upstream APIs.

![Capability-first approach](https://naftiko.github.io/docs/images/technology/use_case_context_engineering_capability_first.png)

#### Key features
- [x] Declarative MCP server with tools, resources, and prompts
  - [x] Typed input parameters with descriptions for agent discovery
  - [x] Static file-backed resources with MIME types
  - [x] Prompt templates with argument placeholders
- [x] Support for key MCP transport protocols
  - [x] MCP for Streaming HTTP (for remote MCP clients)
  - [x] MCP for Standard IO (for local MCP clients)
- [x] Mock mode for MCP tools
  - [x] Static value outputs without consuming an API
  - [x] Dynamic mock values using Mustache templates from input parameters
- [x] Multi-step orchestration wired to consumed operations
  - [x] Call and lookup steps with cross-step output bridging

## 9. Capability-first API reusability

Start from existing APIs and define reusable capabilities on top, so API investments can power new AI and app experiences.

How Naftiko achieves this technically:
- Begin with consumed API declarations (`baseUri`, auth, resources, operations), then incrementally add exposed REST/MCP adapters.
- Reuse existing security and configuration using `binds` with file or runtime injection and injected variables (e.g., `{{API_TOKEN}}`).
- Add format-aware parsing and mapping (JSON, YAML, XML, CSV, TSV, PSV, Avro, Protobuf, HTML, Markdown support in the framework) to normalize diverse backends.

![Capability-first approach](https://naftiko.github.io/docs/images/technology/use_case_api_reusability_capability_first.png)

#### Key features
- [x] Externalized secrets via `binds`
  - [x] Support for file, vault, GitHub secrets, K8s secrets, and runtime injection
  - [x] SCREAMING_SNAKE_CASE variable expressions
- [x] Consumes import and reuse across capabilities
  - [x] Import by location with optional namespace aliasing
- [x] Format-aware response parsing and normalization
  - [x] JSON, XML, YAML, CSV, Avro, and Protobuf to JSON conversion
- [x] Multiple request body formats
  - [x] JSON, XML, form URL encoded, multipart form
- [x] Incremental adapter addition (REST, MCP, or Skill)
  - [x] Skill server exposure with agent-compatible metadata

## 10. Interoperate with OpenAPI

Bridge existing OpenAPI ecosystems with Naftiko capabilities: import OAS documents to bootstrap consumption adapters, and export REST adapters as standard OpenAPI specifications.

How Naftiko achieves this technically:
- Import an OpenAPI 3.0 or 3.1 document (or a Swagger 2.0 document, which is auto-converted) with `naftiko import openapi`, generating a ready-to-use `consumes` HTTP adapter with authentication, operations, input parameters, and output parameters pre-filled.
- Export a REST `exposes` adapter with `naftiko export openapi`, producing a standards-compliant OAS document that can be shared with API gateways, developer portals, and documentation tools.
- Use `--adapter <namespace>` to target a specific REST adapter when the capability exposes more than one.

#### Key features
- [x] OpenAPI import into Naftiko `consumes` adapter
  - [x] Swagger 2.0 support (auto-converted to OAS 3.0)
  - [x] OAS 3.0 and 3.1 support
  - [x] Authentication mapping (bearer, basic, API key, digest)
  - [x] Automatic namespace derivation from API title
  - [x] Operation grouping by tag into separate resources
  - [x] Input parameter conversion (query, header, path, cookie, body)
  - [x] Output parameter conversion (object, array, scalar, allOf, oneOf)
- [x] OpenAPI export from Naftiko REST `exposes` adapter
  - [x] OAS 3.0 and 3.1 output via `--spec-version`
  - [x] YAML and JSON output formats
  - [x] Multi-adapter selection with `--adapter`
  - [x] Security scheme generation from consumed authentication
- [x] Round-trip fidelity
  - [x] Import → export preserves structure and semantics

## 11. Monitor and debug capabilities

Observe capability behavior in real time with built-in metrics, distributed traces, and health checks — configured entirely from the spec.

How Naftiko achieves this technically:
- Add a `type: control` adapter in `capability.exposes` to expose a management plane with health, metrics, traces, and diagnostic endpoints on a dedicated port.
- Configure `capability.observability` to enable OpenTelemetry-based distributed tracing and RED metrics (Rate, Errors, Duration) with configurable sampling and OTLP export.
- Scrape Prometheus-format metrics from the control port's `/metrics` endpoint and inspect recent traces via `/traces` — no additional infrastructure code required.

#### Key features
- [x] Built-in control port with configurable endpoints
  - [x] Liveness and readiness health probes
  - [x] Prometheus metrics scrape endpoint
  - [x] Local trace inspection endpoint with ring buffer
  - [x] Runtime status and configuration endpoints
  - [x] Hot-reload and dry-run validation endpoints
  - [x] Log level control and SSE log streaming
- [x] OpenTelemetry observability from the spec
  - [x] Distributed tracing with W3C and B3 propagation
  - [x] Configurable sampling rate
  - [x] OTLP exporter with Mustache-templated endpoint
  - [x] RED metrics: request duration, step duration, HTTP client duration, error count
- [x] Spec-driven configuration — no code required
  - [x] Control port address, port, and authentication
  - [x] Per-endpoint enable/disable toggles

## 12. Transform data between API calls

Reshape, filter, or aggregate data between consumed API calls using inline script steps — without building a separate microservice or writing Java code.

How Naftiko achieves this technically:
- Add `type: script` steps in orchestrated operations to transform data between `call` steps using JavaScript, Python, or Groovy.
- Scripts run in sandboxed environments (GraalVM for JS/Python, SecureASTCustomizer for Groovy) with no filesystem or network access.
- Previous step results are bound as variables; scripts assign to `result` to produce output for subsequent steps or mappings.
- Govern execution centrally via the Control Port's `management.scripting` block — set defaults, timeouts, statement limits, and allowed languages.

#### Key features
- [x] Inline script steps in orchestrated operations
  - [x] JavaScript execution via GraalVM Truffle
  - [x] Python execution via GraalVM Truffle
  - [x] Groovy execution via GroovyShell
  - [x] Script dependencies (shared helpers, libraries)
  - [x] `with` injection for input parameter binding
- [x] Security and governance
  - [x] Sandboxed execution — no I/O, no network, no system access
  - [x] Configurable statement limits and timeouts
  - [x] Allowed languages restriction
  - [x] Control Port `/scripting` endpoint for runtime management
  - [x] CLI `naftiko scripting` command for governance
- [x] Linting support
  - [x] Spectral rule `naftiko-script-defaults-required` validates Control Port defaults
