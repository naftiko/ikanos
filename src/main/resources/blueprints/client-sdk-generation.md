# Client SDK Generation
## Generating Typed Clients from Capability Specifications

**Status**: Proposal  
**Date**: April 21, 2026  
**Spec Version**: `1.0.0-alpha2`  
**Key Concept**: Generate language-native, typed client SDKs from Naftiko capability specifications — leveraging OAS export for REST clients and framework-specific wrappers for AI agent integration. Client SDKs fill the gap when adding a new adapter is not possible or consuming an existing adapter directly is insufficient.

**Related blueprints**:
- [OpenAPI Interoperability](openapi-interoperability.md) — OAS export is the foundation for typed REST client generation
- [gRPC Server Adapter](grpc-server-adapter.md) — gRPC stubs extend the multi-language client surface
- [Agent Skills Support](agent-skills-support.md) — skills catalog feeds AI framework wrappers with discovery metadata

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Goals and Non-Goals](#2-goals-and-non-goals)
3. [SDK Tiers](#3-sdk-tiers)
4. [Tier 1 — Typed REST Clients via OAS Export](#4-tier-1--typed-rest-clients-via-oas-export)
5. [Tier 2 — AI Framework Wrappers](#5-tier-2--ai-framework-wrappers)
6. [Tier 3 — A2A Agent Client](#6-tier-3--a2a-agent-client)
7. [Tier 4 — Embedded Java SDK](#7-tier-4--embedded-java-sdk)
8. [Tier 5 — gRPC Client Stubs](#8-tier-5--grpc-client-stubs)
9. [Why Not Typed MCP Clients](#9-why-not-typed-mcp-clients)
10. [CLI Surface](#10-cli-surface)
11. [Implementation Roadmap](#11-implementation-roadmap)
12. [Risks and Mitigations](#12-risks-and-mitigations)
13. [Acceptance Criteria](#13-acceptance-criteria)

---

## 1. Executive Summary

### What This Proposes

A strategy for generating typed client SDKs from Naftiko capability specifications, organized in tiers by value and timeline:

1. **Typed REST clients** (TypeScript, Python, Java, Go) — Generated from OAS export using mature, external codegen toolchains (`openapi-ts`, `orval`, `openapi-generator`). This is the universal, highest-reach option that covers all application-to-application use cases.

2. **AI framework wrappers** (Langchain4j, LangChain, LlamaIndex) — Bridge the MCP adapter protocol to in-process tool objects expected by AI agent frameworks. This is where MCP typing actually matters.

3. **A2A agent client** (TypeScript, Python) — Discover agent cards and invoke agent tasks once the A2A server adapter ships.

4. **Embedded Java SDK** — In-process library for calling capability orchestration without spinning up a server.

5. **gRPC client stubs** (multi-language) — Generated from capability specs once the gRPC adapter ships.

### What This Does NOT Do

- **No standalone typed MCP client SDK** — see [§9 Why Not Typed MCP Clients](#9-why-not-typed-mcp-clients) for rationale.
- **No client-side code shipping in the framework** — generated SDKs are artifacts produced by the CLI or CI pipelines; the framework itself remains a server-side engine.
- **No runtime client generation** — SDK generation is a build-time / CLI operation, not a live endpoint.
- **No opinion on package registries** — teams publish generated SDKs to npm, PyPI, Maven Central, or internal registries at their discretion.

### Business Value

| Persona | Without Client SDKs | With Client SDKs |
|---------|---------------------|-------------------|
| **App developer** | Writes untyped HTTP calls or raw JSON tool invocations by hand | Imports a typed client with autocomplete, parameter validation, and return types |
| **AI agent builder** | Manages MCP transport plumbing to wire tools into LangChain/Langchain4j | Imports a native `BaseTool` / `@Tool` wrapper — one line to register |
| **Platform engineer** | Publishes API docs and hopes consumers read them | Ships a generated SDK alongside the capability — contract enforced at compile time |
| **Polyglot team** | Limited to languages with HTTP client libraries or MCP SDKs | Consumes gRPC stubs in Go, Rust, C#, or any protobuf-supported language |

---

## 2. Goals and Non-Goals

### Goals

| # | Goal | Measured By |
|---|------|-------------|
| G-1 | Define the SDK tier strategy with clear scope and dependencies per tier | This blueprint is reviewed and accepted |
| G-2 | Typed REST clients generated from OAS export | `naftiko export openapi` → `openapi-generator` produces a compiling client |
| G-3 | AI framework wrappers bridge MCP protocol to in-process tool objects | Langchain4j `@Tool` / LangChain `BaseTool` wraps a Naftiko MCP tool |
| G-4 | CLI commands support SDK generation workflows | `naftiko generate client` orchestrates export + codegen |

### Non-Goals

| # | Non-Goal | Reason |
|---|----------|--------|
| NG-1 | Standalone typed MCP client SDK | No incremental value over typed REST clients for app-to-app; AI-specific value belongs in framework wrappers (see §9) |
| NG-2 | Hosting generated SDKs in the framework repository | SDKs are consumer-side artifacts — separate repos or registries |
| NG-3 | Supporting OAS 2.0 (Swagger) input | OAS export produces 3.1; import normalizes 3.0+ — no 2.0 path |

---

## 3. SDK Tiers

### Tier Priority Matrix

| Tier | SDK Type | Languages | Fills gap when... | Depends on | Timeline |
|------|----------|-----------|-------------------|------------|----------|
| **1** | Typed REST client | TS, Python, Java, Go | Consumer needs type safety over REST | OAS export (exists) | Now |
| **2** | AI framework wrapper | Java, Python | Consumer uses LangChain / Langchain4j / LlamaIndex | MCP adapter (exists) | Third Alpha |
| **3** | A2A agent client | TS, Python | Consumer orchestrates multi-agent flows | A2A adapter (Third Alpha) | Post-Third Alpha |
| **4** | Embedded Java SDK | Java | No server possible, in-process use case | Engine internals | Post-Beta |
| **5** | gRPC client stubs | Multi-lang | Consumer needs polyglot, high-perf RPC | gRPC adapter (v1.1) | v1.1 |

### Decision Criteria — When to Use Which

```
Is the consumer an AI agent/framework?
  ├── YES → Tier 2: AI Framework Wrapper
  │           (in-process tool object, not a network client)
  └── NO
        Is the consumer a different agent in a multi-agent system?
          ├── YES → Tier 3: A2A Agent Client
          └── NO
                Is the consumer in the same JVM?
                  ├── YES → Tier 4: Embedded Java SDK
                  └── NO
                        Does the consumer need high-perf binary RPC?
                          ├── YES → Tier 5: gRPC Client Stubs
                          └── NO → Tier 1: Typed REST Client (default)
```

---

## 4. Tier 1 — Typed REST Clients via OAS Export

### Approach

Leverage the existing `naftiko export openapi` command (see [OpenAPI Interoperability](openapi-interoperability.md)) to produce an OAS 3.1 document, then feed it into mature, battle-tested codegen tools.

### Recommended Toolchains

| Language | Tool | Output |
|----------|------|--------|
| TypeScript | `openapi-ts` or `orval` | Typed fetch/axios client with full inference |
| Python | `openapi-generator` (python) | Pydantic models + httpx/requests client |
| Java | `openapi-generator` (java) | Typed client with Jackson models |
| Go | `oapi-codegen` | Typed client with struct models |

### Why This Is Tier 1

- **Tooling maturity**: OAS codegen is battle-tested across dozens of languages with active communities.
- **Universal reach**: Every HTTP client, API gateway, and proxy understands REST.
- **Equivalent DX**: Both REST and MCP typed clients collapse to the same callsite shape (e.g., `client.getWeather({ city: "Paris" })`). The protocol underneath is invisible to the consumer.
- **Zero framework dependency**: Generated clients depend only on standard HTTP libraries, not on Naftiko runtime.

### Pipeline

```
capability.yml
    │
    ▼
naftiko export openapi
    │
    ▼
openapi.yaml (OAS 3.1)
    │
    ├──► openapi-ts → TypeScript client
    ├──► openapi-generator → Python client
    ├──► openapi-generator → Java client
    └──► oapi-codegen → Go client
```

---

## 5. Tier 2 — AI Framework Wrappers

### Problem

AI agent frameworks (Langchain4j, LangChain, LlamaIndex) expect **in-process tool objects**, not a running server. A developer using LangChain wants to write:

```python
from naftiko_langchain import from_capability
tools = from_capability("weather-api.yml")
agent = create_react_agent(llm, tools)
```

Not:

```python
# Manual MCP transport setup, JSON schema parsing,
# untyped tool invocation, response deserialization...
```

### Why Adapters Alone Are Not Enough

The MCP adapter exposes the *protocol*, but downstream developers still write untyped JSON tool calls. These frameworks expect native tool abstractions — `BaseTool` in LangChain, `@Tool` in Langchain4j, `FunctionTool` in LlamaIndex — that encapsulate invocation, input validation, and output parsing.

### Where MCP Typing Matters

MCP-specific primitives that have no REST equivalent justify wrapping MCP rather than REST for AI consumers:

- **Tool annotations** (`readOnly`, `destructive`, `idempotent`, `openWorld`) — AI routing signals for agent safety and planning.
- **Resources** and **Prompts** — context-providing primitives that REST does not model.
- **Dynamic tool discovery** — MCP's `tools/list` lets agents discover capabilities at runtime.

These are AI-routing signals, not application logic — which is exactly why they belong in framework-specific wrappers, not a standalone SDK.

### Target Frameworks

| Framework | Language | Wrapper Type | Related |
|-----------|----------|-------------|---------|
| Langchain4j | Java | `@Tool`-annotated classes | [Issue #293](https://github.com/naftiko/framework/issues/293) |
| LangChain | Python | `BaseTool` subclasses | — |
| LlamaIndex | Python | `FunctionTool` instances | — |
| Vercel AI SDK | TypeScript | `tool()` definitions | — |
| Mastra | TypeScript | `createTool()` definitions | — |

### Architecture

```
Naftiko MCP Server
    │
    ▼ (MCP protocol — tool discovery + invocation)
    │
AI Framework Wrapper (thin adapter layer)
    ├── Reads tools/list → generates typed tool objects
    ├── Maps inputParameters → framework's parameter schema
    ├── Maps tool annotations → framework's safety metadata
    └── Delegates tool calls → MCP tools/call
    │
    ▼
Framework-native tool objects (BaseTool, @Tool, FunctionTool)
    │
    ▼
Agent runtime (chains, agents, planners)
```

---

## 6. Tier 3 — A2A Agent Client

### Dependency

Requires the A2A server adapter (roadmap: Third Alpha, May 2026).

### Value

Once capabilities expose A2A agent cards, consumers need SDKs to:

- Discover agent cards (capabilities, skills, supported tasks)
- Invoke agent tasks with structured input
- Handle streaming task updates and artifacts

Google's A2A protocol is still young; early typed clients would position Naftiko as an A2A-ready platform.

### Target Languages

TypeScript and Python — the dominant languages in the multi-agent orchestration ecosystem (AutoGen, CrewAI, Google ADK).

---

## 7. Tier 4 — Embedded Java SDK

### Use Case

Teams that want to use capability orchestration as a **library** — calling orchestrated steps from a Spring Boot service, a batch job, or a CLI tool without spinning up a REST/MCP/gRPC server.

This is the literal "when providing an adapter is not possible" case: the consumer **is** the JVM process.

### Approach

Extract the engine's core execution path (`OperationStepExecutor`, aggregate resolution, parameter mapping) into a public API surface that can be invoked programmatically:

```java
var engine = NaftikoEngine.load("capability.yml");
var result = engine.invoke("weather.get-forecast",
    Map.of("city", "Paris"));
```

### Constraints

- Must not require starting Jetty or any server.
- Must not expose internal types — the public surface is `Map<String, Object>` in, `Map<String, Object>` out.
- Secrets resolution (`binds`) must work without environment variables (accept programmatic overrides).

---

## 8. Tier 5 — gRPC Client Stubs

### Dependency

Requires the gRPC server adapter (roadmap: v1.1, December 2026). See [gRPC Server Adapter](grpc-server-adapter.md).

### Value

gRPC's protobuf ecosystem provides mature codegen for Go, Rust, C#, C++, Swift, Kotlin, and other languages where Naftiko has no native adapter. Generating `.proto` definitions from capability specs and publishing language-neutral stubs enables truly polyglot consumption.

### Pipeline

```
capability.yml
    │
    ▼
naftiko export proto (future CLI command)
    │
    ▼
service.proto
    │
    ├──► protoc --go_out → Go stubs
    ├──► protoc --csharp_out → C# stubs
    ├──► protoc --rust_out → Rust stubs (via tonic-build)
    └──► protoc --swift_out → Swift stubs
```

---

## 9. Why Not Typed MCP Clients

A standalone "typed MCP client SDK" was evaluated and deprioritized. The reasoning:

### For application-to-application use cases, typed REST clients are strictly better

| Dimension | Typed REST Client (OAS) | Typed MCP Client |
|-----------|------------------------|-------------------|
| **Tooling maturity** | Battle-tested across dozens of languages (`openapi-generator`, `orval`, `openapi-ts`) | MCP client codegen barely exists |
| **Universal reach** | Every HTTP client, API gateway, and proxy understands REST | MCP requires a specialized runtime |
| **Equivalent DX** | `client.getWeather({ city: "Paris" })` | `client.getWeather({ city: "Paris" })` — identical callsite |
| **Dependency weight** | Standard HTTP library | MCP SDK + transport layer |

### For AI agent use cases, framework wrappers are strictly better

The two scenarios where MCP typing has distinct value both point to the same consumer profile — **AI agents and frameworks**:

1. **MCP-only capabilities** — when the capability doesn't expose a REST adapter at all. No OAS to export, so no REST client to generate.
2. **MCP-specific primitives** — Resources, Prompts, and Tool annotations (`readOnly`, `destructive`, `openWorld`) that have no REST equivalent.

But AI agents don't want a generic typed client — they want **framework-native tool objects** (`BaseTool`, `@Tool`, `FunctionTool`). That's the Tier 2 AI Framework Wrapper, not a standalone SDK.

### Conclusion

"Typed MCP client" as a standalone SDK tier has no distinct audience. App-to-app consumers are better served by Tier 1 (REST). AI consumers are better served by Tier 2 (framework wrappers). The MCP protocol's value is in **runtime tool discovery and AI-specific metadata**, which is consumed programmatically by wrappers — not by hand-written application code that would benefit from static typing.

---

## 10. CLI Surface

### Proposed Commands

| Command | Description | Tier |
|---------|-------------|------|
| `naftiko export openapi <capability.yml>` | Export OAS 3.1 from REST exposes (exists) | 1 |
| `naftiko generate client --lang <ts\|python\|java\|go> <openapi.yaml>` | Generate typed REST client from OAS | 1 |
| `naftiko export proto <capability.yml>` | Export `.proto` from gRPC exposes | 5 |

The `generate client` command wraps the appropriate codegen tool for the target language, providing a single entry point without requiring the user to install `openapi-generator` or `orval` separately.

AI framework wrappers (Tier 2) and A2A clients (Tier 3) are distributed as library packages, not CLI-generated artifacts.

---

## 11. Implementation Roadmap

| Phase | Tier | Milestone | Depends On |
|-------|------|-----------|------------|
| **Phase 1** | Tier 1 | Document OAS export → codegen pipeline, provide examples | OAS export (done) |
| **Phase 2** | Tier 1 | Implement `naftiko generate client` CLI command | Phase 1 |
| **Phase 3** | Tier 2 | Langchain4j wrapper (Java) | MCP adapter (done), Issue #293 |
| **Phase 4** | Tier 2 | LangChain + LlamaIndex wrappers (Python) | Phase 3 patterns |
| **Phase 5** | Tier 3 | A2A typed client | A2A adapter (Third Alpha) |
| **Phase 6** | Tier 4 | Embedded Java SDK | Stable engine API (Beta) |
| **Phase 7** | Tier 5 | `naftiko export proto` + gRPC stubs | gRPC adapter (v1.1) |

---

## 12. Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| **OAS export fidelity gaps** | Medium | High | Validate generated OAS against OAS 3.1 schema; round-trip tests (export → codegen → compile) |
| **Codegen tool version drift** | Medium | Medium | Pin codegen tool versions; test in CI |
| **AI framework API instability** | High | Medium | Wrappers are thin adapters — minimize surface area, isolate framework-specific code |
| **Embedded SDK leaking internals** | Low | High | Strict public API boundary — `Map` in, `Map` out; no internal types in signatures |
| **Maintenance burden across languages** | Medium | Medium | Tier 1 delegates to external codegen; only Tiers 2–4 require maintained code |

---

## 13. Acceptance Criteria

| # | Criterion |
|---|-----------|
| AC-1 | `naftiko export openapi` → `openapi-generator generate -i openapi.yaml -g typescript-fetch` produces a compiling TypeScript client |
| AC-2 | Generated TypeScript client can call all REST-exposed operations with typed parameters and responses |
| AC-3 | Langchain4j wrapper discovers MCP tools and exposes them as `@Tool`-annotated methods |
| AC-4 | Langchain4j wrapper propagates tool annotations (`readOnly`, `destructive`) to framework metadata |
| AC-5 | `naftiko generate client` CLI command wraps codegen for at least TypeScript and Python |
| AC-6 | All generated clients pass compilation and basic smoke tests in CI |
