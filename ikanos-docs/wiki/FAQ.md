Welcome to the Ikanos FAQ! This guide answers common questions from developers who are learning, using, and contributing to Ikanos. For comprehensive technical details, see the [Specification - Schema](https://github.com/naftiko/ikanos/wiki/Specification-Schema) and [Specification - Rules](https://github.com/naftiko/ikanos/wiki/Specification-Rules).

---

## ⛵ Getting Started

### Q: What is Ikanos and why would I use it?
**A:** Ikanos is the first open-source platform for **Spec-Driven Integration**. Instead of writing boilerplate code to consume HTTP APIs and expose unified interfaces, you declare them in YAML. This enables:
- **API composability**: Combine multiple APIs into a single capability
- **Format conversion**: Convert between JSON, XML, Avro, Protobuf, CSV, TSV, PSV, HTML, Markdown, and YAML
- **AI-ready integration**: Better context engineering for AI systems
- **Reduced API sprawl**: Manage microservices and SaaS complexity

Use it when you need to integrate multiple APIs, standardize data formats, or expose simplified interfaces to AI agents.

### Q: What skills do I need to create a capability?
**A:** You only need to know:
- **YAML syntax** - the configuration language for capabilities
- **JSONPath** - for extracting values from JSON responses
- **Mustache templates** - for injecting parameters (optional, if using advanced features)

You don't need to write Java or other code unless you want to extend the framework itself.

### Q: Is Ikanos a code generator or a runtime engine?
**A:** It's a **runtime engine**. The Ikanos Engine, provided as a Docker container, reads your YAML capability file at startup and immediately exposes HTTP or MCP interfaces. There's no compilation step - declare your capability, start the engine, and it works.

### Q: Are there other tools that complement Ikanos?
**A:** Yes. Ikanos is part of [Naftiko Fleet (Community Edition)](https://github.com/naftiko/fleet), which includes free complementary tools:

- **[Naftiko Extension for VS Code](https://github.com/naftiko/fleet/wiki/Naftiko-Extension-for-VS-Code)** — Inline structure and rules validation while editing capability files (`.naftiko.yaml`)
- **[Naftiko Templates for Backstage](https://github.com/naftiko/fleet/wiki/Naftiko-Templates-for-Backstage)** — Scaffold new capabilities and catalog them from CNCF Backstage
- **Naftiko Operator for Kubernetes** — Deploy and operate capabilities on Kubernetes *(coming soon)*

---

## :rowboat: Installation & Setup

### Q: How do I install Ikanos?
**A:** There are two ways:

1. **Docker (recommended)**  
   ```bash
  docker pull ghcr.io/naftiko/ikanos:v1.0.0-alpha1
  docker run -p 8081:8081 -v /path/to/capability.yaml:/app/capability.yaml ghcr.io/naftiko/ikanos:v1.0.0-alpha1 /app/capability.yaml
   ```

2. **CLI tool** (for configuration and validation)  
  Download the binary for [macOS](https://github.com/naftiko/ikanos/releases/download/v1.0.0-alpha1/ikanos-cli-macos-arm64), [Linux](https://github.com/naftiko/ikanos/releases/download/v1.0.0-alpha1/ikanos-cli-linux-amd64), or [Windows](https://github.com/naftiko/ikanos/releases/download/v1.0.0-alpha1/ikanos-cli-windows-amd64.exe)

See the [Installation guide](https://github.com/naftiko/ikanos/wiki/Installation) for detailed setup instructions.

### Q: How do I validate my capability file before running it?
**A:** Use the CLI validation command:
```bash
ikanos validate path/to/capability.yaml
ikanos validate path/to/capability.yaml 1.0.0-alpha1  # Specify schema version
```

This checks your YAML against the Ikanos schema and reports any errors.

### Q: Which version of the schema should I use?
**A:** Use the current framework schema version: **1.0.0-alpha1**.

Set it in your YAML:
```yaml
ikanos: "1.0.0-alpha1"
```

---

## 🧭 Core Concepts

### Q: What are "exposes" and "consumes"?
**A:** These are the two essential parts of every capability:

- **Exposes** - What your capability *provides* to callers (REST, MCP or SKILL server)
- **Consumes** - What HTTP APIs your capability *uses internally*

Example: A capability that consumes the Notion API and GitHub API, then exposes them as a single unified REST endpoint or MCP tool.

### Q: What's the difference between REST and MCP exposure?
**A:** 

| Feature | REST | MCP |
|---------|-----------|-----|
| **Protocol** | REpresentational State Transfer (JSON-HTTP) | Model Context Protocol (JSON-RPC) |
| **Best for** | General-purpose integrations, web apps | AI agent-native integrations |
| **Tool discovery** | Manual or via OpenAPI | Automatic via MCP protocol |
| **Configuration** | `type: "rest"` with resources/operations | `type: "mcp"` with tools |
| **Default transport** | HTTP | stdio or HTTP (streamable) |

**Use REST** for traditional REST clients, web applications, or when you want standard HTTP semantics.  
**Use MCP** when exposing capabilities to AI agents or Claude.

### Q: What is a "namespace"?
**A:** A namespace is a **unique identifier** for a consumed or exposed source, used for routing and references.

- **In consumes**: `namespace: github` means "this is the GitHub API I'm consuming"
- **In exposes**: `namespace: app` means "my exposed API is called `app`"
- **In steps**: `call: github.get-user` routes to the consumed `github` namespace

Namespaces must be unique within their scope (all consumed namespaces must differ, all exposed namespaces must differ).

### Q: What are "steps" and when do I use them?
**A:** Steps enable **multi-step orchestration** - calling multiple APIs in sequence and combining their results.

**Simple mode** (direct call):
```yaml
operations:
  - method: GET
    call: github.get-user      # Call one consumed operation directly
    with:
      username: "{{github_username}}"
```

**Orchestrated mode** (multi-step):
```yaml
operations:
  - name: complex-operation
    method: GET
    steps:
      - type: call
        name: step1
        call: github.list-users
      - type: call
        name: step2
        call: github.get-user
        with:
          username: $step1.result  # Use output from step1
    mappings:
      - targetName: output_field
        value: $.step2.userId
```

Use steps when your capability needs to combine data from multiple sources or perform lookups.

### Q: What's the difference between "call" and "lookup" steps?
**A:** 

- **`call` steps**: Execute a consumed operation (HTTP request)
- **`lookup` steps**: Search through a previous step's output for matching records

Example: Call an API to list all users, then lookup which one matches a given email:
```yaml
steps:
  - type: call
    name: list-all-users
    call: hr.list-employees
  - type: lookup
    name: find-user-by-email
    index: list-all-users
    match: email
    lookupValue: "{{email_to_find}}"
    outputParameters:
      - fullName
      - department
```

---

## 🧱 Aggregates & Reuse (DDD-inspired)

### Q: What are aggregates and why should I use them?
**A:** Aggregates are **domain-centric building blocks** inspired by [Domain-Driven Design (DDD)](https://en.wikipedia.org/wiki/Domain-driven_design#Building_blocks). An aggregate groups reusable **functions** under a namespace that represents a coherent domain concept — similar to how a DDD Aggregate Root encapsulates a cluster of related entities.

Use aggregates when:
- The **same domain operation** (e.g., "get forecast") is exposed through multiple adapters (REST *and* MCP).
- You want to maintain a **single source of truth** for function definitions (name, description, call chain, parameters).
- You want **transport-neutral behavioral metadata** (semantics) that auto-maps to adapter-specific features.

```yaml
capability:
  aggregates:
    - label: Weather Forecast
      namespace: forecast
      functions:
        - name: get-forecast
          description: Retrieve weather forecast for a city
          semantics:
            safe: true
            idempotent: true
          call: weather-api.get-forecast
          inputParameters:
            - name: city
              type: string
              description: City name
          outputParameters:
            - type: object
              mapping: $.forecast
```

### Q: How does `ref` work to reference an aggregate function?
**A:** MCP tools and REST operations can reference an aggregate function using `ref: {namespace}.{function-name}`. The engine merges inherited fields from the function — you only specify what's different at the adapter level.

```yaml
exposes:
  - type: mcp
    tools:
      - ref: forecast.get-forecast      # Inherits name, description, call, params
      - ref: forecast.get-forecast       # Override description for MCP context
        name: weather-lookup
        description: Look up weather for a city (MCP-optimized)
  - type: rest
    resources:
      - path: /forecast
        operations:
          - method: GET
            ref: forecast.get-forecast   # Same function, REST adapter
```

**Merge rules:**
- Explicit fields on the tool/operation **override** inherited fields from the function.
- Fields not set on the tool/operation are **inherited** from the function.
- `name` and `description` are optional when using `ref` — they default to the function's values.

### Q: How do semantics map to MCP tool hints?
**A:** Aggregate functions can declare transport-neutral **semantics** (`safe`, `idempotent`, `cacheable`). When exposed as MCP tools, the engine automatically derives [MCP tool hints](https://modelcontextprotocol.io/specification/2025-06-18/server/tools#tool-annotations):

| Semantics | MCP Hint | Rule |
|-----------|----------|------|
| `safe: true` | `readOnly: true`, `destructive: false` | Safe operations don't modify state |
| `safe: false` | `readOnly: false`, `destructive: true` | Unsafe operations may modify state |
| `idempotent` | `idempotent` | Passed through directly |
| `cacheable` | *(not mapped)* | No MCP equivalent |
| *(not derived)* | `openWorld` | Must be set explicitly on the MCP tool |

Explicit hints on the MCP tool **override** derived values, so you can fine-tune behavior per-tool.

---

## 🔩 Configuration & Parameters

### Q: How do I inject input parameters into a consumed operation?
**A:** Use the `with` injector in your exposed operation:

**Simple mode:**
```yaml
operations:
  - method: GET
    call: github.get-user
    with:
      username: "{{github_username}}"   # From binds
      accept: "application/json"        # Static value
```

**Orchestrated mode (steps):**
```yaml
steps:
  - type: call
    name: fetch-user
    call: github.get-user
    with:
      username: "{{github_username}}"
```

The `with` object maps consumed operation parameter names to:
- Variable references like `{{VARIABLE_NAME}}`  injected from binds
- Static strings or numbers - literal values

### Q: How do I extract values from API responses (output parameters)?
**A:** Use **JsonPath expressions** in the `value` field of `outputParameters`:

```yaml
consumes:
  - resources:
      - operations:
          - outputParameters:
              - name: userId
                type: string
                value: $.id                      # Top-level field
              - name: email
                type: string
                value: $.contact.email           # Nested field
              - name: allNames
                type: array
                value: $.users[*].name           # Array extraction
```

Common JsonPath patterns:
- `$.fieldName` - access a field
- `$.users[0].name` - access array element
- `$.users[*].name` - extract all `.name` values from array
- `$.data.user.email` - nested path

### Q: What are "mappings" and when do I use them?
**A:** Mappings connect step outputs to exposed operation outputs in multi-step orchestrations.

```yaml
steps:
  - type: call
    name: fetch-db
    call: notion.get-database
  - type: call
    name: query-db
    call: notion.query-database

mappings:
  - targetName: database_name      # Exposed output parameter
    value: $.fetch-db.dbName       # From first step's output
  - targetName: row_count
    value: $.query-db.resultCount  # From second step

outputParameters:
  - name: database_name
    type: string
  - name: row_count
    type: number
```

Mappings tell Ikanos how to wire step outputs to your final response.

---

## 🔄 OpenAPI Interoperability

### Q: Can I bootstrap a capability from an existing OpenAPI specification?
**A:** Yes. Use the CLI import command:
```bash
ikanos import openapi petstore.yaml
ikanos import openapi petstore.yaml -o my-capability.yaml
```
This parses an OAS 3.0 or 3.1 document and generates a Ikanos capability YAML with a pre-filled `consumes` HTTP adapter — including authentication, resources, operations, input parameters, and output parameters.

### Q: Can I export my REST adapter as an OpenAPI document?
**A:** Yes. Use the CLI export command:
```bash
ikanos export openapi capability.yaml
ikanos export openapi capability.yaml --spec-version 3.1 -f json
```
This reads a Ikanos capability and generates an OpenAPI document from its REST `exposes` adapter.

### Q: Which OpenAPI versions are supported?
**A:** OAS **3.0** and **3.1** are fully supported for both import and export. OAS 3.2 support is deferred until the upstream Java libraries (`swagger-parser`, `swagger-core`) add it.

### Q: What if my capability has multiple REST adapters?
**A:** Use the `--adapter` option to target a specific namespace:
```bash
ikanos export openapi capability.yaml --adapter public-api
```
When omitted, the first REST adapter found is exported.

---

## 🗝️ Authentication & Security

### Q: How do I authenticate to external APIs?
**A:** Add an `authentication` block to your `consumes` section:

```yaml
consumes:
  - type: http
    namespace: github
    baseUri: https://api.github.com
    authentication:
      type: bearer
      token: "{{GITHUB_TOKEN}}"      # Use token from binds
```

**Supported authentication types:**
- `bearer` - Bearer token
- `basic` - Username/password
- `apikey` - Header or query parameter API key
- `digest` - HTTP Digest authentication

### Q: How do I manage secrets like API tokens?
**A:** Use **binds** to declare variables that are injected at runtime:

```yaml
binds:
  - namespace: secrets
    keys:
      GITHUB_TOKEN: GITHUB_TOKEN      # Maps env var to template variable
      NOTION_TOKEN: NOTION_TOKEN

consumes:
  - namespace: github
    authentication:
      type: bearer
      token: "{{GITHUB_TOKEN}}"       # Use the injected variable
  - namespace: notion
    authentication:
      type: bearer
      token: "{{NOTION_TOKEN}}"
```

**At runtime, provide environment variables:**
```bash
docker run -e GITHUB_TOKEN=ghp_xxx -e NOTION_TOKEN=secret_xxx ...
```

> ⚠️ **Security note**: Use runtime injection (omit `location`) in production. Never commit secrets to your repository.

### Q: Can I authenticate to exposed endpoints (REST/MCP)?
**A:** Yes, add `authentication` to your `exposes` block:

```yaml
exposes:
  - type: rest
    port: 8081
    namespace: my-api
    authentication:
      type: apikey
      in: header
      name: X-Api-Key
      value: "{{api_key}}"
    resources:
      - path: /data
        description: Protected data endpoint
```

**Supported authentication types for exposed endpoints:**
- `apikey` - API key via header or query parameter
- `bearer` - Bearer token validation
- `basic` - Username/password via HTTP Basic Auth

### Q: Can I send complex request bodies (JSON, XML, etc.)?
**A:** Yes, use the `body` field for request bodies:

```yaml
consumes:
  - resources:
      - operations:
          - method: POST
            body:
              type: json
              data:
                filter:
                  status: "active"
```

**Body types:**
- `json` - JSON object or string
- `text`, `xml`, `sparql` - Plain text payloads
- `formUrlEncoded` - URL-encoded form
- `multipartForm` - Multipart file upload

---

## 🗺️ REST API Design

### Q: How do I define resource paths with parameters?
**A:** Use path parameters with curly braces:

```yaml
exposes:
  - resources:
      - path: /users/{userId}/projects/{projectId}
        description: Get a specific project for a user
        inputParameters:
          - name: userId
            in: path
            type: string
            description: The user ID
          - name: projectId
            in: path
            type: string
            description: The project ID
```

Callers access it as: `GET /users/123/projects/456`

### Q: How do I support query parameters and headers?
**A:** Use `in` field in `inputParameters`:

```yaml
inputParameters:
  - name: filter
    in: query
    type: string
    description: Filter results
  - name: Authorization
    in: header
    type: string
    description: Auth header
  - name: X-Custom
    in: header
    type: string
    description: Custom header
```

Callers send: `GET /endpoint?filter=value` with custom headers.

### Q: How do forward proxies work?
**A:** Use `forward` to pass requests through to a consumed API without transformation:

```yaml
exposes:
  - resources:
      - path: /github/{path}
        description: Pass-through proxy to GitHub API
        forward:
          targetNamespace: github
          trustedHeaders:
            - Authorization
            - Accept
```

This forwards `GET /github/repos/owner/name` to GitHub's `/repos/owner/name`.

**Trusted headers** must be explicitly listed for security.

---

## 📡 MCP-Specific

### Q: How do I expose a capability as an MCP tool?
**A:** Use `type: mcp` in exposes instead of `type: rest`:

```yaml
exposes:
  - type: mcp
    address: localhost
    port: 9091
    namespace: my-mcp
    description: My MCP server
    tools:
      - name: query-database
        description: Query the database
        call: notion.query-db
        with:
          db_id: "fixed-db-id"
        outputParameters:
          - type: array
            mapping: $.results
```

### Q: What's the difference between HTTP and Stdio MCP transports?
**A:** 

| Transport | Use Case | Setup |
|-----------|----------|-------|
| **HTTP** | Streamable HTTP transport, integrates with existing infrastructure | Specify `address` and `port` |
| **Stdio** | Direct process communication, native integration with Claude Desktop | No address/port needed |

For Claude integration, Stdio is typically preferred. HTTP is useful for remote or containerized deployments.

### Q: How do I expose MCP resources and prompts?
**A:** Add `resources` and `prompts` sections to your MCP server:

```yaml
exposes:
  - type: mcp
    resources:
      - uri: file:///docs/guide.md
        name: User Guide
        description: API usage guide
    prompts:
      - name: analyze-code
        description: Analyze code snippet
        template: "Analyze this code:\n{{code}}"
```

MCP clients can then discover and use these resources dynamically.

### Q: Can I create MCP tools that return static mock data?
**A:** Yes, starting in version 1.0.0 Alpha 2. Define `outputParameters` with `value` fields and omit `call` and `steps`. The tool serves a fixed JSON response — no `consumes` block is needed. Values can be static strings or Mustache templates resolved against input parameters:

```yaml
exposes:
  - type: mcp
    port: 3001
    namespace: mock-tools
    description: Mock MCP server
    tools:
      - name: say-hello
        description: Returns a greeting
        inputParameters:
          - name: name
            type: string
            required: true
            description: Name to greet
        outputParameters:
          - name: message
            type: string
            value: "Hello, {{name}}!"
```

This mirrors the REST mock pattern (`no-adapter.yml`) and is useful for prototyping, demos, and contract-first development.

---

## ⚙️ Control Port & Observability

### Q: What is the control port?
**A:** The control port is a built-in management plane (`type: "control"` in `capability.exposes`). It provides engine-provided endpoints for health checks, Prometheus metrics, distributed traces, runtime status, configuration reload, log level control, and log streaming — without writing any code.

### Q: How do I enable the control port?
**A:** Add a control adapter to your capability's `exposes` array:

```yaml
capability:
  exposes:
    - type: control
      port: 9090
      management:
        health: true
      observability:
        traces:
          local:
            buffer-size: 200
```

At most one control adapter is allowed per capability, and its port must not collide with any business adapter port.

### Q: What endpoints does the control port provide?
**A:**

| Endpoint | Default | Description |
|---|---|---|
| `/health/live`, `/health/ready` | Enabled | Liveness and readiness probes |
| `/metrics` | Enabled | Prometheus scrape endpoint (requires observability) |
| `/traces` | Enabled | Recent trace summaries (requires observability) |
| `/status`, `/config` | Disabled | Runtime status and loaded configuration |
| `POST /config/reload` | Disabled | Hot-reload the capability |
| `POST /config/validate` | Disabled | Dry-run validation |
| `/logs` | Disabled | Log level control |
| `/logs/stream` | Disabled | SSE log streaming |

### Q: How do I enable OpenTelemetry observability?
**A:** Add an `observability` block on the control adapter. Observability is enabled by default — you only need to set the fields you want to customize:

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

This enables distributed tracing and RED metrics (Rate, Errors, Duration) for all capability operations. Metrics are exposed in Prometheus format on the control port's `/metrics` endpoint.

### Q: What metrics does Ikanos expose?
**A:** The engine emits three histogram metrics following the RED method:

- `ikanos.request.duration.seconds` — end-to-end request duration by tool/operation name and status
- `ikanos.step.duration.seconds` — individual orchestration step duration
- `ikanos.http.client.duration.seconds` — outbound HTTP call duration by namespace, method, and status code

A counter `ikanos.request.errors` tracks failed requests. All metrics are available in Prometheus text format on the control port's `/metrics` endpoint.

### Q: How do I connect Prometheus and Grafana?
**A:** Point Prometheus at the control port's `/metrics` endpoint:

```yaml
# prometheus.yml
scrape_configs:
  - job_name: ikanos
    metrics_path: /metrics
    static_configs:
      - targets: ['localhost:9090']
```

A sample Grafana dashboard is provided in `demo/shared/observability/grafana-ikanos.json`.

---

## 🔭 Troubleshooting & Debugging

### Q: My capability won't start. How do I debug it?
**A:** 

1. **Validate your YAML first:**
   ```bash
   ikanos validate capability.yaml
   ```

2. **Check the Docker logs:**
   ```bash
  docker run ... ghcr.io/naftiko/ikanos:v1.0.0-alpha1 /app/capability.yaml
   # Look for error messages in the output
   ```

3. **Verify your file path** - if using Docker, ensure:
   - The volume mount is correct: `-v /absolute/path:/app/capability.yaml`
   - The file exists and is readable
   - For Docker on Windows/Mac, use proper path translation

4. **Check external services** - ensure:
   - APIs you're consuming are reachable
   - Network connectivity is available
   - Authentication credentials are correct

### Q: Requests to my exposed endpoint return errors. How do I debug?
**A:**

1. **Check the request format** - ensure headers, parameters, and body match your definition
2. **Verify consumed API availability** - test the underlying API directly
3. **Inspect JsonPath mappings** - ensure your extraction paths match the API response
4. **Use Docker logs** - see server-side error messages

### Q: JsonPath expressions aren't extracting the data I expect. How do I fix it?
**A:**

1. **Test your JsonPath** - use an online tool like [jsonpath.com](https://jsonpath.com)
2. **Inspect the actual response** - add an operation without filtering to see raw data
3. **Understand array syntax**:
   - `$.users[0]` - first element
   - `$.users[*]` - all elements (creates array output)
   - `$.users[*].name` - all names

4. **For nested objects**, trace the path step-by-step: `$.data.user.profile.email`

### Q: My parameters aren't being passed to the consumed API. What's wrong?
**A:**

1. **Check parameter names match** - consumed parameter names must match keys in `with`
2. **Verify parameter location** (`in: path`, `in: query`, `in: header`, etc.)
3. **Check variable references** - ensure `{{VARIABLE_NAME}}` variables are defined in binds
4. **Test without transformation** - use `forward` to proxy the request and see if underlying API works

### Q: Authentication is failing. How do I debug it?
**A:**

1. **Test credentials directly** - verify your token/key works with the API
2. **Check token format** - ensure it's a valid token (not expired, wrong format, etc.)
3. **Verify placement** - is the token in the right header/query/body?
4. **Environment variables** - ensure the Docker environment variable matches the key name in `binds`
5. **Quotes** - make sure tokens with special characters are properly quoted in YAML

---

## 🚣 Contributing

### Q: How do I contribute to Ikanos?
**A:** We welcome all contributions! Here's how:

1. **Report bugs or request features** - [GitHub Issues](https://github.com/naftiko/ikanos/issues)
   - Search for existing issues first to avoid duplicates
   
2. **Submit code changes** - [GitHub Pull Requests](https://github.com/naftiko/ikanos/pulls)
   - Create a local branch
   - Ensure your code passes all build validation
   - Rebase on `main` before submitting

3. **Contribute examples** - Add capability examples to the repository
   - Document your use case in the example
   - Include comments explaining key features

4. **Improve documentation** - Fix typos, clarify docs, add examples

### Q: What's the code structure and how do I set up a development environment?
**A:** Ikanos is a **Java project** using Maven. To build and develop:

```bash
# Clone the repository
git clone https://github.com/naftiko/ikanos.git
cd ikanos

# Build the project
mvn clean install

# Run tests
mvn test

# Build Docker image
docker build -t ikanos:local .
```

Key directories:
- `ikanos-engine/src/main/java/io/ikanos/`  Core engine code
- `ikanos-spec/src/main/resources/schemas/`  JSON Schema definitions
- `ikanos-engine/src/test/` and `ikanos-cli/src/test/`  Unit and integration tests
- `ikanos-spec/src/main/resources/schemas/examples/`  Capability examples
- `ikanos-docs/tutorial/`  Tutorial capability files

### Q: What are the design guidelines for creating capabilities?
**A:**

1. **Keep the Ikanos Specification as a first-class citizen** - refer to it often
2. **Don't expose unused input parameters** - every parameter should be used in steps
3. **Don't declare consumed outputs you don't use** - be precise in mappings
4. **Don't prefix variables unnecessarily** - let scope provide clarity

Example:
```yaml
# Good: expose only used input
inputParameters:
  - name: database_id   # Used in step below
    in: path

# Bad: expose unused input
inputParameters:
  - name: database_id
  - name: unused_param   # Never used anywhere

# Good: output only consumed outputs you map
outputParameters:
  - name: result
    value: $.step1.output   # Clearly mapped

# Bad: declare outputs you don't use
outputParameters:
  - name: unused_result
    value: $.step1.unused
```

### Q: How do I test my capability changes?
**A:** 

1. **Unit tests** - Add tests in `src/test/java`
2. **Integration tests** - Test against real or mock APIs
3. **Validation** - Use the CLI tool: `ikanos validate capability.yaml`
4. **Docker testing** - Build and run the Docker image with your capability

### Q: Which version of Java is required?
**A:** Ikanos requires **Java 21 or later**. This is specified in the Maven configuration.

---

## ⛴️ Advanced Topics

### Q: Can I use templates/variables in my capability definition?
**A:** Yes, use **Mustache-style `{{variable}}`** expressions:

```yaml
binds:
  - namespace: env
    keys:
      API_KEY: API_KEY
      API_BASE_URL: API_BASE_URL

consumes:
  - baseUri: "{{API_BASE_URL}}"
    authentication:
      type: apikey
      key: X-API-Key
      value: "{{API_KEY}}"
```

Variables come from `binds` and are injected at runtime.


### Q: Can I compose capabilities (capability calling another capability)?
**A:** Indirectly - by referencing the exposed URL/port as a consumed API:

```yaml
# Capability B "consumes" the exposed endpoint from Capability A
consumes:
  - baseUri: http://localhost:8081   # Capability A's port
    namespace: capability-a
```

This way, Capability B can combine Capability A with other APIs.

### Q: How do I handle errors or retries?
**A:** Ikanos currently doesn't have built-in retry logic in v1.0.0-alpha1. Options:

1. **At the HTTP client level** - use an API gateway with retry policies
2. **In future versions** - this is on the roadmap

Check the [Roadmap](https://github.com/naftiko/ikanos/wiki/Roadmap) for planned features.

### Q: Can I expose the same capability on both REST and MCP?
**A:** Yes! Add multiple entries to `exposes`:

```yaml
exposes:
  - type: rest
    port: 8081
    namespace: rest-api
    resources: [...]
    
  - type: mcp
    port: 9091
    namespace: mcp-server
    tools: [...]

consumes: [...]  # Shared between both
```

Both adapters consume the same sources but expose different interfaces.

---

## 💨 Performance & Deployment

### Q: How scalable is Ikanos for high-load scenarios?
**A:** Ikanos is suitable for moderate to high loads depending on:
- **Your consumed APIs' performance** - Ikanos's overhead is minimal
- **Docker/Kubernetes scaling** - deploy multiple instances behind a load balancer
- **Orchestration complexity** - simpler capabilities (forward, single calls) are faster

For production workloads:
- Use Kubernetes for auto-scaling
- Monitor consuming/consumed API latencies
- Consider caching strategies above Ikanos

### Q: How do I deploy Ikanos to production?
**A:** 

1. **Kubernetes** (recommended):
   ```yaml
   apiVersion: apps/v1
   kind: Deployment
   metadata:
     name: ikanos-engine
   spec:
     replicas: 3
     template:
       spec:
         containers:
         - name: ikanos
           image: ghcr.io/naftiko/ikanos:v1.0.0-alpha1
           volumeMounts:
           - name: capability
             mountPath: /app/capability.yaml
             subPath: capability.yaml
           env:
           - name: GITHUB_TOKEN
             valueFrom:
               secretKeyRef:
                 name: ikanos-secrets
                 key: github-token
   ```

2. **Docker Compose** - for simpler setups
3. **Environment Variables** - inject secrets via `binds` (omit `location` for runtime injection)

### Q: Can I use Ikanos behind a reverse proxy (nginx, Envoy)?
**A:** Yes, absolutely. Ikanos exposes standard HTTP endpoints, so it works with any reverse proxy.

Example (nginx):
```nginx
server {
    listen 80;
    location / {
        proxy_pass http://Ikanos:8081;
    }
}
```

---

## 📜 Specifications & Standards

### Q: How does Ikanos compare to OpenAPI, AsyncAPI, or Arazzo?
**A:** Ikanos is **complementary** to these specifications and combines their strengths into a single runtime model:
- **Consume/expose duality** - like OpenAPI's interface description, but bidirectional
- **Orchestration** - like Arazzo's workflow sequencing
- **AI-driven discovery** - beyond what all three cover natively
- **Namespace-based routing** - unique to Ikanos's runtime approach

See the [Spec-Driven Integration](https://github.com/naftiko/ikanos/wiki/Spec-Driven-Integration) overview and the [Specification - Schema](https://github.com/naftiko/ikanos/wiki/Specification-Schema) for the formal model.

### Q: Is the Ikanos Specification stable?
**A:** The current public version is **1.0.0-alpha1**. Because this is an alpha release, minor schema adjustments can still happen before stable 1.0.0. The specification follows semantic versioning:
- **Major versions** (1.x.x) - breaking changes
- **Minor versions** (x.1.0) - new features, backward-compatible
- **Patch versions** (x.x.1) - bug fixes

Check the Ikanos field in your YAML to specify the version.

---

## 📣 Community & Support

### Q: Where can I ask questions or discuss ideas?
**A:** Join the community at:
- **[GitHub Discussions](https://github.com/orgs/naftiko/discussions)** - Ask questions and share ideas
- **[GitHub Issues](https://github.com/naftiko/ikanos/issues)** - Report bugs or request features
- **Pull Requests** - Review and discuss code changes

### Q: Are there examples I can reference?
**A:** Yes! Several resources:

- **[Tutorial - Part 1](https://github.com/naftiko/ikanos/wiki/Tutorial-MCP-Part-1)** - MCP foundations and step-by-step guide
- **[Tutorial - Part 2](https://github.com/naftiko/ikanos/wiki/Tutorial-MCP-Part-2)** - Agent Skills, REST, and fleet manifest
- **[Use Cases](https://github.com/naftiko/ikanos/wiki/Guide-Use-Cases)** - Real-world examples
- **Repository examples** - In `ikanos-spec/src/main/resources/schemas/examples/`, `ikanos-docs/tutorial/`, and test resources
- **Specification examples** - In the [Specification - Schema](https://github.com/naftiko/ikanos/wiki/Specification-Schema#4-complete-examples) (Section 4)

### Q: How often is Ikanos updated?
**A:** Check the [Releases](https://github.com/naftiko/ikanos/wiki/Releases) page for version history. The project follows a regular release cadence with security updates prioritized.

---

## 🚤 Common Use Cases

### Q: I want to create a unified REST API that combines Notion + GitHub. How do I start?
**A:** 

1. **Read the Tutorial** - particularly steps 1-6 for foundations and steps 7-10 for advanced needs
2. **Define consumed sources** - GitHub and Notion APIs with auth
3. **Design exposed resources** - endpoints that combine their data
4. **Use multi-step orchestration** - call both APIs and map results
5. **Test locally** - use Docker to run your capability

### Q: I want to expose my capability as an MCP tool for Claude. How do I do this?
**A:**

1. **Use `type: mcp`** in `exposes`
2. **Define `tools`** - each tool is an MCP tool your capability provides
3. **Add `hints`** (optional) - declare behavioral hints like `readOnly`, `destructive`, `idempotent`, `openWorld` to help clients understand tool safety
4. **Use stdio transport** - for native Claude Desktop integration
5. **Test with Claude** - configure Claude Desktop with your MCP server
6. **Publish** - share your capability spec with the community

See [Tutorial - Part 1](https://github.com/naftiko/ikanos/wiki/Tutorial-MCP-Part-1) for a full MCP example, then continue with [Tutorial - Part 2](https://github.com/naftiko/ikanos/wiki/Tutorial-MCP-Part-2) for Skill and REST exposure.

### Q: I want to standardize data from multiple SaaS tools. How do I use Ikanos?
**A:**

1. **Consume multiple SaaS APIs** - define each in `consumes`
2. **Normalize outputs** - use `outputParameters` to extract and structure data consistently
3. **Expose unified interface** - create a single API with harmonized formats
4. **Use orchestration** - combine data from multiple sources if needed

This is Ikanos's core strength for managing API sprawl.

---

## 🏝️ Additional Resources

-  **[Installation](https://github.com/naftiko/ikanos/wiki/Installation)** - Setup instructions
-  **[Spec-Driven Integration](https://github.com/naftiko/ikanos/wiki/Spec-Driven-Integration)** - Methodology overview
-  **[Tutorial - Part 1](https://github.com/naftiko/ikanos/wiki/Tutorial-MCP-Part-1)** - MCP foundations
-  **[Tutorial - Part 2](https://github.com/naftiko/ikanos/wiki/Tutorial-MCP-Part-2)** - Skills, REST, and fleet manifest
-  **[Guide - Use Cases](https://github.com/naftiko/ikanos/wiki/Guide-Use-Cases)** - Real-world examples
-  **[Guide - Linting](https://github.com/naftiko/ikanos/wiki/Guide-Linting)** - Validation workflow and CLI usage
-  **[Specification - Schema](https://github.com/naftiko/ikanos/wiki/Specification-Schema)** - Complete technical reference
-  **[Specification - Rules](https://github.com/naftiko/ikanos/wiki/Specification-Rules)** - Validation and linting rules
-  **[Releases](https://github.com/naftiko/ikanos/wiki/Releases)** - Version history
-  **[Roadmap](https://github.com/naftiko/ikanos/wiki/Roadmap)** - Future plans
-  **[Contribute](https://github.com/naftiko/ikanos/wiki/Contribute)** - Become a contributor
-  **[Discussions](https://github.com/orgs/naftiko/discussions)** - Community Q&A

---

## 🔔 Feedback

Did this FAQ help you? Have questions not covered here? 
- **Add an issue** - [GitHub Issues](https://github.com/naftiko/ikanos/issues)
- **Start a discussion** - [GitHub Discussions](https://github.com/orgs/naftiko/discussions)
- **Submit a PR** - Help us improve this FAQ!