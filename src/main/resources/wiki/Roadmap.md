## Version 1.0 - First Alpha - March 30th, 2026 :seedling:

The goal of this version is to deliver a MVP to enable common AI integration use cases and grow our community.

### Rightsize AI context
- [x] Declarative applied capability exposing Agent Skills
- [x] Declarative MCP exposing Resources and Prompts (Tools only so far)

### Enable API reuse
- [x] Support for lookups as part of API call steps
- [x] Authenticate API and MCP Server consumers and manage permissions
- [x] Reusable source HTTP adapter declaration across capabilities
  - [x] Declarative applied capabilities with reused source capabilities

### Core developer experience
- [x] Publish FAQ in the wiki
- [ ] Publish Naftiko JSON Structure
- [ ] Publish Naftiko Skill based on Naftiko CLI
- [ ] Publish Naftiko Ruleset based on Spectral
- [ ] Publish Maven Artifacts to [Maven Central](https://central.sonatype.com/)
- [ ] Publish Javadocs to [Javadoc.io](https://javadoc.io)
- [ ] Publish Docker Image to [Docker Hub](https://hub.docker.com/)
- [ ] Provide GitHub Action template based on [Super Linter](https://github.com/super-linter/super-linter)

## Version 1.0 - Second Alpha - May 11th :deciduous_tree:

The goal of this version is to deliver a MVP to enable common AI integration use cases and grow our community.

### Rightsize AI context
  - [ ] Add support for authentication in the MCP server adapter
  - [ ] Facilitate integration with MCP and AI gateways
  - [ ] Facilitate skills publication in skills marketplaces

### Enable API reuse
  - [ ] Support Webhook server adapter for workflow automation
  - [ ] Factorize capability core with "aggregates" of functions initially, entities and events later
  - [ ] Add conditional steps, for-each steps, parallel-join
  - [ ] Allow reuse of "binds" blocks across capabilities
  - [ ] Enable API token refresh flows
  - [ ] Facilitate integration with API gateways

### Enable agent orchestration use case
  - [ ] Support A2A server adapter with tool discovery and execution

### Core developer experience

- [ ] OpenAPI-to-Naftiko import tooling — generate a starter capability YAML from an existing OpenAPI file
- [ ] Publish starter capability templates (golden path skeletons with all required fields pre-filled)
- [ ] Provide Control port
  - [ ] Usable via REST API, usable via Naftiko CLI, packaged as capability
  - [ ] Usable via webapp as Docker Desktop Extension

## Version 1.0 - First Beta - June :blossom:

The goal of this version is to deliver a stable MVP, including a stable Naftiko Specification.

- [ ] Rightsize AI context
  - [ ] Evolve MCP server adapter to support [server-side code mode like CloudFlare](https://www.reddit.com/r/mcp/comments/1o1wdfh/do_you_think_code_mode_will_supercede_mcp/)
- [ ] Enhance API reusability
  - [ ] Add support for resiliency patterns (retry, circuit breaker, rate limiter, time limiter, bulkhead, cache, fallback)
  - [ ] Publish reference bridge capabilities (RSS/Atom XML feeds, XML/SOAP, CSV, etc.)
- [ ] Provide enhanced security
  - [ ] Facilitate authorization management
- [ ] Incorporate community feedback
- [ ] Increase test coverage and overall quality

## Version 1.0 - General Availability - September :apple:

The goal of this version is to release our first version ready for production.

- [ ] Incorporate community feedback
- [ ] Solidify the existing beta version scope
- [ ] Increase test coverage and overall quality
- [ ] Publish JSON Schema to [JSON Schema Store](https://www.schemastore.org/)

## Version 1.1 - December :snowflake:

The goal of this version is to broaden the platform surface area based on production learnings.

### Extend protocol support
- [ ] Add support for gRPC and tRPC as server adapters
- [ ] Add full resiliency patterns (rate limiter, time limiter, bulkhead, cache)

### Enterprise security
- [ ] Facilitate integration with Keycloak, OpenFGA

### Operator experience
- [ ] Provide Control webapp (per Capability)
- [ ] Publish Docker Desktop Extension to Docker Hub

### Discovery and ecosystem
- [ ] Fabric discovery of published capabilities for consumers