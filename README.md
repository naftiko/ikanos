# Naftiko Framework

[![Coverage](https://img.shields.io/endpoint?url=https://gist.githubusercontent.com/farah-t-trigui/50bfcb34f6512cbad2dd4f460bfc6526/raw/framework-coverage.json)](https://github.com/naftiko/framework/actions/workflows/quality-gate.yml)
[![Bugs](https://img.shields.io/endpoint?url=https://gist.githubusercontent.com/farah-t-trigui/50bfcb34f6512cbad2dd4f460bfc6526/raw/framework-bugs.json)](https://github.com/naftiko/framework/actions/workflows/quality-gate.yml)
[![Trivy](https://img.shields.io/endpoint?url=https://gist.githubusercontent.com/farah-t-trigui/50bfcb34f6512cbad2dd4f460bfc6526/raw/framework-trivy.json)](https://github.com/naftiko/framework/actions/workflows/quality-gate.yml)
[![Gitleaks](https://img.shields.io/endpoint?url=https://gist.githubusercontent.com/farah-t-trigui/50bfcb34f6512cbad2dd4f460bfc6526/raw/framework-gitleaks.json)](https://github.com/naftiko/framework/actions/workflows/quality-gate.yml)

Welcome to Naftiko Framework, the first Open Source project for [Spec-Driven Integration](https://github.com/naftiko/framework/wiki/Spec%E2%80%90Driven-Integration) reinventing API integration for the AI era with governed and versatile capabilities that streamline API sprawl from massive SaaS and microservices growth.

<img src="https://naftiko.github.io/docs/images/technology/architecture_capability.png" width="600">

Each capability is a coarse piece of domain that consumes existing HTTP-based APIs then exposes them in several protocols to enable AI integration and self-integrating agents. Naftiko Framework includes a specification, an engine and a CLI.

| Feature | Description |
|---|---|
| Spec-Driven Integration | Declare capabilities entirely in **YAML** — no Java required |
| Multi-Protocol Servers | Expose capabilities via **MCP**, **SKILL**, or **REST** servers out of the box |
| Control Port | Built-in management plane with **health**, **metrics**, **traces**, and **status** endpoints |
| Cloud Native | **OpenTelemetry** tracing & RED metrics, **Prometheus** scrape, ready-to-run **Docker** container |
| Data Format Conversion | Transform **Protobuf**, **XML**, **YAML**, **CSV**, **TSV**, **PSV**, **Avro**, **HTML**, and **Markdown** payloads into JSON |
| HTTP API Consumption | Connect to any HTTP-based API with built-in authentication support |
| Templating & Querying | Use **Mustache** templates and **JSONPath** expressions for flexible data mapping |
| Domain-Driven Aggregates | Define reusable domain functions once, expose via multiple adapters — inspired by **DDD** Aggregate pattern |
| Server Authentication | Secure exposed endpoints with **Bearer**, **API Key**, **Basic**, **Digest**, or **OAuth 2.1** authentication out of the box |
| AI Native | Designed for Context Engineering and Agent Orchestration, making capabilities directly consumable by AI agents |
| OpenAPI Interoperability | Import **Swagger 2.0**, **OAS 3.0/3.1** into consumes adapters, export REST adapters as **OpenAPI** documents |
| Docker Native | Ships as a ready-to-run **Docker** container |
| Extensible | Open-source core extensible with new protocols and adapters |

***

Here are additional documents to learn more:

- :compass: [Spec-Driven Integration](https://github.com/naftiko/framework/wiki/Spec%E2%80%90Driven-Integration)
- :rowboat: [Installation](https://github.com/naftiko/framework/wiki/Installation)
- :sailboat: [Tutorial - Part 1](https://github.com/naftiko/framework/wiki/Tutorial-%E2%80%90-Part-1)
- :speedboat: [Tutorial - Part 2](https://github.com/naftiko/framework/wiki/Tutorial-%E2%80%90-Part-2)
- :ship: [Guide - Use Cases](https://github.com/naftiko/framework/wiki/Guide-%E2%80%90-Use-Cases)
- :mag: [Guide - Linting](https://github.com/naftiko/framework/wiki/Guide-%E2%80%90-Linting)
- :anchor: [Specification - Schema](https://github.com/naftiko/framework/wiki/Specification-%E2%80%90-Schema)
- :triangular_ruler: [Specification - Rules](https://github.com/naftiko/framework/wiki/Specification-%E2%80%90-Rules)
- :ocean: [FAQ](https://github.com/naftiko/framework/wiki/FAQ)
- :mega: [Releases](https://github.com/naftiko/framework/wiki/Releases)
- :telescope: [Roadmap](https://github.com/naftiko/framework/wiki/Roadmap)
- :nut_and_bolt: [Contribute](https://github.com/naftiko/framework/blob/main/CONTRIBUTING.md)

***

Naftiko Framework is part of [Naftiko Fleet (Community Edition)](https://github.com/naftiko/fleet), which adds free complementary tools:

| Tool | What it does |
|---|---|
| [Naftiko Extension for VS Code](https://github.com/naftiko/fleet/wiki/Naftiko-Extension-for-VS-Code) | Inline structure and rules validation while editing `.naftiko.yaml` files |
| [Naftiko Templates for Backstage](https://github.com/naftiko/fleet/wiki/Naftiko-Templates-for-Backstage) | Scaffold and catalog capabilities from CNCF Backstage |
| Naftiko Operator for Kubernetes | Deploy and operate capabilities on Kubernetes *(coming soon)* |

Please join the community of users and contributors in [this GitHub Discussion forum!](https://github.com/orgs/naftiko/discussions).

<img src="https://naftiko.github.io/docs/images/navi/navi_hello.svg" width="50">
