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
package io.ikanos.spec.exposes.mcp;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Content Security Policy domains requested by an MCP Apps view. Emitted as
 * {@code _meta.ui.csp}. The host builds the iframe CSP from these lists; it MAY restrict them
 * further but MUST NOT allow undeclared domains. Ikanos never enforces CSP itself.
 *
 * <h2>Thread safety</h2>
 * Each list is stored as an immutable snapshot inside an {@link AtomicReference}. A
 * {@code null} value means "not authored" and is not emitted.
 */
public class McpResourceUiCspSpec {

    private final AtomicReference<List<String>> connectDomains = new AtomicReference<>();
    private final AtomicReference<List<String>> resourceDomains = new AtomicReference<>();
    private final AtomicReference<List<String>> frameDomains = new AtomicReference<>();
    private final AtomicReference<List<String>> baseUriDomains = new AtomicReference<>();

    public McpResourceUiCspSpec() {}

    /** Origins for fetch/XHR/WebSocket. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<String> getConnectDomains() { return connectDomains.get(); }
    public void setConnectDomains(List<String> value) { connectDomains.set(copy(value)); }

    /** Origins for scripts, styles, images, fonts and media. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<String> getResourceDomains() { return resourceDomains.get(); }
    public void setResourceDomains(List<String> value) { resourceDomains.set(copy(value)); }

    /** Origins for nested iframes. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<String> getFrameDomains() { return frameDomains.get(); }
    public void setFrameDomains(List<String> value) { frameDomains.set(copy(value)); }

    /** Allowed {@code base-uri} origins. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<String> getBaseUriDomains() { return baseUriDomains.get(); }
    public void setBaseUriDomains(List<String> value) { baseUriDomains.set(copy(value)); }

    private static List<String> copy(List<String> value) {
        return value != null ? List.copyOf(value) : null;
    }
}
