# Guide - Reverse Tunnel for Private APIs

## Table of Contents

- [Overview](#overview)
- [When to use this guide](#when-to-use-this-guide)
- [How the sidecar pattern works](#how-the-sidecar-pattern-works)
- [Recipe 1 — FRP (Fast Reverse Proxy)](#recipe-1--frp-fast-reverse-proxy)
- [Recipe 2 — Cloudflare Tunnel](#recipe-2--cloudflare-tunnel)
- [Recipe 3 — OpenZiti tunneler](#recipe-3--openziti-tunneler)
- [Capability YAML — identical across recipes](#capability-yaml--identical-across-recipes)
- [Health checks and startup ordering](#health-checks-and-startup-ordering)
- [Security trade-offs](#security-trade-offs)
- [Observability](#observability)
- [Troubleshooting](#troubleshooting)
- [What's next — embedded tunneling](#whats-next--embedded-tunneling)

---

## Overview

Many real-world integrations need an Ikanos capability to call an HTTP API that lives **inside a corporate network** — a CRM, an ERP, an internal database — while the engine itself runs in a **public environment** (cloud, SaaS, customer's edge). Three options are tempting but each has a serious drawback:

| Option | Why it's a bad idea |
|---|---|
| Open an inbound firewall rule to the private API | Security teams refuse, and rightly — every rule is a permanent attack surface |
| Move Ikanos into the private network | Defeats the point of running capabilities centrally / publicly / as SaaS |
| Build a bespoke HTTP proxy | Out of scope for a declarative engine, and easy to get wrong |

The correct answer is a **reverse tunnel**: a small process on the private side dials **outbound** to a relay (or directly to Ikanos), then accepts traffic back through the established connection. No inbound firewall rules. No public exposure of the private API. No public DNS. The capability YAML stays declarative.

This guide documents the **sidecar pattern**, which works with **any** current Ikanos version — there is no engine change required. A future guide will cover the **embedded** alternative, where Ikanos dials through an OpenZiti overlay directly using an embedded SDK (see [What's next](#whats-next--embedded-tunneling)).

---

## When to use this guide

Use a reverse tunnel when **all** of the following are true:

- The upstream API lives behind a firewall that denies inbound traffic.
- You cannot (or do not want to) move Ikanos into that network.
- You can run **one extra process** next to the Ikanos engine (the *sidecar*) and **one matching process** on the private side.

If even one of those is not true, prefer the simpler path: a public API plus an authentication layer declared with the standard `authentication:` block.

---

## How the sidecar pattern works

The deployment topology has three pieces:

```
   PUBLIC NETWORK                                 │           PRIVATE NETWORK
                                                  │
   ┌─────────────────────────────┐                │     ┌─────────────────────────┐
   │ Ikanos engine               │                │     │ Internal API            │
   │  baseUri: http://127.0.0.1: │                │     │ (CRM, ERP, DB, …)       │
   │           8443              │                │     └────────────▲────────────┘
   └─────────────┬───────────────┘                │                  │ HTTP
                 │ HTTP (loopback)                │                  │
   ┌─────────────▼───────────────┐                │     ┌────────────┴────────────┐
   │ Tunnel sidecar (frpc /      │                │     │ Tunnel server / agent   │
   │ cloudflared / ziti-tunneler)│ ◄── tunnel ────│─────│ (frps / cloudflared /   │
   └─────────────────────────────┘   (outbound    │     │ ziti-edge-tunnel)       │
                                     from private)     └─────────────────────────┘
```

Key invariants:

1. **The private-side process always dials out.** The firewall on the private network only needs to permit *outbound* traffic to a known relay or controller. Many corporate firewalls already allow this for HTTPS on 443.
2. **The Ikanos capability YAML is unchanged.** `consumes.baseUri` points at `http://127.0.0.1:<port>` (or a synthetic intercept hostname resolved by the sidecar). The capability has no idea a tunnel is in the loop — it sees a normal HTTP endpoint.
3. **Authentication still runs end-to-end** on top of the tunnel. The tunnel is transport; bearer tokens / OAuth2 / API keys declared via the standard `authentication:` block are unaffected.

---

## Recipe 1 — FRP (Fast Reverse Proxy)

[**FRP**](https://github.com/fatedier/frp) is an Apache 2.0 reverse proxy with a self-hostable control plane (`frps`) and a small client (`frpc`). Recommended default for self-hosted setups.

### Components

| Side | Process | Role |
|---|---|---|
| Public (next to Ikanos) | `frpc` | Listens on `127.0.0.1:<port>`, forwards each request through the tunnel |
| Public (relay) | `frps` | Accepts the outbound connection from the private side and brokers requests |
| Private | `frpc` | Connects outbound to `frps`, dials the internal API on each request |

Both `frpc` instances connect outbound to the same `frps`. The public-side `frpc` exposes a local listener; the private-side `frpc` exposes the internal API to the tunnel.

### `docker-compose.yml`

```yaml
version: "3.9"
services:
  ikanos:
    image: ghcr.io/naftiko/ikanos:latest
    ports:
      - "8081:8081"     # REST/MCP exposure
    volumes:
      - ./capability.yaml:/app/capability.yaml:ro
    depends_on:
      frpc:
        condition: service_healthy

  frpc:
    image: snowdreamtech/frpc:latest
    network_mode: "service:ikanos"   # share Ikanos' loopback
    volumes:
      - ./frpc.toml:/etc/frp/frpc.toml:ro
    healthcheck:
      test: ["CMD", "wget", "-q", "-O-", "http://127.0.0.1:7400/healthz"]
      interval: 5s
      timeout: 2s
      retries: 12
```

> `network_mode: "service:ikanos"` puts `frpc` and Ikanos on the same loopback, so `baseUri: http://127.0.0.1:8443` resolves to the sidecar's local listener.

### `frpc.toml` (public side)

```toml
serverAddr = "frps.example.com"
serverPort = 7000
auth.method = "token"
auth.token = "{{REPLACE_WITH_TOKEN}}"

# Expose a local listener that maps to the private CRM
[[visitors]]
name = "crm"
type = "stcp"
serverName = "crm-private"
secretKey = "{{REPLACE_WITH_SECRET}}"
bindAddr = "127.0.0.1"
bindPort = 8443
```

The private-side `frpc` mirrors this with `type = "stcp"`, `name = "crm-private"`, the matching `secretKey`, and `localIP` / `localPort` pointing at the internal API. See the [FRP documentation](https://github.com/fatedier/frp#example-usage) for the full server + private-side config.

### Security profile

- **Authentication**: shared token between every `frpc` and `frps`; per-tunnel `secretKey` for `stcp` visitors.
- **Encryption**: optional mTLS on `frpc ↔ frps`. For HTTPS upstream, the application-layer TLS handshake still runs end-to-end inside the tunnel — `frpc` only sees ciphertext.
- **Granularity**: per-tunnel keys. A leaked `secretKey` only exposes the matching `stcp` mapping, not the whole network.

---

## Recipe 2 — Cloudflare Tunnel

[**Cloudflare Tunnel**](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/) (`cloudflared`) is the lowest-setup option **if you accept a SaaS control plane**. Cloudflare brokers the connection; you do not run a relay server.

### Components

| Side | Process | Role |
|---|---|---|
| Public (next to Ikanos) | `cloudflared access tcp` | Opens a local TCP listener that proxies to a hostname routed via Cloudflare Access |
| Public (control plane) | Cloudflare Access | Identity-aware proxy that authenticates the public side and brokers the tunnel |
| Private | `cloudflared tunnel` | Dials outbound to Cloudflare's edge and registers as the origin for a hostname |

### `docker-compose.yml`

```yaml
version: "3.9"
services:
  ikanos:
    image: ghcr.io/naftiko/ikanos:latest
    ports:
      - "8081:8081"
    volumes:
      - ./capability.yaml:/app/capability.yaml:ro
    depends_on:
      cloudflared:
        condition: service_started

  cloudflared:
    image: cloudflare/cloudflared:latest
    network_mode: "service:ikanos"
    command:
      - access
      - tcp
      - --hostname
      - crm.internal.example.com
      - --url
      - 127.0.0.1:8443
    environment:
      TUNNEL_SERVICE_TOKEN_ID:     "${CF_SERVICE_TOKEN_ID}"
      TUNNEL_SERVICE_TOKEN_SECRET: "${CF_SERVICE_TOKEN_SECRET}"
```

The private-side `cloudflared tunnel run <UUID>` is configured separately and points at the internal API. Authorization between the public sidecar and the tunnel is enforced by **Cloudflare Access Service Tokens**.

### Security profile

- **Authentication**: Cloudflare Access Service Tokens (or SSO / mTLS) on the public side; tunnel credentials on the private side. Both are issued and revocable from the Cloudflare dashboard.
- **Encryption**: end-to-end TLS to Cloudflare's edge; mTLS optional on both legs.
- **Granularity**: per-hostname. Service Tokens can be revoked individually.
- **Trade-off**: the control plane is **not self-hostable** — you depend on Cloudflare. If air-gap or sovereignty is a requirement, prefer FRP or OpenZiti.

---

## Recipe 3 — OpenZiti tunneler

[**OpenZiti**](https://openziti.io/) is an Apache 2.0 zero-trust overlay with a fully self-hostable control plane and short-lived, identity-bound credentials. This recipe runs the standard `ziti-edge-tunnel` as a sidecar — the same overlay that a future Ikanos release will support **without a sidecar** through an embedded SDK (see [What's next](#whats-next--embedded-tunneling)).

### Components

| Side | Process | Role |
|---|---|---|
| Public (next to Ikanos) | `ziti-edge-tunnel run` | Loads a Ziti identity, intercepts a configured hostname, routes through the overlay |
| Public (control plane) | Ziti controller + edge router(s) | Brokers service authorization and connection routing |
| Private | `ziti-edge-tunnel run` (host mode) | Loads a different identity, hosts the internal API as a Ziti **service** |

### `docker-compose.yml`

```yaml
version: "3.9"
services:
  ikanos:
    image: ghcr.io/naftiko/ikanos:latest
    ports:
      - "8081:8081"
    volumes:
      - ./capability.yaml:/app/capability.yaml:ro
    depends_on:
      ziti-tunnel:
        condition: service_started

  ziti-tunnel:
    image: openziti/ziti-edge-tunnel:latest
    network_mode: "service:ikanos"
    cap_add: ["NET_ADMIN"]
    devices: ["/dev/net/tun"]
    volumes:
      - ./ikanos-identity.json:/ziti-edge-tunnel/identity.json:ro
    command: ["run", "--identity", "/ziti-edge-tunnel/identity.json"]
```

The Ziti controller is configured so the identity in `ikanos-identity.json` is authorized to dial the `crm-api` service. The `intercept.v1` config attached to that service rewrites the public-side dial to a synthetic `100.64.x.x` address, so `baseUri: https://crm.internal` resolves through the overlay without any DNS record.

> Because `ziti-edge-tunnel` requires `NET_ADMIN` and `/dev/net/tun`, this recipe is the most container-runtime-sensitive of the three. The future embedded SDK path (see below) avoids both — the Ikanos JVM dials through the overlay directly, using only socket-level integration.

### Security profile

- **Authentication**: x509-based identity enrollment per side; short-lived session credentials issued by the controller.
- **Encryption**: end-to-end mTLS through every hop of the Ziti fabric. Application-layer TLS still runs end-to-end on top.
- **Granularity**: per-service authorization in the controller. Revoking an identity disables only the services it was authorized for; other identities are unaffected.
- **Trade-off**: setting up the controller + edge router takes more time than FRP, but the operational model is strongest of the three.

---

## Capability YAML — identical across recipes

Whichever sidecar you choose, the Ikanos capability stays the same:

```yaml
ikanos: 1.0.0-alpha3

binds:
  - namespace: secrets
    location: env://
    keys:
      CRM_TOKEN: CRM_TOKEN

consumes:
  - namespace: crm
    type: http
    description: |
      Internal CRM API, reached through a reverse-tunnel sidecar.
      The sidecar (frpc / cloudflared / ziti-edge-tunnel) is responsible for
      routing 127.0.0.1:8443 to the private CRM. From Ikanos' point of view
      this is a regular HTTP endpoint.
    baseUri: http://127.0.0.1:8443
    authentication:
      type: bearer
      token: "{{secrets.CRM_TOKEN}}"
    resources:
      - name: customers
        path: /v1/customers/{id}
        operations:
          - name: get
            method: GET
            inputParameters:
              - { name: id, in: path, required: true }
```

Two things to notice:

- `baseUri` uses `http://127.0.0.1:<port>` rather than the real internal hostname. The sidecar owns the mapping — keeping it out of the YAML means the same capability can be deployed against different sidecars without edits.
- `authentication:` is unchanged. The bearer token authenticates the **capability to the upstream API**, end-to-end inside the tunnel. The tunnel never sees plaintext if the upstream is HTTPS.

> **OpenZiti note** — if you set the `ziti-edge-tunnel` intercept to a hostname (e.g. `crm.internal`) and add it to the engine container's `/etc/hosts`, you can keep `baseUri: https://crm.internal` instead of `http://127.0.0.1:8443`. The two styles are equivalent.

---

## Health checks and startup ordering

The sidecar must be **ready before Ikanos starts polling consumed endpoints**, otherwise the first health check on `/health/ready` may fail. Use one of the following:

- **Docker Compose** — `depends_on: { sidecar: { condition: service_healthy } }` plus a `healthcheck:` block on the sidecar (FRP example above).
- **Kubernetes** — model the sidecar as a regular sidecar container with a `readinessProbe`. The pod is not considered ready until both containers report ready, so `/health/ready` only succeeds once the tunnel is up.
- **systemd** — put `Requires=` and `After=` on the Ikanos unit pointing at the tunnel unit.

Once Ikanos is running, the Control Port reflects readiness of the **consumed endpoint**, not the tunnel itself — there is no Ikanos-side visibility into the sidecar's health in this pattern. If you need tunnel state to surface in `/health/ready` and `/metrics`, that is a property of the **embedded** path (Phase 2+ of the roadmap), not the sidecar pattern.

---

## Security trade-offs

| Property | FRP | Cloudflare Tunnel | OpenZiti tunneler |
|---|---|---|---|
| Self-hostable control plane | Yes | No (SaaS) | Yes |
| Short-lived credentials | Optional (TLS rotation) | Yes (Service Tokens) | Yes (Ziti enrollment) |
| Per-service authorization | Per `stcp` `secretKey` | Per hostname / Access policy | Per Ziti service |
| Setup complexity | Low | Lowest | Medium |
| Air-gap-friendly | Yes | No | Yes |
| One identity per capability | Token-shared | Service Token per sidecar | Identity per sidecar |

All three preserve the **no-inbound-firewall-rule** property — that is the whole point. They differ on credential lifecycle, blast radius of a leak, and operational model.

---

## Observability

The sidecar is opaque to Ikanos. Sidecars expose their own metrics:

- `frpc` ships a dashboard on `127.0.0.1:7400` (configurable) with per-tunnel connection counts, bytes in/out, and last-error timestamps.
- `cloudflared` exposes Prometheus metrics on `127.0.0.1:2000/metrics` when started with `--metrics 127.0.0.1:2000`.
- `ziti-edge-tunnel` reports session state via the controller's API and exposes local metrics through the Ziti agent socket.

Scrape these alongside Ikanos' own `/metrics` endpoint to correlate consumed-operation errors with tunnel state. Trace context propagates end-to-end as usual — the tunnel is transparent at the HTTP layer.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `/health/ready` returns 503 at startup, then recovers | Ikanos started before the sidecar | Add a `healthcheck:` + `depends_on: condition: service_healthy` (Compose) or `readinessProbe` (k8s) on the sidecar |
| Consumed operations fail with `Connection refused` on `127.0.0.1` | Sidecar not listening on the expected loopback / port | Verify the sidecar's bind address; ensure `network_mode: "service:ikanos"` (Compose) so loopback is shared |
| Consumed operations fail with `UnknownHostException` | `baseUri` uses a hostname the engine cannot resolve | Either point `baseUri` at `127.0.0.1:<port>` (recommended) or add the hostname to the engine container's `/etc/hosts` |
| Intermittent 502 / `Connection reset` mid-request | Tunnel re-establishing on the private side | Check sidecar logs for reconnect events; increase keep-alive on the tunnel; verify outbound port 443 (or the FRP port) is reliably open from the private side |
| Authentication errors despite a correct bearer token | TLS terminated at the sidecar (HTTP `baseUri` against an HTTPS upstream) | Use the same scheme end-to-end — either keep HTTPS through the sidecar, or terminate TLS *only* at the sidecar and use HTTP `baseUri` |

---

## What's next — embedded tunneling

The sidecar pattern works today and will keep working — it is a **supported deployment recipe**, not a workaround. Even so, it has two operational costs:

- **An extra process per Ikanos instance.** One more container to ship, monitor, restart, and roll out.
- **Opaque to the engine.** Tunnel state does not surface in Ikanos' `/health/ready`, `/metrics`, or distributed traces; capability YAML cannot declare "this endpoint requires tunnel X" in a way the engine understands.

A future Ikanos release will add an **embedded** alternative — a first-class `tunnel:` block on `ConsumesHttp` and an embedded OpenZiti SDK that lets the engine dial the overlay directly:

```yaml
# Preview — not available yet. Tracked in the Reverse Tunnel blueprint (Phases 1–5).
consumes:
  - namespace: crm
    type: http
    baseUri: https://crm.internal
    tunnel:
      type: ziti
      service: crm-api
      identity: "{{secrets.ZITI_IDENTITY}}"
    authentication:
      type: bearer
      token: "{{secrets.CRM_TOKEN}}"
```

When the embedded path lands, both options will be supported side by side — the sidecar recipe remains the right choice for FRP, Cloudflare Tunnel, or any deployment where adding an SDK to the JVM is undesirable.
