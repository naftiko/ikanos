Welcome to the Naftiko Framework FAQ! This guide answers common questions from developers who are learning, using, and contributing to Naftiko. For comprehensive technical details, see the [Specification - Schema](https://github.com/naftiko/framework/wiki/Specification-Schema) and [Specification - Rules](https://github.com/naftiko/framework/wiki/Specification-Rules).

---

## ⛵ Getting Started

### Q: What is Naftiko Framework and why would I use it?
**A:** Naftiko Framework is the first open-source platform for **Spec-Driven Integration**. Instead of writing boilerplate code to consume HTTP APIs and expose unified interfaces, you declare them in YAML. This enables:
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

### Q: Is Naftiko a code generator or a runtime engine?
**A:** It's a **runtime engine**. The Naftiko Engine, provided as a Docker container, reads your YAML capability file at startup and immediately exposes HTTP or MCP interfaces. There's no compilation step - declare your capability, start the engine, and it works.

---

## :rowboat: Installation & Setup

### Q: How do I install Naftiko?
**A:** There are two ways:

1. **Docker (recommended)**  
   ```bash
  docker pull ghcr.io/naftiko/framework:v1.0.0-alpha1
  docker run -p 8081:8081 -v /path/to/capability.yaml:/app/capability.yaml ghcr.io/naftiko/framework:v1.0.0-alpha1 /app/capability.yaml
   ```

2. **CLI tool** (for configuration and validation)  
  Download the binary for [macOS](https://github.com/naftiko/framework/releases/download/v1.0.0-alpha1/naftiko-cli-macos-arm64), [Linux](https://github.com/naftiko/framework/releases/download/v1.0.0-alpha1/naftiko-cli-linux-amd64), or [Windows](https://github.com/naftiko/framework/releases/download/v1.0.0-alpha1/naftiko-cli-windows-amd64.exe)

See the [Installation guide](https://github.com/naftiko/framework/wiki/Installation) for detailed setup instructions.

### Q: How do I validate my capability file before running it?
**A:** Use the CLI validation command:
```bash
naftiko validate path/to/capability.yaml
naftiko validate path/to/capability.yaml 1.0.0-alpha1  # Specify schema version
```

This checks your YAML against the Naftiko schema and reports any errors.

### Q: Which version of the schema should I use?
**A:** Use the current framework schema version: **1.0.0-alpha1**.

Set it in your YAML:
```yaml
naftiko: "1.0.0-alpha1"
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

Mappings tell Naftiko how to wire step outputs to your final response.

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

---

## 🔭 Troubleshooting & Debugging

### Q: My capability won't start. How do I debug it?
**A:** 

1. **Validate your YAML first:**
   ```bash
   naftiko validate capability.yaml
   ```

2. **Check the Docker logs:**
   ```bash
  docker run ... ghcr.io/naftiko/framework:v1.0.0-alpha1 /app/capability.yaml
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

### Q: How do I contribute to Naftiko Framework?
**A:** We welcome all contributions! Here's how:

1. **Report bugs or request features** - [GitHub Issues](https://github.com/naftiko/framework/issues)
   - Search for existing issues first to avoid duplicates
   
2. **Submit code changes** - [GitHub Pull Requests](https://github.com/naftiko/framework/pulls)
   - Create a local branch
   - Ensure your code passes all build validation
   - Rebase on `main` before submitting

3. **Contribute examples** - Add capability examples to the repository
   - Document your use case in the example
   - Include comments explaining key features

4. **Improve documentation** - Fix typos, clarify docs, add examples

### Q: What's the code structure and how do I set up a development environment?
**A:** Naftiko is a **Java project** using Maven. To build and develop:

```bash
# Clone the repository
git clone https://github.com/naftiko/framework.git
cd framework

# Build the project
mvn clean install

# Run tests
mvn test

# Build Docker image
docker build -t naftiko:local .
```

Key directories:
- `src/main/java/io/naftiko/`  Core engine code
- `src/main/resources/schemas/`  JSON Schema definitions
- `src/test/`  Unit and integration tests
- `src/main/resources/schemas/examples/`  Capability examples
- `src/main/resources/tutorial/`  Tutorial capability files

### Q: What are the design guidelines for creating capabilities?
**A:**

1. **Keep the Naftiko Specification as a first-class citizen** - refer to it often
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
3. **Validation** - Use the CLI tool: `naftiko validate capability.yaml`
4. **Docker testing** - Build and run the Docker image with your capability

### Q: Which version of Java is required?
**A:** Naftiko requires **Java 21 or later**. This is specified in the Maven configuration.

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
**A:** Naftiko currently doesn't have built-in retry logic in v1.0.0-alpha1. Options:

1. **At the HTTP client level** - use an API gateway with retry policies
2. **In future versions** - this is on the roadmap

Check the [Roadmap](https://github.com/naftiko/framework/wiki/Roadmap) for planned features.

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

### Q: How scalable is Naftiko for high-load scenarios?
**A:** Naftiko is suitable for moderate to high loads depending on:
- **Your consumed APIs' performance** - Naftiko's overhead is minimal
- **Docker/Kubernetes scaling** - deploy multiple instances behind a load balancer
- **Orchestration complexity** - simpler capabilities (forward, single calls) are faster

For production workloads:
- Use Kubernetes for auto-scaling
- Monitor consuming/consumed API latencies
- Consider caching strategies above Naftiko

### Q: How do I deploy Naftiko to production?
**A:** 

1. **Kubernetes** (recommended):
   ```yaml
   apiVersion: apps/v1
   kind: Deployment
   metadata:
     name: naftiko-engine
   spec:
     replicas: 3
     template:
       spec:
         containers:
         - name: naftiko
           image: ghcr.io/naftiko/framework:v1.0.0-alpha1
           volumeMounts:
           - name: capability
             mountPath: /app/capability.yaml
             subPath: capability.yaml
           env:
           - name: GITHUB_TOKEN
             valueFrom:
               secretKeyRef:
                 name: naftiko-secrets
                 key: github-token
   ```

2. **Docker Compose** - for simpler setups
3. **Environment Variables** - inject secrets via `binds` (omit `location` for runtime injection)

### Q: Can I use Naftiko behind a reverse proxy (nginx, Envoy)?
**A:** Yes, absolutely. Naftiko exposes standard HTTP endpoints, so it works with any reverse proxy.

Example (nginx):
```nginx
server {
    listen 80;
    location / {
        proxy_pass http://naftiko:8081;
    }
}
```

---

## 📜 Specifications & Standards

### Q: How does Naftiko compare to OpenAPI, AsyncAPI, or Arazzo?
**A:** Naftiko is **complementary** to these specifications and combines their strengths into a single runtime model:
- **Consume/expose duality** - like OpenAPI's interface description, but bidirectional
- **Orchestration** - like Arazzo's workflow sequencing
- **AI-driven discovery** - beyond what all three cover natively
- **Namespace-based routing** - unique to Naftiko's runtime approach

See the [Spec-Driven Integration](https://github.com/naftiko/framework/wiki/Spec-Driven-Integration) overview and the [Specification - Schema](https://github.com/naftiko/framework/wiki/Specification-Schema) for the formal model.

### Q: Is the Naftiko Specification stable?
**A:** The current public version is **1.0.0-alpha1**. Because this is an alpha release, minor schema adjustments can still happen before stable 1.0.0. The specification follows semantic versioning:
- **Major versions** (1.x.x) - breaking changes
- **Minor versions** (x.1.0) - new features, backward-compatible
- **Patch versions** (x.x.1) - bug fixes

Check the naftiko field in your YAML to specify the version.

---

## 📣 Community & Support

### Q: Where can I ask questions or discuss ideas?
**A:** Join the community at:
- **[GitHub Discussions](https://github.com/orgs/naftiko/discussions)** - Ask questions and share ideas
- **[GitHub Issues](https://github.com/naftiko/framework/issues)** - Report bugs or request features
- **Pull Requests** - Review and discuss code changes

### Q: Are there examples I can reference?
**A:** Yes! Several resources:

- **[Tutorial - Part 1](https://github.com/naftiko/framework/wiki/Tutorial-MCP-Part-1)** - MCP foundations and step-by-step guide
- **[Tutorial - Part 2](https://github.com/naftiko/framework/wiki/Tutorial-MCP-Part-2)** - Agent Skills, REST, and fleet manifest
- **[Use Cases](https://github.com/naftiko/framework/wiki/Guide-Use-Cases)** - Real-world examples
- **Repository examples** - In `src/main/resources/schemas/examples/`, `src/main/resources/tutorial/`, and test resources
- **Specification examples** - In the [Specification - Schema](https://github.com/naftiko/framework/wiki/Specification-Schema#4-complete-examples) (Section 4)

### Q: How often is Naftiko updated?
**A:** Check the [Releases](https://github.com/naftiko/framework/wiki/Releases) page for version history. The project follows a regular release cadence with security updates prioritized.

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
3. **Use stdio transport** - for native Claude Desktop integration
4. **Test with Claude** - configure Claude Desktop with your MCP server
5. **Publish** - share your capability spec with the community

See [Tutorial - Part 1](https://github.com/naftiko/framework/wiki/Tutorial-MCP-Part-1) for a full MCP example, then continue with [Tutorial - Part 2](https://github.com/naftiko/framework/wiki/Tutorial-MCP-Part-2) for Skill and REST exposure.

### Q: I want to standardize data from multiple SaaS tools. How do I use Naftiko?
**A:**

1. **Consume multiple SaaS APIs** - define each in `consumes`
2. **Normalize outputs** - use `outputParameters` to extract and structure data consistently
3. **Expose unified interface** - create a single API with harmonized formats
4. **Use orchestration** - combine data from multiple sources if needed

This is Naftiko's core strength for managing API sprawl.

---

## 🏝️ Additional Resources

-  **[Installation](https://github.com/naftiko/framework/wiki/Installation)** - Setup instructions
-  **[Spec-Driven Integration](https://github.com/naftiko/framework/wiki/Spec-Driven-Integration)** - Methodology overview
-  **[Tutorial - Part 1](https://github.com/naftiko/framework/wiki/Tutorial-MCP-Part-1)** - MCP foundations
-  **[Tutorial - Part 2](https://github.com/naftiko/framework/wiki/Tutorial-MCP-Part-2)** - Skills, REST, and fleet manifest
-  **[Guide - Use Cases](https://github.com/naftiko/framework/wiki/Guide-Use-Cases)** - Real-world examples
-  **[Guide - Linting](https://github.com/naftiko/framework/wiki/Guide-Linting)** - Validation workflow and CLI usage
-  **[Specification - Schema](https://github.com/naftiko/framework/wiki/Specification-Schema)** - Complete technical reference
-  **[Specification - Rules](https://github.com/naftiko/framework/wiki/Specification-Rules)** - Validation and linting rules
-  **[Releases](https://github.com/naftiko/framework/wiki/Releases)** - Version history
-  **[Roadmap](https://github.com/naftiko/framework/wiki/Roadmap)** - Future plans
-  **[Contribute](https://github.com/naftiko/framework/wiki/Contribute)** - Become a contributor
-  **[Discussions](https://github.com/orgs/naftiko/discussions)** - Community Q&A

---

## 🔔 Feedback

Did this FAQ help you? Have questions not covered here? 
- **Add an issue** - [GitHub Issues](https://github.com/naftiko/framework/issues)
- **Start a discussion** - [GitHub Discussions](https://github.com/orgs/naftiko/discussions)
- **Submit a PR** - Help us improve this FAQ!