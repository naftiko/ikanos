<img src="https://naftiko.github.io/docs/images/logos/logo_ikanos_horizontal.png" width="300">

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/naftiko/ikanos/badges/main/ikanos-coverage.json)](https://github.com/naftiko/ikanos/actions/workflows/quality-gate.yml)
[![Quality Gate](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/naftiko/ikanos/badges/main/ikanos-quality-gate.json)](https://github.com/naftiko/ikanos/actions/workflows/nightly-quality-gate.yml)
[![Bugs](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/naftiko/ikanos/badges/main/ikanos-bugs.json)](https://github.com/naftiko/ikanos/actions/workflows/nightly-quality-gate.yml)
[![Trivy](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/naftiko/ikanos/badges/main/ikanos-trivy.json)](https://github.com/naftiko/ikanos/actions/workflows/nightly-quality-gate.yml)
[![Gitleaks](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/naftiko/ikanos/badges/main/ikanos-gitleaks.json)](https://github.com/naftiko/ikanos/actions/workflows/nightly-quality-gate.yml)

**Ikanos** is the open source capability engine for Spec-Driven Integration.

<img src="https://naftiko.github.io/docs/images/technology/architecture_capability.png" width="700">

It reads one YAML file describing a slice of your business — say *customers* or *orders*. A capability **consumes** the HTTP APIs and MCP servers you already have and **structures** their responses into clean JSON, then **aggregates** and **orchestrates** those operations into a unified contract that it instantly serves to AI agents, web apps, and partners over several protocols at once. One source of truth, many audiences, no code to write or compile.

---

## What that means for *you*

- :robot: **You're building AI agents** — give agents governed MCP tools and Agent Skills without writing handlers, and send the model exactly the context a task needs. → [Context engineering tutorial](https://shipyard.naftiko.io/ikanos/latest/tutorials/track-1-context-engineering/)
- :electric_plug: **You own a sprawl of APIs, microservices, or a monolith** — put a clean, governed contract in front of messy or legacy endpoints. → [API reusability tutorial](https://shipyard.naftiko.io/ikanos/latest/tutorials/track-2-api-reusability/)
- :bricks: **You want one source of truth for every client** — write the domain once and serve it as REST and MCP from the same spec. → [Use cases](https://shipyard.naftiko.io/ikanos/latest/concepts/use-cases/)

See **[Who is it for?](https://shipyard.naftiko.io/ikanos/latest/concepts/who-is-it-for/)** for the full persona map.

---

## Key features at a glance

| Feature | Description |
|---|---|
| **Spec-Driven Integration** | Declare capabilities entirely in **YAML**, no Java required, linted by embedded [Polychro](https://github.com/naftiko/polychro) |
| **Multi-Protocol Servers** | Serve one capability as **MCP** (tools, resources, prompts, agent Skills, MCP Apps), **REST**, and **Control** |
| **API & MCP Consumption** | HTTP APIs with Bearer, API key, Basic, or Digest auth; upstream **MCP servers** as sources |
| **Durable Execution** | Long-running flows run as **MCP tasks** that survive a restart, and pause to **ask the caller** for input over MCP or REST *(new in Beta 10)* |
| **Data Format Conversion** | Protobuf, XML, YAML, CSV, TSV, PSV, Avro, HTML, Markdown → JSON, plus byte-faithful binary |
| **Templating & Querying** | Mustache templates and JSONPath expressions for flexible data mapping |
| **Domain-Driven Aggregates** | Define reusable flows once, reference them from every adapter, call flows from flows |
| **Inline Scripting** | Sandboxed JavaScript, Python, or Groovy steps via GraalVM |
| **OpenAPI Interoperability** | Import Swagger 2.0, OAS 3.0/3.1; export REST adapters as OpenAPI |
| **Cloud-Native Operations** | OTel tracing, RED and JVM metrics, Prometheus scrape, health probes, single Docker image |
| **AI Native** | Structured tool output, hints derived from domain semantics, OAuth 2.1 for MCP servers |

For the full feature list see [Features](https://shipyard.naftiko.io/ikanos/latest/features/).

---

## New in 1.0 Beta 10

- **Durable execution.** Choose a durability mode on the Control port: `none` (the default, unchanged), `memory`, or `local` (embedded H2). Under `local`, a task that is interrupted resumes after a restart from its last completed step, so a completed write is not made twice. A write still in flight when the process stops runs again on restart.
- **Questions to the caller.** An `elicit` step asks the caller for input over MCP, through `tasks/update` for tasks, or over REST with a `202 Accepted` and an answer URL. Questions from upstream MCP servers pass through.
- **Breaking:** a capability declares its bindings under `capability.binds` only. See the [release notes](https://github.com/naftiko/ikanos/releases) for the migration and the full list of changes.

---

## What it is not

- **Not a code generator.** No source files are produced. The spec is the artifact and the runtime contract.
- **Not a service mesh.** Ikanos focuses on the application contract; mesh features (mTLS between services, traffic shifting) live below it.

---

## Continue reading

- [Installation](https://shipyard.naftiko.io/ikanos/latest/installation/) — install with the installer script or Docker
- [Use Cases](https://shipyard.naftiko.io/ikanos/latest/concepts/use-cases/) — typical integration scenarios
- [Specification](https://shipyard.naftiko.io/ikanos/latest/spec/) — the full reference for every keyword
- [Linting Guide](https://shipyard.naftiko.io/ikanos/latest/guide/linting/) — pair Ikanos with Polychro
- [Releases](https://shipyard.naftiko.io/ikanos/latest/releases/) — versions, dates, and support windows
- [Roadmap](https://shipyard.naftiko.io/ikanos/latest/roadmap/) — what's coming before 1.0 GA

<img src="https://naftiko.github.io/docs/images/navi/navi_hello.svg" width="50">
