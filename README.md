<img src="https://naftiko.github.io/docs/images/logos/logo_ikanos_horizontal.png" width="300">

[![Coverage](https://img.shields.io/endpoint?url=https://gist.githubusercontent.com/farah-t-trigui/50bfcb34f6512cbad2dd4f460bfc6526/raw/framework-coverage.json)](https://github.com/naftiko/ikanos/actions/workflows/quality-gate.yml)
[![Bugs](https://img.shields.io/endpoint?url=https://gist.githubusercontent.com/farah-t-trigui/50bfcb34f6512cbad2dd4f460bfc6526/raw/framework-bugs.json)](https://github.com/naftiko/ikanos/actions/workflows/quality-gate.yml)
[![Trivy](https://img.shields.io/endpoint?url=https://gist.githubusercontent.com/farah-t-trigui/50bfcb34f6512cbad2dd4f460bfc6526/raw/framework-trivy.json)](https://github.com/naftiko/ikanos/actions/workflows/quality-gate.yml)
[![Gitleaks](https://img.shields.io/endpoint?url=https://gist.githubusercontent.com/farah-t-trigui/50bfcb34f6512cbad2dd4f460bfc6526/raw/framework-gitleaks.json)](https://github.com/naftiko/ikanos/actions/workflows/quality-gate.yml)

Welcome to **Ikanos**, the first Open Source project for [Spec-Driven Integration](https://shipyard.naftiko.io/docs/1.0.0-alpha3/concepts/spec-driven-integration/) — reinventing API integration for the AI era with governed, versatile **capabilities** that streamline the API sprawl created by massive SaaS and microservices growth.

> Ikanos comes from the Greek *ικανός* — **capable**.

<img src="https://naftiko.github.io/docs/images/technology/architecture_capability.png" width="600">

## What Ikanos is

Ikanos is a **capability engine** that reads an Ikanos YAML specification at startup and immediately serves it as a multi-protocol server — **MCP**, **Skill**, **REST**, and **Control** — with **no code generation and no compilation step**. The spec *is* the artifact and the runtime contract.

Each capability is a coarse-grained slice of a domain. It consumes existing HTTP-based APIs, optionally orchestrates and transforms the data, then exposes the result in several protocols so that AI agents, web apps, and partners can all consume it the same way. The project ships as three pieces:

In production, one Ikanos process serves one *capability* as a Docker container. Multiple capabilities running in the same Kubernetes cluster constitute a *ship* while larger organizations operate a *fleet* federating several ships. This is where Naftiko Fleet can help you scale Ikanos from development to governance.

## What Ikanos is *not*

- **Not a code generator.** Tooling can *scaffold* the YAML spec for you, and Ikanos can *export* artifacts from it — such as OpenAPI contracts — but the running capability never compiles or executes generated source. At runtime it does one thing: interpret the YAML spec directly. The spec is the deliverable — and because that deliverable is plain, declarative YAML rather than generated code, it's especially well-suited to **Generative AI**: an LLM can author, edit, and reason about a capability end to end, with no build step in the loop.
- **Not a service mesh** A classic mesh wraps every pod in a sidecar (or an ambient data plane) to add resiliency, transport security, and traffic control *underneath* your services. Ikanos builds those same concerns into the capability engine itself such as retries, timeouts, circuit breaking, and rate limiting today, mTLS verification on both sides and traffic controls like load-based routing planned next. When your traffic is Ikanos capabilities talking to Ikanos capabilities, there's no sidecar to inject and no mesh to operate — the engine *is* the data plane.
- **Not a workflow engine.** Ikanos already orchestrates multi-step capabilities; as it grows to cover Agent Orchestration in v1.0+, it adds long-running workflows, durable agent memory, and checkpoints natively — without the bespoke DSLs, BPMN diagrams, or separate runtime of a classic workflow engine. Orchestration stays inside the capability contract and is consumable by AI agents the moment it's declared.

## Key features at a glance

| Feature | Description |
|---|---|
| Spec-Driven Integration | Declare capabilities entirely in **YAML** — no Java required |
| Multi-Protocol Servers | Expose capabilities via **MCP**, **Skill**, **REST**, or **Control** out of the box |
| Data Format Conversion | Transform **Protobuf**, **XML**, **YAML**, **CSV**, **TSV**, **PSV**, **Avro**, **HTML**, and **Markdown** payloads into JSON |
| HTTP API Consumption | Connect to any HTTP-based API with built-in authentication support |
| Templating & Querying | Use **Mustache** templates and **JSONPath** expressions for flexible data mapping |
| Inline Scripting | Embed sandboxed **JavaScript**, **Python**, or **Groovy** transformations between API calls (GraalVM) |
| Domain-Driven Aggregates | Define reusable domain functions once, expose via multiple adapters — inspired by the **DDD** Aggregate pattern |
| Server Authentication | Secure exposed endpoints with **Bearer**, **API Key**, **Basic**, **Digest**, or **OAuth 2.1** out of the box |
| AI Native | Designed for Context Engineering and Agent Orchestration, making capabilities directly consumable by AI agents |
| OpenAPI Interoperability | Import **Swagger 2.0**, **OAS 3.0/3.1** into consumes adapters; export REST adapters as **OpenAPI** documents |
| Control Port | Built-in management plane with **health**, **metrics**, **traces**, and **status** endpoints |
| Cloud Native Operations | **OpenTelemetry** tracing & RED metrics, **Prometheus** scrape, single **Docker** image for all capabilities |
| Extensible | Open-source core extensible with new protocols and adapters |

## How Ikanos compares

Increasingly, developers don't hand-write this integration layer at all — they describe what they want and let generative AI produce the artifact. That shifts the question from *"which tool is nicest to code in?"* to *"which artifact can an AI reliably generate, and can a human still review and govern it?"* The answer depends on what each tool asks the AI to emit:

- **Agentic coding frameworks** — *LangChain, LangChain4j, LlamaIndex, Spring AI.* The AI has to write tools, retrievers, and orchestration as Python/Java — full application code that must compile, run, and be trusted.
- **MCP server frameworks** — *FastMCP, Spring AI MCP, the official MCP SDKs.* The AI writes an MCP server and its tool handlers — again, code to build and maintain.
- **MCP proxy generators** — *Stainless, APIMatic, Speakeasy, Mintlify.* No AI authoring here; they mechanically turn one OpenAPI document into a 1:1 SDK or MCP server.

Ikanos asks the AI for something simpler and safer: a **declarative YAML capability** validated against a stable schema. Generated YAML is easier for a model to get right than framework code, it shows up as a readable diff in a pull request, and it can be linted (with [Polychro](https://shipyard.naftiko.io/docs/1.0.0-alpha3/polychro/)) before it ever runs — so a human stays in control of what the AI produced. One capability can also consume and compose several upstream APIs (for example, joining a customer, their orders, and their open tickets from three services) and expose the result over MCP, Skill, and REST at once.

Ikanos is also designed to fit alongside these tools rather than replace them: it imports OpenAPI, exposes MCP, and integrates with LangChain4j as in-process tools, so adopting it usually means keeping your runtime and replacing hand-written glue with an AI-authored, reviewable spec.

> For a dimension-by-dimension comparison, and guidance on when another tool is a better fit, see the [Comparison guide](https://shipyard.naftiko.io/docs/1.0.0-alpha3/ikanos/comparison/) on the Shipyard.

## Quick start

You can run Ikanos with the **native CLI** or the **Docker image**. The CLI has the least friction, so start there.

### With the CLI

```bash
# Create a minimal, valid capability interactively
ikanos create capability

# Validate it against the latest schema
ikanos validate my-capability.yml

# Serve it locally (Ctrl-C to stop)
ikanos serve my-capability.yml
```

The CLI can also bootstrap a capability from an existing OpenAPI document (`ikanos import openapi ...`) and export a REST adapter back to OpenAPI (`ikanos export openapi ...`).

### With Docker

```bash
# Pull the image (pin to a release tag, or use latest for the newest snapshot)
docker pull ghcr.io/naftiko/ikanos:latest

# Serve a capability file (mount it at /app/ikanos.yaml — the image serves it by default)
docker run -p 8081:3001 \
  -v "$PWD/my-capability.yml:/app/ikanos.yaml" \
  ghcr.io/naftiko/ikanos:latest
```

> Full installation steps for the CLI, Docker, and every platform are in the [Installation guide](https://shipyard.naftiko.io/docs/1.0.0-alpha3/ikanos/installation/) on the Shipyard.

***

## :anchor: Dive deeper on the Shipyard

The complete, always-up-to-date Ikanos documentation lives on the **Naftiko Shipyard**. Start here and keep exploring:

- :compass: [Concepts — Spec-Driven Integration](https://shipyard.naftiko.io/docs/1.0.0-alpha3/concepts/)
- :rowboat: [Installation](https://shipyard.naftiko.io/docs/1.0.0-alpha3/ikanos/installation/)
- :sailboat: [Tutorials](https://shipyard.naftiko.io/docs/1.0.0-alpha3/tutorials/) — guided tracks from your first capability to platform operations
- :ship: [Use Cases](https://shipyard.naftiko.io/docs/1.0.0-alpha3/concepts/use-cases/)
- :star: [Features](https://shipyard.naftiko.io/docs/1.0.0-alpha3/ikanos/features/)
- :mag: [Linting Guide](https://shipyard.naftiko.io/docs/1.0.0-alpha3/ikanos/guide/linting/) — pair Ikanos with Polychro
- :anchor: [Specification — Schema](https://shipyard.naftiko.io/docs/1.0.0-alpha3/ikanos/schema/)
- :triangular_ruler: [Specification — Ruleset](https://shipyard.naftiko.io/docs/1.0.0-alpha3/ikanos/ruleset/)
- :keyboard: [CLI Reference](https://shipyard.naftiko.io/docs/1.0.0-alpha3/ikanos/cli/)
- :ocean: [FAQ](https://shipyard.naftiko.io/docs/1.0.0-alpha3/ikanos/faq/)
- :mega: [Releases](https://shipyard.naftiko.io/docs/1.0.0-alpha3/ikanos/releases/)
- :telescope: [Roadmap](https://shipyard.naftiko.io/docs/1.0.0-alpha3/ikanos/roadmap/)
- :nut_and_bolt: [Contribute](https://github.com/naftiko/ikanos/blob/main/CONTRIBUTING.md)

## :video_game: Try it in the Playground

Want to see Ikanos in action without installing anything? The upcoming **Shipyard Playground** lets you author a capability, lint it live with **Polychro**, and serve it with **Ikanos** — all from your browser. Keep an eye on the [Shipyard](https://shipyard.naftiko.io/docs/1.0.0-alpha3/) for its release.

***

## Part of the Naftiko Fleet

Ikanos is part of the [Naftiko Fleet (Community Edition)](https://shipyard.naftiko.io/docs/1.0.0-alpha3/fleet/), which adds free complementary tools:

| Tool | What it does |
|---|---|
| [Polychro](https://shipyard.naftiko.io/docs/1.0.0-alpha3/polychro/) | Polyglot linter that validates Ikanos capabilities against the schema and ruleset. |
| [Crafter](https://shipyard.naftiko.io/docs/1.0.0-alpha3/fleet/crafter/) | Free Naftiko extension for Visual Studio Code to help with editing and linting Ikanos capabilities. |
| [Warden](https://shipyard.naftiko.io/docs/1.0.0-alpha3/fleet/) | Naftiko custom templates for CNCF's Backstage to help with scaffolding and cataloguing Ikanos capabilities. |
| [Skipper](https://shipyard.naftiko.io/docs/1.0.0-alpha3/fleet/skipper/) | Operator and Helm chart for CNCF's Kubernetes to help with the operations of Ikanos capabilities. |

Please join the community of users and contributors in [this GitHub Discussion forum!](https://github.com/orgs/naftiko/discussions)

<img src="https://naftiko.github.io/docs/images/navi/navi_hello.svg" width="50">
