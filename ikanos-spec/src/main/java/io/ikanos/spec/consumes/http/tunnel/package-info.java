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
 * Tunnel transport specifications for consumed HTTP adapters.
 *
 * <p>This package contains the spec POJOs for the optional {@code tunnel:} block
 * on {@code ConsumesHttp}. {@link io.ikanos.spec.consumes.http.tunnel.TunnelConfigSpec}
 * is the polymorphic base; concrete subtypes (today only
 * {@link io.ikanos.spec.consumes.http.tunnel.ZitiTunnelConfigSpec}) carry transport-specific
 * configuration such as the Ziti service name, identity reference, and fallback policy.
 *
 * <p>Schema reference: see {@code TunnelConfig} and {@code TunnelZiti} in
 * {@code ikanos-schema.json}. The runtime engine integration that consumes
 * these specs is delivered in a later phase of the Reverse Tunnel blueprint.
 */
package io.ikanos.spec.consumes.http.tunnel;
