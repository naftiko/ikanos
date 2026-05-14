Spec-Driven Integration (SDI) is a methodology for designing, operating, and governing integrations between systems — derived from the broader principles of [Spec-Driven Development](https://github.com/github/spec-kit/blob/main/spec-driven.md) (SDD) and applied specifically to the integration domain.

Where SDD addresses the gap between intent and implementation in software, SDI addresses the equivalent gap in integration: the distance between *what a system should expose or consume* and *how that contract is actually implemented and maintained*. As organizations accumulate dozens or hundreds of API endpoints — internal services, third-party providers, AI model APIs, agent orchestration surfaces — that gap becomes the primary source of fragility, duplication, and operational cost.

SDI treats this not as a tooling problem, but as a specification problem.

## The Integration Problem

Modern integration is characterized by three compounding challenges:

- **API sprawl**: Systems expose endpoints that are inconsistently shaped, poorly documented, and duplicated across teams and contexts.
- **Context fragmentation**: AI-driven workflows depend on access to the right data, in the right shape, at the right time — but integration layers are often too coarse or too opaque to provide this reliably.
- **Drift between intent and behavior**: Integration logic is encoded in code, configuration, and institutional knowledge rather than in durable, inspectable artifacts. As systems evolve, intent diverges from behavior invisibly.

SDI addresses these challenges by making the specification the primary integration artifact — not a byproduct.

## Core Principles

**Specifications as the Lingua Franca**  
The integration specification is the single source of truth. It defines what is consumed from upstream systems, how inputs and outputs are shaped and composed, and what is re-exposed downstream. Maintaining an integration means evolving its specification. Everything else is derived.

**Executable Specifications**  
A specification is only valuable if it is precise, complete, and unambiguous enough to produce working integrations directly. SDI rejects the idea that specifications are documentation written after the fact. When a specification cannot be executed as-is, the gap is a signal of incompleteness — not an invitation for interpretation.

**Continuous Refinement**  
Integration consistency is not validated at a single gate. Specifications are continuously analyzed for ambiguity, internal contradiction, and gaps — ideally with AI assistance — throughout their lifecycle. This surfaces intent mismatches early, before they reach production.

**Research-Driven Context**  
Specifications are informed by research, not intuition. Decisions about how to shape upstream consumption, how to model domain concepts, and how to expose integration surfaces should be grounded in technical context, operational constraints, and organizational requirements — gathered as part of the specification process itself.

**Bidirectional Feedback**  
Production behavior feeds back into specification evolution. Metrics, incidents, and real-world usage patterns are inputs for refining specifications — not just signals for operational teams. The specification remains a living artifact, not a snapshot.

**Branching for Exploration**  
A single specification can give rise to multiple integration variants, each optimized for a different target: performance, cost, consumer type, or context granularity. Exploration and experimentation happen at the specification level, not in the implementation layer.

## Implementation Approaches

SDI as a methodology is tool-agnostic, but it requires concrete artifacts to be effective: a specification format expressive enough to capture integration intent, and a runtime capable of executing that specification without translation.

**The Capability as a Standard Container of Business Value**  
In SDI, a *capability* is more than an integration artifact — it is a standard container of business value. Each capability encapsulates four dimensions that together define a complete, self-contained unit of organizational worth:

- **Skills** — the accumulated knowledge and expertise encoded in the capability: domain rules, data interpretation patterns, transformation heuristics, and the understanding of how upstream systems actually behave beyond their documented APIs. Skills answer *how the integration reasons* — the intelligence that turns raw data into meaningful, well-shaped output.
- **Functions** — the concrete operations the capability performs: consuming upstream APIs, transforming and composing data, and exposing well-shaped contracts to downstream consumers. Functions answer *what the integration does* — the inputs it accepts, the outputs it produces, and the logic that connects them.
- **Events** — the signals that trigger, inform, or result from the capability's execution: webhooks, state changes, notifications, and feedback loops that tie the capability into the broader operational landscape. Events answer *when the integration acts* and *what happens as a consequence*.
- **Terms** — the shared vocabulary and domain language that give the capability meaning: how concepts are named, what upstream systems it connects to, what authentication and compliance boundaries apply, and what organizational constraints shape its behavior. Terms answer *how this integration is expressed* — making all other dimensions intelligible.

A capability specification is the declarative document that captures all four dimensions in a single place. It is self-contained and human-readable, without requiring knowledge of the underlying implementation. In practice, this means a structured YAML document that describes skills, functions, events and terms — along with the authentication, protocol mappings, and transformation logic that connect them. The spec *is* the integration — not a description of it.

This framing matters because it aligns integration boundaries with business boundaries. When a capability maps to a recognizable unit of business value — not just an API endpoint or a data pipeline — it becomes something the organization can reason about, prioritize, govern, and standardize at the right level of abstraction. Capabilities still deliver *context* as a first-class output: when exposed via the Model Context Protocol (MCP), each capability provides AI agents with structured, well-scoped context — turning integration surfaces into context surfaces. The same capability can be projected through Agent-to-Agent (A2A) for multi-agent collaboration, through REST for traditional consumers, or through classic query protocols like SQL and GraphQL — one specification, many surfaces.

**The Capability Engine**  
For specifications to be executable, a runtime must interpret them directly. Rather than generating code from a spec (which reintroduces drift), the engine reads the specification at runtime and handles all integration concerns: HTTP consumption, data transformation, format conversion, and exposure via REST, MCP, A2A, or classic query interfaces such as SQL and GraphQL.

Derived artifacts — client SDKs, human-readable documentation, validation schemas — are still generated, but always *from* the capability spec, which remains the single source of truth. Combined with GitOps workflows, this ensures that every derived artifact stays traceable to a specific spec version, and drift is caught as a diff rather than discovered in production.

Packaging this engine as a self-contained container means any capability specification can be deployed without build pipelines, language runtimes, or bespoke infrastructure. The engine is the stable layer; the specification is the variable one.

**The Capability Ship**  
A single engine runs a single capability. In practice, organizations group related capabilities into a **s**hared **h**osting **i**ntegration **p**artition — a *ship*. Because every capability conforms to the same specification standard, ships can load, schedule, and run any combination of them without custom integration work — much as a container ship carries diverse cargo in uniform containers. A ship is an independently operated runtime cell that hosts many capability-containers at once, with its own network edge, identity and secrets domain, telemetry, and policy enforcement.

This standardized container format is what makes co-location practical: capabilities from different teams or domains can share a ship's resources while remaining individually versioned, deployable, and replaceable. Grouping capabilities into ships provides a natural isolation boundary: each ship can be scaled, secured, and governed as a unit, without requiring global coordination for every change.

**The Capability Fleet**  
Ships, in turn, are organized into a **f**ederated **l**ayer for **e**nterprise **e**nforcement and **t**raceability — a *fleet* — that manages capabilities across teams, regions, and compliance boundaries. The Fleet coordinates placement, routing, and policy rollout across ships, providing global visibility through a manifest that tracks what is deployed, where it runs, and whether it conforms to organizational policy. Governance is structural: identity-based access controls, automated compliance gates, and immutable audit trails are applied uniformly across the fleet rather than configured per ship or capability.

This matters for SDI because scaling specifications without scaling governance reintroduces the drift and fragility that specifications were designed to eliminate. The Fleet ensures that as the number of capabilities and ships grows, operational consistency grows with it — turning a collection of individual integrations into a managed supply chain of business value.

**The SDI Workflow**  
Together, these layers define a concrete SDI workflow:

1. **Specify** — Author a capability specification that captures the integration intent.
2. **Validate** — Analyze the spec for completeness, consistency, and ambiguity before execution.
3. **Execute** — Run the engine against the specification; no code generation or compilation required.
4. **Refine** — Evolve the specification based on production feedback, consumer requirements, or AI-assisted analysis.

This cycle keeps the specification as the primary artifact throughout — ensuring that what is deployed always reflects documented intent.

## SDI and AI Integration

SDI is particularly well-suited to the demands of AI-driven architectures. Context engineering — shaping, filtering, composing and securing data for AI model consumption — is fundamentally an integration problem. Agent orchestration surfaces, such as MCP servers and tool-based APIs, require exactly the kind of right-sized, semantically coherent integration boundaries that SDI is designed to produce.

When specifications are the primary artifact, AI agents can reason about integrations, propose refinements, and validate consistency — treating the specification as a structured, inspectable contract rather than opaque runtime behavior.

**A Deterministic Foundation for Agents**  
AI agents are powerful reasoning engines, but they are non-deterministic by nature — their outputs vary across invocations, they can hallucinate plausible-but-wrong API contracts, and they have no built-in mechanism to guarantee that a tool call today behaves the same as it did yesterday. This is acceptable for generation tasks; it is unacceptable for integration, where correctness, repeatability, and auditability are non-negotiable.

SDI addresses this by placing a deterministic layer beneath the agent. The capability specification defines — precisely and immutably for a given version — what data is consumed, how it is shaped, and what contract is exposed. The engine executes that specification without interpretation. There is no room for the runtime to invent a field, guess a mapping, or silently change a response shape. What the spec declares is what the agent receives.

This deterministic foundation reduces two categories of risk that compound in agent-driven architectures:

- **Drift risk**: When integration logic lives in code maintained by multiple teams — or worse, in prompts and agent memory — behavior drifts invisibly over time. SDI anchors integration behavior to a versioned, diffable artifact. If the spec has not changed, the integration has not changed. Drift becomes visible as a spec delta, not as a mysterious production incident.
- **Hallucination risk**: Agents tasked with calling APIs or composing tool outputs can fabricate parameter names, assume response structures, or invent error-handling paths that do not exist. When integrations are mediated by a spec-driven engine, the agent operates against a declared contract — not against its own inference of what the API might return. The specification constrains the agent to reality.

Together, these properties enable **operational governance of AI**: organizations can audit what an agent can access, review what transformations are applied, version-control every change, and enforce approval workflows — all through the specification. Governance shifts from monitoring opaque agent behavior after the fact to controlling the integration contracts agents depend on before deployment. The specification becomes the governance surface.