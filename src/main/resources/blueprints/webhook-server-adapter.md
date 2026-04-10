# Webhook Server Adapter
## Receiving External Events and Triggering Orchestrated Reactions

**Status**: Proposed

**Version**: 1.0.0-alpha1

**Date**: April 4, 2026

**Roadmap target**: [Second Alpha — May 11th](../wiki/Roadmap.md)

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Architecture Overview](#architecture-overview)
3. [Specification Landscape — AsyncAPI, OAS, Arazzo](#specification-landscape--asyncapi-oas-arazzo)
4. [Design Analogy](#design-analogy)
5. [Core Concepts](#core-concepts)
6. [Schema Changes — Exposes](#schema-changes--exposes)
7. [Schema Changes — Aggregates](#schema-changes--aggregates)
8. [Schema Changes — Consumes](#schema-changes--consumes)
9. [Implementation Examples](#implementation-examples)
10. [Security Considerations](#security-considerations)
11. [Validation Rules](#validation-rules)
12. [Design Decisions & Rationale](#design-decisions--rationale)
13. [Implementation Roadmap](#implementation-roadmap)
14. [Backward Compatibility](#backward-compatibility)

---

## 1. Executive Summary

### What This Proposes

A new `type: "webhook"` server adapter that lets a Naftiko capability **receive inbound HTTP events** from external platforms (GitHub, Stripe, Notion, Slack, etc.) and **react** by orchestrating consumed API calls. This inverts the existing request-response model: instead of a client explicitly calling a tool or endpoint, an external system pushes an event and the capability processes it autonomously.

Three building blocks:

1. **`ExposesWebhook` adapter** (exposes layer) — Listens on an HTTP endpoint, validates incoming payloads, dispatches events to handlers based on type/topic routing.

2. **Aggregate `semantics` extension** (aggregates layer) — New `reactive: true` semantic flag so aggregate functions can express that they are triggered by events rather than client requests. This is purely descriptive metadata — the orchestration engine (`call`, `steps`, `with`, `outputParameters`) remains unchanged.

3. **Consumes webhook registration** (consumes layer) — Optional `webhooks` block on consumed HTTP adapters allowing the engine to **auto-register** webhook subscriptions at startup and **auto-deregister** at shutdown, eliminating manual webhook configuration on the source platform.

### What This Does NOT Do

- **No persistent event store** — events are processed inline; if the handler fails, the event is lost (retry is the source platform's responsibility via standard HTTP status codes).
- **No fan-out / pub-sub** — each webhook endpoint maps to exactly one handler chain. For fan-out, use multiple webhook endpoints or an external event bus.
- **No WebSocket or SSE** — this is standard HTTP POST webhook reception only. Streaming adapters are a separate concern.
- **No changes to CI/CD workflows** or branch protection rules.

### Business Value

| Benefit | Impact | Users |
|---------|--------|-------|
| **Workflow automation** | React to external events (PR merged → deploy, payment received → provision) | DevOps, Developers |
| **Zero-glue integration** | Replace custom webhook handlers (AWS Lambda, Zapier) with declarative YAML | Architects |
| **Aggregate reuse** | Same domain function handles both on-demand calls (MCP/REST) and event-driven triggers (webhook) | Domain Modelers |
| **Auto-registration** | No manual webhook setup on source platforms — engine registers on startup | Operations |
| **Security-first** | Payload signature verification built-in (HMAC-SHA256, etc.) | InfoSec Teams |

### Key Design Decisions

1. **Same orchestration engine**: Webhook handlers use `call`/`steps`/`with`/`outputParameters` — the existing `OperationStepExecutor`. No new execution paradigm.

2. **Event routing by type**: Each handler declares an `event` filter (exact match or pattern). The adapter inspects the inbound payload or header to dispatch to the matching handler.

3. **HTTP response is the output**: Webhook endpoints return the handler's `outputParameters` as the HTTP response body (JSON). Source platforms that expect a simple `200 OK` get an empty body when no `outputParameters` are declared.

4. **Signature verification is declarative**: A `verification` block on the adapter configures HMAC validation using a secret from `binds` — no custom code needed.

5. **Aggregate functions can be both reactive and on-demand**: A function with `semantics.reactive: true` signals that it was designed for event triggering, but nothing prevents it from being also exposed via MCP or REST. The adapter simply invokes it with the event payload as input.

6. **Auto-registration is opt-in**: The `webhooks` block on consumes is optional. Manual registration (configuring the webhook URL on the source platform) is always supported.

### Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| **Replay attacks** | Medium | High | Timestamp validation + HMAC verification |
| **Payload injection** | Medium | High | Strict JSON schema validation on event payloads |
| **DDoS via webhook flood** | Medium | Medium | Rate limiting, source IP allowlisting |
| **Registration secret leak** | Low | High | Secrets via `binds`, never in YAML |
| **Schema complexity** | Low | Low | Additive — new adapter type, optional consumes block |

**Overall Risk**: **MEDIUM** — Webhook endpoints are internet-facing; signature verification and input validation are non-negotiable.

---

## 2. Architecture Overview

### Current State

```
External Platform ──(manual config)──► Webhook URL on custom handler
                                        │
                                        ▼
                                    Custom code (Lambda, Express, etc.)
                                        │
                                        ▼
                                    Downstream API calls (hand-rolled)
```

### Proposed State

```
External Platform ──(auto-registered or manual)──► Naftiko Webhook Adapter
                                                      │
                                                      ├─ Verify signature (HMAC)
                                                      ├─ Route by event type
                                                      ├─ Extract input parameters
                                                      │
                                                      ▼
                                                  OperationStepExecutor
                                                      │
                                                      ├─ step 1: call consumed API
                                                      ├─ step 2: lookup / transform
                                                      └─ step N: call consumed API
                                                      │
                                                      ▼
                                                  HTTP Response (200 + output)
```

### Adapter Comparison (updated)

| Feature | REST | MCP | Skill | **Webhook** |
|---------|------|-----|-------|-------------|
| **Trigger** | Client request | Agent request | Agent discovery | **External event push** |
| **Transport** | HTTP | HTTP / stdio | HTTP | **HTTP (POST)** |
| **Port Required** | Yes | Yes (if HTTP) | Yes | **Yes** |
| **Authentication** | Supported | Not yet | Supported | **Signature verification** |
| **Streaming** | No | Yes (Streamable HTTP) | No | **No** |
| **`ref` support** | Yes | Yes | No (derived) | **Yes** |
| **Orchestration** | call / steps | call / steps | N/A | **call / steps** |

---

## 3. Specification Landscape — AsyncAPI, OAS, Arazzo

Three industry specifications address webhook and event-driven semantics at different levels of abstraction. This section analyzes each and maps their concepts to the Naftiko webhook adapter design, identifying what we adopt, adapt, or deliberately diverge from.

### 3.1 OpenAPI Specification (OAS 3.1) — Webhooks & Callbacks

OAS 3.1 introduced a top-level `webhooks` field alongside the existing `callbacks` mechanism on operations.

#### `webhooks` (OAS 3.1, §4.8.1.1)

```yaml
# OAS webhooks — describe incoming requests the API provider may send
webhooks:
  newPet:
    post:
      requestBody:
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/Pet'
      responses:
        '200':
          description: webhook processed
```

- **Semantics**: `webhooks` describes requests **initiated by the API provider** — not by the client. Each webhook is a `Path Item Object` keyed by a unique name. The key is a logical identifier, not a URL.
- **No subscription model**: OAS `webhooks` are purely descriptive — they document "this API may push events to you" but define no mechanism for registering, verifying, or routing them. Registration is "out of band."
- **Per-event shape**: Each webhook entry defines the request body schema and expected responses, providing a typed contract for each event.

#### `callbacks` (OAS 3.1, §4.8.18)

```yaml
# OAS callbacks — out-of-band requests triggered by a parent operation
callbacks:
  onPaymentComplete:
    '{$request.body#/callbackUrl}':
      post:
        requestBody:
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/PaymentEvent'
        responses:
          '200':
            description: callback acknowledged
```

- **Semantics**: Callbacks are **tied to a parent operation** — they model "after you call this operation, the server may call you back at the URL you provided." The URL is a runtime expression (`{$request.body#/callbackUrl}`).
- **Difference from webhooks**: Callbacks are operation-scoped; webhooks are standalone. Callbacks imply a prior API call that initiates the subscription; webhooks don't.

#### Mapping to Naftiko

| OAS Concept | Naftiko Equivalent | Notes |
|-------------|-------------------|-------|
| `webhooks` key | `ExposesWebhook.handlers[].event` | Both use a logical name to identify the event type |
| `Path Item Object` per webhook | `WebhookHandler` | Both describe the expected request shape and response |
| `requestBody` schema | `WebhookInputParameter[]` with JsonPath extraction | OAS uses full JSON Schema; Naftiko extracts specific fields |
| `responses` | HTTP status code from handler outcome | Naftiko returns 200 + optional `outputParameters` |
| `callbacks` runtime expression | `ConsumesWebhookConfig.targetUrl` (Mustache) | Both inject the callback URL dynamically, but at different scopes |
| No subscription mechanism | `ConsumesWebhookConfig.register/deregister` | **Naftiko goes further** — declarative auto-registration |
| No signature verification | `WebhookVerification` | **Naftiko goes further** — HMAC built into the spec |
| No event routing | `WebhookRouting` (header/payload) | **Naftiko goes further** — declarative dispatch |

**Key insight**: OAS describes webhook *contracts* (what the payload looks like). Naftiko describes webhook *behavior* (how to receive, verify, route, and react). They are complementary — a future evolution could generate Naftiko webhook handlers from OAS webhook definitions.

### 3.2 AsyncAPI (v3.0.0) — Event-Driven Architecture

AsyncAPI is the most directly relevant specification. It was designed for message-driven APIs and introduces concepts that map naturally to webhooks.

#### Core model

```yaml
# AsyncAPI 3.0 — event-driven architecture
asyncapi: 3.0.0
info:
  title: PR Events
channels:
  pullRequestMerged:
    address: github.pull_request.merged
    messages:
      prMerged:
        payload:
          type: object
          properties:
            prNumber: { type: integer }
            repository: { type: string }
operations:
  onPrMerged:
    action: receive
    channel:
      $ref: '#/channels/pullRequestMerged'
```

#### Key concepts

| AsyncAPI Concept | Description | Naftiko Mapping |
|-----------------|-------------|-----------------|
| **Channel** | Addressable message conduit (topic, queue, event type). Has an `address` and typed `messages`. | `WebhookHandler.event` — the event type is the channel address |
| **Operation** (`action: receive`) | The application receives messages from a channel. | `WebhookHandler` — reacts to an inbound event |
| **Operation** (`action: send`) | The application sends messages to a channel. | Not applicable — Naftiko webhooks only receive |
| **Message** | Typed payload + headers + correlationId | `WebhookInputParameter[]` — typed extraction from payload/headers |
| **Server** | Broker or endpoint with protocol + host | `ExposesWebhook` — address + port + path |
| **Bindings** | Protocol-specific details (HTTP method, headers, status codes) | `WebhookVerification` + `WebhookRouting` — webhook-specific protocol details |
| **Operation Reply** | Request-reply pattern within async | Handler `outputParameters` — synchronous response to the webhook sender |
| **Traits** | Reusable operation/message fragments | Naftiko `aggregates` with `ref` — same reuse pattern, different mechanism |
| **Security Scheme** | Auth on server or operation level | `WebhookVerification` (HMAC) — specialized for webhook signing |
| **Components** | Reusable schemas, messages, channels | Naftiko `aggregates` + `components` in JSON Schema |

#### What Naftiko adopts from AsyncAPI

1. **`action: receive` as the core verb** — Naftiko webhook handlers are inherently `receive` operations. The distinction between `send` and `receive` in AsyncAPI maps to Naftiko's separation of `consumes` (send/call) and `exposes` (receive/serve).

2. **Channel as event address** — AsyncAPI's `channel.address` (e.g., `github.pull_request.merged`) is conceptually equivalent to Naftiko's `handler.event`. Both use a string identifier to route messages.

3. **Typed messages with headers and payload** — AsyncAPI separates `headers` from `payload` in the Message Object. Naftiko's `WebhookInputParameter` with `in: payload | header | query` achieves the same separation.

4. **Protocol bindings** — AsyncAPI's HTTP binding specifies method, headers, and status codes for HTTP-transported events. Naftiko's `verification` and `routing` blocks serve the same purpose for webhook-specific HTTP details.

#### Where Naftiko diverges

| AsyncAPI Pattern | Naftiko Approach | Rationale |
|-----------------|------------------|-----------|
| **Multi-protocol** (AMQP, Kafka, MQTT, etc.) | **HTTP POST only** | Webhooks are exclusively HTTP. AsyncAPI's protocol abstraction is valuable for message brokers but unnecessary overhead for webhooks. |
| **Channel as first-class object** | **Event type as handler attribute** | AsyncAPI channels are reusable across operations. Naftiko handlers are self-contained — simpler for the webhook use case. A future event-bus adapter could introduce channels. |
| **CorrelationId** | **Not in MVP** | Useful for request-reply tracing across async flows. Webhooks are typically fire-and-forget. Could be added later for audit/tracing. |
| **Message Traits (mixins)** | **Aggregate `ref`** | AsyncAPI applies traits to messages; Naftiko applies aggregate functions to handlers. Same reuse goal, different granularity. |
| **Server = broker** | **Server = Naftiko adapter** | AsyncAPI servers are external brokers the app connects to. Naftiko's webhook adapter is the server itself — it listens for inbound events. |

**Key insight**: AsyncAPI solves the broader "describe any event-driven system" problem. Naftiko solves the narrower "receive HTTP webhooks and orchestrate reactions" problem. AsyncAPI's vocabulary influenced our naming and structure, but we don't need its multi-protocol, multi-broker abstractions.

### 3.3 Arazzo (v1.0.0) — API Workflow Orchestration

Arazzo defines sequences of API calls (workflows) with step dependencies, success/failure criteria, and runtime expressions. It is the closest specification to Naftiko's orchestration engine.

#### Core model

```yaml
# Arazzo 1.0 — workflow orchestration
arazzo: 1.0.0
info:
  title: PR Merge Reaction
sourceDescriptions:
  - name: slackApi
    url: ./slack-openapi.yaml
    type: openapi
workflows:
  - workflowId: onPrMerged
    inputs:
      type: object
      properties:
        prNumber: { type: integer }
        repository: { type: string }
    steps:
      - stepId: notifySlack
        operationId: postMessage
        parameters:
          - name: channel
            in: body
            value: '#deployments'
          - name: text
            in: body
            value: 'PR #{$inputs.prNumber} merged in {$inputs.repository}'
        successCriteria:
          - condition: $statusCode == 200
    outputs:
      notified: $steps.notifySlack.outputs.ok
```

#### Concept mapping

| Arazzo Concept | Naftiko Equivalent | Alignment |
|---------------|-------------------|-----------|
| **Workflow** (`workflowId`, `inputs`, `steps`, `outputs`) | **Aggregate Function** or **Handler** with `steps` + `outputParameters` | Direct parallel — both define sequenced API call chains |
| **Step** (`stepId`, `operationId`, `parameters`) | **OperationStepCall** (`name`, `call`, `with`) | Nearly identical — Arazzo uses `operationId`, Naftiko uses `namespace.operationName` |
| **Step `outputs`** | Step execution context (`{{step-name.field}}`) | Both make prior step results available to downstream steps |
| **`successCriteria`** (Criterion Object) | Not in Naftiko MVP | Arazzo steps can assert `$statusCode == 200`; Naftiko treats non-2xx as failure. Future: `successCriteria` on steps |
| **`onSuccess` / `onFailure` actions** | Not in Naftiko MVP | Arazzo supports `goto`/`retry`/`end` on step outcomes. Future: conditional/retry steps |
| **`dependsOn`** (workflow dependencies) | Not in Naftiko MVP | Arazzo workflows can declare prerequisites. Naftiko handlers are independent |
| **`requestBody.replacements`** (Payload Replacement) | `with` injectors (Mustache) | Both inject dynamic values into request bodies. Arazzo uses JSON Pointer, Naftiko uses Mustache templates |
| **Runtime Expressions** (`$response.body#/id`) | Mustache + JsonPath (`{{step.field}}`, `$.path`) | Different syntax, same capability — extract and inject runtime values |
| **Source Descriptions** | `consumes` adapters | Both reference external API definitions that steps operate against |
| **Components** (reusable inputs, parameters, actions) | `aggregates` + JSON Schema `$defs` | Both centralize reusable definitions |
| **Failure Action `retry`** (`retryAfter`, `retryLimit`) | Not in Naftiko MVP | Valuable for webhook handlers — added to Phase 3 roadmap |

#### What Naftiko adopts from Arazzo

1. **Workflow as sequenced steps** — Arazzo's `steps` array with `operationId` references and output chaining is the same pattern as Naftiko's `steps` with `call` references and `{{step.field}}` expressions. The designs converged independently.

2. **Input/output contracts** — Arazzo workflows declare typed `inputs` and `outputs`. Naftiko handlers declare `inputParameters` and `outputParameters`. Same contract, different syntax.

3. **Runtime expressions for value injection** — Arazzo uses `$inputs.prNumber`, `$steps.loginStep.outputs.token`, and `$response.body#/id`. Naftiko uses `$this.namespace.param`, `{{step.field}}`, and JsonPath. The concept is identical; Naftiko's Mustache-based syntax is more tightly scoped.

#### What Arazzo offers that could influence future Naftiko evolution

| Arazzo Feature | Naftiko Status | Potential Value |
|---------------|---------------|-----------------|
| **`successCriteria`** on steps | Not supported | Allows conditional flow based on response content, not just status code |
| **`onFailure` with `retry`** | Not supported | Built-in retry with `retryAfter` and `retryLimit` — directly applicable to webhook handlers |
| **`goto` actions** | Not supported | Jump to a different step or workflow on success/failure — enables branching |
| **`dependsOn`** | Not supported | Declare workflow prerequisites — useful for complex multi-webhook scenarios |
| **Criterion Object** (jsonpath, regex, xpath) | Partial (filter expression on handler) | Richer assertion language for step success/failure |

**Key insight**: Arazzo validates that Naftiko's orchestration model (steps + call + with + outputs) is on the right track — it's the same pattern used by the OpenAPI Initiative for workflow orchestration. The gap areas (`successCriteria`, `retry`, `goto`) align well with Naftiko's roadmap items: "conditional steps, for-each steps, parallel-join" and resiliency patterns.

### 3.4 Synthesis — Where Naftiko's Webhook Adapter Sits

```
                   ┌──────────────────────────────────────────────┐
                   │              SPECIFICATION STACK              │
                   │                                              │
                   │  AsyncAPI    ← Event architecture (broad)    │
                   │    ▼                                          │
                   │  OAS 3.1    ← Webhook/callback contracts     │
                   │    ▼                                          │
                   │  Arazzo     ← Step orchestration              │
                   │    ▼                                          │
                   │  ┌──────────────────────────────────┐        │
                   │  │    NAFTIKO WEBHOOK ADAPTER        │        │
                   │  │                                    │        │
                   │  │  Receive (AsyncAPI action:receive) │        │
                   │  │  + Typed payload (OAS schemas)     │        │
                   │  │  + Verify (HMAC — beyond OAS)      │        │
                   │  │  + Route (event type dispatch)     │        │
                   │  │  + Orchestrate (Arazzo-like steps)  │        │
                   │  │  + Register (beyond all three)     │        │
                   │  └──────────────────────────────────┘        │
                   └──────────────────────────────────────────────┘
```

| Capability | OAS 3.1 | AsyncAPI 3.0 | Arazzo 1.0 | **Naftiko Webhook** |
|-----------|---------|-------------|-----------|---------------------|
| Describe event payload | Yes (JSON Schema) | Yes (Multi-format Schema) | No (references OAS) | Yes (typed input parameters) |
| Describe expected response | Yes (Response Object) | Yes (Operation Reply) | Yes (successCriteria) | Yes (outputParameters) |
| Subscription registration | No | No (bindings hint) | No | **Yes (auto-register/deregister)** |
| Signature verification | No | No | No | **Yes (HMAC-SHA256/SHA1)** |
| Event routing/dispatch | No | Via channels | Via steps | **Yes (header/payload routing)** |
| Reaction orchestration | No | No | **Yes (steps)** | **Yes (call/steps/ref)** |
| Multi-protocol | No (HTTP only) | **Yes (AMQP, Kafka, etc.)** | No (HTTP assumed) | No (HTTP POST only) |
| Reusable definitions | Yes (components) | Yes (components) | Yes (components) | Yes (aggregates + ref) |

**Conclusion**: Naftiko's webhook adapter combines the strongest element from each specification — **event contracts** from OAS, **receive semantics** from AsyncAPI, and **step orchestration** from Arazzo — then adds the operational features none of them provide: **auto-registration**, **HMAC verification**, and **declarative routing**.

---

## 4. Design Analogy

### REST is a waiter. Webhook is a doorbell.

The `rest` adapter is like a restaurant waiter: the client walks in, places an order (HTTP request), and waits for the dish (HTTP response). The waiter controls the pace.

The `webhook` adapter is like a doorbell: someone outside presses it (external event), and the house reacts (handler chain). The visitor doesn't wait for a full conversation — they just need to know the bell was heard (HTTP 200).

Both use the same kitchen (orchestration engine) to prepare the result. The difference is who initiates the interaction.

### In Naftiko terms

| Concept | REST | Webhook |
|---------|------|---------|
| **Who initiates** | Client (human, agent, tool) | External platform (GitHub, Stripe, Slack) |
| **Entry point** | Resource path + HTTP method | Event type + HTTP POST |
| **Parameters** | query / path / header / body | Event payload fields |
| **Response** | Shaped output | Acknowledgement (200) + optional output |
| **Idempotency** | Client's responsibility | Source platform may retry — handler should be idempotent |

---

## 5. Core Concepts

### 4.1 Event Handler

A webhook **handler** is the equivalent of an exposed REST operation or MCP tool. It binds to:

- An **event type** (e.g., `push`, `pull_request.opened`, `invoice.payment_succeeded`)
- An optional **filter expression** for fine-grained matching (e.g., only `pull_request` events where `action == "merged"`)
- **Input parameters** extracted from the event payload (via JsonPath)
- An **orchestration chain** (`call`/`steps`/`with`) that reacts to the event
- Optional **output parameters** returned in the HTTP response body

### 4.2 Event Routing

The adapter inspects the incoming request to determine which handler should process it. Routing uses two sources:

1. **Header-based**: A configurable header (e.g., `X-GitHub-Event`, `Stripe-Event-Type`) maps to the handler's `event` field.
2. **Payload-based**: A JsonPath expression on the request body extracts the event type (e.g., `$.event_type`).

The `routing` block on the adapter declares which strategy to use. If no handler matches, the adapter returns `400 Bad Request`.

### 4.3 Signature Verification

Most webhook providers sign payloads using HMAC-SHA256 (or similar). The `verification` block declares:

- `algorithm`: `hmac-sha256`, `hmac-sha1`, or `none`
- `header`: The HTTP header containing the signature (e.g., `X-Hub-Signature-256`)
- `secret`: A Mustache variable reference to the signing secret from `binds` (e.g., `{{GITHUB_WEBHOOK_SECRET}}`)
- `prefix`: Optional string prefix to strip from the header value (e.g., `sha256=`)

### 4.4 Auto-Registration (Consumes Evolution)

For platforms that support programmatic webhook management (GitHub, Stripe, Notion), the `webhooks` block on the consumed HTTP adapter declares:

- **Registration endpoint**: The API call to create the webhook subscription
- **Deregistration endpoint**: The API call to remove it
- **Target URL**: The Naftiko webhook adapter's external URL (injected via `binds`)
- **Events**: The list of event types to subscribe to

The engine calls the registration endpoint at startup and the deregistration endpoint at shutdown. The subscription ID is stored in memory for the lifecycle.

---

## 6. Schema Changes — Exposes

### 5.1 `ExposesWebhook` (new adapter type)

```yaml
# New entry in oneOf for exposes items
ExposesWebhook:
  type: object
  additionalProperties: false
  required: [type, port, namespace, routing, handlers]
  properties:
    type:
      type: string
      const: "webhook"
    address:
      $ref: "#/$defs/Address"
    port:
      type: integer
      minimum: 1
      maximum: 65535
    namespace:
      $ref: "#/$defs/IdentifierKebab"
    description:
      type: string
    path:
      type: string
      description: >
        Base path for the webhook endpoint (e.g., "/webhooks/github").
        All handlers share this path; routing is by event type, not URL.
      pattern: "^/[a-z0-9/_-]*$"
      default: "/webhook"
    verification:
      $ref: "#/$defs/WebhookVerification"
    routing:
      $ref: "#/$defs/WebhookRouting"
    handlers:
      type: array
      items:
        $ref: "#/$defs/WebhookHandler"
      minItems: 1
```

### 5.2 `WebhookVerification`

```yaml
WebhookVerification:
  type: object
  additionalProperties: false
  required: [algorithm, header, secret]
  properties:
    algorithm:
      type: string
      enum: [hmac-sha256, hmac-sha1, none]
      description: "Signature algorithm used by the webhook provider."
    header:
      type: string
      description: "HTTP header containing the signature (e.g., X-Hub-Signature-256)."
    secret:
      type: string
      description: "Mustache reference to the signing secret from binds (e.g., {{WEBHOOK_SECRET}})."
    prefix:
      type: string
      description: "Prefix to strip from the header value before verification (e.g., 'sha256=')."
```

### 5.3 `WebhookRouting`

```yaml
WebhookRouting:
  type: object
  additionalProperties: false
  required: [source]
  properties:
    source:
      type: string
      enum: [header, payload]
      description: "Where to find the event type: an HTTP header or a payload field."
    header:
      type: string
      description: "Header name (required when source is 'header'). E.g., 'X-GitHub-Event'."
    expression:
      type: string
      description: "JsonPath expression (required when source is 'payload'). E.g., '$.event_type'."
  # Conditional: header required when source=header, expression required when source=payload
```

### 5.4 `WebhookHandler`

A handler follows the same `anyOf` pattern as `ExposedOperation` and `McpTool` — supporting simple call, orchestrated steps, or aggregate ref.

```yaml
WebhookHandler:
  type: object
  additionalProperties: false
  required: [event]
  properties:
    event:
      type: string
      description: >
        Event type this handler reacts to. Matched against the value extracted
        by the routing config. Supports exact match or glob patterns (e.g.,
        'pull_request.*').
    name:
      $ref: "#/$defs/IdentifierKebab"
    label:
      type: string
    description:
      type: string
    filter:
      type: string
      description: >
        Optional JsonPath boolean expression for fine-grained filtering on
        the event payload. If the expression evaluates to false, the handler
        is skipped and the adapter returns 204 No Content.
    inputParameters:
      type: array
      items:
        $ref: "#/$defs/WebhookInputParameter"
    # --- Same anyOf as ExposedOperation ---
    call:
      type: string
      pattern: "^[a-zA-Z0-9-]+\\.[a-zA-Z0-9-]+$"
    with:
      $ref: "#/$defs/WithInjector"
    steps:
      type: array
      items:
        $ref: "#/$defs/OperationStep"
      minItems: 1
    mappings:
      type: array
      items:
        $ref: "#/$defs/StepOutputMapping"
    outputParameters:  # varies by mode (Mapped vs Orchestrated)
    ref:
      type: string
      pattern: "^[a-zA-Z0-9-]+\\.[a-zA-Z0-9-]+$"
  anyOf:
    - required: [name, call]
    - required: [name, steps]
    - required: [ref]
```

### 5.5 `WebhookInputParameter`

Webhook input parameters extract values from the event payload, headers, or query string (for platforms that append metadata to the webhook URL).

```yaml
WebhookInputParameter:
  type: object
  additionalProperties: false
  required: [name, type, in]
  properties:
    name:
      $ref: "#/$defs/IdentifierKebab"
    type:
      type: string
      enum: [string, number, boolean, object, array]
    description:
      type: string
    in:
      type: string
      enum: [payload, header, query]
      description: "Where to extract the value from the incoming webhook request."
    expression:
      type: string
      description: "JsonPath expression for payload extraction (required when in='payload')."
```

---

## 7. Schema Changes — Aggregates

### 6.1 New semantic: `reactive`

Extend the `AggregateSemantics` definition with a `reactive` flag:

```yaml
# Current
AggregateSemantics:
  properties:
    safe: { type: boolean }
    idempotent: { type: boolean }
    cacheable: { type: boolean }

# Proposed
AggregateSemantics:
  properties:
    safe: { type: boolean }
    idempotent: { type: boolean }
    cacheable: { type: boolean }
    reactive: { type: boolean }     # NEW — indicates event-triggered domain logic
```

### 6.2 Purpose and derivation

`reactive: true` is purely descriptive metadata. It tells consumers:

- This function was designed to be triggered by an external event (webhook, message queue, etc.).
- The function's input parameters align with event payload shapes.
- The function is likely _not_ idempotent by default (state changes happen on event receipt).

**No automatic derivation** for webhooks (unlike `semantics → hints` for MCP). The flag is informational — useful for documentation, agent discovery, and future event-bus integration.

### 6.3 Impact on aggregate design

Functions with `reactive: true` can still be referenced via `ref` from REST and MCP adapters. This enables a powerful pattern: **test a webhook handler manually** by invoking the same function through the REST or MCP adapter, then letting real events trigger it in production.

```yaml
aggregates:
  - label: "PR Automation"
    namespace: "pr-automation"
    functions:
      - name: "on-pr-merged"
        description: "React to a merged pull request: update tracking, notify team."
        semantics:
          reactive: true
          idempotent: true    # safe to retry if the webhook is replayed
        inputParameters:
          - name: "pr-number"
            type: "number"
            description: "Pull request number."
          - name: "repository"
            type: "string"
            description: "Full repository name (owner/repo)."
          - name: "merged-by"
            type: "string"
            description: "Username of the person who merged."
        steps:
          - type: "call"
            name: "update-tracker"
            call: "project-tracker.mark-done"
            with:
              pr: "pr-number"
              repo: "repository"
          - type: "call"
            name: "notify"
            call: "slack.post-message"
            with:
              channel: "#deployments"
              text: "PR #{{pr-number}} merged by {{merged-by}} in {{repository}}"
        outputParameters:
          - name: "tracker-updated"
            type: "boolean"
          - name: "notification-sent"
            type: "boolean"
```

---

## 8. Schema Changes — Consumes

### 7.1 Optional `webhooks` block on `ConsumesHttp`

```yaml
ConsumesHttp:
  properties:
    # ... existing: namespace, type, baseUri, authentication, resources ...
    webhooks:                                  # NEW — optional
      $ref: "#/$defs/ConsumesWebhookConfig"
```

### 7.2 `ConsumesWebhookConfig`

```yaml
ConsumesWebhookConfig:
  type: object
  additionalProperties: false
  required: [register, events, targetUrl]
  properties:
    register:
      $ref: "#/$defs/WebhookRegistrationCall"
      description: "API call to register the webhook subscription on the source platform."
    deregister:
      $ref: "#/$defs/WebhookDeregistrationCall"
      description: "API call to remove the webhook subscription. Optional — if omitted, the subscription persists after shutdown."
    targetUrl:
      type: string
      description: >
        The external URL of the Naftiko webhook adapter endpoint that the source
        platform should send events to. Typically injected from binds
        (e.g., '{{WEBHOOK_CALLBACK_URL}}').
    events:
      type: array
      items:
        type: string
      minItems: 1
      description: "Event types to subscribe to (e.g., ['push', 'pull_request'])."
    secret:
      type: string
      description: >
        Shared secret for webhook signature verification, injected into the
        registration request and used by the exposes webhook adapter for
        HMAC validation. From binds (e.g., '{{GITHUB_WEBHOOK_SECRET}}').
```

### 7.3 `WebhookRegistrationCall` / `WebhookDeregistrationCall`

These reuse the existing consumed operation pattern — a `call` reference to a consumed operation plus `with` injectors.

```yaml
WebhookRegistrationCall:
  type: object
  additionalProperties: false
  required: [call]
  properties:
    call:
      type: string
      pattern: "^[a-zA-Z0-9-]+\\.[a-zA-Z0-9-]+$"
      description: "Reference to a consumed operation (namespace.operation) that creates the webhook subscription."
    with:
      $ref: "#/$defs/WithInjector"
      description: "Parameter injection for the registration call."

WebhookDeregistrationCall:
  type: object
  additionalProperties: false
  required: [call]
  properties:
    call:
      type: string
      pattern: "^[a-zA-Z0-9-]+\\.[a-zA-Z0-9-]+$"
    with:
      $ref: "#/$defs/WithInjector"
```

### 7.4 Example: GitHub webhook auto-registration

```yaml
consumes:
  - type: "http"
    namespace: "github"
    baseUri: "https://api.github.com"
    authentication:
      type: "bearer"
      token: "{{GITHUB_TOKEN}}"
    resources:
      - name: "webhooks"
        path: "/repos/{{GITHUB_OWNER}}/{{GITHUB_REPO}}/hooks"
        operations:
          - name: "create-webhook"
            method: "POST"
            body:
              type: "json"
              data:
                name: "web"
                active: true
                events: "{{events}}"
                config:
                  url: "{{callback-url}}"
                  content_type: "json"
                  secret: "{{webhook-secret}}"
            outputParameters:
              - name: "hook-id"
                type: "number"
                value: "$.id"
          - name: "delete-webhook"
            method: "DELETE"
            path: "/{{hookId}}"
    webhooks:
      register:
        call: "github.create-webhook"
        with:
          callback-url: "{{WEBHOOK_CALLBACK_URL}}"
          webhook-secret: "{{GITHUB_WEBHOOK_SECRET}}"
          events: '["push", "pull_request"]'
      deregister:
        call: "github.delete-webhook"
      targetUrl: "{{WEBHOOK_CALLBACK_URL}}"
      events:
        - "push"
        - "pull_request"
      secret: "{{GITHUB_WEBHOOK_SECRET}}"
```

---

## 9. Implementation Examples

### 8.1 Minimal — GitHub PR merge notification (no aggregates)

```yaml
naftiko: "1.0.0-alpha1"

info:
  label: "GitHub PR Merge Notifier"
  description: >
    Listens for GitHub pull_request events and posts a Slack
    message when a PR is merged.
  tags:
    - GitHub
    - Slack
    - Webhook

binds:
  - namespace: "secrets"
    keys:
      GITHUB_WEBHOOK_SECRET: "GITHUB_WEBHOOK_SECRET"
      SLACK_TOKEN: "SLACK_BOT_TOKEN"

capability:
  exposes:
    - type: "webhook"
      address: "0.0.0.0"
      port: 9000
      namespace: "github-hooks"
      path: "/webhooks/github"
      description: "Receives GitHub webhook events."
      verification:
        algorithm: "hmac-sha256"
        header: "X-Hub-Signature-256"
        secret: "{{GITHUB_WEBHOOK_SECRET}}"
        prefix: "sha256="
      routing:
        source: "header"
        header: "X-GitHub-Event"
      handlers:
        - event: "pull_request"
          name: "on-pr-merged"
          description: "Notify Slack when a PR is merged."
          filter: "$.action == 'closed' && $.pull_request.merged == true"
          inputParameters:
            - name: "pr-title"
              type: "string"
              in: "payload"
              expression: "$.pull_request.title"
            - name: "pr-number"
              type: "number"
              in: "payload"
              expression: "$.pull_request.number"
            - name: "merged-by"
              type: "string"
              in: "payload"
              expression: "$.pull_request.merged_by.login"
            - name: "repo"
              type: "string"
              in: "payload"
              expression: "$.repository.full_name"
          call: "slack.post-message"
          with:
            channel: "#deployments"
            text: "PR #{{github-hooks.pr-number}} ({{github-hooks.pr-title}}) merged by {{github-hooks.merged-by}} in {{github-hooks.repo}}"

  consumes:
    - type: "http"
      namespace: "slack"
      description: "Slack Web API for posting notifications."
      baseUri: "https://slack.com/api"
      authentication:
        type: "bearer"
        token: "{{SLACK_TOKEN}}"
      resources:
        - name: "messages"
          path: "/chat.postMessage"
          operations:
            - name: "post-message"
              method: "POST"
              body:
                type: "json"
```

### 8.2 With aggregates — same function via webhook, MCP, and REST

```yaml
naftiko: "1.0.0-alpha1"

info:
  label: "PR Automation (Aggregate)"
  description: >
    Demonstrates a domain function that reacts to PR merge events
    (via webhook) and can also be triggered on-demand (via MCP/REST).
  tags:
    - GitHub
    - Aggregate
    - Webhook

binds:
  - namespace: "secrets"
    keys:
      GITHUB_WEBHOOK_SECRET: "GITHUB_WEBHOOK_SECRET"
      GITHUB_TOKEN: "GITHUB_TOKEN"
      SLACK_TOKEN: "SLACK_BOT_TOKEN"
      WEBHOOK_CALLBACK_URL: "WEBHOOK_CALLBACK_URL"

capability:
  aggregates:
    - label: "PR Lifecycle"
      namespace: "pr-lifecycle"
      functions:
        - name: "on-pr-merged"
          description: >
            Update project tracker and notify the team when a pull request
            is merged. Can be triggered by a webhook event or invoked
            manually for testing.
          semantics:
            reactive: true
            idempotent: true
          inputParameters:
            - name: "pr-number"
              type: "number"
              description: "Pull request number."
            - name: "repo"
              type: "string"
              description: "Full repository name (owner/repo)."
            - name: "merged-by"
              type: "string"
              description: "GitHub username of the person who merged."
          call: "slack.post-message"
          with:
            channel: "#deployments"
            text: "PR #{{pr-number}} merged by {{merged-by}} in {{repo}}"

  exposes:
    # Webhook adapter — event-driven trigger
    - type: "webhook"
      address: "0.0.0.0"
      port: 9000
      namespace: "github-hooks"
      path: "/webhooks/github"
      verification:
        algorithm: "hmac-sha256"
        header: "X-Hub-Signature-256"
        secret: "{{GITHUB_WEBHOOK_SECRET}}"
        prefix: "sha256="
      routing:
        source: "header"
        header: "X-GitHub-Event"
      handlers:
        - event: "pull_request"
          ref: "pr-lifecycle.on-pr-merged"
          filter: "$.action == 'closed' && $.pull_request.merged == true"
          inputParameters:
            - name: "pr-number"
              type: "number"
              in: "payload"
              expression: "$.pull_request.number"
            - name: "repo"
              type: "string"
              in: "payload"
              expression: "$.repository.full_name"
            - name: "merged-by"
              type: "string"
              in: "payload"
              expression: "$.pull_request.merged_by.login"

    # MCP adapter — on-demand trigger (for testing / manual invocation)
    - type: "mcp"
      address: "localhost"
      port: 3000
      namespace: "pr-mcp"
      description: "MCP tools for PR lifecycle management."
      tools:
        - ref: "pr-lifecycle.on-pr-merged"

    # REST adapter — on-demand trigger
    - type: "rest"
      address: "localhost"
      port: 3001
      namespace: "pr-rest"
      resources:
        - path: "/pr-merged"
          name: "pr-merged"
          operations:
            - ref: "pr-lifecycle.on-pr-merged"
              method: "POST"
              inputParameters:
                - name: "pr-number"
                  in: "body"
                  type: "number"
                - name: "repo"
                  in: "body"
                  type: "string"
                - name: "merged-by"
                  in: "body"
                  type: "string"

  consumes:
    - type: "http"
      namespace: "slack"
      description: "Slack Web API for posting notifications."
      baseUri: "https://slack.com/api"
      authentication:
        type: "bearer"
        token: "{{SLACK_TOKEN}}"
      resources:
        - name: "messages"
          path: "/chat.postMessage"
          operations:
            - name: "post-message"
              method: "POST"
              body:
                type: "json"

    - type: "http"
      namespace: "github"
      description: "GitHub REST API for webhook management."
      baseUri: "https://api.github.com"
      authentication:
        type: "bearer"
        token: "{{GITHUB_TOKEN}}"
      resources:
        - name: "webhooks"
          path: "/repos/{{GITHUB_OWNER}}/{{GITHUB_REPO}}/hooks"
          operations:
            - name: "create-webhook"
              method: "POST"
              body:
                type: "json"
              outputParameters:
                - name: "hook-id"
                  type: "number"
                  value: "$.id"
            - name: "delete-webhook"
              method: "DELETE"
              path: "/{{hookId}}"
      webhooks:
        register:
          call: "github.create-webhook"
          with:
            callback-url: "{{WEBHOOK_CALLBACK_URL}}"
            webhook-secret: "{{GITHUB_WEBHOOK_SECRET}}"
        deregister:
          call: "github.delete-webhook"
        targetUrl: "{{WEBHOOK_CALLBACK_URL}}"
        events:
          - "pull_request"
        secret: "{{GITHUB_WEBHOOK_SECRET}}"

### 8.3 Multi-step orchestration — Stripe payment processing

```yaml
# (Excerpt — exposes.webhook section only)
- type: "webhook"
  port: 9001
  namespace: "stripe-hooks"
  path: "/webhooks/stripe"
  verification:
    algorithm: "hmac-sha256"
    header: "Stripe-Signature"
    secret: "{{STRIPE_WEBHOOK_SECRET}}"
  routing:
    source: "payload"
    expression: "$.type"
  handlers:
    - event: "invoice.payment_succeeded"
      name: "on-payment-success"
      description: "Provision access when a Stripe invoice is paid."
      inputParameters:
        - name: "customer-id"
          type: "string"
          in: "payload"
          expression: "$.data.object.customer"
        - name: "amount-paid"
          type: "number"
          in: "payload"
          expression: "$.data.object.amount_paid"
        - name: "subscription-id"
          type: "string"
          in: "payload"
          expression: "$.data.object.subscription"
      steps:
        - type: "call"
          name: "lookup-customer"
          call: "stripe.get-customer"
          with:
            customer-id: "$this.stripe-hooks.customer-id"
        - type: "call"
          name: "provision"
          call: "internal-api.grant-access"
          with:
            email: "{{lookup-customer.email}}"
            plan: "{{lookup-customer.plan}}"
        - type: "call"
          name: "notify"
          call: "slack.post-message"
          with:
            channel: "#billing"
            text: "Payment received: {{lookup-customer.email}} — ${{stripe-hooks.amount-paid}}"
      outputParameters:
        - name: "provisioned"
          type: "boolean"
        - name: "notified"
          type: "boolean"
      mappings:
        - targetName: "provisioned"
          value: "$.provision.success"
        - targetName: "notified"
          value: "$.notify.ok"
```

---

## 10. Security Considerations

### 9.1 Signature Verification (Non-Negotiable)

Every production webhook adapter **must** have a `verification` block with `algorithm` other than `none`. The `none` option exists only for development/testing.

**Validation rule**: `naftiko-webhook-verification-required` (warn) — warns when `algorithm: "none"` is used.

### 9.2 Replay Protection

The `verification` block validates payload integrity but not freshness. To prevent replay attacks:

- Source platforms typically include a timestamp header (e.g., GitHub: delivery timestamp, Stripe: `Stripe-Signature` includes `t=`).
- **Future extension**: Add an optional `maxAge` field to `WebhookVerification` that rejects events older than N seconds.
- **MVP**: Rely on HMAC + source platform retry policies. Document the replay risk.

### 9.3 Input Validation

Webhook payloads are untrusted external input:

- `WebhookInputParameter.expression` extracts specific fields — the handler never sees the raw payload.
- Extracted values are type-checked against the declared `type`.
- Mustache injection in `with` blocks is already sanitized by the existing `Resolver`.

### 9.4 Network Exposure

Webhook endpoints are internet-facing by nature:

- **Rate limiting**: Future extension — not MVP. Rely on infrastructure-level rate limiting (reverse proxy, cloud WAF).
- **IP allowlisting**: Future extension — optional `allowedSources` field on the adapter.
- **TLS**: Webhook URLs should always use HTTPS in production. The `targetUrl` in consumes auto-registration should use `https://`.

---

## 11. Validation Rules

### Spectral Rules (New)

| Rule ID | Severity | Description |
|---------|----------|-------------|
| `naftiko-webhook-namespace-unique` | error | Webhook adapter namespace must be unique across all exposes, consumes, and binds |
| `naftiko-webhook-verification-not-none` | warn | `verification.algorithm` should not be `none` in production capabilities |
| `naftiko-webhook-handler-event-unique` | error | No two handlers in the same adapter can have the same `event` value |
| `naftiko-webhook-routing-header-required` | error | When `routing.source` is `header`, the `header` field must be present |
| `naftiko-webhook-routing-expression-required` | error | When `routing.source` is `payload`, the `expression` field must be present |
| `naftiko-webhook-input-expression-required` | error | When `inputParameter.in` is `payload`, the `expression` field must be present |
| `naftiko-webhook-handler-orphan-input` | warn | Every handler `inputParameter` should be referenced in `with`, `steps`, or `mappings` |
| `naftiko-webhook-ref-valid` | error | `ref` must resolve to an existing aggregate function |
| `naftiko-consumes-webhook-register-valid` | error | `webhooks.register.call` must reference a valid consumed operation |

### JSON Schema Constraints

- `ExposesWebhook` added to the `oneOf` array of `exposes` items (alongside `ExposesRest`, `ExposesMcp`, `ExposesSkill`)
- `WebhookHandler` uses `anyOf` for the three modes (call, steps, ref) — same pattern as `ExposedOperation`
- `ConsumesWebhookConfig` is optional on `ConsumesHttp` — `additionalProperties: false` must be updated to allow `webhooks`
- `AggregateSemantics` expanded to include `reactive` (optional boolean)

---

## 12. Design Decisions & Rationale

### D1: Single path, route by event type (not path-per-handler)

**Decision**: All handlers share one path (e.g., `/webhooks/github`). The adapter routes by event type extracted from headers or payload.

**Why**: Most webhook providers send all events to a single URL. Creating path-per-handler would force users to register multiple webhooks on the source platform — more configuration, more secrets to manage, more surface area.

**Tradeoff**: Cannot host handlers for multiple providers on the same path. Use separate `ExposesWebhook` entries (different port or path) for different providers.

### D2: `verification` is separate from `authentication`

**Decision**: Webhook signature verification is a distinct concept from server authentication. It gets its own `verification` block rather than reusing the existing `Authentication` type.

**Why**: Webhook verification is payload-level (HMAC of the body), not transport-level (bearer token, basic auth). The semantics, headers, and algorithms are different. Reusing `Authentication` would conflate two distinct security concerns.

### D3: Auto-registration is on `consumes`, not `exposes`

**Decision**: The `webhooks` block lives on the consumed HTTP adapter, not on the webhook server adapter.

**Why**: Webhook registration is an API call _to_ the external platform (GitHub, Stripe) — it belongs with the consumes adapter that knows how to talk to that platform. The exposes adapter only knows how to _receive_ events. This follows the existing separation: consumes = outbound, exposes = inbound.

### D4: `reactive` semantic is informational, not behavioral

**Decision**: `semantics.reactive: true` does not change orchestration behavior. It's metadata for documentation and discovery.

**Why**: Adding behavioral semantics (e.g., "auto-retry", "at-least-once delivery") would require an event store and delivery guarantees — far beyond MVP scope. The flag is forward-compatible: a future event bus adapter can use it to identify which functions are event-ready.

### D5: Handlers use the same `anyOf` pattern (call / steps / ref)

**Decision**: `WebhookHandler` supports all three orchestration modes, identical to `ExposedOperation` and `McpTool`.

**Why**: Consistency across adapters reduces cognitive load and ensures aggregate `ref` works everywhere. The orchestration engine (`OperationStepExecutor`) is already adapter-agnostic — no new execution code needed.

### D6: No persistent event queue (MVP)

**Decision**: Events are processed synchronously. If the handler fails, the event is lost from Naftiko's perspective.

**Why**: Most webhook providers implement their own retry logic (GitHub retries up to 3 times, Stripe retries with exponential backoff). Adding a persistence layer would introduce significant complexity (storage, replay, deduplication) for marginal benefit in the MVP.

**Future**: An optional `queue` configuration could buffer events for reliability, but this is out of scope.

---

## 13. Implementation Roadmap

### Phase 1: Core Webhook Adapter (MVP)

| Step | Scope | Effort |
|------|-------|--------|
| Schema: `ExposesWebhook`, `WebhookHandler`, `WebhookVerification`, `WebhookRouting`, `WebhookInputParameter` | JSON Schema | Small |
| Java: `WebhookServerSpec` extends `ServerSpec` + `@JsonSubTypes` registration | Spec layer | Small |
| Java: `WebhookServerAdapter` extends `ServerAdapter` (Jetty or Restlet HTTP listener) | Adapter layer | Medium |
| Java: Signature verification (HMAC-SHA256/SHA1) | Security | Small |
| Java: Event routing (header-based and payload-based) | Dispatcher | Small |
| Java: Handler dispatch → `OperationStepExecutor` | Integration | Small |
| Java: `Capability.java` type dispatch: `else if ("webhook".equals(...))` | Bootstrap | Trivial |
| Spectral: Validation rules | Linting | Small |
| Tests: Unit + integration with mock webhook payloads | Quality | Medium |
| Example: `webhook-github-slack.yml` | Documentation | Small |

### Phase 2: Auto-Registration

| Step | Scope | Effort |
|------|-------|--------|
| Schema: `ConsumesWebhookConfig`, `WebhookRegistrationCall` | JSON Schema | Small |
| Java: Startup hook — call registration endpoint | Engine lifecycle | Medium |
| Java: Shutdown hook — call deregistration endpoint | Engine lifecycle | Small |
| Schema: `AggregateSemantics.reactive` | JSON Schema | Trivial |
| Tests: Registration/deregistration lifecycle | Quality | Medium |

### Phase 3: Hardening (Future)

| Step | Scope | Effort |
|------|-------|--------|
| Replay protection (`maxAge` on verification) | Security | Small |
| Rate limiting | Security | Medium |
| IP allowlisting (`allowedSources`) | Security | Small |
| Event buffering / retry queue | Reliability | Large |
| Multiple handlers per event (fan-out) | Routing | Medium |

---

## 14. Backward Compatibility

**Fully backward-compatible.** All changes are additive:

- `ExposesWebhook` is a new entry in the existing `oneOf` for exposes — existing `rest`, `mcp`, and `skill` adapters are unchanged.
- `ConsumesWebhookConfig` is an optional new property on `ConsumesHttp` — existing consumes definitions are unaffected.
- `AggregateSemantics.reactive` is a new optional boolean — existing aggregate definitions remain valid.
- No existing validation rules are modified.
- No existing Java classes are structurally changed (only `Capability.java` type dispatch and `ServerSpec.java` `@JsonSubTypes` get new entries).
