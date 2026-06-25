---
notion_page_id: 3894adce-3d02-8125-9097-df2f13767bbc
notion_last_sync: 2026-06-25T11:31:54Z
---

# Feature Coverage Tracker — Ikanos Engine

**Issue**: [#578](https://github.com/naftiko/ikanos/issues/578) / sub-issue [#590](https://github.com/naftiko/ikanos/issues/590)
**Last updated**: 2026-06-25
**Scope**: Disk + Notion ITD cross-checked (see limitations for the residual caveat).
**Model**: 1 blueprint = 1 feature.

> **Notion mirror — sync contract.** This document is mirrored to a page in the
> Internal Tech Documentation (ITD) Notion database
> (`2e14adce-3d02-8063-8426-eec9aedf3a5e`). The mirror reuses the **sync-pointer formalism** of
> the `blueprint-itd-sync` skillset (the two `notion_page_id` / `notion_last_sync` frontmatter
> fields above) — but **this is not a blueprint**: it is a factual tracker, so none of the
> blueprint-specific machinery applies (no `Status`-maturity mapping, no header-block→property
> contract).
>
> **Expected flow today: VS Code → Notion.** The **source of truth is this file**, edited from VS
> Code alongside the code it measures; the Notion page is the downstream mirror. The convention is:
> after every edit of this file, re-push the ITD page — don't edit the Notion page and expect it to
> flow back. On each successful push, bump `notion_last_sync` (frontmatter, UTC `Z`) to the
> push-completion time; `notion_page_id` is written once on first push and never overwritten. The
> mirror page is [`3894adce…`](https://app.notion.com/p/3894adce3d0281259097df2f13767bbc) in the
> ITD database.
>
> This is a **default direction, not a guaranteed invariant**: full bidirectional reconciliation
> (conflict detection via `notion_last_sync` vs Notion's `last_edited_time`) is **deliberately not
> set up** — it would be speculative for a need that has not materialised. The frontmatter socle is
> intentionally **direction-neutral**, so if a two-way need ever arises (or once the
> `blueprint-itd-sync` skillset is externalised and can adopt this doc), the reconciliation can be
> layered on **without changing these two fields** — nothing here presumes one-way only.

> **This is a living tracking document, not a one-shot audit.** It started as the #578 e2e
> coverage audit, but it is meant to be **kept up to date** as features ship and tests are added.
> It tracks, per feature/blueprint, two things over time:
>
> 1. **Test coverage** — does a shipped feature have an end-user integration test, and at which
>    level (NATIVE / JVM / NONE)?
> 2. **Implementation phases** — which phases declared in the blueprint's *Roadmap* are
>    **actually delivered** today (reconciled against the engine + the GitHub issues), and which
>    remain.
>
> **Maintenance rule**: whenever a PR ships a blueprint phase or adds an end-user test, update the
> matching row in the [feature table](#feature-table) — the `Phases` count and the `Coverage`
> status — and bump **Last updated** above.

> **Test scope**: coverage by **end-user integration tests** only — the capability is *launched*
> and driven from the outside. Whether a feature is unit-tested is **out of scope** here (owned by
> the `ikanos-100-percent-coverage` / JaCoCo quality gate). Unit tests are not counted in this
> tracker.

---

## Coverage model — two non-orthogonal levels

Testing a feature by booting the adapter **in-process on the JVM** (what the tutorials do)
validates the **engine feature**, but it bypasses the **CLI and the packaging** (native image /
Docker / `ikanos serve`). A bug in the CLI, in the native-image configuration, or a feature that
does not "reflect" correctly through GraalVM native compilation would go unnoticed. The two
concerns are chained, not orthogonal: a feature is only *fully* validated end-to-end when it is
exercised through the **path the user actually runs** — the native image.

| Status | Meaning | Implies |
|---|---|---|
| **NATIVE** | Level 2 — the capability is launched via the **native image** (Docker built from `deployment/Dockerfile`, or `ikanos serve` on the GraalVM native binary) and driven from the outside. | Level 1 (the feature is necessarily exercised too). |
| **JVM** *(partial)* | Level 1 only — the adapter is booted **in-process on the JVM** (`io.ikanos.tutorial.*` via `startServerFromSpec()`), against a real backend (Microcks). The feature is validated; the native path is **not**. Easy to upgrade to NATIVE. | — |
| **NONE** | No end-user integration test. In-process tests that merely load a spec without booting an HTTP server, and unit tests, count as NONE here. | — |

**Where the coverage comes from today** — three layers run every night
(`.github/workflows/nightly-quality-gate.yml`, cron 3h):

- **`test-and-analyze`** — starts Microcks (Docker), imports the OpenAPI specs, runs `mvn clean
  verify` (the tutorial suite). The **consumed backend** is real and dockerized, but the **Ikanos
  capability is booted on the JVM**. → **Level 1 (JVM)** for the tutorial-covered features. ✅
- **`integration-tests`** → `validate-tuto-examples.yml` — the tutorial examples. Also JVM. ✅
- **`smoke-tests`** → `publish-cli-bin.yml` — **builds the GraalVM `-Pnative` binary on 4 OS**, then
  the `test-binary` job exercises the **native binary itself**: `create`, `validate`, and
  `serve` (booting the Restlet HTTP connector + TCP-probing the exposed REST port). It carries the
  **reflect-config regression guards for [#581](https://github.com/naftiko/ikanos/issues/581)**
  (alpha4 shipped a native binary missing `org.restlet` and networknt validator classes from
  `reflect-config.json`, crashing `serve`/`validate`). → **Level 2 (NATIVE)**. ✅

So NATIVE coverage **does exist and runs nightly** — but it is **narrow**: a single generic fixture
(`ikanos-cli/src/test/resources/native-regression/serve-http.ikanos.yaml`), one TCP probe on the
REST port, and the CLI verbs `create` / `validate`. It proves the binary boots and the
reflect/resource config is complete; it does **not** validate features one by one at the native
level.

> **Central structural finding**: NATIVE coverage is **mono-fixture / not per-feature**. Only the
> **REST** adapter and the **CLI** (`create`/`validate`/`serve`) are exercised through the native
> image; the **MCP, Skill and Control** adapters and the advanced features (lookup, aggregates,
> auth, script, imports, OTel, tunnel) have **no native-level validation**. Most features sit at
> JVM. Lifting a JVM-partial feature to NATIVE is cheap (the harness and fixtures already exist via
> #581); covering a NONE feature from scratch is the larger effort.

> **Native HTTP-serve was broken until [#594](https://github.com/naftiko/ikanos/issues/594) — and
> the #581 smoke does not catch it.** The nightly native smoke only **TCP-probes** the exposed REST
> port (`/dev/tcp/127.0.0.1/$REST_PORT` in the `test-binary` job — it *deliberately* does not run an
> HTTP client), so it proves the socket *binds* but never that a request is *served*. In fact the
> native binary **bound the port yet hung on every HTTP request** (the JVM served fine) because
> conditional Jetty/Restlet reflection classes were missing from `reflect-config.json`. The fix
> (#594, commit `636a00e2` on the **origin-only** branch `fix/native-jetty-reflect-config`) adds 5
> entries (318→323: `NativePRNG`, `SecurityUtils`, `ForwardedRequestCustomizer$Forwarded`,
> `org.restlet…ServerAdapter`, a Xerces factory) and a minimal fixture
> `serve-rest-mock.ikanos.yaml` exposing `GET /greet` → real native `200` (was hanging). **#594 is
> OPEN, not yet merged (no PR).** Consequence for this tracker: any *real-HTTP* native e2e of an
> HTTP-adapter feature (REST, Skill, Control) is **gated on #594** — the current "NATIVE" marks
> below rest on the TCP-probe, not on a served request. A first native control-port scaffold,
> `ControlPortNativeIT`, is parked on `origin/chore/issue-578-control-port-native-it` (`a4fff4b3`),
> awaiting #594's merge before it can assert a real `200`.

---

## What this document does NOT do

It does not propose test designs and does not implement tests. It tracks facts (coverage + phase
delivery). The follow-up sub-issues (family A / B) are the actionable test output.

---

## Feature table

- **Delivery**: `SHIPPED` / `PARTIAL` / `NOT_IMPL` — sourced from `gap-analysis-report.md` + engine presence, **not** the blueprint `Status` field (unreliable).
- **Coverage**: `NATIVE` / `JVM` / `NONE` per the model above.
- **Phases**: delivered / total phases declared in the blueprint's roadmap — a coarse count (e.g. `2 / 6`). When no count applies, one of three explicit labels: `no phasing` (a blueprint exists but declares no phased roadmap — delivered in one block), `no dedicated blueprint` (a base-spec engine primitive covered only by the generic capability spec, with no blueprint of its own), or `unverified` (a phased blueprint exists but the delivered-count is not yet reconciled). Blueprints were cross-checked against **both** the local disk (`blueprints/` + `blueprints/archives/`) **and** the Notion ITD database.

| # | Blueprint (feature) | Delivery | Coverage | Phases | Evidence — test(s) / launch path |
|---|---|---|---|---|---|
| 1 | MCP HTTP exposition (`type: mcp`, tools) | SHIPPED | JVM | no dedicated blueprint | Steps 1–8, 11 boot the MCP adapter in-process; nightly runs them against Microcks. No native-image launch. Base-spec primitive (Generic Capability Spec), no phasable blueprint of its own. |
| 2 | Mock mode (`MockOutputParameter`) | SHIPPED | JVM | no dedicated blueprint | `Step1ShipyardMcpClientIntegrationTest` (in-process boot). The ITD *Capability Mocking & Resolution Policies* blueprint describes a broader future `scenarios`/`resolution` model, not this shipped base-spec primitive. |
| 3 | Consumed HTTP + output shaping | SHIPPED | JVM | no dedicated blueprint | Steps 2–7 (in-process boot, Microcks backend). Base-spec primitive. |
| 4 | Consumed auth — bearer | SHIPPED | JVM | no dedicated blueprint | `Step3ShipyardMcpClientIntegrationTest`. Base-spec auth scheme. |
| 5 | Consumed auth — basic / apikey / digest | SHIPPED | NONE | no dedicated blueprint | No integration test launches a capability against a backend requiring these schemes. Base-spec auth schemes. |
| 6 | Request body — JSON | SHIPPED | JVM | no dedicated blueprint | `Step6ShipyardMcpClientIntegrationTest` (write operations). Base-spec primitive. |
| 7 | Request body — form-urlencoded / multipart / text / raw | SHIPPED | NONE | no dedicated blueprint | No end-user test sends these content types. Base-spec primitives. |
| 8 | Multi-step orchestration (call + lookup) | SHIPPED | JVM | no dedicated blueprint | `Step7ShipyardMcpClientIntegrationTest`. Base-spec orchestration steps. |
| 9 | Aggregates + `ref` (REST + MCP reuse) | SHIPPED | NONE | no dedicated blueprint | `AggregateIntegrationTest` / `AggregateSharedMockIntegrationTest` load the spec in-process **without booting an HTTP server** → NONE. `step-9-shipyard-aggregates.yml` fixture exists but **has no test class**. |
| 10 | REST exposition (`type: rest`) | SHIPPED | NATIVE | no dedicated blueprint | JVM via `Step10ShipyardRestAdapterIntegrationTest`; **also NATIVE** — the nightly `smoke-tests` boots `serve-http.ikanos.yaml` on the native binary and TCP-probes the exposed REST port (`ping-rest`, #581). **Caveat**: this NATIVE mark rests on a **TCP-probe only**, not a served HTTP request — real native `GET` was hanging until [#594](https://github.com/naftiko/ikanos/issues/594) (fixed on branch `fix/native-jetty-reflect-config`, commit `636a00e2`, fixture `serve-rest-mock.ikanos.yaml` → `GET /greet` 200; **OPEN, pending merge**). A *real-HTTP* native REST e2e is **gated on #594**. |
| 11 | Skill server adapter (`type: skill`) | SHIPPED | JVM | 4 / 4 | `Step8`, `Step10`, `SkillIntegrationTest`. The native fixture declares a `skill` port but the smoke test only probes the REST port → not exercised at native level. A native skill smoke (real HTTP) is **gated on [#594](https://github.com/naftiko/ikanos/issues/594)** like the other HTTP adapters. Blueprint `archives/agent-skills-support.md` (ITD: *Agent Skills Support*) — all 4 roadmap phases shipped as base-spec `type: skill`. |
| 12 | Bindings (`binds:`, file/vault) | SHIPPED | JVM | no dedicated blueprint | Steps 3–11 load `shared/secrets.yaml`. Base-spec primitive. |
| 13 | Fleet manifest (`catalog-info.yaml`) | SHIPPED | JVM | no dedicated blueprint | `Step11ShipyardFleetManifestIntegrationTest`. Owned by the Fleet/Warden template, not a phasable blueprint. |
| 14 | Control Port (`type: control`, `/health/live` + `/health/ready` + `/status` + `/metrics` + `/traces`) | SHIPPED | NONE | 1 / 4 | `CapabilityRuntimeIntegrationTest` does a GET on `/health/ready`, but only as a lifecycle/readiness signal — no test asserts the **content** of any control endpoint. The native fixture declares a `control` port but the smoke test does not call it. Real routes (`ControlServerAdapter`): `/health/live` (always 200 `{"status":"UP"}`), `/health/ready` (200/503), `/status` (gated by `info`), `/metrics` + `/traces` (503 unless OTel active) — there is no bare `/health` or `/ready`. Phase 1 shipped; phases 2–4 (config/reload, logs, lifecycle, debug) modeled in schema but not wired in the engine. A native control-port e2e (the `ControlPortNativeIT` scaffold parked on `origin/chore/issue-578-control-port-native-it`, `a4fff4b3`) is **gated on [#594](https://github.com/naftiko/ikanos/issues/594)** — it cannot assert a real `200` until native HTTP-serve is fixed. Blueprint: `archives/control-port.md`. |
| 15 | OTel observability (tracing + Prometheus metrics) | SHIPPED | NONE | 5 / 5 | `ObservabilitySpecIntegrationTest` etc. load in-process without booting a server; no OTLP export driven end-to-end. All 5 roadmap phases (logging facade, tracing, metrics, spec-driven config, dashboarding) shipped; coverage still NONE. Blueprint: `archives/opentelemetry-observability.md`. |
| 16 | Reverse tunnel (Ziti) | SHIPPED | NONE | 2 / 6 | `TunnelTransportTest`/`TunnelRouteTableTest`/`TunnelBootstrapTest` are unit-level; no launched capability routes a call through a Ziti network. Phases 1–2 shipped, 3 partial, 4–5 pending. Blueprint: `reverse-tunnel-private-network.md`. |
| 17 | MCP exposed auth (OAuth 2.1 / Bearer) | SHIPPED | NONE | no dedicated blueprint | `ServerAdapterAuthenticationTest` is unit-level; no launched MCP adapter validates a token end-to-end. Base-spec primitive. |
| 18 | Script step (`type: script`, JS/Python/Groovy) | SHIPPED | NONE | no dedicated blueprint | `ScriptStepExecutorTest` / `OperationStepExecutorIntegrationTest` run in-process; no launched capability calls a scripted tool. Base-spec primitive. |
| 19 | Import mechanism (`from:` across sections) | SHIPPED | NONE | 3 / 3 | `ImportResolverParameterizedTest` loads specs only; no launched capability uses `from:` imports. All 3 roadmap phases shipped (blueprint checklist `[x]`); coverage still NONE. Blueprint: `unified-import-mechanism.md`. |
| 20 | OAS import/export | SHIPPED | NONE | 0–1 / 4 | `Oas*IntegrationTest` are in-process round-trips; no end-user CLI/HTTP flow. Phase 1 partial, 2–4 pending. Blueprint: `openapi-interoperability.md`. |
| 21 | MCP resources (`McpResource`) | SHIPPED | NONE | 6 / 6 | No launched MCP adapter serves a `resources:` block. Blueprint: ITD *MCP Resources & Prompt Templates Support Proposal* (Notion `31b4adce…`) — all 6 roadmap phases shipped. |
| 22 | MCP prompts (`McpPrompt`) | SHIPPED | NONE | 6 / 6 | No launched MCP adapter serves a `prompts:` block. Same blueprint as #21 (ITD *MCP Resources & Prompt Templates Support Proposal*) — all 6 roadmap phases shipped. |
| 23 | MCP tool hints (`McpToolHints`) | SHIPPED | NONE | no dedicated blueprint | `AggregateIntegrationTest` checks semantics→hints in-process; no launched tool-call asserts hints on the wire. Hints derived from `semantics`, no phasable blueprint of their own. |
| 24 | CLI `serve` command | SHIPPED | NATIVE | no phasing | Nightly `smoke-tests` runs `ikanos serve` **on the native binary** (`serve-http.ikanos.yaml`), confirming the HTTP connector boots and the REST port listens (#581). `ServeCommandTest` adds JVM-level parsing coverage. Blueprint `archives/cli-serve-command.md` (ITD: *CLI serve Command*) is a post-hoc record (PR #460), no phased roadmap. |
| 25 | Embedded library (`StepHandlerRegistry`) | SHIPPED | NONE | no phasing | `EngineStepHandlerOverrideTest` is in-process; not an end-user launch (and partly out of the launched-capability scope). Blueprint `embedded-library.md` has a `Delivery` section but no enumerated phases. |
| 26 | Consumed auth — OAuth2 client credentials (token refresh) | PARTIAL | NONE | 0 / 3 | `token-refresh-authentication.md` §12 — client-side flow not implemented (Phase 4 is explicitly out of proposal). |
| 27 | Resilience (retry / timeout / failover) | PARTIAL | NONE | 0–1 / 4 | gap-report §17.3 — Phase 1b partial, rest pending (+1 Adjacent item). Blueprint: `resilience-through-resolution-policies.md`. |
| 28 | HTTP cache control | NOT_IMPL | — | 0 / 4 | Out of scope. Blueprint: `http-cache-control.md`. |
| 29 | Deterministic flow steps (if/for-each/parallel) | NOT_IMPL | — | no phasing | Out of scope. Blueprint `deterministic-flow-steps.md` declares no roadmap section. |
| 30 | gRPC adapter | NOT_IMPL | — | 0 / 6 | Out of scope. 6 milestones planned. Blueprint: `grpc-server-adapter.md`. |
| 31 | Webhook adapter | NOT_IMPL | — | 0 / 3 | Out of scope. 3 phases planned. Blueprint: `webhook-server-adapter.md`. |
| 32 | mTLS client certificates | NOT_IMPL | — | 0 / 1 | Out of scope. Phase 1 (MVP) planned, blocked on token-refresh refactor; Phase 2 = future/out of proposal. Blueprint: `mtls-client-certificates.md`. |

> **Note on the `Phases` column.** A numeric count `delivered / total` is the *delivered / total*
> phases declared in the blueprint's roadmap; ranges (e.g. `0–1 / 4`) mean a phase is partially
> delivered. When no count applies the cell carries an explicit label — `no phasing` (a blueprint
> exists but declares no phased roadmap), `no dedicated blueprint` (base-spec engine primitive with
> no blueprint of its own, covered only by the generic capability spec), or `unverified` (a phased
> blueprint exists but its delivered-count has not been reconciled). Blueprint existence was checked
> against **both** the local disk and the Notion ITD database. This is a coarse indicator — to
> recheck a value, read the blueprint's Roadmap section directly. Keep the count in sync on every PR
> that ships a phase.

---

## How to launch — strategy by category

Not test designs — the right question per category when writing the family-A tests. The launch
vector should be **the lightest of the two** by default (`ikanos serve` on the native binary),
reserving **Docker** for the cases where the full packaging matters.

**Generic native-path harness** (applies to most JVM→NATIVE upgrades): the pattern already exists
in the nightly `smoke-tests` job — start `ikanos serve <fixture>.yml` as a subprocess against the
native binary (or `docker run ikanos …`), wait for the port, drive the adapter with a real HTTP
client, assert, then tear down. The #581 guard does exactly this for the REST port; extending it to
other adapters/features mostly means **adding fixtures and real client calls**, not building the
harness from scratch. The existing tutorial fixtures and Microcks backend are reusable as-is — only
the **launch path** changes from in-process JVM to native image.

- **Aggregates** (#9): `step-9-shipyard-aggregates.yml` exists — needs a launched test (start with JVM-level `Step9…` to reach parity, then a NATIVE variant).
- **Control port** (#14): launch a `type: control` capability; HTTP GET `/health/live` (the reliable always-200 probe), then `/health/ready` and `/status`. `/metrics` + `/traces` return 503 unless OTel is active, so assert their reachability, not a 200. No backend needed — a good first **NATIVE** candidate. The scaffold (`ControlPortNativeIT`, parked on `origin/chore/issue-578-control-port-native-it`) is ready but **gated on #594** (native HTTP-serve fix) before it can assert a real `200`.
- **OTel** (#15): launch a capability with a control port; assert `/metrics` returns Prometheus data and `/traces` returns the ring buffer; OTLP export against an in-process collector for enrichment.
- **Reverse tunnel** (#16): Ziti needs a live controller — not feasible in a plain test. First step: a contract test through a stubbed `TunnelTransport`; true NATIVE e2e requires a Ziti sandbox (CI/nightly only).
- **MCP auth** (#17): launch an MCP adapter with `authentication: bearer`/`oauth2`; drive with/without a valid token; assert 401 / 200.
- **Script step** (#18): launch a capability exposing a `type: script` (JS) tool; call it; assert the scripted output.
- **Imports** (#19): a fixture split across files using `from:`; launch the composed capability; assert all tools resolve.
- **CLI serve** (#24): already exercised at native level by the #581 smoke — the same subprocess pattern is the NATIVE vector to reuse for the other adapters.
- **MCP resources / prompts** (#21/#22): extend a fixture with `resources:` / `prompts:`; launch; call `resources/*` / `prompts/*`.
- **Request body variants** (#7): add a Microcks operation accepting `x-www-form-urlencoded`; launch a capability that posts to it.
- **Consumed auth basic/apikey/digest** (#5): add Microcks examples per scheme; launch a capability consuming each.

---

## Family-A gaps — blocking for #578 done criterion

Two sub-types: **(A0)** lift an existing JVM-partial feature to NATIVE (cheap), and **(A1)** create
a first end-user test for a NONE feature.

| Priority | Type | Gap | Effort |
|---|---|---|---|
| 🔴 High | A1 | Aggregates launched test (Step 9 missing) | Low — fixture exists |
| 🔴 High | A1 | Control port endpoints (a `control` port is already declared in the native fixture — wire a smoke GET) | Low — no backend |
| 🔴 High | A1 | MCP resources end-to-end | Low — extend fixture |
| 🔴 High | A1 | MCP prompts end-to-end | Low — extend fixture |
| 🟠 Medium | A1 | Script step launched (JS) | Medium |
| 🟠 Medium | A1 | MCP adapter auth (bearer/OAuth2.1) | Medium |
| 🟠 Medium | A1 | Import mechanism launched | Medium |
| 🟠 Medium | A0 | **Extend the existing native smoke** (#581 `serve-http` fixture) to drive the **MCP** and **Skill** adapters at native level — both ports are already declared in the fixture but never probed (**gated on #594**: a *real* HTTP call, vs today's TCP-probe, only succeeds once the native serve fix lands) | Medium — fixture exists, add a real client call |
| 🟡 Low | A1 | OTel via control port (`/metrics` + `/traces`) | Low |
| 🟡 Low | A1 | Consumed auth basic / apikey / digest | Low |
| 🟡 Low | A1 | Request body form / multipart | Medium |
| ⬜ Special | A1 | Reverse tunnel (Ziti) — contract test first, sandbox e2e later | High |

---

## Family-B — enrichment backlog (sub-features, non-blocking)

| Feature | Covered today | Missing sub-features |
|---|---|---|
| Native launch path (per feature) | REST + CLI (`create`/`validate`/`serve`) at native level via #581 smoke | NATIVE coverage for **MCP, Skill, Control** adapters and advanced features — extend the existing `serve-http` smoke fixture per adapter |
| Consumed auth | bearer | basic, apikey, digest |
| MCP tool hints | semantics→hints (in-process) | hints on the wire in `tools/list` |
| OTel | in-process span/metric creation | OTLP export, W3C propagation over HTTP |
| Script step | JS executor | Python, Groovy, dependency pre-evaluation |
| Control port | lifecycle | `/health`, `/ready`, `/metrics`, `/traces`, `/status`, `/logs`, scripting endpoint |
| Aggregates | ref resolution / hints override (in-process) | multi-adapter reuse via `step-9` launched |
| OAS interoperability | import/export round-trip (in-process) | CLI `import-openapi` / `export-openapi` end-to-end |

---

## Limitations of this tracker

1. **Blueprint inventory cross-checked against Notion ITD (2026-06-23).** Both the local disk (`blueprints/` + `blueprints/archives/`) and the ITD Notion database (`2e14adce-3d02-8063-8426-eec9aedf3a5e`) were queried. Several features carry a blueprint that lives only in Notion or only in `archives/` (e.g. #11 *Agent Skills Support*, #21/#22 *MCP Resources & Prompt Templates*, #24 *CLI serve Command*). Residual caveat: the ITD listing was read by `Doc name`; a blueprint filed under an unexpected title could still be missed.

---

## Changelog

| Date | Change |
|---|---|
| 2026-06-25 | Recorded the native HTTP-serve dependency on [#594](https://github.com/naftiko/ikanos/issues/594): the #581 native smoke is a **TCP-probe only**, so real-HTTP native e2e of the REST / Skill / Control adapters is **gated on #594** (OPEN, fix on branch `fix/native-jetty-reflect-config` `636a00e2`, fixture `serve-rest-mock.ikanos.yaml`). Qualified rows #10/#11/#14, the Control-port launch strategy (parked `ControlPortNativeIT` scaffold), and Family-A A0. Facts verified read-only via the `crawler` agent. |
| 2026-06-23 | Initial coverage audit for #578 / #590 (32 features, delivery × coverage × phases table, Family-A/B backlog). Mirrored to Notion ITD. |
