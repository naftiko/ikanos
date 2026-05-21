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
 * Reverse-tunnel transport SPI for consumed HTTP APIs.
 *
 * <p>This package defines {@link io.ikanos.engine.consumes.tunnel.Tunnel}, the service-loader
 * contract that lets the engine dial a private API through an overlay network (e.g. OpenZiti)
 * instead of the public internet. Implementations are discovered via
 * {@link java.util.ServiceLoader} at bootstrap and pooled by
 * {@code (tunnel.type, tunnel.identity)}.
 *
 * <p>See the {@code blueprints/reverse-tunnel-private-network.md} blueprint, §6.3, for the
 * original Architecture Decision Record and §6.4 for the revised Jetty-integration approach
 * adopted in Phase 2: a per-request {@link org.eclipse.jetty.io.Transport} attached by a
 * Jetty request listener, with byte pumping from the Ziti {@link
 * java.nio.channels.AsynchronousSocketChannel} into a Jetty
 * {@link org.eclipse.jetty.io.MemoryEndPointPipe}.
 */
package io.ikanos.engine.consumes.tunnel;
