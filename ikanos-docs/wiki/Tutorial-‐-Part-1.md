# The Shipyard — Tutorial — Part 1

You run a maritime shipping company. Ships, crews, voyages, cargo — all scattered across REST APIs. What you want is an AI agent that can actually *use* them. What you don't want is to wait for every backend team to finish their work before you start.

Good news: you don't have to. This tutorial opens with a **mock**. One YAML file, no backend, and the agent already works. Then — step by step — we wire the real registry, lock the doors (both of them), shape the output, plug in a legacy system that still speaks XML, teach the agent to write, and finally orchestrate server-side lookups so the agent gets names instead of IDs.

No code. Just a spec. Contract-first.

> ⚓ **Prerequisites.** A running Ikanos Engine (see the [installation instructions](https://github.com/naftiko/ikanos/wiki/Installation)). All capability files for this tutorial live in `ikanos-docs/tutorial/`.
>
> **Editor support.** For inline validation and autocompletion while writing capability files, install the free [Naftiko Extension for VS Code](https://github.com/naftiko/fleet/wiki/Naftiko-Extension-for-VS-Code). Name your files `*.naftiko.yaml` / `*.naftiko.yml` to activate it.

---

## Step 1 — Mock First

**File:** `step-1-shipyard-mock.yml` — [download](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/step-1-shipyard-mock.yml)

You don't have a backend. You *will*, eventually — but operations hasn't finished migrating ship data, and the registry API is still in review. Meanwhile, the agent needs to demo next week.

Contract-first says: define what the agent sees *first*, fill it with mock data, worry about the wire later. One expose block, one tool, no `consumes`, no `call`. Just `value`:

~~~yaml
ikanos: "1.0.0-alpha2"

capability:
  exposes:
    - type: mcp
      port: 3001
      namespace: shipyard-tools
      description: "Shipyard MCP tools for fleet management"
      tools:
        - name: get-ship
          description: "Retrieve a ship's details by IMO number"
          inputParameters:
            - name: imo
              type: string
              required: true
              description: "IMO number of the ship"
          outputParameters:
            - name: imo
              type: string
              value: "{{imo}}"
            - name: name
              type: string
              value: "Northern Star"
            - name: type
              type: string
              value: "cargo"
            - name: flag
              type: string
              value: "NO"
            - name: status
              type: string
              value: "active"
~~~

`outputParameters` is a flat list of named parameters — same shape as `inputParameters`. Two flavors of `value` here: static strings (`"Northern Star"`, `"cargo"`, `"NO"`, `"active"`) that the mock returns as-is, and a **Mustache template** on the `imo` output — `value: "{{imo}}"` references the tool's input parameter `imo`, so whatever IMO the agent passes is echoed back in the response. A mock can do that: input parameters are fully usable inside `value:` templates, no `consumes` required. Enough to lock the contract and demo a tool that actually reacts to its input. Run the engine, connect an MCP client — [MCP Inspector](https://github.com/modelcontextprotocol/inspector) is the fastest — and call `get-ship(imo: "IMO-9321483")`:

~~~json
{ "imo": "IMO-9321483", "name": "Northern Star", "type": "cargo", "flag": "NO", "status": "active" }
~~~

The agent works. The contract is locked. Step 2 will swap every `value` for a `mapping` against the real registry — and the agent won't notice.

> 🧭 **What you learned:** contract-first, flat `outputParameters`, `value` (static + Mustache template referencing an `inputParameter`), `inputParameters`, MCP expose without `consumes`.

---

## Step 2 — Wiring to a real API

**File:** `step-2-shipyard-wiring.yml` — [download](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/step-2-shipyard-wiring.yml)

The Maritime Registry is live. `GET /ships/{imo_number}` returns real data. Time to honor the contract you defined in Step 1 — same tool signature, same output shape, real data behind it.

Three surgical changes to `get-ship`: add a `consumes` block, add `call` on the tool, swap every `value:` for `mapping:`. And since a static mock for `list-ships` would be pointless, we add that tool now — filtering by status only makes sense against real data.

~~~yaml
capability:
  consumes:
    - namespace: registry
      type: http
      baseUri: "https://mocks.naftiko.net/rest/naftiko-shipyard-maritime-registry-api/1.0.0-alpha2"
      resources:
        - name: ships
          path: "/ships"
          operations:
            - name: list-ships
              method: GET
              inputParameters:
                - { name: status, in: query }
        - name: ship
          path: "/ships/{{imo_number}}"
          operations:
            - name: get-ship
              method: GET
              inputParameters:
                - { name: imo_number, in: path, required: true }

  exposes:
    - type: mcp
      port: 3001
      namespace: shipyard-tools
      tools:
        - name: get-ship
          inputParameters:
            - { name: imo, type: string, required: true }
          call: registry.get-ship
          with:
            imo_number: shipyard-tools.imo
          outputParameters:
            - type: object
              properties:
                imo:    { type: string, mapping: "$.imo_number" }
                name:   { type: string, mapping: "$.vessel_name" }
                type:   { type: string, mapping: "$.vessel_type" }
                flag:   { type: string, mapping: "$.flag_code" }
                status: { type: string, mapping: "$.operational_status" }
        - name: list-ships
          description: "List ships, optionally filtered by status"
          inputParameters:
            - { name: status, type: string, required: false }
          call: registry.list-ships
          with:
            status: shipyard-tools.status
          outputParameters:
            - type: array
              mapping: "$."
              items:
                type: object
                properties:
                  imo:    { type: string, mapping: "$.imo_number" }
                  name:   { type: string, mapping: "$.vessel_name" }
                  type:   { type: string, mapping: "$.vessel_type" }
                  flag:   { type: string, mapping: "$.flag_code" }
                  status: { type: string, mapping: "$.operational_status" }
~~~

The `with` block is the bridge: the agent speaks `imo`, the registry speaks `imo_number` — `with` maps one to the other. On the consumes side, `status` becomes a query string (`?status=active`) and `imo_number` fills a path template (`/ships/IMO-9321483`).

Call `get-ship` and you get the exact same JSON shape as in Step 1 — the agent saw no change. Call `list-ships(status: "active")`:

~~~json
[
  { "imo": "IMO-9321483", "name": "Northern Star", "type": "cargo", "flag": "NO", "status": "active" },
  { "imo": "IMO-9456781", "name": "Pacific Dawn",  "type": "tanker", "flag": "SG", "status": "active" }
]
~~~

The mock is gone. The contract survived.

> 🧭 **What you learned:** `consumes`, `call`, `with`, `mapping` replacing `value`, path vs query parameters, contract preservation.

---

## Step 3 — Auth & Binds — both doors

**File:** `step-3-shipyard-auth.yml` — [download](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/step-3-shipyard-auth.yml)

The registry's public tier returns 5 fields per ship. Nice for a demo, useless for operations. Specs, dimensions, certifications, crew assignments — all of that sits behind a bearer token. And while we're handling tokens, there's a second door to lock: the MCP server itself. Right now *anyone* on the network can call `get-ship`. Not acceptable.

Same mechanism, two directions. `binds` declares what's secret. `authentication` plugs it in — once on the **back door** (consumes, calling the registry), once on the **front door** (MCP expose, validating clients).

~~~yaml
binds:
  - namespace: registry-env
    location: "file:///./shared/secrets.yaml"
    keys:
      REGISTRY_TOKEN: "registry-bearer-token"
      REGISTRY_VERSION: "registry-api-version"
      MCP_SERVER_TOKEN: "mcp-server-token"

capability:
  consumes:
    - namespace: registry
      type: http
      baseUri: "https://mocks.naftiko.net/rest/naftiko-shipyard-maritime-registry-api/1.0.0-alpha2"
      authentication:
        type: bearer
        token: "REGISTRY_TOKEN"
      inputParameters:
        - name: Registry-Version
          in: header
          value: "REGISTRY_VERSION"

  exposes:
    - type: mcp
      port: 3001
      namespace: shipyard-tools
      authentication:
        type: bearer
        token: "MCP_SERVER_TOKEN"
      tools:
        # ...unchanged from Step 2
~~~

`binds` loads secrets from a file during dev; in production you swap the `location` for a vault or omit it for env-var injection. The consumes-level `inputParameters` injects `Registry-Version` on every request automatically — no per-operation boilerplate.

Tool signatures don't change. Output shapes don't change. But behind the scenes the registry now returns 30+ fields on `get-ship`, and the MCP server rejects unauthenticated clients on port 3001. Step 4 will decide what to do with all that extra data.

Create `secrets.yaml` next to your capability:

~~~yaml
registry-bearer-token: "dummy-token"
registry-api-version: "1.0.0-alpha2"
mcp-server-token: "sk-mcp-YYYYYYYYYYYY"
~~~

> 🧭 **What you learned:** `binds`, `authentication` on both consumes and MCP expose, consumes-level `inputParameters`, symmetric secret injection.

---

## Step 4 — Shaping the ship card

**File:** `step-4-shipyard-output-shaping.yml` — [download](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/step-4-shipyard-output-shaping.yml)

Auth unlocked the firehose. 30+ fields on every `get-ship` call: year built, gross tonnage, length overall, beam, draft, classification society, certifications, crew assignments, port of registry… An agent asked *"tell me about Northern Star"* doesn't need all of that. It needs a **ship card**.

Rightsizing is the deal: auth gives you *everything*, shaping hands the agent *only what matters*. `MappedOutputParameter` is recursive — you nest an object inside an object, reach deep into the consumed payload with JSONPath, and drop the fields you don't care about.

~~~yaml
- name: get-ship
  call: registry.get-ship
  with:
    imo_number: shipyard-tools.imo
  outputParameters:
    - type: object
      properties:
        imo:     { type: string, mapping: "$.imo_number" }
        name:    { type: string, mapping: "$.vessel_name" }
        type:    { type: string, mapping: "$.vessel_type" }
        flag:    { type: string, mapping: "$.flag_code" }
        status:  { type: string, mapping: "$.operational_status" }
        specs:
          type: object
          properties:
            yearBuilt: { type: number, mapping: "$.year_built" }
            tonnage:   { type: number, mapping: "$.gross_tonnage" }
            length:    { type: number, mapping: "$.dimensions.length_overall" }
~~~

The key line is `mapping: "$.dimensions.length_overall"` — deep JSONPath reaches into the nested registry response and pulls out just the length. `list-ships` stays on the flat 5 fields (no point in specs for a list view).

~~~json
{
  "imo": "IMO-9321483",
  "name": "Northern Star",
  "type": "cargo",
  "flag": "NO",
  "status": "active",
  "specs": { "yearBuilt": 2015, "tonnage": 42000, "length": 229 }
}
~~~

Four steps in. Contract-first → wire → auth → shape. A clean progression.

> 🧭 **What you learned:** recursive `outputParameters`, deep JSONPath mapping, the rightsize pattern.

---

## Step 5 — Multi-source + XML conversion

**Files:** `step-5-shipyard-multi-source.yml`, `shared/step5-registry-consumes.yaml`, `shared/legacy-consumes.yaml`

- [step-5-shipyard-multi-source.yml](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/step-5-shipyard-multi-source.yml)
- [step5-registry-consumes.yaml](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/shared/step5-registry-consumes.yaml)
- [legacy-consumes.yaml](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/shared/legacy-consumes.yaml)

Data rarely sits in one place. The modern registry is clean, but older vessels still live in the **legacy Dockyard** — a system that predates JSON, speaks **XML**, and authenticates with an API key instead of a bearer token.

Two new skills to learn here. First, **`outputRawFormat: xml`**: the framework receives `Content-Type: application/xml`, parses the body, then applies your JSONPath mappings as if it had been JSON all along. The agent never knows XML was involved. Second, **consumes import**: we extract the registry block to a shared file so other capabilities can reuse it — DRY at the spec level.

~~~yaml
capability:
  consumes:
    - namespace: registry
      import: ./shared/step5-registry-consumes.yaml
    - namespace: legacy
      type: http
      baseUri: "https://legacy.dockyard.dev/api"
      authentication:
        type: apikey
        key: X-Dock-Key
        value: "DOCKYARD_API_KEY"
      resources:
        - name: vessels
          path: "/vessels"
          operations:
            - name: list-vessels
              method: GET
              outputRawFormat: xml
              inputParameters:
                - { name: status, in: query }
~~~

What the legacy Dockyard sends back:

~~~xml
<?xml version="1.0" encoding="UTF-8"?>
<vessels>
  <vessel>
    <vesselCode>LEGACY-4012</vesselCode>
    <vesselName>Old Faithful</vesselName>
    <category>cargo</category>
    <flagState>GB</flagState>
    <operationalStatus>active</operationalStatus>
  </vessel>
</vessels>
~~~

What the agent sees, after framework-level XML→JSON conversion and mapping:

~~~json
[
  { "vesselCode": "LEGACY-4012", "name": "Old Faithful", "type": "cargo",        "flag": "GB", "status": "active"  },
  { "vesselCode": "LEGACY-2087", "name": "Iron Maiden",  "type": "bulk_carrier", "flag": "PA", "status": "laid_up" }
]
~~~

Two tools now, one per source: `list-ships` (registry) and `list-legacy-vessels` (Dockyard). The framework doesn't offer a server-side merge step today — agents are great at merging client-side, and Step 8 will show you how to group both tools under one skill so the fan-out feels natural.

> 🔮 **Coming in beta.** Parallel step execution will let a future `list-fleet` tool fan out across multiple sources and unify results server-side. For now: two tools, one skill.

> 🧭 **What you learned:** multiple `consumes`, `import` + `location`, different auth types (bearer vs apikey), **`outputRawFormat: xml`**, API normalization at the mapping layer.

---

## Step 6 — Write operations + hints

**Files:** `step-6-shipyard-write-operations.yml`, `shared/step6-registry-consumes.yaml`, `shared/legacy-consumes.yaml`

- [step-6-shipyard-write-operations.yml](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/step-6-shipyard-write-operations.yml)
- [step6-registry-consumes.yaml](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/shared/step6-registry-consumes.yaml)
- [legacy-consumes.yaml](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/shared/legacy-consumes.yaml)

Captain Erik Lindström walks into the office. He wants to take the *Northern Star* from Oslo to Singapore. He's got his crew lined up, his cargo booked, the dates locked. Can the agent plan the voyage? Right now: no. Every tool so far is read-only — the agent can *observe* the shipyard, never *act* on it.

Time for the first write tool. `create-voyage` is a `POST` to the registry with 7 input parameters — including two arrays for crew and cargo. And since we're now mixing read and write, we give the agent's clients a way to distinguish them: **`hints`**, which map directly to MCP `ToolAnnotations`.

~~~yaml
- name: create-voyage
  description: "Plan a new voyage with ship, crew, route, and dates"
  inputParameters:
    - { name: shipImo,        type: string, required: true }
    - { name: departurePort,  type: string, required: true }
    - { name: arrivalPort,    type: string, required: true }
    - { name: departureDate,  type: string, required: true }
    - { name: arrivalDate,    type: string, required: true }
    - { name: crewIds,        type: array,  required: true,  description: "List of crew member IDs" }
    - { name: cargoIds,       type: array,  required: false, description: "List of cargo item IDs" }
  call: registry.create-voyage
  hints:
    readOnly: false
    destructive: false
    idempotent: false
    openWorld: true
~~~

`hints` tells any MCP client — Claude, Cursor, a custom orchestrator — that this tool *changes state*, that each call creates something new (non-idempotent), that it doesn't delete anything (non-destructive), and that it reaches out to an external system whose state can change independently (open world). A good client uses these to decide whether to confirm with the user before firing.

The response shapes the same way Step 4 shaped `get-ship` — flat registry fields become a clean nested structure:

~~~json
{
  "voyageId": "VOY-2026-042",
  "shipImo": "IMO-9321483",
  "route": { "from": "Oslo", "to": "Singapore" },
  "dates": { "departure": "2026-04-10", "arrival": "2026-05-02" },
  "crewIds": ["CREW-001", "CREW-003"],
  "cargoIds": ["CARGO-2024-0451"],
  "status": "planned"
}
~~~

Captain Erik has his voyage. The agent went from observer to operator.

> ℹ️ **Mock server note.** Scalar fields in the response (`voyageId`, `shipImo`, route, dates, status) are dynamically echoed from your request. Array fields (`crewIds`, `cargoIds`) return fixed example values — mock servers don't support dynamic array templating.

> 🧭 **What you learned:** `POST` consumed operations, array-type `inputParameters`, body mapping via `with`, **`hints`** → MCP `ToolAnnotations`, first write tool.

---

## Step 7 — Orchestrated lookup

**Files:** `step-7-shipyard-orchestrated-lookup.yml`, `shared/step7-registry-consumes.yaml`, `shared/legacy-consumes.yaml`

- [step-7-shipyard-orchestrated-lookup.yml](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/step-7-shipyard-orchestrated-lookup.yml)
- [step7-registry-consumes.yaml](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/shared/step7-registry-consumes.yaml)
- [legacy-consumes.yaml](https://raw.githubusercontent.com/Ikanos/ikanos/refs/tags/v1.0.0-alpha2/src/main/resources/tutorial/shared/legacy-consumes.yaml)

Back to the captain. He insists on his cook: *"No Aiko, no departure."* The agent calls `get-ship` for the Northern Star and gets back `assignedCrew: ["CREW-001", "CREW-003"]`. Raw IDs. Useless. Who is CREW-003? Is that Aiko?

The agent *could* call `list-crew`, cross-reference in its head, and answer. But that's one more round-trip, one more chunk of context to hold, and it doesn't scale — imagine doing that for 50 crew members across 20 ships. The better answer is server-side enrichment: the framework calls both APIs, joins them internally, and hands the agent the finished picture in a single tool call. This is **orchestrated mode**.

~~~yaml
- name: get-ship-with-crew
  description: "Get ship details with resolved crew names"
  inputParameters:
    - name: imo
      type: string
      required: true
  steps:
    - name: get-ship
      type: call
      call: registry.get-ship
      with:
        imo_number: shipyard-tools.imo
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

Three steps replace the simple `call`: fetch the ship, fetch the crew roster, **lookup** — a server-side JOIN that matches `crewId` against the ship's `assignedCrew` array and plucks `fullName` + `role`. `mappings` at the bottom wires the individual step outputs into the final response shape.

~~~json
{
  "imo": "IMO-9321483",
  "name": "Northern Star",
  "type": "cargo",
  "flag": "NO",
  "status": "active",
  "specs": { "yearBuilt": 2015, "tonnage": 42000, "length": 229 },
  "crew": [
    { "fullName": "Erik Lindström", "role": "captain" },
    { "fullName": "Aiko Tanaka",   "role": "cook" }
  ]
}
~~~

Aiko is on board. The captain is happy. And the Fleet Manifest that closes Part 2 will chain *seven* such steps.

> 🧭 **What you learned:** `steps`, `type: call` and `type: lookup`, `index` / `match` / `lookupValue`, `mappings` for step-output assembly, server-side enrichment.

---

## What you built

Over 7 steps, one YAML file grew from a bare contract with no backend into a multi-source, authenticated, write-capable, orchestrated agent platform:

**5 MCP tools** — `get-ship`, `list-ships`, `list-legacy-vessels`, `create-voyage`, `get-ship-with-crew`

**2 consumed APIs** — the Maritime Registry (bearer auth, JSON) and the Legacy Dockyard (API key, XML)

**Both doors locked** — clients authenticate to MCP, MCP authenticates to the registry

**Progression** — contract-first → wire → auth → shape → multi-source → write → lookup

No code. Welcome to Spec-Driven Integration.

## Going further

Ready to expose your tools as **Agent Skills**, factor logic into reusable **aggregates**, add a **REST front door**, and assemble a full **Fleet Manifest** capstone?

Continue with [Tutorial — Part 2](https://github.com/naftiko/ikanos/wiki/Tutorial-%E2%80%90-Part-2).