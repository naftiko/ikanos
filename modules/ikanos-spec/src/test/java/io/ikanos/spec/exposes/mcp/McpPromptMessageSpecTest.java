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
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Unit tests for {@link McpPromptMessageSpec}.
 */
public class McpPromptMessageSpecTest {

    @Test
    public void noArgConstructorShouldLeaveFieldsNull() {
        McpPromptMessageSpec spec = new McpPromptMessageSpec();
        assertNull(spec.getRole());
        assertNull(spec.getContent());
    }

    @Test
    public void allArgsConstructorShouldAssignBothFields() {
        McpPromptMessageSpec spec = new McpPromptMessageSpec("user", "Hello {{name}}");

        assertEquals("user", spec.getRole());
        assertEquals("Hello {{name}}", spec.getContent());
    }

    @Test
    public void settersShouldRoundTripValues() {
        McpPromptMessageSpec spec = new McpPromptMessageSpec();
        spec.setRole("assistant");
        spec.setContent("Hi there");

        assertEquals("assistant", spec.getRole());
        assertEquals("Hi there", spec.getContent());
    }

    @Test
    public void shouldDeserializeFromYaml() throws Exception {
        String yaml = """
                role: user
                content: "Hello {{name}}"
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        McpPromptMessageSpec spec = mapper.readValue(yaml, McpPromptMessageSpec.class);

        assertEquals("user", spec.getRole());
        assertEquals("Hello {{name}}", spec.getContent());
    }
}
