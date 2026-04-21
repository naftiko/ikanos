# Aggregate as DDD Core — Four Dimensions
## Functions, Events, Skills, and Terms in a Unified Domain Building Block

**Status**: Proposed

**Version**: 1.0.0-alpha2

**Date**: April 20, 2026

**Roadmap target**: [Third Alpha — May](../wiki/Roadmap.md) (Phases 1–2), [First Beta — June](../wiki/Roadmap.md) (Phase 3), [GA — September](../wiki/Roadmap.md) (Phase 4)

**Related**: [webhook-server-adapter.md](webhook-server-adapter.md) (events), [agent-skills-support.md](agent-skills-support.md) (skills), [standalone-exposes-aggregates.md](standalone-exposes-aggregates.md) (modular aggregates)

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Architecture Overview](#architecture-overview)
3. [Design Analogy](#design-analogy)
4. [Specification Landscape — DDD, AsyncAPI, Agent Skills](#specification-landscape--ddd-asyncapi-agent-skills)
5. [Phase 1 — Aggregate Terms](#phase-1--aggregate-terms)
6. [Phase 2 — Aggregate Events](#phase-2--aggregate-events)
7. [Phase 3 — Aggregate Skills](#phase-3--aggregate-skills)
8. [Phase 4 — Full DDD Aggregate Composition](#phase-4--full-ddd-aggregate-composition)
9. [Implementation Examples](#implementation-examples)
10. [Security Considerations](#security-considerations)
11. [Validation Rules](#validation-rules)
12. [Design Decisions & Rationale](#design-decisions--rationale)
13. [Implementation Roadmap](#implementation-roadmap)
14. [Backward Compatibility](#backward-compatibility)

---

## 1. Executive Summary

### What This Proposes

Elevate the Naftiko `Aggregate` from a container of reusable **functions** into a full DDD Aggregate that encapsulates four capability dimensions — **functions**, **events**, **skills**, and **terms** — within a single bounded context. Each aggregate becomes a self-contained domain building block that adapters (`rest`, `mcp`, `skill`, `webhook`) project outward.

Four building blocks, delivered in four phases:

1. **Aggregate Terms** (Phase 1) — A `terms` array on `Aggregate` capturing the ubiquitous language of the bounded context: domain terms, aliases, and definitions that the engine uses to enrich tool descriptions, validate parameter naming, and generate documentation.

2. **Aggregate Events** (Phase 2) — An `events` array on `Aggregate` defining domain events that can be received from external platforms (webhook triggers) or emitted by functions. Events are the reactive counterpart to functions: functions are imperative ("do this"), events are declarative ("this happened"). This phase coordinates with the [Webhook Server Adapter](webhook-server-adapter.md) blueprint.

3. **Aggregate Skills** (Phase 3) — A `skills` array on `Aggregate` capturing domain knowledge, reasoning instructions, and expertise that belong to the bounded context. Today skills live in the `exposes` layer as a catalog adapter; moving their *definition* into aggregates co-locates domain knowledge with the functions and events it supports.

4. **Full DDD Aggregate Composition** (Phase 4) — Standalone aggregate files with all four dimensions, cross-aggregate references, aggregate-level `binds`, and a complete projection matrix where every adapter type projects all four dimensions appropriately.

### What This Does NOT Do

- **No entities / value objects** — DDD entities and value objects are planned for post-GA (v1.1). This blueprint focuses on the behavioral building blocks (functions, events, skills) and their shared vocabulary (terms).
- **No event store or pub-sub** — events are processed inline by the webhook adapter. Persistent event sourcing is a separate concern.
- **No breaking changes** — every phase is additive. Existing aggregates with `functions` only continue to work unchanged.
- **No changes to CI/CD workflows** or branch protection rules.

### Business Value

| Benefit | Impact | Users |
|---------|--------|-------|
| **Ubiquitous language enforcement** | Domain terms defined once, validated across all parameters and descriptions | Domain Modelers, Architects |
| **Event-driven domain logic** | Same aggregate function handles on-demand calls (MCP/REST) and event triggers (webhook) | DevOps, Developers |
| **Co-located domain knowledge** | Reasoning instructions live next to the functions and events they support | AI Agents, Domain Experts |
| **True bounded contexts** | Each aggregate is a self-contained domain package — functions, events, skills, vocabulary | DDD Practitioners |
| **Uniform adapter projection** | One aggregate definition, four adapter projections (REST, MCP, Skill, Webhook) | Platform Teams |
| **Agent context quality** | Domain terms + skill instructions injected into MCP tool descriptions reduce hallucination | AI Teams |

### Key Design Decisions

1. **Terms are aggregate-scoped, not capability-scoped**: Domain terms belong to the bounded context, not the deployment artifact. Two aggregates in the same capability may define the same term differently — that is a valid DDD pattern (separate bounded contexts).

2. **Events are aggregate-owned, adapter-projected**: Domain events are defined on the aggregate. The `webhook` adapter references them via `ref`, just as `mcp` and `rest` reference functions. The event definition is protocol-agnostic.

3. **Skills move from exposes to aggregates**: The `skill` adapter becomes a projection of aggregate skills, not the definition point. This mirrors how `mcp` and `rest` project aggregate functions. Existing `ExposesSkill` with inline tools remains valid for backward compatibility.

4. **Phases are independently valuable**: Each phase delivers standalone value. Phase 2 does not require Phase 1. Phase 3 benefits from Phase 1 (terms feed skill context) but does not require it.

5. **Same orchestration engine for all dimensions**: Events use `call`/`steps`/`with`/`outputParameters` — the existing `OperationStepExecutor`. Skills use the existing file/inline instruction model. No new execution paradigms.

### Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| **Schema complexity** | Medium | Medium | Each phase is additive; new arrays are optional |
| **Terms adoption friction** | Medium | Low | Terms are optional; linting rules are warn-level |
| **Overlap with skill adapter** | Medium | Medium | Clear migration path: inline skills → aggregate skills via `ref` |
| **Event handling security** | Medium | High | Inherits webhook adapter security (HMAC, input validation) |
| **Cross-aggregate coupling** | Low | Medium | Linting rules detect and warn on cross-aggregate references |

**Overall Risk**: **LOW-MEDIUM** — All changes are additive and optional. The primary risk is schema complexity, mitigated by phased delivery.

---

## 2. Architecture Overview

### Current State (Alpha 2)

```
Aggregate
  └── functions[]
        ├── name, description, semantics (safe, idempotent, cacheable)
        ├── inputParameters[]
        ├── call / steps / outputParameters
        └── projected via ref → MCP tools, REST operations
```

Functions are the only building block inside aggregates. Domain knowledge lives in the `skill` adapter (exposes layer). Events are not modeled. Domain vocabulary is implicit in naming conventions.

### Proposed State (GA)

```
Aggregate
  ├── namespace, label
  ├── terms[]                               ← Phase 1
  │     ├── name, description
  │     └── aliases[]
  ├── functions[]                           ← Exists (unchanged)
  │     ├── name, description, semantics
  │     ├── inputParameters[]
  │     ├── call / steps / outputParameters
  │     └── projected via ref → MCP tools, REST operations
  ├── events[]                              ← Phase 2
  │     ├── name, description, semantics (reactive: true)
  │     ├── payload[]
  │     ├── handler: call / steps / outputParameters
  │     └── projected via ref → Webhook handlers
  └── skills[]                              ← Phase 3
        ├── name, description
        ├── instruction (inline / file)
        ├── allowed-functions[]
        └── projected via ref → Skill adapter skills, MCP prompts
```

### Projection Matrix (Target State)

| Dimension | REST | MCP | Skill | Webhook |
|-----------|------|-----|-------|---------|
| **Functions** | Operations via `ref` | Tools via `ref` | Derived tools via `from` | — |
| **Events** | AsyncAPI doc export | Notifications | Event catalog entries | Handlers via `ref` |
| **Skills** | — | Prompts via `ref` | Skills via `ref` | — |
| **Terms** | OAS `x-terms` extension | Tool description enrichment | Frontmatter metadata | — |

---

## 3. Design Analogy

### An aggregate is a department in a company.

A **function** is a service the department provides — "process an order", "check inventory". Anyone can request it (REST client, AI agent, another department).

An **event** is a memo the department issues or receives — "order was placed", "shipment arrived". The department reacts to external memos (webhooks) and announces its own state changes.

A **skill** is the department's institutional knowledge — "how we classify returns", "rules for customs declarations". This knowledge guides how the services are performed and how the memos are interpreted.

A **term** is the department's shared vocabulary — "when we say 'ship', we mean a maritime vessel, not a delivery shipment". Everyone in the department (functions, events, skills) speaks the same language.

### In Naftiko terms

| DDD Concept | Naftiko Aggregate Element | Projected via |
|-------------|--------------------------|---------------|
| Command | `function` (imperative) | REST operation, MCP tool |
| Domain Event | `event` (declarative) | Webhook handler |
| Domain Service knowledge | `skill` (reasoning) | Skill adapter, MCP prompt |
| Ubiquitous Language | `term` (vocabulary) | Description enrichment, linting |
| Bounded Context | `aggregate` (container) | Namespace scoping |

---

## 4. Specification Landscape — DDD, AsyncAPI, Agent Skills

### 4.1 Domain-Driven Design — Aggregates

In DDD (Evans, 2003), an **aggregate** is a cluster of domain objects that can be treated as a single unit. It has:

- A **root entity** with a unique identity
- **Value objects** that describe characteristics
- **Invariants** that must be consistent within the boundary
- A **repository** for persistence

Naftiko aggregates are not persistence-oriented — they are behavioral. The mapping:

| DDD Aggregate Element | Naftiko Aggregate Element | Notes |
|----------------------|--------------------------|-------|
| Root entity identity | `namespace` | Unique identifier within the capability |
| Commands (operations on the aggregate) | `functions` | Imperative actions the aggregate performs |
| Domain events (state changes) | `events` | Declarative signals emitted or received |
| Invariants / business rules | `skills` (reasoning instructions) | Knowledge that constrains how functions execute |
| Ubiquitous language | `terms` | Shared vocabulary within the bounded context |
| Value objects | Not in scope (v1.1 entities) | Future: typed domain objects with validation |
| Repository | Not applicable | Naftiko is stateless; persistence is in consumed APIs |

**Key insight**: Naftiko adopts the DDD aggregate as an **organizational pattern**, not a persistence pattern. The aggregate groups behavioral building blocks (functions, events, skills) around a shared vocabulary (term) — achieving the same goal of bounded context cohesion without requiring a domain model layer.

### 4.2 AsyncAPI — Event Vocabulary

AsyncAPI 3.0 defines events through **channels**, **messages**, and **operations** (see [webhook-server-adapter.md §3.2](webhook-server-adapter.md) for the full analysis). The relevant mapping for aggregate events:

| AsyncAPI Concept | Naftiko Aggregate Event | Notes |
|-----------------|------------------------|-------|
| Channel address | `event.name` | Logical identifier for the event type |
| Message payload schema | `event.payload[]` | Typed parameter extraction from event data |
| Operation `action: receive` | `event.handler` | Orchestration triggered by the event |
| Message traits (reusable) | Aggregate `ref` | Handlers in webhook adapters reference aggregate events |
| Server bindings | Webhook adapter `verification` + `routing` | Protocol-specific details stay in the adapter |

**Design choice**: Naftiko events live in the aggregate (domain layer), not in the adapter (protocol layer). AsyncAPI's `channel` is closest to Naftiko's `event` — both are named, typed, and protocol-agnostic. The protocol details (HTTP POST, HMAC verification, header routing) belong to the webhook adapter, not the event definition.

### 4.3 Agent Skills Specification — Skill Vocabulary

The [Agent Skills Spec](https://agentskills.io/specification) defines skills as metadata documents with frontmatter properties. Naftiko's current `skill` adapter already implements this spec. The evolution to aggregate skills adds:

| Agent Skills Concept | Current (Exposes) | Proposed (Aggregate) |
|---------------------|-------------------|---------------------|
| Skill name + description | `ExposedSkill.name`, `.description` | `AggregateSkill.name`, `.description` |
| Tools | `SkillTool.from` (derived from sibling adapter) | `AggregateSkill.allowed-functions[]` (references aggregate functions) |
| Instructions | `SkillTool.instruction` (file path) | `AggregateSkill.instruction` (inline or file) |
| Frontmatter metadata | `ExposedSkill.*` (full Agent Skills frontmatter) | Adapter-specific — stays in `ExposesSkill` |
| Distribution endpoints | `ExposesSkill` REST endpoints | Unchanged — `ExposesSkill` still serves distribution |

**Design choice**: Domain knowledge (what the skill knows) moves into the aggregate. Distribution metadata (how the skill is cataloged and served) stays in the `skill` adapter. This mirrors how functions define domain logic in aggregates while REST/MCP adapters define protocol details.

---

## 5. Phase 1 — Aggregate Terms

### 5.1 Motivation

The SDI methodology defines terms as "the shared vocabulary and domain language that give the capability meaning." Today, domain vocabulary is implicit — it exists in parameter names, descriptions, and naming conventions, but is never formally declared. This creates three problems:

1. **Inconsistent naming** — different functions in the same aggregate use `ship-name`, `vessel-name`, and `name` for the same concept. Nothing enforces consistency.
2. **Agent hallucination** — LLMs inventing parameter names when calling MCP tools have no authoritative vocabulary to anchor against. They guess based on the tool description.
3. **Onboarding cost** — new contributors must read examples and infer the domain language. There is no dictionary.

### 5.2 Schema Changes

#### `AggregateTerm` (new definition)

```yaml
AggregateTerm:
  type: object
  additionalProperties: false
  required: [term]
  properties:
    term:
      $ref: "#/$defs/IdentifierKebab"
      description: >
        The canonical domain term in kebab-case. This is the preferred
        name for parameters, mappings, and descriptions within the
        aggregate's bounded context.
    aliases:
      type: array
      items:
        $ref: "#/$defs/IdentifierKebab"
      description: >
        Alternative names for the same concept (e.g., 'vessel' as alias
        for 'ship'). Used for validation — the linter warns when an alias
        appears where the canonical term should be used.
    description:
      type: string
      maxLength: 500
      description: >
        Human-readable definition of the term within this bounded context.
        Should clarify meaning, not just expand the abbreviation.
```

#### `Aggregate` extension

```yaml
Aggregate:
  properties:
    # ... existing: label, namespace, functions ...
    term:                               # NEW
      type: array
      items:
        $ref: "#/$defs/AggregateTerm"
      description: >
        Domain vocabulary for this aggregate's bounded context.
        Defines the ubiquitous language that functions, events,
        and skills should use consistently.
```

### 5.3 Engine Behavior — Description Enrichment

When projecting an aggregate function to an MCP tool, the engine appends a **terms context block** to the tool description:

```
# Before (current)
description: "Fetch current weather forecast for a location."

# After (with terms)
description: >
  Fetch current weather forecast for a location.

  Domain terms:
  - forecast: predicted weather conditions for a given location and time period
  - location: city name or geographic coordinates (latitude, longitude)
  - condition: qualitative weather state (e.g., sunny, cloudy, rain)
```

This enrichment is:
- **Automatic** — no configuration needed; if the aggregate has terms, they are appended.
- **Opt-out** — a future `enrichDescription: false` on the tool can suppress it.
- **Adapter-specific** — MCP tools get it in the description. REST operations get it as an `x-terms` OAS extension. Skill adapters get it in frontmatter metadata.

### 5.4 Use Cases

| # | Use Case | Concrete Example | Value |
|---|----------|-----------------|-------|
| 1 | **Consistent parameter naming** | Terms define `ship` with alias `vessel`. Linter warns when a function uses `vessel-name` instead of `ship-name`. | Ubiquitous language enforced at spec level |
| 2 | **MCP tool enrichment** | Agent calling `get-forecast` tool receives domain terms in the description. Knows `condition` means qualitative state, not a filter clause. | Reduced hallucination of parameter semantics |
| 3 | **OAS documentation** | Exported OpenAPI spec includes `x-terms` with term definitions. API consumers see the domain dictionary alongside endpoints. | Self-documenting APIs |
| 4 | **Cross-aggregate conflict detection** | Two aggregates define `order` with different descriptions. Spectral warns: "Term 'order' defined in both 'sales' and 'fulfillment' aggregates with different descriptions." | Catches bounded context leakage |
| 5 | **Onboarding** | New team member reads the terms to understand the domain language before touching functions. | Reduced ramp-up time |

### 5.5 Validation Rules

| Rule ID | Severity | Description |
|---------|----------|-------------|
| `naftiko-aggregate-term-unique` | error | No duplicate terms within the same aggregate |
| `naftiko-aggregate-term-prefer-canonical` | warn | Input/output parameter names should use canonical terms, not aliases |
| `naftiko-aggregate-term-no-cross-conflict` | warn | Same term defined in multiple aggregates with different descriptions |
| `naftiko-aggregate-term-description-recommended` | info | Terms without a description should have one for documentation quality |

---

## 6. Phase 2 — Aggregate Events

### 6.1 Motivation

The [Webhook Server Adapter](webhook-server-adapter.md) blueprint introduced event handling at the adapter level — `WebhookHandler` defines event routing, payload extraction, and orchestration inline on the `webhook` adapter. This works, but it binds event definitions to a specific protocol.

Moving event definitions into the aggregate achieves:

1. **Protocol independence** — the domain event is defined once; the webhook adapter projects it, but a future event bus, MCP notification, or A2A message adapter can project the same event.
2. **Aggregate cohesion** — events that modify or describe the aggregate's domain belong inside it, alongside the functions that produce or react to them.
3. **Testability** — an event handler defined in an aggregate can be invoked through REST or MCP for testing, then triggered by real webhook events in production.

### 6.2 Schema Changes

#### `AggregateEvent` (new definition)

```yaml
AggregateEvent:
  type: object
  additionalProperties: false
  required: [name, description]
  properties:
    name:
      $ref: "#/$defs/IdentifierKebab"
      description: >
        Logical name of the domain event (e.g., 'order-placed',
        'payment-received'). Used for ref resolution from adapters.
    description:
      type: string
      description: "Human-readable description of when and why this event occurs."
    semantics:
      $ref: "#/$defs/Semantics"
      description: >
        Behavioral metadata. Typically includes reactive: true.
        May also include idempotent: true if the handler is safe to replay.
    payload:
      type: array
      items:
        $ref: "#/$defs/McpToolInputParameter"
      description: >
        Typed fields extracted from the event data. Uses the same
        parameter model as function inputParameters for consistency.
    handler:
      $ref: "#/$defs/AggregateEventHandler"
      description: >
        Optional inline handler that reacts to the event. Uses the
        same call/steps/with/outputParameters model as functions.
        When omitted, the event is a pure contract — adapters must
        provide their own handling logic.
```

#### `AggregateEventHandler` (new definition)

Reuses the existing orchestration model — identical structure to the execution part of `AggregateFunction`.

```yaml
AggregateEventHandler:
  type: object
  additionalProperties: false
  properties:
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
    outputParameters:
      type: array
  anyOf:
    - required: [call]
    - required: [steps]
```

#### `Aggregate` extension

```yaml
Aggregate:
  properties:
    # ... existing: label, namespace, functions, terms (Phase 1) ...
    events:                                 # NEW
      type: array
      items:
        $ref: "#/$defs/AggregateEvent"
      description: >
        Domain events owned by this aggregate. Events can be received
        from external platforms (via webhook ref) or emitted by
        functions (future: event emission steps).
```

#### `Semantics` extension

```yaml
Semantics:
  properties:
    # ... existing: safe, idempotent, cacheable ...
    reactive:                               # NEW
      type: boolean
      description: >
        Indicates this element is designed to be triggered by external
        events rather than client requests. Informational — does not
        change orchestration behavior.
```

### 6.3 Coordination with Webhook Adapter

The [Webhook Server Adapter](webhook-server-adapter.md) blueprint defines `WebhookHandler` with inline `call`/`steps` logic. With aggregate events, the handler can reference an aggregate event via `ref`:

```yaml
# Webhook adapter references an aggregate event
exposes:
  - type: "webhook"
    port: 9000
    namespace: "github-hooks"
    routing:
      source: "header"
      header: "X-GitHub-Event"
    verification:
      algorithm: "hmac-sha256"
      header: "X-Hub-Signature-256"
      secret: "{{GITHUB_WEBHOOK_SECRET}}"
    handlers:
      - event: "pull_request"
        ref: "pr-lifecycle.on-pr-merged"        # ← references aggregate event
        filter: "$.action == 'closed' && $.pull_request.merged == true"
        inputParameters:                         # ← adapter-specific extraction
          - name: "pr-number"
            type: "number"
            in: "payload"
            expression: "$.pull_request.number"
```

The `ref` resolves to an `AggregateEvent` (not just an `AggregateFunction`). The `AggregateRefResolver` is extended to look up events by `namespace.event-name`. The adapter provides protocol-specific details (routing, verification, payload extraction), while the aggregate provides the domain handler logic.

### 6.4 Use Cases

| # | Use Case | Concrete Example | Value |
|---|----------|-----------------|-------|
| 1 | **Webhook-triggered orchestration** | GitHub pushes `pull_request` event → webhook adapter routes to `pr-lifecycle.on-pr-merged` → handler calls Slack + Jira | Declarative workflow automation |
| 2 | **Same event, multiple projections** | `order-placed` event handled by webhook (Stripe callback) and discoverable in skill catalog (agent awareness) | Protocol-independent event modeling |
| 3 | **Manual testing via REST** | Developer invokes `pr-lifecycle.on-pr-merged` handler through REST adapter with test payload | Event handlers testable without real webhook traffic |
| 4 | **Event catalog for agents** | Agent discovers available events through skill adapter → knows what triggers exist → can suggest webhook configurations | Agent-driven integration design |
| 5 | **Typed event contracts** | Downstream capability imports an upstream aggregate → uses its `events[].payload` as a typed contract | Event-driven architecture with spec-level type safety |
| 6 | **Auto-registration** | Engine reads aggregate events → registers webhook URLs on consumed platforms at startup → deregisters at shutdown | Zero-glue integration with GitHub, Stripe, Notion |

### 6.5 Validation Rules

| Rule ID | Severity | Description |
|---------|----------|-------------|
| `naftiko-aggregate-event-name-unique` | error | No duplicate event names within the same aggregate |
| `naftiko-aggregate-event-payload-defined` | warn | Events with a handler should have at least one payload parameter |
| `naftiko-aggregate-event-reactive-semantic` | info | Events without `semantics.reactive: true` — suggest adding it |
| `naftiko-webhook-ref-resolves-event` | error | Webhook handler `ref` must resolve to an aggregate event or function |
| `naftiko-aggregate-event-handler-call-valid` | error | Event handler `call` must reference a valid consumed operation |

---

## 7. Phase 3 — Aggregate Skills

### 7.1 Motivation

The current `skill` adapter (see [agent-skills-support.md](agent-skills-support.md)) is a catalog and distribution layer. Skills declare tools derived from sibling `rest`/`mcp` adapters or defined as file instructions. The skill definition lives in the `exposes` layer — alongside protocol details, port configuration, and distribution endpoints.

This creates a separation problem: the domain knowledge a skill encapsulates (classification rules, compliance procedures, reasoning heuristics) logically belongs with the functions and events it supports, not with the adapter that serves it. Moving skill definitions into aggregates achieves:

1. **Co-location** — domain knowledge lives next to domain behavior (functions) and domain events.
2. **Reuse** — an aggregate imported by multiple capabilities carries its skills with it. No need to redefine skill instructions per capability.
3. **Terms integration** — aggregate skills automatically inherit the terms context, ensuring agents reason with the correct domain vocabulary.

### 7.2 Schema Changes

#### `AggregateSkill` (new definition)

```yaml
AggregateSkill:
  type: object
  additionalProperties: false
  required: [name, description]
  properties:
    name:
      $ref: "#/$defs/IdentifierKebab"
      description: >
        Unique skill name within the aggregate (e.g., 'vessel-classification',
        'port-compliance'). Used for ref resolution from adapters.
    description:
      type: string
      description: "What domain knowledge this skill encapsulates."
    instruction:
      $ref: "#/$defs/AggregateSkillInstruction"
      description: >
        The skill's reasoning instructions. Can be inline text or
        a file reference pointing to a SKILL.md and supporting files.
    allowed-functions:
      type: array
      items:
        $ref: "#/$defs/IdentifierKebab"
      description: >
        Names of aggregate functions this skill applies to. When the
        skill is projected as an MCP prompt, only these functions are
        included as available tools. When omitted, the skill applies
        to all functions in the aggregate.
```

#### `AggregateSkillInstruction` (new definition)

```yaml
AggregateSkillInstruction:
  type: object
  additionalProperties: false
  properties:
    inline:
      type: string
      description: >
        Inline reasoning instructions as plain text or Markdown.
        Suitable for short rules and heuristics.
    location:
      type: string
      format: "uri"
      description: >
        URI pointing to a directory containing SKILL.md and supporting
        files. Uses file:/// scheme for local files. Suitable for
        complex domain manuals.
  oneOf:
    - required: [inline]
    - required: [location]
```

#### `Aggregate` extension

```yaml
Aggregate:
  properties:
    # ... existing: label, namespace, functions, terms, events ...
    skills:                                 # NEW
      type: array
      items:
        $ref: "#/$defs/AggregateSkill"
      description: >
        Domain knowledge and reasoning instructions owned by this
        aggregate. Skills guide how functions are used and how events
        are interpreted.
```

### 7.3 Adapter Projection

#### Skill adapter — `ref` to aggregate skills

The `ExposesSkill` adapter gains support for `ref` on individual skills, alongside the existing `from` (derived) and `instruction` (file) tool types:

```yaml
exposes:
  - type: "skill"
    port: 4000
    namespace: "shipyard-skills"
    skills:
      - ref: "shipyard.vessel-classification"   # ← aggregate skill
        # Agent Skills Spec frontmatter (adapter-specific)
        license: "MIT"
        compatibility:
          - "gpt-4o"
          - "claude-sonnet"
```

The `ref` resolves to an `AggregateSkill`. The adapter inherits `name`, `description`, `instruction`, and `allowed-functions` from the aggregate. Adapter-specific frontmatter (license, compatibility, user-invocable) is specified at the adapter level — it is distribution metadata, not domain knowledge.

#### MCP adapter — skills as prompts

An aggregate skill can be projected as an MCP prompt. The engine maps:

| Aggregate Skill | MCP Prompt |
|----------------|------------|
| `name` | `prompt.name` |
| `description` | `prompt.description` |
| `instruction.inline` | `prompt.template` (text content) |
| `instruction.location` | `prompt.location` (file reference) |
| `allowed-functions` | Prompt arguments reference the tool names |

```yaml
exposes:
  - type: "mcp"
    port: 3000
    namespace: "shipyard-mcp"
    tools:
      - ref: "shipyard.get-ship"
      - ref: "shipyard.list-ships"
    prompts:
      - ref: "shipyard.vessel-classification"   # ← aggregate skill as prompt
```

### 7.4 Terms Integration

When a skill is projected (as a Skill adapter entry or an MCP prompt), the engine automatically appends the aggregate's terms to the skill's instruction content:

```
# Projected MCP prompt for vessel-classification
When evaluating compliance, check flag-state conventions first,
then verify PSC inspection history from the last 36 months.

Domain vocabulary:
- ship: a maritime vessel identified by its IMO number
- berth: a designated docking location within a port
- flag-state: the country under whose laws the vessel is registered
```

This ensures agents reasoning with the skill use the same domain vocabulary as the functions they invoke.

### 7.5 Use Cases

| # | Use Case | Concrete Example | Value |
|---|----------|-----------------|-------|
| 1 | **Domain-aware agents** | MCP tool invocation includes vessel-classification skill as system context → agent reasons with authoritative domain rules | Grounded reasoning, reduced hallucination |
| 2 | **Skill reuse via aggregate import** | Three capabilities import the `shipyard` aggregate → all three get `vessel-classification` and `port-compliance` skills without redefinition | Single source of truth for domain knowledge |
| 3 | **Function-skill binding** | `vessel-classification` skill has `allowed-functions: ["get-ship", "list-ships"]` → agent knows exactly which tools pair with this knowledge | Typed reasoning scope |
| 4 | **Inline + file flexibility** | Simple rule: `inline: "Always use IMO number, never MMSI."` Complex manual: `location: "file:///skills/port-compliance/"` | Right-sized instructions |
| 5 | **Term-enriched prompts** | Skill projected as MCP prompt includes domain terms → agent uses `ship` not `vessel`, `berth` not `dock` | Consistent terminology |
| 6 | **Skill marketplace** | Aggregate skills packaged in standalone file → published to marketplace with full metadata from the skill adapter | Domain knowledge as a distributable asset |

### 7.6 Validation Rules

| Rule ID | Severity | Description |
|---------|----------|-------------|
| `naftiko-aggregate-skill-name-unique` | error | No duplicate skill names within the same aggregate |
| `naftiko-aggregate-skill-allowed-functions-exist` | error | Every function in `allowed-functions` must exist in the same aggregate |
| `naftiko-aggregate-skill-instruction-required` | warn | Skills without instruction (inline or location) may lack actionable content |
| `naftiko-skill-ref-resolves` | error | Skill adapter `ref` must resolve to an existing aggregate skill |
| `naftiko-mcp-prompt-ref-resolves` | error | MCP prompt `ref` must resolve to an existing aggregate skill |

---

## 8. Phase 4 — Full DDD Aggregate Composition

### 8.1 Motivation

With all four dimensions in place, the aggregate is a self-contained bounded context. Phase 4 delivers the composition patterns that make this modular:

- **Standalone aggregate files** carry functions, events, skills, and terms — importable into any capability.
- **Cross-aggregate references** let events in one aggregate trigger functions in another.
- **Aggregate-level binds** scope secrets and configuration to the bounded context.
- **Full validation** ensures internal consistency across all four dimensions.

### 8.2 Schema Changes

#### Standalone aggregate file (extends [standalone-exposes-aggregates.md](standalone-exposes-aggregates.md))

The standalone aggregates file format already supports `functions`. Phase 4 extends it to carry all four dimensions:

```yaml
# shipyard-domain.yml — full four-dimensional standalone aggregate
naftiko: "1.0.0-alpha2"

info:
  label: "Shipyard Domain"
  description: "Complete domain model for maritime vessel operations."

consumes:
  - location: "./maritime-api.yml"
    import: "maritime-api"

aggregates:
  - label: "Shipyard Operations"
    namespace: "shipyard"

    terms:
      - term: "ship"
        aliases: ["vessel"]
        description: "A maritime vessel identified by its IMO number."
      - term: "berth"
        description: "A designated docking location within a port."
      - term: "flag-state"
        description: "The country under whose laws the vessel is registered."

    functions:
      - name: "get-ship"
        description: "Retrieve details for a specific ship by IMO number."
        semantics:
          safe: true
          idempotent: true
        inputParameters:
          - name: "imo-number"
            type: "string"
            description: "IMO identification number of the ship."
        call: "maritime-api.get-vessel"
        with:
          imo: "imo-number"
        outputParameters:
          - type: "object"
            mapping: "$"
            properties:
              name: { type: "string", mapping: "$.name" }
              flag-state: { type: "string", mapping: "$.flag" }
              tonnage: { type: "number", mapping: "$.gross_tonnage" }

      - name: "list-ships"
        description: "List all ships in the registry."
        semantics:
          safe: true
        call: "maritime-api.list-vessels"
        outputParameters:
          - type: "array"
            mapping: "$.vessels"
            items:
              type: "object"
              properties:
                name: { type: "string", mapping: "$.name" }
                imo-number: { type: "string", mapping: "$.imo" }

    events:
      - name: "ship-arrived"
        description: "Received when a vessel arrives at a monitored port."
        semantics:
          reactive: true
          idempotent: true
        payload:
          - name: "imo-number"
            type: "string"
            description: "IMO number of the arriving vessel."
          - name: "port-code"
            type: "string"
            description: "UN/LOCODE of the arrival port."
        handler:
          steps:
            - type: "call"
              name: "fetch-vessel"
              call: "maritime-api.get-vessel"
              with:
                imo: "imo-number"
            - type: "call"
              name: "notify"
              call: "notification-api.send"
              with:
                message: "{{fetch-vessel.name}} arrived at {{port-code}}"

    skills:
      - name: "vessel-classification"
        description: "Rules for classifying vessels by type, tonnage, and flag state."
        instruction:
          inline: |
            Classify vessels using the following categories:
            - Cargo: gross tonnage > 500, not passenger-certified
            - Tanker: carries liquid bulk cargo
            - Passenger: certified for > 12 passengers
            When in doubt, check the flag-state registry for the official classification.
        allowed-functions:
          - "get-ship"
          - "list-ships"
```

#### Aggregate-level binds

```yaml
Aggregate:
  properties:
    # ... existing: label, namespace, functions, terms, events, skills ...
    binds:                                  # NEW
      type: array
      items:
        $ref: "#/$defs/BindSpec"
      description: >
        Secrets and configuration scoped to this aggregate.
        Resolved before the aggregate's functions, events, and skills.
        When the aggregate is imported into a capability, these binds
        are merged with the capability's root binds.
```

#### Cross-aggregate event references

A function in one aggregate can declare that it is triggered by an event in another aggregate. This is modeled via a new optional `triggeredBy` property on `AggregateFunction`:

```yaml
# In the "invoicing" aggregate
functions:
  - name: "create-invoice"
    description: "Generate an invoice when an order is placed."
    triggeredBy: "orders.order-placed"      # ← cross-aggregate event reference
    inputParameters:
      - name: "order-id"
        type: "string"
    call: "billing-api.create-invoice"
    with:
      order-id: "order-id"
```

This is metadata — it documents the relationship between aggregates. The actual triggering mechanism is the webhook adapter routing an event to the function. `triggeredBy` enables:
- Dependency graph visualization across aggregates
- Spectral validation that the referenced event exists
- Documentation generation showing event-function relationships

### 8.3 Use Cases

| # | Use Case | Concrete Example | Value |
|---|----------|-----------------|-------|
| 1 | **Standalone domain package** | `shipyard-domain.yml` with functions, events, skills, terms → imported by `port-mcp.yml` and `port-rest.yml` capabilities | One domain, many projections |
| 2 | **Cross-aggregate collaboration** | `invoicing.create-invoice` triggered by `orders.order-placed` event → both aggregates in the same capability, connected via webhook routing | Bounded context interaction without tight coupling |
| 3 | **Aggregate-scoped secrets** | Shipyard aggregate has its own `binds` for maritime API keys → capability imports it without knowing the keys | Clean secret separation per bounded context |
| 4 | **Dependency graph** | `triggeredBy` references form a DAG of aggregate relationships → tooling visualizes the event flow | Architecture documentation from the spec |
| 5 | **Full validation suite** | Spectral validates: skills reference existing functions, events have handlers, terms cover parameter names, cross-aggregate refs resolve | Comprehensive quality gate |

### 8.4 Validation Rules

| Rule ID | Severity | Description |
|---------|----------|-------------|
| `naftiko-aggregate-triggered-by-valid` | error | `triggeredBy` must reference an existing event in another aggregate (format: `namespace.event-name`) |
| `naftiko-aggregate-binds-namespace-unique` | error | Aggregate bind namespaces must not conflict with capability-level binds |
| `naftiko-aggregate-internal-consistency` | warn | Every function in a skill's `allowed-functions` should use terms in its parameter names |
| `naftiko-aggregate-event-handler-coverage` | info | Events with payload but no handler — may be intentional (pure contract), but worth reviewing |

---

## 9. Implementation Examples

### 9.1 Minimal — terms only (Phase 1)

Extends the existing [forecast-aggregate.yml](../schemas/examples/forecast-aggregate.yml) with terms:

```yaml
naftiko: "1.0.0-alpha2"
info:
  label: "Weather Forecast (Aggregate + Terms)"
  description: >
    Demonstrates aggregate terms with domain term definitions.
    The engine enriches MCP tool descriptions with domain terms.

capability:
  aggregates:
    - label: "Forecast"
      namespace: "forecast"
      terms:
        - term: "forecast"
          description: "Predicted weather conditions for a given location and time period."
        - term: "location"
          aliases: ["city", "coordinates"]
          description: "A geographic reference — city name or latitude/longitude pair."
        - term: "condition"
          description: "Qualitative weather state (e.g., sunny, cloudy, rain, snow)."
        - term: "temperature"
          description: "Air temperature in degrees Celsius."
      functions:
        - name: "get-forecast"
          description: "Fetch current weather forecast for a location."
          semantics:
            safe: true
            idempotent: true
          inputParameters:
            - name: "location"
              type: "string"
              description: "City name or coordinates."
          call: "weather-api.get-forecast"
          with:
            location: "location"
          outputParameters:
            - type: "object"
              mapping: "$.forecast"
              properties:
                temperature:
                  type: "number"
                  mapping: "$.temperature"
                condition:
                  type: "string"
                  mapping: "$.condition"

  exposes:
    - type: "mcp"
      address: "localhost"
      port: 3000
      namespace: "forecast-mcp"
      tools:
        - ref: "forecast.get-forecast"
        # MCP tool description will be enriched with domain terms

  consumes:
    - type: "http"
      namespace: "weather-api"
      description: "External weather API"
      baseUri: "https://api.weather.example/v1"
      resources:
        - path: "forecast"
          name: "forecast"
          operations:
            - method: "GET"
              name: "get-forecast"
```

### 9.2 Events + functions via three adapters (Phase 2)

```yaml
naftiko: "1.0.0-alpha2"
info:
  label: "Order Management (Events + Functions)"
  description: >
    Demonstrates aggregate events alongside functions.
    The order-placed event is handled by a webhook adapter and
    the create-order function is exposed via MCP and REST.
  tags:
    - Orders
    - Aggregate
    - Webhook

binds:
  - namespace: "secrets"
    keys:
      STRIPE_WEBHOOK_SECRET: "STRIPE_WEBHOOK_SECRET"
      SLACK_TOKEN: "SLACK_BOT_TOKEN"
      ORDERS_API_KEY: "ORDERS_API_KEY"

capability:
  aggregates:
    - label: "Order Lifecycle"
      namespace: "orders"
      terms:
        - term: "order"
          description: "A customer purchase request with line items and a total amount."
        - term: "payment-intent"
          aliases: ["payment"]
          description: "A Stripe payment intent representing a charge attempt."
      functions:
        - name: "create-order"
          description: "Create a new order in the system."
          semantics:
            idempotent: false
          inputParameters:
            - name: "customer-id"
              type: "string"
              description: "Unique customer identifier."
            - name: "items"
              type: "array"
              description: "Line items for the order."
          call: "orders-api.create"
          with:
            customer: "customer-id"
            items: "items"
          outputParameters:
            - type: "object"
              mapping: "$"
              properties:
                order-id: { type: "string", mapping: "$.id" }
                status: { type: "string", mapping: "$.status" }
      events:
        - name: "payment-received"
          description: "Received from Stripe when a payment intent succeeds."
          semantics:
            reactive: true
            idempotent: true
          payload:
            - name: "payment-intent-id"
              type: "string"
              description: "Stripe payment intent ID."
            - name: "amount"
              type: "number"
              description: "Payment amount in cents."
            - name: "customer-id"
              type: "string"
              description: "Stripe customer ID."
          handler:
            steps:
              - type: "call"
                name: "fulfill"
                call: "orders-api.mark-paid"
                with:
                  payment-id: "payment-intent-id"
                  customer: "customer-id"
              - type: "call"
                name: "notify"
                call: "slack.post-message"
                with:
                  channel: "#orders"
                  text: "Payment of {{amount}} received for customer {{customer-id}}"
            outputParameters:
              - name: "fulfilled"
                type: "boolean"
            mappings:
              - targetName: "fulfilled"
                value: "$.fulfill.success"

  exposes:
    # Webhook — receives Stripe payment events
    - type: "webhook"
      port: 9000
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
          ref: "orders.payment-received"
          inputParameters:
            - name: "payment-intent-id"
              type: "string"
              in: "payload"
              expression: "$.data.object.payment_intent"
            - name: "amount"
              type: "number"
              in: "payload"
              expression: "$.data.object.amount_paid"
            - name: "customer-id"
              type: "string"
              in: "payload"
              expression: "$.data.object.customer"

    # MCP — on-demand order operations
    - type: "mcp"
      address: "localhost"
      port: 3000
      namespace: "orders-mcp"
      tools:
        - ref: "orders.create-order"

    # REST — on-demand order operations
    - type: "rest"
      address: "localhost"
      port: 3001
      namespace: "orders-rest"
      resources:
        - path: "/orders"
          name: "orders"
          operations:
            - ref: "orders.create-order"
              method: "POST"

  consumes:
    - type: "http"
      namespace: "orders-api"
      description: "Internal order management API."
      baseUri: "https://api.internal.example/orders"
      authentication:
        type: "apikey"
        name: "X-API-Key"
        in: "header"
        value: "{{ORDERS_API_KEY}}"
      resources:
        - name: "orders"
          path: "/"
          operations:
            - name: "create"
              method: "POST"
              body:
                type: "json"
            - name: "mark-paid"
              method: "PATCH"
              path: "/{{paymentId}}/pay"
              body:
                type: "json"

    - type: "http"
      namespace: "slack"
      description: "Slack Web API."
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

### 9.3 Full four-dimensional aggregate (Phase 4)

```yaml
naftiko: "1.0.0-alpha2"
info:
  label: "Shipyard Operations (Full DDD Aggregate)"
  description: >
    Demonstrates a complete four-dimensional aggregate: functions,
    events, skills, and terms — all projected through REST, MCP,
    Skill, and Webhook adapters.
  tags:
    - Maritime
    - DDD
    - Aggregate

binds:
  - namespace: "secrets"
    keys:
      MARITIME_API_KEY: "MARITIME_API_KEY"
      WEBHOOK_SECRET: "WEBHOOK_SECRET"
      SLACK_TOKEN: "SLACK_BOT_TOKEN"

capability:
  aggregates:
    - label: "Shipyard Operations"
      namespace: "shipyard"

      terms:
        - term: "ship"
          aliases: ["vessel"]
          description: "A maritime vessel identified by its IMO number."
        - term: "imo-number"
          aliases: ["imo"]
          description: "International Maritime Organization identification number — unique per vessel."
        - term: "berth"
          description: "A designated docking location within a port."
        - term: "flag-state"
          description: "The country under whose laws the vessel is registered and inspected."
        - term: "port-code"
          aliases: ["locode"]
          description: "UN/LOCODE identifying a port (e.g., FRMRS for Marseille)."

      functions:
        - name: "get-ship"
          description: "Retrieve details for a specific ship by IMO number."
          semantics:
            safe: true
            idempotent: true
          inputParameters:
            - name: "imo-number"
              type: "string"
              description: "IMO number of the vessel."
          call: "maritime-api.get-vessel"
          with:
            imo: "imo-number"
          outputParameters:
            - type: "object"
              mapping: "$"
              properties:
                name: { type: "string", mapping: "$.name" }
                flag-state: { type: "string", mapping: "$.flag" }
                tonnage: { type: "number", mapping: "$.gross_tonnage" }

        - name: "list-ships"
          description: "List all ships in the registry."
          semantics:
            safe: true
            idempotent: true
          call: "maritime-api.list-vessels"
          outputParameters:
            - type: "array"
              mapping: "$.vessels"
              items:
                type: "object"
                properties:
                  name: { type: "string", mapping: "$.name" }
                  imo-number: { type: "string", mapping: "$.imo" }

      events:
        - name: "ship-arrived"
          description: "Received when a vessel arrives at a monitored port."
          semantics:
            reactive: true
            idempotent: true
          payload:
            - name: "imo-number"
              type: "string"
              description: "IMO number of the arriving vessel."
            - name: "port-code"
              type: "string"
              description: "UN/LOCODE of the arrival port."
            - name: "arrival-time"
              type: "string"
              description: "ISO 8601 timestamp of the vessel's arrival."
          handler:
            steps:
              - type: "call"
                name: "fetch-vessel"
                call: "maritime-api.get-vessel"
                with:
                  imo: "imo-number"
              - type: "call"
                name: "notify"
                call: "slack.post-message"
                with:
                  channel: "#port-arrivals"
                  text: "{{fetch-vessel.name}} ({{flag-state}}) arrived at {{port-code}} at {{arrival-time}}"
            outputParameters:
              - name: "vessel-name"
                type: "string"
              - name: "notified"
                type: "boolean"
            mappings:
              - targetName: "vessel-name"
                value: "$.fetch-vessel.name"
              - targetName: "notified"
                value: "$.notify.ok"

        - name: "ship-departed"
          description: "Received when a vessel departs from a monitored port."
          semantics:
            reactive: true
          payload:
            - name: "imo-number"
              type: "string"
            - name: "port-code"
              type: "string"

      skills:
        - name: "vessel-classification"
          description: "Rules for classifying vessels by type, tonnage, and flag state."
          instruction:
            inline: |
              Classify vessels using the following categories:
              - Cargo: gross tonnage > 500, not passenger-certified
              - Tanker: carries liquid bulk cargo (oil, chemicals, LNG)
              - Passenger: certified for more than 12 passengers
              - Fishing: registered for commercial fishing operations
              When tonnage is ambiguous, check the flag-state registry
              for the official classification. Always use the IMO number
              as the primary identifier, never the MMSI.
          allowed-functions:
            - "get-ship"
            - "list-ships"

        - name: "port-compliance"
          description: "Knowledge of port-state control inspection procedures."
          instruction:
            location: "file:///skills/port-compliance/"
          allowed-functions:
            - "get-ship"

  exposes:
    # MCP adapter — tools from functions, prompts from skills
    - type: "mcp"
      address: "localhost"
      port: 3000
      namespace: "shipyard-mcp"
      description: "MCP server for maritime vessel operations."
      tools:
        - ref: "shipyard.get-ship"
        - ref: "shipyard.list-ships"
      prompts:
        - ref: "shipyard.vessel-classification"
        - ref: "shipyard.port-compliance"

    # REST adapter — operations from functions
    - type: "rest"
      address: "localhost"
      port: 3001
      namespace: "shipyard-rest"
      resources:
        - path: "/ships"
          name: "ships"
          operations:
            - ref: "shipyard.list-ships"
              method: "GET"
        - path: "/ships/{imo-number}"
          name: "ship"
          operations:
            - ref: "shipyard.get-ship"
              method: "GET"
              inputParameters:
                - name: "imo-number"
                  in: "path"
                  type: "string"

    # Webhook adapter — handlers from events
    - type: "webhook"
      port: 9000
      namespace: "port-hooks"
      path: "/webhooks/port-authority"
      verification:
        algorithm: "hmac-sha256"
        header: "X-Signature"
        secret: "{{WEBHOOK_SECRET}}"
      routing:
        source: "payload"
        expression: "$.event_type"
      handlers:
        - event: "vessel.arrived"
          ref: "shipyard.ship-arrived"
          inputParameters:
            - name: "imo-number"
              type: "string"
              in: "payload"
              expression: "$.vessel.imo"
            - name: "port-code"
              type: "string"
              in: "payload"
              expression: "$.port.locode"
            - name: "arrival-time"
              type: "string"
              in: "payload"
              expression: "$.timestamp"
        - event: "vessel.departed"
          ref: "shipyard.ship-departed"
          inputParameters:
            - name: "imo-number"
              type: "string"
              in: "payload"
              expression: "$.vessel.imo"
            - name: "port-code"
              type: "string"
              in: "payload"
              expression: "$.port.locode"

    # Skill adapter — catalog from skills
    - type: "skill"
      port: 4000
      namespace: "shipyard-skills"
      skills:
        - ref: "shipyard.vessel-classification"
          license: "MIT"
          compatibility:
            - "gpt-4o"
            - "claude-sonnet"
        - ref: "shipyard.port-compliance"

  consumes:
    - type: "http"
      namespace: "maritime-api"
      description: "Maritime vessel registry API."
      baseUri: "https://api.maritime.example/v2"
      authentication:
        type: "apikey"
        name: "X-API-Key"
        in: "header"
        value: "{{MARITIME_API_KEY}}"
      resources:
        - name: "vessels"
          path: "/vessels"
          operations:
            - name: "list-vessels"
              method: "GET"
            - name: "get-vessel"
              method: "GET"
              path: "/{{imo}}"

    - type: "http"
      namespace: "slack"
      description: "Slack Web API for notifications."
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

---

## 10. Security Considerations

### 10.1 Terms Content (Phase 1)

Terms and descriptions are rendered in MCP tool descriptions and OAS documentation. Security considerations:

- **XSS in descriptions**: Existing `naftiko-no-script-tags-in-markdown` rule applies to term descriptions.
- **Sensitive terms**: Terms should not contain secrets, API keys, or internal infrastructure details. This is a documentation surface.
- **Description length**: The `maxLength: 500` constraint on descriptions prevents excessive content injection.

### 10.2 Event Handling (Phase 2)

Event security inherits from the [Webhook Server Adapter](webhook-server-adapter.md) blueprint:

- **HMAC verification**: Non-negotiable for production. The `verification` block on the webhook adapter validates payload integrity.
- **Input validation**: Event `payload` fields are type-checked. The handler never sees the raw payload.
- **Replay protection**: Future `maxAge` extension on webhook verification.
- **Cross-aggregate events**: `triggeredBy` is metadata only — no cross-aggregate execution without explicit webhook/adapter wiring.

### 10.3 Skill Instructions (Phase 3)

Skill instructions are reasoning content served to AI agents:

- **Instruction injection**: Inline instructions are author-controlled YAML strings. File instructions (`location`) are loaded from the file system at runtime — the engine validates that the path stays within allowed directories.
- **Path traversal**: The `location` URI must use `file:///` scheme. Resolved paths are validated against the capability's root directory — identical to the existing skill adapter security model.
- **Agent prompt injection**: Skill instructions are served as-is to agents. Authors should not include instructions that override agent safety controls. This is an authoring concern, not a runtime enforcement.

### 10.4 Standalone Aggregates with Binds (Phase 4)

- **Secret scoping**: Aggregate-level `binds` are resolved in the aggregate's scope. They cannot override capability-level binds or access binds from other aggregates.
- **Transitive import secrets**: When a standalone aggregate file uses transitive consumes imports, the binds in the standalone file are resolved independently — they cannot leak into the importing capability's scope.

---

## 11. Validation Rules

### All Phases — Spectral Rules

| Phase | Rule ID | Severity | Description |
|-------|---------|----------|-------------|
| 1 | `naftiko-aggregate-term-unique` | error | No duplicate terms within the same aggregate |
| 1 | `naftiko-aggregate-term-prefer-canonical` | warn | Parameter names should use canonical terms, not aliases |
| 1 | `naftiko-aggregate-term-no-cross-conflict` | warn | Same term in multiple aggregates with different descriptions |
| 1 | `naftiko-aggregate-term-description-recommended` | info | Terms without description should have one |
| 2 | `naftiko-aggregate-event-name-unique` | error | No duplicate event names within the same aggregate |
| 2 | `naftiko-aggregate-event-payload-defined` | warn | Events with handler should have payload parameters |
| 2 | `naftiko-aggregate-event-reactive-semantic` | info | Events without `semantics.reactive: true` — suggest adding |
| 2 | `naftiko-webhook-ref-resolves-event` | error | Webhook handler `ref` must resolve to aggregate event or function |
| 2 | `naftiko-aggregate-event-handler-call-valid` | error | Event handler `call` must reference valid consumed operation |
| 3 | `naftiko-aggregate-skill-name-unique` | error | No duplicate skill names within the same aggregate |
| 3 | `naftiko-aggregate-skill-allowed-functions-exist` | error | `allowed-functions` must reference functions in the same aggregate |
| 3 | `naftiko-aggregate-skill-instruction-required` | warn | Skills without instruction may lack actionable content |
| 3 | `naftiko-skill-ref-resolves` | error | Skill adapter `ref` must resolve to aggregate skill |
| 3 | `naftiko-mcp-prompt-ref-resolves` | error | MCP prompt `ref` must resolve to aggregate skill |
| 4 | `naftiko-aggregate-triggered-by-valid` | error | `triggeredBy` must reference event in another aggregate |
| 4 | `naftiko-aggregate-binds-namespace-unique` | error | Aggregate bind namespaces must not conflict with capability binds |
| 4 | `naftiko-aggregate-internal-consistency` | warn | Skills' `allowed-functions` should use terms in parameters |
| 4 | `naftiko-aggregate-event-handler-coverage` | info | Events with payload but no handler — review if intentional |

### JSON Schema Constraints

| Phase | Change | Impact |
|-------|--------|--------|
| 1 | `AggregateTerm` new definition | Additive |
| 1 | `Aggregate.terms` new optional property | Backward-compatible |
| 2 | `AggregateEvent`, `AggregateEventHandler` new definitions | Additive |
| 2 | `Aggregate.events` new optional property | Backward-compatible |
| 2 | `Semantics.reactive` new optional boolean | Backward-compatible |
| 3 | `AggregateSkill`, `AggregateSkillInstruction` new definitions | Additive |
| 3 | `Aggregate.skills` new optional property | Backward-compatible |
| 3 | `ExposedSkill` gains `ref` support | Backward-compatible (alongside existing `from`/`instruction`) |
| 3 | `McpPrompt` gains `ref` support | Backward-compatible (alongside existing `template`/`location`) |
| 4 | `Aggregate.binds` new optional property | Backward-compatible |
| 4 | `AggregateFunction.triggeredBy` new optional property | Backward-compatible |

---

## 12. Design Decisions & Rationale

### D1: Terms are aggregate-scoped, not capability-scoped

**Decision**: The `terms` live on `Aggregate`, not on `Capability` or `Info`.

**Why**: In DDD, the ubiquitous language belongs to the bounded context. Two aggregates in the same capability may legitimately define the same term differently — "order" in a sales context vs. "order" in a fulfillment context. Scoping to the aggregate preserves this distinction. Scoping to the capability would force a single definition.

**Tradeoff**: Capabilities with a single aggregate have a slightly deeper nesting path for terms. Acceptable — most multi-aggregate capabilities will benefit from the scoping.

### D2: Events are aggregate-owned, not adapter-owned

**Decision**: Domain events are defined on the `Aggregate` with an optional inline handler. The webhook adapter references them via `ref`.

**Why**: The domain event ("payment was received") is protocol-independent. Whether it arrives via a Stripe webhook, a Kafka message, or an A2A notification is an adapter concern. Defining events in the aggregate ensures the domain model is complete regardless of which adapters are wired.

**Tradeoff**: The webhook adapter's `inputParameters` (with `in: payload`, `expression`) are adapter-specific extraction logic that doesn't live in the aggregate event. This means the event's `payload` defines the *domain shape* (what fields matter) while the adapter defines the *extraction logic* (where to find them in the raw HTTP body). This is intentional — it separates domain modeling from protocol wiring.

### D3: Aggregate events can have handlers (not just contracts)

**Decision**: `AggregateEvent` has an optional `handler` block with `call`/`steps`/`outputParameters`.

**Why**: Many events have a natural, domain-defined reaction. "Payment received → mark order as paid" is domain logic, not adapter logic. Putting the handler in the aggregate means the reaction is defined once and reused across adapters (webhook, future event bus, manual trigger via REST).

**Tradeoff**: Events without handlers are valid — they serve as typed contracts. The `handler` being optional means both patterns are supported: pure contracts for consumed events and self-handling for domain events.

### D4: Skills move to aggregates, skill adapter becomes projection

**Decision**: Domain knowledge (instructions, allowed functions) moves to `Aggregate.skills`. The `ExposesSkill` adapter gains `ref` support to reference aggregate skills and adds distribution metadata (frontmatter, license, endpoints).

**Why**: Skills describe *how to reason about the domain*. That knowledge belongs with the functions and events it supports, not with the adapter that distributes it. This mirrors how `mcp` and `rest` adapters project aggregate functions — the definition is in the aggregate, the protocol details are in the adapter.

**Tradeoff**: Existing `ExposesSkill` definitions with inline `tools[].instruction` remain valid. The migration path is: inline → extract to aggregate skill → reference via `ref`. This is not a breaking change — both patterns coexist.

### D5: Term enrichment is automatic, opt-out

**Decision**: The engine appends terms to MCP tool descriptions automatically. No configuration needed.

**Why**: The primary value of terms is reducing agent hallucination. If enrichment requires opt-in, most users won't enable it, defeating the purpose. Automatic enrichment with opt-out (future `enrichDescription: false`) maximizes impact while preserving control.

**Tradeoff**: Tool descriptions become longer. For aggregates with many terms, this could increase token count. Mitigation: the engine only appends terms that appear in the function's input/output parameter names — not the entire terms array.

### D6: `triggeredBy` is metadata, not execution wiring

**Decision**: `AggregateFunction.triggeredBy` documents which event triggers the function. It does not configure automatic execution.

**Why**: Automatic cross-aggregate execution would require an internal event bus, delivery guarantees, and failure handling — far beyond the scope of this blueprint. The webhook adapter provides the actual execution path. `triggeredBy` enables tooling (dependency graphs, documentation, validation) without runtime complexity.

**Tradeoff**: Users must still wire the webhook adapter to connect events to functions. The `triggeredBy` metadata makes the intent explicit but does not remove the wiring step.

### D7: Phases are independently valuable

**Decision**: Each phase delivers standalone value. Phase 2 (events) does not require Phase 1 (terms). Phase 3 (skills) benefits from Phase 1 but does not require it.

**Why**: Tight phase dependencies would block delivery. Term enrichment of skill context (Phase 3 + Phase 1) is a bonus, not a prerequisite. Each phase must be shippable and useful on its own.

**Tradeoff**: Some cross-phase features (term-enriched skills, event-triggered functions with skill context) only work when multiple phases are complete. The projection matrix in §2 makes these relationships explicit.

---

## 13. Implementation Roadmap

### Phase 1: Aggregate Terms — Alpha 3 (May)

| Step | Scope | Effort |
|------|-------|--------|
| Schema: `AggregateTerm` definition | JSON Schema | Small |
| Schema: `Aggregate.terms` optional property | JSON Schema | Trivial |
| Java: `AggregateTermSpec` POJO | Spec layer | Small |
| Java: `AggregateSpec` extended with `terms` field | Spec layer | Trivial |
| Java: Term-aware description enrichment in `McpServerAdapter` | Engine | Medium |
| Java: Term-aware `x-terms` extension in OAS export | Engine | Small |
| Spectral: `naftiko-aggregate-term-*` rules (4 rules) | Linting | Small |
| Tests: Term enrichment, linting, cross-aggregate conflict | Quality | Medium |
| Example: `forecast-aggregate-terms.yml` | Documentation | Small |

### Phase 2: Aggregate Events — Alpha 3 (May)

| Step | Scope | Effort |
|------|-------|--------|
| Schema: `AggregateEvent`, `AggregateEventHandler` definitions | JSON Schema | Small |
| Schema: `Aggregate.events` optional property | JSON Schema | Trivial |
| Schema: `Semantics.reactive` optional boolean | JSON Schema | Trivial |
| Java: `AggregateEventSpec`, `AggregateEventHandlerSpec` POJOs | Spec layer | Small |
| Java: `AggregateRefResolver` extended to resolve events | Engine | Medium |
| Java: Webhook adapter `ref` resolution to aggregate events | Engine | Medium |
| Spectral: `naftiko-aggregate-event-*` rules (5 rules) | Linting | Small |
| Tests: Event resolution, webhook handler ref, handler execution | Quality | Medium |
| Example: `order-management-events.yml` | Documentation | Small |

**Note**: This phase coordinates with the [Webhook Server Adapter](webhook-server-adapter.md) implementation. The schema changes here extend the aggregate model; the webhook adapter implements the protocol layer.

### Phase 3: Aggregate Skills — Beta (June)

| Step | Scope | Effort |
|------|-------|--------|
| Schema: `AggregateSkill`, `AggregateSkillInstruction` definitions | JSON Schema | Small |
| Schema: `Aggregate.skills` optional property | JSON Schema | Trivial |
| Schema: `ExposedSkill.ref` support | JSON Schema | Small |
| Schema: `McpPrompt.ref` support | JSON Schema | Small |
| Java: `AggregateSkillSpec`, `AggregateSkillInstructionSpec` POJOs | Spec layer | Small |
| Java: `AggregateRefResolver` extended to resolve skills | Engine | Medium |
| Java: Skill adapter `ref` resolution to aggregate skills | Engine | Medium |
| Java: MCP prompt `ref` resolution to aggregate skills | Engine | Medium |
| Java: Term injection into skill/prompt content | Engine | Small |
| Spectral: `naftiko-aggregate-skill-*` rules (5 rules) | Linting | Small |
| Tests: Skill resolution, prompt projection, term injection | Quality | Medium |
| Example: `shipyard-skills-aggregate.yml` | Documentation | Small |

### Phase 4: Full DDD Composition — GA (September)

| Step | Scope | Effort |
|------|-------|--------|
| Schema: `Aggregate.binds` optional property | JSON Schema | Small |
| Schema: `AggregateFunction.triggeredBy` optional property | JSON Schema | Trivial |
| Schema: Standalone file format validation (all four dimensions) | JSON Schema | Medium |
| Java: Aggregate-level bind resolution | Engine | Medium |
| Java: `triggeredBy` validation and documentation generation | Engine | Small |
| Java: Full standalone aggregate import with all dimensions | Engine | Medium |
| Spectral: Phase 4 rules (4 rules) | Linting | Small |
| Tests: Full aggregate import, cross-aggregate refs, bind scoping | Quality | Large |
| Example: `shipyard-full-aggregate.yml` (standalone file) | Documentation | Medium |
| Wiki: Update Specification.md with four-dimensional aggregate docs | Documentation | Medium |

---

## 14. Backward Compatibility

**Fully backward-compatible across all phases.** Every change is additive:

- **Phase 1**: `terms` is a new optional array on `Aggregate`. Existing aggregates without it are valid.
- **Phase 2**: `events` is a new optional array on `Aggregate`. `Semantics.reactive` is a new optional boolean. Existing aggregates and semantics are unaffected.
- **Phase 3**: `skills` is a new optional array on `Aggregate`. `ExposedSkill.ref` and `McpPrompt.ref` are new optional properties alongside existing `from`/`instruction`/`template`/`location`. Existing skill and prompt definitions continue to work.
- **Phase 4**: `Aggregate.binds` and `AggregateFunction.triggeredBy` are new optional properties. Standalone file format gains support for all four dimensions — existing standalone files with only functions are still valid.

No existing validation rules are modified. No existing Java classes are structurally changed — only extended with new optional fields and new subtype registrations. The `AggregateRefResolver` gains resolution paths for events and skills alongside the existing function resolution.

**Migration path**: Existing capabilities evolve incrementally. A team can add terms (Phase 1) without touching their functions. They can add events (Phase 2) without terms. They can move inline skill definitions to aggregate skills (Phase 3) at their own pace. Phase 4 composition features are entirely optional — capabilities with a single aggregate and no cross-aggregate references work exactly as they do today.
