---
name: dev-to-production-reference
description: >
  “Dev to production” reference: use this when the user needs the same capability
  to work locally with test credentials and in production with real secrets, without
  hardcoding sensitive values in YAML. This story focuses on modeling secret and
  environment-dependent values using `binds` (variable injection) and wiring those
  variables into consumes/exposes via parameters and `with`.

---

## When to activate this reference

Use this reference when the user wants to:

- run a capability locally with a `file://...` configuration (checked out repo, local env, dev secrets);
- deploy the same capability to production and resolve secrets at runtime (Vault/K8s secrets/GitHub secrets/etc.);
- avoid embedding API keys, passwords, or tokens directly in the capability YAML;
- make auth configuration portable across environments.

Do not use it if:

- there are no secrets and no environment differences (simple static configuration is enough);
- the user is only asking about orchestration logic (use `chain-api-calls` / `reusable-capability`).

## Prerequisites (schema & rules)

Before adding dev/prod secret handling:

- Namespaces must be globally unique across all adapters and bindings.
- Markdown fields must be safe (no `<script` tags, no `eval(` calls).
- HTTP hygiene rules still apply:
    - `consumes.baseUri` must not end with `/`
    - resource `path` must not contain query strings (`?`)
    - exposed REST `path` must not end with `/` and must not contain `?`

## Core concept: `binds` = external variable injection

Ikanos uses `binds` to declare external sources of variables (secrets/config). Variables declared in `binds[*].keys` are injected using mustache-style expressions.

### What a binding provides

A binding defines:

- `namespace`: a unique identifier for the binding (used as a qualifier for disambiguation)
- optional `description`: strongly recommended (context for humans/agents)
- optional `location`: a URI that indicates *how/where* values are resolved
    - with an explicit scheme: `file://`, `vault://`, `k8s-secret://`, `github-secrets://`, etc.
    - when omitted: values are injected by the runtime environment
- `keys`: mapping of variables:
    - key name must be SCREAMING_SNAKE_CASE (schema constraint)
    - value is the provider key identifier (IdentifierExtended)

### Where `binds` can live

The schema supports:

- root-level `binds`
- and/or `capability.binds`

Pick one and be consistent in your project conventions.

## Recommended environment strategy

### Dev (local) strategy

Use `location` with a `file://` URI so values can be resolved from a local file-based provider.

Guidelines:

- keep dev secret files out of git (gitignore);
- avoid storing production secrets locally;
- keep variable names stable across environments (only the provider changes).

### Production strategy

Prefer runtime resolution:

- use a `vault://...`, `k8s-secret://...`, `github-secrets://...` location, or
- omit `location` if your runtime injects them by convention.

Guidelines:

- do not encode tenant-specific values in capability YAML unless they are non-sensitive and truly constant;
- rotate secrets without changing capability code by updating the provider, not the YAML.

## Wiring secrets into consumes/exposes

A binding alone does nothing unless its variables are used. Common wiring patterns:

### Pattern A — Authentication fields reference injected variables

If `authentication` fields accept strings, set them to an injected expression that resolves from `binds` keys.

Rules of thumb:

- never paste an API key/token directly into YAML;
- prefer a binding variable even in dev (so switching environments is trivial).

### Pattern B — Pass secrets via `with`

When a consumed operation expects a header/query/body field for auth:

- declare the consumed input parameter location (`in: header` / `query` / `body`)
- inject values via `with` from binding variables

This is useful when:

- auth is not modeled as `authentication` (or you need per-operation overrides);
- the API expects multiple headers (e.g., key + client id) and you want explicit wiring.

### Pattern C — Environment-dependent baseUri or routing inputs

If dev and prod target different hosts:

- keep `baseUri` stable when possible (recommended),
- otherwise parameterize it via configuration patterns your runtime supports (binding or environment).

Be careful:

- Spectral warns when `baseUri` looks placeholder-ish (e.g., [example.com](http://example.com)).
- Always avoid trailing slashes.

## Constraints (dev-to-prod specific)

1. Do not store secrets in the capability YAML (tokens, passwords, API keys).
2. All binding variable names must be SCREAMING_SNAKE_CASE (schema constraint).
3. Prefer stable variable names across environments; change only the provider mapping/location.
4. Binding namespaces must be globally unique across all adapters/bindings.
5. Keep descriptions meaningful:
    - binding `description` should explain what the secret/config is for (helps discovery and safe usage).
6. Avoid leaking secret values in any markdown fields (and keep them free of `<script` / `eval(`).

## Recommended workflow (recipe)

### Step 0 — Inventory secrets and environment differences

For each external API:

- which credentials are required?
- where should they come from in dev vs prod?
- are there different endpoints/tenants per environment?

### Step 1 — Define bindings

Create one binding per provider or per domain boundary (pragmatic):

- `namespace`: `github-secrets`, `notion-dev`, `vault-core`, etc.
- `keys`: declare all required variables as SCREAMING_SNAKE_CASE

### Step 2 — Wire bindings into consumes/exposes

- For consumes authentication: reference variables in auth fields or inject via `with`
- For exposed operations: never echo secrets back in `outputParameters`

### Step 3 — Validate

Run the Spectral lint script (and ensure you still satisfy all path/baseUri hygiene rules). Fix warnings early; they become painful when you multiply environments.

## Validation checklist (fast)

Before calling dev→prod done:

- No secrets appear literally in YAML.
- `binds` are present with:
    - unique `namespace`
    - `keys` with SCREAMING_SNAKE_CASE variable names
- All required secrets/config are actually referenced in consumes/exposes wiring.
- Descriptions are safe (no `<script`, no `eval(`) and helpful.
- baseUri/path rules still pass (no trailing slash, no query in path, etc.).

## References

- Ikanos JSON Schema: `ikanos-spec/src/main/resources/schemas/ikanos-schema.json`
- Polychro Rules: `ikanos-spec/src/main/resources/rules/ikanos-rules.yml`