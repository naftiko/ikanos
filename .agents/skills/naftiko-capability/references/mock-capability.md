# Story: Mock a Capability

## User problem

The backend API doesn't exist yet, or you want to prototype a contract-first
design — defining the exposed shape before any upstream service is available.
You need the MCP tool or REST endpoint to return realistic, shaped data so
consumers (agents, developers, tests) can start integrating immediately.

## Naftiko pattern

A **mock capability** is a capability that omits `consumes`, `call`, and
`steps`. Output parameters carry a `value` field instead of a `mapping` field.
The runtime returns the value directly without making any HTTP call.

`value` accepts:
- A **static string**: `value: "Hello, World!"`
- A **Mustache template**: `value: "Hello, {{name}}!"` — resolved against the
  tool's or endpoint's input parameters by name at request time.

Both REST and MCP adapters support mock mode identically — the same output
parameter shape works for both adapter types.

## When to use each

| Situation | `value` |
|---|---|
| Fixed stub data, no inputs needed | Static string |
| Response echoes or interpolates an input | Mustache template `{{paramName}}` |
| Nested object with static leaves | Static string on each leaf scalar |
| Nested object with dynamic leaves | Mustache template on each leaf scalar |

## Minimal MCP mock example

```yaml
naftiko: "1.0.0-alpha1"

capability:
  exposes:
    - type: mcp
      port: 3001
      namespace: mock-tools
      description: Mock MCP server for prototyping
      tools:
        - name: say-hello
          description: Returns a greeting using the provided name
          inputParameters:
            - name: name
              type: string
              required: true
              description: Name to greet
          outputParameters:
            - name: message
              type: string
              value: "Hello, {{name}}! Welcome aboard."
```

## Minimal REST mock example

```yaml
naftiko: "1.0.0-alpha1"

capability:
  exposes:
    - type: rest
      address: localhost
      port: 8080
      namespace: mock-api
      resources:
        - path: /greet
          operations:
            - method: GET
              inputParameters:
                - name: name
                  in: query
                  type: string
                  required: true
              outputParameters:
                - name: message
                  type: string
                  value: "Hello, {{name}}!"
```

## Nested object mock

Nest `type: object` with scalar children carrying `value`. The container
itself has no `value` — only scalar leaves do.

```yaml
outputParameters:
  - name: user
    type: object
    properties:
      - name: id
        type: string
        value: "usr-001"
      - name: displayName
        type: string
        value: "{{name}}"
      - name: role
        type: string
        value: "viewer"
```

## Array mock

Use `type: array` with a single `items` descriptor. The runtime returns
one representative item.

```yaml
outputParameters:
  - name: results
    type: array
    items:
      type: object
      properties:
        - name: id
          type: string
          value: "item-001"
        - name: label
          type: string
          value: "Sample item for {{query}}"
```

## Migrating from mock to real

When the upstream API is ready:

1. Add a `consumes` block with `baseUri`, `namespace`, resources, and operations.
2. Replace `value` with `mapping` on each output parameter (JSONPath expression).
3. Add `call: {namespace}.{operationName}` to the exposed tool or operation.
4. Remove any inputs that were only needed for Mustache interpolation if they
   are now resolved from the upstream response instead.

The exposed contract (tool/resource names, input parameters, output parameter
names and types) does not need to change — consumers see no difference.

## Hard constraints for mock mode

- Use `value`, not `const`. `const` is a JSON Schema keyword for validation;
  it has no runtime effect.
- `value` and `mapping` are mutually exclusive on a scalar output parameter.
- Mustache placeholders resolve against top-level input parameter names only.
  Names remapped via `with` are not in scope for output value resolution.
- Object and array output parameters in mock mode must NOT carry `value` on
  the container — only on the scalar leaf descendants.
- If `call` or `steps` is present alongside output `value` fields and an
  upstream response is available, the upstream response takes precedence;
  `value` is the fallback when no response body exists.
