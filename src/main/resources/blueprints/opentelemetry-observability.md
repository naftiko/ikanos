# OpenTelemetry Observability
## Distributed Tracing, Metrics, and Structured Logging for the Naftiko Engine

**Status**: Proposal  
**Date**: April 17, 2026  
**Spec Version**: `1.0.0-alpha2`  
**Key Concept**: Add OpenTelemetry-based observability to the Naftiko engine — distributed tracing, Prometheus-compatible metrics, and structured logging — feeding Prometheus and Datadog as primary backends, with zero changes to existing logging call sites thanks to Restlet's SLF4J extension.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Goals and Non-Goals](#goals-and-non-goals)
3. [Terminology](#terminology)
4. [Design Analogy](#design-analogy)
5. [Architecture Overview](#architecture-overview)
6. [Phase 0 — Logging Facade Migration](#phase-0--logging-facade-migration)
7. [Phase 1 — Distributed Tracing](#phase-1--distributed-tracing)
8. [Phase 2 — Metrics](#phase-2--metrics)
9. [Phase 3 — Spec-Driven Observability Configuration](#phase-3--spec-driven-observability-configuration)
10. [Phase 4 — Dashboarding and Alerting](#phase-4--dashboarding-and-alerting)
11. [Dependency Changes](#dependency-changes)
12. [Native Image Considerations](#native-image-considerations)
13. [Testing Strategy](#testing-strategy)
14. [Implementation Roadmap](#implementation-roadmap)
15. [Risks and Mitigations](#risks-and-mitigations)
16. [Acceptance Criteria](#acceptance-criteria)

---

## Executive Summary

### What This Proposes

Add OpenTelemetry (OTel) instrumentation to the Naftiko engine, covering the three pillars of observability:

1. **Traces** — Distributed spans across the full request lifecycle: server adapter → step execution → HTTP client calls to consumed APIs.
2. **Metrics** — RED metrics (Rate, Errors, Duration) for server adapters, step execution, and HTTP client calls, exposed via the [Control Port](control-port.md)'s Prometheus scrape endpoint (`/metrics`) and OTLP push.
3. **Logs** — Structured logging through SLF4J, correlated with trace IDs for end-to-end debugging.

**Prometheus alone is not enough** for this initiative: it is excellent for scrape-based metrics, but it does not model the per-request lifecycle (end-to-end span context, parent/child causality, and cross-service correlation). **OpenTelemetry fills that gap** by instrumenting the full request lifecycle (tracing) and providing **context propagation** (W3C `traceparent`) across adapter → steps → consumed HTTP calls. From there, we can export **metrics to Prometheus** (scrape) and **traces + metrics to Datadog** (OTLP ingestion) using a single OTel SDK — no vendor-specific SDKs required.

### Why This Fits Naftiko

Naftiko capabilities are declarative YAML documents that wire consumed APIs to exposed adapters through orchestration steps. Today, when a step fails or a consumed API returns an unexpected response, the only signal is a JUL warning in the console. There is no way to:

- Trace a request from the exposed adapter through each orchestration step to the consumed HTTP call
- Measure latency per step, per adapter, or per consumed operation
- Correlate log entries with the request that produced them
- Feed operational dashboards in Prometheus or Datadog

This proposal fills that gap while preserving Naftiko's zero-code philosophy: observability is automatic for every capability, with optional YAML-level tuning.

### Value

| Benefit | Impact |
|---|---|
| **End-to-end tracing** | Debug request flow from adapter → steps → consumed API in Datadog APM or Jaeger |
| **Trace tree continuity (W3C context)** | For HTTP transports, extracting/injecting `traceparent` lets Naftiko **continue the caller's trace tree** and correlate with downstream services (not possible with Prometheus alone) |
| **RED metrics** | Rate, error count, and duration histograms for SRE dashboards and alerting |
| **Log-trace correlation** | Every log entry carries a trace ID — jump from log to trace in one click |
| **Zero-code instrumentation** | Existing capabilities gain observability without YAML changes |
| **Dual backend** | Single OTel SDK feeds both Prometheus and Datadog |
| **Context propagation** | W3C `traceparent` injected into outgoing HTTP calls — correlate with downstream services |
| **Library-safe embedding** | OTel SDK is optional — only `opentelemetry-api` (~200 KB) is required; when SDK JARs are absent, all spans become zero-cost no-ops. Embedders (e.g. Langchain4j) can pass their own `OpenTelemetry` instance or exclude telemetry entirely |
| **Clear stdio limitation** | For MCP stdio, there's no header/metadata carrier for parent extraction, so each tool call starts a **new root span** (new trace) — documented and explicit |

### Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| OTel SDK jar size (~3 MB) | Medium | Low | SDK deps are `<optional>true</optional>` — embedders only pull `opentelemetry-api` (~200 KB); standalone deployment includes all JARs via shade plugin |
| Restlet request model lacks OTel Context carrier | Medium | Medium | Wrap with `Context.current().with(span)` at handler entry; pass via `Request.getAttributes()` |
| Stdio transport has no HTTP headers for propagation | High | Low | Start new root span; document limitation |
| Prometheus port conflicts with adapter ports | Low | Low | Metrics served via [Control Port](control-port.md) — dedicated management port, isolated from business traffic |
| Performance overhead on hot paths | Low | Medium | Sampling via `traces.sampling` in spec or `OTEL_TRACES_SAMPLER_ARG` env var |

---

## Goals and Non-Goals

### Goals

1. Route all Restlet, Jetty, and application logs through SLF4J using Restlet's `org.restlet.ext.slf4j` extension — zero changes to existing `Context.getCurrentLogger()` call sites.
2. Instrument the request lifecycle with OTel spans: server adapter → orchestration steps → HTTP client calls.
3. For HTTP transports (REST + MCP over HTTP), **propagate W3C trace context end-to-end**:
    - Extract inbound `traceparent`/`tracestate` from request headers (so Naftiko continues the caller's trace tree)
    - Inject outbound `traceparent`/`tracestate` into HTTP calls to consumed APIs (so downstream services can join the same trace)
4. For MCP stdio transport, start a **new root span** per tool call (no header/metadata carrier available for parent extraction) and document the limitation.
5. Expose RED metrics via the [Control Port](control-port.md)'s Prometheus scrape endpoint (`/metrics`) and OTLP push to Datadog. The Control Port blueprint defines the hosting surface; this blueprint defines what metrics are recorded and how.
6. Support zero-config operation via OTel environment variables (`OTEL_EXPORTER_*`) for containerized deployments.
7. Optionally allow capability authors to tune observability settings in YAML.
8. Support library embedding (e.g. Langchain4j): OTel SDK dependencies are optional — when absent, `TelemetryBootstrap` falls back to `OpenTelemetry.noop()` with zero overhead. Embedders can also pass their own `OpenTelemetry` instance via `TelemetryBootstrap.init(OpenTelemetry)`.

### Non-Goals (This Proposal)

1. Custom distributed tracing UI — use Datadog APM, Jaeger, or Grafana Tempo.
2. Application Performance Management (APM) agents — this is SDK-based, not agent-based.
3. Per-tool or per-operation access control based on telemetry data.
4. Real-time log streaming or log aggregation — use external tools (Loki, Datadog Logs).
5. Profiling or heap dump integration.

---

## Terminology

| Term | Definition |
|---|---|
| **Span** | A unit of work in a trace — represents a single operation (e.g., handling a request, executing a step, calling a consumed API) |
| **Trace** | A tree of spans representing the full lifecycle of a request |
| **Context propagation** | The mechanism that links spans across process boundaries via HTTP headers (`traceparent`, `tracestate`) |
| **RED metrics** | Rate (request count), Errors (error count), Duration (latency histogram) — the standard SRE metric set |
| **OTLP** | OpenTelemetry Protocol — the standard wire protocol for exporting traces, metrics, and logs |
| **Prometheus scrape** | Pull-based metrics collection where Prometheus fetches `/metrics` from the application |
| **SLF4J** | Simple Logging Facade for Java — a logging abstraction that decouples application code from the logging backend |
| **Slf4jLoggerFacade** | Restlet extension that replaces the default JUL-based logger factory with SLF4J — all `Context.getCurrentLogger()` calls are automatically routed through SLF4J |

---

## Design Analogy

### How observability maps to the Naftiko engine

```
Capability YAML              Naftiko Engine                 Observability Signal
───────────────              ──────────────                 ────────────────────
exposes:                     Server Adapter Handler         → SERVER span
  tools / operations           ↓                              + request metrics
                             OperationStepExecutor
  steps:                       ↓
    - type: call             HttpClientAdapter.handle()     → CLIENT span
                                                              + HTTP client metrics
    - type: lookup           LookupExecutor                 → INTERNAL span
    - type: script           ScriptStepExecutor             → INTERNAL span

  outputParameters           Converter / Resolver           → INTERNAL span
                                                              + converter metrics
```

### Trace hierarchy (example MCP tool call with 2 steps)

```
mcp.request [SERVER]                            ← JettyStreamableHandler / ToolHandler
  └── step.call [INTERNAL]                      ← OperationStepExecutor (step 0)
  │     └── http.client.GET [CLIENT]            ← HttpClientAdapter
  └── step.lookup [INTERNAL]                    ← OperationStepExecutor (step 1)
```

---

## Architecture Overview

### Current Logging State

| Area | Status |
|---|---|
| Logging | `java.util.logging` via Restlet's `Context.getCurrentLogger()` (~30 call sites across 10 classes) |
| One outlier | `ToolHandler` uses raw `Logger.getLogger()` (JUL, not Restlet context) |
| Jetty | Jetty 12 uses SLF4J natively (already ready) |
| Metrics | None |
| Tracing | None |

### Target Architecture

```
Naftiko Engine
  ├── Restlet ext.slf4j (Slf4jLoggerFacade)
  │     └── All Context.getCurrentLogger() → SLF4J
  │
  ├── OTel SDK (traces + metrics + logs bridge)
  │     ├── OTLP Exporter ──→ Datadog Agent (OTLP endpoint)
  │     └── Prometheus MetricReader ──→ Control Port /metrics
  │
  ├── Control Port (type: "control" adapter)
  │     └── GET /metrics ──→ Prometheus scrape endpoint
  │
  ├── SLF4J + Logback (structured JSON logs)
  │     └── OTel Logback Appender ──→ trace-correlated logs
  │
  └── jul-to-slf4j bridge (catches any stray JUL usage)
```

Datadog natively supports [OTLP ingestion](https://docs.datadoghq.com/opentelemetry/otlp_ingest_in_the_agent/) — no vendor-specific SDK needed. Prometheus scrapes the `/metrics` endpoint on the [Control Port](control-port.md) — a dedicated management adapter isolated from business traffic. A single OTel SDK covers both backends.

---

## Phase 0 — Logging Facade Migration

**Goal**: Route all logging through SLF4J using Restlet's `org.restlet.ext.slf4j` extension — zero changes to existing `Context.getCurrentLogger()` call sites.

### Why Restlet ext.slf4j

Restlet provides a `LoggerFacade` extension point. The `org.restlet.ext.slf4j` extension (`Slf4jLoggerFacade`) replaces the default JUL-based logger factory at the engine level. All existing `Context.getCurrentLogger()` calls — ~30 across 10 classes — are automatically routed through SLF4J without touching any application code.

This is strictly better than the alternative `SLF4JBridgeHandler.install()` approach, which creates a JUL → SLF4J bridge at the handler level and incurs a performance penalty from systematic `LogRecord` creation.

### Step 1 — Add Dependencies

```xml
<!-- Restlet SLF4J bridge — routes all Context.getCurrentLogger() to SLF4J -->
<dependency>
    <groupId>org.restlet</groupId>
    <artifactId>org.restlet.ext.slf4j</artifactId>
    <version>${restlet.version}</version>
</dependency>

<!-- SLF4J backend — structured logging, JSON output, MDC support -->
<dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-classic</artifactId>
    <version>1.5.18</version>
</dependency>

<!-- JUL bridge for stray JUL usage from third-party libraries -->
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>jul-to-slf4j</artifactId>
    <version>2.0.17</version>
</dependency>
```

No explicit `slf4j-api` dependency needed — `org.restlet.ext.slf4j` and `logback-classic` both pull it transitively.

### Step 2 — Activate the Facade

Set the system property in `Capability.java` startup, before any Restlet context is created:

```java
System.setProperty(
    "org.restlet.engine.loggerFacadeClass",
    "org.restlet.ext.slf4j.Slf4jLoggerFacade");
```

Or via JVM argument: `-Dorg.restlet.engine.loggerFacadeClass=org.restlet.ext.slf4j.Slf4jLoggerFacade`

Install the JUL bridge for any remaining corner cases:

```java
import org.slf4j.bridge.SLF4JBridgeHandler;

SLF4JBridgeHandler.removeHandlersForRootLogger();
SLF4JBridgeHandler.install();
```

### Step 3 — Fix the One Outlier

Only `ToolHandler` uses raw `Logger.getLogger()` (JUL, not Restlet's context logger). Migrate this single call:

```java
// Before
private static final Logger logger = Logger.getLogger(ToolHandler.class.getName());

// After
private static final org.slf4j.Logger logger = LoggerFactory.getLogger(ToolHandler.class);
```

### What Does NOT Change

| Pattern | Count | Action |
|---|---|---|
| `Context.getCurrentLogger().log(Level.INFO, ...)` | ~12 | **None** — `Slf4jLoggerFacade` routes it |
| `Context.getCurrentLogger().warning(...)` | ~10 | **None** — same bridge |
| `Context.getCurrentLogger().log(Level.SEVERE, ...)` | ~10 | **None** — same bridge |
| Jetty internal logs | all | **None** — Jetty 12 already uses SLF4J natively |

### Step 4 — Logback Configuration

Add `src/main/resources/logback.xml` with structured JSON output:

```xml
<configuration>
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
      <pattern>%d{ISO8601} [%thread] %-5level %logger{36} - traceId=%X{trace_id} spanId=%X{span_id} - %msg%n</pattern>
    </encoder>
  </appender>
  <root level="INFO">
    <appender-ref ref="CONSOLE" />
  </root>
</configuration>
```

The `trace_id` and `span_id` MDC fields are populated automatically by the OTel Logback appender in Phase 1.

### Result

- **0 code changes** to existing `Context.getCurrentLogger()` calls
- **1 line change** in `ToolHandler` (JUL → SLF4J)
- All Restlet, Jetty, and application logs flow through SLF4J
- Logback provides structured output, MDC for trace correlation, and level filtering
- When Phase 1 lands, adding the OTel Logback appender enables automatic log-trace correlation

---

## Phase 1 — Distributed Tracing

**Goal**: Instrument the request lifecycle end-to-end with OTel spans. This is the highest-value phase — it immediately enables Datadog APM and trace-based debugging.

### SDK Bootstrap

Create `io.naftiko.engine.telemetry.TelemetryBootstrap`:

- Initialized once in `Capability.java` during startup (after spec parsing, before adapter init)
- Uses OTel autoconfigure (`OTEL_EXPORTER_*` env vars) for zero-config in Docker/K8s
- **Classpath-guarded**: checks for `AutoConfiguredOpenTelemetrySdk` via `Class.forName()` — if the SDK JARs are absent (library embedding), falls back to `OpenTelemetry.noop()` automatically
- Service name: `naftiko-<capability.info.label>` (derived from YAML spec)
- Singleton `Tracer` via `get()` — returns no-op instance when `init()` was never called
- Three initialization paths:
  1. `init(String serviceName)` — standalone mode, autoconfigure with classpath guard
  2. `init(OpenTelemetry)` — embedder passes their own instance (or used in tests with `InMemorySpanExporter`)
  3. Never called — `get()` returns no-op, all span calls are zero-cost

```java
public class TelemetryBootstrap {

    private static volatile TelemetryBootstrap instance;
    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;

    TelemetryBootstrap(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer("io.naftiko.engine");
    }

    public static TelemetryBootstrap init(String serviceName) {
        try {
            Class.forName("io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk");
            instance = new TelemetryBootstrap(buildAutoConfigured(serviceName));
        } catch (ClassNotFoundException e) {
            logger.info("OpenTelemetry SDK not on classpath — telemetry disabled");
            instance = new TelemetryBootstrap(OpenTelemetry.noop());
        }
        return instance;
    }

    public static TelemetryBootstrap init(OpenTelemetry openTelemetry) {
        instance = new TelemetryBootstrap(openTelemetry);
        return instance;
    }

    public static TelemetryBootstrap get() {
        TelemetryBootstrap current = instance;
        return current != null ? current : new TelemetryBootstrap(OpenTelemetry.noop());
    }
}
```

### Instrumentation Points (Spans)

| Span Name | Class to Instrument | Span Kind | Key Attributes |
|---|---|---|---|
| `{adapter}.request` | `ResourceRestlet.handle()`, `ToolHandler.handleToolCall()`, `SkillServerResource.handle()` | `SERVER` | `naftiko.adapter.type`, `naftiko.capability`, `naftiko.operation.id`, `http.method`, `http.route` |
| `step.call` | `OperationStepExecutor.executeStep()` | `INTERNAL` | `naftiko.step.index`, `naftiko.step.call`, `naftiko.namespace` |
| `step.lookup` | `OperationStepExecutor` (lookup branch) | `INTERNAL` | `naftiko.step.index`, `naftiko.step.match` |
| `step.script` | `OperationStepExecutor` (script branch) | `INTERNAL` | `naftiko.step.index`, `naftiko.step.script.engine` |
| `http.client.{method}` | `HttpClientAdapter.handle()` | `CLIENT` | `http.method`, `http.url`, `http.status_code`, `naftiko.namespace`, `naftiko.operation.id` |
| `resolver.template` | `Resolver.resolve()` | `INTERNAL` | `naftiko.template.keys` (count only, no values — security) |
| `converter.{format}` | `Converter` format methods | `INTERNAL` | `naftiko.format` (`json`, `xml`, `avro`, etc.) |

### Span Hierarchy Examples

**MCP tool with 2 steps:**

```
mcp.request [SERVER]
  └── step.call [INTERNAL]            ← step 0
  │     └── http.client.GET [CLIENT]
  └── step.lookup [INTERNAL]          ← step 1
```

**REST operation with aggregate ref:**

```
rest.request [SERVER]
  └── step.call [INTERNAL]            ← step 0 (from aggregate function)
  │     └── http.client.POST [CLIENT]
  └── step.script [INTERNAL]          ← step 1 (inline transform)
```

**REST operation in simple call mode:**

```
rest.request [SERVER]
  └── http.client.GET [CLIENT]
```

### Context Propagation

**Why it matters:** Context propagation is what turns a set of spans into a *single trace tree* across process boundaries. Without propagation, every inbound request would look like an unrelated root trace, making end-to-end debugging and cross-service correlation impossible.

| Direction | Mechanism | Transport |
|---|---|---|
| **Inbound** (server adapters) | Extract W3C `traceparent`/`tracestate` from HTTP headers — preserves the upstream parent span so Naftiko can attach its spans into the caller's trace tree | REST (via Restlet `Request`), MCP HTTP (via Jetty `HttpServletRequest`) |
| **Outbound** (HTTP client) | Inject `traceparent` into outgoing requests — links Naftiko's CLIENT spans to downstream service traces | `HttpClientAdapter` headers — links Naftiko trace to downstream API traces |
| **MCP stdio** | No standardized request metadata / headers to extract. We cannot reliably recover an upstream parent span context, so we cannot "continue" an existing trace tree. | Start a new root span per tool call (new trace) and document the limitation. If/when MCP defines (or we adopt) a trace-context carrier in message metadata, we can revisit and support parent extraction. |

Inbound extraction at REST adapter:

```java
// In ResourceRestlet.handle()
Context extractedContext = openTelemetry.getPropagators()
    .getTextMapPropagator()
    .extract(Context.current(), request, restletHeaderGetter);
```

Outbound injection at HTTP client:

```java
// In HttpClientAdapter, before handle()
openTelemetry.getPropagators()
    .getTextMapPropagator()
    .inject(Context.current(), clientRequest, restletHeaderSetter);
```

### Quick win: return trace identifiers in HTTP responses

For all HTTP-based server adapters (REST and MCP over HTTP), we should add the active trace identifiers to **response headers**. This is a low-effort, high-value feature: it lets callers immediately copy/paste a trace ID into Datadog/Jaeger/Grafana Tempo to find the corresponding trace, even if they do not have full client-side tracing.

**Proposed headers:**

- `traceparent`: the W3C trace-context header for the *current* span context (preferred — standard)
- `x-naftiko-trace-id`: a convenience header containing just the trace ID (optional)

**Notes:**

- For HTTP requests that already provided inbound `traceparent`, returning it (or the updated value for the active span) makes correlation trivial.
- For MCP stdio, there is no response header concept, so this does not apply.

**Example (conceptual):**

```java
Span span = Span.current();
String traceId = span.getSpanContext().getTraceId();
// 1) Convenience header
response.getHeaders().add("x-naftiko-trace-id", traceId);
// 2) Standard header (inject into the response headers)
openTelemetry.getPropagators()
    .getTextMapPropagator()
    .inject(Context.current(), response, responseHeaderSetter);
```

### Async and multithreaded execution (context handoff)

The OTel **current context is thread-local** (`Context.current()`). If request handling, step execution, or HTTP calls hop threads (e.g., `ExecutorService`, `CompletableFuture`, Jetty async), spans will **detach** and traces will fragment into multiple roots unless we explicitly carry the context across thread boundaries.

**Rules:**

- Capture the parent `Context` at request entry (after header extraction + SERVER span start).
- When scheduling async work, **wrap** the runnable/callable with the captured context (or manually reattach with `makeCurrent()`), so child spans remain parented correctly.
- Prefer storing the captured request context in a transport-scoped container:
    - REST: Restlet `Request.getAttributes()`
    - MCP HTTP: servlet request attributes
    - MCP stdio: in the tool-call execution object / handler state (since there are no headers)

**Example (ExecutorService):**

```java
Context ctx = Context.current();
executor.submit(ctx.wrap(() -> {
    Span span = tracer.spanBuilder("step.call").startSpan();
    try (Scope scope = span.makeCurrent()) {
        // ... work
    } finally {
        span.end();
    }
}));
```

**Failure mode if ignored:** traces will exist, but the span tree breaks as soon as work continues on a different thread; downstream `traceparent` injection may no longer match the intended parent.

### Error Recording

| Scenario | Span Action |
|---|---|
| Step execution throws exception | `span.recordException(e)` + `span.setStatus(StatusCode.ERROR)` |
| HTTP 5xx from consumed API | `span.setStatus(StatusCode.ERROR)` + `http.status_code` attribute |
| HTTP 4xx from consumed API | `http.status_code` attribute only (not an engine error) |
| Script timeout / statement limit exceeded | `span.recordException(e)` + `span.setStatus(StatusCode.ERROR)` |

Existing `catch` blocks in `ResourceRestlet`, `ToolHandler`, and `ProtocolDispatcher` are natural injection points — no new try/catch needed.

---

## Phase 2 — Metrics

**Goal**: Record RED metrics (Rate, Errors, Duration) plus business metrics via the OTel SDK, served through the [Control Port](control-port.md)'s `/metrics` endpoint and pushed via OTLP to Datadog.

**Dependency**: This phase requires the [Control Port](control-port.md) blueprint's Phase 2 (Metrics and Governance), which provides the `MetricsRestlet` on the `type: "control"` adapter. The OTel SDK records metrics internally; the Control Port serves them to Prometheus.

### How Metrics Reach Prometheus (via Control Port)

The OTel SDK records metrics using its internal `Meter` API. The **Prometheus metric reader** (`opentelemetry-exporter-prometheus`) collects those metrics and translates them into Prometheus text exposition format. Instead of spinning up a standalone HTTP server (as the OTel library does by default), the Control Port's `MetricsRestlet` calls the reader directly and writes the exposition-format output to the response:

```
OTel Meter API                Control Port                       Prometheus
────────────                  ────────────                       ──────────
counter.add(1, labels)  ──►  GET /metrics                  ──►  scrape interval
histogram.record(0.3s)  ──►    └── PrometheusMetricReader  ──►  pull :9090/metrics
                                     └── exposition format
```

This consolidation means:
- **One management port** — no separate `:9464` for Prometheus. Metrics live alongside health, info, and governance on the Control Port.
- **Prometheus scrape config** points to `<host>:<control-port>/metrics`.
- The OTel SDK still records metrics via `Meter`; the Control Port simply serves them.
- When no Control Port is declared, metrics fall back to OTel's default standalone exporter (if configured via `OTEL_EXPORTER_PROMETHEUS_PORT` env var).

### Metric Definitions

| Metric | Type | Labels | Source |
|---|---|---|---|
| `naftiko.request.duration` | Histogram | `adapter`, `operation`, `status` | Server adapter handlers |
| `naftiko.request.total` | Counter | `adapter`, `operation`, `status` | Server adapter handlers |
| `naftiko.request.errors` | Counter | `adapter`, `operation`, `error.type` | Error handlers |
| `naftiko.step.duration` | Histogram | `step.type` (`call`/`lookup`/`script`), `namespace` | `OperationStepExecutor` |
| `naftiko.http.client.duration` | Histogram | `method`, `host`, `status_code` | `HttpClientAdapter` |
| `naftiko.http.client.total` | Counter | `method`, `host`, `status_code` | `HttpClientAdapter` |
| `naftiko.capability.active` | UpDownCounter | `capability.name` | `Capability` start/stop |
| `naftiko.converter.duration` | Histogram | `format` | `Converter` |

All HTTP-related metrics follow [OTel semantic conventions for HTTP](https://opentelemetry.io/docs/specs/semconv/http/).

### Metric Registration

```java
// In TelemetryBootstrap or a dedicated MetricsRegistry
LongCounter requestCounter = meter.counterBuilder("naftiko.request.total")
    .setDescription("Total number of requests handled by the engine")
    .build();

DoubleHistogram requestDuration = meter.histogramBuilder("naftiko.request.duration")
    .setDescription("Duration of request handling in seconds")
    .setUnit("s")
    .build();
```

---

## Phase 3 — Spec-Driven Observability Configuration

**Goal**: Allow capability authors to declare observability preferences in YAML, consistent with Naftiko's spec-driven philosophy.

### Schema Extension

Add an optional `observability` block on the control adapter. Metrics and traces
local endpoints are configured here (moved from the former `endpoints` block):

```yaml
capability:
  exposes:
    - type: control
      port: 9090            # Prometheus /metrics served here
      observability:
        enabled: true
        metrics:
          local:
            enabled: true     # /metrics Prometheus scrape (default: true)
        traces:
          sampling: 0.1        # 10% sampling rate (default: 1.0 = all)
          propagation: w3c      # w3c | b3 (default: w3c)
          local:
            enabled: true      # /traces endpoint (default: true)
            buffer-size: 200   # ring buffer capacity (default: 100)
        exporters:
          otlp:
            endpoint: "{{OTEL_EXPORTER_OTLP_ENDPOINT}}"
```

> **Note**: The `endpoints.metrics` and `endpoints.traces` fields from earlier schema versions have been **merged into `observability`** as `observability.metrics.local` and `observability.traces.local`. This eliminates the two-knob problem where enabling observability still required separate endpoint toggles. Management endpoints (health, info, reload, etc.) remain under `management`.

### Design Principles

- All fields are optional — defaults to OTel env vars (`OTEL_*`) when not specified
- `binds` integration: exporter endpoints can reference bound secrets (e.g., `{{DD_API_KEY}}`)
- When `observability` is absent, telemetry is still active if OTel env vars are set (zero-config for containerized deployments)
- When `observability.enabled` is `false`, telemetry is disabled entirely (no SDK init, no overhead)
- Prometheus metrics port is **not** configured here — it is determined by the [Control Port](control-port.md) adapter's `port` field. If no Control Port is declared, metrics fall back to OTel's standalone exporter via `OTEL_EXPORTER_PROMETHEUS_PORT`.

### Schema Definition

```json
"ObservabilitySpec": {
  "type": "object",
  "properties": {
    "enabled": {
      "type": "boolean",
      "default": true,
      "description": "Enable or disable observability. Defaults to true."
    },
    "metrics": {
      "$ref": "#/$defs/ObservabilityMetricsSpec"
    },
    "traces": {
      "$ref": "#/$defs/ObservabilityTracesSpec"
    },
    "exporters": {
      "$ref": "#/$defs/ObservabilityExportersSpec"
    }
  },
  "unevaluatedProperties": false
}

"ObservabilityMetricsSpec": {
  "type": "object",
  "properties": {
    "local": {
      "$ref": "#/$defs/ObservabilityLocalEndpointSpec"
    }
  },
  "unevaluatedProperties": false
}

"ObservabilityTracesSpec": {
  "type": "object",
  "properties": {
    "sampling": {
      "type": "number",
      "minimum": 0,
      "maximum": 1,
      "default": 1.0,
      "description": "Trace sampling rate. 1.0 = sample all, 0.1 = sample 10%."
    },
    "propagation": {
      "type": "string",
      "enum": ["w3c", "b3"],
      "default": "w3c",
      "description": "Context propagation format for outgoing HTTP calls."
    },
    "local": {
      "$ref": "#/$defs/ObservabilityTracesLocalSpec"
    }
  },
  "unevaluatedProperties": false
}

"ObservabilityLocalEndpointSpec": {
  "type": "object",
  "properties": {
    "enabled": {
      "type": "boolean",
      "default": true
    }
  },
  "unevaluatedProperties": false
}

"ObservabilityTracesLocalSpec": {
  "type": "object",
  "properties": {
    "enabled": {
      "type": "boolean",
      "default": true
    },
    "buffer-size": {
      "type": "integer",
      "minimum": 10,
      "maximum": 10000,
      "default": 100
    }
  },
  "unevaluatedProperties": false
}

"ObservabilityExportersSpec": {
  "type": "object",
  "properties": {
    "otlp": {
      "$ref": "#/$defs/ObservabilityOtlpExporterSpec"
    }
  },
  "unevaluatedProperties": false
}

"ObservabilityOtlpExporterSpec": {
  "type": "object",
  "properties": {
    "endpoint": {
      "type": "string",
      "description": "OTLP exporter endpoint. Supports Mustache expressions for binds."
    }
  },
  "required": ["endpoint"],
  "unevaluatedProperties": false
}
```

### Spec Classes

| Class | Package | Description |
|---|---|---|
| `ObservabilitySpec` | `io.naftiko.spec` | Root observability configuration |
| `ObservabilityMetricsSpec` | `io.naftiko.spec` | Metrics collection and local exposure |
| `ObservabilityTracesSpec` | `io.naftiko.spec` | Sampling, propagation, and local exposure |
| `ObservabilityLocalEndpointSpec` | `io.naftiko.spec` | Toggle for a local endpoint |
| `ObservabilityTracesLocalSpec` | `io.naftiko.spec` | Traces endpoint with buffer size |
| `ObservabilityExportersSpec` | `io.naftiko.spec` | Exporter configuration container |
| `ObservabilityOtlpExporterSpec` | `io.naftiko.spec` | OTLP endpoint configuration |

---

## Phase 4 — Dashboarding and Alerting

**Goal**: Provide companion artifacts for operational readiness. Not engine code — shipped alongside the framework.

| Artifact | Location | Purpose |
|---|---|---|
| Grafana dashboard JSON | `demo/observability/grafana-naftiko.json` | Pre-built Prometheus dashboard for Naftiko RED metrics |
| Datadog dashboard template | `demo/observability/datadog-naftiko.json` | Terraform-compatible Datadog dashboard |
| Docker Compose overlay | `demo/observability/docker-compose.yml` | Prometheus + Grafana + OTel Collector + Datadog Agent for local dev |
| Prometheus alerting rules | `demo/observability/alerts.yml` | High error rate, latency P99 thresholds |

### Docker Compose Dev Stack

> **Note**: Prometheus scrapes the Control Port's `/metrics` endpoint. The `prometheus.yml` target should point to `<capability-host>:<control-port>/metrics` (e.g., `host.docker.internal:9090/metrics`).

```yaml
services:
  prometheus:
    image: prom/prometheus
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
    ports:
      - "9090:9090"

  grafana:
    image: grafana/grafana
    ports:
      - "3001:3000"
    volumes:
      - ./grafana-naftiko.json:/var/lib/grafana/dashboards/naftiko.json

  otel-collector:
    image: otel/opentelemetry-collector-contrib
    ports:
      - "4317:4317"   # OTLP gRPC
      - "4318:4318"   # OTLP HTTP
    volumes:
      - ./otel-collector-config.yml:/etc/otelcol/config.yaml

  datadog-agent:
    image: datadog/agent
    environment:
      - DD_API_KEY=${DD_API_KEY}
      - DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_GRPC_ENDPOINT=0.0.0.0:4317
    ports:
      - "4317:4317"
```

---

## Dependency Changes

### Phase 0 — Logging

```xml
<dependency>
    <groupId>org.restlet</groupId>
    <artifactId>org.restlet.ext.slf4j</artifactId>
    <version>${restlet.version}</version>
</dependency>
<dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-classic</artifactId>
    <version>1.5.18</version>
</dependency>
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>jul-to-slf4j</artifactId>
    <version>2.0.17</version>
</dependency>
```

### Phase 1 — Tracing

```xml
<!-- Required — the only mandatory OTel dependency (~200 KB).
     Provides the API surface (Tracer, Span, Context) and OpenTelemetry.noop() fallback.
     Versions managed via ${opentelemetry.version} property (1.48.0). -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <version>${opentelemetry.version}</version>
</dependency>

<!-- Optional — SDK + autoconfigure + exporter + log appender.
     Pulled automatically in standalone deployment (shade plugin).
     Embedders can exclude these — TelemetryBootstrap falls back to OpenTelemetry.noop(). -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
    <version>${opentelemetry.version}</version>
    <optional>true</optional>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk-extension-autoconfigure</artifactId>
    <version>${opentelemetry.version}</version>
    <optional>true</optional>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
    <version>${opentelemetry.version}</version>
    <optional>true</optional>
</dependency>
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-logback-appender-1.0</artifactId>
    <version>2.14.0-alpha</version>
    <optional>true</optional>
</dependency>
```

**Embedding note:** When Naftiko is used as a library (e.g. inside Langchain4j), only `opentelemetry-api` is transitively required. The optional SDK JARs are not pulled. `TelemetryBootstrap.init(String)` detects the SDK absence via `Class.forName()` and falls back to `OpenTelemetry.noop()`. Embedders can also call `TelemetryBootstrap.init(OpenTelemetry)` to supply their own instance.

### Phase 2 — Prometheus Metric Reader (served by Control Port)

```xml
<!-- Prometheus metric reader — translates OTel metrics to Prometheus exposition format.
     The Control Port's MetricsRestlet calls this reader to serve /metrics.
     No standalone HTTP server is spun up — the Control Port hosts the endpoint. -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-prometheus</artifactId>
</dependency>
```

### JAR Size Impact

| Dependency | Approximate Size | Required? |
|---|---|---|
| `org.restlet.ext.slf4j` | ~50 KB | Yes |
| `logback-classic` + `logback-core` | ~1.2 MB | Yes |
| `jul-to-slf4j` | ~10 KB | Yes |
| `opentelemetry-api` | ~200 KB | **Yes** (only mandatory OTel dep) |
| OTel SDK + autoconfigure | ~2.8 MB | Optional |
| OTel OTLP exporter (HTTP/protobuf) | ~1.5 MB | Optional |
| OTel Prometheus exporter | ~500 KB | Optional |
| OTel Logback appender | ~100 KB | Optional |
| **Total (standalone)** | **~6.4 MB** | |
| **Total (embedded, no SDK)** | **~1.5 MB** | |

---

## Native Image Considerations

### SLF4J + Logback (Phase 0)

SLF4J + Logback work with GraalVM native image out of the box. Logback service providers are discovered via `META-INF/services/` which native-image handles automatically.

### OTel SDK (Phase 1+)

- OTel SDK supports GraalVM via `opentelemetry-sdk-extension-autoconfigure-spi` — declare service providers in `META-INF/native-image/`
- **Prefer HTTP/protobuf** OTLP variant over gRPC to avoid the heavier gRPC native-image footprint
- Test each phase in the `native` Maven profile before merging

### Reflect Config

Add `reflect-config.json` entries for OTel autoconfigure SPI classes:

```
src/main/resources/META-INF/native-image/io.naftiko/reflect-config.json
```

---

## Testing Strategy

### Unit Tests

| Test Class | Package | Purpose |
|---|---|---|
| `TelemetryBootstrapTest` | `io.naftiko.engine.telemetry` | SDK initialization, service name derivation, no-op when disabled |
| `SpanInstrumentationTest` | `io.naftiko.engine.telemetry` | Verify span creation, attributes, status codes using in-memory exporter |
| `ContextPropagationTest` | `io.naftiko.engine.telemetry` | W3C `traceparent` extraction/injection round-trip |
| `MetricsRegistrationTest` | `io.naftiko.engine.telemetry` | Verify counter/histogram registration and recording |

### Integration Tests

| Test Class | Package | Purpose |
|---|---|---|
| `ObservabilityMcpIntegrationTest` | `io.naftiko.engine.exposes.mcp` | MCP tool call produces trace with expected span hierarchy |
| `ObservabilityRestIntegrationTest` | `io.naftiko.engine.exposes.rest` | REST operation produces trace + metrics |
| `ObservabilityContextPropagationTest` | `io.naftiko.engine.consumes.http` | Outgoing HTTP calls carry `traceparent` header |

### Test Approach

All tests use OTel's `InMemorySpanExporter` and `InMemoryMetricReader` — no external collector needed. Tests assert:

1. Correct span names and hierarchy (parent-child relationships)
2. Required attributes are present on each span
3. Error status is set on failure spans
4. `traceparent` is injected into outgoing HTTP requests
5. Metrics are recorded with correct labels
6. No telemetry overhead when `observability.enabled: false`

### Test Fixtures

| Fixture | Location | Description |
|---|---|---|
| `observability-capability.yaml` | `src/test/resources/` | Capability with `observability` block configured |
| `observability-disabled-capability.yaml` | `src/test/resources/` | Capability with `observability.enabled: false` |

---

## Implementation Roadmap

### Phase 0 — Logging Facade (SLF4J via Restlet ext.slf4j)

| Task | Component | Description |
|---|---|---|
| 0.1 | Dependencies | Add `org.restlet.ext.slf4j`, `logback-classic`, `jul-to-slf4j` to `pom.xml` |
| 0.2 | Bootstrap | Set `loggerFacadeClass` system property + install `SLF4JBridgeHandler` in `Capability.java` |
| 0.3 | ToolHandler | Migrate single raw JUL `Logger.getLogger()` call to SLF4J `LoggerFactory.getLogger()` |
| 0.4 | Config | Add `logback.xml` with structured console output and trace ID MDC placeholders |
| 0.5 | Tests | Verify all log output routes through SLF4J; no JUL console output |

### Phase 1 — Distributed Tracing

| Task | Component | Description |
|---|---|---|
| 1.1 | Dependencies | Add `opentelemetry-api` (required) + SDK, autoconfigure, OTLP exporter, Logback appender as `<optional>true</optional>` |
| 1.2 | Bootstrap | Create `TelemetryBootstrap` — classpath-guarded SDK init via `Class.forName()`, no-op fallback, `init(OpenTelemetry)` for embedders |
| 1.3 | Server spans | Instrument `ResourceRestlet.handle()`, `ToolHandler.handleToolCall()`, `SkillServerResource.handle()` with `SERVER` spans |
| 1.4 | Step spans | Instrument `OperationStepExecutor.executeStep()` with `INTERNAL` spans per step type |
| 1.5 | Client spans | Instrument `HttpClientAdapter.handle()` with `CLIENT` spans |
| 1.6 | Propagation | Extract `traceparent` on inbound; inject on outbound HTTP calls |
| 1.7 | Error recording | Add `recordException()` + `setStatus(ERROR)` in existing catch blocks |
| 1.8 | Log correlation | Add OTel Logback appender — automatic `trace_id`/`span_id` in MDC |
| 1.9 | Tests | Unit + integration tests with in-memory exporter |

### Phase 2 — Metrics

| Task | Component | Description |
|---|---|---|
| 2.1 | Dependencies | Add `opentelemetry-exporter-prometheus` (metric reader only — no standalone server) |
| 2.2 | Metrics registry | Define counters and histograms in `TelemetryBootstrap` |
| 2.3 | Server metrics | Record `naftiko.request.total`, `naftiko.request.duration`, `naftiko.request.errors` in adapter handlers |
| 2.4 | Step metrics | Record `naftiko.step.duration` in `OperationStepExecutor` |
| 2.5 | Client metrics | Record `naftiko.http.client.total`, `naftiko.http.client.duration` in `HttpClientAdapter` |
| 2.6 | Control Port integration | Wire `PrometheusMetricReader` into the [Control Port](control-port.md)'s `MetricsRestlet` — no standalone Prometheus HTTP server |
| 2.7 | Fallback | When no Control Port is declared, fall back to OTel standalone exporter via `OTEL_EXPORTER_PROMETHEUS_PORT` env var |
| 2.8 | Tests | Verify metric registration and recording with in-memory reader; integration test via Control Port `/metrics` |

### Phase 3 — Spec-Driven Configuration

| Task | Component | Description |
|---|---|---|
| 3.1 | Schema | Add `ObservabilitySpec` and related definitions to `naftiko-schema.json` (no `metrics.port` — superseded by Control Port) |
| 3.2 | Spec classes | Create `ObservabilitySpec`, `ObservabilityTracesSpec`, `ObservabilityExportersSpec` |
| 3.3 | Bootstrap | Wire spec-level config into `TelemetryBootstrap` (sampling, propagation, endpoint) |
| 3.4 | Tests | Deserialization tests + integration tests with different configurations |

### Phase 4 — Dashboarding

| Task | Component | Description |
|---|---|---|
| 4.1 | Grafana | Create pre-built dashboard JSON for Naftiko RED metrics |
| 4.2 | Datadog | Create Terraform-compatible dashboard template |
| 4.3 | Docker Compose | Create `demo/observability/` dev stack with Prometheus + Grafana + OTel Collector |
| 4.4 | Alerts | Define Prometheus alerting rules for error rate and latency |

### Implementation Order and Rationale

| Order | Phase | Effort | Value | Rationale |
|---|---|---|---|---|
| 1st | **Phase 0** (Logging) | Low | Medium | Foundation for everything else; unblocks log-trace correlation; zero risk |
| 2nd | **Phase 1** (Tracing) | Medium | **Highest** | Distributed traces provide immediate debugging value; Datadog APM lights up; context propagation enables cross-service correlation |
| 3rd | **Phase 2** (Metrics) | Medium | High | RED metrics + Prometheus scrape via [Control Port](control-port.md) enable SRE dashboards and alerting |
| 4th | **Phase 3** (Spec config) | Low | Medium | Declarative config aligns with Naftiko philosophy but OTel env vars already cover most needs |
| 5th | **Phase 4** (Dashboards) | Low | Medium | Not engine code — can be contributed incrementally |

---

## Risks and Mitigations

### Privacy / GDPR (PII filtering)

Yes — this proposal should explicitly include a mechanism to **avoid exporting personal data** (or to redact it) in traces, logs, and (less commonly) metrics.

**Principles:**

- **No request/response bodies in telemetry by default.** Never add HTTP payloads as span attributes or logs automatically.
- **Minimize attributes:** prefer *structural* attributes (operation id, step index/type, status code, duration) over user-provided values.
- **Treat all inbound headers and query parameters as untrusted** (may include emails, tokens, names).

**Concrete mechanisms to add:**

1. **Attribute allowlist + denylist**
    - Default allowlist of safe attributes (e.g., `http.method`, `http.route`, `http.status_code`, `naftiko.operation.id`, `naftiko.step.index`).
    - Denylist for common sensitive keys (`authorization`, `cookie`, `set-cookie`, `x-api-key`, `token`, `password`, `email`, etc.).
    - Configurable via env vars / YAML, e.g. `observability.privacy.attributes.allow` and `observability.privacy.attributes.deny`.
2. **Redaction / hashing helpers**
    - When we must include an identifier for correlation, prefer **hashing** (stable, non-reversible) over raw values.
    - Example: `naftiko.user.id_hash = sha256(userId + salt)` rather than `user.id`.
3. **OTel Collector processors (recommended deployment option)**
    - Document using collector-side processors to enforce policy centrally before data reaches Datadog/Prometheus.
    - Typical choices: `attributes` processor (delete/insert), `transform` processor (rewrite), and routing rules.
4. **Log filtering**
    - Ensure our logback pattern/layout never automatically logs request headers/bodies.
    - If we add structured request logging later, it must go through the same allowlist/denylist + redaction layer.

**Non-goal:** fully automatic PII detection (regex/ML) — can be added later, but is risky for false negatives. Prefer deterministic policy (allowlist/denylist) plus collector enforcement.

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **OTel SDK jar size** (~6.4 MB total) | Medium | Low | SDK deps are `<optional>true</optional>` — only `opentelemetry-api` (~200 KB) is mandatory; `TelemetryBootstrap` classpath-guards the autoconfigure call and falls back to no-op. Standalone deployment shades all JARs |
| **Restlet's request model doesn't carry OTel `Context`** | Medium | Medium | Wrap with `Context.current().with(span)` at handler entry; pass via `Request.getAttributes()` or `ThreadLocal` |
| **Stdio transport has no HTTP headers for propagation** | High | Low | Start new root span; document limitation; explore MCP `_meta` extension |
| **Prometheus port conflicts with adapter ports** | Low | Low | **Eliminated** — Prometheus metrics are served by the [Control Port](control-port.md) on its dedicated management port; no separate metrics port to conflict |
| **Performance overhead on hot paths** | Low | Medium | Sampling via `traces.sampling` in spec or `OTEL_TRACES_SAMPLER_ARG` env var; benchmark before/after |
| **GraalVM native-image compatibility** | Medium | Medium | Prefer HTTP/protobuf OTLP over gRPC; add `reflect-config.json`; test with native profile |
| **Logback conflicts with existing JUL config** | Low | Low | `Slf4jLoggerFacade` takes priority; `SLF4JBridgeHandler` catches remaining JUL |

---

## Acceptance Criteria

### Phase 0

1. All Restlet `Context.getCurrentLogger()` calls route through SLF4J without code changes.
2. `ToolHandler` uses SLF4J `LoggerFactory` instead of raw JUL.
3. Jetty logs flow through SLF4J (already native).
4. `logback.xml` provides structured console output with trace ID placeholders.
5. All existing tests pass — zero regressions.

### Phase 1

1. Every server adapter request produces a `SERVER` span with correct attributes.
2. Every orchestration step produces an `INTERNAL` span as a child of the request span.
3. Every HTTP client call produces a `CLIENT` span as a child of the step span.
4. Outgoing HTTP requests carry `traceparent` header.
5. Inbound `traceparent` headers are extracted and used as parent context.
6. Failed operations set `StatusCode.ERROR` and record exceptions on spans.
7. Log entries carry `trace_id` and `span_id` via MDC.
8. All tests pass with `InMemorySpanExporter`.
9. When OTel SDK JARs are absent from the classpath, `TelemetryBootstrap.init(String)` falls back to `OpenTelemetry.noop()` — zero overhead, no `ClassNotFoundException`.
10. Embedders can supply their own `OpenTelemetry` instance via `TelemetryBootstrap.init(OpenTelemetry)` and all engine spans participate in the host application's traces.

### Phase 2

1. Prometheus `/metrics` endpoint on the [Control Port](control-port.md) serves all defined metrics in exposition format.
2. Metrics are recorded with correct labels on every request.
3. OTLP push exports metrics to configured endpoint.
4. `naftiko.capability.active` increments on start and decrements on stop.
5. When no Control Port is declared, metrics fall back to OTel standalone exporter via `OTEL_EXPORTER_PROMETHEUS_PORT`.

### Phase 3

1. `observability` YAML block is accepted and deserialized correctly.
2. `observability.enabled: false` disables all telemetry (no SDK init).
3. `observability.traces.sampling` controls the sampling rate.
4. `observability.exporters.otlp.endpoint` supports Mustache expressions for binds.
5. Absent `observability` block defaults to OTel env var configuration.
6. No `observability.metrics.port` field — Prometheus port is determined by the Control Port adapter.

### Phase 4

1. Grafana dashboard displays all Naftiko RED metrics correctly.
2. Docker Compose dev stack starts Prometheus + Grafana + OTel Collector.
3. Alerting rules fire on simulated high error rate.
