<img src="https://naftiko.github.io/docs/images/logos/logo_ikanos_horizontal.png" width="300">

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/naftiko/ikanos/badges/main/ikanos-coverage.json)](https://github.com/naftiko/ikanos/actions/workflows/quality-gate.yml)
[![Quality Gate](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/naftiko/ikanos/badges/main/ikanos-quality-gate.json)](https://github.com/naftiko/ikanos/actions/workflows/nightly-quality-gate.yml)
[![Bugs](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/naftiko/ikanos/badges/main/ikanos-bugs.json)](https://github.com/naftiko/ikanos/actions/workflows/nightly-quality-gate.yml)
[![Trivy](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/naftiko/ikanos/badges/main/ikanos-trivy.json)](https://github.com/naftiko/ikanos/actions/workflows/nightly-quality-gate.yml)
[![Gitleaks](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/naftiko/ikanos/badges/main/ikanos-gitleaks.json)](https://github.com/naftiko/ikanos/actions/workflows/nightly-quality-gate.yml)

**Ikanos** is the open source capability engine for Spec-Driven Integration.

<img src="https://naftiko.github.io/docs/images/technology/architecture_capability.png" width="700">

It reads one YAML file describing a slice of your business — say *customers* or *orders*. A capability **consumes** the HTTP/REST APIs you already have and **structures** their responses into clean JSON, then **aggregates** and **orchestrates** those operations into a unified contract that it instantly serves to AI agents, web apps, and partners over several protocols at once. One source of truth, many audiences, no code to write or compile.

---

## What that means for *you*

- :robot: **You're building AI agents** — give agents governed MCP tools without writing handlers, and send the model exactly the context a task needs. → [Context engineering tutorial](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/tutorials/track-1-context-engineering/)
- :electric_plug: **You own a sprawl of APIs, microservices, or a monolith** — put a clean, governed contract in front of messy or legacy endpoints. → [API reusability tutorial](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/tutorials/track-2-api-reusability/)
- :bricks: **You want one source of truth for every client** — write the domain once and serve it as REST, MCP, and Skill from the same spec. → [Use cases](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/concepts/use-cases/)

See **[Who is it for?](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/concepts/who-is-it-for/)** for the full persona map.

---

## Key features at a glance

| Feature | Description |
|---|---|
| **Spec-Driven Integration** | Declare capabilities entirely in **YAML** — no Java required |
| **Multi-Protocol Servers** | Expose capabilities via **MCP**, **Skill**, **REST**, or **Control** |
| **Data Format Conversion** | Protobuf, XML, YAML, CSV, TSV, PSV, Avro, HTML, Markdown → JSON |
| **HTTP API Consumption** | Bearer, API key, basic, digest, OAuth 2.1 auth out of the box |
| **Templating & Querying** | Mustache templates and JSONPath expressions for flexible data mapping |
| **Inline Scripting** | Sandboxed JavaScript, Python, or Groovy steps via GraalVM |
| **Domain-Driven Aggregates** | Define reusable functions once, reference from multiple adapters |
| **OpenAPI Interoperability** | Import Swagger 2.0, OAS 3.0/3.1; export REST adapters as OpenAPI |
| **Cloud-Native Operations** | OTel tracing, RED metrics, Prometheus scrape, single Docker image |
| **AI Native** | Designed for context engineering and agent orchestration |

For the full feature list see [Features](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/features/).

---

## What it is not

- **Not a code generator.** No source files are produced. The spec is the artifact and the runtime contract.
- **Not a workflow engine.** Multi-step orchestration is supported, but long-running workflows, schedules, and durable state are out of scope.
- **Not a service mesh.** Ikanos focuses on the application contract; mesh features (mTLS between services, traffic shifting) live below it.

---

## Continue reading

- [Installation](https://shipyard.naftiko.io/ikanos/1.0.0-beta2/installation/) — install via Docker or native binary
- [Use Cases](https://shipyard.naftiko.io/ikanos/1.0.0-beta2/concepts/use-cases/) — typical integration scenarios
- [Schema](https://shipyard.naftiko.io/ikanos/1.0.0-beta2/spec/) — the full reference for every keyword
- [Linting Guide](https://shipyard.naftiko.io/ikanos/1.0.0-beta2/guide/linting/) — pair Ikanos with Polychro
- [Roadmap](https://shipyard.naftiko.io/ikanos/1.0.0-beta2/roadmap/) — what's coming in upcoming alphas

<img src="https://naftiko.github.io/docs/images/navi/navi_hello.svg" width="50">
