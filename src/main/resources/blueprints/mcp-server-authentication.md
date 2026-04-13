# MCP Server Authentication
## OAuth 2.1 Authorization for the MCP Server Adapter

**Status**: Proposed

**Version**: 1.0.0-alpha1

**Date**: April 4, 2026

**MCP Protocol Version**: [2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization) (current)

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [MCP Authorization Primer](#mcp-authorization-primer)
3. [Architecture Overview](#architecture-overview)
4. [Design Analogy](#design-analogy)
5. [Core Concepts](#core-concepts)
6. [Schema Changes — Exposes (MCP)](#schema-changes--exposes-mcp)
7. [Schema Changes — Aggregates](#schema-changes--aggregates)
8. [Implementation Examples](#implementation-examples)
9. [Engine Behavior](#engine-behavior)
10. [Security Considerations](#security-considerations)
11. [Validation Rules](#validation-rules)
12. [Design Decisions & Rationale](#design-decisions--rationale)
13. [Implementation Roadmap](#implementation-roadmap)
14. [Backward Compatibility](#backward-compatibility)

---

## 1. Executive Summary

### What This Proposes

Authentication support for the MCP server adapter, adding two complementary authentication modes:

1. **`authentication` with existing types** (bearer, apikey, basic, digest) — Reuse the same `Authentication` union already defined for `ExposesRest` and `ExposesSkill`. The engine validates incoming `Authorization` headers on every HTTP request to the MCP endpoint before dispatching to `McpServerResource`. This covers the common case of a shared secret, API key, or static bearer token protecting the MCP server — identical to how the REST adapter works today.

2. **`authentication` with new `oauth2` type** — A new `AuthOAuth2` schema definition added to the **shared** `Authentication` union, making OAuth 2.1 available to all three adapter types (REST, MCP, Skill). The server acts as an OAuth 2.1 resource server: it validates bearer tokens issued by an external authorization server. A shared `OAuth2AuthenticationRestlet` handles core JWT/JWKS validation, audience, expiry, and scope checks — identical across adapters. The MCP adapter layers protocol-specific behavior on top: auto-serving Protected Resource Metadata (RFC 9728) and returning `resource_metadata` in `WWW-Authenticate` challenges per the [MCP 2025-11-25 Authorization specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization).

### What This Does NOT Do

- **No built-in authorization server** — Naftiko does not become an OAuth authorization server. It delegates token issuance to an external AS (Keycloak, Auth0, Entra ID, etc.). The capability author configures the AS metadata URL; the engine validates tokens against it.
- **No MCP client-side OAuth flow** — This proposal covers the *server* side only. MCP clients are responsible for obtaining tokens through the OAuth 2.1 authorization flow described in the spec.
- **No scope-based access control per tool** — Token validation is all-or-nothing at the adapter level. Fine-grained per-tool scoping (e.g., "this token can call `list-users` but not `delete-user`") is deferred to a future authorization policy layer.
- **No stdio transport authentication** — Per the MCP spec, stdio transport SHOULD NOT follow the HTTP authorization flow and instead retrieves credentials from the environment. No change needed.
- **No dynamic client registration server** — Dynamic client registration (RFC 7591) is an authorization server concern, not a resource server concern.
- **No changes to CI/CD workflows** or branch protection rules.

### Business Value

| Benefit | Impact | Users |
|---------|--------|-------|
| **MCP spec compliance** | Full alignment with MCP 2025-11-25 authorization | Developers, Architects |
| **Secure remote deployment** | MCP servers can be deployed on public endpoints with OAuth protection | Operations, InfoSec |
| **Parity with REST adapter** | Same auth UX for all adapter types — configure once in YAML | Capability authors |
| **Enterprise readiness** | Integration with corporate IdPs (Keycloak, Entra ID, Okta) via standard OAuth 2.1 | Enterprise teams |
| **Zero-code security** | Declarative authentication — no custom filters or middleware | Developers |

### Key Design Decisions

1. **Shared `Authentication` union with `oauth2`**: `AuthOAuth2` is added to the shared `Authentication` union used by all three adapters. Core JWT/JWKS validation lives in a shared `OAuth2AuthenticationRestlet` in `io.naftiko.engine.exposes`, reusable by REST, MCP, and Skill adapters alike.

2. **Restlet filter chain**: Authentication is implemented as a Restlet `Restlet` wrapper that intercepts requests before the `Router` → `ServerResource` chain, following the same filter-before-router pattern used by all adapters.

3. **MCP-specific: Protected Resource Metadata is auto-served**: When `oauth2` authentication is configured on an MCP adapter, the engine auto-serves the `/.well-known/oauth-protected-resource` endpoint and includes `resource_metadata` in `WWW-Authenticate` challenges per MCP 2025-11-25. REST and Skill adapters use the shared `OAuth2AuthenticationRestlet` directly without this MCP protocol overlay.

4. **`WWW-Authenticate` challenges follow the spec**: On `401 Unauthorized`, the server includes the `resource_metadata` URL and optional `scope` in the `WWW-Authenticate: Bearer` header, as required by RFC 9728 and MCP 2025-11-25.

5. **Token validation is pluggable**: The engine supports two JWT validation strategies — JWKS endpoint (standard) and opaque token introspection (RFC 7662) — configurable via the `tokenValidation` property.

6. **Timing-safe comparison preserved**: Static credential validation continues to use `MessageDigest.isEqual()` for constant-time comparison, consistent with `ServerAuthenticationRestlet`.

### Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| **OAuth complexity** | Medium | Medium | Start with JWT/JWKS validation only; introspection is optional |
| **Token validation latency** | Low | Medium | JWKS key caching with configurable TTL |
| **Schema breaking change** | None | None | Purely additive — new optional `authentication` field on `ExposesMcp` |
| **MCP spec drift** | Low | Medium | Pin to `2025-11-25`; monitor spec revisions |

**Overall Risk**: **LOW** — Purely additive; existing MCP behavior unchanged for capabilities without `authentication`

---

## 2. MCP Authorization Primer

### How MCP Authentication Differs from REST Authentication

The REST adapter uses traditional HTTP authentication: the client sends credentials (API key, bearer token, username/password) directly to the server. The server validates the credentials against a locally-known secret (from environment variables via `binds`).

The MCP 2025-11-25 specification defines a more sophisticated OAuth 2.1-based flow for HTTP transport:

```
┌─────────┐        ┌──────────────────┐        ┌─────────────────────┐
│MCP Client│       │  MCP Server      │        │ Authorization Server│
│(AI Agent)│       │  (Resource Server)│        │ (Keycloak, Auth0…)  │
└────┬─────┘       └────────┬─────────┘        └──────────┬──────────┘
     │  POST /mcp (no token) │                             │
     │──────────────────────>│                             │
     │  401 + WWW-Authenticate│                            │
     │<──────────────────────│                             │
     │                        │                            │
     │  GET /.well-known/oauth-protected-resource          │
     │──────────────────────>│                             │
     │  Protected Resource   │                             │
     │  Metadata (RFC 9728)  │                             │
     │<──────────────────────│                             │
     │                        │                            │
     │  GET /.well-known/oauth-authorization-server        │
     │────────────────────────────────────────────────────>│
     │  AS Metadata (RFC 8414)│                            │
     │<────────────────────────────────────────────────────│
     │                        │                            │
     │  OAuth 2.1 Authorization Flow (PKCE, resource param)│
     │<──────────────────────────────────────────────────->│
     │  Access Token          │                            │
     │<────────────────────────────────────────────────────│
     │                        │                            │
     │  POST /mcp + Authorization: Bearer <token>          │
     │──────────────────────>│                             │
     │  Validate token (JWKS)│                             │
     │  MCP Response          │                            │
     │<──────────────────────│                             │
```

### Key Protocol Requirements (MCP 2025-11-25)

| Requirement | Spec Source | Naftiko Responsibility |
|-------------|-------------|------------------------|
| MCP servers MUST implement Protected Resource Metadata (RFC 9728) | Authorization §4.1 | Auto-serve `/.well-known/oauth-protected-resource` |
| Return `401` with `WWW-Authenticate: Bearer resource_metadata=...` | Authorization §4.2 | Generate response header from config |
| Validate access tokens per OAuth 2.1 §5.2 | Authorization §7.2 | JWT validation via JWKS or introspection |
| Tokens MUST be in `Authorization: Bearer` header, NOT query string | Authorization §7.1 | Reject query-string tokens |
| `403` with `scope` for insufficient scope errors | Authorization §8.1 | Return scope challenge when scopes don't match |
| Authorization is OPTIONAL for MCP implementations | Authorization §1.2 | Default: no auth (backward compatible) |
| stdio transport SHOULD NOT follow HTTP authorization | Authorization §1.2 | Skip auth for stdio |

### What Naftiko Does NOT Need to Implement

The following are **client-side** or **authorization-server-side** concerns:

- OAuth 2.1 authorization code flow with PKCE (client responsibility)
- Dynamic client registration / RFC 7591 (AS responsibility)
- Client ID Metadata Documents (client responsibility)
- Authorization server metadata endpoint / RFC 8414 (AS responsibility)
- Token issuance and refresh (AS responsibility)

---

## 3. Architecture Overview

### Current State

```
ExposesMcp
├── type: "mcp"
├── transport: "http" | "stdio"
├── namespace
├── description
├── tools[]
├── resources[]
└── prompts[]

Restlet HTTP Chain:
  Server → Router → McpServerResource → ProtocolDispatcher
  (no authentication)
```

### Proposed State

```
Authentication union (shared — all adapters)
├── AuthBasic
├── AuthApiKey
├── AuthBearer
├── AuthDigest
└── AuthOAuth2              ← NEW

ExposesMcp
├── type: "mcp"
├── transport: "http" | "stdio"
├── namespace
├── description
├── authentication          ← NEW (optional, refs shared Authentication)
├── tools[]
├── resources[]
└── prompts[]

Shared engine classes:
  OAuth2AuthenticationRestlet   (JWT/JWKS validation, audience, scope — reused by all adapters)
  ServerAuthenticationRestlet   (bearer/apikey — already shared)

Restlet HTTP Chain — MCP (with static auth):
  Server → ServerAuthenticationRestlet → Router → McpServerResource

Restlet HTTP Chain — MCP (with OAuth2 auth):
  Server → McpOAuth2Restlet → Router → McpServerResource
  (extends OAuth2AuthenticationRestlet + adds Protected Resource Metadata + resource_metadata WWW-Authenticate)

Restlet HTTP Chain — REST (with OAuth2 auth):
  Server → OAuth2AuthenticationRestlet → Router → ResourceRestlet
  (JWT validation only — no MCP protocol overlay)
```

---

## 4. Design Analogy

### How authentication works across adapter types

```
REST Adapter (proposed)             MCP Adapter (proposed)             Skill Adapter (proposed)
────────────────────               ──────────────────────             ────────────────────
ExposesRest                        ExposesMcp                         ExposesSkill
├─ authentication                  ├─ authentication   ← NEW          ├─ authentication
│  ├─ type: bearer                 │  ├─ type: bearer                  │  ├─ type: bearer
│  ├─ type: apikey                 │  ├─ type: apikey                  │  ├─ type: apikey
│  ├─ type: basic                  │  ├─ type: basic                   │  ├─ type: basic
│  ├─ type: digest                 │  ├─ type: digest                  │  ├─ type: digest
│  └─ type: oauth2 ← NEW          │  └─ type: oauth2  ← NEW          │  └─ type: oauth2 ← NEW
│                                  │                                   │
├─ resources[]                     ├─ tools[]                          ├─ skills[]
│  └─ operations[]                 ├─ resources[]                      │
└─ (Restlet filter chain)          └─ prompts[]                        └─ (Restlet filter chain)
                                      (Restlet filter chain)

Filter/Handler flow:

REST:   OAuth2AuthenticationRestlet ──→ Router ──→ ResourceRestlet
        (shared — JWT validation only)

MCP:    McpOAuth2Restlet ──→ Router ──→ McpServerResource ──→ ProtocolDispatcher
        (extends shared + Protected Resource Metadata + resource_metadata WWW-Authenticate)

Skill:  OAuth2AuthenticationRestlet ──→ Router ──→ SkillServerResource
        (shared — JWT validation only)
```

### Conceptual mapping

| Concept | REST Adapter | MCP Adapter (proposed) |
|---------|-------------|------------------------|
| Auth config location | `exposes[].authentication` | `exposes[].authentication` |
| Static credential validation | `ServerAuthenticationRestlet` | `ServerAuthenticationRestlet` (reused) |
| HTTP challenge (basic/digest) | Restlet `ChallengeAuthenticator` | Restlet `ChallengeAuthenticator` (reused) |
| OAuth2 resource server | `OAuth2AuthenticationRestlet` (shared, new) | `McpOAuth2Restlet` (extends shared + MCP protocol) |
| Credential source | `binds` → environment vars | `binds` → environment vars |
| Timing-safe comparison | `MessageDigest.isEqual()` | `MessageDigest.isEqual()` |
| Transport applicability | HTTP only | HTTP only (skip for stdio) |

---

## 5. Core Concepts

### 5.1 Static Authentication (bearer, apikey, basic, digest)

Identical to the REST adapter. The engine resolves credential templates from `binds`, then validates incoming requests:

- **Bearer**: Extract `Authorization: Bearer <token>` header; compare with `MessageDigest.isEqual()`
- **API Key**: Extract from header or query parameter by configured key name; compare value
- **Basic**: Decode `Authorization: Basic <base64>` to `username:password`; compare both
- **Digest**: HTTP Digest challenge-response (implemented via Restlet's `ChallengeAuthenticator`)

Static authentication is best for:
- Internal/private MCP servers with a shared secret
- Development and testing
- Simple deployments where OAuth infrastructure is unnecessary

### 5.2 OAuth 2.1 Authentication (oauth2)

Any adapter acts as an **OAuth 2.1 resource server** (RFC 6749 / OAuth 2.1 draft-13). It does not issue tokens — it validates tokens issued by an external authorization server. The core validation logic (JWT/JWKS, audience, expiry, scope) is shared across all adapter types via `OAuth2AuthenticationRestlet`.

**Configuration declares:**
- `authorizationServerUri` — The authorization server's issuer URL (used to derive metadata endpoints)
- `resource` — The canonical URI of this MCP server (used in `resource_metadata`, RFC 8707)
- `scopes` — Scopes this resource server recognizes (used in `scopes_supported` in Protected Resource Metadata, and in `WWW-Authenticate` challenges)
- `tokenValidation` — How to validate tokens: `jwks` (default, fetch public keys from AS) or `introspection` (call AS token introspection endpoint, RFC 7662)
- `audience` — Expected audience claim (`aud`) in the token (defaults to `resource` if not set)

**Runtime behavior:**
1. Unauthenticated request → `401` with `WWW-Authenticate: Bearer resource_metadata="<url>"`
2. Client discovers AS and obtains token (client responsibility)
3. Authenticated request → validate JWT signature, expiry, audience, scopes
4. Insufficient scope → `403` with `WWW-Authenticate: Bearer error="insufficient_scope", scope="<required>"`

### 5.3 Protected Resource Metadata (RFC 9728)

When `oauth2` authentication is configured, the engine auto-serves the Protected Resource Metadata endpoint. The path is derived from the MCP endpoint path:

- MCP endpoint at `/mcp` → metadata at `/.well-known/oauth-protected-resource/mcp`
- MCP endpoint at root `/` → metadata at `/.well-known/oauth-protected-resource`

Response:
```json
{
  "resource": "https://mcp.example.com/mcp",
  "authorization_servers": ["https://auth.example.com"],
  "scopes_supported": ["tools:read", "tools:execute"],
  "bearer_methods_supported": ["header"]
}
```

This is generated from the YAML configuration — no manual metadata file needed.

---

## 6. Schema Changes — Authentication (Shared)

### 6.1 Add `AuthOAuth2` to the shared `Authentication` union

Add `AuthOAuth2` to the existing `Authentication` union used by all adapter types. This makes OAuth 2.1 available to REST, MCP, and Skill adapters through the same `authentication` property they already support:

```json
"Authentication": {
  "description": "Authentication for exposed server adapters. Supports static credentials and OAuth 2.1 resource server mode.",
  "oneOf": [
    { "$ref": "#/$defs/AuthBasic" },
    { "$ref": "#/$defs/AuthApiKey" },
    { "$ref": "#/$defs/AuthBearer" },
    { "$ref": "#/$defs/AuthDigest" },
    { "$ref": "#/$defs/AuthOAuth2" }
  ]
}
```

### 6.2 Add `authentication` to `ExposesMcp`

The `ExposesMcp` definition references the same shared `Authentication` union already used by `ExposesRest` and `ExposesSkill`:

```json
"ExposesMcp": {
  "properties": {
    "type": { "const": "mcp" },
    "transport": { ... },
    "address": { ... },
    "port": { ... },
    "namespace": { ... },
    "description": { ... },
    "authentication": {
      "$ref": "#/$defs/Authentication"
    },
    "tools": { ... },
    "resources": { ... },
    "prompts": { ... }
  }
}
```

**Design note:** No separate `McpAuthentication` union is needed. The `AuthOAuth2` type is generic — its JWT/JWKS validation, audience, and scope logic applies equally to all adapters. MCP-specific protocol concerns (Protected Resource Metadata, `resource_metadata` in `WWW-Authenticate`) are handled in the engine layer, not the schema.

### 6.3 New `AuthOAuth2` Definition

```json
"AuthOAuth2": {
  "type": "object",
  "description": "OAuth 2.1 resource server authentication. The server validates bearer tokens issued by an external authorization server. Available on all adapter types.",
  "properties": {
    "type": {
      "type": "string",
      "const": "oauth2"
    },
    "authorizationServerUri": {
      "type": "string",
      "format": "uri",
      "description": "Issuer URL of the OAuth 2.1 authorization server. The engine derives metadata endpoints (.well-known/oauth-authorization-server) from this URL."
    },
    "resource": {
      "type": "string",
      "format": "uri",
      "description": "Canonical URI of this MCP server (RFC 8707). Used as the 'resource' field in Protected Resource Metadata and for audience validation. Example: https://mcp.example.com/mcp"
    },
    "scopes": {
      "type": "array",
      "items": { "type": "string" },
      "description": "Scopes this resource server recognizes. Published in Protected Resource Metadata as 'scopes_supported' and included in WWW-Authenticate challenges."
    },
    "audience": {
      "type": "string",
      "description": "Expected 'aud' claim in the JWT. Defaults to the 'resource' URI if not set."
    },
    "tokenValidation": {
      "type": "string",
      "enum": ["jwks", "introspection"],
      "default": "jwks",
      "description": "How to validate incoming access tokens. 'jwks' (default): fetch the AS public keys and validate JWT signatures locally. 'introspection': call the AS token introspection endpoint (RFC 7662) for each request."
    }
  },
  "required": ["type", "authorizationServerUri", "resource"],
  "additionalProperties": false
}
```

---

## 7. Schema Changes — Aggregates

No aggregate schema changes are needed. Authentication is an adapter-level concern, not a domain function concern. `semantics` (safe, idempotent, cacheable) describe domain behavior; authentication is orthogonal.

---

## 8. Implementation Examples

### 8.1 Static Bearer Token (Same Pattern as REST)

Protects the MCP server with a shared secret, identical to the REST adapter:

```yaml
naftiko: "1.0.0-alpha1"

info:
  label: "Protected MCP Server"
  description: "An MCP server protected with a static bearer token"

capability:
  exposes:
    - type: mcp
      port: 3001
      namespace: protected-tools
      description: "Tools requiring a bearer token"
      authentication:
        type: bearer
        token: "{{MCP_SERVER_TOKEN}}"
      tools:
        - name: list-users
          description: "List all users"
          call: "user-api.list-users"
          outputParameters:
            - name: users
              type: array

  consumes:
    - type: http
      namespace: user-api
      baseUri: "https://api.example.com"
      resources:
        - path: "/users"
          operations:
            - operationId: list-users
              method: GET
              outputParameters:
                - name: users
                  type: array
                  mapping: "$.data"

binds:
  - namespace: mcp-secrets
    location: "file:///etc/naftiko/secrets.env"
    keys:
      MCP_SERVER_TOKEN: ""
```

### 8.2 API Key in Header

```yaml
authentication:
  type: apikey
  key: "X-API-Key"
  value: "{{MCP_API_KEY}}"
  placement: header
```

### 8.3 OAuth 2.1 Resource Server (MCP-Native)

Full MCP 2025-11-25 authorization compliance:

```yaml
naftiko: "1.0.0-alpha1"

info:
  label: "Enterprise MCP Server"
  description: "MCP server with OAuth 2.1 authorization, integrated with Keycloak"

capability:
  exposes:
    - type: mcp
      port: 3001
      namespace: enterprise-tools
      description: "Enterprise tools requiring OAuth authorization"
      authentication:
        type: oauth2
        authorizationServerUri: "https://keycloak.example.com/realms/mcp"
        resource: "https://mcp.example.com/mcp"
        scopes:
          - "tools:read"
          - "tools:execute"
        tokenValidation: jwks
      tools:
        - name: list-orders
          label: "List Orders"
          description: "List all orders for the current tenant"
          call: "order-api.list-orders"
          outputParameters:
            - name: orders
              type: array
        - name: create-order
          label: "Create Order"
          description: "Create a new order"
          inputParameters:
            - name: product-id
              type: string
              description: "Product identifier"
              required: true
          call: "order-api.create-order"
          with:
            product-id: "{{enterprise-tools.product-id}}"
          outputParameters:
            - name: order-id
              type: string

  consumes:
    - type: http
      namespace: order-api
      baseUri: "https://api.example.com"
      resources:
        - path: "/orders"
          operations:
            - operationId: list-orders
              method: GET
              outputParameters:
                - name: orders
                  type: array
                  mapping: "$.data"
            - operationId: create-order
              method: POST
              inputParameters:
                - name: product-id
                  in: body
              outputParameters:
                - name: order-id
                  type: string
                  mapping: "$.id"
```

### 8.4 OAuth 2.1 with Token Introspection

For opaque tokens (not JWTs) — validate via AS introspection endpoint:

```yaml
authentication:
  type: oauth2
  authorizationServerUri: "https://auth0.example.com"
  resource: "https://mcp.example.com/mcp"
  scopes:
    - "read:tools"
    - "execute:tools"
  tokenValidation: introspection
```

### 8.5 With Aggregates and Ref

OAuth authentication works seamlessly with aggregate functions:

```yaml
capability:
  aggregates:
    - namespace: order-domain
      functions:
        - name: list-orders
          label: "List Orders"
          description: "List all orders"
          semantics:
            safe: true
            cacheable: true
          call: "order-api.list-orders"
          outputParameters:
            - name: orders
              type: array

  exposes:
    - type: mcp
      port: 3001
      namespace: mcp-orders
      description: "Order management tools"
      authentication:
        type: oauth2
        authorizationServerUri: "https://keycloak.example.com/realms/mcp"
        resource: "https://mcp.example.com/mcp"
        scopes:
          - "orders:read"
      tools:
        - name: list-orders
          ref: "order-domain.list-orders"
```

---

## 9. Engine Behavior

### 9.1 Authentication Handler Insertion

`McpServerAdapter.initHttpTransport()` currently passes the `Router` directly to `initServer()`. With authentication, a `buildServerChain()` method wraps the router — identical to the REST adapter's pattern:

```java
// Pseudocode — current
Router router = new Router(context);
router.attachDefault(McpServerResource.class);
initServer(address, port, router);

// Pseudocode — proposed
Router router = new Router(context);
router.attachDefault(McpServerResource.class);
Restlet chain = buildServerChain(serverSpec, router);
initServer(address, port, chain);
```

Where `buildServerChain` returns:
- `ServerAuthenticationRestlet` wrapping the router for static types (bearer, apikey)
- Restlet `ChallengeAuthenticator` wrapping the router for basic/digest
- `McpOAuth2Restlet` wrapping the router for `oauth2` (MCP adapter only)
- `OAuth2AuthenticationRestlet` wrapping the router for `oauth2` (REST and Skill adapters)

### 9.2 Static Authentication (reuses `ServerAuthenticationRestlet`)

The existing `ServerAuthenticationRestlet` is reused directly — no adapter-specific class needed. The Restlet sits before the `Router` in the chain:

1. Extracts credentials from the HTTP request (bearer token or API key)
2. Resolves `{{VARIABLE}}` templates from environment, restricted to `binds`-declared keys
3. Compares using `MessageDigest.isEqual()` (timing-safe)
4. On success: delegates to the next `Restlet` (the `Router` → `ServerResource`)
5. On failure: returns `401 Unauthorized` with appropriate challenge header

For basic/digest authentication, the existing Restlet `ChallengeAuthenticator` is reused.

```
Request → ServerAuthenticationRestlet
           ├─ Valid credentials → Router → ServerResource
           └─ Invalid/missing  → 401 Unauthorized
```

### 9.3 Shared OAuth 2.1 Handler (`OAuth2AuthenticationRestlet`)

A shared Restlet `Restlet` subclass in `io.naftiko.engine.exposes` that implements the core OAuth 2.1 resource server logic. Used directly by REST and Skill adapters; extended by `McpOAuth2Restlet` for MCP-specific protocol concerns.

**Initialization:**
1. Fetch AS metadata from `authorizationServerUri` (try `.well-known/oauth-authorization-server` then `.well-known/openid-configuration`)
2. If `tokenValidation: jwks` — fetch JWKS from the AS `jwks_uri` endpoint; cache keys with configurable TTL
3. If `tokenValidation: introspection` — store AS `introspection_endpoint` URL

**Request handling:**

```
Request → OAuth2AuthenticationRestlet
           ├─ No Authorization header → 401 + WWW-Authenticate: Bearer
           ├─ Invalid/expired token → 401 + WWW-Authenticate: Bearer
           ├─ Insufficient scope → 403 + WWW-Authenticate: Bearer error="insufficient_scope"
           └─ Valid token → next Restlet (Router → ServerResource)
```

**Token validation (JWKS mode):**
1. Extract `Authorization: Bearer <token>` header
2. Decode JWT; verify signature against cached JWKS
3. Check `exp` (expiry), `iss` (issuer matches `authorizationServerUri`), `aud` (matches `audience` or `resource`)
4. Check `scope` claim against configured `scopes` (if scopes are declared)

**Token validation (introspection mode):**
1. Extract `Authorization: Bearer <token>` header
2. POST to AS `introspection_endpoint` with `token=<token>`
3. Verify response `active: true`, check audience and scope

### 9.4 MCP OAuth 2.1 Handler (`McpOAuth2Restlet`)

`McpOAuth2Restlet` extends `OAuth2AuthenticationRestlet` to add MCP 2025-11-25 protocol-specific behavior:

1. **Protected Resource Metadata** — intercepts `GET /.well-known/oauth-protected-resource` and serves auto-generated metadata (see §9.5)
2. **`resource_metadata` in WWW-Authenticate** — enriches the `401`/`403` challenge headers with the `resource_metadata` URL parameter per RFC 9728

```
Request → McpOAuth2Restlet
           ├─ GET /.well-known/oauth-protected-resource → Return metadata JSON
           └─ All other requests → delegate to OAuth2AuthenticationRestlet
                ├─ Valid token → Router → McpServerResource → ProtocolDispatcher
                └─ Invalid → 401/403 + WWW-Authenticate (with resource_metadata)
```

### 9.5 Protected Resource Metadata Endpoint

Auto-generated from configuration:

```json
{
  "resource": "<authentication.resource>",
  "authorization_servers": ["<authentication.authorizationServerUri>"],
  "scopes_supported": ["<authentication.scopes[0]>", "..."],
  "bearer_methods_supported": ["header"]
}
```

Served at the well-known path derived from the MCP endpoint.

### 9.6 WWW-Authenticate Header Generation

On `401 Unauthorized`:
```
WWW-Authenticate: Bearer resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource/mcp"
```

If scopes are configured:
```
WWW-Authenticate: Bearer resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource/mcp",
                         scope="tools:read tools:execute"
```

On `403 Forbidden` (insufficient scope):
```
WWW-Authenticate: Bearer error="insufficient_scope",
                         scope="tools:execute",
                         resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource/mcp",
                         error_description="Required scope 'tools:execute' not present in token"
```

### 9.7 stdio Transport — No Authentication

Per MCP spec §1.2: "Implementations using an STDIO transport SHOULD NOT follow this specification, and instead retrieve credentials from the environment."

When `transport: "stdio"`, the `authentication` field is ignored (or rejected by validation — see Validation Rules). The engine logs a warning if both `transport: "stdio"` and `authentication` are set.

---

## 10. Security Considerations

### 10.1 Token Passthrough Prevention

The MCP 2025-11-25 spec explicitly forbids token passthrough: "MCP servers MUST NOT accept or transit any other tokens" and "MUST NOT pass through the token it received from the MCP client."

When the MCP server acts as an OAuth 2.1 resource server and also consumes upstream APIs:
- The **incoming** MCP bearer token authenticates the MCP client to the MCP server
- The **outgoing** requests to consumed APIs use their own `consumes[].authentication` credentials (e.g., API key, separate bearer token)
- These are completely independent credential chains — the engine MUST NOT forward the MCP client's token to consumed APIs

This is already the natural behavior in Naftiko, since `consumes` authentication is configured independently. No additional safeguard is needed.

### 10.2 Audience Validation

Tokens must be validated for their intended audience to prevent cross-service token reuse:
- If `audience` is configured, the JWT `aud` claim MUST contain that value
- If `audience` is not configured, default to `resource` URI
- Tokens without an `aud` claim SHOULD be rejected when audience validation is enabled

### 10.3 Timing-Safe Comparison

Static authentication (bearer, apikey, basic, digest) MUST continue to use `MessageDigest.isEqual()` for constant-time credential comparison, as the existing `ServerAuthenticationRestlet` does.

### 10.4 HTTPS Enforcement

The MCP spec requires "All authorization server endpoints MUST be served over HTTPS." While Naftiko does not enforce HTTPS on the MCP server itself (that is typically handled by a reverse proxy), the `authorizationServerUri` MUST use the `https` scheme.

Validation rule: `authorizationServerUri` must start with `https://`.

### 10.5 JWKS Key Caching

JWKS keys fetched from the authorization server should be cached to avoid network latency on every request:
- Default cache TTL: 5 minutes
- If a JWT contains an unknown `kid`, trigger a one-time JWKS refresh (key rotation support)
- Maximum refresh rate: once per 30 seconds (prevents DoS via unknown-kid flood)

### 10.6 Origin Header Validation

The Streamable HTTP transport spec requires: "Servers MUST validate the `Origin` header on all incoming connections to prevent DNS rebinding attacks." This is an existing concern for the MCP adapter (regardless of authentication) and should be implemented as part of this work.

---

## 11. Validation Rules

### 11.1 Schema Validation (JSON Schema)

These constraints are enforced by the schema itself:

| Rule | Enforcement |
|------|-------------|
| `authentication` is optional on `ExposesMcp` | Not in `required` array |
| `AuthOAuth2.authorizationServerUri` is required | In `required` array |
| `AuthOAuth2.resource` is required | In `required` array |
| `AuthOAuth2.tokenValidation` defaults to `jwks` | `default: "jwks"` |
| `AuthOAuth2.type` must be `"oauth2"` | `const: "oauth2"` |

### 11.2 Spectral Rules (naftiko-rules.yml)

Additional cross-field validations:

| Rule Name | Severity | Scope | Description |
|-----------|----------|-------|-------------|
| `naftiko-oauth2-https-authserver` | error | All adapters | `authorizationServerUri` must use `https://` scheme |
| `naftiko-oauth2-resource-https` | warn | All adapters | `resource` should use `https://` scheme for production |
| `naftiko-oauth2-scopes-defined` | warn | All adapters | `scopes` array should be defined for OAuth2 auth (enables scope challenges) |
| `naftiko-mcp-auth-stdio-conflict` | warn | MCP only | `authentication` should not be set when `transport: "stdio"` |

---

## 12. Design Decisions & Rationale

### D1: Shared `Authentication` union with `AuthOAuth2`

**Decision**: Add `AuthOAuth2` directly to the shared `Authentication` union used by all three adapters.

**Rationale**: The core OAuth 2.1 resource server logic — JWT/JWKS validation, audience/expiry/scope checks, token introspection — is not adapter-specific. Any HTTP server protecting endpoints with bearer tokens needs the same validation. A shared union avoids a parallel `McpAuthentication` type and ensures REST and Skill adapters get OAuth 2.1 support without additional schema work.

**Alternative considered**: A separate `McpAuthentication` union containing the four existing types plus `AuthOAuth2`, keeping REST/Skill unaffected. Rejected because the `AuthOAuth2` configuration shape is generic, and restricting it to one adapter would force duplication when the others inevitably need it.

### D2: Shared `OAuth2AuthenticationRestlet` with MCP extension

**Decision**: Implement a shared `OAuth2AuthenticationRestlet` in `io.naftiko.engine.exposes` for core JWT validation, and extend it in `McpOAuth2Restlet` for MCP protocol-specific concerns.

**Rationale**: The MCP adapter requires Protected Resource Metadata (RFC 9728) and `resource_metadata` in `WWW-Authenticate` headers — neither of which applies to REST or Skill. The two-class design cleanly separates shared validation from protocol overlay. REST and Skill adapters use `OAuth2AuthenticationRestlet` directly; the MCP adapter uses its subclass.

### D3: No per-tool scope mapping

**Decision**: Authentication is all-or-nothing at the adapter level. No per-tool `requiredScope` field.

**Rationale**: Per-tool scoping is an authorization concern, not an authentication concern. It adds significant schema complexity and runtime overhead for a feature most deployments won't use. The MCP spec defines scope challenges but leaves scope-to-tool mapping to implementations. A future "authorization policy" layer can add this without changing the authentication schema.

### D4: JWKS as default validation, introspection as option

**Decision**: Default to JWKS-based JWT validation; support introspection as an opt-in alternative.

**Rationale**: JWT validation with JWKS is stateless, fast, and the standard approach for modern OAuth deployments. Introspection is needed for opaque tokens (some legacy AS configurations, or when tokens are reference tokens rather than self-contained JWTs). Supporting both covers the full spectrum without over-engineering the default path.

### D5: Auto-serve Protected Resource Metadata

**Decision**: The engine auto-generates and serves the `/.well-known/oauth-protected-resource` endpoint from YAML configuration.

**Rationale**: The MCP spec requires this endpoint for authorization server discovery (RFC 9728). Requiring capability authors to manually create and serve this metadata would be a poor UX. By generating it from the `oauth2` authentication configuration, the YAML remains the single source of truth.

### D6: `authorizationServerUri` vs. inline AS metadata

**Decision**: Require only the AS issuer URL, not inline metadata fields.

**Rationale**: The engine discovers AS endpoints dynamically via `.well-known/oauth-authorization-server` (RFC 8414). Inlining `jwks_uri`, `introspection_endpoint`, etc. in the YAML would be redundant and fragile (AS configuration can change). The URL-only approach follows the OAuth metadata discovery pattern.

---

## 13. Implementation Roadmap

### Phase 1: Static Authentication (bearer, apikey)

**Bring MCP authentication to parity with REST adapter for the simplest cases.**

1. Add `authentication` property to `ExposesMcp` in JSON schema (use existing `Authentication` union first; `McpAuthentication` union comes in Phase 2)
2. Add `buildServerChain()` to `McpServerAdapter` — reuse `ServerAuthenticationRestlet` and `ChallengeAuthenticator` from the REST adapter, wrapping the `Router` before `McpServerResource`
3. Wire into `McpServerAdapter.initHttpTransport()` — insert authentication restlet before the `Router`
4. Add Spectral rule `naftiko-mcp-auth-stdio-conflict`
5. Tests: unit tests for chain wiring, integration test with bearer-protected MCP server

### Phase 2: OAuth 2.1 Resource Server (shared)

**Core OAuth 2.1 validation, available to all adapters.**

1. Add `AuthOAuth2` definition to JSON schema, in the shared `Authentication` union
2. Create `OAuth2AuthenticationRestlet` in `io.naftiko.engine.exposes`:
   - AS metadata discovery (RFC 8414)
   - JWKS fetching and caching
   - JWT validation (signature, expiry, issuer, audience)
   - Scope checking and `403` challenge
   - `WWW-Authenticate: Bearer` header generation
3. Wire `OAuth2AuthenticationRestlet` into `RestServerAdapter.buildServerChain()` and `SkillServerAdapter`
4. Add Spectral rules: `naftiko-oauth2-https-authserver`, `naftiko-oauth2-resource-https`, `naftiko-oauth2-scopes-defined` (adapter-agnostic)
5. Tests: unit tests for shared JWT validation; integration tests for REST and Skill adapters with OAuth2

### Phase 3: MCP Protocol Overlay

**MCP 2025-11-25-specific authorization behavior.**

1. Create `McpOAuth2Restlet` extending `OAuth2AuthenticationRestlet`:
   - Protected Resource Metadata endpoint (RFC 9728)
   - `resource_metadata` URL in `WWW-Authenticate` headers
2. Wire into `McpServerAdapter.buildServerChain()` for `oauth2` type
3. Add MCP-specific Spectral rule: `naftiko-mcp-oauth2-resource-defined` (warn if `resource` missing)
4. Tests: integration test with mock AS, Protected Resource Metadata endpoint test

### Phase 4: Token Introspection and Extended Features

**Extended OAuth features in the shared layer.**

1. Add introspection support to `OAuth2AuthenticationRestlet` (RFC 7662)
2. JWKS cache key rotation support (unknown-kid refresh)
3. Origin header validation for DNS rebinding prevention (MCP adapter)
4. Tests: introspection flow, scope challenge flow, Origin validation

---

## 14. Backward Compatibility

### Schema

- `authentication` on `ExposesMcp` is optional (not in `required`) — existing capabilities without it continue to work unchanged
- `AuthOAuth2` is added to the shared `Authentication` union — existing REST and Skill capabilities without it continue to work unchanged
- No properties removed or renamed
- New `AuthOAuth2` definition is purely additive

### Engine

- `McpServerAdapter` with no `authentication` configured behaves exactly as it does today — the `Router` is passed directly to `initServer()`, no overhead
- Existing `McpServerResource` and `ProtocolDispatcher` are not modified — authentication restlets wrap the `Router`

### Wire Protocol

- Unauthenticated MCP servers continue to accept all requests
- Authenticated MCP servers follow the standard MCP 2025-11-25 authorization challenge flow — compliant MCP clients handle `401` responses automatically
- The `initialize` JSON-RPC method is not affected by authentication (the MCP spec requires authentication on every HTTP request, including initialization)

### Test Impact

- All existing tests remain unchanged and pass
- New tests are additive
