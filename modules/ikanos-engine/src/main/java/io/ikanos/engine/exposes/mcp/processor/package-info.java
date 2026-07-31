/**
 * Pre- and post-processors that run around an MCP call handler's execution, as wired by
 * {@link io.ikanos.engine.exposes.mcp.handler.McpCallHandlersFactory}.
 *
 * <p>Two small functional-style contracts define the extension points:</p>
 * <ul>
 *   <li>{@link io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor} — runs on the
 *   request body before the handler, and may abort dispatch by throwing a
 *   {@link io.ikanos.exception.ProcessorException} (e.g. an unsupported protocol version).</li>
 *   <li>{@link io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor} — runs on the
 *   response body after the handler, typically to enrich it before it is sent back to the
 *   client.</li>
 * </ul>
 *
 * <p>Built-in implementations:</p>
 * <ul>
 *   <li>{@link io.ikanos.engine.exposes.mcp.processor.ProtocolsVersionsValidator} — a
 *   pre-processor rejecting requests whose MCP protocol version or {@code jsonrpc} version
 *   don't match what the server supports.</li>
 *   <li>{@link io.ikanos.engine.exposes.mcp.processor.CacheDataAppender} — a post-processor
 *   adding {@code ttlMs} / {@code cacheScope} caching hints to the response.</li>
 *   <li>{@link io.ikanos.engine.exposes.mcp.processor.ServerDataAppender} — a post-processor
 *   stamping the {@code jsonrpc} version and server identity metadata
 *   ({@code io.modelcontextprotocol/serverInfo}) onto the response.</li>
 * </ul>
 */
package io.ikanos.engine.exposes.mcp.processor;
