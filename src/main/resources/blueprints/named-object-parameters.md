# Named-Object Parameters
## Unified Parameter Syntax via JSON Schema `properties` Convention

**Status**: Proposal  
**Date**: April 10, 2026  
**Spec Version**: `1.0.0-alpha2`  
**Key Concept**: Replace array-based `inputParameters` and `outputParameters` with named-object maps (key = parameter name, value = parameter definition), aligning all parameter declarations with the JSON Schema `properties` convention that Naftiko already uses internally for nested object structures.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Goals and Non-Goals](#goals-and-non-goals)
3. [Terminology](#terminology)
4. [Current State Analysis](#current-state-analysis)
5. [Proposed Syntax](#proposed-syntax)
6. [Design Decisions](#design-decisions)
7. [Specification and Schema Changes](#specification-and-schema-changes)
8. [Capability YAML Examples](#capability-yaml-examples)
9. [Java Implementation Impact](#java-implementation-impact)
10. [Migration Strategy](#migration-strategy)
11. [Risks and Mitigations](#risks-and-mitigations)
12. [Acceptance Criteria](#acceptance-criteria)

---

## Executive Summary

### What This Proposes

Change all `inputParameters` and `outputParameters` declarations across the Naftiko Specification from **arrays of objects with a `name` field** to **named-object maps** where the YAML key is the parameter name and the value is the parameter definition — matching JSON Schema `properties` syntax.

This affects every parameter context in the spec:

- **Exposes** — MCP tools, REST operations, REST resources, aggregate functions
- **Consumes** — HTTP adapters, resources, operations
- **Orchestrated outputs** — step-based operations

### Why This Matters

The spec currently uses two conflicting patterns to define the same concept (a named, typed parameter):

| Pattern | Where | Example |
|---|---|---|
| **Array with `name`** | `inputParameters`, orchestrated `outputParameters`, consumed `outputParameters` | `- name: imo` / `  type: string` |
| **Named-object map** | `properties` inside object-typed outputs | `imo:` / `  type: string` |

This dual syntax creates three DX problems:

1. **Redundancy** — Orchestrated output `properties` require the name as both the YAML key *and* a `name` field inside the object
2. **Inconsistency** — Two mental models for the same concept, depending on whether you're at the top level or inside a nested object
3. **Boilerplate** — Simple mapped outputs require wrapping in `- type: object` + `properties:` even for flat responses

### Value

| Benefit | Impact |
|---|---|
| **One pattern** | All parameters — input, output, nested — use the same named-object syntax |
| **Less YAML** | No `name` field on every parameter; no wrapper `- type: object` for flat outputs |
| **JSON Schema alignment** | Matches the well-known `properties` convention from JSON Schema / OpenAPI |
| **Reduced errors** | Eliminates the orchestrated-mode redundancy where key and `name` must match |
| **Better tooling** | IDE autocompletion works naturally on object keys; no array index navigation |

### Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Large migration surface (YAML + Java + tests) | Certain | Medium | Alpha stage — no external consumers; batch migration |
| YAML key ordering not guaranteed by spec | Low | Low | Java `LinkedHashMap`; YAML 1.2 preserves insertion order in practice |
| Duplicate parameter names silently merge | Low | Medium | YAML 1.2 forbids duplicate keys; add `propertyNames` schema constraint |
| Downstream code assumes `List<T>` | High | Medium | Change to `Map<String, T>` with named deserialization |

---

## Goals and Non-Goals

### Goals

1. Replace all array-based `inputParameters` with named-object maps across every spec context (MCP tools, REST operations, REST resources, aggregate functions, consumed adapters, consumed resources, consumed operations).
2. Replace all array-based `outputParameters` with named-object maps for mapped mode, orchestrated mode, and consumed operations.
3. Remove the `name` field from all parameter type definitions — the YAML key carries the name.
4. Eliminate the `- type: object` + `properties:` wrapper for flat mapped outputs.
5. Remove the redundant `name` field from `OrchestratedOutputParameter` properties.
6. Update the JSON Schema, Java spec classes, engine, examples, tutorials, test fixtures, and Spectral rules.

### Non-Goals (This Proposal)

1. Changing lookup step `outputParameters` — these are field selectors (string arrays), not parameter definitions.
2. Backward compatibility with the array syntax — this is a clean break at alpha stage.
3. Changing the `with` injector syntax — `with` is a flat key-value map already.
4. Changing the `properties` syntax inside nested object-typed parameters — it already uses named-objects.

---

## Terminology

| Term | Definition |
|---|---|
| **Named-object map** | A YAML mapping where each key is a parameter name and each value is a parameter definition object — equivalent to JSON Schema `properties` |
| **Array-based parameters** | The current syntax: a YAML sequence of objects, each containing a `name` field |
| **Flat output** | An `outputParameters` block that maps scalar fields directly, without a wrapper `- type: object` + `properties:` |
| **Parameter key** | The YAML key in a named-object map that serves as the parameter name |

---

## Current State Analysis

### Parameter Types in the Schema

The spec defines **six parameter types** across three contexts:

| Type | Context | Fields |
|---|---|---|
| `McpToolInputParameter` | MCP tools, aggregate functions | `name`, `type`, `description`, `required` |
| `ExposedInputParameter` | REST resources, REST operations | `name`, `in`, `type`, `description`, `pattern`, `value` |
| `ConsumedInputParameter` | Consumed adapters, resources, operations | `name`, `in`, `value` |
| `MappedOutputParameter` | Simple-mode exposed outputs, aggregate outputs | `type`, `mapping`/`value`, `properties`, `items` |
| `OrchestratedOutputParameter` | Step-mode exposed outputs | `name`, `type`, `properties`, `items` |
| `ConsumedOutputParameter` | Consumed operations | `name`, `type`, `value` |

### Inconsistencies

| Issue | Details |
|---|---|
| **Input vs output naming** | Inputs use `- name: x` (array). Output `properties` use `x:` (named-object). Two patterns for the same concept. |
| **Orchestrated redundancy** | `OrchestratedOutputParameterObject.properties` requires both the YAML key *and* a `name` field inside each child. |
| **Mapped output wrapper** | Flat mapped outputs must be wrapped in `- type: object` + `properties:` — even when there's only one level of fields. |
| **No name on mapped outputs** | `MappedOutputParameter` has no `name` field. The only way to name a top-level output is the wrapper object pattern. |
| **Three input types** | `McpToolInputParameter`, `ExposedInputParameter`, `ConsumedInputParameter` are structurally similar but defined separately. |

### The `properties` Pattern (Already Correct)

Inside object-typed parameters, the spec already uses named-object maps:

```yaml
# This pattern exists today — properties use key = name
properties:
  imo:
    type: string
    mapping: $.imo_number
  name:
    type: string
    mapping: $.vessel_name
```

The proposal extends this pattern to the top-level `inputParameters` and `outputParameters`.

---

## Proposed Syntax

### Input Parameters

**MCP tool / Aggregate function:**

```yaml
# BEFORE (array)
inputParameters:
  - name: imo
    type: string
    description: "IMO number"
  - name: status
    type: string
    required: false
    description: "Operational status"

# AFTER (named-object)
inputParameters:
  imo:
    type: string
    description: "IMO number"
  status:
    type: string
    required: false
    description: "Operational status"
```

**REST exposed operation** (retains `in` — location is a REST concern):

```yaml
# BEFORE (array)
inputParameters:
  - name: imo
    in: path
    type: string
    description: "IMO number"
  - name: status
    in: query
    type: string
    description: "Filter by status"

# AFTER (named-object)
inputParameters:
  imo:
    in: path
    type: string
    description: "IMO number"
  status:
    in: query
    type: string
    description: "Filter by status"
```

**Consumed operation:**

```yaml
# BEFORE (array)
inputParameters:
  - name: imo_number
    in: path
  - name: status
    in: query

# AFTER (named-object)
inputParameters:
  imo_number:
    in: path
  status:
    in: query
```

**Consumed adapter (global headers):**

```yaml
# BEFORE (array)
inputParameters:
  - name: Registry-Version
    in: header
    value: "{{REGISTRY_VERSION}}"

# AFTER (named-object)
inputParameters:
  Registry-Version:
    in: header
    value: "{{REGISTRY_VERSION}}"
```

### Output Parameters

**Simple mode (mapped) — flat response:**

```yaml
# BEFORE (array + wrapper)
outputParameters:
  - type: object
    properties:
      imo:
        type: string
        mapping: $.imo_number
      name:
        type: string
        mapping: $.vessel_name
      specs:
        type: object
        properties:
          yearBuilt:
            type: number
            mapping: $.year_built

# AFTER (flat named-object)
outputParameters:
  imo:
    type: string
    mapping: $.imo_number
  name:
    type: string
    mapping: $.vessel_name
  specs:
    type: object
    properties:
      yearBuilt:
        type: number
        mapping: $.year_built
```

**Simple mode (mapped) — array response:**

```yaml
# BEFORE (array + wrapper)
outputParameters:
  - type: array
    mapping: "$."
    items:
      type: object
      properties:
        imo:
          type: string
          mapping: $.imo_number

# AFTER (named — array outputs are now named)
outputParameters:
  results:
    type: array
    mapping: "$."
    items:
      type: object
      properties:
        imo:
          type: string
          mapping: $.imo_number
```

**Orchestrated mode — no more redundant `name`:**

```yaml
# BEFORE (array with redundant name in properties)
outputParameters:
  - name: voyage
    type: object
    properties:
      voyageId:
        name: voyageId
        type: string
      route:
        name: route
        type: object
        properties:
          from:
            name: from
            type: string

# AFTER (named-object, name from key only)
outputParameters:
  voyage:
    type: object
    properties:
      voyageId:
        type: string
      route:
        type: object
        properties:
          from:
            type: string
```

**Consumed operation output:**

```yaml
# BEFORE (array)
outputParameters:
  - name: database-id
    type: string
    value: $.id
  - name: db-title
    type: string
    value: $.title[0].text.content

# AFTER (named-object)
outputParameters:
  database-id:
    type: string
    value: $.id
  db-title:
    type: string
    value: $.title[0].text.content
```

**Lookup steps — UNCHANGED** (field selectors, not parameter definitions):

```yaml
steps:
  - name: resolve-crew
    type: lookup
    outputParameters:
      - "fullName"
      - "role"
```

---

## Design Decisions

| Decision | Rationale |
|---|---|
| **Named-object map over array** | Aligns with JSON Schema `properties`; eliminates `name` redundancy; matches how nested `properties` already work |
| **Keep `in` for REST inputs** | Parameter location is REST-specific, unrelated to naming. Stays as a value-level property |
| **Keep `required` for MCP inputs** | Schema-level optionality for tool inputs. Stays as a value-level property (default `true`) |
| **Require names on array-typed outputs** | Currently, `- type: array` has no name. Named-object syntax forces a meaningful key (e.g. `results:`) |
| **Lookup outputs unchanged** | These are field selectors (string arrays), not parameter definitions — different concept, different syntax |
| **Drop `name` from `OrchestratedOutputParameter`** | The YAML key carries the name. The `name` field was pure redundancy |
| **No backward-compatible dual-mode** | Alpha stage — clean break, no migration burden |
| **`propertyNames` pattern constraint** | Move name validation (`^[a-zA-Z0-9-_*]+$`) from `name` property to `propertyNames` on the container |

---

## Specification and Schema Changes

### Container Fields (array → object)

Every `inputParameters` and `outputParameters` field in the schema changes from:

```json
"inputParameters": {
  "type": "array",
  "items": {
    "$ref": "#/$defs/McpToolInputParameter"
  }
}
```

To:

```json
"inputParameters": {
  "type": "object",
  "description": "Named input parameters. Each key is the parameter name.",
  "propertyNames": {
    "pattern": "^[a-zA-Z0-9-_*]+$"
  },
  "additionalProperties": {
    "$ref": "#/$defs/McpToolInputParameter"
  }
}
```

### Parameter Types — `name` Removed

| Schema Type | Change |
|---|---|
| `McpToolInputParameter` | Remove `name` from `properties` and `required` |
| `ExposedInputParameter` | Remove `name` from `properties` and `required` |
| `ConsumedInputParameter` | Remove `name` from `properties` and `required` |
| `OrchestratedOutputParameterBase` | Remove `name` from `properties` and `required` |
| `ConsumedOutputParameter` | Remove `name` from `properties` and `required` |
| `MappedOutputParameter*` | No structural change (already lacks `name`). Parent container changes. |

### Mapped Output — Wrapper Elimination

The top-level `outputParameters` for mapped mode changes from:

```json
"outputParameters": {
  "type": "array",
  "items": { "$ref": "#/$defs/MappedOutputParameter" },
  "maxItems": 1
}
```

To:

```json
"outputParameters": {
  "type": "object",
  "propertyNames": {
    "pattern": "^[a-zA-Z0-9-_*]+$"
  },
  "additionalProperties": {
    "$ref": "#/$defs/MappedOutputParameter"
  }
}
```

This means flat responses no longer need the `- type: object` + `properties:` wrapper — each output field is a direct key in the `outputParameters` map.

### Orchestrated Output — Properties Simplification

`OrchestratedOutputParameterObject.properties` drops the redundant `name` requirement:

```json
// BEFORE — child values have required name
"properties": {
  "type": "object",
  "additionalProperties": {
    "$ref": "#/$defs/OrchestratedOutputParameter"  // has required "name"
  }
}

// AFTER — name removed from OrchestratedOutputParameterBase
"properties": {
  "type": "object",
  "additionalProperties": {
    "$ref": "#/$defs/OrchestratedOutputParameter"  // name no longer required
  }
}
```

### Name Validation via `propertyNames`

The identifier pattern that was on each parameter's `name` field moves to the container:

```json
"propertyNames": {
  "pattern": "^[a-zA-Z0-9-_*]+$"
}
```

This enforces valid parameter names at the map key level.

---

## Capability YAML Examples

### Full MCP Capability (After)

```yaml
naftiko: "1.0.0-alpha2"

info:
  label: "Ship Registry"
  description: "Exposes CIR ship data via MCP"

capability:
  exposes:
    - type: mcp
      port: 3001
      namespace: ship-registry
      tools:
        - name: get-ship
          description: "Retrieve ship details by IMO number"
          inputParameters:
            imo:
              type: string
              description: "IMO number of the ship"
          call: cir.get-ship
          with:
            imo_number: "{{imo}}"
          outputParameters:
            imo:
              type: string
              mapping: $.imo_number
            name:
              type: string
              mapping: $.vessel_name
            specs:
              type: object
              properties:
                yearBuilt:
                  type: number
                  mapping: $.year_built
                tonnage:
                  type: number
                  mapping: $.gross_tonnage

  consumes:
    - namespace: cir
      type: http
      baseUri: https://api.cir-registry.org
      resources:
        - name: ships
          path: /ships
          operations:
            - name: get-ship
              method: GET
              path: /{imo_number}
              inputParameters:
                imo_number:
                  in: path
```

### Full REST Capability (After)

```yaml
naftiko: "1.0.0-alpha2"

info:
  label: "Ship Registry REST"
  description: "Exposes CIR ship data via REST"

capability:
  exposes:
    - type: rest
      port: 8080
      namespace: ship-registry
      resources:
        - path: /ships/{imo}
          inputParameters:
            imo:
              in: path
              type: string
              description: "IMO number"
          operations:
            - method: GET
              inputParameters:
                include-specs:
                  in: query
                  type: boolean
                  description: "Include technical specifications"
              call: cir.get-ship
              with:
                imo_number: "{{imo}}"
              outputParameters:
                imo:
                  type: string
                  mapping: $.imo_number
                name:
                  type: string
                  mapping: $.vessel_name

  consumes:
    - namespace: cir
      type: http
      baseUri: https://api.cir-registry.org
      resources:
        - name: ships
          path: /ships
          operations:
            - name: get-ship
              method: GET
              path: /{imo_number}
              inputParameters:
                imo_number:
                  in: path
```

### Orchestrated Tool with Steps (After)

```yaml
tools:
  - name: build-manifest
    description: "Build a ship manifest by combining ship, crew, and cargo data"
    inputParameters:
      imo:
        type: string
        required: true
        description: "IMO number"
    steps:
      - name: get-ship
        type: call
        call: cir.get-ship
        with:
          imo_number: "{{imo}}"
      - name: list-crew
        type: call
        call: crew.list-by-ship
        with:
          shipImo: "{{imo}}"
      - name: resolve-crew
        type: lookup
        index: list-crew
        match: crewId
        lookupValue: "$.get-ship.assignedCrew"
        outputParameters:
          - "fullName"
          - "role"
    outputParameters:
      ship:
        type: string
      crew:
        type: array
        items:
          - name: fullName
            type: string
          - name: role
            type: string
    mappings:
      - stepName: get-ship
        outputParameters:
          ship: $.vessel_name
      - stepName: resolve-crew
        outputParameters:
          crew: "$."
```

### Aggregate with Ref (After)

```yaml
capability:
  aggregates:
    - namespace: weather
      functions:
        - name: get-forecast
          description: "Get weather forecast for a location"
          semantics:
            safe: true
            cacheable: true
          inputParameters:
            location:
              type: string
              description: "City name or coordinates"
          call: openweather.get-forecast
          with:
            q: "{{location}}"
          outputParameters:
            temperature:
              type: number
              mapping: $.main.temp
            condition:
              type: string
              mapping: $.weather[0].description

  exposes:
    - type: mcp
      port: 3001
      namespace: weather
      tools:
        - name: get-forecast
          ref: weather.get-forecast

    - type: rest
      port: 8080
      namespace: weather
      resources:
        - path: /forecast
          operations:
            - method: GET
              ref: weather.get-forecast
```

---

## Java Implementation Impact

### Spec Classes

| Class | Change |
|---|---|
| `McpToolInputParameterSpec` | Remove `name` field and getter |
| `ExposedInputParameterSpec` | Remove `name` field and getter |
| `ConsumedInputParameterSpec` | Remove `name` field and getter |
| `OrchestratedOutputParameterSpec` | Remove `name` field and getter |
| `ConsumedOutputParameterSpec` | Remove `name` field and getter |
| All container parents | `List<XxxParameter>` → `LinkedHashMap<String, XxxParameter>` |

### Deserialization

Jackson custom deserializers (if any) that read parameter arrays need to read named-object maps. If using default Jackson binding:

- `@JsonAnySetter` or custom `MapDeserializer` to handle the YAML-key-as-name pattern
- Alternatively, Jackson naturally deserializes `type: object` + `additionalProperties` into `Map<String, T>` — minimal custom code needed

### Engine (Runtime)

| Component | Change |
|---|---|
| `OperationStepExecutor` | Iterate `Map.Entry<String, T>` instead of `List<T>` with `.getName()` |
| `BindResolver` | Parameter lookup by name → direct `map.get(name)` (simpler than list scan) |
| `MCP tool schema builder` | Iterate map entries to build JSON Schema `properties` (already named-object output) |
| `WithInjector` resolution | No change — `with` is already a map |
| Parameter validation | `map.containsKey()` instead of `list.stream().filter(p -> p.getName().equals(...))` |

**Net effect on engine code**: Simplification — `map.get(name)` replaces `list.stream().filter()` lookups.

### Spectral Rules

Rules referencing `inputParameters[*].name` or `outputParameters[*].name` need path updates. Example:

```yaml
# BEFORE
given: "$.capability.exposes[*].tools[*].inputParameters[*]"
then:
  field: name
  function: pattern
  functionOptions:
    match: "^[a-z][a-z0-9-]*$"

# AFTER — name validation moves to the key level
given: "$.capability.exposes[*].tools[*].inputParameters"
then:
  function: pattern
  functionOptions:
    match: "^[a-z][a-z0-9-]*$"
```

---

## Migration Strategy

**Approach**: Spec version bump (`1.0.0-alpha2`). Clean break — no dual-support period.

### Phase 1 — Schema + Java Spec Classes

1. Update `naftiko-schema.json`: change all parameter containers from `array` to `object` with `additionalProperties` and `propertyNames`
2. Remove `name` from all parameter type `$defs`
3. Update Java spec POJOs: `List<T>` → `LinkedHashMap<String, T>`, remove `name` fields
4. Update custom deserializers

### Phase 2 — Engine + Runtime

1. Update `OperationStepExecutor`, `BindResolver`, MCP schema builder, REST parameter handlers
2. Update all parameter iteration from list-based to map-based
3. Ensure parameter ordering is preserved via `LinkedHashMap`

### Phase 3 — YAML Files

1. Migrate all examples in `src/main/resources/schemas/examples/`
2. Migrate all tutorial files in `src/main/resources/tutorial/`
3. Migrate all test fixtures in `src/test/resources/`
4. Migrate demo files in `demo/`

### Phase 4 — Tooling + Validation

1. Update Spectral rules in `naftiko-rules.yml`
2. Update wiki Specification
3. Update AGENTS.md capability design rules
4. Update skill references
5. Full `mvn test` + Spectral lint on all YAML files
6. Review all blueprints for outdated parameter syntax

---

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **Large migration surface** | Certain | Medium | Alpha stage — no external consumers. Batch migration in phases. |
| **YAML key ordering** | Low | Low | YAML 1.2 preserves insertion order in practice. Java `LinkedHashMap` preserves it in code. Document as convention. |
| **Duplicate parameter names** | Low | Medium | YAML 1.2 spec forbids duplicate keys. Add `propertyNames` constraint. Spectral rule as safety net. |
| **Downstream code assumes `List<T>`** | High | Medium | Systematic `List` → `Map` migration. IDE refactoring + `mvn test` catches all callers. |
| **`name` loss in Java objects** | Medium | Low | If downstream needs a `name`, inject it during deserialization from the map key, or use `Map.Entry`. |
| **MCP SDK expects array inputs** | Low | Medium | The MCP wire format uses JSON Schema `properties` (already named-object). The Naftiko→MCP translation becomes simpler. |

---

## Acceptance Criteria

1. **Schema** — `naftiko-schema.json` validates both input and output parameters as named-object maps with `propertyNames` constraints.
2. **No `name` field** — No parameter type definition contains a `name` property. Names come exclusively from the YAML key / JSON property name.
3. **Examples pass** — All files in `schemas/examples/` validate against the updated schema.
4. **Tutorials pass** — All files in `tutorial/` validate against the updated schema.
5. **Tests green** — `mvn clean test` passes with zero failures.
6. **Spectral clean** — All YAML examples lint clean against updated rules.
7. **Engine works** — MCP and REST servers start and serve correct responses using the new parameter format.
8. **Wiki updated** — Specification wiki reflects named-object syntax for all parameter examples.
9. **Lookup unchanged** — Lookup step `outputParameters` remain string arrays.
10. **Ordering preserved** — Parameter declaration order in YAML is preserved at runtime (verified by test).
