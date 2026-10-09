# Ikanos Architecture

This document explains how the Ikanos engine works inside: its modules, how a capability is loaded and started, and the path a request takes through the engine. It is written for contributors, people and coding agents, who need a mental model of the code before changing it.

It is not a user guide. To learn how to *write* a capability, read the [specification and tutorials on Shipyard](https://shipyard.naftiko.io/ikanos/). For per-class detail, read the Javadoc and the `package-info.java` of each package: this document names the important classes and leaves the detail to them.

> **Keep this file current.** It describes modules, packages and flows, not lines of code, so it only needs an update when the architecture changes: a new module or adapter type, a new step type, a change to the startup order or to the request path. If your PR does one of those, update the matching section in the same PR.

> **Work in progress.** Three open PRs change the consumes side and the step flow. When they are merged, the sections they touch are updated:
>
> - [#780](https://github.com/naftiko/ikanos/pull/780) separates consumed-call execution from HTTP: request building moves into `HttpClientAdapter.prepare`, and `HandlingContext` is replaced. This affects [Consumes](#consumes-calling-upstream-apis), the request diagram and [Extension points](#extension-points).
> - [#782](https://github.com/naftiko/ikanos/pull/782) adds an MCP client as a second kind of consumed adapter.
> - [#770](https://github.com/naftiko/ikanos/pull/770) stops an orchestrated sequence when a call step gets a non-2xx response.

## Contents

- [The big picture](#the-big-picture)
- [Code map](#code-map)
- [Spec and validation](#spec-and-validation)
- [Life of a capability: startup and shutdown](#life-of-a-capability-startup-and-shutdown)
- [Life of a request](#life-of-a-request)
- [Consumes: calling upstream APIs](#consumes-calling-upstream-apis)
- [Orchestration: from parameters to a result](#orchestration-from-parameters-to-a-result)
- [Exposes: the server adapters](#exposes-the-server-adapters)
- [Observability](#observability)
- [Extension points](#extension-points)
- [Tests](#tests)
- [Invariants and design decisions](#invariants-and-design-decisions)
- [Where to start reading](#where-to-start-reading)

## The big picture

A **capability** is a single YAML file. It declares which upstream APIs it **consumes**, how calls to them are combined (**orchestration**), and how the result is **exposed** to clients: as MCP tools, REST endpoints, or agent skills. Ikanos reads that file and runs it. There is no generated code and no Java written by the capability author.

```mermaid
flowchart LR
    subgraph clients[Clients]
        A[AI agent / MCP client]
        B[HTTP client]
        C[Agent skill client]
        O[Operator / Kubernetes]
    end

    subgraph engine[Ikanos engine]
        direction LR
        subgraph exposes[Exposes]
            MCP[MCP server<br/>HTTP or stdio]
            REST[REST server]
            SKILL[Skill server]
            CTRL[Control port]
        end
        ORCH[Orchestration<br/>call · steps · aggregates]
        subgraph consumes[Consumes]
            HTTP[HTTP client adapter]
        end
    end

    UP[(Upstream APIs)]

    A --> MCP
    B --> REST
    C --> SKILL
    O --> CTRL
    MCP --> ORCH
    REST --> ORCH
    ORCH --> HTTP
    HTTP --> UP
```

The same three ideas appear everywhere in the code:

- **Spec model.** The YAML is deserialized into plain Java objects (`*Spec` classes in `ikanos-spec`). Nothing runs at this stage.
- **Adapters.** At startup, each `consumes` entry becomes a *client adapter* and each `exposes` entry becomes a *server adapter*. Adapters are the runtime objects that own sockets, routes and HTTP clients.
- **Execution.** When a request arrives, a server adapter resolves its parameters, hands them to the orchestration layer (`OperationStepExecutor`, or an `AggregateFlow`), which calls client adapters and maps their responses back into the shape the client expects.

## Code map

### Modules

```mermaid
flowchart BT
    spec[ikanos-spec]
    engine[ikanos-engine]
    cli[ikanos-cli]
    ziti[ikanos-tunnel-ziti]
    engine --> spec
    cli --> engine
    cli --> spec
    ziti --> engine
```

| Module | What it owns |
|---|---|
| `modules/ikanos-spec` | The specification. The JSON Schema (`schemas/ikanos-schema.json`, the source of truth for the YAML format), the Polychro ruleset (`rules/ikanos-rules.yml`), the Jackson model the YAML is read into (`io.ikanos.spec`), and OpenAPI import/export (`io.ikanos.spec.openapi`). It has no runtime behaviour. |
| `modules/ikanos-engine` | The runtime. Loads a spec, builds the adapters, runs requests, and handles telemetry. Usable as a library (see [Extension points](#extension-points)). |
| `modules/ikanos-cli` | The `ikanos` command (Picocli): `serve`, `validate`, `create`, `import`, `export`, and the control-port clients `health`, `status`, `traces`, `metrics`, `scripting`. It builds the shaded `ikanos.jar` used by the Docker image and the GraalVM native binary. |
| `modules/ikanos-tunnel-ziti` | An optional OpenZiti implementation of the reverse-tunnel SPI. It is discovered with `ServiceLoader` when the jar is on the classpath. |
| `modules/ikanos-docs` | No code. Tutorial capabilities (`tutorial/`), demos, and test documentation. |
| `modules/ikanos-coverage` | No code. Aggregates JaCoCo coverage across modules for the quality gate. |

The JSON Schema and the ruleset are also synchronized to Crafter, the Naftiko VS Code extension, by the `synchronize-schema-and-rules` workflow. A change to either one reaches editors too.

### Engine packages

All engine code lives under `io.ikanos`, in `modules/ikanos-engine/src/main/java`:

| Package | Role |
|---|---|
| `io.ikanos` | `Capability`: the root runtime object. Builds and holds every adapter, the aggregates and the resolved bindings. |
| `io.ikanos.bootstrap` | `CapabilityRuntime`: what `ikanos serve` runs. Reads the file, starts telemetry, starts the capability, waits for shutdown. |
| `io.ikanos.engine` | `Adapter`: the start/stop contract shared by all adapters. |
| `io.ikanos.engine.consumes` | `ClientAdapter` base class. |
| `io.ikanos.engine.consumes.http` | `HttpClientAdapter`: calls an upstream HTTP API, with its authentication schemes. |
| `io.ikanos.engine.consumes.tunnel` | The reverse-tunnel SPI (`Tunnel`) and its bootstrap. |
| `io.ikanos.engine.util` | The orchestration core: `OperationStepExecutor` (runs calls and steps), `Resolver` (Mustache templates, parameter extraction, output mapping), `Converter` (non-JSON formats to JSON), `LookupExecutor`, `BindingResolver`. |
| `io.ikanos.engine.aggregates` | Reusable domain flows (`Aggregate`, `AggregateFlow`) and the load-time `ref` resolution (`AggregateRefResolver`). |
| `io.ikanos.engine.scripting` | `ScriptStepExecutor`: sandboxed JavaScript, Python (GraalVM) and Groovy script steps. |
| `io.ikanos.engine.step` | The embedding API: Java `StepHandler`s registered by step name. |
| `io.ikanos.engine.imports` | Resolves `import` entries in `consumes`, `exposes`, `aggregates` and `binds` before anything else runs. |
| `io.ikanos.engine.exposes` | `ServerAdapter` base class and the shared authentication filters. |
| `io.ikanos.engine.exposes.mcp` | The MCP server: transports, `ProtocolDispatcher`, per-method handlers, tool/resource/prompt handlers. |
| `io.ikanos.engine.exposes.rest` | The REST server: routing and `ResourceRestlet`. |
| `io.ikanos.engine.exposes.skill` | The Skill server: a read-only catalog of agent skills. |
| `io.ikanos.engine.exposes.control` | The control port: health, status, metrics, traces, scripting management. |
| `io.ikanos.engine.observability` | OpenTelemetry bootstrap, metrics, trace context propagation. |

The HTTP layer on both sides is Restlet. Server adapters are Restlet `Server`s with a chain of `Restlet`s; client adapters wrap a Restlet `Client`.

## Spec and validation

The YAML format is defined once, in `modules/ikanos-spec/src/main/resources/schemas/ikanos-schema.json`. Everything else follows it:

| Layer | Where | Role |
|---|---|---|
| JSON Schema | `schemas/ikanos-schema.json` | The source of truth for structure: which keys exist, their types, which are required. `schemas/README.md` documents it, and `schemas/examples/` holds reference capabilities. |
| Ruleset | `rules/ikanos-rules.yml` | Checks the schema cannot express: cross-object consistency (a `call` points to a declared operation), quality and security rules. It is written in the Spectral format and is the ruleset Polychro applies. |
| Jackson model | `io.ikanos.spec` | The `*Spec` classes the YAML is read into. Polymorphic sections are picked by custom deserializers: `ServerSpecDeserializer` by `type`, `ClientSpecDeserializer` by the presence of `from` (an import), and similar ones for parameters, steps and bindings. |

Validation runs **outside** the runtime:

- `ikanos validate` checks a file against the JSON Schema. It builds its validator with `IkanosMetaSchemaFactory`, which teaches the schema library the Ikanos `name` keyword.
- The ruleset runs in CI through Spectral, from the MegaLinter workflow (`.mega-linter.yml`, `.spectral.yaml`), on the tutorial, the examples and the test fixtures. Moving that check to Polychro is tracked in [#382](https://github.com/naftiko/ikanos/issues/382).
- Crafter, the VS Code extension, receives the schema and the ruleset through the `synchronize-schema-and-rules` workflow.

The spec version (`ikanos: "1.0.0-..."`) comes from the Maven build: `VersionHelper` reads it from the filtered `version.properties`, and `scripts/sync-schema-version.py` updates the version strings in fixtures and examples at each bump.

## Life of a capability: startup and shutdown

`ikanos serve [file]` (default `ikanos.yaml`, which is also what the Docker image runs) calls `CapabilityRuntime.serve`. From there, the order matters, and the comments in `Capability`'s constructor explain why:

1. **Read the YAML** into an `IkanosSpec` with Jackson. Unknown properties are ignored, and the file is **not** validated against the JSON Schema here (see [Invariants](#invariants-and-design-decisions)).
2. **Start telemetry** (`TelemetryBootstrap.init`), configured from the control adapter's `observability` block if there is one.
3. **Build the `Capability`**:
    1. **Resolve imports** in a fixed order: `consumes`, then `aggregates`, then `exposes`, then `binds` (`ImportResolver`, with one `ImportStrategy` per section). After this pass every section contains only inline entries.
    2. **Resolve aggregate refs** (`AggregateRefResolver`). Every `ref` on an MCP tool or REST operation must point to a known `namespace.flow`, otherwise startup fails. MCP tool hints are derived from the flow's `semantics`.
    3. **Find the scripting settings** on the control adapter, if any.
    4. **Build the aggregates** (`Aggregate` / `AggregateFlow`), sharing one `OperationStepExecutor`.
    5. **Resolve bindings** (`BindingResolver`): values read from a file when the binding has a `location`, otherwise from environment variables.
    6. **Construct the server adapters**, one per `exposes` entry, by `type`: `rest`, `mcp`, `skill`, `control`. A capability must expose at least one.
    7. **Discover and start tunnels** declared by `consumes` entries (`TunnelBootstrap`), waiting up to 30 seconds for them to be ready.
    8. **Construct the client adapters**, one per `consumes` entry of type `http`, handing each its tunnel if it has one.
4. **Start**: client adapters first, then server adapters, so nothing accepts a request before it can call upstream.
5. **Wait** until the JVM shuts down. A shutdown hook stops the capability once: server adapters first, then client adapters.

Two consequences for contributors:

- An adapter **constructor must not** call `getClientAdapters()` or `getServerAdapters()`: they are not published until the end of the `Capability` constructor. Do cross-adapter lookups at request time or in `start()`.
- Anything that can be checked at load time (an unknown `ref`, a malformed skill tool) should fail **here**, not on the first request.

## Life of a request

### An MCP `tools/call` over HTTP

This is the most complete path through the engine:

```mermaid
sequenceDiagram
    autonumber
    participant Client as MCP client
    participant Chain as Restlet server<br/>+ auth filter
    participant Res as McpServerResource
    participant Disp as ProtocolDispatcher
    participant H as ToolsCallHandler
    participant Tool as ToolHandler
    participant Exec as OperationStepExecutor
    participant HCA as HttpClientAdapter
    participant API as Upstream API

    Client->>Chain: POST (JSON-RPC body + Mcp-* headers)
    Chain->>Res: authenticated request
    Res->>Res: check required headers match the body
    Res->>Disp: dispatchWithTracing(body)
    Disp->>Disp: entry span, trace context from _meta or traceparent
    Disp->>H: pre-processors (protocol version), then handle()
    H->>Tool: handleToolCall(name, arguments)
    Tool->>Tool: merge arguments with the tool's with map
    alt tool has ref
        Tool->>Exec: via AggregateFlow.execute(parameters)
    else tool has call or steps
        Tool->>Exec: execute(...) / executeSteps(...)
    else neither
        Tool->>Tool: mock result from outputParameters values, no upstream call
    end
    Exec->>Exec: build request: URI template, input parameters, body, auth
    Exec->>HCA: HandlingContext.handle() (CLIENT span, traceparent injected)
    HCA->>API: HTTP request
    API-->>HCA: response
    HCA-->>Exec: response
    Exec-->>Tool: response / step outputs
    Tool->>Tool: convert to JSON, apply output mappings
    Tool-->>H: CallToolResult
    H-->>Disp: result
    Disp->>Disp: post-processors (server info, cache hints)
    Disp-->>Res: JSON-RPC response
    Res-->>Client: HTTP status mapped from the JSON-RPC error code, or 200
```

Points worth knowing:

- **Transport and protocol are separate.** `McpServerResource` holds everything specific to HTTP: header checks, HTTP status codes, content types. `ProtocolDispatcher` holds the MCP protocol and is shared with the stdio transport (`StdioJsonRpcHandler`). A protocol change belongs in the dispatcher or a handler, never in a transport.
- **One handler per JSON-RPC method.** Each method (`tools/call`, `resources/read`, `server/discover`, ...) has its own `McpCallHandler` subclass in `exposes.mcp.handler`, built once per adapter by `McpCallHandlersFactory`. The factory is the list of supported methods. Each handler declares the MCP headers it requires, its pre-processors (for example `ProtocolsVersionsValidator`) and its post-processors (for example `ServerDataAppender`).
- **MCP 2026-07-28 only.** Every request carries the protocol version in `params._meta`, and over HTTP also in headers that must match the body. There is no `initialize` handshake. A legacy client that still sends `initialize` gets an `UnsupportedProtocolVersionError` naming the supported versions (`LegacyInitializeHandler`), so it fails with a clear reason. The versions live in one place: `ProtocolDispatcher.MCP_PROTOCOL_VERSION` and `SUPPORTED_PROTOCOL_VERSIONS`.
- **Errors.** A bad argument (`IllegalArgumentException`) becomes a JSON-RPC `invalid params` error. Any other failure while running the tool becomes a tool result with `isError: true` and a generic message plus a reference id. The detail is only logged (see `ErrorReference`).

### Other entry points

- **MCP over stdio** (`transport: stdio`): `StdioJsonRpcHandler` reads JSON-RPC lines from stdin and writes responses to stdout, through the same `ProtocolDispatcher`. Stdout is reserved for the protocol, so all logs go to stderr.
- **REST**: `RestServerAdapter` attaches one `ResourceRestlet` per resource path. For each request, `ResourceRestlet` first looks for an operation matching the HTTP method and runs it in one of the [four execution modes](#execution-modes). Only when no operation handles the request does it fall back to `forward`, which proxies the request to a consumed API; otherwise it answers `404`. Input parameters are read from the path, query, headers and body, at server, resource and operation level, the most specific winning. In single-call mode the REST response keeps the upstream status code.
- **Skill**: read-only. It serves skill metadata and files; it never runs a call itself. A skill's tools point either to a tool of a sibling `mcp` or `rest` adapter (`from`) or to a local instruction file (`instruction`).
- **Control port**: engine-defined management endpoints, no orchestration.

## Consumes: calling upstream APIs

Each `consumes` entry of type `http` becomes an `HttpClientAdapter`, which owns one Restlet `Client` for HTTP and HTTPS.

For a `call` or a call step, the request is prepared in `OperationStepExecutor.findClientRequestFor`:

1. Find the client adapter by `namespace` and the operation by name.
2. Resolve `baseUri` + resource `path` as a Mustache template. Fail if any `{{...}}` is left.
3. Apply input parameters (adapter level, then operation level): query, header, path, and so on.
4. Build the body when the operation declares one: JSON by default, or form-urlencoded, XML or SPARQL. Fail if any template is left unresolved. Form bodies are encoded by `FormUrlEncodedBody`, which URL-encodes every substituted value so that a caller cannot add form fields.
5. Set authentication and default headers, through the client adapter (`HttpClientAdapter.setChallengeResponse` and `setHeaders`). Authentication values are resolved against the request parameters **and** the capability's bindings.
6. Return a `HandlingContext`: the request, the response, and the adapter and operation that produced them.

`HandlingContext.handle()` sends the request inside a CLIENT span, injects W3C trace context so the upstream sees Ikanos as the parent, and records the HTTP client metrics.

**The REST `forward` path is the exception.** `ResourceRestlet.handleFromForwardSpec` builds and sends its own request: it reuses the adapter's authentication and headers, but it does not go through `HandlingContext`, so a forwarded call has no CLIENT span, no injected trace context and no client metrics. This is a known gap, in scope for the audit of divergent implementations ([#667](https://github.com/naftiko/ikanos/issues/667)).

**Reverse tunnels.** A `consumes` entry can declare a `tunnel` to reach a private API through an overlay network instead of the public internet. `Tunnel` is a `ServiceLoader` SPI (`io.ikanos.engine.consumes.tunnel`). `TunnelBootstrap` discovers the implementations at startup, and requests to the adapter's host are routed through the tunnel by a Jetty request listener (`TunnelAwareHttpClientHelper`). `ikanos-tunnel-ziti` is the only implementation today.

## Orchestration: from parameters to a result

### Execution modes

Every executable unit, whether an MCP tool, a REST operation or an aggregate flow, runs in exactly one of four modes. The same decision appears in `ToolHandler`, `ResourceRestlet` and `AggregateFlow`:

| The unit declares | Mode | What happens |
|---|---|---|
| `ref` | **Aggregate** | Delegate to the `AggregateFlow` named `namespace.flow`. The flow then runs in one of the modes below. |
| `call` | **Single call** | One consumed operation, then output mappings. |
| `steps` | **Orchestrated** | A sequence of steps, then optional `mappings` that assemble the final result from step outputs. |
| none of these | **Mock** | The result is built from the `value` fields of `outputParameters`. Used to prototype a capability before wiring a real API. |

**Aggregates** follow the DDD aggregate pattern: a flow is defined once and projected through several adapters. At load time, `AggregateRefResolver` copies only descriptive metadata (name, description, MCP hints derived from `semantics`) onto the adapter unit. The execution fields (`call`, `steps`, `with`, parameters, mappings) are **not** copied: at request time the adapter delegates to the `AggregateFlow`, so there is one copy of the logic.

### Steps

`OperationStepExecutor.executeSteps` runs steps in declaration order. Before the normal dispatch, it checks whether a Java `StepHandler` is registered under the step's name (embedding API); if so, the handler runs instead. Otherwise:

| Step | Runs | Output |
|---|---|---|
| `call` | A consumed operation, with the step's `with` merged into the parameters. | The JSON response, projected through the consumed operation's `outputParameters` when it declares any. |
| `lookup` | A match against the output of an earlier step (`LookupExecutor`), for one value or a list of values. | The matched entries, reduced to the listed fields. |
| `script` | JavaScript, Python or Groovy in a sandbox (`ScriptStepExecutor`). The script reads a `context` binding and assigns `result`. | `result` as JSON. |

Each step gets its own span and step metric. The output of a step is stored under the step's name, so later steps and templates can use `{{step-name.field}}`.

### Parameters and templates

A request's parameters start as the client's arguments (or the REST inputs), merged with the unit's `with` map. Each step output is added under its step name. Two value syntaxes are resolved:

- **Mustache** (`{{name}}`, `{{step.field}}`), rendered by `Resolver.resolveMustacheTemplate` with JMustache.
- **Namespace-qualified references** (`namespace.param`) in `with` maps, read directly from the parameters.

Output mappings use **JSONPath** (Jayway) over the response.

### Data formats

Inside the engine, **all structured data is JSON**: Jackson `JsonNode` for trees, `Map`/`List` for template rendering. `Converter.convertToJson` turns an upstream response in another format into JSON at the boundary, based on the operation's `outputRawFormat`: XML, YAML, CSV, TSV, PSV, HTML, Markdown, Protobuf and Avro (the last two need an `outputSchema`). Mappings and lookups therefore only ever deal with JSON.

**Binary** content is the exception: when an operation declares `outputRawFormat: binary`, the bytes are read with a size cap (`maxBinarySize`) and passed through without decoding.

### Scripting

Script steps are allowed unless they are switched off with `IKANOS_SCRIPTING=false` or with `management.scripting` on the control adapter, which takes precedence. The control adapter can also limit the allowed languages and set defaults. JavaScript and Python run in a GraalVM polyglot context; Groovy runs in a `GroovyShell` restricted by a `SecureASTCustomizer`. All have a timeout.

## Exposes: the server adapters

All server adapters extend `ServerAdapter`, which owns the Restlet `Server` lifecycle and builds the **authentication chain** in front of the adapter's router (`buildServerChain`):

| `authentication.type` | Filter |
|---|---|
| `basic`, `digest` | Restlet `ChallengeAuthenticator`, with constant-time comparison |
| `bearer`, `apikey` | `ServerAuthenticationRestlet` |
| `oauth2` | `OAuth2AuthenticationRestlet`. The MCP adapter uses `McpOAuth2Restlet`, which also serves the Protected Resource Metadata (RFC 9728) that MCP clients look for. |

Authentication runs **before** any protocol handling: an unauthenticated request never reaches the MCP dispatcher or a REST operation.

| Adapter | Routes | Notes |
|---|---|---|
| `McpServerAdapter` | One endpoint (`POST`) over HTTP, or stdio | Builds the advertised MCP tools at startup: the input schema from `inputParameters` (or from the flow for a `ref`), annotations from `hints`, and an output schema when the tool has an output contract. |
| `RestServerAdapter` | The resource paths declared in the spec | Path templates come from the resource `path`. |
| `SkillServerAdapter` | `/skills`, `/skills/{name}`, `.../download`, `.../contents`, `.../contents/{file}` | Validates at startup that every skill tool has exactly one of `from` or `instruction`. |
| `ControlServerAdapter` | `/health/live`, `/health/ready`, `/status`, `/metrics`, `/traces`, `/traces/{traceId}`, `/scripting` | Each route is only attached when the matching management or observability option is on. The `ikanos health`, `status`, `traces`, `metrics` and `scripting` commands are its clients. |

## Observability

`TelemetryBootstrap` holds the engine's single OpenTelemetry instance. It is configured by OTel environment variables (`OTEL_*`), overridden by the control adapter's `observability` block, and falls back to a **no-op** implementation when telemetry is disabled or the OTel SDK is not on the classpath. Engine code therefore always calls the telemetry API, with no `if enabled` checks.

What gets a span:

- **Entry** spans: each incoming request, from `ProtocolDispatcher.dispatchWithTracing` (MCP), `ResourceRestlet` (REST) and `SkillServerResource` (Skill). The span is SERVER when Ikanos is the entry point, and INTERNAL when the caller already sent trace context, because the caller's span is then the real SERVER span (`TelemetryBootstrap.startServerSpan`).
- **INTERNAL** spans: a tool call, an aggregate flow, each step.
- **CLIENT** spans: each upstream call, in `HandlingContext.handle()` (except REST `forward`, see [Consumes](#consumes-calling-upstream-apis)).

Trace context crosses the engine in both directions. Incoming, it is read from the MCP `params._meta` (the transport-neutral carrier in MCP 2026-07-28), falling back to the HTTP `traceparent` header. Outgoing, it is injected into upstream requests. The trace id is also put in the logging MDC and used as the `ErrorReference`, so a reference returned to a client finds both the log line and the trace (`/traces/{traceId}` on the control port).

Metrics (`EngineMetrics`) are exposed in Prometheus format by the control port's `/metrics`.

## Extension points

| To add... | Start from |
|---|---|
| A new kind of **exposed** adapter | Subclass `ServerAdapter`, add a `*ServerSpec` and its `type` case in `ServerSpecDeserializer` (`ikanos-spec`), and instantiate it in the `Capability` constructor. |
| A new kind of **consumed** adapter | Subclass `ClientAdapter`, add a `*ClientSpec` and its case in `ClientSpecDeserializer`, and instantiate it in the `Capability` constructor. Today the call path (`OperationStepExecutor`, `HandlingContext`) assumes HTTP. |
| A new **MCP method** | A `McpCallHandler` subclass, registered in `McpCallHandlersFactory` with its required headers and processors. |
| A new **step type** | An `OperationStep*Spec` in `ikanos-spec` (like `OperationStepCallSpec`) and a case in `OperationStepExecutor.executeSteps`. |
| A new **data format** | A `ConversionFormat` value and a converter in `Converter`. |
| A new **tunnel transport** | Implement `Tunnel` in its own module and register it in `META-INF/services/io.ikanos.engine.consumes.tunnel.Tunnel`, like `ikanos-tunnel-ziti`. |
| Custom Java logic, when **embedding** the engine | `Capability.builder()` with a `StepHandler` registered by step name. The handler replaces the step of that name. |

Any change to the YAML format starts in `ikanos-schema.json`. The Jackson model, the ruleset, the examples and the tutorials follow, and the schema is the reference when they disagree.

## Tests

| Level | Where | Runs |
|---|---|---|
| Unit | `src/test/java` of each module, next to the class under test (`ResolverTest`, `ConverterTest`, ...) | `mvn test`, on every PR |
| Integration | Classes named `*IntegrationTest`, mostly in `ikanos-engine`. They load a capability YAML (fixtures in `src/test/resources`, or inline with the version from `VersionHelper`), build a `Capability`, and call it through an adapter, often against a local Restlet stub of the upstream. | `mvn test`, on every PR |
| Tutorial | The tutorial capabilities, from the engine's copy in `src/test/resources/tutorial`: the `io.ikanos.tutorial` tests call them as an MCP or REST client, and `validate-tuto-examples` serves each one with the CLI jar against Microcks mocks | `mvn test` on every PR; the workflow on PRs that change main code or a `pom.xml` |
| End to end | `.github/e2e/resources/features/<feature>/`: a `capability.yml` and a `run.sh` each, run against the Docker image with Microcks and Keycloak | `e2e-feature-tests`, nightly and on demand |

Coverage is measured by JaCoCo and aggregated across modules by `ikanos-coverage`. `mvn verify`, which the `quality-gate` workflow runs, fails below 75 % line and 65 % branch coverage. The test-writing rules (naming, unit vs integration, the bug workflow) are in `AGENTS.md`.

## Invariants and design decisions

These hold across the codebase. Breaking one needs a deliberate decision, not a side effect.

- **The capability is the whole program.** Everything a capability does is declared in its YAML. The engine adds no behaviour the YAML cannot see, apart from the engine-defined control port.
- **The runtime does not validate the schema.** `serve` reads the YAML leniently and ignores unknown properties. Validation is a separate step (see [Spec and validation](#spec-and-validation)). Errors the runtime cannot work around, such as an unknown `ref` or a missing exposed adapter, still fail at load time.
- **Load-time over request-time.** Imports, refs and adapter configuration are resolved and checked once at startup. Request handling assumes a consistent spec.
- **Clients start before servers, and servers stop before clients.**
- **JSON inside, other formats at the edge.** Conversion happens when data enters (`Converter`) and the protocol shape is built when it leaves. Binary is passed through, never decoded.
- **Secrets stay in authentication.** The resolved `binds` are only used for authentication, on both sides: the credentials a server adapter checks (`ServerAdapter`, `ServerAuthenticationRestlet`) and the credentials a client adapter sends (`HttpClientAdapter`), plus the tunnel identity. They are not merged into the parameters used for URLs, bodies and steps.
- **No internal detail in error responses.** Unexpected failures return a generic message and a reference id. The detail goes to the server log under that id (`ErrorReference`).
- **Transport-agnostic protocol code.** MCP protocol logic lives in `ProtocolDispatcher` and the handlers, shared by HTTP and stdio. Transports only translate I/O.
- **MCP 2026-07-28 only.** There is no `initialize` handshake and no fallback to older protocol versions. A legacy `initialize` is answered with an explicit error, never served.
- **Telemetry is always on in code, optional at runtime.** Code always creates spans and metrics; a no-op instance makes them free when telemetry is off.
- **Factor by default.** Mechanisms that recur across adapters (Mustache resolution, JSONPath extraction, authentication, header handling) have one shared implementation. Fix a defect in every place that shares the mechanism, not only where it was reported (see `AGENTS.md`). The audit in [#667](https://github.com/naftiko/ikanos/issues/667) looks for the places that still diverge.
- **Thread safety for a future hot reload.** `Capability` and the adapters hold their mutable state in `AtomicReference`s and copy-on-write lists, so a new spec could be swapped in while requests are running.

## Where to start reading

| To understand... | Read |
|---|---|
| Startup | `CapabilityRuntime.serve`, then the `Capability` constructor |
| An MCP request | `McpServerResource.handlePost`, `ProtocolDispatcher.dispatch`, `ToolHandler.doHandleToolCall` |
| A REST request | `ResourceRestlet.handle` |
| How a call is built and sent | `OperationStepExecutor.findClientRequestFor` and `HandlingContext.handle` |
| Steps | `OperationStepExecutor.executeSteps` |
| Templates and mappings | `Resolver` |
| The YAML format | `modules/ikanos-spec/src/main/resources/schemas/ikanos-schema.json` and the examples next to it |
| Contribution rules and conventions | `CONTRIBUTING.md` and `AGENTS.md` |
