# The Shipyard - Tutorial - Part 2

> **Heads up — work in progress.** This part of the tutorial is published as a teaser. The capability files and concepts described here are not yet fully supported by the engine. They will become functional in an upcoming release.

This track picks up where the [Tutorial - Part 1](Tutorial-MCP-Part-1.md) left off. Complete Steps 1–7 of that tutorial before continuing here.

Three topics are covered: exposing tools into **Agent Skills**, exposing the same capability as a **REST API**, and assembling a full **Fleet Manifest** using multi-steps orchestration.

---

## Step 1 — Exposing tools as Agent Skills

**`step-8-shipyard-skill-groups.yml`**

> 📥 [step-8-shipyard-skill-groups.yml](../tutorial/step-8-shipyard-skill-groups.yml)

MCP already gives an AI agent direct access to tools, inputs, and outputs. That is enough to execute operations, but not always enough to help the agent understand how a growing toolset is organized. **Agent Skills** add a business-facing layer on top of MCP: they group related tools, reduce discovery noise, and give the agent clearer context for planning the next action. For the broader model and specification, see the [Agent Skills website](https://agentskills.io/).

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

### Discovering and installing skills remotely

Once the engine is running, the SKILL adapter auto-exposes a read-only HTTP API that any remote agent can use to browse, inspect, and download skills — no manual file sharing required.

| Endpoint | What it returns |
|---|---|
| `GET /skills` | Catalog — count and summary of every skill with its tool names |
| `GET /skills/{name}` | Full metadata, tool catalog, and `invocationRef` for each tool |
| `GET /skills/{name}/download` | ZIP archive of the skill's `location` directory |
| `GET /skills/{name}/contents` | File listing of the skill's `location` directory |
| `GET /skills/{name}/contents/{file}` | Individual file from the skill's `location` directory |

A typical agent workflow looks like this:

1. **Discover** — call `GET http://localhost:3002/skills` to list available skills.
2. **Inspect** — call `GET /skills/fleet-ops` to see what tools the skill offers and how to invoke them (the `invocationRef` tells the agent which sibling MCP or REST namespace to call).
3. **Install** — call `GET /skills/fleet-ops/download` to pull a ZIP of the skill's instruction files. The agent extracts them locally and is ready to use the skill.

Because the API is plain HTTP/JSON, it works for any client — another Naftiko capability, a custom orchestrator, or an AI agent that speaks HTTP.

**What you learned:** `type: skill`, `skills`, `from` referencing, business-level discovery, skill API endpoints.

---

## Step 2 — A REST front door

**`step-9-shipyard-rest-adapter.yml`**

> 📥 [step-9-shipyard-rest-adapter.yml](../tutorial/step-9-shipyard-rest-adapter.yml)

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

## Step 3 — Multi-step orchestration

**`step-10-shipyard-fleet-manifest.yml`** — Consumes: `shared/step10-registry-consumes.yml`, `shared/legacy-consumes.yaml`

> 📥 [step-10-shipyard-fleet-manifest.yml](../tutorial/step-10-shipyard-fleet-manifest.yml)

The voyage is planned (tutorial Part 1, Step 6). The crew is confirmed (tutorial Part 1, Step 7). Now the operations team needs one document that has everything: ship details, crew names, cargo inventory — all resolved from raw IDs, all assembled capabilty-side. The **Fleet Manifest**.

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

This step also adds `info` metadata (label, description, tags, dates) — because a production capability deserves a proper identity. And it updates the SKILL and REST layers one last time: `get-voyage-manifest` joins `voyage-ops`, and a new `GET /voyages/{voyageId}/manifest` endpoint appears.

Everything from the main tutorial, plus SKILL and REST, converges here. Contract-first → wire → auth → shape → multi-source → write → lookup → skills → REST → **full orchestration.** That's the Shipyard.

**What you learned:** Multi-lookup chaining, `info` metadata, consumes-level `outputParameters`, and that a complete maritime agent fits in a single YAML file.
