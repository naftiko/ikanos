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
package io.naftiko.spec.exposes;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.spec.NaftikoSpec;

/**
 * Round-trip tests for {@link SkillServerSpec} — YAML deserialization, field validation, and
 * JSON re-serialization.
 */
public class SkillServerSpecRoundTripTest {

    private NaftikoSpec naftikoSpec;
    private SkillServerSpec skillSpec;

    @BeforeEach
    public void setUp() throws Exception {
        File file = new File("src/test/resources/skill-capability.yaml");
        assertTrue(file.exists(), "Skill capability test file should exist");

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        naftikoSpec = mapper.readValue(file, NaftikoSpec.class);

        skillSpec = (SkillServerSpec) naftikoSpec.getCapability().getExposes().stream()
                .filter(s -> s instanceof SkillServerSpec)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No SkillServerSpec found in fixture"));
    }

    @Test
    public void testNaftikoVersionLoaded() {
        assertEquals("1.0.0-alpha1", naftikoSpec.getNaftiko());
    }

    @Test
    public void testSkillServerSpecType() {
        assertEquals("skill", skillSpec.getType());
    }

    @Test
    public void testSkillServerSpecAddress() {
        assertEquals("localhost", skillSpec.getAddress());
    }

    @Test
    public void testSkillServerSpecPort() {
        assertEquals(9097, skillSpec.getPort());
    }

    @Test
    public void testSkillServerSpecNamespace() {
        assertEquals("orders-skills", skillSpec.getNamespace());
    }

    @Test
    public void testSkillServerSpecDescription() {
        assertEquals("Order management skills catalog", skillSpec.getDescription());
    }

    @Test
    public void testSkillCount() {
        List<ExposedSkillSpec> skills = skillSpec.getSkills();
        assertEquals(2, skills.size(), "Should have two skills");
    }

    @Test
    public void testFirstSkillFields() {
        ExposedSkillSpec skill = skillSpec.getSkills().get(0);
        assertEquals("order-management", skill.getName());
        assertEquals("Tools for managing orders", skill.getDescription());
        assertEquals("file:///tmp/naftiko-test-skills/order-management", skill.getLocation());
    }

    @Test
    public void testFirstSkillHyphenatedFields() {
        ExposedSkillSpec skill = skillSpec.getSkills().get(0);
        assertEquals("list-orders", skill.getAllowedTools(),
                "allowed-tools should be deserialized despite hyphen");
        assertEquals("Use for order operations", skill.getArgumentHint(),
                "argument-hint should be deserialized despite hyphen");
    }

    @Test
    public void testFirstSkillToolCount() {
        ExposedSkillSpec skill = skillSpec.getSkills().get(0);
        assertEquals(2, skill.getTools().size(), "Should have two tools");
    }

    @Test
    public void testDerivedToolFromSpec() {
        SkillToolSpec tool = skillSpec.getSkills().get(0).getTools().get(0);
        assertEquals("list-orders", tool.getName());
        assertEquals("List all orders in the system", tool.getDescription());
        assertNotNull(tool.getFrom(), "from should be present");
        assertNull(tool.getInstruction(), "instruction should be null for derived tool");
        assertEquals("orders-rest", tool.getFrom().getSourceNamespace());
        assertEquals("list-orders", tool.getFrom().getAction());
    }

    @Test
    public void testInstructionToolSpec() {
        SkillToolSpec tool = skillSpec.getSkills().get(0).getTools().get(1);
        assertEquals("order-guide", tool.getName());
        assertNull(tool.getFrom(), "from should be null for instruction tool");
        assertNotNull(tool.getInstruction(), "instruction should be present");
        assertEquals("order-guide.md", tool.getInstruction());
    }

    @Test
    public void testSecondSkillNoTools() {
        ExposedSkillSpec skill = skillSpec.getSkills().get(1);
        assertEquals("onboarding-guide", skill.getName());
        assertTrue(skill.getTools().isEmpty(), "Descriptive skill should have no tools");
    }

    @Test
    public void testJsonRoundTrip() throws Exception {
        ObjectMapper jsonMapper = new ObjectMapper();

        // Serialize SkillServerSpec to JSON
        String json = jsonMapper.writeValueAsString(skillSpec);

        // Deserialize back using the polymorphic ServerSpec supertype
        ServerSpec restored = jsonMapper.readValue(json, ServerSpec.class);

        assertInstanceOf(SkillServerSpec.class, restored);
        SkillServerSpec restoredSpec = (SkillServerSpec) restored;

        assertEquals(skillSpec.getNamespace(), restoredSpec.getNamespace());
        assertEquals(skillSpec.getPort(), restoredSpec.getPort());
        assertEquals(skillSpec.getSkills().size(), restoredSpec.getSkills().size());

        // Verify first skill round-trips correctly
        ExposedSkillSpec restoredSkill = restoredSpec.getSkills().get(0);
        assertEquals("order-management", restoredSkill.getName());
        assertEquals("list-orders", restoredSkill.getAllowedTools());
        assertEquals("order-guide.md",
                restoredSkill.getTools().get(1).getInstruction());
    }
}
