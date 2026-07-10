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
package io.ikanos.spec.exposes.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Unit tests for {@link RestServerForwardSpec}.
 */
public class RestServerForwardSpecTest {

    @Test
    public void noArgConstructorShouldLeaveTargetNullAndHeadersEmpty() {
        RestServerForwardSpec spec = new RestServerForwardSpec();

        assertNull(spec.getTargetNamespace());
        assertNotNull(spec.getTrustedHeaders());
        assertTrue(spec.getTrustedHeaders().isEmpty());
    }

    @Test
    public void targetNamespaceConstructorShouldAssignTarget() {
        RestServerForwardSpec spec = new RestServerForwardSpec("upstream-api");
        assertEquals("upstream-api", spec.getTargetNamespace());
    }

    @Test
    public void setterShouldRoundTripTargetNamespace() {
        RestServerForwardSpec spec = new RestServerForwardSpec();
        spec.setTargetNamespace("downstream");
        assertEquals("downstream", spec.getTargetNamespace());
    }

    @Test
    public void trustedHeadersListShouldBeMutable() {
        RestServerForwardSpec spec = new RestServerForwardSpec();
        spec.getTrustedHeaders().add("Authorization");
        spec.getTrustedHeaders().add("X-Trace-Id");

        assertEquals(2, spec.getTrustedHeaders().size());
        assertEquals("Authorization", spec.getTrustedHeaders().get(0));
        assertEquals("X-Trace-Id", spec.getTrustedHeaders().get(1));
    }

    @Test
    public void shouldDeserializeFromYaml() throws Exception {
        String yaml = """
                targetNamespace: upstream-api
                trustedHeaders:
                  - Authorization
                  - X-Trace-Id
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        RestServerForwardSpec spec = mapper.readValue(yaml, RestServerForwardSpec.class);

        assertEquals("upstream-api", spec.getTargetNamespace());
        assertEquals(2, spec.getTrustedHeaders().size());
    }
}
