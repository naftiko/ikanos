/**
 * Handlers for the MCP JSON-RPC methods exposed by the {@code type: mcp} server adapter.
 *
 * <p>Each JSON-RPC method supported by the MCP protocol ({@code tools/list}, {@code tools/call},
 * {@code resources/list}, {@code resources/read}, {@code resources/templates/list},
 * {@code prompts/list}, {@code prompts/get}, and the server-discovery method) is implemented by
 * a dedicated {@link io.ikanos.engine.exposes.mcp.handler.McpCallHandler} subclass. Handlers are
 * looked up and dispatched by
 * {@link io.ikanos.engine.exposes.mcp.ProtocolDispatcher} based on the incoming request's
 * {@code method} field, and are assembled once per adapter by
 * {@link io.ikanos.engine.exposes.mcp.handler.McpCallHandlersFactory}.</p>
 *
 * <p>{@link io.ikanos.engine.exposes.mcp.handler.McpCallHandler} is the common base class: it
 * declares the required MCP headers for a given method (e.g. protocol version, method name,
 * tool/resource/prompt name) and holds the ordered lists of
 * {@link io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor}s and
 * {@link io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor}s that run respectively
 * before and after {@link io.ikanos.engine.exposes.mcp.handler.McpCallHandler#handle(com.fasterxml.jackson.databind.JsonNode)}
 * — for example protocol-version validation, response caching directives, and server metadata
 * enrichment.</p>
 *
 * <p>Handlers are stateless beyond their constructor-injected dependencies and are safe to reuse
 * across concurrent requests.</p>
 */
package io.ikanos.engine.exposes.mcp.handler;
