# HTTP Cache Control for the REST Server Adapter
## Declarative Cache Semantics — From Origin to Client

**Status**: Proposed

**Version**: 1.0.0-alpha1

**Date**: April 4, 2026

**Roadmap items**: [Support HTTP cache control directives](../wiki/Roadmap.md), [Add support for resiliency patterns (… cache …)](../wiki/Roadmap.md)

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [HTTP Caching Primer](#http-caching-primer)
3. [Architecture Overview](#architecture-overview)
4. [Design Analogy](#design-analogy)
5. [Core Concepts](#core-concepts)
6. [Schema Changes — Exposes (REST)](#schema-changes--exposes-rest)
7. [Schema Changes — Aggregates](#schema-changes--aggregates)
8. [Schema Changes — Consumes](#schema-changes--consumes)
9. [Implementation Examples](#implementation-examples)
10. [Engine Behavior](#engine-behavior)
11. [Security Considerations](#security-considerations)
12. [Validation Rules](#validation-rules)
13. [Design Decisions & Rationale](#design-decisions--rationale)
14. [Implementation Roadmap](#implementation-roadmap)
15. [Backward Compatibility](#backward-compatibility)

---

## 1. Executive Summary

### What This Proposes

HTTP cache control support for the REST server adapter across three layers:

1. **`cache` on `ExposedOperation`** (exposes layer) — A declarative block on REST operations that lets the capability author set explicit cache directives (`max-age`, `no-store`, `private`, `must-revalidate`, etc.) on responses served by the Naftiko REST adapter. The engine emits `Cache-Control`, `ETag`, and `Vary` response headers automatically.

2. **`semantics.cacheable` activation** (aggregates layer) — When an aggregate function declares `semantics.cacheable: true` and is referenced via `ref` by a REST operation that has no explicit `cache` block, the engine applies a default cache policy (`Cache-Control: public, max-age=60`). This lets domain authors express cacheability once at the function level, with REST-specific tuning as an optional override — the same pattern used for MCP `hints` derivation from `semantics`.

3. **`propagate` mode on `cache`** (consumes → exposes bridge) — An optional `propagate: true` flag on the exposed operation's `cache` block that instructs the engine to forward cache-related response headers (`Cache-Control`, `ETag`, `Last-Modified`, `Expires`, `Vary`) from the consumed API's HTTP response to the REST adapter's response. This is the cache equivalent of `ForwardConfig.trustedHeaders` for request headers, but applied to response headers from the origin.

### What This Does NOT Do

- **No server-side response cache** — this proposal controls HTTP cache *headers* only. An in-memory or shared cache layer (Caffeine, Redis) is a separate resiliency concern tracked under the "cache" pattern in the Roadmap's resiliency section.
- **No conditional request support on consumes** — the engine does not (yet) send `If-None-Match` or `If-Modified-Since` to consumed APIs. That is a client-side optimization for a future iteration.
- **No CDN integration** — the emitted `Cache-Control` headers work naturally with any CDN or reverse proxy placed in front of the REST adapter, but Naftiko does not manage CDN configuration.
- **No MCP or Skill adapter impact** — MCP uses its own `hints` system and does not serve HTTP responses with cache headers. Skill adapters are discovery-only. This proposal is REST-specific.
- **No changes to CI/CD workflows** or branch protection rules.

### Business Value

| Benefit | Impact | Users |
|---------|--------|-------|
| **Reduced origin load** | Clients and intermediaries cache responses, fewer calls to consumed APIs | Operations, Cost |
| **Lower latency** | Cached responses served instantly by browsers, proxies, CDNs | End Users |
| **Declarative control** | Cache policy in YAML — no custom response filters or middleware | Capability authors |
| **Domain-level cacheability** | `semantics.cacheable` expresses caching intent at the aggregate level, adapters derive behavior | Domain Modelers |
| **Origin-faithful caching** | `propagate` mode preserves the consumed API's own cache policy end-to-end | API Proxies |
| **Standards compliance** | RFC 9111 (HTTP Caching) headers emitted correctly | Architects, InfoSec |

### Key Design Decisions

1. **Three sources of cache policy, one precedence chain**: Explicit `cache` block on the operation wins; then `semantics.cacheable` on the referenced aggregate function provides a default; then `propagate: true` delegates to the origin. Only one policy is active per response.

2. **`ETag` is automatic**: For non-propagated responses, the engine computes a weak ETag (`W/"<hash>"`) from the response body SHA-256 digest. This enables conditional requests by downstream clients and intermediary caches without any configuration.

3. **`Vary` is declarative**: The `vary` field lists request header names (e.g. `Authorization`, `Accept-Language`) that affect the response. The engine emits `Vary` headers to prevent cache poisoning when responses differ by user or locale.

4. **Forward mode inherits propagation for free**: When a resource uses `forward` (passthrough proxy), response headers are already forwarded by the Restlet framework. The `cache` block only applies to operations with `call`/`steps`/`ref`.

5. **No opinion on cache duration by default**: Unless `semantics.cacheable: true` or an explicit `cache` block is present, the engine emits no cache headers — leaving caching behavior to the client's defaults. This is the safe default.

### Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| **Stale data served** | Medium | Medium | `must-revalidate` and short `max-age`; document best practices |
| **Cache poisoning via missing Vary** | Low | High | Validation rule: warn when `Authorization` is an input without `Vary` |
| **Private data cached publicly** | Low | High | Default to `private` when authentication is configured on the adapter |
| **ETag collision** | Very Low | Low | SHA-256 weak ETag; collisions are astronomically unlikely |
| **Schema complexity** | Low | Low | Additive — optional `cache` block, optional `propagate` flag |

**Overall Risk**: **LOW** — cache headers are informational metadata on responses; incorrect values degrade performance, not correctness. The security-sensitive case (private data cached publicly) is mitigated by automatic `private` inference when authentication is present.

---

## 2. HTTP Caching Primer

HTTP caching is governed by [RFC 9111](https://www.rfc-editor.org/rfc/rfc9111) (HTTP Caching) and related specifications. The key headers:

| Header | Direction | Purpose | RFC |
|--------|-----------|---------|-----|
| `Cache-Control` | Response | Directives for caches (freshness, revalidation, storage) | 9111 §5.2 |
| `ETag` | Response | Opaque validator for conditional requests | 9110 §8.8.3 |
| `Last-Modified` | Response | Timestamp validator for conditional requests | 9110 §8.8.2 |
| `Vary` | Response | Request headers that affect response selection | 9110 §12.5.5 |
| `Expires` | Response | Legacy freshness (superseded by `Cache-Control`) | 9111 §5.3 |
| `Age` | Response | Time since response was generated by origin | 9111 §5.1 |
| `If-None-Match` | Request | Conditional: send response only if ETag differs | 9110 §13.1.2 |
| `If-Modified-Since` | Request | Conditional: send response only if modified after date | 9110 §13.1.3 |

### Cache-Control Directives (Response)

| Directive | Meaning |
|-----------|---------|
| `public` | Any cache may store the response |
| `private` | Only the end-user's browser may cache — no shared caches |
| `no-cache` | Cache may store but must revalidate before each use |
| `no-store` | No cache may store the response at all |
| `max-age=N` | Response is fresh for N seconds |
| `s-maxage=N` | Overrides `max-age` for shared caches (CDNs, proxies) |
| `must-revalidate` | Once stale, cache must revalidate — no serving stale content |
| `immutable` | Response will not change during its freshness lifetime |
| `stale-while-revalidate=N` | Serve stale for N seconds while revalidating in background |

### How Caching Works End-to-End

```
Client ──► [Browser Cache] ──► [CDN / Reverse Proxy] ──► Naftiko REST Adapter ──► Consumed API
                │                        │                        │
                │  Cache-Control,        │  Cache-Control,        │  Cache-Control,
                │  ETag, Vary            │  ETag, Vary, Age       │  ETag, Last-Modified
                │                        │                        │
                ◄── 200 OK ─────────────◄── 200 OK ─────────────◄── 200 OK
                    (from cache)             (from cache)             (from origin)
```

The Naftiko REST adapter sits in the middle. It can:
- **Emit** cache headers on its own responses (explicit `cache` or derived from `semantics.cacheable`)
- **Propagate** cache headers from the consumed API response (when acting as a proxy)
- **Generate** ETags from response body content (automatic)

---

## 3. Architecture Overview

### Current State

```
Client ──► Naftiko REST Adapter ──► Consumed API
                │                        │
                │  (no cache headers)    │  Cache-Control: max-age=300
                │                        │  ETag: "abc123"
                ◄── 200 OK ─────────────◄── 200 OK
                    body only                body + headers (headers discarded)
```

Response headers from the consumed API are not extracted or forwarded. The `HandlingContext` stores the raw `Response` object — headers exist on it but are never projected to the REST adapter's response.

### Proposed State

```
Client ──► Naftiko REST Adapter ──► Consumed API
                │                        │
                │  Cache-Control: ...    │  Cache-Control: max-age=300
                │  ETag: W/"x"          │  ETag: "abc123"
                │  Vary: Authorization   │
                ◄── 200 OK ─────────────◄── 200 OK

Source of cache headers:
  (a) Explicit:   cache block on ExposedOperation
  (b) Derived:    semantics.cacheable on referenced AggregateFunction
  (c) Propagated: cache.propagate: true → copy origin headers
```

### Precedence Chain

```
ExposedOperation.cache (explicit)
        │
        ▼ (if absent and ref is present)
AggregateFunction.semantics.cacheable → default policy
        │
        ▼ (if absent)
No cache headers emitted (current behavior)

Separately:
ExposedOperation.cache.propagate: true → origin headers forwarded
```

When an explicit `cache` block is present, its directives always win. When `propagate: true` is also set on the same block, origin headers are forwarded for the fields *not* explicitly overridden (merge behavior).

---

## 4. Design Analogy

The cache control system follows the same layered derivation pattern already established for MCP hints:

| Layer | MCP Adapter (existing) | REST Adapter (proposed) |
|-------|----------------------|------------------------|
| **Domain** | `semantics: { safe: true }` | `semantics: { cacheable: true }` |
| **Derivation** | Engine derives `hints: { readOnly: true }` | Engine derives `Cache-Control: public, max-age=60` |
| **Override** | Explicit `hints` on tool overrides derived values | Explicit `cache` on operation overrides derived defaults |
| **Adapter-specific** | `openWorld` (MCP-only, no semantic source) | `propagate` (REST-only, no semantic source) |

This symmetry keeps the mental model consistent: `semantics` describe domain intent, adapters derive behavior, explicit config overrides.

---

## 5. Core Concepts

### 5.1 Cache Policy Sources

| Source | Declared on | Effect |
|--------|------------|--------|
| **Explicit** | `ExposedOperation.cache` | Full control over `Cache-Control`, `Vary` directives |
| **Derived** | `AggregateFunction.semantics.cacheable` | Default `Cache-Control: public, max-age=60` (overridable) |
| **Propagated** | `ExposedOperation.cache.propagate` | Origin's cache headers forwarded to client |
| **None** | (default) | No cache headers emitted |

### 5.2 ETag Generation

For non-propagated responses, the engine generates a weak ETag:

1. Compute SHA-256 of the serialized response body (after output parameter mapping)
2. Truncate to 16 hex characters
3. Emit `ETag: W/"<hash>"`

Weak ETags are appropriate because the entity may be semantically equivalent but not byte-for-byte identical across serializations (JSON key ordering).

### 5.3 Conditional Request Handling

When the engine receives a request with `If-None-Match`:

1. Execute the operation (call/steps) as usual
2. Compute the response ETag
3. If ETag matches any value in `If-None-Match`, return `304 Not Modified` with no body
4. Otherwise, return `200 OK` with the full response and ETag

This is a **revalidation-only** optimization — the engine still calls the consumed API. True origin-side caching (skip the call entirely) requires the server-side cache from the resiliency patterns roadmap item.

### 5.4 Authentication-Aware Defaults

When the REST adapter has `authentication` configured and the operation's cache policy includes `public`, the engine emits a validation warning (not an error) suggesting `private` instead. This prevents accidental caching of user-specific responses in shared caches.

---

## 6. Schema Changes — Exposes (REST)

### New `CacheControl` definition

```json
"CacheControl": {
  "type": "object",
  "description": "HTTP cache control directives for REST responses. Applied as Cache-Control and related headers on the response.",
  "properties": {
    "maxAge": {
      "type": "integer",
      "minimum": 0,
      "description": "Freshness lifetime in seconds. Emitted as max-age directive."
    },
    "sMaxAge": {
      "type": "integer",
      "minimum": 0,
      "description": "Shared cache freshness lifetime in seconds. Overrides maxAge for CDNs and reverse proxies. Emitted as s-maxage directive."
    },
    "scope": {
      "type": "string",
      "enum": ["public", "private"],
      "description": "Cache scope. 'public' allows shared caches; 'private' restricts to end-user browser only. Default: inferred from authentication presence."
    },
    "noStore": {
      "type": "boolean",
      "description": "If true, no cache may store the response. Overrides all other directives. Default: false."
    },
    "noCache": {
      "type": "boolean",
      "description": "If true, caches must revalidate before every use. Default: false."
    },
    "mustRevalidate": {
      "type": "boolean",
      "description": "If true, caches must not serve stale content. Default: false."
    },
    "immutable": {
      "type": "boolean",
      "description": "If true, the response will not change during its freshness lifetime. Browsers skip revalidation entirely. Default: false."
    },
    "staleWhileRevalidate": {
      "type": "integer",
      "minimum": 0,
      "description": "Seconds during which stale content may be served while revalidating in the background."
    },
    "vary": {
      "type": "array",
      "items": { "type": "string" },
      "description": "Request header names that affect the response. Emitted as the Vary header. Example: ['Authorization', 'Accept-Language']."
    },
    "etag": {
      "type": "boolean",
      "description": "If true, the engine generates a weak ETag from the response body. Default: true when any cache directive is set."
    },
    "propagate": {
      "type": "boolean",
      "description": "If true, forward cache-related response headers (Cache-Control, ETag, Last-Modified, Expires, Vary) from the consumed API response. Explicit directives in this block override propagated values. Default: false."
    }
  },
  "additionalProperties": false
}
```

### Updated `ExposedOperation`

Add `cache` as an optional property:

```json
"ExposedOperation": {
  "properties": {
    "cache": {
      "$ref": "#/$defs/CacheControl",
      "description": "HTTP cache control directives for this operation's responses."
    }
  }
}
```

### Full YAML Surface

```yaml
operations:
  - method: "GET"
    name: "get-forecast"
    ref: "weather.get-forecast"
    cache:
      maxAge: 300
      scope: "public"
      mustRevalidate: true
      vary:
        - "Accept-Language"
      etag: true
```

---

## 7. Schema Changes — Aggregates

No new fields are needed. The existing `Semantics.cacheable` boolean is already defined:

```json
"Semantics": {
  "properties": {
    "cacheable": {
      "type": "boolean",
      "description": "If true, the result can be cached. Default: false."
    }
  }
}
```

### Derivation Rule

When a REST `ExposedOperation` uses `ref` pointing to an `AggregateFunction` with `semantics.cacheable: true` and the operation has no explicit `cache` block, the engine generates a default cache policy:

```
Cache-Control: public, max-age=60
ETag: W/"<sha256-of-body>"
```

If authentication is present on the REST adapter, `public` becomes `private`:

```
Cache-Control: private, max-age=60
ETag: W/"<sha256-of-body>"
```

This mirrors how `semantics.safe: true` → `hints.readOnly: true` works for MCP.

---

## 8. Schema Changes — Consumes

### Response Header Extraction

Today, the `HandlingContext` stores the full `Response` object from the consumed API. Cache-related response headers (`Cache-Control`, `ETag`, `Last-Modified`, `Expires`, `Vary`) already exist on this object but are never read.

No schema changes are needed on the consumes side. The engine reads cache headers from `HandlingContext.clientResponse.getHeaders()` at response time when `cache.propagate: true` is set on the exposed operation.

### Propagated Headers

The following headers are forwarded when `propagate: true`:

| Header | Propagation behavior |
|--------|---------------------|
| `Cache-Control` | Forwarded unless explicit directives override |
| `ETag` | Forwarded as-is (origin ETag, not recomputed) |
| `Last-Modified` | Forwarded as-is |
| `Expires` | Forwarded as-is (legacy; `max-age` in `Cache-Control` takes precedence) |
| `Vary` | Merged with explicit `vary` list if both are present |
| `Age` | **Not forwarded** — the Naftiko adapter is not a shared cache; `Age` would be misleading |

---

## 9. Implementation Examples

### 9.1 Explicit Cache Control — Weather API

A capability that consumes a weather API and exposes forecast data with a 5-minute cache.

```yaml
naftiko: "1.0.0-alpha1"

info:
  label: "Weather Forecast Service"
  description: "Exposes weather forecasts with HTTP caching"

capability:
  exposes:
    - type: "rest"
      address: "localhost"
      port: 8080
      namespace: "weather"
      resources:
        - path: "/forecasts/{city}"
          name: "forecasts"
          operations:
            - method: "GET"
              name: "get-forecast"
              call: "weather-api.get-forecast"
              with:
                city: "{{weather.city}}"
              inputParameters:
                - name: "city"
                  in: "path"
                  type: "string"
                  description: "City name"
              outputParameters:
                - name: "temperature"
                  type: "number"
                  mapping: "$.main.temp"
                - name: "description"
                  type: "string"
                  mapping: "$.weather[0].description"
              cache:
                maxAge: 300
                scope: "public"
                mustRevalidate: true
                vary:
                  - "Accept-Language"

  consumes:
    - namespace: "weather-api"
      type: "http"
      baseUri: "https://api.openweathermap.org"
      resources:
        - path: "/data/2.5/weather"
          name: "weather"
          operations:
            - method: "GET"
              name: "get-forecast"
              inputParameters:
                - name: "city"
                  in: "query"
                  field: "q"
                  type: "string"
```

**Response headers emitted:**

```http
HTTP/1.1 200 OK
Cache-Control: public, max-age=300, must-revalidate
ETag: W/"a1b2c3d4e5f6g7h8"
Vary: Accept-Language
Content-Type: application/json
```

### 9.2 Derived Cache from Aggregates

A capability using `aggregates` + `ref` where cacheability is expressed at the domain level.

```yaml
naftiko: "1.0.0-alpha1"

info:
  label: "Product Catalog"
  description: "Product listings via REST and MCP with domain-level caching"

capability:
  aggregates:
    - label: "Catalog"
      namespace: "catalog"
      functions:
        - name: "list-products"
          description: "Lists all products in the catalog"
          semantics:
            safe: true
            cacheable: true
          call: "store-api.list-products"
          outputParameters:
            - name: "products"
              type: "array"
              mapping: "$.data"

  exposes:
    - type: "rest"
      address: "localhost"
      port: 8080
      namespace: "catalog-rest"
      resources:
        - path: "/products"
          name: "products"
          operations:
            - method: "GET"
              name: "list-products"
              ref: "catalog.list-products"
              # no explicit cache block → derived from semantics.cacheable

    - type: "mcp"
      transport: "http"
      port: 3000
      namespace: "catalog-mcp"
      tools:
        - name: "list-products"
          ref: "catalog.list-products"
          # MCP derives hints: readOnly from safe, ignores cacheable

  consumes:
    - namespace: "store-api"
      type: "http"
      baseUri: "https://api.example-store.com"
      resources:
        - path: "/v1/products"
          name: "products"
          operations:
            - method: "GET"
              name: "list-products"
```

**REST response headers (derived):**

```http
Cache-Control: public, max-age=60
ETag: W/"f8e7d6c5b4a39281"
```

**MCP response**: No cache headers (MCP protocol has no cache header equivalent — `cacheable` is informational only for MCP).

### 9.3 Propagated Cache — API Proxy

A capability proxying an API that already has well-defined cache headers.

```yaml
naftiko: "1.0.0-alpha1"

info:
  label: "GitHub API Proxy"
  description: "Proxies GitHub REST API with cache propagation"

capability:
  exposes:
    - type: "rest"
      address: "localhost"
      port: 8080
      namespace: "github"
      authentication:
        type: "bearer"
        token: "{{PROXY_TOKEN}}"
      resources:
        - path: "/repos/{owner}/{repo}"
          name: "repos"
          operations:
            - method: "GET"
              name: "get-repo"
              call: "github-api.get-repo"
              with:
                owner: "{{github.owner}}"
                repo: "{{github.repo}}"
              inputParameters:
                - name: "owner"
                  in: "path"
                  type: "string"
                - name: "repo"
                  in: "path"
                  type: "string"
              cache:
                propagate: true

  consumes:
    - namespace: "github-api"
      type: "http"
      baseUri: "https://api.github.com"
      authentication:
        type: "bearer"
        token: "{{GITHUB_TOKEN}}"
      resources:
        - path: "/repos/{owner}/{repo}"
          name: "repos"
          operations:
            - method: "GET"
              name: "get-repo"
              inputParameters:
                - name: "owner"
                  in: "path"
                  type: "string"
                - name: "repo"
                  in: "path"
                  type: "string"

binds:
  - namespace: "secrets"
    keys:
      GITHUB_TOKEN: ""
      PROXY_TOKEN: ""
```

**If GitHub responds with:**

```http
Cache-Control: private, max-age=60, s-maxage=60
ETag: "abc123def456"
Vary: Accept, Authorization
```

**Naftiko REST adapter forwards:**

```http
Cache-Control: private, max-age=60, s-maxage=60
ETag: "abc123def456"
Vary: Accept, Authorization
```

### 9.4 Propagated with Explicit Overrides

Override specific directives while propagating the rest from the origin.

```yaml
operations:
  - method: "GET"
    name: "get-repo"
    call: "github-api.get-repo"
    cache:
      propagate: true
      maxAge: 120          # override origin's max-age
      vary:                # merged with origin's Vary
        - "X-Custom-Header"
```

**If origin sends `Cache-Control: public, max-age=60` and `Vary: Accept`:**

```http
Cache-Control: public, max-age=120
Vary: Accept, X-Custom-Header
ETag: "abc123def456"
```

`max-age` is overridden to 120. `Vary` is merged. `scope` remains `public` from origin since not explicitly overridden.

### 9.5 No-Store for Sensitive Data

```yaml
operations:
  - method: "POST"
    name: "create-payment"
    call: "payment-api.charge"
    cache:
      noStore: true
```

```http
Cache-Control: no-store
```

No ETag, no Vary — nothing should be cached.

---

## 10. Engine Behavior

### 10.1 Cache Header Emission in `ResourceRestlet.sendResponse`

After building the response body (output parameter mapping), the engine applies cache headers:

```
1. Check ExposedOperation.cache
   ├── present → build Cache-Control from explicit directives
   │   ├── if propagate: true → read origin headers, merge with explicit overrides
   │   └── if etag != false → compute ETag from response body
   └── absent → check ref → AggregateFunction.semantics.cacheable
       ├── true → emit default policy (public/private, max-age=60, ETag)
       └── false/absent → emit no cache headers (current behavior)

2. Check request If-None-Match against computed/propagated ETag
   ├── match → return 304 Not Modified (no body)
   └── no match → return 200 OK with body and cache headers
```

### 10.2 Cache-Control Header Construction

The engine builds the `Cache-Control` value by assembling directives in standard order:

```
[scope] [, max-age=N] [, s-maxage=N] [, no-cache] [, no-store]
[, must-revalidate] [, immutable] [, stale-while-revalidate=N]
```

Example: `scope: "public"`, `maxAge: 300`, `mustRevalidate: true` → `public, max-age=300, must-revalidate`

### 10.3 Propagation Flow

When `cache.propagate: true`:

```
1. Execute operation (call/steps) → obtain HandlingContext
2. Read response headers from HandlingContext.clientResponse.getHeaders():
   - Cache-Control → parse into directive map
   - ETag → store
   - Last-Modified → store
   - Expires → store
   - Vary → parse into header name set
3. Apply explicit overrides from cache block:
   - maxAge set? → replace max-age in directive map
   - scope set? → replace public/private in directive map
   - vary set? → merge into header name set
   - noStore set? → override everything
4. Serialize directive map → Cache-Control header value
5. Emit all headers on the REST adapter response
```

### 10.4 Orchestrated Steps (Multi-Step)

When an operation uses `steps` (multiple consumed API calls), propagation is applied from the **last step's response** only. Intermediate step responses do not contribute cache headers — the final response is what the client receives.

If the last step's consumed API returns no cache headers and `propagate: true` is set, no cache headers are emitted (propagation produces nothing to forward).

### 10.5 Forward Mode

Resources using `forward` already pass through response headers from the consumed API via the Restlet framework. The `cache` block does **not** apply to forwarded resources — they are passthrough by design. This is consistent with `ForwardConfig` being a separate mode from `operations`.

---

## 11. Security Considerations

### 11.1 Private vs Public Inference

When the REST adapter has `authentication` configured and no explicit `scope` is set:

- **Propagated**: Origin's scope is preserved (the origin knows best whether responses are user-specific)
- **Derived** (from `semantics.cacheable`): Default to `private` (conservative — authenticated endpoints likely serve user-specific data)
- **Explicit**: Author's choice — a validation warning is emitted if `scope: "public"` is used on an authenticated adapter

### 11.2 No-Store for Write Operations

The engine emits `Cache-Control: no-store` automatically for `POST`, `PUT`, `PATCH`, and `DELETE` operations, regardless of the `cache` block. Only `GET` operations apply cache directives. This prevents accidental caching of mutation responses.

A validation warning is emitted if a `cache` block (with directives other than `noStore`) is placed on a non-GET operation.

### 11.3 Vary and Authorization

When `Authorization` is an input parameter (via `in: "header"`) and the `cache` block does not include `Authorization` in `vary`, a validation warning is emitted. Missing `Vary: Authorization` can cause shared caches to serve one user's response to another.

### 11.4 ETag Timing Safety

ETag computation uses SHA-256, which is not timing-sensitive for this use case. The ETag is derived from the response body — it does not leak information about request parameters or internal state.

---

## 12. Validation Rules

### Spectral Rules

| Rule | Severity | Condition |
|------|----------|-----------|
| `naftiko-cache-only-on-get` | warn | `cache` block (with directives other than `noStore`) on a non-GET operation |
| `naftiko-cache-public-with-auth` | warn | `scope: "public"` on an operation under an authenticated REST adapter |
| `naftiko-cache-vary-authorization` | warn | Input parameter `in: "header"` named `Authorization` without `Authorization` in `cache.vary` |
| `naftiko-cache-propagate-with-forward` | error | `cache.propagate` on a resource that uses `forward` (conflicting modes) |
| `naftiko-cache-no-store-conflicts` | warn | `noStore: true` combined with `maxAge`, `sMaxAge`, or `scope` (contradictory) |
| `naftiko-cache-max-age-range` | warn | `maxAge` or `sMaxAge` > 31536000 (one year — RFC 9111 recommends no more) |

---

## 13. Design Decisions & Rationale

### D1: Why not a resiliency pattern?

**Decision**: Cache *headers* are separate from cache *storage*.

**Rationale**: The roadmap lists "cache" as a resiliency pattern alongside retry, circuit breaker, etc. That refers to a **server-side response cache** (e.g., Caffeine) that avoids calling the consumed API entirely for repeated requests. This proposal addresses the complementary concern: telling *downstream* caches (browsers, CDNs, proxies) what to cache. Both are needed — they are orthogonal.

### D2: Why not just use ForwardConfig for cache headers?

**Decision**: `ForwardConfig` is for *request* header whitelisting on forward-mode resources. Cache headers are *response* headers on operation-mode resources.

**Rationale**: `ForwardConfig.trustedHeaders` controls which incoming request headers are forwarded to the consumed API. Cache control needs to move in the opposite direction — from the consumed API's response to the REST adapter's response. Different direction, different mode, different config.

### D3: Why default max-age=60 for semantics.cacheable?

**Decision**: One minute is short enough to be safe, long enough to be useful.

**Rationale**: The `semantics.cacheable` flag expresses "this result can be cached" without specifying duration. A zero default would make the flag meaningless. A long default (e.g., 1 hour) risks serving stale data for functions where the author simply forgot to tune. 60 seconds is a compromise: it reduces load on bursty traffic patterns while staying fresh enough for most use cases. Authors who need longer durations add an explicit `cache` block.

### D4: Why weak ETags?

**Decision**: All engine-generated ETags are weak (`W/"..."`) .

**Rationale**: Strong ETags require byte-for-byte identical representations. JSON serialization may vary (key ordering, whitespace) across engine restarts or versions. Weak ETags assert semantic equivalence, which is sufficient for `304 Not Modified` responses. Origin ETags propagated via `cache.propagate` retain their original strength.

### D5: Why not propagate Age?

**Decision**: The `Age` header is not forwarded in propagation mode.

**Rationale**: `Age` indicates how long a response has been in a shared cache (RFC 9111 §5.1). The Naftiko engine is not a shared cache — it calls the origin on every request. Forwarding `Age: 0` is harmless but misleading; forwarding a non-zero Age would require the engine to track time since the origin generated the response, which is cache-storage territory.

### D6: Why compute ETag even without explicit cache?

**Decision**: When any cache directive is active (explicit or derived), ETag is emitted by default unless `etag: false`.

**Rationale**: ETags enable conditional requests (`If-None-Match` → `304`), which save bandwidth even when max-age is short. The cost is a SHA-256 hash of the response body — negligible for typical API responses. Authors can disable it with `etag: false` for large responses where hashing is expensive.

### D7: Why enforce no-store on write operations?

**Decision**: POST/PUT/PATCH/DELETE always get `no-store` regardless of the `cache` block.

**Rationale**: RFC 9111 §3 states that caches must invalidate stored responses when a successful unsafe request is received to the same URI. While allowing cache directives on unsafe methods is technically legal, it is almost always a mistake in API contexts. Enforcing `no-store` prevents accidental caching of side-effectful responses.

---

## 14. Implementation Roadmap

### Phase 1 — Explicit Cache Block (MVP)

**Scope**: `CacheControl` schema definition, `cache` property on `ExposedOperation`, header emission in `ResourceRestlet.sendResponse`, ETag generation, `304 Not Modified` handling.

**Deliverables**:
- Schema: `CacheControl` definition, `ExposedOperation.cache` property
- Engine: `CacheHeaderEmitter` utility class
- Engine: ETag computation in `ResourceRestlet`
- Engine: Conditional request handling (`If-None-Match`)
- Tests: Unit tests for header construction, ETag computation, 304 responses
- Tests: Integration test with a REST capability using explicit `cache`
- Spectral: `naftiko-cache-only-on-get`, `naftiko-cache-no-store-conflicts`, `naftiko-cache-max-age-range`

### Phase 2 — Semantic Derivation

**Scope**: Derivation of default cache policy from `AggregateFunction.semantics.cacheable` when `ref` is used and no explicit `cache` block is present.

**Deliverables**:
- Engine: Derivation logic in `AggregateRefResolver` (parallel to `deriveHints`)
- Engine: Authentication-aware scope inference (`private` when auth present)
- Tests: Integration test with aggregate `cacheable: true` → REST response headers
- Spectral: `naftiko-cache-public-with-auth`

### Phase 3 — Propagation

**Scope**: `cache.propagate` flag, response header extraction from `HandlingContext`, merge logic for explicit overrides + propagated headers.

**Deliverables**:
- Engine: Response header extraction from `HandlingContext.clientResponse.getHeaders()`
- Engine: Propagation + merge logic in `ResourceRestlet`
- Tests: Integration test with consumed API returning cache headers, propagated to REST response
- Tests: Merge test — explicit overrides + propagated values
- Spectral: `naftiko-cache-propagate-with-forward`, `naftiko-cache-vary-authorization`

### Phase 4 — Documentation & Examples

**Deliverables**:
- Specification: Update `Specification.md` with cache control section
- Examples: Add `cache-control.yml` to `src/main/resources/schemas/examples/`
- Tutorial: Optional step in the Shipyard Track demonstrating cache headers

---

## 15. Backward Compatibility

This proposal is **fully additive**:

- The `cache` property on `ExposedOperation` is optional. Existing capabilities without it behave exactly as they do today — no cache headers emitted.
- The `semantics.cacheable` boolean already exists in the schema. Its new effect (REST header derivation) only activates when a REST operation uses `ref` — existing capabilities without `ref` are unaffected.
- No existing schema properties are modified or removed.
- No existing engine behavior is changed when `cache` is absent.
- Forward-mode resources continue to pass through headers as before.

**Migration path**: None required. Authors opt in by adding `cache` blocks to operations or `cacheable: true` to aggregate functions.
