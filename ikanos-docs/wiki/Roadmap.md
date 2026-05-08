## Version 1.0 - Third Alpha - End of May :deciduous_tree:

### Rightsize AI context
- [ ] Facilitate integration with MCP and AI gateways
  - [ ] MCP trust propagation

### Enable API reusability
- [ ] Increase HTTP client resiliency (retry, time limiter)
- [ ] Support HTTP cache control directives
- [ ] Enable pagination at consumes and exposes level
- [ ] Facilitate integration with API gateways
  - [ ] CORS configuration for API developer portals
  - [ ] Enable token refresh flows for consumed APIs
  - [ ] Gateway context propagation via Open Telemetry
  - [ ] mTLS client certificates on consumes

### Enable agent orchestration
- [ ] Deterministic orchestration
  - [ ] Add conditional steps, for-each steps, parallel-join
- [ ] Agentic with A2A server adapter with tool discovery and execution
  - [ ] Native integration with [Langchain4j](https://docs.langchain4j.dev/), see [issue #293](https://github.com/naftiko/ikanos/issues/293)

### Core developer experience
- [ ] Run capabilities simply from Ikanos's CLI (aka Docker-less mode)
- [ ] Use named objects for input and output parameters, like for properties, matching the JSON Structure syntax
- [ ] Externalize individual "exposes" objects into separate files, similar to "consumes" objects
- [ ] Expand support for "tags" and "labels" in Ikanos Spec
- [ ] Allow reuse of "binds" blocks across capabilities
- [ ] Complete test coverage and overall quality
- [ ] Complete the Javadoc descriptions, at package level in particular

### Packaging
- [ ] Publish Ikanos JSON Structure
- [ ] Publish Ikanos Skill based on Ikanos CLI
- [ ] Publish Ikanos ruleset based on Spectral

## Version 1.0 - First Beta - End of June :blossom:

The goal of this version is to deliver a stable MVP, including a stable Ikanos Specification.

### Rightsize AI context
- [ ] Enable interactive [MCP Apps](https://apps.extensions.modelcontextprotocol.io/)
- [ ] Evolve MCP server adapter to support [server-side code mode like CloudFlare](https://www.reddit.com/r/mcp/comments/1o1wdfh/do_you_think_code_mode_will_supercede_mcp/)
- [ ] Support dynamic [MCP tools search](https://code.claude.com/docs/en/agent-sdk/tool-search)
- [ ] Facilitate skills publication in skills marketplaces

### Enable API reusability
- [ ] Increase HTTP client resiliency (circuit breaker, rate limiter, bulkhead, cache, fallback)
- [ ] Add client SDKs generation to Ikanos CLI for top languages (TypeScript, Python, Java, Go)
  - [ ] Ensure it is extensible to bring your own client SDK generator (Apimatic, Fern, Stainless, Speakeasy, etc.)

### Core developer experience
- [ ] Provide additional governance rules to our initial set (error, warning, info, hint)
- [ ] Facilitate authorization management (scope declarations and enforcement, via [Open Policy Agent](https://www.openpolicyagent.org/))
- [ ] Publish starter capability templates (golden path skeletons with all required fields pre-filled)

## Version 1.0 - General Availability - September :apple:

The goal of this version is to release our first version ready for production.

- [ ] Incorporate community feedback
- [ ] Publish reference bridge capabilities (RSS/Atom XML feeds, XML/SOAP, CSV, etc.)
- [ ] Solidify the existing beta version scope
- [ ] Increase test coverage and overall quality
- [ ] Publish JSON Schema to [JSON Schema Store](https://www.schemastore.org/)
- [ ] Profile and optimize engine for best latency and throughput

## Version 1.1 - December :snowflake:

The goal of this version is to broaden the platform surface area based on production learnings.

### Rightsize AI context
- [ ] Add AI framework wrappers (Langchain4j, LangChain, LlamaIndex)
- [ ] Support AI Catalog spec (join MCP, A2A initiative)

### Enhance API reusability
- [ ] Support Webhook server adapter for workflow automation
- [ ] Add support for gRPC (incl. proto generation) and oRPC as server adapters
- [ ] Add full resiliency patterns (rate limiter, time limiter, bulkhead, cache)

### Enable Data reusability
- [ ] Add support for Singer, Airbyte and SQL as server adapters
- [ ] Add support for FILE and SQL as client adapters
- [ ] Support templatized SQL request with proper security

### Core developer experience
- [ ] Facilitate integration with Keycloak, OpenFGA
- [ ] Provide Control webapp (per Capability)
- [ ] Publish Docker Desktop Extension to Docker Hub
