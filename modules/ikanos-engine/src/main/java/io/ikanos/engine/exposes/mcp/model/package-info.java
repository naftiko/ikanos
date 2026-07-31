/**
 * Shared model types for the MCP JSON-RPC protocol layer.
 *
 * <p>These classes are protocol-level building blocks used by both the
 * {@link io.ikanos.engine.exposes.mcp.handler} handlers and the dispatch pipeline
 * ({@link io.ikanos.engine.exposes.mcp.ProtocolDispatcher}, pre/post-processors):</p>
 *
 * <ul>
 *   <li>{@link io.ikanos.engine.exposes.mcp.model.HandlerResult} — sealed result of a handler's
 *   execution, either a {@link io.ikanos.engine.exposes.mcp.model.HandlerSuccessResult} or a
 *   {@link io.ikanos.engine.exposes.mcp.model.HandlerFailureResult}.</li>
 *   <li>{@link io.ikanos.engine.exposes.mcp.model.DispatchResult} — the outcome of dispatching a
 *   full JSON-RPC request, carrying the response body and, on error, the associated
 *   {@link io.ikanos.engine.exposes.mcp.model.JsonRpcError}.</li>
 *   <li>{@link io.ikanos.engine.exposes.mcp.model.JsonRpcError} — the JSON-RPC / MCP error codes
 *   (standard JSON-RPC 2.0 codes plus the MCP-reserved sub-range), and
 *   {@link io.ikanos.engine.exposes.mcp.model.JsonRpcCodeMapper} — maps each one to the HTTP
 *   status required by the MCP specification.</li>
 *   <li>{@link io.ikanos.engine.exposes.mcp.model.McpHeader} — the MCP-specific HTTP headers
 *   (protocol version, method, name) required by handlers, including where to read each header's
 *   value from the JSON-RPC request body and how base64-encoded header values (MIME
 *   "encoded-word" style, {@code =?base64?...?=}) are decoded.</li>
 * </ul>
 */
package io.ikanos.engine.exposes.mcp.model;
