# Script Step
## Polyglot Scripting in Orchestration Steps via GraalVM

**Status**: Proposal  
**Date**: April 21, 2026  
**Spec Version**: `1.0.0-alpha2`  
**Key Concept**: Add a new `type: script` orchestration step that executes JavaScript, Python, or Groovy code from external files via the GraalVM Polyglot API, enabling lightweight data transformation, filtering, and enrichment between `call` and `lookup` steps — without deploying additional services.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Goals and Non-Goals](#goals-and-non-goals)
3. [Terminology](#terminology)
4. [Design Analogy](#design-analogy)
5. [Architecture Overview](#architecture-overview)
6. [Specification and Schema Changes](#specification-and-schema-changes)
7. [Capability YAML Examples](#capability-yaml-examples)
8. [Runtime Design](#runtime-design)
9. [Security Model](#security-model)
10. [Dependency Changes](#dependency-changes)
11. [Native Image Considerations](#native-image-considerations)
12. [Testing Strategy](#testing-strategy)
13. [Implementation Roadmap](#implementation-roadmap)
14. [Risks and Mitigations](#risks-and-mitigations)
15. [Acceptance Criteria](#acceptance-criteria)

---

## Executive Summary

### What This Proposes

Add a new `type: script` step to the `OperationStep` discriminated union. A script step executes JavaScript, Python, or Groovy code — loaded from an external file via `location` — inside a sandboxed GraalVM polyglot context and stores the result in the step execution context — exactly like `call` and `lookup` steps. Scripts can declare dependent scripts via `dependencies`, enabling reuse of shared libraries and helper functions across capabilities.

Script steps can be used anywhere `steps` are accepted:

- **MCP tools** (`capability.exposes[type=mcp].tools[].steps`)
- **REST operations** (`capability.exposes[type=rest].resources[].operations[].steps`)
- **Aggregate functions** (`capability.aggregates[].functions[].steps`)

### Why This Fits Naftiko

Today, orchestration steps can dispatch HTTP calls (`call`) and cross-reference previous results (`lookup`). Any data transformation — filtering, reshaping, conditional logic, enrichment — requires either a dedicated consumed API endpoint or pre-computed server-side logic.

A `script` step fills the gap between "fetch data" and "expose data" by enabling transformations loaded from external files — reusable, testable, and version-controlled. This keeps Naftiko's declarative-first model intact while allowing procedural escape hatches when pure mapping is insufficient.

The step follows the same contract as existing step types:
- It has a `name` used as namespace in the step context
- It reads from `runtimeParameters` and `StepExecutionContext` (previous step outputs)
- It writes its result to `StepExecutionContext` under its `name`
- Subsequent steps and mappings reference it via `{{step-name.field}}` or `$.step-name.field`

### Value

| Benefit | Impact |
|---|---|
| **External script files** | Load scripts from `file:///` URIs for reusable, testable, version-controlled logic |
| **Dependent scripts** | Declare supporting script files that are pre-evaluated before the main script |
| **Polyglot** | JavaScript, Python, and Groovy — three of the most common scripting languages in the JVM ecosystem |
| **Sandboxed execution** | GraalVM polyglot context with no host access, no I/O, no network — safe by default |
| **Consistent step model** | Same `name` → context → reference pattern as `call` and `lookup` |
| **Aggregate-compatible** | Works in aggregate functions, so transformations are reusable across adapters |

### Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| GraalVM polyglot JAR size | High | Medium | Single image; scripting enabled by default in Docker, governed via Control Port; no runtime cost unless YAML uses `script` steps |
| Script timeout / infinite loop | Medium | High | Statement limit + execution timeout via `ResourceLimits` |
| Native image compatibility | Medium | Medium | Optional profile; polyglot adds significant binary size |

---

## Goals and Non-Goals

### Goals

1. Introduce `script` as a new `OperationStep` type alongside `call` and `lookup`.
2. Support `javascript`, `python`, and `groovy` as script languages via GraalVM Polyglot API.
3. Provide a sandboxed execution environment with no host access, no I/O, no network.
4. Inject previous step outputs and runtime parameters into the script context as read-only bindings.
5. Capture the script result via a well-known `result` variable binding.
6. Store script output in `StepExecutionContext` under the step's `name` — same contract as other step types.
7. Support `with` for additional parameter injection into the script context.
8. Enforce execution timeout and statement limits to prevent resource exhaustion.

### Non-Goals (This Proposal)

1. Scripts that make HTTP calls, read files, or access the host environment at runtime.
2. Persistent script state across step executions (each invocation is isolated).
3. Script debugging or REPL capabilities.
4. Script compilation caching across requests (deferred optimization).
5. Remote script locations (e.g., `https://`) — only `file:///` URIs are supported.
6. Inline scripts via `source` — only file-based scripts via `location` are supported in Phase 1 to limit usage to advanced, reviewable cases.

---

## Terminology

| Term | Definition |
|---|---|
| **Script step** | An `OperationStep` with `type: "script"` that executes code loaded from an external file |
| **Language** | The GraalVM language identifier: `javascript`, `python`, or `groovy` |
| **Location** | A `file:///` URI pointing to the main script file |
| **Dependencies** | An array of relative paths to dependent script files (helpers, libraries) within the `location` directory. Pre-evaluated in order before the main script |
| **Context bindings** | Variables injected into the script: `context` (all runtime parameters + previous step outputs) and `with` overrides |
| **Result binding** | The `result` variable that the script assigns to return its output |
| **Sandbox** | GraalVM polyglot `Context` configured with `allowAllAccess(false)` — no host classes, no I/O, no threads |

---

## Design Analogy

### How step types map to their purpose

```
call step                    lookup step                   script step (proposed)
─────────                    ───────────                   ─────────────────────
Dispatches HTTP request      Cross-references step output  Loads and executes script
to consumed operation        against previous step data    for data transformation

Input:                       Input:                        Input:
├─ call (namespace.op)       ├─ index (step name)          ├─ language (javascript|python|groovy)
├─ with (param injection)    ├─ match (key field)          ├─ location (file:/// directory URI)
│                            ├─ lookupValue (expression)   ├─ file (relative path to main script)
│                            └─ outputParameters (fields)  ├─ dependencies (relative paths to dependent scripts)
│                                                          ├─ with (param injection)

Output:                      Output:                       Output:
└─ HTTP response body        └─ Matched/extracted fields   └─ result variable value
   → stored in context          → stored in context           → stored in context
   under step name              under step name               under step name
```

### Step type selection guide

| Situation | Step Type |
|---|---|
| Fetch data from an external API | `call` |
| Cross-reference data from a previous step | `lookup` |
| Transformation logic (filter, reshape, compute) | `script` with `location` + `file` |
| Transformation with shared helper libraries | `script` with `location` + `file` + `dependencies` |

---

## Architecture Overview

### Current Step Model

```
OperationStep (oneOf)
├── OperationStepCall    — type: "call"
└── OperationStepLookup  — type: "lookup"
```

`OperationStepExecutor.executeSteps()` dispatches via Java pattern matching:

```java
case OperationStepCallSpec callStep -> { /* HTTP dispatch */ }
case OperationStepLookupSpec lookupStep -> { /* in-memory lookup */ }
```

### Proposed Step Model

```
OperationStep (oneOf)
├── OperationStepCall    — type: "call"
├── OperationStepLookup  — type: "lookup"
└── OperationStepScript  — type: "script"    ← new
```

`OperationStepExecutor.executeSteps()` gains a third case:

```java
case OperationStepScriptSpec scriptStep -> {
    JsonNode scriptResult = scriptExecutor.execute(
        scriptStep, runtimeParameters, stepContext);
    stepContext.storeStepOutput(scriptStep.getName(), scriptResult);
    addStepOutputToParameters(runtimeParameters, scriptStep.getName(), scriptResult);
}
```

### Execution Flow

```
OperationStepExecutor.executeSteps()
  │
  ├── OperationStepCallSpec     → HTTP client dispatch (existing)
  ├── OperationStepLookupSpec   → LookupExecutor (existing)
  └── OperationStepScriptSpec   → ScriptStepExecutor (new)
        │
        ├── 1. Resolve script file:
        │      location (directory) + file (relative path)
        │      using resolveAndValidate() — same as Skill adapter
        │
        ├── 2. Build bindings map:
        │      context = runtimeParameters + stepContext outputs
        │      + with overrides (Mustache-resolved)
        │
        ├── 3. Create sandboxed GraalVM Context:
        │      Context.newBuilder(language)
        │        .allowAllAccess(false)
        │        .option("engine.WarnInterpreterOnly", "false")
        │        .build()
        │
        ├── 4. Bind context map as guest-accessible Value
        │
        ├── 5. Evaluate dependent scripts in order (if present)
        │
        ├── 6. Evaluate main script
        │
        ├── 7. Read result binding → convert to JsonNode
        │
        └── 8. Close Context (releases resources)
```

---

## Specification and Schema Changes

### OperationStepBase — Add `"script"` to type enum

```json
"OperationStepBase": {
  "type": "object",
  "properties": {
    "type": {
      "type": "string",
      "enum": ["call", "lookup", "script"]
    },
    "name": {
      "$ref": "#/$defs/IdentifierKebab"
    }
  },
  "required": ["type", "name"]
}
```

### OperationStepScript — New definition

```json
"OperationStepScript": {
  "allOf": [
    {
      "$ref": "#/$defs/OperationStepBase"
    },
    {
      "properties": {
        "type": {
          "const": "script"
        },
        "name": true,
        "language": {
          "type": "string",
          "enum": ["javascript", "python", "groovy"],
          "description": "GraalVM language identifier for the script engine. Optional when 'management.scripting.defaultLanguage' is set in the Control Port — the engine falls back to the default."
        },
        "location": {
          "type": "string",
          "format": "uri",
          "pattern": "^file:///",
          "description": "file:/// URI pointing to the scripts directory. Follows the same convention as ExposedSkill.location — a directory root from which 'file' and 'dependencies' are resolved as relative paths. Optional when 'management.scripting.defaultLocation' is set in the Control Port — the engine falls back to the default."
        },
        "file": {
          "type": "string",
          "description": "Relative path to the main script file within the 'location' directory. The script must assign to the 'result' variable to produce output. Path segments must match [a-zA-Z0-9._-]+ (same validation as Skill instruction paths).",
          "minLength": 1
        },
        "dependencies": {
          "type": "array",
          "description": "Relative paths to dependent script files within the 'location' directory, pre-evaluated in order before the main script. Each dependent script can define helper functions, constants, or shared logic.",
          "items": {
            "type": "string",
            "minLength": 1
          },
          "minItems": 1
        },
        "with": {
          "$ref": "#/$defs/WithInjector"
        }
      },
      "required": ["file"],
      "unevaluatedProperties": false
    }
  ]
}
```

**Key constraints:**
- `location` points to a **directory** (not a file) — same convention as `ExposedSkill.location`
- `location` is **optional** when `management.scripting.defaultLocation` is set in the Control Port — the engine falls back to the default; if neither is set, the engine fails with a clear error
- `language` is **optional** when `management.scripting.defaultLanguage` is set in the Control Port — the engine falls back to the default; if neither is set, the engine fails with a clear error
- `file` is a **relative path** within that directory — same convention as `SkillTool.instruction`
- `dependencies` are relative paths within the same `location` directory
- Path resolution and validation reuse the same `resolveAndValidate()` logic as the Skill adapter (segment allowlist + prefix containment check)
- Multiple script steps (across tools, operations, and capabilities) can share the same `location` directory, enabling script reuse

### OperationStep — Add script to oneOf

```json
"OperationStep": {
  "oneOf": [
    { "$ref": "#/$defs/OperationStepCall" },
    { "$ref": "#/$defs/OperationStepLookup" },
    { "$ref": "#/$defs/OperationStepScript" }
  ]
}
```

### Summary of Schema Changes

| Object | Change |
|---|---|
| `OperationStepBase.type.enum` | Add `"script"` |
| `OperationStepScript` | New definition (with `location` directory + `file` relative path + `dependencies` + `with`) |
| `OperationStep.oneOf` | Add `{ "$ref": "#/$defs/OperationStepScript" }` |

No changes to `ExposedOperation`, `McpTool`, `AggregateFunction`, or any other definition — they already accept `OperationStep` via `steps`, which gains the new variant automatically.

### Spectral Rules — `naftiko-script-defaults-required`

Since `location` and `language` are optional in the schema (they can be inherited from `management.scripting.defaultLocation` and `management.scripting.defaultLanguage`), a Spectral rule enforces that every `script` step has a resolvable location and language at lint time.

#### Rule definition (`naftiko-rules.yml`)

```yaml
naftiko-script-defaults-required:
  message: >-
    {{error}}
  description: >
    Every script step must have a resolvable scripts directory and language.
    When a step omits 'location' or 'language', the engine falls back to
    'management.scripting.defaultLocation' or 'management.scripting.defaultLanguage'
    on the control adapter. If neither is set, the capability will fail at load time.
    This rule catches the misconfiguration at lint time.
  severity: error
  recommended: true
  given: "$"
  then:
    function: script-defaults-required
```

#### Custom function (`functions/script-defaults-required.js`)

```javascript
export default function scriptDefaultsRequired(targetVal) {
  if (!targetVal || typeof targetVal !== "object") return;

  const capability =
    targetVal.capability && typeof targetVal.capability === "object"
      ? targetVal.capability
      : {};

  // Check if a control adapter defines defaults
  const exposes = Array.isArray(capability.exposes) ? capability.exposes : [];
  let hasDefaultLocation = false;
  let hasDefaultLanguage = false;
  for (const adapter of exposes) {
    if (
      adapter &&
      adapter.type === "control" &&
      adapter.management &&
      adapter.management.scripting
    ) {
      const scripting = adapter.management.scripting;
      if (typeof scripting.defaultLocation === "string") hasDefaultLocation = true;
      if (typeof scripting.defaultLanguage === "string") hasDefaultLanguage = true;
      break;
    }
  }

  // If both defaults are set, all steps are covered — nothing to check
  if (hasDefaultLocation && hasDefaultLanguage) return;

  const results = [];

  // Collect all script steps from all step-bearing contexts
  const stepSources = [];

  // MCP tools
  for (let e = 0; e < exposes.length; e++) {
    const adapter = exposes[e];
    if (!adapter) continue;
    if (adapter.type === "mcp" && Array.isArray(adapter.tools)) {
      for (let t = 0; t < adapter.tools.length; t++) {
        const tool = adapter.tools[t];
        if (Array.isArray(tool.steps)) {
          for (let s = 0; s < tool.steps.length; s++) {
            stepSources.push({
              step: tool.steps[s],
              path: ["capability", "exposes", e, "tools", t, "steps", s],
            });
          }
        }
      }
    }
    // REST operations
    if (adapter.type === "rest" && Array.isArray(adapter.resources)) {
      for (let r = 0; r < adapter.resources.length; r++) {
        const resource = adapter.resources[r];
        if (Array.isArray(resource.operations)) {
          for (let o = 0; o < resource.operations.length; o++) {
            const op = resource.operations[o];
            if (Array.isArray(op.steps)) {
              for (let s = 0; s < op.steps.length; s++) {
                stepSources.push({
                  step: op.steps[s],
                  path: [
                    "capability", "exposes", e,
                    "resources", r, "operations", o, "steps", s,
                  ],
                });
              }
            }
          }
        }
      }
    }
  }

  // Aggregate functions
  const aggregates = Array.isArray(capability.aggregates)
    ? capability.aggregates
    : [];
  for (let a = 0; a < aggregates.length; a++) {
    const agg = aggregates[a];
    const functions = Array.isArray(agg.functions) ? agg.functions : [];
    for (let f = 0; f < functions.length; f++) {
      const fn = functions[f];
      if (Array.isArray(fn.steps)) {
        for (let s = 0; s < fn.steps.length; s++) {
          stepSources.push({
            step: fn.steps[s],
            path: ["capability", "aggregates", a, "functions", f, "steps", s],
          });
        }
      }
    }
  }

  // Report script steps that omit location or language without defaults
  for (const { step, path } of stepSources) {
    if (step && step.type === "script") {
      if (!step.location && !hasDefaultLocation) {
        results.push({
          message:
            "Script step '" +
            (step.name || "unnamed") +
            "' omits 'location' and no 'management.scripting.defaultLocation' " +
            "is configured. Add 'location' to this step or set a " +
            "'defaultLocation' on the control adapter.",
          path: [...path, "location"],
        });
      }
      if (!step.language && !hasDefaultLanguage) {
        results.push({
          message:
            "Script step '" +
            (step.name || "unnamed") +
            "' omits 'language' and no 'management.scripting.defaultLanguage' " +
            "is configured. Add 'language' to this step or set a " +
            "'defaultLanguage' on the control adapter.",
          path: [...path, "language"],
        });
      }
    }
  }

  return results;
}
```

---

## Capability YAML Examples

### Example 1 — JavaScript filter in MCP tool

Script file at `scripts/filter-active.js`:
```javascript
// filter-active.js
var members = context['list-members'];
var active = [];
for (var i = 0; i < members.length; i++) {
  if (members[i].type === 'User') {
    active.push({ login: members[i].login, id: members[i].id });
  }
}
result = active;
```

Capability YAML:
```yaml
naftiko: "1.0.0-alpha2"

info:
  label: "Active Users Report"
  description: "Fetches org members from GitHub and filters active ones"

capability:
  exposes:
    - type: mcp
      address: localhost
      port: 3000
      namespace: reporting
      tools:
        - name: active-members
          description: "Returns active GitHub organization members"
          inputParameters:
            - name: org
              type: string
              description: "GitHub organization name"
              required: true
          steps:
            - type: call
              name: list-members
              call: github.list-org-members
              with:
                org: "{{org}}"

            - type: script
              name: filter-active
              language: javascript
              location: "file:///app/capabilities/scripts"
              file: "filter-active.js"
          mappings:
            - targetName: active-members
              value: "$.filter-active"
          outputParameters:
            - name: active-members
              type: array
              items:
                - name: login
                  type: string
                - name: id
                  type: number

  consumes:
    - type: http
      namespace: github
      baseUri: "https://api.github.com"
      resources:
        - path: "/orgs/{{org}}/members"
          name: members
          operations:
            - method: GET
              name: list-org-members
```

### Example 2 — Transform and enrich in REST operation

Script file at `scripts/compute-totals.js`:
```javascript
// compute-totals.js
var orders = context['get-orders'];
var total = 0;
var count = 0;
for (var i = 0; i < orders.length; i++) {
  total += orders[i].amount;
  count++;
}
result = { totalAmount: total, orderCount: count };
```

Capability YAML:
```yaml
steps:
  - type: call
    name: get-orders
    call: shop.list-orders
    with:
      customer-id: "{{customerId}}"

  - type: script
    name: compute-totals
    language: javascript
    location: "file:///app/capabilities/scripts"
    file: "compute-totals.js"

  - type: call
    name: update-summary
    call: shop.update-customer-summary
    with:
      customer-id: "{{customerId}}"
      total-amount: "{{compute-totals.totalAmount}}"
      order-count: "{{compute-totals.orderCount}}"
```

### Example 3 — Python script step

Script file at `scripts/analyze-readings.py`:
```python
# analyze-readings.py
readings = context['fetch-readings']
values = [r['temperature'] for r in readings]
result = {
    'average': sum(values) / len(values),
    'min': min(values),
    'max': max(values),
    'count': len(values)
}
```

Capability YAML:
```yaml
steps:
  - type: call
    name: fetch-readings
    call: sensors.get-readings
    with:
      device-id: "{{deviceId}}"

  - type: script
    name: analyze
    language: python
    location: "file:///app/capabilities/scripts"
    file: "analyze-readings.py"
```

### Example 4 — Script step in aggregate function

Script file at `scripts/summarize-events.js`:
```javascript
// summarize-events.js
var events = context['fetch-events'];
var byType = {};
for (var i = 0; i < events.length; i++) {
  var t = events[i].type;
  byType[t] = (byType[t] || 0) + 1;
}
result = { eventsByType: byType, totalEvents: events.length };
```

Capability YAML:
```yaml
capability:
  aggregates:
    - namespace: analytics
      functions:
        - name: user-activity-summary
          description: "Computes activity summary from raw events"
          semantics:
            safe: true
            cacheable: true
          inputParameters:
            - name: user-id
              type: string
              description: "Target user identifier"
              required: true
          steps:
            - type: call
              name: fetch-events
              call: events-api.list-user-events
              with:
                user-id: "{{user-id}}"

            - type: script
              name: summarize
              language: javascript
              location: "file:///app/capabilities/scripts"
              file: "summarize-events.js"
          mappings:
            - targetName: events-by-type
              value: "$.summarize.eventsByType"
            - targetName: total-events
              value: "$.summarize.totalEvents"
          outputParameters:
            - name: events-by-type
              type: object
              properties:
                PushEvent:
                  name: PushEvent
                  type: number
                IssuesEvent:
                  name: IssuesEvent
                  type: number
            - name: total-events
              type: number

  exposes:
    - type: mcp
      address: localhost
      port: 3000
      namespace: analytics-mcp
      tools:
        - ref: analytics.user-activity-summary

    - type: rest
      address: localhost
      port: 8080
      namespace: analytics-rest
      resources:
        - path: "/users/{{user-id}}/activity"
          name: user-activity
          operations:
            - method: GET
              name: get-activity
              ref: analytics.user-activity-summary
```

### Example 5 — Script step with `with` injection

Script file at `scripts/filter-by-threshold.js`:
```javascript
// filter-by-threshold.js
var items = context['fetch-items'];
var threshold = context['threshold'];
var low = [];
for (var i = 0; i < items.length; i++) {
  if (items[i].stock < threshold) {
    low.push(items[i]);
  }
}
result = low;
```

Capability YAML:
```yaml
steps:
  - type: call
    name: fetch-items
    call: inventory.list-items

  - type: script
    name: filter-by-threshold
    language: javascript
    location: "file:///app/capabilities/scripts"
    file: "filter-by-threshold.js"
    with:
      threshold: "{{minStock}}"
```

### Example 6 — Script with dependent scripts

Shared helper at `scripts/lib/array-utils.js`:
```javascript
// array-utils.js — reusable helpers
function filterByField(arr, field, value) {
  var result = [];
  for (var i = 0; i < arr.length; i++) {
    if (arr[i][field] === value) {
      result.push(arr[i]);
    }
  }
  return result;
}

function pluckFields(arr, fields) {
  var result = [];
  for (var i = 0; i < arr.length; i++) {
    var obj = {};
    for (var j = 0; j < fields.length; j++) {
      obj[fields[j]] = arr[i][fields[j]];
    }
    result.push(obj);
  }
  return result;
}
```

Main script at `scripts/active-members.js`:
```javascript
// active-members.js — uses helpers from array-utils.js
var members = context['list-members'];
var active = filterByField(members, 'type', 'User');
result = pluckFields(active, ['login', 'id']);
```

Capability YAML:
```yaml
steps:
  - type: call
    name: list-members
    call: github.list-org-members
    with:
      org: "{{org}}"

  - type: script
    name: filter-active
    language: javascript
    location: "file:///app/capabilities/scripts"
    file: "active-members.js"
    dependencies:
      - "lib/array-utils.js"
```

### Example 7 — Python script with dependent scripts

Helper at `scripts/lib/stats.py`:
```python
# stats.py — reusable statistics helpers
def compute_stats(values):
    return {
        'average': sum(values) / len(values),
        'min': min(values),
        'max': max(values),
        'count': len(values)
    }
```

Main script at `scripts/analyze-readings.py`:
```python
# analyze-readings.py
readings = context['fetch-readings']
values = [r['temperature'] for r in readings]
result = compute_stats(values)
```

Capability YAML:
```yaml
steps:
  - type: call
    name: fetch-readings
    call: sensors.get-readings
    with:
      device-id: "{{deviceId}}"

  - type: script
    name: analyze
    language: python
    location: "file:///app/capabilities/scripts"
    file: "analyze-readings.py"
    dependencies:
      - "lib/stats.py"
```

### Example 8 — Groovy script step

Script file at `scripts/enrich-products.groovy`:
```groovy
// enrich-products.groovy
def products = context['fetch-products']
result = products.collect { p ->
    [name: p.name, price: p.price, discounted: p.price * 0.9]
}
```

Capability YAML:
```yaml
steps:
  - type: call
    name: fetch-products
    call: catalog.list-products
    with:
      category: "{{category}}"

  - type: script
    name: enrich
    language: groovy
    location: "file:///app/capabilities/scripts"
    file: "enrich-products.groovy"
```

### Example 9 — Groovy script with dependent scripts

Helper at `scripts/lib/pricing.groovy`:
```groovy
// pricing.groovy — reusable pricing helpers
def applyDiscount(items, rate) {
    items.collect { item ->
        item + [discounted: item.price * (1 - rate)]
    }
}
```

Main script at `scripts/enrich-with-discount.groovy`:
```groovy
// enrich-with-discount.groovy — uses helpers from pricing.groovy
def products = context['fetch-products']
result = applyDiscount(products, 0.1)
```

Capability YAML:
```yaml
steps:
  - type: call
    name: fetch-products
    call: catalog.list-products
    with:
      category: "{{category}}"

  - type: script
    name: enrich
    language: groovy
    location: "file:///app/capabilities/scripts"
    file: "enrich-with-discount.groovy"
    dependencies:
      - "lib/pricing.groovy"
```

### Example 10 — Reusing scripts across tools and skills

Two capabilities share the same `scripts/` directory. The `filter-active.js` and `lib/array-utils.js` scripts are used by both an MCP tool and a Skill tool instruction — each references the same `location`:

MCP capability:
```yaml
steps:
  - type: call
    name: list-members
    call: github.list-org-members
    with:
      org: "{{org}}"

  - type: script
    name: filter-active
    language: javascript
    location: "file:///app/shared-scripts"
    file: "active-members.js"
    dependencies:
      - "lib/array-utils.js"
```

REST capability (different YAML file, same scripts directory):
```yaml
steps:
  - type: call
    name: list-members
    call: github.list-org-members
    with:
      org: "{{org}}"

  - type: script
    name: filter-active
    language: javascript
    location: "file:///app/shared-scripts"
    file: "active-members.js"
    dependencies:
      - "lib/array-utils.js"
```

Both capabilities resolve `active-members.js` and `lib/array-utils.js` from the same `/app/shared-scripts/` directory — write once, reuse across tools.

### Example 11 — Simplified steps with defaults (Phase 3)

When the Control Port sets `defaultLocation` and `defaultLanguage`, script steps no longer need to repeat `location` or `language` on every step. Compare the verbose form (Examples 1–10) with the simplified form below.

Capability YAML:
```yaml
naftiko: "1.0.0-alpha2"

info:
  label: "Order Analytics"
  description: "Fetches orders, computes totals, and filters low-stock items"

capability:
  exposes:
    - type: control
      address: localhost
      port: 9090
      management:
        health: true
        scripting:
          enabled: true
          defaultLocation: "file:///app/capabilities/scripts"
          defaultLanguage: javascript

    - type: mcp
      address: localhost
      port: 3000
      namespace: analytics
      tools:
        - name: order-summary
          description: "Computes order totals for a customer"
          inputParameters:
            - name: customer-id
              type: string
              description: "Customer identifier"
              required: true
          steps:
            - type: call
              name: get-orders
              call: shop.list-orders
              with:
                customer-id: "{{customer-id}}"

            - type: script
              name: compute-totals
              file: "compute-totals.js"

          mappings:
            - targetName: total-amount
              value: "$.compute-totals.totalAmount"
            - targetName: order-count
              value: "$.compute-totals.orderCount"
          outputParameters:
            - name: total-amount
              type: number
            - name: order-count
              type: number

        - name: low-stock-items
          description: "Returns items below a stock threshold"
          inputParameters:
            - name: min-stock
              type: number
              description: "Minimum stock threshold"
              required: true
          steps:
            - type: call
              name: fetch-items
              call: inventory.list-items

            - type: script
              name: filter-low-stock
              file: "filter-by-threshold.js"
              with:
                threshold: "{{min-stock}}"

          mappings:
            - targetName: items
              value: "$.filter-low-stock"
          outputParameters:
            - name: items
              type: array
              items:
                - name: name
                  type: string
                - name: stock
                  type: number

  consumes:
    - type: http
      namespace: shop
      baseUri: "https://api.shop.example.com"
      resources:
        - path: "/customers/{{customer-id}}/orders"
          name: orders
          operations:
            - method: GET
              name: list-orders

    - type: http
      namespace: inventory
      baseUri: "https://api.inventory.example.com"
      resources:
        - path: "/items"
          name: items
          operations:
            - method: GET
              name: list-items
```

Both script steps (`compute-totals` and `filter-low-stock`) resolve their files from `file:///app/capabilities/scripts` and use `javascript` as their language — set once in the Control Port, inherited by all steps.

### Example 12 — Mixing defaults with explicit overrides

When most scripts share a directory and language but a specific step differs:

```yaml
capability:
  exposes:
    - type: control
      address: localhost
      port: 9090
      management:
        scripting:
          enabled: true
          defaultLocation: "file:///app/capabilities/scripts"
          defaultLanguage: javascript

    - type: mcp
      address: localhost
      port: 3000
      namespace: reporting
      tools:
        - name: activity-report
          description: "Generates user activity report with shared analytics scripts"
          inputParameters:
            - name: user-id
              type: string
              required: true
          steps:
            - type: call
              name: fetch-events
              call: events-api.list-user-events
              with:
                user-id: "{{user-id}}"

            # Uses defaults — location and language from Control Port
            - type: script
              name: summarize
              file: "summarize-events.js"

            # Overrides both — different location and language
            - type: script
              name: format-report
              language: groovy
              location: "file:///app/shared-scripts"
              file: "format-markdown-report.groovy"

          mappings:
            - targetName: report
              value: "$.format-report.markdown"
          outputParameters:
            - name: report
              type: string
```

The `summarize` step inherits both `defaultLocation` and `defaultLanguage`; the `format-report` step overrides both because it uses a Groovy script from a shared directory used by other capabilities.

---

## Runtime Design

### New Java Classes

| Class | Package | Description |
|---|---|---|
| `OperationStepScriptSpec` | `io.naftiko.spec.exposes` | Spec POJO: `language`, `location`, `file`, `dependencies`, `with`. Extends `OperationStepSpec` |
| `ScriptStepExecutor` | `io.naftiko.engine.exposes` | Stateless executor. Resolves file from location directory, evaluates dependent scripts, runs script in sandboxed GraalVM `Context` (JS/Python) or `GroovyShell` (Groovy), extracts `result` |

### OperationStepScriptSpec

```java
@JsonTypeName("script")
public class OperationStepScriptSpec extends OperationStepSpec {
    private volatile String language;
    private volatile String location;    // file:/// URI to scripts directory
    private volatile String file;        // relative path to main script within location
    private final List<String> dependencies = new CopyOnWriteArrayList<>();
    private final Map<String, Object> with = new ConcurrentHashMap<>();

    // Constructors, getters, setters following existing patterns
}
```

Registered in `OperationStepSpec`:
```java
@JsonSubTypes({
    @JsonSubTypes.Type(value = OperationStepCallSpec.class, name = "call"),
    @JsonSubTypes.Type(value = OperationStepLookupSpec.class, name = "lookup"),
    @JsonSubTypes.Type(value = OperationStepScriptSpec.class, name = "script")
})
```

### ScriptStepExecutor

```java
class ScriptStepExecutor {

    private static final long DEFAULT_STATEMENT_LIMIT = 100_000;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    JsonNode execute(OperationStepScriptSpec scriptStep,
                     Map<String, Object> runtimeParameters,
                     StepExecutionContext stepContext) {

        String language = scriptStep.getLanguage();
        String locationUri = scriptStep.getLocation();

        // 1. Resolve main script file using the same mechanism as Skill adapter
        String mainSource = readScript(locationUri, scriptStep.getFile());

        // 2. Build bindings: merge runtimeParameters + step outputs + with
        Map<String, Object> bindings = buildBindings(
            runtimeParameters, stepContext, scriptStep.getWith());

        // 3. Dispatch to language-specific executor
        if ("groovy".equals(language)) {
            return executeGroovy(mainSource, bindings, scriptStep);
        }
        return executePolyglot(language, mainSource, bindings, scriptStep);
    }

    private JsonNode executePolyglot(String language, String mainSource,
                                      Map<String, Object> bindings,
                                      OperationStepScriptSpec scriptStep) {

        String locationUri = scriptStep.getLocation();

        try (Context context = Context.newBuilder(language)
                .allowAllAccess(false)
                .allowHostAccess(HostAccess.NONE)
                .allowIO(IOAccess.NONE)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .option("engine.WarnInterpreterOnly", "false")
                .resourceLimits(ResourceLimits.newBuilder()
                    .statementLimit(DEFAULT_STATEMENT_LIMIT, null)
                    .build())
                .build()) {

            // Inject bindings
            Value contextBinding = context.eval(language,
                buildBindingExpression(language, bindings));
            context.getBindings(language).putMember("context", contextBinding);

            // Evaluate dependent scripts in order
            if (scriptStep.getDependencies() != null) {
                for (String depPath : scriptStep.getDependencies()) {
                    String depSource = readScript(locationUri, depPath);
                    context.eval(language, depSource);
                }
            }

            // Evaluate main script
            context.eval(language, mainSource);

            // Extract result
            Value resultValue = context.getBindings(language).getMember("result");
            return convertToJsonNode(resultValue);
        }
    }

    private JsonNode executeGroovy(String mainSource,
                                    Map<String, Object> bindings,
                                    OperationStepScriptSpec scriptStep) {
        String locationUri = scriptStep.getLocation();

        CompilerConfiguration config = new CompilerConfiguration();
        SecureASTCustomizer secure = new SecureASTCustomizer();
        secure.setDisallowedImports(List.of("java.io.**", "java.nio.**",
            "java.net.**", "java.lang.Process", "java.lang.Runtime"));
        secure.setAllowedReceivers(List.of());
        config.addCompilationCustomizers(secure);

        Binding binding = new Binding();
        binding.setVariable("context", bindings);

        GroovyShell shell = new GroovyShell(binding, config);

        // Evaluate dependent scripts in order
        if (scriptStep.getDependencies() != null) {
            for (String depPath : scriptStep.getDependencies()) {
                String depSource = readScript(locationUri, depPath);
                shell.evaluate(depSource);
            }
        }

        // Evaluate main script
        shell.evaluate(mainSource);

        // Extract result
        Object resultValue = binding.getVariable("result");
        return convertToJsonNode(resultValue);
    }

    /**
     * Resolves and reads a script file using the same mechanism as the Skill adapter.
     * Delegates to the shared resolveAndValidate() utility.
     *
     * @param locationUri file:/// URI pointing to the scripts directory
     * @param relativePath relative path to the script file within the directory
     * @return script file contents as UTF-8 string
     */
    private String readScript(String locationUri, String relativePath) {
        Path resolved = resolveAndValidate(locationUri, relativePath);
        return Files.readString(resolved, StandardCharsets.UTF_8);
    }
}
```

### File Resolution — Shared with Skill Adapter

Script steps reuse the **same `resolveAndValidate()` method** used by `SkillServerResource`:

```java
private static final Pattern SAFE_SEGMENT = Pattern.compile("^[a-zA-Z0-9._-]+$");

Path resolveAndValidate(String locationUri, String file) {
    Path root = Paths.get(URI.create(locationUri)).normalize().toAbsolutePath();
    Path relPath = Paths.get(file);

    // 1) Validate each segment — rejects "..", spaces, special chars
    for (int i = 0; i < relPath.getNameCount(); i++) {
        String segment = relPath.getName(i).toString();
        if (!SAFE_SEGMENT.matcher(segment).matches()) {
            throw new SecurityException("Unsafe path segment in request: " + segment);
        }
    }

    // 2) Resolve, normalize, then confirm it's still under root
    Path resolved = root.resolve(relPath).normalize().toAbsolutePath();
    if (!resolved.startsWith(root)) {
        throw new SecurityException("Path traversal attempt detected");
    }

    return resolved;
}
```

This logic should be extracted into a shared utility (e.g., `io.naftiko.engine.util.SafePathResolver`) so that both `SkillServerResource` and `ScriptStepExecutor` delegate to the same implementation. This avoids duplication and ensures consistent security behavior.

### File Resolution Rules

1. **`location` is a `file:///` URI pointing to a directory** — the scripts root, same convention as `ExposedSkill.location`. Optional when `management.scripting.defaultLocation` is configured in the Control Port; if neither is set, the engine fails at load time with a descriptive error
2. **`file` and `dependencies` are relative paths within that directory** — same convention as `SkillTool.instruction`
3. **Path traversal is rejected** — each segment must match `[a-zA-Z0-9._-]+`, and the resolved absolute path must remain under the `location` root
4. **Symlinks that escape the root are rejected** — resolved path is canonicalized before validation
5. **Files are read at step execution time** — not cached across requests (caching is a future optimization)
6. **UTF-8 encoding is assumed** for all script files
7. **Missing files cause a runtime error** with a descriptive message identifying the step name and file path
8. **Multiple steps and capabilities can share the same `location` directory** — enabling script reuse across tools, operations, and capabilities

### Integration in OperationStepExecutor

The `executeSteps()` switch gains one new case:

```java
case OperationStepScriptSpec scriptStep -> {
    JsonNode scriptResult = scriptExecutor.execute(
        scriptStep, runtimeParameters, stepContext);
    if (scriptResult != null) {
        stepContext.storeStepOutput(scriptStep.getName(), scriptResult);
        addStepOutputToParameters(
            runtimeParameters, scriptStep.getName(), scriptResult);
    }
}
```

### Result Extraction Contract

The script must assign to a variable named `result`:

| Language | Assignment syntax |
|---|---|
| JavaScript | `result = { key: "value" };` |
| Python | `result = {"key": "value"}` |
| Groovy | `result = [key: "value"]` |

If `result` is not assigned, the step produces `null` output (no entry in step context) — consistent with how `lookup` steps behave when no match is found.

### Type Mapping (GraalVM Value → JsonNode)

| GraalVM Value | JsonNode |
|---|---|
| `isNull()` | `NullNode` |
| `isBoolean()` | `BooleanNode` |
| `isNumber()` and `fitsInLong()` | `LongNode` |
| `isNumber()` | `DoubleNode` |
| `isString()` | `TextNode` |
| `hasArrayElements()` | `ArrayNode` (recursive) |
| `hasMembers()` | `ObjectNode` (recursive) |

---

## Security Model

### Sandbox Configuration

The GraalVM polyglot context (JavaScript, Python) is configured with maximum restriction:

| Capability | Setting | Effect |
|---|---|---|
| Host class access | `allowHostAccess(HostAccess.NONE)` | No access to Java classes from scripts |
| I/O access | `allowIO(IOAccess.NONE)` | No file system read/write |
| Thread creation | `allowCreateThread(false)` | No spawning threads |
| Process creation | `allowCreateProcess(false)` | No executing system commands |
| Environment access | `allowEnvironmentAccess(EnvironmentAccess.NONE)` | No reading environment variables |
| Network access | `allowAllAccess(false)` | No network sockets |
| Native access | `allowNativeAccess(false)` | No native library loading |

Groovy uses `GroovyShell` with `SecureASTCustomizer` + `CompilerConfiguration`:

| Capability | Setting | Effect |
|---|---|---|
| Import restriction | `setDisallowedImports(java.io.**, java.nio.**, java.net.**, ...)` | No I/O, no networking classes |
| Static receiver restriction | `setAllowedReceivers([])` | No static method calls on host classes |
| Process / Runtime access | Disallowed via import deny-list | No `Runtime.exec()` or `ProcessBuilder` |

### Resource Limits

| Limit | Default | Configurable | Purpose |
|---|---|---|---|
| Statement count | 100,000 | Yes (future) | Prevents infinite loops |
| Execution timeout | 5 seconds | Yes (future) | Prevents long-running scripts |

When the statement limit is exceeded, GraalVM throws `PolyglotException` with `isResourceExhausted() == true`. The engine catches this and wraps it in an `IllegalArgumentException` with a descriptive message.

### Threat Model

| Threat | Vector | Mitigation |
|---|---|---|
| **Sandbox escape** | Malicious script accesses host | `allowAllAccess(false)` + `HostAccess.NONE` — all host access denied |
| **Denial of service** | Infinite loop or excessive computation | Statement limit + execution timeout |
| **Memory exhaustion** | Script allocates unbounded data | Statement limit bounds computation; JVM heap is the outer limit |
| **Code injection** | End-user input interpolated into script | Script files are authored by capability designer, not end-user input. Runtime data is injected as data bindings (not string concatenation), preventing injection |
| **Data exfiltration** | Script sends data to external endpoint | No I/O, no network, no process creation — all egress blocked |
| **Environment leakage** | Script reads secrets from env vars | `EnvironmentAccess.NONE` — environment is invisible |
| **Path traversal** | `file` or `dependencies` path escapes location directory | `resolveAndValidate()` validates each segment against `[a-zA-Z0-9._-]+` allowlist + prefix containment check — same as Skill adapter |
| **Symlink escape** | Symlink in script directory points outside base | Resolved path is canonicalized before validation; symlinks that escape are rejected |

### Trust Boundary

Script files referenced by `location` + `file` and `dependencies` are **design-time artifacts** written by the capability author — the same trust boundary as `call` references and `mapping` expressions. They are not influenced by end-user request input. Runtime parameters are injected as structured data bindings, never interpolated into the script source.

Scripts are loaded from the local file system using the same `resolveAndValidate()` mechanism as the Skill adapter. The `location` URI identifies a directory root; `file` and `dependencies` are relative paths validated against segment allowlists and prefix containment checks. Multiple capabilities can share the same `location` directory, enabling script reuse without duplicating files.

---

## Dependency Changes

Scripting is an **opt-in feature controlled at runtime**. A single Docker image is published to Docker Hub with all polyglot dependencies included. Scripting is disabled by default — polyglot classes are never loaded, so there is no startup time or memory overhead. Users enable it via an environment variable when they need it.

### Dependencies (`pom.xml`)

All scripting dependencies are standard (non-optional) so they ship in every build:

```xml
<!-- GraalVM Polyglot API -->
<dependency>
    <groupId>org.graalvm.polyglot</groupId>
    <artifactId>polyglot</artifactId>
    <version>${graalvm.polyglot.version}</version>
</dependency>

<!-- JavaScript language (Truffle-based) -->
<dependency>
    <groupId>org.graalvm.polyglot</groupId>
    <artifactId>js</artifactId>
    <version>${graalvm.polyglot.version}</version>
    <type>pom</type>
</dependency>

<!-- Groovy language -->
<dependency>
    <groupId>org.apache.groovy</groupId>
    <artifactId>groovy</artifactId>
    <version>${groovy.version}</version>
</dependency>
```

Python language pack added in Phase 2:

```xml
<!-- Python language (Truffle-based) — Phase 2 -->
<dependency>
    <groupId>org.graalvm.polyglot</groupId>
    <artifactId>python</artifactId>
    <version>${graalvm.polyglot.version}</version>
    <type>pom</type>
</dependency>
```

### Version Property

```xml
<properties>
    <graalvm.polyglot.version>24.1.1</graalvm.polyglot.version>
    <groovy.version>4.0.24</groovy.version>
</properties>
```

### Image Size Impact

| Dependency | Approximate Size |
|---|---|
| `polyglot` API | ~2 MB |
| `js` language pack | ~40 MB |
| `groovy` | ~7 MB |
| `python` language pack | ~80 MB |

Phase 1 (JavaScript + Groovy): **~49 MB** added to the Docker image. This is a disk-only cost — no runtime overhead when scripting is disabled.

### Runtime Activation

Scripting uses a **two-level activation model**:

| Level | Mechanism | Purpose |
|---|---|---|
| **Infrastructure** | `NAFTIKO_SCRIPTING` env var (default: `true` in Docker image) | Permits scripting in this deployment — the safety gate |
| **Capability** | Presence of `type: script` steps in YAML | Activates scripting for this capability — the intent signal |

Both levels must be satisfied for scripting to execute. No extra YAML flag is needed — the presence of `type: script` steps *is* the declaration of intent.

The Docker image defaults `NAFTIKO_SCRIPTING=true` because the Control Port (Phase 3) provides runtime governance — operators can disable scripting, restrict languages, and tune limits without rebuilding. For deployments that don't use the Control Port, the env var can be set to `false` to disable scripting entirely.

**Docker usage** — no Java or Maven knowledge required:

```bash
# Default — scripting permitted (governed via Control Port when configured)
docker run naftiko ...

# Explicitly disable scripting at infrastructure level
docker run -e NAFTIKO_SCRIPTING=false naftiko ...
```

In Docker Compose:

```yaml
services:
  naftiko:
    image: naftiko
    environment:
      # Scripting is enabled by default in the Docker image.
      # Set to "false" to disable it entirely.
      NAFTIKO_SCRIPTING: "false"
```

### Activation Matrix

| `NAFTIKO_SCRIPTING` | Capability has `script` steps | Behavior |
|---|---|---|
| `true` (default in Docker) | No | Normal operation — zero overhead (polyglot never loaded) |
| `true` (default in Docker) | Yes | Polyglot initialized on first `script` step execution |
| `false` | No | Normal operation — zero overhead |
| `false` | Yes | **Fail fast** at capability load time with actionable error |

### Runtime Cost Model

| State | Disk cost | Startup cost | Memory cost |
|---|---|---|---|
| Scripting permitted (default in Docker), no `script` steps in YAML | +49 MB in image | None — no contexts created | None |
| Scripting permitted, `script` step executes | +49 MB in image | First polyglot context creation (~200 ms) | ~20 MB per active context (released after step) |
| Scripting disabled (`NAFTIKO_SCRIPTING=false`) | +49 MB in image | None — polyglot classes never loaded | None |

The key insight: polyglot classes are **lazy-loaded by the JVM**. Setting `NAFTIKO_SCRIPTING=true` alone does not load them. They are only loaded when the engine encounters an actual `type: script` step in a capability and creates a GraalVM `Context`. If a deployment permits scripting but no loaded capability uses script steps, the cost is zero.

### Fail-Fast Behavior

When `script` steps are present in a capability but `NAFTIKO_SCRIPTING` is explicitly set to `false`, the engine fails fast at capability load time with a clear error message:

> Script steps require scripting support. Scripting is disabled via NAFTIKO_SCRIPTING=false. Remove the override or set it to `true` to enable it.

The schema always accepts `type: script` — validation is structural. The runtime check happens at engine initialization when the capability is loaded. This keeps the schema stable across deployments and gives a clear, actionable error at the right moment.

### Detection Mechanism

`ScriptStepExecutor` reads the environment variable once at class load time. The capability loader checks for `script` steps during initialization:

```java
class ScriptStepExecutor {

    private static final boolean SCRIPTING_PERMITTED =
        !"false".equalsIgnoreCase(System.getenv("NAFTIKO_SCRIPTING"));

    /**
     * Called by the capability loader when a capability containing
     * script steps is being initialized — before any request is served.
     */
    static void requireScriptingPermitted(String stepName) {
        if (!SCRIPTING_PERMITTED) {
            throw new IllegalStateException(
                "Script step '" + stepName
                + "' requires scripting support. "
                + "Scripting is disabled via NAFTIKO_SCRIPTING=false.");
        }
    }

    JsonNode execute(OperationStepScriptSpec scriptStep, ...) {
        // At this point, SCRIPTING_PERMITTED is guaranteed true
        // (checked at capability load time)
        // ... create polyglot context, execute script
    }
}
```

The capability loader scans steps during initialization:

```java
// During capability loading
for (OperationStepSpec step : tool.getSteps()) {
    if (step instanceof OperationStepScriptSpec scriptStep) {
        ScriptStepExecutor.requireScriptingPermitted(scriptStep.getName());
    }
}
```

This ensures:
1. The check happens **once at startup**, not on every request
2. Capabilities without script steps **never trigger the check**
3. Polyglot classes are **never loaded** unless a script step actually executes

---

## Native Image Considerations

GraalVM polyglot languages are compatible with native image but require:

1. Truffle language JARs on the module path at build time
2. `--language:js` (and later `--language:python`) flags in `native-image` args
3. Groovy runs on standard JVM bytecode — no Truffle dependency — so it adds minimal native image overhead
4. Significant increase in native binary size (~100 MB+ for JavaScript alone)

For the native CLI build, polyglot dependencies can be excluded via an optional Maven profile (`-Pno-scripting`) to avoid inflating the binary. The Docker image (JVM-based) always includes them. The runtime `NAFTIKO_SCRIPTING` flag works identically in both contexts — if the native binary was built without polyglot and a script step is encountered, the missing class triggers a clear error.

---

## Testing Strategy

### Unit Tests

| Test Class | Package | Purpose |
|---|---|---|
| `OperationStepScriptDeserializationTest` | `io.naftiko.spec.exposes` | YAML → `OperationStepScriptSpec` deserialization, round-trip, validation |
| `ScriptStepExecutorTest` | `io.naftiko.engine.exposes` | JavaScript, Groovy, and Python execution, `result` binding, type mapping, `with` injection |
| `ScriptStepSecurityTest` | `io.naftiko.engine.exposes` | Host access denied, I/O blocked, statement limit enforced, timeout enforced |

### Integration Tests

| Test Class | Package | Purpose |
|---|---|---|
| `ScriptStepIntegrationTest` | `io.naftiko.engine.exposes` | Full `call` → `script` → `call` pipeline via MCP tool |
| `ScriptStepRestIntegrationTest` | `io.naftiko.engine.exposes.rest` | Full pipeline via REST operation |
| `AggregateScriptStepIntegrationTest` | `io.naftiko.engine.aggregates` | Script step in aggregate function, exposed via `ref` |

### Test Fixtures

| Fixture | Location | Description |
|---|---|---|
| `script-step-capability.yaml` | `src/test/resources/` | MCP capability with `call` + `script` steps |
| `script-step-rest-capability.yaml` | `src/test/resources/` | REST capability with `call` + `script` steps |
| `script-step-aggregate-capability.yaml` | `src/test/resources/` | Aggregate function with script step, exposed via both MCP and REST |
| `script-step-dependencies-capability.yaml` | `src/test/resources/` | Capability using `dependencies` for dependent scripts |
| `script-step-shared-capability.yaml` | `src/test/resources/` | Two tools sharing the same `location` directory to verify reuse |
| `scripts/filter-active.js` | `src/test/resources/scripts/` | JavaScript script file for basic tests |
| `scripts/compute-totals.js` | `src/test/resources/scripts/` | JavaScript script file for REST operation tests |
| `scripts/lib/array-utils.js` | `src/test/resources/scripts/lib/` | Shared helper for dependent script tests |
| `scripts/active-members.js` | `src/test/resources/scripts/` | Main script that depends on `array-utils.js` |
| `scripts/enrich-products.groovy` | `src/test/resources/scripts/` | Groovy script file for file-based tests |
| `scripts/lib/pricing.groovy` | `src/test/resources/scripts/lib/` | Shared Groovy helper for dependent script tests |

### Key Test Scenarios

**Script execution:**

1. **JavaScript script filters array** — `call` fetches list, `script` filters via external file, output is accessible via mapping
2. **JavaScript script computes derived value** — `script` reads multiple step outputs, produces new object
3. **Groovy script transforms data** — `script` uses Groovy closures and collection methods for transformation
4. **Python script transforms data** — same scenarios in Python (Phase 2)
5. **`with` injection resolves Mustache** — `with` values are resolved before injection into script context
6. **Missing `result` produces null** — script that does not assign `result` yields no step output
7. **Cross-step reference works** — `context['previous-step']` reads previous step output

**File resolution (aligned with Skill adapter):**

8. **Script file loads from `location` + `file`** — `location` directory + relative `file` path resolves correctly
9. **Dependent scripts are pre-evaluated** — helper functions defined in `dependencies` (relative paths) are available in the main script
10. **Multiple dependent scripts load in order** — second script can reference functions from first script
11. **Missing file produces error** — non-existent `file` path causes descriptive runtime error
12. **Missing dependent script produces error** — non-existent `dependencies` path causes descriptive runtime error
13. **Path traversal is rejected** — `file: "../../etc/passwd"` is rejected by segment validation (same as Skill adapter)
14. **Unsafe path segments rejected** — segments with spaces, `..`, or special chars are rejected
15. **Scripts shared across tools** — two tools in different capabilities using the same `location` directory both resolve correctly

**Security:**

13. **Statement limit terminates loop** — `while(true){}` is killed after limit
14. **Host access is denied** — `java.lang.System.exit(1)` or equivalent throws `PolyglotException` (JS) or `SecurityException` (Groovy)
15. **I/O is blocked** — `require('fs')` (JS) / `new File(...)` (Groovy) / `open('file')` (Python) throws
16. **Unsupported language fails fast** — `language: ruby` is rejected at schema validation

**Schema validation:**

16. **Missing `location` rejected** — omitting `location` is rejected by schema (`required`)
17. **Missing `file` rejected** — omitting `file` is rejected by schema (`required`)
18. **Unsupported language fails fast** — `language: ruby` is rejected at schema validation

---

## Implementation Roadmap

### Phase 1 — JavaScript and Groovy Script Step

**Scope**: Schema + Spec + `ScriptStepExecutor` + JavaScript + Groovy + runtime feature flag + fail-fast + unit/integration tests

| Task | Component | Description |
|---|---|---|
| 1.1 | Schema | Add `"script"` to `OperationStepBase.type.enum`, add `OperationStepScript` definition, update `OperationStep.oneOf` |
| 1.2 | Spec | Create `OperationStepScriptSpec` class, register in `@JsonSubTypes` |
| 1.3 | Engine | Create `ScriptStepExecutor` with GraalVM polyglot API (JS) and Groovy `GroovyShell` API, sandbox config, `result` extraction |
| 1.4 | Engine | Extract shared `SafePathResolver` from `SkillServerResource.resolveAndValidate()` — reuse in `ScriptStepExecutor` |
| 1.5 | Engine | Add `case OperationStepScriptSpec` to `OperationStepExecutor.executeSteps()` |
| 1.6 | Dependencies | Add `polyglot` + `js` + `groovy` dependencies to `pom.xml` (always included in build) |
| 1.7 | Engine | Implement two-level activation: `NAFTIKO_SCRIPTING` env var gate + capability-level detection of `script` steps at load time; fail-fast with clear error when gate is closed |
| 1.8 | Tests | Deserialization, execution, security, integration tests (JavaScript + Groovy) |
| 1.9 | Tests | Fail-fast test: verify clear error when scripting is disabled and a `script` step is loaded |
| 1.10 | Rules | Add `naftiko-script-defaults-required` Spectral rule + `script-defaults-required` custom function |
| 1.11 | Examples | Add example capability YAML to `src/main/resources/schemas/examples/` |

### Phase 2 — Python Support

**Scope**: Add Python language pack + Python-specific tests + optional pre-built virtual environment

| Task | Component | Description |
|---|---|---|
| 2.1 | Dependencies | Add `python` language pack to `pom.xml` |
| 2.2 | Engine | Verify `result` extraction works for Python (GraalPython uses different scoping) |
| 2.3 | Tests | Python-specific unit and integration tests |
| 2.4 | Docker | Pre-install a curated GraalPython virtual environment in the Docker image with managed NumPy (no C extensions). Add an optional `pythonPath` property on the script step to reference the venv. Sandbox allows read-only access to the venv directory only — no general I/O |
| 2.5 | Tests | Integration tests verifying NumPy availability when `pythonPath` is set, and clear error when it is not |

**Note on third-party libraries**: GraalPython ships a managed NumPy implementation that works without native C extensions — sufficient for array math, basic linear algebra, and statistical operations. For the typical orchestration use case (filter, reshape, aggregate between API calls), Python builtins are enough. The pre-built venv is an optional enhancement for more advanced numerical processing.

### Phase 3 — Control Port Governance

**Scope**: Expose scripting configuration and observability through the Control Port management plane

The Control Port already provides management toggles (`health`, `info`, `reload`, `validate`, `logs`) and observability endpoints (`metrics`, `traces`). Phase 3 extends this pattern to scripting — giving operators runtime visibility and fine-grained control without restarting the container.

#### Schema Changes

Add a `scripting` object to the `ExposesControl.management` definition:

```json
"scripting": {
  "type": "object",
  "description": "Scripting governance controls exposed via the Control Port.",
  "properties": {
    "enabled": {
      "type": "boolean",
      "default": false,
      "description": "Runtime toggle to enable or disable script step execution. When false, all script steps fail fast with a descriptive error — same behavior as NAFTIKO_SCRIPTING=false. Overrides the env var when set."
    },
    "defaultLocation": {
      "type": "string",
      "format": "uri",
      "pattern": "^file:///",
      "description": "Default scripts directory for all script steps. When set, individual steps can omit 'location' and the engine resolves 'file' and 'dependencies' from this directory. Steps with an explicit 'location' override this default."
    },
    "defaultLanguage": {
      "type": "string",
      "enum": ["javascript", "python", "groovy"],
      "description": "Default language for all script steps. When set, individual steps can omit 'language' and the engine uses this default. Steps with an explicit 'language' override this default."
    },
    "timeout": {
      "type": "integer",
      "minimum": 1,
      "default": 5000,
      "description": "Execution timeout in milliseconds for each script step. Overrides the engine default (5000 ms)."
    },
    "statementLimit": {
      "type": "integer",
      "minimum": 1,
      "default": 100000,
      "description": "Maximum number of statements a script can execute. Overrides the engine default (100,000)."
    },
    "allowedLanguages": {
      "type": "array",
      "items": {
        "type": "string",
        "enum": ["javascript", "python", "groovy"]
      },
      "description": "Restrict which script languages are permitted. If omitted, all languages supported by the build are allowed."
    }
  },
  "additionalProperties": false
}
```

Capability YAML example:

```yaml
capability:
  exposes:
    - type: control
      address: localhost
      port: 9090
      management:
        health: true
        info: true
        scripting:
          enabled: true
          defaultLocation: "file:///app/capabilities/scripts"
          defaultLanguage: javascript
          timeout: 3000
          statementLimit: 50000
          allowedLanguages:
            - javascript
```

With `defaultLocation` and `defaultLanguage` set, script steps can omit both `location` and `language`:

```yaml
# location and language inherited from Control Port defaults
- type: script
  name: filter-active
  file: "filter-active.js"

# explicit overrides when a step differs from the defaults
- type: script
  name: enrich
  language: groovy
  location: "file:///app/other-scripts"
  file: "enrich.groovy"
```

#### Control Port Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/scripting` | GET | Returns current scripting configuration: enabled state, timeout, statement limit, allowed languages, execution stats |
| `/scripting` | PUT | Updates scripting configuration at runtime (e.g., toggle `enabled`, adjust `timeout`) — takes effect on next script execution |

Example `GET /scripting` response:

```json
{
  "enabled": true,
  "defaultLocation": "file:///app/capabilities/scripts",
  "defaultLanguage": "javascript",
  "timeout": 3000,
  "statementLimit": 50000,
  "allowedLanguages": ["javascript"],
  "stats": {
    "totalExecutions": 142,
    "totalErrors": 3,
    "averageDurationMs": 12,
    "lastExecutionAt": "2026-04-21T14:30:00Z"
  }
}
```

Example `PUT /scripting` request:

```json
{
  "enabled": false
}
```

#### Activation Precedence

Phase 3 introduces a third level to the activation model:

| Priority | Level | Mechanism | Scope |
|---|---|---|---|
| 1 (highest) | Control Port | `management.scripting.enabled` in YAML or `PUT /scripting` | Per-capability, runtime-adjustable |
| 2 | Infrastructure | `NAFTIKO_SCRIPTING` env var (default: `true` in Docker) | Per-container, set at deployment |
| 3 (lowest) | Capability | Presence of `type: script` steps | Per-capability, set at design time |

Resolution logic:
- If the Control Port `scripting.enabled` is explicitly set, it **overrides** the env var
- If not set in the Control Port, the env var is the gate (Phase 1 behavior)
- The presence of `script` steps in YAML remains the trigger — no steps, no check

This means an operator can:
- **Enable scripting** via Control Port even if the env var is `false` (emergency unlock)
- **Disable scripting** via Control Port even if the env var is `true` (default) — emergency kill switch
- **Adjust timeout and statement limits** without restarting the container
- **Restrict languages** to a subset (e.g., allow only `javascript` in production)

#### Implementation Tasks

| Task | Component | Description |
|---|---|---|
| 3.1 | Schema | Add `scripting` object to `ExposesControl.management` (with `defaultLocation` and `defaultLanguage`) |
| 3.2 | Spec | Create `ScriptingManagementSpec` class, integrate into `ControlManagementSpec` |
| 3.3 | Engine | Create `ScriptingResource` for `GET /scripting` and `PUT /scripting` endpoints |
| 3.4 | Engine | Wire Control Port `scripting.enabled` into `ScriptStepExecutor` activation logic — override env var when present |
| 3.5 | Engine | Make `timeout` and `statementLimit` configurable via `ScriptingManagementSpec` — pass to `ResourceLimits` and watchdog |
| 3.6 | Engine | Implement `allowedLanguages` filter — reject script steps with non-permitted languages at load time |
| 3.7 | Engine | Implement `defaultLocation` and `defaultLanguage` fallback — resolve `file`, `dependencies`, and `language` from defaults when step omits them |
| 3.8 | Engine | Add execution stats tracking (total executions, errors, average duration) |
| 3.9 | Tests | Unit tests for `ScriptingResource`, activation precedence, runtime config updates |
| 3.10 | Tests | Integration test: toggle scripting via `PUT /scripting`, verify behavior change without restart |
| 3.11 | Tests | Integration test: script step without `location` resolves from `defaultLocation` |
| 3.12 | Tests | Integration test: script step without `language` resolves from `defaultLanguage` |

#### Acceptance Criteria

19. `management.scripting` is accepted in Control Port YAML with `enabled`, `defaultLocation`, `defaultLanguage`, `timeout`, `statementLimit`, and `allowedLanguages`.
20. `GET /scripting` returns current configuration (including `defaultLocation` and `defaultLanguage`) and execution stats.
21. `PUT /scripting` updates configuration at runtime — takes effect on next script execution.
22. Control Port `scripting.enabled` overrides `NAFTIKO_SCRIPTING` env var when set.
23. `allowedLanguages` restricts permitted script languages at load time.
24. `timeout` and `statementLimit` are applied to `ResourceLimits` and watchdog.
25. When `defaultLocation` is set, script steps without an explicit `location` resolve from it.
26. When `defaultLocation` is not set and a script step omits `location`, the engine fails with a clear error.
27. When `defaultLanguage` is set, script steps without an explicit `language` use it.
28. When `defaultLanguage` is not set and a script step omits `language`, the engine fails with a clear error.

---

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **GraalVM polyglot JAR size** (~42 MB for JS) | High | Medium | Single image; scripting enabled by default, governed via Control Port; no runtime cost unless YAML uses `script` steps |
| **Script timeout not enforced reliably** | Low | High | GraalVM `ResourceLimits` + watchdog thread with `Context.close(true)` |
| **Python `result` scoping differs from JS** | Medium | Low | Phase 2 handles Python-specific extraction; unit tests validate |
| **Groovy sandbox limitations** | Medium | Medium | Groovy uses `GroovyShell` with `SecureASTCustomizer` + `CompilerConfiguration` to restrict language features; no host class imports, no I/O classes |
| **GraalVM version incompatibility** | Low | Medium | Pin version in property; test in CI |
| **Polyglot context creation overhead** | Medium | Medium | Measure per-request overhead; consider `Engine` caching if significant |
| **Capability authors write non-terminating scripts** | Medium | Medium | Statement limit + timeout; error message identifies the offending step |
| **Path traversal in `file` or `dependencies` paths** | Low | High | `resolveAndValidate()` with segment allowlist + prefix check — same as Skill adapter |
| **External script file not found at runtime** | Medium | Low | Fail with descriptive error identifying step name and file path |

---

## Acceptance Criteria

### Phase 1

1. `type: script` is accepted in `steps` arrays for MCP tools, REST operations, and aggregate functions.
2. JavaScript and Groovy scripts execute in a sandboxed context with no host access.
3. Script output is stored in `StepExecutionContext` and accessible to subsequent steps and mappings.
4. `with` parameters are resolved and injected into the script context.
5. Statement limit prevents infinite loops.
6. Scripts are loaded from external files via `location` (directory) + `file` (relative path).
7. Dependent scripts (`dependencies`) are pre-evaluated before the main script.
8. Path traversal in `file` and `dependencies` paths is rejected using the same `resolveAndValidate()` logic as the Skill adapter.
9. `file` is required (schema-enforced); `location` and `language` are optional when `defaultLocation` / `defaultLanguage` are configured in the Control Port.
10. Multiple tools and capabilities can share the same `location` directory for script reuse.
11. `resolveAndValidate()` is extracted into a shared utility used by both Skill adapter and Script step.
12. Scripting uses two-level activation: `NAFTIKO_SCRIPTING` env var (default `true` in Docker, infrastructure gate) + presence of `type: script` steps in YAML (capability trigger) — single Docker image, zero overhead when no `script` steps are used.
13. Capabilities with `script` steps fail fast at load time with a clear, actionable error when `NAFTIKO_SCRIPTING` is set to `false`.
14. Capabilities without `script` steps never trigger scripting checks or load polyglot classes, regardless of `NAFTIKO_SCRIPTING`.
14. All existing tests pass — zero regressions.
15. Deserialization, execution, security, and integration tests all pass.
16. Spectral lint rules accept `type: script` steps.
17. Spectral rule `naftiko-script-defaults-required` reports an error when a `script` step omits `location` or `language` and no corresponding default (`management.scripting.defaultLocation` / `management.scripting.defaultLanguage`) is configured.

### Phase 2

17. Python scripts execute with the same sandbox and result contract.
18. Python-specific tests pass.
