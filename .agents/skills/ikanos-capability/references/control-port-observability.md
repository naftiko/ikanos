---
name: control-port-observability-reference
description: >
  Reference for adding a control port and OpenTelemetry observability to an
  Ikanos capability. Use when the user wants health checks, Prometheus metrics,
  trace inspection, or distributed tracing with OTLP export.

---

## Why

Every production capability needs:

- **Health probes** for Kubernetes liveness/readiness.
- **Prometheus metrics** for RED dashboards (Rate, Errors, Duration).
- **Trace inspection** for debugging request flows without an external collector.
- **Distributed tracing** with W3C context propagation for end-to-end visibility.

Ikanos provides all of this declaratively via `type: "control"` (the management
adapter) and `capability.observability` (the OTel configuration).

## Control Port (type: "control")

The control port is a **management plane** isolated from business traffic.
It exposes engine-provided endpoints — you declare it but do not define them.
Management endpoints (health, info, reload, etc.) are configured under
`management`. OTel-dependent endpoints (metrics, traces) are configured
under `observability`.

### Minimal example

```yaml
capability:
  exposes:
    - type: control
      port: 9090
```

This gives you `/health/live` and `/health/ready` on port 9090 with all
defaults (health enabled, metrics enabled, traces enabled, info disabled).

### Full example with all options

```yaml
capability:
  exposes:
    - type: control
      address: localhost          # default; use 0.0.0.0 only in containers
      port: 9090
      management:
        health: true              # /health/live, /health/ready (default: true)
        info: false               # /status, /config (default: false)
        reload: false             # POST /config/reload (default: false)
        validate: false           # POST /config/validate (default: false)
        logs:                     # /logs endpoints (default: false)
          level-control: true     # /logs, /logs/{logger} (default: true when object)
          stream: false           # /logs/stream — SSE log streaming (default: false)
          max-subscribers: 5      # max concurrent SSE subscribers (default: 5)
      observability:
        traces:
          sampling: 1.0
          propagation: w3c
          local:
            buffer-size: 200      # ring buffer capacity (default: 100)
```

### Endpoint summary

| Endpoint | Configured in | Default | Requires OTel |
|---|---|---|---|
| `/health/live`, `/health/ready` | `management.health` | Enabled | No |
| `/metrics` | `observability.metrics.local` | Enabled | Yes |
| `/traces`, `/traces/{traceId}` | `observability.traces.local` | Enabled | Yes |
| `/status`, `/config` | `management.info` | Disabled | No |
| `POST /config/reload` | `management.reload` | Disabled | No |
| `POST /config/validate` | `management.validate` | Disabled | No |
| `/logs` | `management.logs` (or `management.logs.level-control`) | Disabled | No |
| `/logs/stream` | `management.logs.stream` | Disabled | No |
| `/scripting` | `management.scripting` | Disabled | No |

### Rules

- At most **one** control adapter per capability.
- The control port **must not** collide with any business adapter port.
- Bind `address` to `localhost` (default) for security. Use `0.0.0.0` only
  inside containers where Prometheus/k8s needs to reach the port externally.

## Scripting Governance

The control port can govern inline script step execution via `management.scripting`.
This allows operators to enable/disable scripting, set defaults, enforce timeouts,
limit statement counts, and restrict languages — all at runtime.

### Configuration

```yaml
capability:
  exposes:
    - type: control
      port: 9090
      management:
        scripting:
          enabled: true
          defaultLocation: "file:///app/capabilities/scripts"
          defaultLanguage: javascript
          timeout: 3000
          statementLimit: 50000
          allowedLanguages:
            - javascript
            - python
```

| Field | Type | Default | Description |
|---|---|---|---|
| `enabled` | `boolean` | `true` | Enable/disable all script steps |
| `defaultLocation` | `string (uri)` | — | Fallback `file:///` location for scripts |
| `defaultLanguage` | `enum` | — | Fallback language (`javascript`, `python`, `groovy`) |
| `timeout` | `integer` | `60000` | Max execution time in milliseconds |
| `statementLimit` | `integer` | `100000` | Max statements per execution (JavaScript and Python only; Groovy scripts are not subject to this limit) |
| `allowedLanguages` | `string[]` | all | Restrict permitted languages |

### REST API

- **GET `/scripting`** — returns current scripting config and execution stats
  (`totalExecutions`, `totalFailures`, `lastExecutionTime`, `lastFailureTime`).
- **PUT `/scripting`** — updates scripting config at runtime. Accepts a JSON body
  with any subset of the fields above.

### CLI

```bash
ikanos scripting                          # Display current config and stats
ikanos scripting --set timeout=60000      # Update a setting
ikanos scripting --set enabled=false      # Disable scripting at runtime
ikanos scripting --set allowedLanguages=javascript,python  # Restrict languages
```

Requires a running Control Port with `management.scripting` configured.

## Observability (OpenTelemetry)

The `observability` block on the control adapter configures the OTel SDK. When the SDK
is on the classpath and observability is enabled, the engine automatically:

- Records **RED metrics** (Rate, Errors, Duration) for every adapter request.
- Creates **distributed tracing spans** for server requests, client calls, and
  orchestration steps.
- Propagates **W3C trace context** on outbound HTTP calls.

### Minimal example

```yaml
capability:
  exposes:
    - type: control
      port: 9090
      observability: {}
```

All defaults apply: observability enabled, 100% trace sampling, W3C propagation,
`/metrics` and `/traces` endpoints active, no OTLP export (metrics available via
Prometheus scrape on the control port). Set `enabled: false` to disable
observability while keeping the rest of the config.

### Full example with OTLP export

```yaml
capability:
  exposes:
    - type: control
      port: 9090
      observability:
        traces:
          sampling: 1.0              # 1.0 = all traces, 0.1 = 10% (default: 1.0)
          propagation: w3c           # w3c or b3 (default: w3c)
        exporters:
          otlp:
            endpoint: "{{OTEL_ENDPOINT}}"   # Mustache for binds injection

binds:
  - namespace: otel-config
    keys:
      OTEL_ENDPOINT: "otel-collector-endpoint"
```

### Metrics emitted

| Metric | Type | Description |
|---|---|---|
| `ikanos.request.total` | Counter | Requests by adapter, operation, status |
| `ikanos.request.duration.seconds` | Histogram | Request duration |
| `ikanos.request.errors` | Counter | Errors by adapter, operation, error type |
| `ikanos.step.duration.seconds` | Histogram | Step duration by type and namespace |
| `ikanos.http.client.total` | Counter | Outbound HTTP calls by method, host, status |
| `ikanos.http.client.duration.seconds` | Histogram | Outbound HTTP call duration |
| `ikanos.capability.active` | UpDownCounter | Active capability count |

### Combining control port + observability

For a production-ready setup, use both together:

```yaml
capability:
  exposes:
    - type: control
      port: 9090
      management:
        health: true
      observability:
        traces:
          sampling: 1.0
          local:
            buffer-size: 200
        exporters:
          otlp:
            endpoint: "{{OTEL_ENDPOINT}}"
    - type: mcp
      port: 3001
      namespace: my-tools
      # ... tools ...
```

### Local dev stack

A ready-to-use Prometheus + Grafana + OTel Collector stack is provided in
`src/main/resources/demo/observability/`. Start it with:

```bash
cd src/main/resources/demo/observability
docker compose up -d
```

- Prometheus UI: `http://localhost:9091`
- Grafana UI: `http://localhost:3001` (admin / admin)
- OTel Collector gRPC: `localhost:4317`

Point your capability's OTLP endpoint to `http://localhost:4318` and
Prometheus scrapes the control port's `/metrics` automatically.

## Common mistakes

1. **Disabling `/metrics` without observability** — metrics and traces are now
   configured under `observability`, not `management`. When `observability` is
   absent, metrics and traces default to enabled but return 503 if the OTel SDK
   is not on the classpath.
2. **Port collision** — using the same port for control and a business adapter.
   The linter catches this via `ikanos-control-port-singleton-and-unique`.
3. **Binding control to `0.0.0.0` in dev** — exposes management endpoints on
   all interfaces. Use `localhost` (the default) unless inside a container.
4. **Forgetting binds for OTLP endpoint** — hardcoding `http://localhost:4318`
   works in dev but breaks in production. Use Mustache + binds.

## References

- Schema: `ExposesControl`, `ControlManagementSpec`, `ObservabilitySpec` in
  `ikanos-spec/src/main/resources/schemas/ikanos-schema.json`
- Polychro Rules: `ikanos-control-port-singleton-and-unique`,
  `ikanos-control-address-localhost-warning`
- Blueprint: `src/main/resources/blueprints/control-port.md`
- Blueprint: `src/main/resources/blueprints/opentelemetry-observability.md`
- Blueprint: `src/main/resources/blueprints/inline-script-step.md`
- Demo stack: `src/main/resources/demo/observability/`
