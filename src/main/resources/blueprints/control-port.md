# Control Port

## A Dedicated Management Adapter for Development and Operations

**Status**: Proposal

**Date**: April 17, 2026

**Spec Version**: `1.0.0-alpha2`

**Backlog**: [#15 — Control port: Status](https://github.com/naftiko/framework/issues/15), [#16 — Control port: Information](https://github.com/naftiko/framework/issues/16)

**Key Concept**: Introduce a new `type: "control"` exposed adapter that provides a single management surface for every capability — health checks, Prometheus metrics, runtime configuration, diagnostic endpoints, and CLI integration — isolated from business traffic on a dedicated port. Governance metadata (dependency inventory, compliance labels) is handled by the `naftiko catalog` CLI command, which generates a Backstage-native `catalog-info.yaml` from the capability YAML. Phase 1 aligns with [OpenTelemetry Observability](opentelemetry-observability.md) to deliver a unified observability foundation.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Goals and Non-Goals](#goals-and-non-goals)
3. [Terminology](#terminology)
4. [Design Analogy](#design-analogy)
5. [Architecture Overview](#architecture-overview)
6. [Core Concepts](#core-concepts)
7. [CLI ↔︎ Control Port Interaction](#cli--control-port-interaction)
8. [Specification and Schema Changes](#specification-and-schema-changes)
9. [Capability YAML Examples](#capability-yaml-examples)
10. [Endpoint Catalog](#endpoint-catalog)
11. [Runtime Design](#runtime-design)
12. [Relationship with OpenTelemetry Observability Blueprint](#relationship-with-opentelemetry-observability-blueprint)
13. [Security Considerations](#security-considerations)
14. [Testing Strategy](#testing-strategy)
15. [Implementation Roadmap](#implementation-roadmap)
16. [Risks and Mitigations](#risks-and-mitigations)
17. [Acceptance Criteria](#acceptance-criteria)

---

## Executive Summary

### What This Proposes

Add a new `type: "control"` server adapter in `capability.exposes` that provides a **management plane** for every Naftiko capability. The control port is not a business adapter — it does not expose tools, operations, or skills. Instead, it exposes a fixed set of **engine-provided endpoints** grouped into three domains:

| Domain | Purpose | Example Endpoints |
| --- | --- | --- |
| **Operations** | OTel-integrated health, metrics, trace inspection, and production readiness signals | Health/readiness/liveness probes, Prometheus `/metrics`, `/traces/active`, runtime info, adapter lifecycle, dependency health |
| **Development** | Runtime introspection, configuration, and CLI-driven diagnostics | Configuration reload, spec introspection, live log level control, step dry-run |

Governance metadata (dependency inventory, compliance labels) is served **statically** via `naftiko catalog`, which generates a Backstage `catalog-info.yaml` — not via a runtime endpoint.

The control port runs on a **dedicated port**, isolated from business traffic, and is managed by the engine — capability authors declare it but do not define its endpoints.

A key design principle is that the **Naftiko CLI becomes a first-class consumer** of the control port. The CLI can connect to a running capability's control port to provide developers with live diagnostics, log streaming, configuration reload, and trace inspection — bridging the gap between static YAML authoring and runtime behavior.

### Why This Fits Naftiko

Naftiko capabilities are declarative YAML documents that wire consumed APIs to exposed adapters. Today, there is no standard way to:

- Check if a running capability is healthy
- Scrape Prometheus metrics without conflicting with business ports
- Reload configuration without restarting the process
- Inspect the resolved capability spec at runtime
- Generate governance metadata for platform cataloging
- Control log verbosity at runtime without restarting
- Inspect active OTel traces for debugging
- Validate a YAML change against a running capability before applying it
- Stream structured logs from a running capability to the developer's terminal

Every production-grade framework solves this with a management port (Spring Boot Actuator on `:8081`, Quarkus management interface, Quarkus Dev UI, Envoy Admin). The control port brings this to Naftiko while preserving its declarative philosophy: declare `type: "control"` in YAML, get a full management surface with zero code. The CLI integration adds a developer experience layer comparable to `quarkus dev` or `spring-boot-devtools` — but driven by a standalone binary rather than a build plugin.

### Why a Separate Adapter (Not Embedded in Business Adapters)

1. **Port isolation** — Infrastructure tools (Prometheus, Kubernetes probes, service mesh sidecars) expect management endpoints on a dedicated port. Mixing them with business traffic creates routing complexity, security risks, and port conflicts.
2. **Independent lifecycle** — The control port must respond to health probes even when business adapters are overloaded, restarting, or misconfigured.
3. **Unified surface** — A capability may expose multiple business adapters (REST on `:8080`, MCP on `:3000`). The control port provides a single address for all management concerns, regardless of how many business adapters are running.
4. **Security boundary** — Management endpoints (config reload, debug info) should never be accidentally exposed on a public-facing business port. A separate adapter makes this explicit.
5. **CLI integration point** — The control port gives the Naftiko CLI a stable, well-known address to connect to for live diagnostics. The CLI does not need to guess which adapter to talk to or parse transport-specific protocols — it always targets `localhost:<control-port>`.

### Value

| Benefit | Impact |
| --- | --- |
| **Kubernetes-native** | Standard `/health/live` and `/health/ready` endpoints for probe configuration |
| **Prometheus integration** | `/metrics` on a dedicated port — no conflict with business adapters |
| **OTel-first observability** | Traces, metrics, and logs flow through OpenTelemetry; the control port serves the Prometheus scrape surface |
| **Zero-downtime config** | Reload capability configuration without process restart |
| **Spec introspection** | Query the resolved capability spec at runtime for debugging and cataloging |
| **Backstage catalog generation** | `naftiko catalog` generates `catalog-info.yaml` for Backstage integration — static, file-first, native to the platform catalog model |
| **CLI-driven development** | `naftiko health`, `naftiko logs`, `naftiko config reload` — live diagnostics from the terminal |
| **Live log level control** | Change log verbosity at runtime without restarting — debug a production issue, then restore normal levels |
| **Trace inspection** | Query active and recent OTel traces directly from the engine — no external collector required for basic debugging |
| **Consistent across adapters** | Same management surface whether the capability exposes REST, MCP, Skill, or all three |

### Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
| --- | --- | --- | --- |
| Config reload causes runtime inconsistency | Medium | High | Atomic swap with validation; reject invalid specs |
| Management port exposed to public internet | Medium | High | Bind to `localhost` by default; document network policy |
| Debug endpoints leak sensitive data | Low | High | Redact secrets from introspection; auth required for debug |
| Port conflict with business adapters | Low | Low | Schema validation rejects duplicate ports |
| Feature creep — control port becomes a second REST API | Low | Medium | Endpoints are engine-provided, not user-defined |
| CLI assumes control port is always available | Medium | Low | CLI detects missing control port and suggests adding it; graceful fallback to static validation |
| Log level changes persist across restarts | Low | Medium | Log level changes are ephemeral — cleared on restart; never written to YAML |
| Trace buffer memory pressure | Low | Medium | Ring buffer with configurable size; oldest traces evicted first |

---

## Goals and Non-Goals

### Goals

1. Introduce `control` as a new exposed adapter type, joining `rest`, `mcp`, `skill`.
2. Provide built-in health, readiness, and liveness endpoints for Kubernetes probe integration.
3. Host the Prometheus `/metrics` scrape endpoint — absorbing Phase 2 of the [OpenTelemetry Observability](opentelemetry-observability.md) blueprint.
4. Align Phase 1 with [OpenTelemetry Observability](opentelemetry-observability.md) Phases 0–1 to deliver health, metrics, and trace-aware info as a unified observability foundation.
5. Support runtime configuration reload (hot reload of capability YAML without process restart).
6. Expose a read-only spec introspection endpoint for debugging and catalog integration.
7. Provide a `naftiko catalog` CLI command that generates Backstage-native `catalog-info.yaml` from the capability YAML — dependency inventory, adapter summary, and compliance labels are served statically, not via a runtime endpoint.
8. Keep the control port engine-managed — capability authors declare it, the engine provides the endpoints.
9. Bind to `localhost` by default for security; allow override via `address`.
10. Provide a **CLI integration surface** — the Naftiko CLI connects to the control port for live diagnostics (`status`, `logs`, `reload`, `trace`, `validate --live`).
11. Support **live log level control** — change logging verbosity at runtime via `/logs` endpoint.
12. Support **trace inspection** — query recent OTel traces from an in-engine ring buffer via `/traces`.
13. Support **dry-run validation** — CLI sends a modified YAML to the control port for validation against the running engine's state before applying.

### Non-Goals (This Proposal)

1. User-defined endpoints on the control port — it is not a second REST API.
2. Full APM or profiling — those belong in the [OpenTelemetry Observability](opentelemetry-observability.md) blueprint.
3. Cluster-wide orchestration or service discovery — the control port is per-capability.
4. WebSocket or streaming management connections — log streaming uses HTTP long-polling or SSE, not WebSocket.
5. Runtime governance endpoints — governance metadata is static (generated from YAML via `naftiko catalog`), not served by the control port.
6. Full distributed tracing UI — use Datadog APM, Jaeger, or Grafana Tempo for visual trace exploration. The control port provides API access to recent traces, not a UI.
7. CLI as a daemon or persistent process — CLI commands are stateless; they connect, query, and disconnect.

---

## Terminology

| Term | Definition |
| --- | --- |
| **Control port** | A dedicated HTTP port for management traffic, isolated from business adapters |
| **Health probe** | An HTTP endpoint that reports the operational status of the capability (`UP`, `DOWN`, `DEGRADED`) |
| **Liveness probe** | Kubernetes concept — indicates the process is alive and not deadlocked; failure triggers a restart |
| **Readiness probe** | Kubernetes concept — indicates the capability is ready to serve traffic; failure removes it from the load balancer |
| **Hot reload** | Replacing the active capability spec at runtime without stopping the process |
| **Spec introspection** | Querying the resolved (post-merge, post-bind) capability specification at runtime |
| **Governance metadata** | Structured information about a capability's dependencies, adapters, and compliance posture — generated statically via `naftiko catalog` as a Backstage `catalog-info.yaml` |
| **RED metrics** | Rate, Errors, Duration — the standard SRE metric set, exposed via Prometheus |
| **Trace ring buffer** | A fixed-size, in-memory circular buffer that retains the most recent N completed OTel trace summaries for local inspection via `/traces` |
| **Live validation** | Sending a modified YAML to the control port for schema + rule validation against the running engine's resolved state — without applying the change |
| **Ephemeral configuration** | Runtime changes (log levels, sampling rates) that take effect immediately but do not survive a process restart — never persisted to YAML |
| **CLI control session** | A stateless HTTP request from the `naftiko` CLI to the control port — connect, query/mutate, disconnect |

---

## Design Analogy

### How the control port relates to existing adapters

```
REST Adapter           MCP Adapter            Skill Adapter          Control Adapter (proposed)
────────────           ───────────            ─────────────          ─────────────────────────
ExposesRest            ExposesMcp             ExposesSkill           ExposesControl
├─ namespace           ├─ namespace           ├─ namespace           ├─ (no namespace — singleton)
├─ port                ├─ port                ├─ port                ├─ port
├─ address             ├─ address             ├─ address             ├─ address (default: localhost)
├─ authentication      ├─ transport           ├─ authentication      ├─ authentication (reuses Authentication)
│                      │                      │                      │
├─ resources[]         ├─ tools[]             ├─ skills[]            ├─ (no user-defined constructs)
│  └─ operations[]     │  └─ call/steps       │  └─ tools[]          │
│     └─ call/steps    │                      │     └─ call/steps    ├─ Engine-provided endpoints:
│                      ├─ resources[]         │                      │  ├─ Operations:
│                      │                      │                      │  │  ├─ /health/live
│                      ├─ prompts[]           │                      │  │  ├─ /health/ready
│                      │                      │                      │  │  ├─ /lifecycle             (future)
│                      │                      │                      │  │  ├─ /lifecycle/{namespace} (future)
│                      │                      │                      │  │  ├─ /metrics
│                      │                      │                      │  │  └─ /traces
│                      │                      │                      │  ├─ Development:
│                      │                      │                      │  │  ├─ /config
│                      │                      │                      │  │  ├─ /config/reload
│                      │                      │                      │  │  ├─ /config/validate
│                      │                      │                      │  │  ├─ /logs
│                      │                      │                      │  │  ├─ /logs/{logger}
│                      │                      │                      │  │  └─ /status
│                      │                      │                      │  └─ Governance:
│                      │                      │                      │     (static — via naftiko catalog CLI)
│                      │                      │                      │
│                      │                      │                      ├─ CLI consumer:
│                      │                      │                      │  naftiko config          → /config
│                      │                      │                      │  naftiko config reload   → /config/reload
│                      │                      │                      │  naftiko config validate --live → /config/validate
│                      │                      │                      │  naftiko health          → /health/*
│                      │                      │                      │  naftiko lifecycle       → /lifecycle (future)
│                      │                      │                      │  naftiko logs            → /logs
│                      │                      │                      │  naftiko status          → /status
│                      │                      │                      │  naftiko traces          → /traces
```

### Key difference from business adapters

Business adapters expose **user-defined constructs** (tools, operations, skills) backed by orchestration steps. The control adapter exposes **engine-defined endpoints** backed by the engine's own internal state. Capability authors control *whether* the control port is enabled and *where* it listens — not *what* it serves.

### Analogy to established frameworks

| Framework | Management Surface | Default Port | CLI Integration |
| --- | --- | --- | --- |
| Spring Boot | Actuator (`/actuator/*`) | `management.server.port` (separate from app) | `spring-boot-devtools` (automatic restart, live reload via JMX) |
| Quarkus | Management interface (`/q/*`) + Dev UI | `quarkus.management.port` (9000) | `quarkus dev` (continuous testing, Dev UI dashboard, hot reload) |
| Envoy | Admin interface (`/`) | `admin.address` (typically 15000) | `envoy --admin-address-path` + REST API consumers |
| Micronaut | Management endpoints (`/health`, `/info`) | `endpoints.all.port` | `mn` CLI for scaffolding; management via HTTP |
| **Naftiko** | **Control adapter (`/`)** | **`exposes[type=control].port`** | **`naftiko config/health/logs/metrics/status/traces` via control port HTTP API** |

---

## Architecture Overview

### Current State

```
Capability
├─ exposes:
│  ├─ type: rest   (port 8080)   ← business traffic
│  ├─ type: mcp    (port 3000)   ← business traffic
│  └─ type: skill  (port 4000)   ← business traffic
│
├─ No health endpoints
├─ No metrics endpoint (Prometheus has nothing to scrape)
├─ No config reload (must restart)
├─ No spec introspection
└─ No governance metadata
```

### Proposed State

```
Capability
├─ exposes:
│  ├─ type: rest    (port 8080)  ← business traffic
│  ├─ type: mcp     (port 3000)  ← business traffic
│  ├─ type: skill   (port 4000)  ← business traffic
│  └─ type: control (port 9090)  ← management traffic
│     │
│     ├─ Operations:
│     │  ├─ GET /health/live         → { "status": "UP" }
│     │  ├─ GET /health/ready        → { "status": "UP" } or 503
│     │  ├─ GET /lifecycle           → adapter states (future)
│     │  ├─ PUT /lifecycle/{ns}      → set adapter state: up, down, drain (future)
│     │  ├─ GET /metrics             → Prometheus exposition format
│     │  └─ GET /traces              → recent trace summaries from ring buffer
│     │
│     ├─ Development:
│     │  ├─ GET  /config             → resolved capability spec (redacted)
│     │  ├─ POST /config/reload      → trigger hot reload
│     │  ├─ POST /config/validate    → dry-run validation of a modified YAML
│     │  ├─ GET  /logs               → current log levels for all loggers
│     │  ├─ PUT  /logs/{logger}      → change log level at runtime (ephemeral)
│     │  └─ GET  /status             → capability name, version, uptime, OTel status
│     │
│     └─ Governance:
│        (static — via `naftiko catalog` CLI, not a runtime endpoint)
│
├─ Kubernetes probes point to :9090/health/*
├─ Prometheus scrapes :9090/metrics
├─ Platform catalog reads catalog-info.yaml (generated by `naftiko catalog`)
└─ Naftiko CLI connects to :9090 for live diagnostics
```

### Traffic Isolation

```
                  ┌─────────────────┐
Business traffic  │                 │  Management traffic
─────────────────►│   Capability    │◄───────────────────
 :8080 (REST)     │                 │   :9090 (Control)
```

---

## Core Concepts

### 1. Engine-Provided Endpoints

Unlike business adapters where the user declares tools/operations/resources, the control port's endpoints are **provided by the engine**. The set of available endpoints is determined by:

- **Always present**: `/health/live`, `/health/ready`
- **When OTel is active**: `/metrics` (Prometheus scrape)
- **When enabled in spec**: `/config`, `/config/reload`, `/status`

Capability authors do not define routes, parameters, or output mappings on the control port.

### 2. Health Model

The health model distinguishes between three states:

| State | Liveness | Readiness | Meaning |
| --- | --- | --- | --- |
| `UP` | 200 | 200 | All adapters started, all consumed APIs reachable |
| `DEGRADED` | 200 | 503 | Process is alive but one or more adapters failed to start or a consumed API is unreachable |
| `DOWN` | 503 | 503 | Critical failure — process should be restarted |

**Liveness** answers: "Is the process alive?" — returns 200 unless the JVM is deadlocked or the control port itself is broken (which would mean no response at all).

**Readiness** answers: "Is the capability ready to serve business traffic?" — checks that all declared business adapters are started and listening.

### 3. Hot Reload

`POST /config/reload` triggers a re-read of the capability YAML file and an atomic swap of the internal spec. The reload follows these rules:

1. **Parse** the YAML file from disk (same path as the original load).
2. **Validate** the new spec against the JSON schema and Naftiko rules.
3. **Diff** the new spec against the current spec to determine what changed.
4. **Reject** if structural changes require a restart (e.g., adding/removing adapters, changing ports).
5. **Apply** safe changes atomically (e.g., updated operation parameters, new steps, modified output mappings).
6. **Report** the result in the response: `{ "status": "applied", "changes": [...] }` or `{ "status": "rejected", "reason": "..." }`.

Changes that always require a restart (rejected by reload):
- Adding or removing an `exposes` adapter
- Changing an adapter's `port` or `address`
- Adding or removing a `consumes` adapter
- Changing `info.label`

Changes that are safe to hot-reload:
- Modified `steps` in an operation/tool/procedure
- Updated `outputParameters` mappings
- Changed `inputParameters` (name, type, description, required)
- Updated `aggregates` function definitions
- Modified `authentication` credentials (re-bind)

### 4. Spec Introspection

`GET /config` returns the **resolved** capability specification — after aggregate ref resolution, bind substitution, and import merging. This is the spec as the engine sees it at runtime.

**Security**: Secrets referenced via `binds` are **redacted** in the response (replaced with `"***"`). The introspection endpoint never exposes raw credentials.

### 5. Backstage Catalog Generation (Static Governance)

Governance metadata (dependency inventory, adapter summary, compliance labels) is not served as a runtime endpoint. Instead, the `naftiko catalog` CLI command generates a Backstage-native `catalog-info.yaml` from the capability YAML:

```bash
$ naftiko catalog my-capability.yaml
```

**Output** (`catalog-info.yaml`) — given `info.label: "Weather Service"`:

```yaml
apiVersion: backstage.io/v1alpha1
kind: Component
metadata:
  name: weather-service
spec:
  type: service
```

**Why static instead of runtime:**

- **Backstage is file-first** — its catalog indexes `catalog-info.yaml` from repos, not runtime APIs. A runtime `/governance` endpoint would require a custom Entity Provider plugin — extra complexity for no gain.
- **Governance data is declarative** — adapters, dependencies, and labels are declared in the capability YAML and don't change at runtime. There is no value in serving them from a live process.
- **Follows industry pattern** — Kubernetes, Istio, and Spotify's own Backstage deployment all use static labels/annotations for catalog metadata. Runtime endpoints serve operational data (health, metrics, traces), not catalog metadata.

**Mapping rules:**

| Capability YAML | Backstage Entity |
| --- | --- |
| `info.label` | `metadata.name` (slugified) |
| `info.labels` | `metadata.labels` |
| `info.tags` | `metadata.tags` (merged with adapter type tags) |
| `capability.exposes[type=rest\|mcp\|skill].namespace` | `spec.providesApis` |
| `capability.exposes[type=rest\|mcp\|skill].lifecycle` | `spec.lifecycle` (first non-nil value) |
| `capability.exposes[type=control].port` | `metadata.annotations["naftiko.io/control-port"]` |
| `capability.consumes[*].namespace` | `spec.consumesApis` + `spec.dependsOn` |

Labels live in `info.labels` (a top-level sibling of `capability`) — they are capability-level metadata, not control-port-specific. The catalog generator reads them from `info.labels` and maps them to `metadata.labels`. Control-port-derived annotations (e.g., `prometheus.io/scrape-port`, `naftiko.io/control-port`) are inferred automatically from `capability.exposes[type=control].port`.

> **Cross-reference**: The `lifecycle` and `tags` fields on `exposes` adapters, and the `tags` field on `consumes` entries, are proposed in the [Kubernetes Backstage Governance](kubernetes-backstage-governance.md) blueprint's Spec Metadata Taxonomy. When those fields land in the schema, the `naftiko catalog` generator will incorporate them into the Backstage entity (e.g., `lifecycle` → `spec.lifecycle`, `consumes[].tags` → scorecard facts). In Kubernetes environments, the [NaftikoCapability operator](kubernetes-backstage-governance.md#kubernetes-crd-and-operator) auto-generates Backstage entities from CRDs — `naftiko catalog` serves the non-Kubernetes / local-development path.

### 6. Live Log Level Control

`GET /logs` returns the current effective log level for all active loggers. `PUT /logs/{logger}` changes the level for a specific logger at runtime.

**Design:**

- Changes are **ephemeral** — they take effect immediately but are cleared on process restart. The control port never writes to `logback.xml` or the capability YAML.
- Log levels follow the SLF4J model: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `OFF`.
- The root logger level can be changed via `PUT /logs/ROOT`.
- Changes are applied via Logback's `LoggerContext` API — the same mechanism used by Spring Boot Actuator's `/loggers` endpoint.

**Example request:**

```
PUT /logs/io.naftiko.engine.consumes.http
Content-Type: application/json

{ "level": "DEBUG" }
```

**Example response from `GET /logs`:**

```json
{
  "loggers": {
    "ROOT": { "configuredLevel": "INFO", "effectiveLevel": "INFO" },
    "io.naftiko.engine": { "configuredLevel": null, "effectiveLevel": "INFO" },
    "io.naftiko.engine.consumes.http": { "configuredLevel": "DEBUG", "effectiveLevel": "DEBUG" }
  }
}
```

**Use case:** A developer notices unexpected behavior in a consumed API call. Instead of restarting with `-Dlogging.level.io.naftiko.engine.consumes.http=DEBUG`, they run `naftiko logs --level debug --logger io.naftiko.engine.consumes.http` and immediately see detailed HTTP client logs in the console.

### 7. Trace Inspection

`GET /traces` returns summaries of recent OTel traces from an in-engine ring buffer. This provides basic trace inspection without requiring an external collector (Jaeger, Datadog, Grafana Tempo).

**Design:**

- The engine maintains a **ring buffer** of the last N completed traces (default: 100, configurable via `endpoints.traces.bufferSize`).
- Each entry contains: trace ID, root span name, duration, status, timestamp, and span count.
- `GET /traces` returns the list, sorted by most recent first.
- `GET /traces/{traceId}` returns the full span tree for a specific trace.
- The ring buffer is populated by a custom OTel `SpanProcessor` that captures completed spans alongside the standard OTLP exporter.
- When OTel is in no-op mode (SDK JARs absent), the ring buffer is empty and `/traces` returns `[]`.

**Example response from `GET /traces`:**

```json
{
  "traces": [
    {
      "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
      "rootSpan": "mcp.request",
      "operation": "get-forecast",
      "duration": "PT0.342S",
      "status": "OK",
      "spanCount": 4,
      "startedAt": "2026-04-17T14:32:01.123Z"
    },
    {
      "traceId": "00f067aa0ba902b7",
      "rootSpan": "rest.request",
      "operation": "list-pages",
      "duration": "PT1.205S",
      "status": "ERROR",
      "spanCount": 3,
      "startedAt": "2026-04-17T14:31:58.456Z"
    }
  ],
  "bufferSize": 100,
  "bufferUsed": 47
}
```

**Use case:** A developer runs `naftiko traces` to see recent requests and quickly identify which operations are failing or slow — without needing a Jaeger or Datadog setup during local development.

### 8. Live Validation (Dry Run)

`POST /config/validate` accepts a modified capability YAML body and validates it against the running engine's state — without applying any changes. This is distinct from the static `naftiko config validate` command (without `--live`), which checks schema conformance only.

**What live validation adds over static validation:**

| Check | Static (`naftiko config validate`) | Live (`/config/validate`) |
| --- | --- | --- |
| JSON Schema conformance | Yes | Yes |
| Naftiko rules (Spectral) | Yes | Yes |
| Bind resolution — are all `externalRefs` resolvable? | No | Yes — checks against the running engine's resolved bindings |
| Import resolution — can all `consumes` imports be fetched? | No | Yes — tests actual network reachability |
| Structural change detection — would this require a restart? | No | Yes — diffs against current running spec |
| Port conflict detection (with other running processes) | No | Yes — checks actual port availability |

**Example request:**

```
POST /config/validate
Content-Type: application/x-yaml

naftiko: "1.0.0-alpha2"
info:
  label: Weather Service
  description: Updated weather service.
capability:
  ...
```

**Example response:**

```json
{
  "valid": true,
  "reloadable": true,
  "structuralChanges": [],
  "warnings": [
    "Bind 'API_KEY' resolves to a different value than the running instance — will take effect on reload"
  ]
}
```

**Use case:** Before running `naftiko config reload`, a developer runs `naftiko config validate --live my-capability.yaml` to confirm the change is safe and reloadable.

---

## CLI ↔︎ Control Port Interaction

### Design Philosophy

The Naftiko CLI (`io.naftiko.Cli`) currently provides two commands: `validate` (static schema validation) and `create capability` (interactive scaffolding). Both are **offline** — they operate on YAML files without connecting to a running engine.

The control port introduces a **live mode** for the CLI. When a capability is running with a control adapter, the CLI can connect to it over HTTP and provide runtime diagnostics, configuration management, and trace inspection. This mirrors the developer experience of `quarkus dev` and `spring-boot-devtools`, but as explicit CLI commands rather than an implicit build-tool plugin.

### Architecture

```
Developer Terminal                    Running Capability
──────────────────                    ──────────────────
naftiko config                ──────► GET  :9090/config
                              ◄────── (resolved YAML, secrets redacted)

naftiko config reload         ──────► POST :9090/config/reload
                              ◄────── { "status": "applied", "changes": [...] }

naftiko config validate --live ────► POST :9090/config/validate
  my-capability.yaml          ◄────── { "valid": true, "reloadable": true }

naftiko health                ──────► GET  :9090/health/ready
                              ◄────── { "status": "UP" }

naftiko lifecycle             ──────► GET  :9090/lifecycle        (future)
                              ◄────── { adapters: [{ namespace, state }] }

naftiko lifecycle rest        ──────► PUT  :9090/lifecycle/rest   (future)
  --state down                ◄────── 204 No Content

naftiko logs                  ──────► GET  :9090/logs
                              ◄────── { loggers: { ROOT: "INFO", ... } }

naftiko logs --level debug    ──────► PUT  :9090/logs/ROOT
  --logger io.naftiko.engine  ◄────── 204 No Content

naftiko metrics               ──────► GET  :9090/metrics
                              ◄────── (Prometheus exposition format)

naftiko status                ──────► GET  :9090/status
                              ◄────── { capability, engine, adapters, otel }

naftiko traces                ──────► GET  :9090/traces
                              ◄────── { traces: [...], bufferSize: 100 }

naftiko traces <traceId>      ──────► GET  :9090/traces/{traceId}
                              ◄────── { spans: [...] }
```

### Command Reference

### `naftiko config reload [--port <port>] [<file>]`

Trigger a hot reload of the capability configuration.

| Flag | Default | Description |
| --- | --- | --- |
| `--port` | `9090` | Control port to connect to |
| `<file>` | (none) | Optional: send this YAML file as the new spec (instead of re-reading from disk) |

When `<file>` is provided, the CLI sends the file content as the request body to `/config/reload`. When omitted, the engine re-reads from its original YAML path.

```
$ naftiko config reload
Reload applied: 2 changes
  - steps[0].call: weather-api/get-forecast → weather-api/get-detailed-forecast
  - outputParameters[0].mapping: $.forecast → $.detailedForecast
```

### `naftiko config [--port <port>] [--format <format>]`

Retrieve the resolved capability spec from the running engine. Connects to `/config`.

| Flag | Default | Description |
| --- | --- | --- |
| `--port` | `9090` | Control port to connect to |
| `--format` | `yaml` | Output format: `yaml` or `json` |

Secrets are redacted (`***`) in the output. This is useful for debugging ref resolution, bind substitution, and import merging without restarting.

### `naftiko config validate [--port <port>] [--live] <file> [<version>]`

Validate a capability YAML file. Extended with `--live` mode.

| Flag | Default | Description |
| --- | --- | --- |
| `--port` | `9090` | Control port to connect to (only used with `--live`) |
| `--live` | `false` | Validate against the running engine's state (requires control port) |
| `<file>` | (required) | Path to the YAML file to validate |
| `<version>` | (current) | Schema version to validate against (static mode only) |

**Static mode** (existing behavior): validates against the JSON Schema + Naftiko rules.

**Live mode** (new): sends the YAML to `/config/validate` on the running capability. Gets back schema validation + bind resolution + import resolution + structural change detection + reload safety assessment.

```
$ naftiko config validate --live my-capability.yaml
✓ Schema valid
✓ Rules passed (12/12)
✓ Binds resolvable (3/3)
✓ Imports reachable (1/1)
✓ Reloadable (no structural changes)
```

### `naftiko health [--port <port>]`

Connect to the control port and report the capability's health probe status.

| Flag | Default | Description |
| --- | --- | --- |
| `--port` | `9090` | Control port to connect to |

**Output:**

```
weather-service: UP (all 2 adapters ready)
```

### `naftiko lifecycle [--port <port>] [<namespace>] [--state <state>]`

Query or change the lifecycle state of business adapters.

| Flag | Default | Description |
| --- | --- | --- |
| `--port` | `9090` | Control port to connect to |
| `<namespace>` | (none) | Target a specific adapter by namespace |
| `--state` | (none) | Set adapter state: `up`, `down`, or `drain` |

**Query mode** (no `--state`): lists all business adapters with their current state.

```
$ naftiko lifecycle
ADAPTER    PORT   STATE
rest       :8080  started
mcp        :3000  started
skill      :4000  stopped
```

**Set mode** (with `--state`): changes the lifecycle state and confirms.

```
$ naftiko lifecycle rest --state down
rest :8080 → stopped
```

**Drain all** (no namespace, `--state drain`): drains all business adapters then stops the process.

```
$ naftiko lifecycle --state drain
Draining all adapters... done (3 adapters stopped, 0 in-flight requests)
```

### `naftiko logs [--port <port>] [--level <level>] [--logger <name>] [--follow]`

Query or change log levels on the running capability.

| Flag | Default | Description |
| --- | --- | --- |
| `--port` | `9090` | Control port to connect to |
| `--level` | (none) | Set this log level (TRACE, DEBUG, INFO, WARN, ERROR, OFF) |
| `--logger` | `ROOT` | Target logger name (Java package or class) |
| `--follow` | `false` | Stream log output via SSE (Server-Sent Events) from `/logs/stream` |

**Query mode** (no `--level`): displays current log levels.

**Set mode** (with `--level`): changes the level and confirms.

```
$ naftiko logs --level debug --logger io.naftiko.engine.consumes.http
Set io.naftiko.engine.consumes.http → DEBUG (ephemeral — cleared on restart)
```

**Follow mode** (with `--follow`): streams structured log entries to the terminal in real time. Uses SSE to avoid polling. Filters by logger if `--logger` is specified.

```
$ naftiko logs --follow --logger io.naftiko.engine.consumes.http
14:32:01.123 DEBUG [http-0] HttpClientAdapter — GET https://api.weather.gov/points/39.7456,-104.9994 → 200 (342ms)
14:32:02.456 DEBUG [http-0] HttpClientAdapter — response body: {"properties":{"forecast":"..."}}
^C
```

### `naftiko metrics [--port <port>] [--filter <pattern>]`

Fetch Prometheus metrics from the control port.

| Flag | Default | Description |
| --- | --- | --- |
| `--port` | `9090` | Control port to connect to |
| `--filter` | (none) | Regex filter on metric names (e.g., `naftiko.request.*`) |

### `naftiko status [--port <port>]`

Retrieve runtime information from the running capability (engine version, uptime, OTel status, and adapter details). Connects to `/status`.

| Flag | Default | Description |
| --- | --- | --- |
| `--port` | `9090` | Control port to connect to |

**Output:**

```
weather-service: UP
  Engine:    1.0.0-alpha2 (Java 21.0.3, native: false)
  Uptime:    2h 34m 12s
  OTel:      active (OTLP → http://localhost:4318)
  Adapters:
    mcp     :3000  started
    control :9090  started
```

### `naftiko traces [--port <port>] [<traceId>] [--operation <name>] [--status <status>]`

Inspect recent traces from the engine's ring buffer.

| Flag | Default | Description |
| --- | --- | --- |
| `--port` | `9090` | Control port to connect to |
| `<traceId>` | (none) | Show the full span tree for this trace |
| `--operation` | (none) | Filter traces by operation name |
| `--status` | (none) | Filter traces by status (OK, ERROR) |

**List mode** (no traceId):

```
$ naftiko traces
TRACE ID                          OPERATION        DURATION  STATUS  SPANS  TIME
4bf92f3577b34da6a3ce929d0e0e4736  get-forecast     342ms     OK      4      14:32:01
00f067aa0ba902b7                  list-pages       1.2s      ERROR   3      14:31:58
a3b4c5d6e7f8a9b0                  create-page      891ms     OK      5      14:31:55
```

**Detail mode** (with traceId):

```
$ naftiko traces 4bf92f3577b34da6a3ce929d0e0e4736
mcp.request [SERVER] 342ms OK
  └── step.call [INTERNAL] 320ms OK
  │     └── http.client.GET [CLIENT] 298ms OK
  │           https://api.weather.gov/points/39.7456,-104.9994 → 200
  └── step.lookup [INTERNAL] 12ms OK
        match: location-name → "Denver"
```

### Control Port Discovery

The CLI needs to know the control port address. Discovery follows this priority:

1. **Explicit `-port` flag** — always wins.
2. **`NAFTIKO_CONTROL_PORT` environment variable** — useful for CI and containerized environments.
3. **Parse the local YAML file** — if the CWD contains `naftiko.yaml`, the CLI reads it and extracts `exposes[type=control].port`. This is the zero-config local development path.
4. **Default `9090`** — fallback when no other source is available.

For `--address`, the same cascade applies via `NAFTIKO_CONTROL_ADDRESS` (default: `localhost`).

### Graceful Degradation

When the CLI attempts to connect and the control port is not available (capability not running, no control adapter declared), the CLI provides a helpful message:

```
$ naftiko health
Error: Cannot connect to control port at localhost:9090
Hint: Ensure your capability is running with a control adapter:
  exposes:
    - type: control
      port: 9090
```

The `validate` command (without `--live`) and `create capability` continue to work offline — they never require a running capability.

### Log Streaming Design

The `--follow` mode on `naftiko logs` uses a **Server-Sent Events (SSE)** endpoint (`GET /logs/stream`) on the control port:

1. The engine registers a custom Logback `Appender` that publishes log events to an internal `BlockingQueue`.
2. The SSE endpoint reads from the queue and writes each event as an SSE `data:` frame.
3. The CLI reads the SSE stream and formats each event for terminal output.
4. When the CLI disconnects (Ctrl+C), the SSE connection closes and the appender slot is freed.

**Concurrency:** Multiple CLI sessions can subscribe simultaneously. Each gets an independent queue fork (fan-out pattern). A configurable maximum number of subscribers (default: 5) prevents resource exhaustion.

**Filtering:** The SSE endpoint accepts query parameters: `?logger=io.naftiko.engine&level=DEBUG`. The engine-side appender filters before enqueuing — no wasted bandwidth for filtered-out events.

**Security note:** Log streaming can expose sensitive runtime data. It is disabled by default (`endpoints.logs.stream: false`) and should only be enabled in development environments.

---

## Specification and Schema Changes

### Extend `Info` with `labels`

Add an optional `labels` property to the existing `Info` definition:

```json
"Info": {
  "type": "object",
  "properties": {
    "label": {
      "$ref": "#/$defs/IdentifierLabel",
      "description": "Human-readable label for the capability."
    },
    "description": {
      "type": "string",
      "description": "Short description of the capability."
    },
    "labels": {
      "type": "object",
      "additionalProperties": { "type": "string" },
      "description": "Free-form key-value labels for governance and catalog integration (e.g., data-classification, team, cost-center). Mapped to metadata.labels in generated Backstage catalog-info.yaml."
    },
    "tags": {
      "type": "array",
      "items": { "type": "string" },
      "description": "Tags for categorization and discovery. Mapped to metadata.tags in generated Backstage catalog-info.yaml."
    }
  },
  "required": ["label"],
  "additionalProperties": false
}
```

### New `ExposesControl` Definition

```json
"ExposesControl": {
  "type": "object",
  "properties": {
    "type": {
      "const": "control"
    },
    "address": {
      "type": "string",
      "default": "localhost",
      "description": "Bind address for the control port. Defaults to localhost for security."
    },
    "port": {
      "type": "integer",
      "minimum": 1,
      "maximum": 65535,
      "description": "TCP port for the control adapter."
    },
    "authentication": {
      "$ref": "#/$defs/Authentication",
      "description": "Optional authentication for the control port. Reuses the same Authentication model as business adapters."
    },
    "endpoints": {
      "$ref": "#/$defs/ControlManagementSpec"
    },
    "observability": {
      "$ref": "#/$defs/ObservabilitySpec"
    }
  },
  "required": ["type", "port"],
  "unevaluatedProperties": false
}
```

> **Note**: Compliance labels (`environment`, `cost-center`, `owner`, etc.) belong in `info.labels` at the document root, not on the control adapter. The `naftiko catalog` CLI reads them from `info.labels` and maps them to `metadata.labels` in the generated Backstage entity. The control port's `port` value is mapped automatically to `metadata.annotations["naftiko.io/control-port"]`.

### `ControlManagementSpec` — Granular Management Endpoint Toggle

```json
"ControlManagementSpec": {
  "type": "object",
  "description": "Toggle individual control port management endpoint groups. Does not include OTel-dependent endpoints (metrics, traces) — those are configured under observability.",
  "properties": {
    "health": {
      "type": "boolean",
      "default": true,
      "description": "Enable /health/live and /health/ready endpoints."
    },
    "info": {
      "type": "boolean",
      "default": false,
      "description": "Enable /status and /config endpoints. Disabled by default — /config contains resolved spec details."
    },
    "reload": {
      "type": "boolean",
      "default": false,
      "description": "Enable POST /config/reload. Disabled by default — mutates runtime state."
    },
    "validate": {
      "type": "boolean",
      "default": false,
      "description": "Enable POST /config/validate (dry-run validation). Disabled by default."
    },
    "logging": {
      "type": "boolean",
      "default": false,
      "description": "Enable /logs endpoints for live log level control. Disabled by default — mutates runtime state."
    },
    "logs": {
      "$ref": "#/$defs/ControlLogsEndpointSpec",
      "description": "Configure the /logs/stream SSE endpoint for log streaming."
    }
  },
  "unevaluatedProperties": false
}

"ControlLogsEndpointSpec": {
  "type": "object",
  "properties": {
    "stream": {
      "type": "boolean",
      "default": false,
      "description": "Enable /logs/stream SSE endpoint. Disabled by default — can expose sensitive runtime data."
    },
    "maxSubscribers": {
      "type": "integer",
      "minimum": 1,
      "maximum": 20,
      "default": 5,
      "description": "Maximum concurrent SSE subscribers for log streaming."
    }
  },
  "unevaluatedProperties": false
}
```

### Update `ServerSpec` Discriminator

Add `control` to the `oneOf` in the `exposes` array:

```json
"exposes": {
  "type": "array",
  "items": {
    "oneOf": [
      { "$ref": "#/$defs/ExposesRest" },
      { "$ref": "#/$defs/ExposesMcp" },
      { "$ref": "#/$defs/ExposesSkill" },
      { "$ref": "#/$defs/ExposesControl" }
    ]
  }
}
```

### Validation Rules

Add to `naftiko-rules.yml`:

| Rule | Description |
| --- | --- |
| `control-port-singleton` | At most one `type: "control"` adapter per capability |
| `control-port-unique` | Control port must not conflict with any business adapter port |
| `control-address-localhost-warning` | Warn if `address` is not `localhost` or `127.0.0.1` (security) |
| `control-logs-stream-not-production` | Warn if `endpoints.logs.stream: true` — log streaming should be disabled in production |
| `control-dev-endpoints-not-production` | Warn if `endpoints.reload: true` and adapter has a `public`-tagged sibling (from [Kubernetes Backstage Governance](kubernetes-backstage-governance.md) taxonomy) |

---

## Capability YAML Examples

### Minimal Control Port

```yaml
naftiko: "1.0.0-alpha2"
info:
  label: Weather Service
  description: Provides weather forecasts from the National Weather Service API.
capability:
  consumes:
    - type: http
      namespace: weather-api
      baseUri: https://api.weather.gov
      operations:
        - id: get-forecast
          method: GET
          path: /points/{lat},{lon}/forecast
  exposes:
    - type: mcp
      namespace: weather
      port: 3000
      tools:
        - name: get-forecast
          description: Get weather forecast for a location.
          inputParameters:
            - name: lat
              type: number
              description: Latitude
              required: true
            - name: lon
              type: number
              description: Longitude
              required: true
          call: weather-api.get-forecast
          with:
            lat: "{{lat}}"
            lon: "{{lon}}"
    - type: control
      port: 9090
```

This gives you `/health/live`, `/health/ready`, `/metrics`, and `/traces` on `:9090` with zero additional configuration. The CLI can immediately connect via `naftiko health`.

### Development Profile (All Endpoints)

```yaml
    - type: control
      port: 9090
      management:
        info: true
        reload: true
        validate: true
        logging: true
        logs:
          stream: true
```

This enables all development endpoints: `/status`, `/config`, `/config/reload`, `/config/validate`, `/logs`, `/logs/{logger}`, and `/logs/stream`. Use this during local development for full CLI integration.

### Production Profile (Health + Metrics Only)

```yaml
    - type: control
      port: 9090
      address: "0.0.0.0"
      observability:
        traces:
          local:
            enabled: false
```

Binds to all interfaces for Kubernetes probe access and Prometheus scraping. Disables the trace ring buffer to save memory. Development endpoints remain disabled by default.

### With Authentication

```yaml
    - type: control
      port: 9090
      authentication:
        type: basic
        username: admin
        password:
          externalRef: CONTROL_PORT_PASSWORD
      management:
        info: true
        reload: true
```

Requires basic authentication for all control port access. The password is sourced from an external reference (environment variable or secret store).

### With Governance Labels

```yaml
naftiko: "1.0.0-alpha2"
info:
  label: Weather Service
  description: Provides weather forecasts from the National Weather Service API.
  labels:
    team: platform
    data-classification: public
    cost-center: engineering
  tags:
    - weather
    - public-api
capability:
  ...
```

Labels and tags are declared in `info` (not on the control adapter) and are consumed by `naftiko catalog` to generate Backstage `catalog-info.yaml` with `metadata.labels` and `metadata.tags`.

### Schema Validation Rule

The framework validates that at most one control adapter is declared per capability, and that its port does not conflict with any business adapter port:

```yaml
# Invalid — two control adapters
exposes:
  - type: control
    port: 9090
  - type: control
    port: 9091   # ← rejected by control-port-singleton rule

# Invalid — port conflict
exposes:
  - type: rest
    port: 9090
  - type: control
    port: 9090   # ← rejected by control-port-unique rule
```

---

## Endpoint Catalog

### Development Endpoints

| Method | Path | Default | Description | CLI Command |
| --- | --- | --- | --- | --- |
| `GET` | `/config` | Disabled | Returns the resolved capability spec with secrets redacted. Useful for debugging ref resolution, bind substitution, and import merging. | `naftiko config` |
| `POST` | `/config/reload` | Disabled | Triggers a hot reload of the capability YAML. Returns a diff of applied changes or a rejection reason. | `naftiko config reload` |
| `POST` | `/config/validate` | Disabled | Dry-run validation of a modified YAML against the running engine's state. Returns schema errors, rule violations, bind resolution status, and reload safety. | `naftiko config validate --live` |
| `GET` | `/logs` | Disabled | Returns current log levels for all active loggers. | `naftiko logs` |
| `PUT` | `/logs/{logger}` | Disabled | Changes the log level for a specific logger. Ephemeral — cleared on restart. | `naftiko logs --level` |
| `GET` | `/logs/stream` | Disabled | SSE endpoint that streams structured log entries in real time. Supports `?logger=` and `?level=` query filters. | `naftiko logs --follow` |
| `GET` | `/status` | Disabled | Returns capability name, spec version, engine version, uptime, OTel status, and adapter summary. | `naftiko status` |

**Future development endpoints** (not in this proposal):

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/debug/binds` | List all `binds` references and their resolution status (values redacted) |
| `POST` | `/debug/evaluate` | Evaluate a Mustache template against sample input (template playground) |
| `GET` | `/debug/steps/{operation-id}` | Step execution trace for a specific operation (dry run) |
| `GET` | `/debug/threads` | Thread dump for deadlock diagnosis |

### Operations Endpoints

> **Backlog**: `/health/*` and `naftiko health` address [#15 — Control port: Status](https://github.com/naftiko/framework/issues/15). `/status`, `/config`, and `naftiko status` address [#16 — Control port: Information](https://github.com/naftiko/framework/issues/16).

| Method | Path | Default | Description | CLI Command |
| --- | --- | --- | --- | --- |
| `GET` | `/health/live` | Enabled | Liveness probe. Returns `200 {"status":"UP"}` if the process is alive. | `naftiko health` |
| `GET` | `/health/ready` | Enabled | Readiness probe. Returns `200` when all business adapters are started, `503` otherwise. | `naftiko health` |
| `GET` | `/lifecycle` | (Future) | List all adapters with their current state (`started`, `stopped`, `draining`) | `naftiko lifecycle` |
| `PUT` | `/lifecycle` | (Future) | Set the lifecycle state for all business adapters (e.g., `{"state": "drain"}` to drain all then stop the process) | `naftiko lifecycle --state drain` |
| `PUT` | `/lifecycle/{namespace}` | (Future) | Set the lifecycle state for a single business adapter by namespace (e.g., `{"state": "down"}` to stop, `{"state": "up"}` to restart) | `naftiko lifecycle rest --state down` |
| `GET` | `/metrics` | Enabled | Prometheus exposition format. Serves OTel-collected metrics (see [OpenTelemetry Observability](opentelemetry-observability.md) Phase 2). | `naftiko metrics` |
| `GET` | `/traces` | Enabled | Returns recent trace summaries from the in-engine ring buffer. Supports `?operation=` and `?status=` query filters. | `naftiko traces` |
| `GET` | `/traces/{traceId}` | Enabled | Returns the full span tree for a specific trace. | `naftiko traces <traceId>` |

**Future operations endpoints**:

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/health/dependencies` | Per-dependency health status (consumed API reachability) |
| `GET` | `/metrics/json` | Metrics in JSON format (for non-Prometheus consumers) |

---

## Runtime Design

### Java Class Hierarchy

```
ServerAdapter (existing abstract base)
  ├── RestServerAdapter
  ├── McpServerAdapter
  ├── SkillServerAdapter
  └── ControlServerAdapter (new)
        ├── ConfigReloadResource       → /config/reload
        ├── ConfigValidateResource     → /config/validate
        ├── HealthLiveResource         → /health/live
        ├── HealthReadyResource        → /health/ready
        ├── LifecycleResource          → /lifecycle, /lifecycle/{namespace} (future)
        ├── LoggingResource            → /logs, /logs/{logger}
        ├── LogStreamResource          → /logs/stream (SSE)
        ├── MetricsResource            → /metrics
        ├── ConfigResource             → /config
        ├── StatusResource             → /status
        ├── TracesResource             → /traces, /traces/{traceId}
        └── (GovernanceResource removed — static via naftiko catalog)
```

### `ControlServerAdapter`

```java
import org.restlet.routing.Router;
import org.restlet.resource.ServerResource;

class ControlServerAdapter extends ServerAdapter {

    @Override
    Router createRouter() {
        Router router = new Router(getContext());
        var management = spec.getManagement();
        var observability = spec.getObservability();

        // Management endpoints
        if (management.isHealth()) {
            router.attach("/health/live", HealthLiveResource.class);
            router.attach("/health/ready", HealthReadyResource.class);
        }
        if (management.isInfo()) {
            router.attach("/config", ConfigResource.class);
            router.attach("/status", StatusResource.class);
        }
        if (management.isReload()) {
            router.attach("/config/reload", ConfigReloadResource.class);
        }
        if (management.isValidate()) {
            router.attach("/config/validate", ConfigValidateResource.class);
        }
        if (management.isLogging()) {
            router.attach("/logs", LoggingResource.class);
            router.attach("/logs/{logger}", LoggingResource.class);
        }
        if (management.getLogs().isStream()) {
            router.attach("/logs/stream", LogStreamResource.class);
        }

        // Observability endpoints (metrics + traces live under observability)
        if (isMetricsEnabled(observability)) {
            router.attach("/metrics", MetricsResource.class);
        }
        if (isTracesEnabled(observability)) {
            router.attach("/traces", TracesResource.class);
            router.attach("/traces/{traceId}", TracesResource.class);
        }

        // Lifecycle endpoints (future — Phase 4)
        if (management.isLifecycle()) {
            router.attach("/lifecycle", LifecycleResource.class);
            router.attach("/lifecycle/{namespace}", LifecycleResource.class);
        }

        return router;
    }
}
```

### Status Endpoint Response

`GET /status` returns a JSON object with capability metadata, engine version, uptime, OTel status, and adapter summary:

```json
{
  "capability": {
    "label": "Weather Service",
    "specVersion": "1.0.0-alpha2"
  },
  "engine": {
    "version": "1.0.0-alpha2",
    "java": "21.0.3",
    "native": false
  },
  "uptime": "PT2H34M12S",
  "otel": {
    "status": "active",
    "exporter": "otlp",
    "endpoint": "http://localhost:4318"
  },
  "adapters": [
    { "type": "mcp", "namespace": "weather", "port": 3000, "state": "started" },
    { "type": "control", "port": 9090, "state": "started" }
  ]
}
```

### Health Endpoint Responses

**`GET /health/live`** — Liveness probe:

```json
{
  "status": "UP"
}
```

**`GET /health/ready`** — Readiness probe (all adapters started):

```json
{
  "status": "UP",
  "adapters": [
    { "type": "mcp", "namespace": "weather", "port": 3000, "state": "started" },
    { "type": "control", "port": 9090, "state": "started" }
  ]
}
```

**`GET /health/ready`** — Readiness probe (degraded):

```json
{
  "status": "DEGRADED",
  "adapters": [
    { "type": "mcp", "namespace": "weather", "port": 3000, "state": "started" },
    { "type": "rest", "namespace": "api", "port": 8080, "state": "failed", "reason": "Port 8080 already in use" }
  ]
}
```

### Config Reload Response

**Success:**

```json
{
  "status": "applied",
  "changes": [
    {
      "path": "capability.exposes[0].tools[0].steps[0].call",
      "from": "weather-api.get-forecast",
      "to": "weather-api.get-detailed-forecast"
    }
  ]
}
```

**Rejection (structural change):**

```json
{
  "status": "rejected",
  "reason": "Structural change requires restart: adapter 'rest' port changed from 8080 to 8081"
}
```

---

## Relationship with OpenTelemetry Observability Blueprint

The control port and [OpenTelemetry Observability](opentelemetry-observability.md) blueprints are designed as **complementary halves** of Naftiko's observability story. OTel provides the instrumentation (spans, metrics, log correlation); the control port provides the **serving surface** and **local inspection layer**.

### Phase Alignment

| OTel Phase | Control Port Role |
| --- | --- |
| **Phase 0 — Logging** (SLF4J via Restlet ext.slf4j) | Control port's `/logs` endpoint provides runtime log level control over the SLF4J/Logback pipeline. `/logs/stream` serves the structured log output over SSE. |
| **Phase 1 — Distributed Tracing** (OTel spans) | Control port's `/traces` endpoint provides a local inspection layer — the engine captures completed spans in a ring buffer for CLI-driven debugging (`naftiko traces`). Traces are *also* exported via OTLP to Datadog/Jaeger for production use. |
| **Phase 2 — Metrics** (OTel Meter → Prometheus) | **Absorbed** — Prometheus metrics are served by the control adapter on `/metrics`. No standalone OTel metrics server needed. The OTel SDK records via `Meter`; the control port's `MetricsResource` bridges `PrometheusMetricReader` to HTTP. |
| **Phase 3 — Spec-Driven Configuration** (`observability` YAML block) | **Complementary** — The `observability` block configures OTel SDK behavior (sampling, exporters). The control port configures *where* the engine-side endpoints are served. `/status` reports the resolved OTel configuration. |
| **Phase 4 — Dashboarding** (Grafana, Datadog templates) | Control port provides the scrape target for Prometheus. Dashboard JSON templates reference `<host>:<control-port>/metrics`. |

### Joint Phase 1 Implementation

The **Phase 1 deliverable** for both blueprints is a unified observability foundation:

| Component | Owner Blueprint | Description |
| --- | --- | --- |
| SLF4J migration + Logback config | OTel Phase 0 | Foundation for structured logging |
| `TelemetryBootstrap` + OTel SDK init | OTel Phase 1 | Trace/span lifecycle management |
| Server/step/client span instrumentation | OTel Phase 1 | `SERVER` → `INTERNAL` → `CLIENT` span tree |
| W3C context propagation | OTel Phase 1 | `traceparent` extract/inject |
| `ControlServerAdapter` + `/health/*` + `/status` | Control Port Phase 1 | Management surface foundation |
| `/metrics` (Prometheus scrape) | Control Port Phase 1 + OTel Phase 2 | Bridges `PrometheusMetricReader` to HTTP |
| `/traces` (ring buffer inspection) | Control Port Phase 1 | Local trace inspection without external collector |
| CLI commands (`status`, `trace`, `metrics`) | Control Port Phase 1 | Developer-facing diagnostic tools |

This means a single implementation effort delivers: Kubernetes health probes, Prometheus metrics scraping, distributed tracing with Datadog/Jaeger export, local trace inspection via CLI, and structured logging with trace correlation.

### When No Control Port Is Declared

If a capability does not declare `type: "control"`:
- Health, info, traces, and logging endpoints are **not available** (no management surface).
- Prometheus metrics fall back to OTel's default standalone exporter (if configured via `OTEL_EXPORTER_PROMETHEUS_PORT`).
- OTel tracing and log correlation still work — spans are exported via OTLP regardless.
- Config reload is not available (restart required).
- CLI live commands (`naftiko health`, `naftiko status`, `naftiko traces`, etc.) are not available — CLI falls back to static mode.

This preserves backward compatibility — existing capabilities work unchanged.

---

## Security Considerations

### Default-Secure Posture

1. **`address` defaults to `localhost`** — the control port is not network-accessible unless explicitly configured with `0.0.0.0` or a specific interface.
2. **Sensitive endpoints disabled by default** — `/status`, `/config`, `/config/reload`, `/config/validate`, `/logs`, and `/logs/stream` are opt-in. Authors must explicitly enable them.
3. **Secret redaction** — `/config` replaces all `binds`-referenced values with `"***"`. No raw credentials are ever returned.
4. **No runtime governance endpoint** — governance metadata is generated statically via `naftiko catalog`. Labels are set in YAML, not served by the control port.
5. **Log level changes are ephemeral** — `/logs` changes are cleared on restart. The endpoint never writes to configuration files.
6. **Trace data is local only** — `/traces` returns span summaries from the in-engine ring buffer. It does not expose raw request/response bodies or header values (following the OTel blueprint's PII filtering principles).
7. **Log streaming is isolated** — `/logs/stream` has a configurable subscriber limit to prevent resource exhaustion. Log events go through the same attribute allowlist/denylist as OTel spans.
8. **CORS is off by default** — management endpoints are intended for CLI and backend tooling, not browsers. The control adapter should not emit permissive CORS headers unless explicitly enabled, and when enabled should use an allowlist of origins/methods/headers.

### CLI Security Model

The CLI connects to the control port over plain HTTP on `localhost`. This is acceptable for local development because:

- The control port defaults to `localhost` binding — only the local machine can reach it.
- CLI commands are stateless — no persistent session, no stored credentials.
- For production/remote access, the control adapter supports optional `authentication` (same model as business adapters), and the CLI supports `--username`/`--password` or `--token` flags.

### Kubernetes Network Policy

In production Kubernetes environments, restrict control port access to the monitoring namespace:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: control-port-access
spec:
  podSelector:
    matchLabels:
      app: weather-service
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              name: monitoring
      ports:
        - port: 9090
```

---

## Testing Strategy

### Unit Tests

| Test Class | Package | Purpose |
| --- | --- | --- |
| `CatalogCommandTest` | `io.naftiko.cli` | Generates valid `catalog-info.yaml` from capability YAML with correct mappings |
| `ConfigReloadResourceTest` | `io.naftiko.engine.exposes.control` | Accepts safe changes, rejects structural changes |
| `ConfigValidateResourceTest` | `io.naftiko.engine.exposes.control` | Dry-run validation reports schema errors, bind status, reload safety |
| `ControlServerSpecTest` | `io.naftiko.spec.exposes` | Deserialization of `ExposesControl` from YAML |
| `HealthLiveResourceTest` | `io.naftiko.engine.exposes.control` | Returns 200 when capability is running |
| `HealthReadyResourceTest` | `io.naftiko.engine.exposes.control` | Returns 200 when all adapters started, 503 when degraded |
| `LoggingResourceTest` | `io.naftiko.engine.exposes.control` | GET returns current levels; PUT changes level; changes are ephemeral |
| `LogStreamResourceTest` | `io.naftiko.engine.exposes.control` | SSE stream delivers log events; respects subscriber limit |
| `ConfigResourceTest` | `io.naftiko.engine.exposes.control` | Returns resolved spec with secrets redacted |
| `StatusResourceTest` | `io.naftiko.engine.exposes.control` | Returns correct capability metadata, uptime, and OTel status |
| `TracesResourceTest` | `io.naftiko.engine.exposes.control` | Returns trace summaries from ring buffer; filters by operation and status |

### Integration Tests

| Test Class | Package | Purpose |
| --- | --- | --- |
| `ControlPortIntegrationTest` | `io.naftiko.engine.exposes.control` | Full lifecycle: start capability with control port, hit all endpoints, verify responses |
| `ControlPortLoggingIntegrationTest` | `io.naftiko.engine.exposes.control` | Change log level via `/logs`, verify log output changes |
| `ControlPortMetricsIntegrationTest` | `io.naftiko.engine.exposes.control` | Prometheus `/metrics` returns OTel-recorded metrics |
| `ControlPortReloadIntegrationTest` | `io.naftiko.engine.exposes.control` | Hot reload: modify YAML, call `/config/reload`, verify new spec is active |
| `ControlPortSingletonRuleTest` | `io.naftiko.engine.exposes.control` | Reject capabilities declaring more than one control adapter |
| `ControlPortTracesIntegrationTest` | `io.naftiko.engine.exposes.control` | Make a tool call, then query `/traces` and verify the span tree |
| `ControlPortValidateIntegrationTest` | `io.naftiko.engine.exposes.control` | Dry-run: send modified YAML to `/config/validate`, verify response |

### Test Fixtures

| Fixture | Location | Description |
| --- | --- | --- |
| `control-port-capability.yaml` | `src/test/resources/` | Capability with all control endpoints enabled |
| `control-port-dev-capability.yaml` | `src/test/resources/` | Capability with development profile (all dev endpoints enabled) |
| `control-port-minimal-capability.yaml` | `src/test/resources/` | Capability with default control port (no `endpoints` block) |
| `control-port-reload-after.yaml` | `src/test/resources/` | Modified spec for hot reload test (safe changes) |
| `control-port-reload-before.yaml` | `src/test/resources/` | Original spec for hot reload test |
| `control-port-reload-structural.yaml` | `src/test/resources/` | Modified spec with structural changes (should be rejected) |

---

## Implementation Roadmap

### Phase 1 — Observability Foundation (Health + Metrics + Traces + Info)

This phase is **co-implemented with [OpenTelemetry Observability](opentelemetry-observability.md) Phases 0–1** to deliver the full observability stack in a single effort.

> **Backlog**: Tasks 1.4–1.5 close [#15 — Control port: Status](https://github.com/naftiko/framework/issues/15). Tasks 1.4, 1.6 close [#16 — Control port: Information](https://github.com/naftiko/framework/issues/16).

| Task | Component | Description |
| --- | --- | --- |
| 1.1 | Schema | Add `ExposesControl`, `ControlManagementSpec`, `ControlLogsEndpointSpec` to `naftiko-schema.json` |
| 1.2 | Spec classes | Create `ControlServerSpec`, `ControlManagementSpec` and related classes in `io.naftiko.spec.exposes` |
| 1.3 | Discriminator | Add `control` to `ServerSpec` Jackson subtypes and schema `oneOf` |
| 1.4 | Adapter | Implement `ControlServerAdapter` extending `ServerAdapter` |
| 1.5 | Health | Implement `HealthLiveResource` and `HealthReadyResource` — closes [#15](https://github.com/naftiko/framework/issues/15) |
| 1.6 | Status | Implement `StatusResource` (`/status`) with capability metadata, uptime, and OTel status — closes [#16](https://github.com/naftiko/framework/issues/16) |
| 1.7 | Metrics | Implement `MetricsResource` bridging OTel `PrometheusMetricReader` to HTTP |
| 1.8 | Traces | Implement `TracesResource` with ring buffer + custom `SpanProcessor` |
| 1.9 | Rules | Add `control-port-singleton` and `control-port-unique` validation rules |
| 1.10 | CLI `health` | Implement `naftiko health` command connecting to `/health/*` |
| 1.11 | CLI `status` | Implement `naftiko status` command connecting to `/status` |
| 1.12 | CLI `traces` | Implement `naftiko traces` command connecting to `/traces` |
| 1.13 | CLI `metrics` | Implement `naftiko metrics` command connecting to `/metrics` |
| 1.14 | CLI discovery | Implement control port discovery (flag → env var → YAML parse → default) |
| 1.15 | Authentication | Wire optional `authentication` on `ExposesControl` — reuses the existing `Authentication` model and engine infrastructure from business adapters |
| 1.16 | Tests | Unit + integration tests for all Phase 1 endpoints and CLI commands |

### Phase 2 — Development Endpoints (Catalog + Spec + Logging)

| Task | Component | Description |
| --- | --- | --- |
| 2.1 | Catalog CLI | Implement `naftiko catalog` command that generates Backstage `catalog-info.yaml` from capability YAML |
| 2.2 | Labels | Wire `info.labels` into generated `catalog-info.yaml` `metadata.labels` |
| 2.3 | Spec introspection | Implement `ConfigResource` (`/config`) with secret redaction |
| 2.4 | Logging | Implement `LoggingResource` — GET returns levels, PUT changes levels via Logback `LoggerContext` |
| 2.5 | CLI `logs` | Implement `naftiko logs` command (query and set modes) |
| 2.6 | CLI `config` | Implement `naftiko config` command connecting to `/config` |
| 2.7 | Tests | Unit + integration tests for catalog CLI, spec, and logging endpoints |

### Phase 3 — Config Reload + Live Validation + Log Streaming

| Task | Component | Description |
| --- | --- | --- |
| 3.1 | Config reload | Implement `ConfigReloadResource` with validation, diffing, and atomic swap |
| 3.2 | Reload safety | Implement structural change detection and rejection logic |
| 3.3 | Live validation | Implement `ConfigValidateResource` — dry-run validation with bind resolution, import resolution, structural change detection |
| 3.4 | Log streaming | Implement `LogStreamResource` with SSE, custom Logback appender, fan-out queue, subscriber limit |
| 3.5 | CLI `config reload` | Implement `naftiko config reload` command |
| 3.6 | CLI `config validate --live` | Extend `naftiko config validate` with `--live` flag |
| 3.7 | CLI `logs --follow` | Implement SSE stream consumer in the CLI |
| 3.8 | Tests | Unit + integration tests for reload, validation, and log streaming |

### Phase 4 — Advanced Endpoints

| Task | Component | Description |
| --- | --- | --- |
| 4.1 | Dependency health | Implement `/health/dependencies` with per-consumed-API status |
| 4.2 | Lifecycle | Implement `GET /lifecycle`, `PUT /lifecycle`, and `PUT /lifecycle/{namespace}` for adapter state management |
| 4.3 | Debug endpoints | Implement `/debug/threads`, `/debug/binds` |
| 4.4 | Tests | Full test coverage for advanced endpoints |

### Implementation Order and Rationale

Phase 1 is prioritized because:

1. **Health probes are a deployment prerequisite** — Kubernetes cannot manage a capability without liveness/readiness endpoints.
2. **Metrics are an SRE prerequisite** — without Prometheus scraping, there is no visibility into production behavior.
3. **Traces are a debugging prerequisite** — developers need local trace inspection for the OTel integration to be useful during development.
4. **The `ControlServerAdapter` foundation** enables all subsequent phases — once the adapter exists, adding endpoints is incremental.

Phase 2 follows because governance (catalog) and introspection are the next-most-requested capabilities for platform integration. Phase 3 adds the config mutation endpoints that carry the most risk. Phase 4 adds advanced features (lifecycle, dependency health, debug) that depend on all earlier phases being stable.

---

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
| --- | --- | --- | --- |
| **Scope creep** — control port absorbs too many features | Medium | Medium | Fixed endpoint set defined in this blueprint; user-defined endpoints are a non-goal |
| **Config reload instability** — partial apply leaves engine in bad state | Medium | High | Atomic swap with full validation; reject any change that fails schema or rules; rollback on error |
| **Security exposure** — management endpoints leak sensitive data | Low | High | Secret redaction in `/config`; sensitive endpoints disabled by default; `localhost` binding; optional `authentication` (reuses business adapter model) |
| **CLI fragmentation** — too many CLI commands create confusion | Low | Medium | Consistent `naftiko <domain> [<action>]` naming convention; `--help` on every command; command discovery via `naftiko --help` |
| **OTel dependency coupling** — control port requires OTel SDK | Low | Low | `/metrics` and `/traces` gracefully degrade when OTel is absent; health and config endpoints work independently |
| **Performance impact** — trace ring buffer or log streaming consumes too much memory/CPU | Low | Medium | Configurable buffer sizes with sensible defaults; subscriber limits on SSE; ring buffer evicts oldest entries |
| **Backward compatibility** — existing capabilities break when control port is added | Very Low | High | Control port is opt-in (declare `type: "control"` in YAML); existing capabilities without it work unchanged |

---

## Acceptance Criteria

### Phase 1 — Observability Foundation

1. `ExposesControl` is accepted in capability YAML and deserialized correctly.
2. At most one `type: "control"` adapter is allowed per capability (validated by rule).
3. Control port binds to `localhost` by default; explicit `address` overrides it.
4. `GET /health/live` returns `200 {"status":"UP"}` when the capability is running.
5. `GET /health/ready` returns `200` when all business adapters are started, `503` when any is not.
6. `GET /status` returns capability name, spec version, engine version, uptime, OTel status, and adapter summary.
7. `GET /metrics` returns Prometheus exposition format with OTel-recorded metrics (RED metrics for adapters, steps, and HTTP clients).
8. `GET /traces` returns recent trace summaries from the ring buffer, sorted by most recent first.
9. `GET /traces/{traceId}` returns the full span tree for a specific trace.
10. Trace ring buffer size is configurable via `endpoints.traces.bufferSize`.
11. When OTel is not active, `/metrics` returns `503` with an explanatory message; `/traces` returns `[]`.
12. `naftiko health` connects to the control port and reports health probe status.
13. `naftiko status` connects to the control port and reports engine version, uptime, OTel status, and adapter details.
14. `naftiko traces` lists recent traces; `naftiko traces <id>` shows the span tree.
15. `naftiko metrics` fetches and displays Prometheus metrics.
16. CLI discovers the control port via flag → env var → YAML parse → default cascade.
17. CLI provides a helpful error message when the control port is unreachable.
18. Optional `authentication` on the control port restricts access to authorized callers (reuses the existing `Authentication` model).
19. All existing tests pass — zero regressions.

### Phase 2 — Development Endpoints

1. `naftiko catalog` generates a valid `catalog-info.yaml` with metadata.name, labels, tags, dependsOn, and annotations.
2. Labels declared in `info.labels` appear in the generated entity's `metadata.labels`.
3. `GET /config` returns the resolved capability spec with all `binds` values replaced by `"***"`.
4. `GET /logs` returns current log levels for all active loggers.
5. `PUT /logs/{logger}` changes the effective log level. Change is ephemeral — cleared on restart.
6. `naftiko logs` displays current log levels; `naftiko logs --level debug --logger X` changes a level.
7. `naftiko config` retrieves and displays the resolved spec.

### Phase 3 — Config Reload + Live Validation + Log Streaming

1. `POST /config/reload` re-reads, validates, diffs, and applies safe changes.
2. Structural changes (add/remove adapter, change port) are rejected with a descriptive reason.
3. After a successful reload, subsequent requests use the new spec.
4. `POST /config/validate` performs dry-run validation: schema + rules + bind resolution + import resolution + structural change detection.
5. `/config/validate` reports whether the change is reloadable or requires a restart.
6. Both endpoints are disabled by default and require explicit enablement.
7. `GET /logs/stream` delivers structured log entries via SSE.
8. Log streaming respects the `maxSubscribers` limit.
9. `naftiko config reload` triggers reload and displays the result.
10. `naftiko config validate --live` sends YAML to `/config/validate` and displays the result.
11. `naftiko logs --follow` streams log entries to the terminal via SSE.

### Phase 4 — Advanced Endpoints

1. `GET /health/dependencies` returns per-consumed-API reachability status.
2. `PUT /lifecycle {"state": "drain"}` drains all business adapters and shuts down after in-flight requests complete.
3. `PUT /lifecycle/{namespace} {"state": "down"}` stops a single adapter; `{"state": "up"}` restarts it. `/health/ready` reflects the change.
4. Debug endpoints return diagnostic information with secrets redacted.
