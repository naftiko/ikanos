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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Unit tests for {@link McpPromptArgumentSpec}.
 */
public class McpPromptArgumentSpecTest {

    @Test
    public void newSpecShouldHaveNullDefaults() {
        McpPromptArgumentSpec spec = new McpPromptArgumentSpec();

        assertNull(spec.getName());
        assertNull(spec.getLabel());
        assertNull(spec.getDescription());
        assertNull(spec.getRequired());
    }

    @Test
    public void isRequiredShouldDefaultToTrueWhenRequiredFieldIsNull() {
        McpPromptArgumentSpec spec = new McpPromptArgumentSpec();
        assertTrue(spec.isRequired());
    }

    @Test
    public void isRequiredShouldReturnFalseOnlyWhenExplicitlyDisabled() {
        McpPromptArgumentSpec spec = new McpPromptArgumentSpec();
        spec.setRequired(Boolean.FALSE);
        assertFalse(spec.isRequired());
    }

    @Test
    public void isRequiredShouldReturnTrueWhenExplicitlyEnabled() {
        McpPromptArgumentSpec spec = new McpPromptArgumentSpec();
        spec.setRequired(Boolean.TRUE);
        assertTrue(spec.isRequired());
    }

    @Test
    public void settersShouldRoundTripValues() {
        McpPromptArgumentSpec spec = new McpPromptArgumentSpec();
        spec.setName("user");
        spec.setLabel("User name");
        spec.setDescription("The end-user name to greet");
        spec.setRequired(Boolean.TRUE);

        assertEquals("user", spec.getName());
        assertEquals("User name", spec.getLabel());
        assertEquals("The end-user name to greet", spec.getDescription());
        assertEquals(Boolean.TRUE, spec.getRequired());
    }

    @Test
    public void shouldDeserializeFromYaml() throws Exception {
        String yaml = """
                name: user
                label: User name
                description: The user name
                required: false
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        McpPromptArgumentSpec spec = mapper.readValue(yaml, McpPromptArgumentSpec.class);

        assertEquals("user", spec.getName());
        assertEquals("User name", spec.getLabel());
        assertEquals("The user name", spec.getDescription());
        assertFalse(spec.isRequired());
    }
}
