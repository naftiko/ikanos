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
package io.ikanos.spec.consumes.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.http.AuthenticationSpec;

/**
 * Consumed MCP server specification ({@code consumes: type: mcp}).
 *
 * <p>Targets one upstream MCP server over stateless Streamable HTTP ({@code 2026-07-28}). Declares
 * the curated subset of upstream tools a capability may call, keyed by the upstream tool name, so a
 * tool keeps its name on both sides of the seam.</p>
 *
 * <h2>Thread safety</h2>
 * Scalar fields are held in {@link AtomicReference}s and the tools map is synchronized, matching
 * {@code HttpClientSpec} (SonarQube {@code java:S3077}).
 */
@JsonDeserialize(using = JsonDeserializer.None.class)
public class McpClientSpec extends ClientSpec {

    /** Value of {@code type} for this spec. */
    public static final String TYPE = "mcp";

    /** {@code discovery: verify} — list upstream tools at start and check declared ones exist. */
    public static final String DISCOVERY_VERIFY = "verify";

    /** {@code discovery: off} — no start-up call. */
    public static final String DISCOVERY_OFF = "off";

    private final AtomicReference<String> description = new AtomicReference<>();
    private final AtomicReference<String> endpoint = new AtomicReference<>();
    private final AtomicReference<AuthenticationSpec> authentication = new AtomicReference<>();
    private final AtomicReference<String> discovery = new AtomicReference<>(DISCOVERY_VERIFY);

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonDeserialize(using = McpClientToolMapDeserializer.class)
    private final Map<String, McpClientToolSpec> tools =
            Collections.synchronizedMap(new LinkedHashMap<>());

    public McpClientSpec() {
        super(TYPE, null);
    }

    public McpClientSpec(String namespace, String endpoint) {
        super(TYPE, namespace);
        this.endpoint.set(endpoint);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getDescription() {
        return description.get();
    }

    public void setDescription(String description) {
        this.description.set(description);
    }

    /** @return the Streamable HTTP endpoint URL */
    public String getEndpoint() {
        return endpoint.get();
    }

    public void setEndpoint(String endpoint) {
        this.endpoint.set(endpoint);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public AuthenticationSpec getAuthentication() {
        return authentication.get();
    }

    public void setAuthentication(AuthenticationSpec authentication) {
        this.authentication.set(authentication);
    }

    /** @return {@code verify} (default) or {@code off} */
    public String getDiscovery() {
        return discovery.get();
    }

    public void setDiscovery(String discovery) {
        this.discovery.set(normalize(discovery, DISCOVERY_VERIFY));
    }

    /**
     * YAML 1.1 reads a bare {@code off} as boolean {@code false} (and {@code on} as {@code true});
     * Jackson then hands the setter {@code "false"}. Map those back to the documented values.
     */
    static String normalize(String value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if ("false".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value)) {
            return DISCOVERY_OFF;
        }
        return value;
    }

    /** @return {@code true} when the adapter must verify declared tools against the upstream */
    public boolean isDiscoveryVerify() {
        return !DISCOVERY_OFF.equalsIgnoreCase(getDiscovery());
    }

    /** @return declared tools keyed by upstream tool name */
    public Map<String, McpClientToolSpec> getTools() {
        return tools;
    }

    public void setTools(Map<String, McpClientToolSpec> values) {
        tools.clear();
        if (values != null) {
            values.forEach((name, tool) -> {
                tool.setName(name);
                tools.put(name, tool);
            });
        }
    }
}
