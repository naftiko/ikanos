# Ikanos JSON Schema

This directory holds the JSON Schema of the **Ikanos Specification**, the YAML format in which
an Ikanos capability declares what it consumes (APIs, MCP servers), what it exposes (MCP, REST,
control adapters), how calls are orchestrated (steps, aggregates) and which variables it binds.

| File | Purpose |
| --- | --- |
| [`ikanos-schema.json`](ikanos-schema.json) | The schema itself (JSON Schema draft 2020-12). It is the source of truth for structure: which keys exist, their types, and which are required. |
| [`examples/`](examples/) | Reference capabilities, validated against the schema by the `ikanos-spec` tests. |

An interactive view of the schema is available in the
[Schema Viewer](https://naftiko.github.io/schema-viewer/).

## Version

The schema version is the `const` of `properties.ikanos` in
[`ikanos-schema.json`](ikanos-schema.json); the schema `$id` carries the same version. Every
capability and shared document starts with the `ikanos` field, set to exactly that value.
Because the field is a `const`, `ikanos validate` and Polychro reject any other value.
The engine does not check it: like the rest of the schema, it is not validated when a
capability loads, so validate a capability before serving it.

## Specification

The field-by-field specification, with examples, is published on Shipyard and versioned with
each release:

- [Schema overview](https://shipyard.naftiko.io/ikanos/latest/spec/): document shapes, core
  objects, imports
- [Consumes](https://shipyard.naftiko.io/ikanos/latest/spec/consumes/): HTTP and MCP sources,
  resources, operations, authentication
- [Exposes](https://shipyard.naftiko.io/ikanos/latest/spec/exposes/): MCP (tools, resources,
  prompts, skills), REST and control adapters
- [Steps](https://shipyard.naftiko.io/ikanos/latest/spec/steps/): orchestration, mappings,
  `with`
- [Aggregates](https://shipyard.naftiko.io/ikanos/latest/spec/aggregates/): reusable domain
  flows and `ref`

Its sources are in [`naftiko/shipyard`](https://github.com/naftiko/shipyard), under
`shipyard-ikanos/docs/spec/`.

These rules are not covered there yet:

- An `apikey` `placement` defaults to `"header"` when omitted, on exposed and consumed adapters
  alike.
- On a consumed adapter, a credential that uses a variable with no value (unbound, misspelled,
  or bound to an empty value) is never sent: the call fails with an error naming the variable,
  and a warning names it when the capability loads.
- In a `json` body whose `data` is an object, a value that is exactly one placeholder takes
  the parameter's JSON type (string, number, boolean, object, array) and is left out when the
  parameter is absent; any other string stays a JSON string. A placeholder in a key is
  resolved too, but two members that resolve to the same key fail the call. In a string
  template, substituted values are JSON-encoded, so string placeholders must be quoted.
- In a `multipartForm` part whose `contentType` is `application/json` or a `+json` type,
  substituted values are JSON-encoded and the rendered `value` must be a single JSON document
  with no repeated member key, as for a `json` body.
- A `with:` value, or a parameter `value` or `template`, that is exactly one placeholder
  (`"{{statuses}}"`, `"{{ statuses }}"`, `"{{{statuses}}}"`, `"{{&statuses}}"` or a dotted path
  such as `"{{step.ids}}"`) passes the value itself, so a list, a number or a boolean keeps its
  type. When that variable is absent, the `with:` key is removed, even if a caller argument of
  the same name is there, and the parameter is left out. Any other template is rendered to a
  string.
- In a script step's `with:`, such a value reaches the script with its type, not as JSON text: a
  script that parsed it (`JSON.parse(ids)` in JavaScript, `json.loads(ids)` in Python) must use
  it directly. When the variable is absent, a script step still binds the rendered text (`""`).

## Validating a capability

- `ikanos validate <file>` checks a capability against this schema, then the consumed templates
  as the engine renders them.
- [Polychro](https://github.com/naftiko/polychro) lints it against this schema and the
  [Ikanos rules](../rules/ikanos-rules.yml), which add checks the schema cannot express.

## Changing the schema

When the schema changes:

- Update `ikanos-schema.json`, then the Java model in `io.ikanos.spec` if the structure changes.
- Add or update the schema validation tests, and the examples here when a reference capability
  helps.
- Update the Shipyard specification pages in `naftiko/shipyard`.
- See [`architecture.md`](../../../../../ikanos-docs/architecture.md), *Spec and
  validation*, for how the schema, the model and the rules fit together.
