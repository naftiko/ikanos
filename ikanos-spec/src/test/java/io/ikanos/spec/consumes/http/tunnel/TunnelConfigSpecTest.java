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
package io.ikanos.spec.consumes.http.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TunnelConfigSpec} polymorphic deserialization and
 * {@link ZitiTunnelConfigSpec} round-trip serialization.
 *
 * <p>See blueprint {@code reverse-tunnel-private-network.md} Phase 1.</p>
 */
class TunnelConfigSpecTest {

    private ObjectMapper yamlMapper;
    private ObjectMapper jsonMapper;

    @BeforeEach
    void setUp() {
        yamlMapper = new ObjectMapper(new YAMLFactory());
        jsonMapper = new ObjectMapper();
    }

    @Test
    void deserializeShouldYieldZitiSubtypeWhenTypeIsZiti() throws Exception {
        String yaml = """
            type: "ziti"
            service: "crm-api"
            identity: "{{secrets.ZITI_IDENTITY}}"
            """;

        TunnelConfigSpec result = yamlMapper.readValue(yaml, TunnelConfigSpec.class);

        assertInstanceOf(ZitiTunnelConfigSpec.class, result);
        ZitiTunnelConfigSpec ziti = (ZitiTunnelConfigSpec) result;
        assertEquals("ziti", ziti.getType());
        assertEquals("crm-api", ziti.getService());
        assertEquals("{{secrets.ZITI_IDENTITY}}", ziti.getIdentity());
        assertNull(ziti.getFallback(), "fallback should be null when omitted");
    }

    @Test
    void deserializeShouldPreserveFallbackWhenSet() throws Exception {
        String yaml = """
            type: "ziti"
            service: "crm-api"
            identity: "{{secrets.ZITI_IDENTITY}}"
            fallback: "direct"
            """;

        TunnelConfigSpec result = yamlMapper.readValue(yaml, TunnelConfigSpec.class);

        assertInstanceOf(ZitiTunnelConfigSpec.class, result);
        assertEquals(ZitiTunnelConfigSpec.FALLBACK_DIRECT,
            ((ZitiTunnelConfigSpec) result).getFallback());
    }

    @Test
    void roundTripShouldPreserveAllFieldsWhenFallbackIsSet() throws Exception {
        ZitiTunnelConfigSpec original = new ZitiTunnelConfigSpec(
            "crm-api", "{{secrets.ZITI_IDENTITY}}", ZitiTunnelConfigSpec.FALLBACK_FAIL);

        String json = jsonMapper.writeValueAsString(original);
        TunnelConfigSpec parsed = jsonMapper.readValue(json, TunnelConfigSpec.class);

        assertInstanceOf(ZitiTunnelConfigSpec.class, parsed);
        ZitiTunnelConfigSpec ziti = (ZitiTunnelConfigSpec) parsed;
        assertEquals("ziti", ziti.getType());
        assertEquals(original.getService(), ziti.getService());
        assertEquals(original.getIdentity(), ziti.getIdentity());
        assertEquals(original.getFallback(), ziti.getFallback());
    }

    @Test
    void serializeShouldOmitFallbackWhenFallbackIsNull() throws Exception {
        ZitiTunnelConfigSpec spec = new ZitiTunnelConfigSpec("crm-api", "/etc/ziti/id.json");

        String json = jsonMapper.writeValueAsString(spec);

        assertTrue(json.contains("\"type\":\"ziti\""), "expected type field; got " + json);
        assertTrue(json.contains("\"service\":\"crm-api\""),
            "expected service field; got " + json);
        assertFalse(json.contains("\"fallback\""),
            "expected fallback to be omitted when null; got " + json);
    }

    @Test
    void typeDiscriminatorShouldBeZitiByDefault() {
        ZitiTunnelConfigSpec spec = new ZitiTunnelConfigSpec();
        assertEquals("ziti", spec.getType());
    }

    @Test
    void constantsShouldMatchSchemaEnumValues() {
        assertEquals("fail", ZitiTunnelConfigSpec.FALLBACK_FAIL);
        assertEquals("direct", ZitiTunnelConfigSpec.FALLBACK_DIRECT);
    }

}
