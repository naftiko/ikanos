/**
 * Copyright 2025-2026 Naftiko
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
/**
 * The draft MCP files extension ({@code io.modelcontextprotocol/files}), experimental.
 *
 * <p>{@link io.ikanos.engine.exposes.mcp.files.McpFiles} holds the extension identifier and the
 * {@code _meta} placement, which the working group may still move, in one place.
 * {@link io.ikanos.engine.exposes.mcp.files.McpFileDownloads} turns large binary tool results
 * and presigned URLs into download targets, and serves the hosted ones from a
 * {@link io.ikanos.engine.exposes.mcp.files.DownloadStore}.
 * {@link io.ikanos.engine.exposes.mcp.files.McpFileUploads} answers files sent out of band with
 * {@code upload_required}: through presigned URLs when a flow declares {@code upload}, or through
 * targets on the adapter itself ({@link io.ikanos.engine.exposes.mcp.files.HostedUploads}, kept in
 * an {@link io.ikanos.engine.exposes.mcp.files.UploadStore}).</p>
 */
package io.ikanos.engine.exposes.mcp.files;
