<img src="https://naftiko.github.io/docs/images/logos/logo_ikanos_horizontal.png" width="300">

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Coverage](https://img.shields.io/endpoint?url=https://gist.githubusercontent.com/farah-t-trigui/50bfcb34f6512cbad2dd4f460bfc6526/raw/framework-coverage.json)](https://github.com/naftiko/ikanos/actions/workflows/quality-gate.yml)
[![Bugs](https://img.shields.io/endpoint?url=https://gist.githubusercontent.com/farah-t-trigui/50bfcb34f6512cbad2dd4f460bfc6526/raw/framework-bugs.json)](https://github.com/naftiko/ikanos/actions/workflows/quality-gate.yml)
[![Trivy](https://img.shields.io/endpoint?url=https://gist.githubusercontent.com/farah-t-trigui/50bfcb34f6512cbad2dd4f460bfc6526/raw/framework-trivy.json)](https://github.com/naftiko/ikanos/actions/workflows/quality-gate.yml)
[![Gitleaks](https://img.shields.io/endpoint?url=https://gist.githubusercontent.com/farah-t-trigui/50bfcb34f6512cbad2dd4f460bfc6526/raw/framework-gitleaks.json)](https://github.com/naftiko/ikanos/actions/workflows/quality-gate.yml)

Welcome to **Ikanos**, the first Open Source project for [Spec-Driven Integration](https://shipyard.naftiko.io/docs/1.0.0-alpha4/concepts/spec-driven-integration/) — reinventing API integration for the AI era with governed, versatile **capabilities** that streamline the API sprawl created by massive SaaS and microservices growth.

> Ikanos comes from the Greek *ικανός* — **capable**.

<img src="https://naftiko.github.io/docs/images/technology/architecture_capability.png" width="700">

## Ikanos in one sentence

**Ikanos reads one YAML file that describes a slice of your business — say *customers* or *orders* — connects it to the HTTP/REST APIs you already have, and instantly serves that same slice to AI agents, web apps, and partners over several protocols at once.** One source of truth, many audiences, no code to write or compile.

That's the whole idea. The rest of this README explains what that means for *you* — and "you" depends on what you're trying to build.

## Who are you?

Ikanos solves a different problem depending on where you sit. Find yourself below.

### :robot: You're building AI agents

> *You want your AI assistant or agent to reach real business systems — safely, without hand-writing and maintaining a pile of glue code.*

**What Ikanos can do for you:**

- **Give agents governed tools without writing handlers.** Describe an operation in YAML and Ikanos serves it as an **MCP** tool (the open standard AI clients speak) — no Python or Java handler to build and trust.
- **Send the model exactly the context it needs.** Trim and reshape big API responses down to the few fields a task actually uses, so prompts stay relevant and cheap.
- **Compose context from several systems at once.** Join a customer, their orders, and their open tickets from three different APIs into one clean result the agent receives in a single call.
- **Stay in control of what the AI produced.** The capability is plain, reviewable YAML — it shows up as a readable diff in a pull request and can be linted before it ever runs.

→ Start with the **[Context Engineering tutorial](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/tutorials/track-1-context-engineering/)**.

### :electric_plug: You own a sprawl of APIs, microservices, or a monolith

> *You're a platform or integration team, and the systems you expose are messy, inconsistent, or hard for other teams to consume.*

**What Ikanos can do for you:**

- **Elevate an existing API into something easier to consume.** Put a stable, business-oriented contract in front of vendor-native or legacy endpoints — and even fix their semantics (turn a read-only `POST` query into a cacheable `GET`).
- **Rightsize microservices or a monolith.** Hide service fragmentation behind one curated namespace, or carve focused capabilities out of a broad monolith so each consumer gets only what it needs.
- **Convert formats for free.** Upstream returns XML, CSV, Avro, Protobuf, or Markdown? Ikanos normalizes it to clean JSON on the way out.
- **Govern it in CI.** Conventions — naming, security, path rules — are enforced by [Polychro](https://shipyard.naftiko.io/docs/1.0.0-alpha4/polychro/) linting before anything ships.

→ Start with the **[API Reusability tutorial](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/tutorials/track-2-api-reusability/)**.

### :bricks: You want one source of truth for every kind of client

> *You're a product or partner-facing developer, and the same domain has to be reachable by web apps, partner integrations, **and** AI agents — without maintaining three separate implementations.*

**What Ikanos can do for you:**

- **Write the domain once, expose it many ways.** A single capability is served as **REST** for conventional clients, **MCP** for AI agents, and **Skill** for agent skill catalogs — from the same YAML.
- **Keep contracts in sync automatically.** Define a reusable domain function once (an *aggregate*) and project it onto each protocol; no drift between your REST API and your MCP tools.
- **Interoperate with what you already have.** Import an existing **OpenAPI** document to bootstrap a capability, and export your REST surface back out as OpenAPI for downstream consumers.

> Today Ikanos serves **MCP**, **Skill**, **REST**, and a **Control** management plane. More downstream protocols (such as gRPC and webhooks) are on the [Roadmap](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/roadmap/) — and because the source of truth is one declarative spec, adding an audience never means rewriting your integration.

→ Browse all scenarios in **[Use Cases](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/concepts/use-cases/)**, or see **[Who is it for?](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/concepts/who-is-it-for/)** for the full persona map.

## Under the hood

Ikanos is a **capability engine** that reads an Ikanos YAML specification at startup and immediately serves it as a multi-protocol server — **MCP**, **Skill**, **REST**, and **Control** — with **no code generation and no compilation step**. The spec *is* the artifact and the runtime contract.

Each capability is a coarse-grained slice of a domain. It consumes existing HTTP-based APIs, optionally orchestrates and transforms the data, then exposes the result in several protocols so that AI agents, web apps, and partners can all consume it the same way. The project ships as three pieces: a **specification**, an **engine**, and a **CLI**.

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

Ikanos asks the AI for something simpler and safer: a **declarative YAML capability** validated against a stable schema. Generated YAML is easier for a model to get right than framework code, it shows up as a readable diff in a pull request, and it can be linted (with [Polychro](https://shipyard.naftiko.io/docs/1.0.0-alpha4/polychro/)) before it ever runs — so a human stays in control of what the AI produced. One capability can also consume and compose several upstream APIs (for example, joining a customer, their orders, and their open tickets from three services) and expose the result over MCP, Skill, and REST at once.

Ikanos is also designed to fit alongside these tools rather than replace them: it imports OpenAPI, exposes MCP, and integrates with LangChain4j as in-process tools, so adopting it usually means keeping your runtime and replacing hand-written glue with an AI-authored, reviewable spec.

## :anchor: Dive deeper

For a complete, always-up-to-date Ikanos documentation, start here and keep exploring:

- :compass: [Concepts — Spec-Driven Integration](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/concepts/spec-driven-integration/)
- :rowboat: [Installation](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/installation/)
- :sailboat: [Tutorials](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/tutorials/)
- :ship: [Use Cases](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/concepts/use-cases/)
- :star: [Features](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/features/)
- :balance_scale: [Comparison](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/comparison/)
- :anchor: [Specification](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/schema/)
- :keyboard: [Guide - CLI](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/guide/cli)
- :ocean: [FAQ](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/faq/)
- :mega: [Releases](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/releases/)
- :telescope: [Roadmap](https://shipyard.naftiko.io/ikanos/1.0.0-alpha4/roadmap/)
- :nut_and_bolt: [Contribute](https://github.com/naftiko/ikanos/blob/main/CONTRIBUTING.md)

## :video_game: Try it in the Playground

Want to see Ikanos in action without installing anything? The **[Shipyard Playground](https://shipyard.naftiko.io/playground)** lets you author a capability, lint it live with **Polychro**, and serve it with **Ikanos** — all from your browser. Jump straight into a guided tutorial:

- **[Track 1 — Context Engineering](https://shipyard.naftiko.io/playground/tutorial/1)** (mock → live → auth → shaping → legacy → writes → lookups)
- **[Track 2 — API Reusability](https://shipyard.naftiko.io/playground/tutorial/8)** (skill groups → aggregates → REST)
- **[Track 3 — API Orchestration](https://shipyard.naftiko.io/playground/tutorial/11)** (the Fleet Manifest capstone)

***

## Part of the Naftiko Fleet

Ikanos is part of the [Naftiko Fleet](https://shipyard.naftiko.io/docs/1.0.0-alpha4/fleet/), which adds  complementary tools:

| Tool | What it does |
|---|---|
| [Crafter](https://shipyard.naftiko.io/fleet/1.0.0-alpha4/crafter/) | Free Naftiko extension for Visual Studio Code to help with editing and linting Ikanos capabilities. |
| [Warden](https://shipyard.naftiko.io/fleet/1.0.0-alpha4/warden/) | Naftiko custom templates for CNCF's Backstage to help with scaffolding and cataloguing Ikanos capabilities. |
| [Skipper](https://shipyard.naftiko.io/fleet/1.0.0-alpha4/skipper/) | Operator and Helm chart for CNCF's Kubernetes to help with the operations of Ikanos capabilities. |

<img src="https://naftiko.github.io/docs/images/navi/navi_hello.svg" width="50">
