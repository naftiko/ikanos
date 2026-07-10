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
 * OpenZiti implementation of the Ikanos {@code Tunnel} SPI.
 *
 * <p>This package contains a single {@link io.ikanos.tunnel.ziti.ZitiTunnel} class that is
 * discovered by {@link java.util.ServiceLoader} at engine bootstrap via the descriptor file
 * at {@code META-INF/services/io.ikanos.engine.consumes.tunnel.Tunnel}. The tunnel reads its
 * identity (a path to a Ziti JWT/PFX/JSON identity file) from the system property
 * {@code ikanos.tunnel.ziti.identity}, set by
 * {@link io.ikanos.engine.consumes.tunnel.TunnelBootstrap} after resolving the
 * {@code tunnel.identity} Mustache expression against the capability's bindings.
 *
 * <p>The blueprint design ({@code blueprints/reverse-tunnel-private-network.md} §6.5) calls
 * for one {@code ZitiContext} per identity; Phase 2 supports a single identity per
 * capability, which is the canonical deployment shape today and avoids the contention of
 * multi-identity boot during the first release window.
 *
 * <p>End-to-end mTLS, posture checks, and service authorization are handled by the Ziti
 * controller-side configuration; application-layer authentication declared on the
 * {@code consumes.http} block runs unchanged on top.
 */
package io.ikanos.tunnel.ziti;
