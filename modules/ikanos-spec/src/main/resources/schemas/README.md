# Ikanos JSON Schema

This directory holds the JSON Schema of the **Ikanos Specification**, the YAML format in which
an Ikanos capability declares what it consumes (APIs, MCP servers), what it exposes (MCP, REST,
control adapters), how calls are orchestrated (steps, aggregates) and which variables it binds.

| File | Purpose |
| --- | --- |
| [`ikanos-schema.json`](ikanos-schema.json) | The schema itself (JSON Schema draft 2020-12, `$id` `https://ikanos.io/schemas/v1.0.0-beta9/ikanos.json`). It is the source of truth for structure: which keys exist, their types, and which are required. |
| [`examples/`](examples/) | Reference capabilities, validated against the schema by the `ikanos-spec` tests. |

An interactive view of the schema is available in the
[Schema Viewer](https://naftiko.github.io/schema-viewer/).

## Version

The schema version is **`1.0.0-beta9`**. Every capability and shared document starts with the
`ikanos` field, which must have exactly that value:

```yaml
ikanos: "1.0.0-beta9"
```

The field is a `const` in the schema, and an engine accepts only its own schema version: a
`1.0.0-beta9` engine loads `1.0.0-beta9` documents only.

## Specification

The field-by-field specification, with examples, is published on Shipyard and versioned with
each release:

- [Schema overview](https://shipyard.naftiko.io/ikanos/spec/): document shapes, core objects,
  imports
- [Consumes](https://shipyard.naftiko.io/ikanos/spec/consumes/): HTTP and MCP sources,
  resources, operations, authentication
- [Exposes](https://shipyard.naftiko.io/ikanos/spec/exposes/): MCP (tools, resources, prompts,
  skills), REST and control adapters
- [Steps](https://shipyard.naftiko.io/ikanos/spec/steps/): orchestration, mappings, `with`
- [Aggregates](https://shipyard.naftiko.io/ikanos/spec/aggregates/): reusable domain flows and
  `ref`

Its sources are in [`naftiko/shipyard`](https://github.com/naftiko/shipyard), under
`shipyard-ikanos/docs/spec/`.

Two credential rules are not covered there yet:

- An `apikey` `placement` defaults to `"header"` when omitted, on exposed and consumed adapters
  alike.
- On a consumed adapter, a credential that uses a variable with no value (unbound, misspelled,
  or bound to an empty value) is never sent: the call fails with an error naming the variable,
  and a warning names it when the capability loads.

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
