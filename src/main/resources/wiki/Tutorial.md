# The Shipyard

You run a maritime shipping company. You have ships, crews, voyages, cargo — and a handful of REST APIs that hold all that data. What you don't have is an AI agent that can actually *use* them.

That's what we're going to build. In 10 steps, you'll go from zero to a fully operational agent capability: listing ships, planning voyages, resolving crew by name, and assembling a complete fleet manifest — all described in a single YAML file that Naftiko turns into live MCP tools, REST endpoints, and agent skills.

No code. Just a spec. Let's go.

> **Prerequisites:** Make sure you can run the Naftiko Engine. See the [installation instructions](https://github.com/naftiko/framework/wiki/Installation). All capability files for this tutorial live in `src/main/resources/schemas/tutorial/`.

---

## Step 1 — Your first tool

**`step-1-shipyard-first-capability.yml`**

The Maritime Registry at `registry.shipyard.dev` has a REST endpoint: `GET /ships`. It returns a list of ships. We want an agent to be able to call it. That's it — the absolute minimum.

A capability needs two things: something to **consume** (the API) and something to **expose** (the tool).

```yaml
naftiko: "1.0.0-alpha1"

capability:
  consumes:
    - namespace: registry
      type: http
      baseUri: "https://registry.shipyard.dev/api/v1"
      resources:
        - name: ships
          path: "/ships"
          operations:
            - name: list-ships
              method: GET

  exposes:
    - type: mcp
      port: 3001
      namespace: shipyard-tools
      description: "Shipyard MCP tools for fleet management"
      tools:
        - name: list-ships
          description: "List ships in the shipyard"
          call: registry.list-ships
          outputParameters:
            - type: array
              mapping: "$."
              items:
                type: object
```

Run the engine, connect an MCP client to `localhost:3001`, call `list-ships`. You get:

```json
[
  { "imoNumber": "IMO-9321483", "vesselName": "Northern Star", "vesselType": "cargo", "flagCode": "NO", "operationalStatus": "active" },
  { "imoNumber": "IMO-9456781", "vesselName": "Pacific Dawn", "vesselType": "tanker", "flagCode": "SG", "operationalStatus": "maintenance" }
]
```

That's your first tool. `consumes` declares where the data lives, `exposes` declares what the agent sees. `call: registry.list-ships` is the wire between the two. The `outputParameters` with `mapping` rename fields from the API's snake_case (`imo_number`) to camelCase (`imoNumber`) — the agent never sees the raw API shape.

**What you learned:** `consumes`, `exposes`, `type: mcp`, `call`, `outputParameters`, `mapping`.

---

## Step 2 — Taking inputs

**`step-2-shipyard-input-parameters.yml`**

An agent that can only list *all* ships isn't very useful. We need two things: a way to **filter** the list (by status), and a way to **look up** a specific ship (by IMO number).

```yaml
tools:
  - name: list-ships
    description: "List ships in the shipyard, optionally filtered by status"
    inputParameters:
      - name: status
        type: string
        required: false
        description: "Filter by operational status"
    call: registry.list-ships
    with:
      status: shipyard-tools.status

  - name: get-ship
    description: "Retrieve a ship's details by IMO number"
    inputParameters:
      - name: imo
        type: string
        required: true
        description: "IMO number of the ship"
    call: registry.get-ship
    with:
      imo_number: shipyard-tools.imo
```

The `with` keyword is the bridge: the agent says `imo`, the consumed API expects `imo_number` — `with` maps one to the other. On the consumes side, `status` becomes a query parameter (`GET /ships?status=active`) and `imo_number` fills a path template (`GET /ships/IMO-9321483`).

Now the agent can ask: *"Show me only the active ships"* and *"Tell me about the Northern Star."*

```json
{ "imo": "IMO-9321483", "name": "Northern Star", "type": "cargo", "flag": "NO", "status": "active" }
```

**What you learned:** `inputParameters`, `with`, required vs optional, path vs query parameters.

---

## Step 3 — Unlocking the full registry

**`step-3-shipyard-auth-and-binds.yml`**

So far, we've been hitting the registry's **public endpoints** — they return 5 basic fields per ship. But the registry has much more: specs, dimensions, tonnage, crew assignments, certifications. That data sits behind a bearer token.

```yaml
binds:
  - namespace: registry-env
    location: "file:///./secrets.yaml"
    keys:
      REGISTRY_TOKEN: "registry-bearer-token"
      REGISTRY_VERSION: "registry-api-version"

capability:
  consumes:
    - namespace: registry
      type: http
      baseUri: "https://registry.shipyard.dev/api/v1"
      authentication:
        type: bearer
        token: "{{REGISTRY_TOKEN}}"
      inputParameters:
        - name: Registry-Version
          in: header
          value: "{{REGISTRY_VERSION}}"
```

`binds` loads secrets from a file (in production, from a vault or env vars). `authentication` adds the token to every request. The consumes-level `inputParameters` inject a version header on all operations automatically.

The tools themselves don't change — same `list-ships`, same `get-ship`, same signatures. But behind the scenes, the registry now returns 30+ fields instead of 5. We'll deal with that next.

Create a `secrets.yaml` next to your capability:
```yaml
REGISTRY_TOKEN: "sk-registry-XXXXXXXXXXXX"
REGISTRY_VERSION: "2024-01-01"
```

**What you learned:** `binds`, `authentication`, consumes-level `inputParameters`, secret injection.

---

## Step 4 — The ship card

**`step-4-shipyard-output-shaping.yml`**

Now `get-ship` returns everything: year built, gross tonnage, length overall, beam, draft, classification society, certifications, crew assignments… An agent asking *"tell me about Northern Star"* doesn't need 30 fields. It needs a **ship card**.

```yaml
- name: get-ship
  call: registry.get-ship
  with:
    imo_number: shipyard-tools.imo
  outputParameters:
    - type: object
      properties:
        imo:
          type: string
          mapping: "$.imo_number"
        name:
          type: string
          mapping: "$.vessel_name"
        type:
          type: string
          mapping: "$.vessel_type"
        flag:
          type: string
          mapping: "$.flag_code"
        status:
          type: string
          mapping: "$.operational_status"
        specs:
          type: object
          properties:
            yearBuilt:
              type: number
              mapping: "$.year_built"
            tonnage:
              type: number
              mapping: "$.gross_tonnage"
            length:
              type: number
              mapping: "$.dimensions.length_overall"
```

The `specs` nested object is the key: `mapping: $.dimensions.length_overall` reaches deep into the API response and pulls out just the length. Auth gave us *everything*, shaping gives the agent *only what it needs*.

```json
{
  "imo": "IMO-9321483",
  "name": "Northern Star",
  "type": "cargo",
  "flag": "NO",
  "status": "active",
  "specs": { "yearBuilt": 2015, "tonnage": 42000, "length": 229 }
}
```

**What you learned:** Nested `outputParameters`, deep JSONPath mapping, the rightsize pattern.

---

## Step 5 — A second registry

**`step-5-shipyard-multi-source.yml`** — Consumes: `shared/step5-registry-consumes.yaml`, `shared/legacy-consumes.yaml`

Data rarely lives in one place. The Shipyard's modern registry is clean, but there's also a **legacy Dockyard** — an older system with records for vessels that were never migrated. Different API, different auth (API key instead of bearer), different field names.

We want the agent to search both. But Naftiko can't merge two list results into one tool today — so each source gets its own tool. The agent calls both and merges on its side (which agents are great at).

This step also introduces **consumes import**: the registry definition is extracted to `shared/step5-registry-consumes.yaml` so it can be reused across capabilities without copy-paste.

```yaml
capability:
  consumes:
    - import: registry
      location: ./shared/step5-registry-consumes.yaml
    - import: legacy
      location: ./shared/legacy-consumes.yaml
```

New tool: `list-legacy-vessels` — same pattern as `list-ships`, different source:

```json
[
  { "vesselCode": "LEGACY-4012", "name": "Old Faithful", "type": "cargo", "flag": "GB", "status": "active" },
  { "vesselCode": "LEGACY-2087", "name": "Iron Maiden", "type": "bulk_carrier", "flag": "PA", "status": "laid_up" }
]
```

**What you learned:** Multiple `consumes`, `import` + `location`, different auth types, API normalization.

---

## Step 6 — The agent gets hands

**`step-6-shipyard-write-operations.yml`** — Consumes: `shared/step6-registry-consumes.yaml`, `shared/legacy-consumes.yaml`

Until now, every tool was read-only. List, get, inspect. But Captain Erik Lindström wants to **plan a voyage** — Oslo to Singapore, aboard the Northern Star, with his crew and cargo. The agent needs to *act*.

`create-voyage` is the first **write** tool — a `POST` to the registry with 7 input parameters, including arrays for crew and cargo IDs:

```yaml
- name: create-voyage
  description: "Plan a new voyage with ship, crew, route, and dates"
  inputParameters:
    - name: shipImo
      type: string
      required: true
    - name: departurePort
      type: string
      required: true
    - name: arrivalPort
      type: string
      required: true
    - name: departureDate
      type: string
      required: true
    - name: arrivalDate
      type: string
      required: true
    - name: crewIds
      type: array
      required: true
      description: "List of crew member IDs"
    - name: cargoIds
      type: array
      required: false
      description: "List of cargo item IDs"
  call: registry.create-voyage
  with:
    shipImo: shipyard-tools.shipImo
    departurePort: shipyard-tools.departurePort
    arrivalPort: shipyard-tools.arrivalPort
    departureDate: shipyard-tools.departureDate
    arrivalDate: shipyard-tools.arrivalDate
    crewIds: shipyard-tools.crewIds
    cargoIds: shipyard-tools.cargoIds
```

The response gets shaped too — flat fields like `departurePort`/`arrivalPort` become a clean `route` object:

```json
{
  "voyageId": "VOY-2026-042",
  "shipImo": "IMO-9321483",
  "route": { "from": "Oslo", "to": "Singapore" },
  "dates": { "departure": "2026-04-10", "arrival": "2026-05-02" },
  "crewIds": ["CREW-001", "CREW-003"],
  "cargoIds": ["CARGO-2024-0451"],
  "status": "planned"
}
```

The agent went from observer to operator.

**What you learned:** `POST` operations, `body` template, array-type inputs, write tools.

---

## Step 7 — Organizing the toolbox

**`step-7-shipyard-skill-groups.yml`**

Four tools and growing. A flat list works for now, but a real shipyard would have dozens. **Skills** group tools by business domain — so the agent discovers *capabilities*, not individual operations.

```yaml
- type: skill
  port: 3002
  namespace: shipyard-skills
  description: "Shipyard skill groups for structured agent discovery"
  skills:
    - name: fleet-ops
      description: "Fleet management — list, search, and inspect ships across all registries"
      tools:
        - name: list-ships
          from:
            sourceNamespace: shipyard-tools
            action: list-ships
        - name: get-ship
          from:
            sourceNamespace: shipyard-tools
            action: get-ship
        - name: list-legacy-vessels
          from:
            sourceNamespace: shipyard-tools
            action: list-legacy-vessels
    - name: voyage-ops
      description: "Voyage planning — create and manage voyages"
      tools:
        - name: create-voyage
          from:
            sourceNamespace: shipyard-tools
            action: create-voyage
```

Skills don't redefine logic — they reference existing MCP tools via `from`. When the agent asks *"what can I do with voyages?"*, the skill layer answers instantly. Think of it as a table of contents for your toolbox.

**What you learned:** `type: skill`, `skills`, `from` referencing, business-level discovery.

---

## Step 8 — A REST front door

**`step-8-shipyard-rest-adapter.yml`**

Not every consumer is an AI agent. Partner systems, dashboards, and mobile apps still speak REST. One capability, two front doors:

```yaml
- type: rest
  port: 3002
  namespace: shipyard-api
  resources:
    - name: ships
      path: "/ships"
      operations:
        - name: list-ships
          method: GET
          call: shipyard-tools.list-ships
    - name: ship-by-imo
      path: "/ships/{{imo}}"
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
```

Same `call` + `with`, same consumes wiring — different protocol. The REST adapter is additive: it doesn't change the MCP or skill exposes. Three expose blocks, one capability.

**What you learned:** `type: rest`, REST resources/operations, MCP ↔ REST coexistence.

---

## Step 9 — Names, not IDs

**`step-9-shipyard-orchestrated-lookup.yml`** — Consumes: `shared/step9-registry-consumes.yml`, `shared/legacy-consumes.yaml`

Captain Erik is planning Oslo → Singapore. He insists on his cook: *"No Aiko, no departure."* The agent calls `get-ship` — but gets `assignedCrew: ["CREW-001", "CREW-003"]`. Raw IDs. Useless. Who is CREW-003? The captain needs *names*.

Instead of forcing the agent to call a second API and cross-reference, we enrich **server-side**. This is **orchestrated mode**: a tool with `steps` instead of a simple `call`.

```yaml
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
```

Three steps: (1) fetch the ship, (2) fetch the crew roster, (3) **lookup** — a server-side JOIN that matches `crewId` against the ship's `assignedCrew` array and pulls out `fullName` and `role`. One tool call, zero agent round-trips.

```json
{
  "imo": "IMO-9321483",
  "name": "Northern Star",
  "type": "cargo",
  "flag": "NO",
  "status": "active",
  "specs": { "yearBuilt": 2015, "tonnage": 42000, "length": 229 },
  "crew": [
    { "fullName": "Erik Lindström", "role": "captain" },
    { "fullName": "Aiko Tanaka", "role": "cook" }
  ]
}
```

Aiko is on board. The captain is happy.

**What you learned:** `steps`, `type: lookup`, `index`/`match`/`lookupValue`, `mappings`.

---

## Step 10 — The Fleet Manifest

**`step-10-shipyard-fleet-manifest.yml`** — Consumes: `shared/step10-registry-consumes.yml`, `shared/legacy-consumes.yaml`

The voyage is planned (Step 6). The crew is confirmed (Step 9). Now the operations team needs one document that has everything: ship details, crew names, cargo inventory — all resolved from raw IDs, all assembled server-side. The **Fleet Manifest**.

`get-voyage-manifest` is the capstone: **7 steps** — 4 API calls and 3 lookups:

```yaml
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
```

One tool call. One complete manifest:

```json
{
  "voyageId": "VOY-2026-042",
  "status": "planned",
  "route": { "from": "Oslo", "to": "Singapore" },
  "ship": { "name": "Northern Star", "type": "cargo", "flag": "NO" },
  "crew": [
    { "fullName": "Erik Lindström", "role": "captain" },
    { "fullName": "Aiko Tanaka", "role": "cook" }
  ],
  "cargo": [
    { "type": "container", "description": "Electronics components", "weight": 12.5, "hazardous": false },
    { "type": "bulk", "description": "Iron ore samples", "weight": 85.0, "hazardous": false }
  ]
}
```

This step also adds `info` metadata (label, description, tags, dates) — because a production capability deserves a proper identity. And it updates the skill and REST layers one last time: `get-voyage-manifest` joins `voyage-ops`, and a new `GET /voyages/{voyageId}/manifest` endpoint appears.

Everything from Steps 1–9 converges here. Contract-first → wire → auth → shape → multi-source → write → skills → REST → lookup → **full orchestration.** That's the Shipyard.

**What you learned:** Multi-lookup chaining, `info` metadata, consumes-level `outputParameters`, and that a complete maritime agent fits in a single YAML file.

---

## What you built

Over 10 steps, your single YAML capability grew from a 15-line wrapper around `GET /ships` into a full agent platform:

**6 MCP tools** — `list-ships`, `get-ship`, `list-legacy-vessels`, `create-voyage`, `get-ship-with-crew`, `get-voyage-manifest`

**2 skills** — `fleet-ops` (4 tools), `voyage-ops` (2 tools)

**6 REST endpoints** — `GET /ships`, `GET /ships/{imo}`, `GET /legacy/vessels`, `POST /voyages`, `GET /ships/{imo}/crew`, `GET /voyages/{voyageId}/manifest`

**2 consumed APIs** — the Maritime Registry (bearer auth, 6 operations) and the Legacy Dockyard (API key, 1 operation)

All from one spec. No code. Welcome to Spec-Driven Integration.