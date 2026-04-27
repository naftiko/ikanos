---
name: inline-script-step-reference
description: >
  Reference for adding inline script steps to a Naftiko capability. Use when the
  user wants to transform, filter, aggregate, or reshape data between API calls
  using JavaScript, Python, or Groovy — without building a separate microservice.

---

## Why

API responses often need transformation before they can be exposed to consumers.
Common scenarios include:

- **Filtering** — keep only active records from a list
- **Aggregation** — compute statistics (avg, min, max, count) from raw readings
- **Reshaping** — flatten nested objects, rename fields, combine arrays
- **Enrichment** — derive computed fields from raw data

Script steps let you embed these transformations directly in your capability's
orchestration pipeline — between `call` steps — using a sandboxed scripting engine.

## Script Step (type: "script")

A script step executes a script file in a sandboxed GraalVM (JavaScript, Python)
or Groovy (GroovyShell + SecureASTCustomizer) environment. The script reads
previous step results as bound variables and produces output by assigning to a
`result` variable.

### Minimal example

```yaml
steps:
  - type: call
    name: fetch-data
    call: api.get-data
    with:
      id: "{{id}}"

  - type: script
    name: transform
    language: javascript
    location: "file:///app/capabilities/scripts"
    file: "transform.js"
```

The script `transform.js` receives previous step outputs through a single
`context` object. Step names are used as keys exactly as declared, so the
output of `fetch-data` is available as `context["fetch-data"]`. The script
must assign its output to `result`:

```javascript
// transform.js
var fetchData = context["fetch-data"];
var items = fetchData.items;
var active = items.filter(function(item) { return item.status === "active"; });
result = { activeCount: active.length, items: active };
```

This same binding model applies across supported languages: use `context[...]`
to read prior step results, and assign the final value to `result`.

### Full example with dependencies

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
    file: "filter-active.js"
    dependencies:
      - "lib/array-utils.js"
    with:
      org: "{{org}}"
```

Dependencies are pre-evaluated in order before the main script. They can define
helper functions, constants, or shared logic reused across multiple script steps.

### Using `with` for input parameters

The `with` block injects input parameters as additional bound variables:

```yaml
  - type: script
    name: analyze
    language: python
    location: "file:///app/capabilities/scripts"
    file: "analyze-readings.py"
    with:
      threshold: "{{threshold}}"
```

Inside the Python script, `threshold` is available as a variable.

## Schema Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `type` | `const "script"` | Yes | Step type discriminator |
| `name` | `IdentifierKebab` | Yes | Step name, used to reference results in mappings |
| `language` | `enum` | No | `javascript`, `python`, or `groovy`. Falls back to Control Port `defaultLanguage` |
| `location` | `string (uri)` | No | `file:///` URI to the scripts directory. Falls back to Control Port `defaultLocation` |
| `file` | `string` | Yes | Relative path to the main script within `location` |
| `dependencies` | `string[]` | No | Relative paths to pre-evaluated scripts (min 1 if present) |
| `with` | `WithInjector` | No | Input parameter bindings injected as script variables |

## Language Support

| Language | Engine | Language ID | Notes |
|---|---|---|---|
| JavaScript | GraalVM Truffle JS | `javascript` | ES2023 features, no Node.js APIs |
| Python | GraalVM Truffle Python | `python` | Core Python, no pip packages |
| Groovy | GroovyShell + SecureASTCustomizer | `groovy` | Restricted AST — no I/O, no system access |

All three languages share the same execution model: previous step results are
bound as variables, and the script must assign to `result` to produce output.

## Variable Binding

- **Step results** — each previous step's output is bound by step name. For
  example, a step named `fetch-data` produces a variable `fetchData` (camelCased).
- **With injection** — values from the `with` block are bound as additional
  variables by their key name.
- **Result output** — the script MUST assign to the `result` variable. This
  value becomes the step's output, referenceable by subsequent steps and mappings
  (e.g., `$.transform.someField`).

## File Resolution

- `location` is a `file:///` URI pointing to a directory root.
- `file` and `dependencies` are relative paths resolved within that directory.
- Path segments must match `[a-zA-Z0-9._-]+` — no `..`, no absolute paths,
  no symbolic links. The engine enforces path traversal protection.

When `location` is omitted on the step, the engine falls back to
`management.scripting.defaultLocation` on the Control Port. If neither is set,
the Spectral rule `naftiko-script-defaults-required` reports an error.

## Control Port Governance

Script execution can be governed centrally via the Control Port's
`management.scripting` configuration:

```yaml
capability:
  exposes:
    - type: control
      port: 9090
      management:
        scripting:
          enabled: true
          defaultLocation: "file:///app/capabilities/scripts"
          defaultLanguage: javascript
          timeout: 3000
          statementLimit: 50000
          allowedLanguages:
            - javascript
            - python
```

| Field | Type | Default | Description |
|---|---|---|---|
| `enabled` | `boolean` | `true` | Enable/disable all script steps at runtime |
| `defaultLocation` | `string (uri)` | — | Fallback `file:///` location for scripts |
| `defaultLanguage` | `enum` | — | Fallback language (`javascript`, `python`, `groovy`) |
| `timeout` | `integer` | `60000` | Max execution time in milliseconds |
| `statementLimit` | `integer` | `100000` | Max statements per execution (JavaScript and Python only; Groovy scripts are not subject to this limit) |
| `allowedLanguages` | `string[]` | all | Restrict permitted languages |

### Runtime management

The Control Port exposes a `/scripting` endpoint for runtime governance:

- **GET `/scripting`** — returns current config and execution stats
  (`totalExecutions`, `totalFailures`, `lastExecutionTime`, `lastFailureTime`)
- **PUT `/scripting`** — updates config at runtime (e.g., disable scripting,
  change timeout)

The CLI also provides access:

```bash
naftiko scripting                          # Display current config and stats
naftiko scripting --set timeout=60000      # Update a setting at runtime
naftiko scripting --set enabled=false      # Disable scripting
```

## Security Model

Script execution is sandboxed:

- **GraalVM (JS, Python)** — runs in an isolated `Context` with no filesystem,
  network, or host access. Statement limits enforce termination.
- **Groovy** — uses `SecureASTCustomizer` to restrict the AST: no `System`,
  `Runtime`, `ProcessBuilder`, `File`, `Socket`, or reflection APIs.
- **Timeout** — configurable per capability via `management.scripting.timeout`.
- **Statement limit** — configurable via `management.scripting.statementLimit`. Applies to JavaScript and Python only. Groovy scripts are not subject to this limit.

## Common Mistakes

1. **Forgetting to assign to `result`** — the script produces no output and
   subsequent mappings referencing the step return null.
2. **Omitting `language` and `location` without Control Port defaults** — the
   engine cannot determine which language or directory to use. The Spectral rule
   `naftiko-script-defaults-required` catches this at lint time.
3. **Using `..` in file paths** — the engine rejects path traversal attempts.
   All paths must be relative within the `location` directory.
4. **Exceeding statement limit** — long-running or infinite scripts are
  terminated. Increase `statementLimit` if legitimately needed. Applies to JavaScript and Python only. Groovy scripts are not subject to this limit.
5. **Using a language not in `allowedLanguages`** — the engine rejects the
   step at runtime. Ensure the step's language is permitted.

## References

- Schema: `OperationStepScript`, `ScriptingManagementSpec` in
  `src/main/resources/schemas/naftiko-schema.json`
- Spectral rule: `naftiko-script-defaults-required` in
  `src/main/resources/rules/naftiko-rules.yml`
- Example: `src/main/resources/schemas/examples/script-step.yml`
- Blueprint: `src/main/resources/blueprints/inline-script-step.md`
