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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Unit tests for {@link McpServerPromptSpec}.
 */
public class McpServerPromptSpecTest {

    @Test
    public void newSpecShouldInitializeEmptyArgumentAndTemplateLists() {
        McpServerPromptSpec spec = new McpServerPromptSpec();

        assertNull(spec.getName());
        assertNull(spec.getLabel());
        assertNull(spec.getDescription());
        assertNotNull(spec.getArguments());
        assertTrue(spec.getArguments().isEmpty());
        assertNotNull(spec.getTemplate());
        assertTrue(spec.getTemplate().isEmpty());
        assertNull(spec.getLocation());
        assertFalse(spec.isFileBased());
    }

    @Test
    public void settersShouldRoundTripValues() {
        McpServerPromptSpec spec = new McpServerPromptSpec();
        spec.setName("greet");
        spec.setLabel("Greet User");
        spec.setDescription("Greets the user by name");

        assertEquals("greet", spec.getName());
        assertEquals("Greet User", spec.getLabel());
        assertEquals("Greets the user by name", spec.getDescription());
    }

    @Test
    public void isFileBasedShouldBeTrueWhenLocationIsSet() {
        McpServerPromptSpec spec = new McpServerPromptSpec();
        spec.setLocation("file:///prompts/greet.md");

        assertEquals("file:///prompts/greet.md", spec.getLocation());
        assertTrue(spec.isFileBased());
    }

    @Test
    public void isFileBasedShouldBeFalseWhenLocationIsNull() {
        McpServerPromptSpec spec = new McpServerPromptSpec();
        spec.setLocation(null);
        assertFalse(spec.isFileBased());
    }

    @Test
    public void shouldDeserializeInlineTemplateFromYaml() throws Exception {
        String yaml = """
                name: greet
                description: Greets the user
                arguments:
                  - name: user
                    description: User name
                template:
                  - role: user
                    content: "Hello {{user}}"
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        McpServerPromptSpec spec = mapper.readValue(yaml, McpServerPromptSpec.class);

        assertEquals("greet", spec.getName());
        assertEquals("Greets the user", spec.getDescription());
        assertEquals(1, spec.getArguments().size());
        assertEquals("user", spec.getArguments().get(0).getName());
        assertEquals(1, spec.getTemplate().size());
        assertEquals("user", spec.getTemplate().get(0).getRole());
        assertEquals("Hello {{user}}", spec.getTemplate().get(0).getContent());
        assertFalse(spec.isFileBased());
    }

    @Test
    public void shouldDeserializeFileBasedPromptFromYaml() throws Exception {
        String yaml = """
                name: greet
                description: File-based prompt
                location: file:///prompts/greet.md
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        McpServerPromptSpec spec = mapper.readValue(yaml, McpServerPromptSpec.class);

        assertEquals("greet", spec.getName());
        assertEquals("file:///prompts/greet.md", spec.getLocation());
        assertTrue(spec.isFileBased());
    }
}
