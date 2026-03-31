# Google Sheets to Domain-Specific JSON Array
## Spreadsheet Data Mediation via the REST & MCP Server Adapters

**Status**: Use Case  
**Date**: March 27, 2026  
**Key Concept**: Consume spreadsheet data from the Google Sheets API and expose it as a typed, domain-specific JSON array through the REST and MCP server adapters — using positional JsonPath mappings to transform raw row arrays into named object properties. No Java code required.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [How the Google Sheets API Returns Data](#how-the-google-sheets-api-returns-data)
3. [Mapping Strategy: Positional JsonPath](#mapping-strategy-positional-jsonpath)
4. [Full Capability YAML](#full-capability-yaml)
5. [Walkthrough](#walkthrough)
6. [MCP Adapter Variant](#mcp-adapter-variant)
7. [Adapting to a Different Spreadsheet](#adapting-to-a-different-spreadsheet)
8. [Alternative: Dynamic Range via Input Parameters](#alternative-dynamic-range-via-input-parameters)

---

## Executive Summary

### Problem

Spreadsheets are a common source of structured data (team rosters, inventories, schedules), but the Google Sheets API returns rows as raw arrays of strings — not typed, named objects. Exposing this data in a domain-specific JSON shape typically requires custom glue code.

### Solution

A single Naftiko capability consumes the Sheets API, maps each column position to a named field via JsonPath, and exposes the result through the REST adapter, the MCP adapter, or both. The output is a clean JSON array of domain objects — ready for downstream consumption by APIs or AI agents. The `outputRawFormat` defaults to `json` since the Sheets API already returns JSON natively.

### Key Benefit

Column-to-field mapping is declared in YAML. When the spreadsheet schema changes (columns added, reordered), the capability YAML is the only thing that needs updating.

---

## How the Google Sheets API Returns Data

The `GET /v4/spreadsheets/{id}/values/{range}` endpoint returns:

```json
{
  "range": "Sheet1!A2:D4",
  "majorDimension": "ROWS",
  "values": [
    ["Alice Chen", "Engineer", "alice@co.com", "Paris"],
    ["Bob Silva", "Designer", "bob@co.com", "Lisbon"],
    ["Carol Wu", "PM", "carol@co.com", "Tokyo"]
  ]
}
```

Each inner array is a row. There are no field names — column semantics are positional.

---

## Mapping Strategy: Positional JsonPath

The `outputParameters` on the exposed operation use:

- `mapping: "$.values"` on the outer array — selects the rows.
- `mapping: "$[0]"`, `"$[1]"`, etc. on each property — selects columns by index.

This turns each row array into a typed object:

```
["Alice Chen", "Engineer", "alice@co.com", "Paris"]
        ↓                ↓              ↓           ↓
   $[0] → name     $[1] → role    $[2] → email  $[3] → office
```

---

## Full Capability YAML

```yaml
# yaml-language-server: $schema=../naftiko-schema.json
---
naftiko: "1.0.0-alpha1"

info:
  label: "Team Roster — Google Sheets"
  description: "Reads a team roster spreadsheet from Google Sheets and exposes it as a domain-specific JSON array via REST and MCP. Assumes columns: Name | Role | Email | Office."
  tags:
    - google-sheets
    - team
    - roster
  created: "2026-03-27"
  modified: "2026-03-27"
  stakeholders:
    - role: "owner"
      fullName: "Jane Doe"
      email: "jane.doe@example.com"

binds:
  - namespace: "google-env"
    description: "Google API key for reading public or shared spreadsheets via the Sheets API v4."
    location: "file:///path/to/google_env.json"
    keys:
      GOOGLE_API_KEY: "API_KEY"

capability:
  exposes:
    - type: "rest"
      address: "localhost"
      port: 9090
      namespace: "roster"

      resources:
        - path: "/team"
          name: "team"
          label: "Team Roster"
          description: "Returns the team roster spreadsheet as a JSON array of team members, each with name, role, email, and office."
          operations:
            - method: "GET"
              call: "google-sheets.get-range"
              with:
                spreadsheet_id: "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgVE2upms"
                range: "Sheet1!A2:D100"
              outputParameters:
                - type: "array"
                  mapping: "$.values"
                  items:
                    type: "object"
                    properties:
                      name:
                        type: "string"
                        mapping: "$[0]"
                      role:
                        type: "string"
                        mapping: "$[1]"
                      email:
                        type: "string"
                        mapping: "$[2]"
                      office:
                        type: "string"
                        mapping: "$[3]"

    - type: "mcp"
      address: "localhost"
      port: 9091
      namespace: "roster-mcp"
      description: "MCP server exposing the team roster from Google Sheets for AI agent discovery and consumption."

      tools:
        - name: "get-team-roster"
          description: "Retrieve the team roster from a Google Sheets spreadsheet. Returns each team member with their name, role, email, and office location."
          call: "google-sheets.get-range"
          with:
            spreadsheet_id: "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgVE2upms"
            range: "Sheet1!A2:D100"
          outputParameters:
            - type: "array"
              mapping: "$.values"
              items:
                type: "object"
                properties:
                  name:
                    type: "string"
                    mapping: "$[0]"
                  role:
                    type: "string"
                    mapping: "$[1]"
                  email:
                    type: "string"
                    mapping: "$[2]"
                  office:
                    type: "string"
                    mapping: "$[3]"

  consumes:
    - type: "http"
      namespace: "google-sheets"
      description: "Google Sheets API v4 integration for reading spreadsheet data."
      baseUri: "https://sheets.googleapis.com/v4"

      resources:
        - path: "/spreadsheets/{{spreadsheet_id}}/values/{{range}}"
          name: "values"
          label: "Sheet Values"
          operations:
            - method: "GET"
              name: "get-range"
              label: "Get Range Values"
              inputParameters:
                - name: "spreadsheet_id"
                  in: "path"
                - name: "range"
                  in: "path"
                - name: "key"
                  in: "query"
                  value: "{{GOOGLE_API_KEY}}"
```

---

## Walkthrough

### Consumes Side

| Element | Purpose |
|---|---|
| `baseUri` | Points to Google Sheets API v4 |
| `path` with `{{spreadsheet_id}}` and `{{range}}` | Templated path parameters injected at call time |
| `key` query parameter | Injects the API key from `binds` via `{{GOOGLE_API_KEY}}` |
| No `outputRawFormat` | Defaults to `json` — the Sheets API returns JSON natively |

### Exposes Side

| Element | Purpose |
|---|---|
| `call: "google-sheets.get-range"` | Simple mode — one consumed operation, no orchestration needed |
| `with` block | Hardcodes the spreadsheet ID and range (skips the header row with `A2:D100`) |
| `mapping: "$.values"` | Selects the rows array from the Sheets API response |
| `items` → `properties` | Maps each column index to a named, typed field |

### Resulting Output

`GET /team` returns:

```json
[
  { "name": "Alice Chen", "role": "Engineer", "email": "alice@co.com", "office": "Paris" },
  { "name": "Bob Silva", "role": "Designer", "email": "bob@co.com", "office": "Lisbon" },
  { "name": "Carol Wu", "role": "PM", "email": "carol@co.com", "office": "Tokyo" }
]
```

---

## MCP Adapter Variant

The same `consumes` adapter is reused — only the `exposes` side differs. Instead of REST resources and operations, the MCP adapter declares **tools** that AI agents can discover and invoke.

### Key Differences from REST

| Aspect | REST Adapter | MCP Adapter |
|---|---|---|
| Discovery | OpenAPI-style path + method | MCP `tools/list` — agents see `name` + `description` |
| Invocation | `GET /team` | Agent calls tool `get-team-roster` |
| Input schema | `inputParameters` with `in: "query"` / `"path"` | `inputParameters` become JSON Schema properties |
| Output | HTTP JSON response | MCP tool result (JSON content) |
| Transport | HTTP server on a port | Streamable HTTP (default) or `stdio` for local IDE integration |

### MCP-Specific Walkthrough

| Element | Purpose |
|---|---|
| `type: "mcp"` | Exposes as an MCP server instead of REST |
| `namespace: "roster-mcp"` | Separate namespace from the REST adapter — both can coexist |
| `description` on the server | Shown to agents during server discovery |
| `description` on the tool | Critical for agent discovery — describes what the tool does and what it returns |
| `call`, `with`, `outputParameters` | Identical to the REST operation — same consumed adapter, same mappings |

### What an Agent Sees

When an MCP client calls `tools/list`, it receives:

```json
{
  "tools": [
    {
      "name": "get-team-roster",
      "description": "Retrieve the team roster from a Google Sheets spreadsheet. Returns each team member with their name, role, email, and office location.",
      "inputSchema": {
        "type": "object",
        "properties": {}
      }
    }
  ]
}
```

Calling the tool returns the same domain-specific JSON array:

```json
[
  { "name": "Alice Chen", "role": "Engineer", "email": "alice@co.com", "office": "Paris" },
  { "name": "Bob Silva", "role": "Designer", "email": "bob@co.com", "office": "Lisbon" }
]
```

### MCP with Agent-Supplied Inputs

If the agent should control which range to read, declare `inputParameters` on the tool:

```yaml
tools:
  - name: "read-sheet-range"
    description: "Read a specific range from the team roster spreadsheet and return rows as team member objects."
    inputParameters:
      - name: "range"
        type: "string"
        description: "The A1 range to read (e.g. Sheet1!A2:D100). Row 1 is the header row — start from A2."
    call: "google-sheets.get-range"
    with:
      spreadsheet_id: "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgVE2upms"
      range: "{{range}}"
    outputParameters:
      - type: "array"
        mapping: "$.values"
        items:
          type: "object"
          properties:
            name:
              type: "string"
              mapping: "$[0]"
            role:
              type: "string"
              mapping: "$[1]"
            email:
              type: "string"
              mapping: "$[2]"
            office:
              type: "string"
              mapping: "$[3]"
```

The agent can then call `read-sheet-range` with `{"range": "Sheet1!A2:A10"}` to fetch a specific subset.

---

## Adapting to a Different Spreadsheet

To adapt this capability to a different spreadsheet layout:

1. **Change `spreadsheet_id`** in the `with` block to your sheet's ID.
2. **Adjust `range`** — start from row 2 to skip headers (e.g. `Sheet1!A2:F100` for 6 columns).
3. **Update `properties`** — add/remove/rename fields and adjust the positional `$[n]` mappings to match your column order.

Example — an inventory sheet with columns `SKU | Product | Quantity | Warehouse`:

```yaml
outputParameters:
  - type: "array"
    mapping: "$.values"
    items:
      type: "object"
      properties:
        sku:
          type: "string"
          mapping: "$[0]"
        product:
          type: "string"
          mapping: "$[1]"
        quantity:
          type: "number"
          mapping: "$[2]"
        warehouse:
          type: "string"
          mapping: "$[3]"
```

---

## Alternative: Dynamic Range via Input Parameters

If the spreadsheet ID or range should be caller-controlled rather than hardcoded, expose them as `inputParameters`:

```yaml
resources:
  - path: "/sheet-data"
    name: "sheet-data"
    label: "Dynamic Sheet Reader"
    description: "Reads any Google Sheet range and returns rows as typed objects."
    inputParameters:
      - name: "spreadsheet_id"
        in: "query"
        type: "string"
        description: "The Google Sheet ID"
      - name: "range"
        in: "query"
        type: "string"
        description: "The A1 range to read (e.g. Sheet1!A2:D100)"
    operations:
      - method: "GET"
        call: "google-sheets.get-range"
        with:
          spreadsheet_id: "{{spreadsheet_id}}"
          range: "{{range}}"
        outputParameters:
          - type: "array"
            mapping: "$.values"
            items:
              type: "object"
              properties:
                name:
                  type: "string"
                  mapping: "$[0]"
                role:
                  type: "string"
                  mapping: "$[1]"
                email:
                  type: "string"
                  mapping: "$[2]"
                office:
                  type: "string"
                  mapping: "$[3]"
```

> **Note:** This variant exposes the spreadsheet ID and range as user inputs, trading convenience for flexibility. The output mapping still assumes a fixed column layout — a fully generic reader would require runtime schema inference, which is outside Naftiko's current scope.
