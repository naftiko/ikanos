# Inline Script Step
## Polyglot Scripting in Orchestration Steps via GraalVM

**Status**: Proposal  
**Date**: April 10, 2026  
**Spec Version**: `1.0.0-alpha2`  
**Key Concept**: Add a new `type: script` orchestration step that executes JavaScript or Python code — inline or from external files — via the GraalVM Polyglot API, enabling lightweight data transformation, filtering, and enrichment between `call` and `lookup` steps — without deploying additional services.

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

Add a new `type: script` step to the `OperationStep` discriminated union. A script step executes JavaScript or Python code — provided inline via `source` or loaded from an external file via `location` — inside a sandboxed GraalVM polyglot context and stores the result in the step execution context — exactly like `call` and `lookup` steps. External scripts can declare dependent modules via `modules`, enabling reuse of shared libraries and helper functions across capabilities.

Script steps can be used anywhere `steps` are accepted:

- **MCP tools** (`capability.exposes[type=mcp].tools[].steps`)
- **REST operations** (`capability.exposes[type=rest].resources[].operations[].steps`)
- **Aggregate functions** (`capability.aggregates[].functions[].steps`)

### Why This Fits Naftiko

Today, orchestration steps can dispatch HTTP calls (`call`) and cross-reference previous results (`lookup`). Any data transformation — filtering, reshaping, conditional logic, enrichment — requires either a dedicated consumed API endpoint or pre-computed server-side logic.

A `script` step fills the gap between "fetch data" and "expose data" by enabling transformations — either inline for simple logic, or from external files for reusable/complex scripts. This keeps Naftiko's declarative-first model intact while allowing procedural escape hatches when pure mapping is insufficient.

The step follows the same contract as existing step types:
- It has a `name` used as namespace in the step context
- It reads from `runtimeParameters` and `StepExecutionContext` (previous step outputs)
- It writes its result to `StepExecutionContext` under its `name`
- Subsequent steps and mappings reference it via `{{step-name.field}}` or `$.step-name.field`

### Value

| Benefit | Impact |
|---|---|
| **Inline transformation** | Filter, reshape, and enrich data between API calls without additional services |
| **External script files** | Load scripts from `file:///` URIs for reusable, testable, version-controlled logic |
| **Dependent modules** | Declare supporting script files that are pre-evaluated before the main script |
| **Polyglot** | JavaScript and Python — two of the most common scripting languages |
| **Sandboxed execution** | GraalVM polyglot context with no host access, no I/O, no network — safe by default |
| **Consistent step model** | Same `name` → context → reference pattern as `call` and `lookup` |
| **Aggregate-compatible** | Works in aggregate functions, so transformations are reusable across adapters |

### Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| GraalVM polyglot JAR size | High | Medium | Optional Maven profile; exclude from native CLI build |
| Script timeout / infinite loop | Medium | High | Statement limit + execution timeout via `ResourceLimits` |
| Python language pack maturity | Medium | Low | Phase 2; JavaScript first |
| Native image compatibility | Medium | Medium | Optional profile; polyglot adds significant binary size |

---

## Goals and Non-Goals

### Goals

1. Introduce `script` as a new `OperationStep` type alongside `call` and `lookup`.
2. Support `javascript` and `python` as script languages via GraalVM Polyglot API.
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
5. Groovy language support — deferred to a future proposal.
6. Remote script locations (e.g., `https://`) — only `file:///` URIs are supported.

---

## Terminology

| Term | Definition |
|---|---|
| **Script step** | An `OperationStep` with `type: "script"` that executes code inline or from file |
| **Language** | The GraalVM language identifier: `javascript` or `python` |
| **Source** | The inline script body — a string containing valid code in the chosen language. Mutually exclusive with `location` |
| **Location** | A `file:///` URI pointing to the main script file. Mutually exclusive with `source` |
| **Modules** | An array of `file:///` URIs pointing to dependent script files (helpers, libraries). Pre-evaluated in order before the main script |
| **Context bindings** | Variables injected into the script: `context` (all runtime parameters + previous step outputs) and `with` overrides |
| **Result binding** | The `result` variable that the script assigns to return its output |
| **Sandbox** | GraalVM polyglot `Context` configured with `allowAllAccess(false)` — no host classes, no I/O, no threads |

---

## Design Analogy

### How step types map to their purpose

```
call step                    lookup step                   script step (proposed)
─────────                    ───────────                   ─────────────────────
Dispatches HTTP request      Cross-references step output  Executes inline code
to consumed operation        against previous step data    for data transformation

Input:                       Input:                        Input:
├─ call (namespace.op)       ├─ index (step name)          ├─ language (javascript|python)
├─ with (param injection)    ├─ match (key field)          ├─ source (inline) OR
│                            ├─ lookupValue (expression)   │  location (file:/// URI)
│                            └─ outputParameters (fields)  ├─ modules (dependent files)
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
| Simple inline transformation (filter, reshape, compute) | `script` with `source` |
| Reusable or complex transformation logic | `script` with `location` |
| Transformation with shared helper libraries | `script` with `location` + `modules` |

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
        ├── 1. Resolve source:
        │      inline (source) OR file (location)
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
        ├── 5. Evaluate modules in order (if present)
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
          "enum": ["javascript", "python"],
          "description": "GraalVM language identifier for the script engine."
        },
        "source": {
          "type": "string",
          "description": "Inline script body. Must assign to the 'result' variable to produce output. Mutually exclusive with 'location'.",
          "minLength": 1
        },
        "location": {
          "type": "string",
          "format": "uri",
          "pattern": "^file:///",
          "description": "file:/// URI pointing to the main script file. The file must assign to the 'result' variable to produce output. Mutually exclusive with 'source'."
        },
        "modules": {
          "type": "array",
          "description": "Dependent script files pre-evaluated in order before the main script. Each module can define helper functions, constants, or shared logic. Only allowed when 'location' is used.",
          "items": {
            "type": "string",
            "format": "uri",
            "pattern": "^file:///"
          },
          "minItems": 1
        },
        "with": {
          "$ref": "#/$defs/WithInjector"
        }
      },
      "required": ["language"],
      "oneOf": [
        {
          "required": ["source"],
          "not": { "required": ["location"] }
        },
        {
          "required": ["location"],
          "not": { "required": ["source"] }
        }
      ],
      "unevaluatedProperties": false
    }
  ]
}
```

**Key constraints:**
- `source` and `location` are mutually exclusive (enforced via `oneOf`)
- `modules` is only meaningful with `location` (validated by Spectral rule, not schema — to keep the schema simple)
- `modules` are evaluated in order before the main script, so helper functions are available when the main script runs

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
| `OperationStepScript` | New definition (with `source` / `location` oneOf, `modules`, `with`) |
| `OperationStep.oneOf` | Add `{ "$ref": "#/$defs/OperationStepScript" }` |

No changes to `ExposedOperation`, `McpTool`, `AggregateFunction`, or any other definition — they already accept `OperationStep` via `steps`, which gains the new variant automatically.

---

## Capability YAML Examples

### Example 1 — JavaScript filter in MCP tool

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
              source: |
                var members = context['list-members'];
                var active = [];
                for (var i = 0; i < members.length; i++) {
                  if (members[i].type === 'User') {
                    active.push({ login: members[i].login, id: members[i].id });
                  }
                }
                result = active;
          outputParameters:
            - name: active-members
              type: array
              mapping: "$.filter-active"

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
    source: |
      var orders = context['get-orders'];
      var total = 0;
      var count = 0;
      for (var i = 0; i < orders.length; i++) {
        total += orders[i].amount;
        count++;
      }
      result = { totalAmount: total, orderCount: count };

  - type: call
    name: update-summary
    call: shop.update-customer-summary
    with:
      customer-id: "{{customerId}}"
      total-amount: "{{compute-totals.totalAmount}}"
      order-count: "{{compute-totals.orderCount}}"
```

### Example 3 — Python script step

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
    source: |
      readings = context['fetch-readings']
      values = [r['temperature'] for r in readings]
      result = {
        'average': sum(values) / len(values),
        'min': min(values),
        'max': max(values),
        'count': len(values)
      }
```

### Example 4 — Script step in aggregate function

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
              source: |
                var events = context['fetch-events'];
                var byType = {};
                for (var i = 0; i < events.length; i++) {
                  var t = events[i].type;
                  byType[t] = (byType[t] || 0) + 1;
                }
                result = { eventsByType: byType, totalEvents: events.length };
          outputParameters:
            - name: events-by-type
              type: object
              mapping: "$.summarize.eventsByType"
            - name: total-events
              type: number
              mapping: "$.summarize.totalEvents"

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

```yaml
steps:
  - type: call
    name: fetch-items
    call: inventory.list-items

  - type: script
    name: filter-by-threshold
    language: javascript
    with:
      threshold: "{{minStock}}"
    source: |
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

### Example 6 — External script file via `location`

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
steps:
  - type: call
    name: list-members
    call: github.list-org-members
    with:
      org: "{{org}}"

  - type: script
    name: filter-active
    language: javascript
    location: "file:///scripts/filter-active.js"
```

### Example 7 — External script with dependent modules

Shared helper module at `scripts/lib/array-utils.js`:
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
    modules:
      - "file:///scripts/lib/array-utils.js"
    location: "file:///scripts/active-members.js"
```

### Example 8 — Python external script with modules

Helper module at `scripts/lib/stats.py`:
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
    modules:
      - "file:///scripts/lib/stats.py"
    location: "file:///scripts/analyze-readings.py"
```

---

## Runtime Design

### New Java Classes

| Class | Package | Description |
|---|---|---|
| `OperationStepScriptSpec` | `io.naftiko.spec.exposes` | Spec POJO: `language`, `source`, `location`, `modules`, `with`. Extends `OperationStepSpec` |
| `ScriptStepExecutor` | `io.naftiko.engine.exposes` | Stateless executor. Loads source (inline or file), evaluates modules, runs script in sandboxed GraalVM `Context`, extracts `result` |

### OperationStepScriptSpec

```java
@JsonTypeName("script")
public class OperationStepScriptSpec extends OperationStepSpec {
    private volatile String language;
    private volatile String source;      // mutually exclusive with location
    private volatile String location;    // mutually exclusive with source
    private final List<String> modules = new CopyOnWriteArrayList<>();
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

        // 1. Resolve script source: inline or file
        String mainSource = resolveSource(scriptStep);

        // 2. Build bindings: merge runtimeParameters + step outputs + with
        Map<String, Object> bindings = buildBindings(
            runtimeParameters, stepContext, scriptStep.getWith());

        // 3. Create sandboxed polyglot context
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

            // 4. Inject bindings
            Value contextBinding = context.eval(language,
                buildBindingExpression(language, bindings));
            context.getBindings(language).putMember("context", contextBinding);

            // 5. Evaluate dependent modules in order
            if (scriptStep.getModules() != null) {
                for (String moduleUri : scriptStep.getModules()) {
                    String moduleSource = readFileUri(moduleUri);
                    context.eval(language, moduleSource);
                }
            }

            // 6. Evaluate main script
            context.eval(language, mainSource);

            // 7. Extract result
            Value resultValue = context.getBindings(language).getMember("result");
            return convertToJsonNode(resultValue);
        }
    }

    private String resolveSource(OperationStepScriptSpec step) {
        if (step.getSource() != null) {
            return step.getSource();
        }
        return readFileUri(step.getLocation());
    }

    private String readFileUri(String fileUri) {
        // Parse file:/// URI, validate path is within allowed base directory,
        // read file contents as UTF-8 string
        // Throws IllegalArgumentException if file not found or path traversal detected
    }
}
```

### File Resolution Rules

1. **`file:///` URIs are resolved relative to the capability file's directory** — e.g., if the capability is at `/app/capabilities/my-cap.yml`, then `file:///scripts/filter.js` resolves to `/app/capabilities/scripts/filter.js`
2. **Path traversal is rejected** — resolved paths must remain within the capability's base directory. Paths containing `..` that escape the base directory cause a load-time error
3. **Files are read at step execution time** — not cached across requests (caching is a future optimization)
4. **UTF-8 encoding is assumed** for all script files
5. **Missing files cause a runtime error** with a descriptive message identifying the step name and file URI

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

The GraalVM polyglot context is configured with maximum restriction:

| Capability | Setting | Effect |
|---|---|---|
| Host class access | `allowHostAccess(HostAccess.NONE)` | No access to Java classes from scripts |
| I/O access | `allowIO(IOAccess.NONE)` | No file system read/write |
| Thread creation | `allowCreateThread(false)` | No spawning threads |
| Process creation | `allowCreateProcess(false)` | No executing system commands |
| Environment access | `allowEnvironmentAccess(EnvironmentAccess.NONE)` | No reading environment variables |
| Network access | `allowAllAccess(false)` | No network sockets |
| Native access | `allowNativeAccess(false)` | No native library loading |

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
| **Code injection** | End-user input interpolated into source | `source` is authored by capability designer, not end-user input. Runtime data is injected as data bindings (not string concatenation), preventing injection |
| **Data exfiltration** | Script sends data to external endpoint | No I/O, no network, no process creation — all egress blocked |
| **Environment leakage** | Script reads secrets from env vars | `EnvironmentAccess.NONE` — environment is invisible |
| **Path traversal** | `location` or `modules` URI escapes base directory | Resolved paths are validated to remain within the capability file's directory; `..` escapes are rejected |
| **Symlink escape** | Symlink in script directory points outside base | Resolved path is canonicalized before validation; symlinks that escape are rejected |

### Trust Boundary

The `source` field and external script files referenced by `location` and `modules` are **design-time artifacts** written by the capability author — the same trust boundary as `call` references and `mapping` expressions. They are not influenced by end-user request input. Runtime parameters are injected as structured data bindings, never interpolated into the source string.

File-based scripts (`location` and `modules`) are read from the local file system relative to the capability file. The engine validates that resolved paths do not escape the capability's base directory, preventing path traversal attacks.

---

## Dependency Changes

### New Dependencies (`pom.xml`)

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
</properties>
```

### JAR Size Impact

| Dependency | Approximate Size |
|---|---|
| `polyglot` API | ~2 MB |
| `js` language pack | ~40 MB |
| `python` language pack | ~80 MB |

Total impact for Phase 1 (JavaScript only): **~42 MB** added to the uber-JAR.

---

## Native Image Considerations

GraalVM polyglot languages are compatible with native image but require:

1. Truffle language JARs on the module path at build time
2. `--language:js` (and later `--language:python`) flags in `native-image` args
3. Significant increase in native binary size (~100 MB+ for JavaScript alone)

### Recommendation

Make polyglot support an **optional Maven profile** (`-Pscripting`) that is excluded from the default native CLI build. The standard docker image includes it; the native CLI does not.

```xml
<profile>
    <id>scripting</id>
    <dependencies>
        <dependency>
            <groupId>org.graalvm.polyglot</groupId>
            <artifactId>polyglot</artifactId>
            <version>${graalvm.polyglot.version}</version>
        </dependency>
        <dependency>
            <groupId>org.graalvm.polyglot</groupId>
            <artifactId>js</artifactId>
            <version>${graalvm.polyglot.version}</version>
            <type>pom</type>
        </dependency>
    </dependencies>
</profile>
```

When `script` steps are present in a capability but the polyglot runtime is not available, the engine fails fast at capability load time with a clear error message.

---

## Testing Strategy

### Unit Tests

| Test Class | Package | Purpose |
|---|---|---|
| `OperationStepScriptDeserializationTest` | `io.naftiko.spec.exposes` | YAML → `OperationStepScriptSpec` deserialization, round-trip, validation |
| `ScriptStepExecutorTest` | `io.naftiko.engine.exposes` | JavaScript and Python execution, `result` binding, type mapping, `with` injection |
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
| `script-step-capability.yaml` | `src/test/resources/` | MCP capability with `call` + inline `script` steps |
| `script-step-rest-capability.yaml` | `src/test/resources/` | REST capability with `call` + inline `script` steps |
| `script-step-aggregate-capability.yaml` | `src/test/resources/` | Aggregate function with script step, exposed via both MCP and REST |
| `script-step-file-capability.yaml` | `src/test/resources/` | MCP capability with `call` + file-based `script` step |
| `script-step-modules-capability.yaml` | `src/test/resources/` | Capability using `modules` + `location` for dependent scripts |
| `scripts/filter-active.js` | `src/test/resources/scripts/` | External JavaScript script file for file-based tests |
| `scripts/lib/array-utils.js` | `src/test/resources/scripts/lib/` | Shared helper module for module dependency tests |
| `scripts/main-with-module.js` | `src/test/resources/scripts/` | Main script that depends on `array-utils.js` |

### Key Test Scenarios

**Inline scripts:**

1. **JavaScript script filters array** — `call` fetches list, `script` filters, output is accessible via mapping
2. **JavaScript script computes derived value** — `script` reads multiple step outputs, produces new object
3. **Python script transforms data** — same scenarios in Python (Phase 2)
4. **`with` injection resolves Mustache** — `with` values are resolved before injection into script context
5. **Missing `result` produces null** — script that does not assign `result` yields no step output
6. **Cross-step reference works** — `context['previous-step']` reads previous step output

**File-based scripts:**

7. **External script file executes** — `location` points to `.js` file, script runs and produces output
8. **Modules are pre-evaluated** — helper functions defined in `modules` are available in the main script
9. **Multiple modules load in order** — second module can reference functions from first module
10. **Missing file produces error** — non-existent `location` URI causes descriptive runtime error
11. **Missing module produces error** — non-existent `modules` URI causes descriptive runtime error
12. **Path traversal is rejected** — `location: "file:///../../etc/passwd"` causes load-time error

**Security:**

13. **Statement limit terminates loop** — `while(true){}` is killed after limit
14. **Host access is denied** — `java.lang.System.exit(1)` or equivalent throws `PolyglotException`
15. **I/O is blocked** — `require('fs')` (JS) / `open('file')` (Python) throws
16. **Unsupported language fails fast** — `language: ruby` is rejected at schema validation

**Schema validation:**

17. **`source` and `location` are mutually exclusive** — setting both is rejected by schema
18. **Neither `source` nor `location`** — omitting both is rejected by schema
19. **`modules` without `location`** — validated by Spectral rule (warning or error)

---

## Implementation Roadmap

### Phase 1 — JavaScript Script Step

**Scope**: Schema + Spec + `ScriptStepExecutor` + JavaScript only + unit/integration tests

| Task | Component | Description |
|---|---|---|
| 1.1 | Schema | Add `"script"` to `OperationStepBase.type.enum`, add `OperationStepScript` definition, update `OperationStep.oneOf` |
| 1.2 | Spec | Create `OperationStepScriptSpec` class, register in `@JsonSubTypes` |
| 1.3 | Engine | Create `ScriptStepExecutor` with GraalVM polyglot API, sandbox config, `result` extraction |
| 1.4 | Engine | Add `case OperationStepScriptSpec` to `OperationStepExecutor.executeSteps()` |
| 1.5 | Dependencies | Add `polyglot` + `js` dependencies to `pom.xml` |
| 1.6 | Tests | Deserialization, execution, security, integration tests (JavaScript) |
| 1.7 | Examples | Add example capability YAML to `src/main/resources/schemas/examples/` |

### Phase 2 — Python Support

**Scope**: Add Python language pack + Python-specific tests

| Task | Component | Description |
|---|---|---|
| 2.1 | Dependencies | Add `python` language pack to `pom.xml` |
| 2.2 | Engine | Verify `result` extraction works for Python (GraalPython uses different scoping) |
| 2.3 | Tests | Python-specific unit and integration tests |

### Phase 3 — Native Image Support

**Scope**: Optional Maven profile, CI matrix for scripting vs non-scripting builds

| Task | Component | Description |
|---|---|---|
| 3.1 | Build | Create `-Pscripting` Maven profile |
| 3.2 | Engine | Fail-fast detection when polyglot is unavailable at load time |
| 3.3 | CI | Add matrix entry for scripting profile in Docker build |
| 3.4 | Native | Evaluate native image compatibility, binary size, and performance |

---

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **GraalVM polyglot JAR size** (~42 MB for JS) | High | Medium | Optional Maven profile; Docker image includes it, native CLI does not |
| **Script timeout not enforced reliably** | Low | High | GraalVM `ResourceLimits` + watchdog thread with `Context.close(true)` |
| **Python `result` scoping differs from JS** | Medium | Low | Phase 2 handles Python-specific extraction; unit tests validate |
| **GraalVM version incompatibility** | Low | Medium | Pin version in property; test in CI |
| **Polyglot context creation overhead** | Medium | Medium | Measure per-request overhead; consider `Engine` caching if significant |
| **Capability authors write non-terminating scripts** | Medium | Medium | Statement limit + timeout; error message identifies the offending step |
| **Path traversal in file URIs** | Low | High | Canonicalize + validate resolved paths stay within capability base directory |
| **External script file not found at runtime** | Medium | Low | Fail with descriptive error identifying step name and file URI |

---

## Acceptance Criteria

### Phase 1

1. `type: script` is accepted in `steps` arrays for MCP tools, REST operations, and aggregate functions.
2. JavaScript scripts execute in a sandboxed GraalVM context with no host access.
3. Script output is stored in `StepExecutionContext` and accessible to subsequent steps and mappings.
4. `with` parameters are resolved and injected into the script context.
5. Statement limit prevents infinite loops.
6. Inline scripts (`source`) and external scripts (`location`) both work.
7. Dependent modules (`modules`) are pre-evaluated before the main script.
8. Path traversal in `location` and `modules` URIs is rejected.
9. `source` and `location` are mutually exclusive (schema-enforced).
10. All existing tests pass — zero regressions.
11. Deserialization, execution, security, and integration tests all pass.
12. Spectral lint rules accept `type: script` steps.

### Phase 2

9. Python scripts execute with the same sandbox and result contract.
10. Python-specific tests pass.

### Phase 3

11. `-Pscripting` profile is available for builds that include polyglot support.
12. Capabilities with `script` steps fail fast with a clear message when polyglot is unavailable.
