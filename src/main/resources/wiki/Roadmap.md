## Version 1.0 - First Alpha - April 2nd, 2026 :seedling:

The goal of this version is to deliver a MVP to enable common AI integration use cases and grow our community.

### Rightsize AI context
- [x] Declarative applied capability exposing Agent Skills
- [x] Declarative MCP exposing Resources and Prompts (Tools only so far)

### Enable API reusability
- [x] Support for lookups as part of API call steps
- [x] Authenticate API and MCP Server consumers and manage permissions
- [x] Reusable source HTTP adapter declaration across capabilities
  - [x] Declarative applied capabilities with reused source capabilities

### Core developer experience
- [x] Publish FAQ in the wiki
- [x] Provide GitHub Action template based on Mega Linter
- [ ] Publish Naftiko JSON Structure
- [ ] Publish Naftiko Skill based on Naftiko CLI
- [ ] Publish Naftiko Ruleset based on Spectral
- [ ] Publish Maven Artifacts to [Maven Central](https://central.sonatype.com/)
- [ ] Publish Javadocs to [Javadoc.io](https://javadoc.io)
- [ ] Publish Docker Image to [Docker Hub](https://hub.docker.com/)

## Version 1.0 - Second Alpha - End of April :deciduous_tree:

The goal of this version is to solidify the MVP to enable common AI integration use cases and grow our community.

### Rightsize AI context
  - [x] Add mocking feature to MCP server adapter similar to REST server adapter
  - [x] Add tool annotations (readOnly, destructive, idempotent, openWorld)
  - [ ] Add support for authentication in the MCP server adapter

### Enable API reusability
  - [x] Add HTML and Markdown data format support for HTTP consumption
  - [ ] Enable pagination at consumes and exposes level
  - [ ] Enhance support for tags and labels across capabilities

### Core developer experience

- [x] Factorize capability core with functions initially, entities and events later
- [ ] Allow reuse of "binds" blocks across capabilities
- [ ] Externalize individual "exposes" objects into separate files, similar to "consumes" objects

## Version 1.0 - Third Alpha - End of May :deciduous_tree:

### Rightsize AI context
  - [ ] Facilitate integration with MCP and AI gateways
  - [ ] Facilitate skills publication in skills marketplaces

### Enable API reusability
  - [ ] Support HTTP cache control directives
  - [ ] Enable API token refresh flows
  - [ ] Support Webhook server adapter for workflow automation
  - [ ] Facilitate integration with API gateways

### Enable agent orchestration use case
  - [ ] Support A2A server adapter with tool discovery and execution

### Core developer experience
- [ ] Enhance traceability and debuggability of engine
- [ ] Add conditional steps, for-each steps, parallel-join
- [ ] OpenAPI-to-Naftiko import tooling — generate a starter capability YAML from an existing OpenAPI file
- [ ] Publish starter capability templates (golden path skeletons with all required fields pre-filled)
- [ ] Provide Control port usable via REST clients, Naftiko CLI

## Version 1.0 - First Beta - End of June :blossom:

The goal of this version is to deliver a stable MVP, including a stable Naftiko Specification.

- [ ] Rightsize AI context
  - [ ] Evolve MCP server adapter to support [server-side code mode like CloudFlare](https://www.reddit.com/r/mcp/comments/1o1wdfh/do_you_think_code_mode_will_supercede_mcp/)
- [ ] Enhance API reusability
  - [ ] Add support for resiliency patterns (retry, circuit breaker, rate limiter, time limiter, bulkhead, cache, fallback)
  - [ ] Publish reference bridge capabilities (RSS/Atom XML feeds, XML/SOAP, CSV, etc.)
- [ ] Enable capabilities governance
  - [ ] Expand support for "tags" and "labels" in Naftiko Spec
- [ ] Provide enhanced security
  - [ ] Facilitate authorization management
- [ ] Incorporate community feedback
- [ ] Complete test coverage and overall quality

## Version 1.0 - General Availability - September :apple:

The goal of this version is to release our first version ready for production.

- [ ] Incorporate community feedback
- [ ] Solidify the existing beta version scope
- [ ] Increase test coverage and overall quality
- [ ] Publish JSON Schema to [JSON Schema Store](https://www.schemastore.org/)
- [ ] Profile and optimize engine for best latency and throughput

## Version 1.1 - December :snowflake:

The goal of this version is to broaden the platform surface area based on production learnings.

### Enhance API reusability
- [ ] Add support for gRPC and tRPC as server adapters
- [ ] Add full resiliency patterns (rate limiter, time limiter, bulkhead, cache)

### Enable Data reusability
- [ ] Add support for Singer, Airbyte and SQL as server adapters
- [ ] Add support for FILE and SQL as client adapters
- [ ] Support templatized SQL request with proper security

### Enterprise security
- [ ] Facilitate integration with Keycloak, OpenFGA

### Operator experience
- [ ] Provide Control webapp (per Capability)
- [ ] Publish Docker Desktop Extension to Docker Hub

### Discovery and ecosystem
- [ ] Fabric discovery of published capabilities for consumers