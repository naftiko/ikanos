# MCP Server Adapter — Restlet Transport Migration
## Replacing Jetty with Restlet Framework for the MCP HTTP Transport

**Status**: Proposal  
**Date**: April 10, 2026  
**Key Concept**: Migrate the MCP server adapter HTTP transport from raw Jetty `Handler.Abstract` to the Restlet Framework `ServerResource` pattern, unifying the exposes transport layer across all adapters.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Goals and Non-Goals](#goals-and-non-goals)
3. [Current Architecture](#current-architecture)
4. [Proposed Architecture](#proposed-architecture)
5. [Migration Surface Analysis](#migration-surface-analysis)
6. [Implementation Design](#implementation-design)
7. [Dependency Changes](#dependency-changes)
8. [Testing Strategy](#testing-strategy)
9. [Implementation Roadmap](#implementation-roadmap)
10. [Risks and Mitigations](#risks-and-mitigations)
11. [Acceptance Criteria](#acceptance-criteria)

---

## Executive Summary

### What This Proposes

Replace the raw Jetty `Handler.Abstract` / `Server` / `ServerConnector` usage in the MCP HTTP transport with the Restlet Framework `ServerResource` / `Router` / `Server` pattern already used by the Skill and REST server adapters.

### Why

1. **Consistency** — the Skill adapter (`SkillServerAdapter`) and REST adapter already use `org.restlet.Server` + `Router` + `ServerResource`. The MCP adapter is the only exposes adapter that bypasses Restlet and talks directly to Jetty. Unifying the transport layer simplifies the codebase and makes all adapters follow the same pattern.
2. **Reduced dependency surface** — Jetty `jetty-server` and `jetty-http2-common` are direct dependencies used only by the MCP adapter. Restlet manages its own embedded HTTP connector internally, removing the need to manage Jetty lifecycle, connectors, and handlers directly.
3. **Simpler code** — the current `JettyStreamableHandler` manually handles request methods, reads raw byte buffers, manages content types, and writes HTTP responses via Jetty's `Callback` API. A Restlet `ServerResource` handles all of this declaratively with `@Post`, `@Delete`, `@Get` annotations and the `Representation` abstraction.

### What This Does NOT Do

- **No protocol changes** — the MCP JSON-RPC protocol, session management, and `ProtocolDispatcher` logic are unchanged. Only the HTTP I/O layer is replaced.
- **No MCP SDK changes** — the `io.modelcontextprotocol.sdk:mcp-core` dependency is unaffected; it provides `McpSchema` types, not transport.
- **No stdio transport changes** — the `StdioJsonRpcHandler` path is transport-independent and not touched.
- **No gRPC adapter impact** — the [gRPC adapter proposal](grpc-server-adapter.md) uses Jetty HTTP/2 h2c directly for binary framing; this migration does not apply to it. If both proposals are implemented, Jetty dependencies would remain for the gRPC adapter only.

---

## Goals and Non-Goals

### Goals

1. Replace `JettyStreamableHandler` with a Restlet `ServerResource` subclass (`McpServerResource`).
2. Replace `org.eclipse.jetty.server.Server` / `ServerConnector` setup in `McpServerAdapter` with `org.restlet.Server` + `Router`.
3. Remove direct Jetty dependency from the MCP adapter code.
4. Preserve 100% behavioral compatibility — identical HTTP behavior, status codes, headers, and JSON-RPC responses.
5. All existing MCP integration tests pass without modification to test assertions.

### Non-Goals

1. Changing the MCP JSON-RPC protocol or `ProtocolDispatcher` logic.
2. Adding new HTTP features (streaming, SSE, WebSocket).
3. Migrating the gRPC adapter proposal away from Jetty.
4. Changing the stdio transport.

---

## Current Architecture

### Jetty Usage (2 files only)

| File | Jetty APIs Used | Role |
|---|---|---|
| `McpServerAdapter` | `org.eclipse.jetty.server.Server`, `ServerConnector` | Creates the Jetty server, configures host/port/idle timeout, sets the handler |
| `JettyStreamableHandler` | `Handler.Abstract`, `Request`, `Response`, `Callback`, `Content.Source`, `HttpHeader`, `ByteBuffer` | Implements MCP Streamable HTTP: dispatches POST to `ProtocolDispatcher`, handles DELETE for session termination, rejects GET with 405 |

### Transport-Agnostic Layer

`ProtocolDispatcher` is fully transport-agnostic:
- Input: `JsonNode` (parsed JSON-RPC request)
- Output: `ObjectNode` (JSON-RPC response envelope)
- Used by both `JettyStreamableHandler` (HTTP) and `StdioJsonRpcHandler` (stdio)

### Execution Flow (HTTP transport)

```
Jetty Server (port N)
  └── JettyStreamableHandler extends Handler.Abstract
        ├── POST → read body → parse JSON → ProtocolDispatcher.dispatch() → write JSON response
        ├── DELETE → remove session → 200
        └── GET → 405
```

---

## Proposed Architecture

### Restlet-Based Transport

Replace the Jetty handler with a Restlet `ServerResource` following the pattern already established by `SkillServerResource`:

```
Restlet Server (port N)
  └── Router
        └── "/" → McpServerResource extends ServerResource
              ├── @Post → read entity → parse JSON → ProtocolDispatcher.dispatch() → return JSON
              ├── @Delete → remove session → 200
              └── @Get → 405
```

### Component Mapping

| Current (Jetty) | Proposed (Restlet) |
|---|---|
| `org.eclipse.jetty.server.Server` | `org.restlet.Server` |
| `ServerConnector` (host, port, idle timeout) | `org.restlet.Server(Protocol.HTTP, address, port)` |
| `JettyStreamableHandler extends Handler.Abstract` | `McpServerResource extends ServerResource` |
| `Content.Source.asString(request)` | `getRequestEntity().getText()` |
| `response.getHeaders().put(HttpHeader.CONTENT_TYPE, ...)` | `new StringRepresentation(json, MediaType.APPLICATION_JSON)` |
| `response.setStatus(status)` | `setStatus(Status.valueOf(status))` |
| `response.getHeaders().put("Mcp-Session-Id", id)` | `getResponse().getHeaders().set("Mcp-Session-Id", id)` |
| `response.write(true, ByteBuffer.wrap(bytes), callback)` | `return representation` (Restlet handles write) |

---

## Migration Surface Analysis

### Files Modified

| File | Change |
|---|---|
| `McpServerAdapter.java` | Replace `org.eclipse.jetty.server.Server` + `ServerConnector` with `org.restlet.Server` + `Router`. Remove Jetty imports. |
| `pom.xml` | Remove `jetty-server` and `jetty-http2-common` dependencies (if gRPC proposal is not being implemented concurrently). |

### Files Removed

| File | Reason |
|---|---|
| `JettyStreamableHandler.java` | Replaced entirely by `McpServerResource`. |

### Files Created

| File | Purpose |
|---|---|
| `McpServerResource.java` | Restlet `ServerResource` implementing the MCP Streamable HTTP endpoint (POST/DELETE/GET). |

### Files Unchanged

| File | Reason |
|---|---|
| `ProtocolDispatcher.java` | Transport-agnostic — no Jetty dependency. |
| `ToolHandler.java` | Transport-agnostic. |
| `ResourceHandler.java` | Transport-agnostic. |
| `PromptHandler.java` | Transport-agnostic. |
| `StdioJsonRpcHandler.java` | stdio transport — unrelated. |

---

## Implementation Design

### McpServerResource

```java
package io.naftiko.engine.exposes.mcp;

import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Delete;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.ServerResource;

public class McpServerResource extends ServerResource {

    private static final String HEADER_MCP_SESSION_ID = "Mcp-Session-Id";

    @Post("json")
    public Representation handlePost(Representation entity) {
        ProtocolDispatcher dispatcher = getDispatcher();
        // Read body, parse JSON-RPC, delegate to dispatcher
        // Handle initialize (create session, set Mcp-Session-Id header)
        // Handle notifications/initialized (return 202, empty)
        // Return JSON-RPC response as StringRepresentation
    }

    @Delete
    public void handleDelete() {
        // Read Mcp-Session-Id header, remove session, return 200
    }

    @Get
    public Representation handleGet() {
        setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
        return new StringRepresentation("GET not supported", MediaType.TEXT_PLAIN);
    }

    private ProtocolDispatcher getDispatcher() {
        return (ProtocolDispatcher) getContext().getAttributes().get("dispatcher");
    }
}
```

### McpServerAdapter Changes

```java
// Before (Jetty)
private Server jettyServer;

private void initHttpTransport(McpServerSpec serverSpec) {
    this.jettyServer = new Server();
    ServerConnector connector = new ServerConnector(jettyServer);
    connector.setHost(address);
    connector.setPort(serverSpec.getPort());
    connector.setIdleTimeout(120000);
    jettyServer.addConnector(connector);
    jettyServer.setHandler(new JettyStreamableHandler(this));
}

// After (Restlet)
private org.restlet.Server restletServer;

private void initHttpTransport(McpServerSpec serverSpec) {
    ProtocolDispatcher dispatcher = new ProtocolDispatcher(this);
    Map<String, Boolean> activeSessions = new ConcurrentHashMap<>();

    Context context = new Context();
    context.getAttributes().put("dispatcher", dispatcher);
    context.getAttributes().put("activeSessions", activeSessions);

    Router router = new Router(context);
    router.attachDefault(McpServerResource.class);

    String address = serverSpec.getAddress() != null ? serverSpec.getAddress() : "localhost";
    this.restletServer = new org.restlet.Server(Protocol.HTTP, address, serverSpec.getPort());
    this.restletServer.setNext(router);
}
```

### Session Management

The `activeSessions` map currently lives in `JettyStreamableHandler`. It moves to `McpServerAdapter` and is shared via the Restlet `Context` attributes — the same pattern used by `SkillServerAdapter` for `skillServerSpec`.

---

## Dependency Changes

### pom.xml

**Remove** (only if gRPC adapter is not being implemented concurrently):
```xml
<dependency>
    <groupId>org.eclipse.jetty.http2</groupId>
    <artifactId>jetty-http2-common</artifactId>
    <version>${jetty.version}</version>
</dependency>
<dependency>
    <groupId>org.eclipse.jetty</groupId>
    <artifactId>jetty-server</artifactId>
    <version>${jetty.version}</version>
</dependency>
```

**Keep** (already present, no changes):
```xml
<dependency>
    <groupId>org.restlet</groupId>
    <artifactId>org.restlet</artifactId>
    <version>${restlet.version}</version>
</dependency>
<dependency>
    <groupId>org.restlet</groupId>
    <artifactId>org.restlet.ext.jackson</artifactId>
    <version>${restlet.version}</version>
</dependency>
```

The `jetty.version` property can also be removed from the properties block if no other dependency references it.

---

## Testing Strategy

### Existing Tests (must remain green)

All existing MCP integration tests exercise the HTTP transport through actual HTTP calls. They must pass without assertion changes:

| Test Class | Coverage |
|---|---|
| `McpIntegrationTest` | Core MCP protocol: initialize, tools/list, tools/call |
| `McpToolHintsIntegrationTest` | Tool annotations and hints |
| `AggregateIntegrationTest` | Aggregate ref resolution through MCP |
| `ResourcesPromptsIntegrationTest` | Resources and prompts protocol |
| `NestedObjectOutputMappingIntegrationTest` | Complex output mapping |
| `ProtocolDispatcherNegativeTest` | Error handling paths |
| `ProtocolDispatcherCoverageTest` | Protocol edge cases |

### New/Modified Tests

| Test | Purpose |
|---|---|
| `McpServerResourceTest` (replaces `JettyStreamableHandlerTest`) | Unit-test the new Restlet resource: POST dispatch, DELETE session removal, GET rejection, empty body handling, malformed JSON handling |
| Integration tests | No changes expected — they send HTTP requests and assert JSON-RPC responses, which are transport-independent |

### Test Execution

```bash
mvn clean test --no-transfer-progress
```

All 50+ existing test classes must pass. The `JettyStreamableHandlerTest` is replaced by `McpServerResourceTest` with equivalent coverage.

---

## Implementation Roadmap

### Phase 1 — Create McpServerResource (additive)

1. Create `McpServerResource.java` implementing POST/DELETE/GET with `ProtocolDispatcher` delegation.
2. Unit-test it with `McpServerResourceTest`.
3. No existing code is modified yet.

### Phase 2 — Wire McpServerAdapter to Restlet

1. Modify `McpServerAdapter.initHttpTransport()` to use `org.restlet.Server` + `Router` + `McpServerResource`.
2. Update `start()` and `stop()` to use `restletServer.start()` / `restletServer.stop()`.
3. Remove `jettyServer` field and Jetty imports.
4. Run full integration test suite.

### Phase 3 — Remove Jetty artifacts

1. Delete `JettyStreamableHandler.java`.
2. Delete `JettyStreamableHandlerTest.java`.
3. Remove `jetty-server` and `jetty-http2-common` from `pom.xml` (guarded by gRPC adapter status).
4. Run full test suite.

---

## Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Restlet internal connector has different timeout behavior than Jetty `ServerConnector.setIdleTimeout()` | Long-running tool calls could be interrupted | Configure Restlet server connector parameters (`socketTimeout`, `persistingConnections`) to match current 120s idle timeout |
| Custom header (`Mcp-Session-Id`) handling differs between Restlet and Jetty | Session management breaks | Restlet supports custom headers via `getResponse().getHeaders().set()` — verified in Restlet 2.7 API |
| gRPC adapter proposal depends on Jetty | Cannot remove Jetty dependencies | Guard dependency removal on gRPC adapter status — if gRPC is in progress, keep Jetty deps and only remove MCP code coupling |
| Restlet 2.7.0-m2 is a milestone release | Potential stability issues | Already used in production for Skill and REST adapters — risk is already accepted |
| Integration tests assume specific HTTP behavior (status codes, headers) | Tests break after migration | `ProtocolDispatcher` is unchanged — identical JSON-RPC responses. HTTP status codes and headers are explicitly set in `McpServerResource` to match current behavior |

---

## Acceptance Criteria

1. `McpServerAdapter` HTTP transport uses `org.restlet.Server` — no Jetty imports remain in MCP adapter classes.
2. All existing MCP integration tests pass without assertion changes.
3. MCP JSON-RPC protocol behavior is identical: same status codes, same headers, same response bodies.
4. `Mcp-Session-Id` header is set on initialize response and read on DELETE request.
5. `GET /` returns 405 with `"GET not supported"` body.
6. Empty body POST returns JSON-RPC parse error (`-32700`).
7. `mvn clean test` is green across all modules.
8. No Jetty imports exist in `io.naftiko.engine.exposes.mcp` package (unless gRPC adapter is colocated).
