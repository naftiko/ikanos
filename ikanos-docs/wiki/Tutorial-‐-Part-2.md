# The Shipyard — Tutorial — Part 2

At the end of Part 1, your capability could list, inspect, plan, and enrich. Five tools, two APIs, one contract that survived the journey from mock to production. Good for an MCP client sitting next to the capability. But the Shipyard doesn't live alone: partner agents want a *catalog* they can discover, some logic keeps getting copy-pasted between tools, the operations dashboard speaks REST not MCP, and ops walks in one morning asking for *"one document that has everything."*

Part 2 adds four layers on top of Part 1's foundation without touching the core tools: **Skills** for discoverable grouping, **Aggregates & Ref** to factor shared logic once, a **REST adapter** for non-AI consumers, and a **Fleet Manifest** capstone that ties it all together.

Same YAML. Still no code.

> ⚓ **Before you start.** You should have completed [Part 1](https://github.com/naftiko/ikanos/wiki/Tutorial-%E2%80%90-Part-1) (Steps 1–7). All files for this part live alongside the earlier ones in `ikanos-docs/tutorial/`.

---

## Step 8 — Skill Groups

**File:** `step-8-shipyard-skill-groups.yml` — [download](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/step-8-shipyard-skill-groups.yml)

Five tools today. Twenty tomorrow. Fifty next quarter. An agent landing on your MCP endpoint sees a flat list and has to figure out on its own which tools matter for *planning a voyage* versus *auditing the fleet*. That's noise, and noise burns context.

**Agent Skills** are the table of contents for your toolbox. They group related tools under a business-facing label (`fleet-ops`, `voyage-ops`) with a description the agent reads *before* deciding what to do. The [Agent Skills website](https://agentskills.io/) documents the broader model; Ikanos exposes it as a third expose type alongside MCP and REST.

~~~yaml
- type: skill
  port: 3002
  namespace: shipyard-skills
  description: "Shipyard skill groups for structured agent discovery"
  skills:
    - name: fleet-ops
      description: "Fleet management — list, search, and inspect ships across all registries"
      tools:
        - name: list-ships
          from: { sourceNamespace: shipyard-tools, action: list-ships }
        - name: get-ship
          from: { sourceNamespace: shipyard-tools, action: get-ship }
        - name: get-ship-with-crew
          from: { sourceNamespace: shipyard-tools, action: get-ship-with-crew }
        - name: list-legacy-vessels
          from: { sourceNamespace: shipyard-tools, action: list-legacy-vessels }
    - name: voyage-ops
      description: "Voyage planning — create and manage voyages"
      tools:
        - name: create-voyage
          from: { sourceNamespace: shipyard-tools, action: create-voyage }
~~~

No logic duplication — each skill just references existing MCP tools via `from.sourceNamespace` + `action`. Adding a skill block is purely additive: your MCP tools keep working exactly as before, and any client that prefers the skill-level view gets it for free.

### Remote discovery — the SKILL HTTP API

The real leverage shows up when another agent, on another machine, wants to *find* your skills without someone emailing a YAML file. The SKILL adapter auto-exposes a read-only HTTP API on port 3002:

| Endpoint | Returns |
|---|---|
| `GET /skills` | Catalog — count and summary of every skill with its tool names |
| `GET /skills/{name}` | Full metadata, tool catalog, and `invocationRef` for each tool |
| `GET /skills/{name}/download` | ZIP archive of the skill's `location` directory |
| `GET /skills/{name}/contents` | File listing of the skill's `location` directory |
| `GET /skills/{name}/contents/{file}` | Individual file from the skill's `location` directory |

Typical workflow: **discover** with `GET /skills`, **inspect** with `GET /skills/fleet-ops` to learn which sibling MCP or REST namespace to actually call (`invocationRef`), **install** with `GET /skills/fleet-ops/download` to pull the skill's instruction files. Plain HTTP/JSON — any client can consume it.

> 🧭 **What you learned:** `type: skill`, `ExposedSkill` / `SkillTool.from`, the SKILL HTTP API for remote discovery + install.

---

## Step 9 — Aggregates & Ref

**File:** `step-9-shipyard-aggregates.yml` — [download](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/step-9-shipyard-aggregates.yml)

Something's starting to smell. Step 7 defined a three-step chain inside `get-ship-with-crew` — fetch ship, fetch crew, lookup. Step 11 will chain seven steps for the Fleet Manifest. And the REST adapter in Step 10 wants to expose `get-ship-with-crew` too. That's the *same* logic needed in three places. Copy-paste is where capabilities go to die.

**Aggregates** are named, reusable computation units that live at capability level. Inside them, **`functions`** define the steps — just like a tool's `steps`, but not directly exposed. Tools and REST operations reference them with **`ref`** instead of inlining logic. Bonus: each function declares **`semantics`** (`safe`, `idempotent`, `cacheable`), and the framework derives MCP `hints` from them automatically — no more hand-setting `readOnly: true` on every read tool.

~~~yaml
aggregates:
  - namespace: crew-resolver
    functions:
      - name: resolve-crew-for-ship
        semantics:
          safe: true
          idempotent: true
          cacheable: true
        inputParameters:
          - { name: imo, type: string, required: true }
        steps:
          - name: get-ship
            type: call
            call: registry.get-ship
            with:
              imo_number: crew-resolver.imo
          - name: list-crew
            type: call
            call: registry.list-crew
          - name: resolve-crew
            type: lookup
            index: list-crew
            match: crewId
            lookupValue: "$.get-ship.assignedCrew"
            outputParameters:
              - "fullName"
              - "role"
        mappings:
          - targetName: imo
            value: "$.get-ship.imo_number"
          - targetName: name
            value: "$.get-ship.vessel_name"
          - targetName: crew
            value: "$.resolve-crew"
~~~

Tools and REST ops now point to the aggregate instead of redefining the chain:

~~~yaml
# MCP tool — was 3 inline steps + mappings in Step 7
- name: get-ship-with-crew
  inputParameters:
    - { name: imo, type: string, required: true }
  ref: crew-resolver.resolve-crew-for-ship
  with:
    imo: shipyard-tools.imo
~~~

No `hints` block. The framework sees `safe: true` and derives `readOnly: true, destructive: false`; it sees `idempotent: true` and carries that through to MCP `ToolAnnotations`. `cacheable` is a framework-internal signal (not an MCP hint) — it unlocks response caching between identical calls.

The tool output is byte-for-byte identical to Step 7. The *spec* got dramatically cleaner, and Step 11's seven-step Fleet Manifest will pay off the pattern at scale.

> 🧭 **What you learned:** `aggregates`, `functions`, `semantics` (safe / idempotent / cacheable), `ref`, automatic semantics→hints derivation.

---

## Step 10 — REST adapter

**File:** `step-10-shipyard-rest-adapter.yml` — [download](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/step-10-shipyard-rest-adapter.yml)

Not every consumer is an AI agent. The operations dashboard is a plain React app. The partner logistics company speaks OpenAPI. The mobile team doesn't care what MCP is — they want `GET /ships/{imo}` and they want it *now*.

Same capability, different front door. `type: rest` exposes HTTP endpoints that map to the same consumed operations — or, better, the same aggregate functions we defined in Step 9. Write logic once, expose it three times.

~~~yaml
- type: rest
  port: 3003
  namespace: shipyard-api
  resources:
    - name: ships
      path: "/ships"
      operations:
        - name: list-ships
          method: GET
          call: registry.list-ships
        - name: get-ship
          method: GET
          path: "/ships/{imo}"
          inputParameters:
            - { name: imo, in: path, required: true }
          call: registry.get-ship
          with:
            imo_number: shipyard-api.imo
        - name: get-ship-with-crew
          method: GET
          path: "/ships/{imo}/crew"
          inputParameters:
            - { name: imo, in: path, required: true }
          ref: crew-resolver.resolve-crew-for-ship
          with:
            imo: shipyard-api.imo
    - name: legacy-vessels
      path: "/legacy/vessels"
      operations:
        - name: list-legacy-vessels
          method: GET
          call: legacy.list-vessels
    - name: voyages
      path: "/voyages"
      operations:
        - name: create-voyage
          method: POST
          call: registry.create-voyage
~~~

Two things to notice. First, REST operations declare HTTP-level parameter placement (`in: path`, `in: query`, `in: body`) that MCP tools don't need — the HTTP router has to know where to look. Second, the `get-ship-with-crew` endpoint uses `ref: crew-resolver.resolve-crew-for-ship` — the exact same aggregate the MCP tool uses. Three expose blocks (MCP, Skills, REST) now coexist in the same capability, all backed by the same consumes and aggregates. Every consumer picks the door that fits; no one pays for the others.

> 🧭 **What you learned:** `type: rest`, resources/operations with HTTP verbs and paths, `inputParameters` with `in: path|query|body`, `ref` on REST operations, MCP ↔ Skills ↔ REST coexistence.

---

## Step 11 — The Fleet Manifest (capstone)

**Files:** `step-11-shipyard-fleet-manifest.yml`, `shared/step11-registry-consumes.yml`, `shared/legacy-consumes.yaml`

- [step-11-shipyard-fleet-manifest.yml](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/step-11-shipyard-fleet-manifest.yml)
- [step11-registry-consumes.yml](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/shared/step11-registry-consumes.yml)
- [legacy-consumes.yaml](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/shared/legacy-consumes.yaml)

The voyage is planned. The crew is confirmed. Oslo to Singapore, Northern Star, Captain Erik, Aiko in the galley. And now operations walks in with the final ask: *"I need one document. Voyage, ship, crew, cargo — all of it, resolved, in one payload. Before the ship leaves."*

Three separate calls and client-side stitching would work. But we already have the pattern (Step 7) and the abstraction (Step 9). This is the same lookup mechanic, scaled up: **four `call` steps and three `lookup` steps**, defined once as an aggregate function, referenced from MCP, Skills, and REST simultaneously.

~~~yaml
aggregates:
  - namespace: manifest
    functions:
      - name: build-voyage-manifest
        semantics:
          safe: true
          idempotent: true
          cacheable: true
        inputParameters:
          - { name: voyageId, type: string, required: true }
        steps:
          - name: get-voyage
            type: call
            call: registry.get-voyage
            with:
              voyageId: manifest.voyageId
          - name: list-ships
            type: call
            call: registry.list-ships
          - name: resolve-ship
            type: lookup
            index: list-ships
            match: imo-number
            lookupValue: "$.get-voyage.shipImo"
          - name: list-crew
            type: call
            call: registry.list-crew
          - name: resolve-crew
            type: lookup
            index: list-crew
            match: crewId
            lookupValue: "$.get-voyage.crewIds"
            outputParameters: [ "fullName", "role" ]
          - name: list-cargo
            type: call
            call: registry.list-cargo
          - name: resolve-cargo
            type: lookup
            index: list-cargo
            match: cargoId
            lookupValue: "$.get-voyage.cargoIds"
            outputParameters: [ "description", "weight", "hazardous" ]
~~~

The MCP tool and the REST endpoint both collapse to a one-liner `ref`. The Skills layer picks up the new tool automatically once it's registered in `voyage-ops`:

~~~yaml
# MCP
- name: get-voyage-manifest
  inputParameters:
    - { name: voyageId, type: string, required: true }
  ref: manifest.build-voyage-manifest

# REST
- name: get-voyage-manifest
  method: GET
  path: "/voyages/{voyageId}/manifest"
  inputParameters:
    - { name: voyageId, in: path, required: true }
  ref: manifest.build-voyage-manifest
~~~

One tool call in, one assembled manifest out:

~~~json
{
  "voyageId": "VOY-2026-042",
  "status": "planned",
  "route": { "from": "Oslo", "to": "Singapore" },
  "ship": { "name": "Northern Star", "type": "cargo", "flag": "NO" },
  "crew": [
    { "fullName": "Erik Lindström", "role": "captain" },
    { "fullName": "Aiko Tanaka",   "role": "cook" }
  ],
  "cargo": [
    { "type": "container", "description": "Electronics components", "weight": 12.5, "hazardous": false },
    { "type": "bulk",      "description": "Iron ore samples",       "weight": 85.0, "hazardous": false }
  ]
}
~~~

Ops has their document. A last flourish: the capability gets an **`info`** block at the top (label, description, tags, dates) — a proper production identity.

> 🧭 **What you learned:** multi-step + multi-lookup chaining inside an aggregate, `ref` shared across MCP and REST, `info` metadata, capstone composition.

---

## The full journey

Eleven steps. One YAML file. Zero code.

Part 1: **mock** → wire → auth → shape → multi-source + XML → write + hints → orchestrated lookup.

Part 2: **skills** → aggregates + ref → REST → full orchestration.

Three front doors (MCP, Skills, REST) share one engine, one set of consumes, one set of aggregates. Every consumer picks the door that fits, and the spec grew without a single backend change touching the agent's contract.

That's the Shipyard.

**File:** `step-9-shipyard-rest-adapter.yml` — [download](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/step-9-shipyard-rest-adapter.yml)

Not every consumer is an AI agent. The operations dashboard is a plain React app. The partner logistics company speaks OpenAPI. The mobile app team doesn't even know what MCP is — they want `GET /ships/{imo}` and they want it *now*.

Same capability, different front door. `type: rest` exposes the exact same underlying tools as HTTP endpoints.

~~~yaml
- type: rest
  port: 3003
  namespace: shipyard-api
  resources:
    - name: ships
      path: "/ships"
      operations:
        - name: list-ships
          method: GET
          call: shipyard-tools.list-ships
    - name: ship-by-imo
      path: "/ships/{imo}"
      operations:
        - name: get-ship
          method: GET
          call: shipyard-tools.get-ship
          with:
            imo_number: shipyard-api.imo
    - name: legacy-vessels
      path: "/legacy/vessels"
      operations:
        - name: list-legacy-vessels
          method: GET
    - name: voyages
      path: "/voyages"
      operations:
        - name: create-voyage
          method: POST
~~~

Exactly the same `call` + `with` wiring you learned in Part 1 — just a different protocol at the edge. `GET /ships/{imo}` on port 3003 and `list-ships` on the MCP port share one implementation. Three expose blocks (MCP, Skills, REST) now coexist in the same capability. Every consumer picks the door that fits; no one pays for the others.

> 🧭 **What you learned:** `type: rest`, resources and operations mapping HTTP verbs/paths to existing tools, MCP ↔ Skills ↔ REST coexistence.

---

## Step 10 — The Fleet Manifest (capstone)

**Files:** `step-10-shipyard-fleet-manifest.yml`, `shared/step10-registry-consumes.yml`, `shared/legacy-consumes.yaml`

- [step-10-shipyard-fleet-manifest.yml](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/step-10-shipyard-fleet-manifest.yml)

The voyage is planned. The crew is confirmed. Oslo to Singapore, Northern Star, Captain Erik, Aiko in the galley. Now operations walks in and says: *"I need one document. Ship, crew, cargo — all of it, resolved, in one payload. Before the ship leaves."*

Three separate tools + client-side stitching would work. But we already know how to do better: one orchestrated tool, 4 `call` steps and 3 `lookup` steps, assembled server-side. This is Step 7's lookup pattern, scaled up.

~~~yaml
- name: get-voyage-manifest
  description: "Assemble a complete voyage manifest with ship, crew, and cargo"
  inputParameters:
    - name: voyageId
      type: string
      required: true
  steps:
    - name: get-voyage
      type: call
      call: registry.get-voyage
      with:
        voyageId: shipyard-tools.voyageId
    - name: list-ships
      type: call
      call: registry.list-ships
    - name: resolve-ship
      type: lookup
      index: list-ships
      match: imo-number
      lookupValue: "$.get-voyage.shipImo"
    - name: list-crew
      type: call
      call: registry.list-crew
    - name: resolve-crew
      type: lookup
      index: list-crew
      match: crewId
      lookupValue: "$.get-voyage.crewIds"
      outputParameters:
        - "fullName"
        - "role"
    - name: list-cargo
      type: call
      call: registry.list-cargo
    - name: resolve-cargo
      type: lookup
      index: list-cargo
      match: cargoId
      lookupValue: "$.get-voyage.cargoIds"
      outputParameters:
        - "description"
        - "weight"
        - "hazardous"
~~~

~~~json
{
  "voyageId": "VOY-2026-042",
  "status": "planned",
  "route": { "from": "Oslo", "to": "Singapore" },
  "ship": { "name": "Northern Star", "type": "cargo", "flag": "NO" },
  "crew": [
    { "fullName": "Erik Lindström", "role": "captain" },
    { "fullName": "Aiko Tanaka",   "role": "cook" }
  ],
  "cargo": [
    { "type": "container", "description": "Electronics components", "weight": 12.5, "hazardous": false },
    { "type": "bulk",      "description": "Iron ore samples",       "weight": 85.0, "hazardous": false }
  ]
}
~~~

One tool call in, one assembled manifest out. Ops has their document.

Two finishing touches wrap up the capability: a top-level **`info` block** (label, description, tags, dates) gives it a proper production identity, and the new tool is surfaced everywhere at once — `get-voyage-manifest` joins the `voyage-ops` skill, and `GET /voyages/{voyageId}/manifest` appears on the REST adapter. One edit, three front doors updated.

> 🧭 **What you learned:** Chaining multiple `call` + `lookup` steps, resolving independent related entities from a single payload, consumes-level `outputParameters`, and the `info` metadata block.

---

## The full journey

Part 1 took you from a 15-line `list-ships` wrapper to an authenticated, multi-source, write-capable, server-enriched MCP capability. Part 2 added three surfaces on top without touching the core logic.

Contract-first → wire → auth → shape → multi-source → write → lookup → **skills → REST → full orchestration**.

One YAML file. Zero code. Three front doors. A complete maritime agent platform.

That's the Shipyard.