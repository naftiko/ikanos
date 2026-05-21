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
package io.ikanos.spec.consumes.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.http.tunnel.TunnelConfigSpec;
import io.ikanos.spec.consumes.http.tunnel.ZitiTunnelConfigSpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the optional {@code tunnel} field on {@link HttpClientSpec}.
 *
 * <p>See blueprint {@code reverse-tunnel-private-network.md} Phase 1.</p>
 */
class HttpClientSpecTunnelTest {

    private ObjectMapper yamlMapper;
    private ObjectMapper jsonMapper;

    @BeforeEach
    void setUp() {
        yamlMapper = new ObjectMapper(new YAMLFactory());
        yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        jsonMapper = new ObjectMapper();
    }

    @Test
    void tunnelShouldDefaultToNullWhenNotSet() {
        HttpClientSpec spec = new HttpClientSpec();
        assertNull(spec.getTunnel());
    }

    @Test
    void setTunnelShouldPersistValueWhenInvoked() {
        HttpClientSpec spec = new HttpClientSpec();
        ZitiTunnelConfigSpec tunnel = new ZitiTunnelConfigSpec(
            "crm-api", "{{secrets.ZITI_IDENTITY}}");

        spec.setTunnel(tunnel);

        assertNotNull(spec.getTunnel());
        assertInstanceOf(ZitiTunnelConfigSpec.class, spec.getTunnel());
        assertEquals("crm-api", ((ZitiTunnelConfigSpec) spec.getTunnel()).getService());
    }

    @Test
    void serializeShouldOmitTunnelFieldWhenTunnelIsNull() throws Exception {
        HttpClientSpec spec = new HttpClientSpec("crm", "https://crm.internal", null);

        String json = jsonMapper.writeValueAsString(spec);

        assertFalse(json.contains("\"tunnel\""),
            "expected tunnel field to be omitted when null; got " + json);
    }

    @Test
    void deserializeShouldPopulateTunnelWhenYamlContainsZitiBlock() throws Exception {
        String yaml = """
            type: "http"
            namespace: "crm"
            baseUri: "https://crm.internal"
            tunnel:
              type: "ziti"
              service: "crm-api"
              identity: "{{secrets.ZITI_IDENTITY}}"
              fallback: "fail"
            resources: []
            """;

        ClientSpec result = yamlMapper.readValue(yaml, ClientSpec.class);

        assertInstanceOf(HttpClientSpec.class, result);
        HttpClientSpec http = (HttpClientSpec) result;
        TunnelConfigSpec tunnel = http.getTunnel();
        assertInstanceOf(ZitiTunnelConfigSpec.class, tunnel);
        ZitiTunnelConfigSpec ziti = (ZitiTunnelConfigSpec) tunnel;
        assertEquals("crm-api", ziti.getService());
        assertEquals("{{secrets.ZITI_IDENTITY}}", ziti.getIdentity());
        assertEquals(ZitiTunnelConfigSpec.FALLBACK_FAIL, ziti.getFallback());
    }

    @Test
    void deserializeShouldLeaveTunnelNullWhenYamlOmitsTunnelBlock() throws Exception {
        String yaml = """
            type: "http"
            namespace: "crm"
            baseUri: "https://crm.internal"
            resources: []
            """;

        ClientSpec result = yamlMapper.readValue(yaml, ClientSpec.class);

        assertInstanceOf(HttpClientSpec.class, result);
        assertNull(((HttpClientSpec) result).getTunnel());
    }

    @Test
    void serializeShouldEmitTunnelBlockWhenTunnelIsSet() throws Exception {
        HttpClientSpec spec = new HttpClientSpec("crm", "https://crm.internal", null);
        spec.setTunnel(new ZitiTunnelConfigSpec("crm-api", "{{secrets.ZITI_IDENTITY}}"));

        String json = jsonMapper.writeValueAsString(spec);

        assertTrue(json.contains("\"tunnel\""), "expected tunnel field; got " + json);
        assertTrue(json.contains("\"type\":\"ziti\""), "expected ziti type tag; got " + json);
        assertTrue(json.contains("\"service\":\"crm-api\""),
            "expected service field; got " + json);
    }

}
