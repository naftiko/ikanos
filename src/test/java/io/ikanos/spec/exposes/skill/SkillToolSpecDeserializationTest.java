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
package io.ikanos.spec.exposes.skill;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Deserialization tests for {@link SkillToolSpec} and {@link SkillToolFromSpec} — verifying the
 * two tool variants ({@code from} and {@code instruction}) deserialize correctly from inline YAML.
 */
public class SkillToolSpecDeserializationTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    public void testDerivedToolFromVariant() throws Exception {
        String yaml = """
                name: "list-orders"
                description: "List all orders in the system"
                from:
                  sourceNamespace: "orders-rest"
                  action: "list-orders"
                """;

        SkillToolSpec tool = YAML.readValue(yaml, SkillToolSpec.class);

        assertEquals("list-orders", tool.getName());
        assertEquals("List all orders in the system", tool.getDescription());
        assertNotNull(tool.getFrom(), "from should be present");
        assertNull(tool.getInstruction(), "instruction should be null for derived tool");
        assertEquals("orders-rest", tool.getFrom().getSourceNamespace());
        assertEquals("list-orders", tool.getFrom().getAction());
    }

    @Test
    public void testInstructionToolVariant() throws Exception {
        String yaml = """
                name: "order-guide"
                description: "Guide for working with orders"
                instruction: "order-guide.md"
                """;

        SkillToolSpec tool = YAML.readValue(yaml, SkillToolSpec.class);

        assertEquals("order-guide", tool.getName());
        assertEquals("Guide for working with orders", tool.getDescription());
        assertNull(tool.getFrom(), "from should be null for instruction tool");
        assertNotNull(tool.getInstruction(), "instruction should be present");
        assertEquals("order-guide.md", tool.getInstruction());
    }

    @Test
    public void testFromSpecNamespaceAndAction() throws Exception {
        String yaml = """
                name: "create-order"
                description: "Create a new order"
                from:
                  sourceNamespace: "orders-mcp"
                  action: "create-order"
                """;

        SkillToolSpec tool = YAML.readValue(yaml, SkillToolSpec.class);

        assertNotNull(tool.getFrom());
        assertEquals("orders-mcp", tool.getFrom().getSourceNamespace());
        assertEquals("create-order", tool.getFrom().getAction());
    }

    @Test
    public void testToolWithInstructionSubPath() throws Exception {
        String yaml = """
                name: "deep-guide"
                description: "A guide in a subdirectory"
                instruction: "guides/advanced/deep-guide.md"
                """;

        SkillToolSpec tool = YAML.readValue(yaml, SkillToolSpec.class);

        assertEquals("guides/advanced/deep-guide.md", tool.getInstruction());
    }

    @Test
    public void testNullFromAndNullInstructionAllowedAtDeserializationLevel() throws Exception {
        // Both fields absent is allowed at deserialization level; runtime validation
        // (SkillServerAdapter.validateSkills) is responsible for rejecting this.
        String yaml = """
                name: "empty-tool"
                description: "No from or instruction"
                """;

        SkillToolSpec tool = YAML.readValue(yaml, SkillToolSpec.class);

        assertNull(tool.getFrom());
        assertNull(tool.getInstruction());
    }
}
