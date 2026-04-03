# The Shipyard - Tutorial - Part 1

You run a maritime shipping company. You have ships, crews, voyages, cargo — and a handful of REST APIs that hold all that data. What you don't have is an AI agent that can actually *uses* them.

That's what we're going to build. Step-by-step, you'll go from zero to an operational agent capability: listing ships, planning voyages, resolving crew by name, and assembling a complete fleet manifest — all described in a single YAML file that Naftiko turns into live MCP tools, REST endpoints, and agent skills.

No code. Just a spec. Let's go.

> **Prerequisites:** Make sure you can run the Naftiko Engine. See the [installation instructions](https://github.com/naftiko/framework/wiki/Installation). All capability files for this tutorial live in `src/main/resources/tutorial/`.

---

## Step 1 — Your first MCP tool

**`step-1-shipyard-first-capability.yml`**

> 📥 [step-1-shipyard-first-capability.yml](https://raw.githubusercontent.com/naftiko/framework/refs/tags/v1.0.0-alpha1/src/main/resources/tutorial/step-1-shipyard-first-capability.yml)

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

Run the engine and connect an MCP client to `localhost:3001`. You can use the [MCP Inspector](https://github.com/modelcontextprotocol/inspector) to browse tools and call them interactively — it's the fastest way to test during development. Call `list-ships`. You get:

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

> 📥 [step-2-shipyard-input-parameters.yml](https://raw.githubusercontent.com/naftiko/framework/refs/tags/v1.0.0-alpha1/src/main/resources/tutorial/step-2-shipyard-input-parameters.yml)

An agent that can only list *all* ships isn't very useful. We need two things: a way to **filter** the list (by status), and a way to **look up** a specific ship (by IMO number).

```yaml
tools:
  - name: get-ship
    description: "Retrieve a ship's details by IMO number"
    inputParameters:
      - name: imo
        type: string
        required: true
        description: "IMO number of the ship"
    call: registry.get-ship
    with:
      imo_number: "{{imo}}"
```

The `with` keyword is the bridge: the agent says `imo`, the consumed API expects `imo_number` — `with` maps one to the other. On the consumes side, `status` becomes a query parameter (`GET /ships?status=active`) and `imo_number` fills a path template (`GET /ships/IMO-9321483`).

Now the agent can ask: *"Show me only the active ships"* and *"Tell me about the Northern Star."*

```json
{ "imo": "IMO-9321483", "name": "Northern Star", "type": "cargo", "flag": "NO", "status": "active" }
```

**What you learned:** `inputParameters`, `with`, required vs optional, path vs query parameters.

---

## Step 3 — Binding secrets

**`step-3-shipyard-auth-and-binds.yml`**

> 📥 [step-3-shipyard-auth-and-binds.yml](https://raw.githubusercontent.com/naftiko/framework/refs/tags/v1.0.0-alpha1/src/main/resources/tutorial/step-3-shipyard-auth-and-binds.yml)

So far, we've been hitting the registry's **public endpoints** — they return 5 basic fields per ship. But the registry has much more: specs, dimensions, tonnage, crew assignments, certifications. That data sits behind a bearer token.

```yaml
binds:
  - namespace: registry-env
    location: "file:///./shared/secrets.yaml"
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

## Step 4 — Shaping the tool output

**`step-4-shipyard-output-shaping.yml`**

> 📥 [step-4-shipyard-output-shaping.yml](https://raw.githubusercontent.com/naftiko/framework/refs/tags/v1.0.0-alpha1/src/main/resources/tutorial/step-4-shipyard-output-shaping.yml)

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

## Step 5 — Consuming multiple APIs

**`step-5-shipyard-multi-source.yml`** — Consumes: `shared/step5-registry-consumes.yaml`, `shared/legacy-consumes.yaml`

> 📥 [step-5-shipyard-multi-source.yml](https://raw.githubusercontent.com/naftiko/framework/refs/tags/v1.0.0-alpha1/src/main/resources/tutorial/step-5-shipyard-multi-source.yml)
> 📥 [step5-registry-consumes.yaml](https://raw.githubusercontent.com/naftiko/framework/refs/tags/v1.0.0-alpha1/src/main/resources/tutorial/shared/step5-registry-consumes.yaml)
> 📥 [legacy-consumes.yaml](https://raw.githubusercontent.com/naftiko/framework/refs/tags/v1.0.0-alpha1/src/main/resources/tutorial/shared/legacy-consumes.yaml)

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

  exposes:
    - type: mcp
      address: "0.0.0.0" # Needed in Docker context. Permits to access the MCP with localhost from outside the container.
      port: 3001
      namespace: shipyard-tools
      tools:
        - name: list-legacy-vessels
          description: "List vessels from the legacy dockyard"
          call: legacy.list-vessels
          outputParameters:
            - type: array
              mapping: "$."
              items:
                type: object
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

## Step 6 — Write operations and body templates

**`step-6-shipyard-write-operations.yml`** — Consumes: `shared/step6-registry-consumes.yaml`, `shared/legacy-consumes.yaml`

> 📥 [step-6-shipyard-write-operations.yml](https://raw.githubusercontent.com/naftiko/framework/refs/tags/v1.0.0-alpha1/src/main/resources/tutorial/step-6-shipyard-write-operations.yml)
> 📥 [step6-registry-consumes.yaml](https://raw.githubusercontent.com/naftiko/framework/refs/tags/v1.0.0-alpha1/src/main/resources/tutorial/shared/step6-registry-consumes.yaml)
> 📥 [legacy-consumes.yaml](https://raw.githubusercontent.com/naftiko/framework/refs/tags/v1.0.0-alpha1/src/main/resources/tutorial/shared/legacy-consumes.yaml)

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

> **Note:** All scalar fields in the response (`voyageId`, `shipImo`, `route`, `dates`, `status`) are dynamically echoed from your request by the mock server. Array fields (`crewIds`, `cargoIds`) are an exception — mock servers don't support dynamic array templating, so they return fixed example values instead.

The agent went from observer to operator.

> **💡 Tip: Tool hints.** Now that you have both read-only tools (`list-ships`, `get-ship`) and write tools (`create-voyage`), you can declare behavioral hints that help clients distinguish them:
> ```yaml
> - name: list-ships
>   hints:
>     readOnly: true
>   # ...
> - name: create-voyage
>   hints:
>     readOnly: false
>     destructive: false
>     idempotent: false
>     openWorld: true
>   # ...
> ```
> Hints map to MCP `ToolAnnotations` and are advisory — clients use them to decide which tools need confirmation, can be retried safely, etc. See [McpToolHints](https://github.com/naftiko/framework/wiki/Specification-Schema#3551-mctoolhints-object) in the spec.

**What you learned:** `POST` operations, `body` template, array-type inputs, write tools.

---

## Step 7 — Orchestrated lookups

**`step-7-shipyard-orchestrated-lookup.yml`** — Consumes: `shared/step7-registry-consumes.yml`, `shared/legacy-consumes.yaml`

> 📥 [step-7-shipyard-orchestrated-lookup.yml](https://raw.githubusercontent.com/naftiko/framework/refs/tags/v1.0.0-alpha1/src/main/resources/tutorial/step-7-shipyard-orchestrated-lookup.yml)
> 📥 [step7-registry-consumes.yaml](https://raw.githubusercontent.com/naftiko/framework/refs/tags/v1.0.0-alpha1/src/main/resources/tutorial/shared/step7-registry-consumes.yaml)
> 📥 [legacy-consumes.yaml](https://raw.githubusercontent.com/naftiko/framework/refs/tags/v1.0.0-alpha1/src/main/resources/tutorial/shared/legacy-consumes.yaml)

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

## What you built

Over 7 steps, your single YAML capability grew from a 15-line wrapper around `GET /ships` into a multi-source, write-capable, orchestrated agent platform:

**5 MCP tools** — `list-ships`, `get-ship`, `list-legacy-vessels`, `create-voyage`, `get-ship-with-crew`

**2 consumed APIs** — the Maritime Registry (bearer auth, 5 operations) and the Legacy Dockyard (API key, 1 operation)

All from one spec. No code. Welcome to Spec-Driven Integration.

---

## Going further

Ready to expose your tools as **agent skills**, add a **REST front door**, and assemble a full **Fleet Manifest** with multi-step orchestration?

Continue with the [Tutorial - Part 2](Tutorial-%E2%80%90-Part-2).
