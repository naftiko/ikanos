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
package io.ikanos.engine.exposes.skill;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.exposes.skill.SkillServerSpec;
import io.ikanos.spec.exposes.skill.ExposedSkillSpec;
import io.ikanos.spec.exposes.skill.SkillToolSpec;
import io.ikanos.spec.exposes.skill.SkillToolFromSpec;

public class SkillValidationTest {

    @Test
    public void skillServerSpecShouldAllowMultipleSkills() {
        SkillServerSpec spec = new SkillServerSpec("localhost", 8080, "test-skills");
        
        ExposedSkillSpec skill1 = new ExposedSkillSpec();
        skill1.setName("skill1");
        skill1.setDescription("First skill");
        
        ExposedSkillSpec skill2 = new ExposedSkillSpec();
        skill2.setName("skill2");
        skill2.setDescription("Second skill");

        spec.getSkills().add(skill1);
        spec.getSkills().add(skill2);

        assertEquals(2, spec.getSkills().size());
        assertEquals("skill1", spec.getSkills().get(0).getName());
        assertEquals("skill2", spec.getSkills().get(1).getName());
    }

    @Test
    public void skillToolMustHaveEitherFromOrInstruction() {
        SkillToolSpec skillToolWithFrom = new SkillToolSpec();
        skillToolWithFrom.setName("derived-tool");
        SkillToolFromSpec from = new SkillToolFromSpec();
        from.setSourceNamespace("test-ns");
        from.setAction("list-orders");
        skillToolWithFrom.setFrom(from);

        assertNotNull(skillToolWithFrom.getFrom());
        assertNull(skillToolWithFrom.getInstruction());

        SkillToolSpec skillToolWithInstruction = new SkillToolSpec();
        skillToolWithInstruction.setName("instruction-tool");
        skillToolWithInstruction.setInstruction("guide.md");

        assertNull(skillToolWithInstruction.getFrom());
        assertNotNull(skillToolWithInstruction.getInstruction());
    }

    @Test
    public void exposedSkillMayHaveLocation() {
        ExposedSkillSpec skill = new ExposedSkillSpec();
        skill.setName("skill-with-location");
        skill.setLocation("file:///skills/my-skill");

        assertEquals("file:///skills/my-skill", skill.getLocation());
    }

    @Test
    public void exposedSkillMayHaveTools() {
        ExposedSkillSpec skill = new ExposedSkillSpec();
        skill.setName("skill-with-tools");

        SkillToolSpec tool1 = new SkillToolSpec();
        tool1.setName("tool1");
        tool1.setInstruction("tool1.md");

        SkillToolSpec tool2 = new SkillToolSpec();
        tool2.setName("tool2");
        tool2.setInstruction("tool2.md");

        skill.getTools().add(tool1);
        skill.getTools().add(tool2);

        assertEquals(2, skill.getTools().size());
        assertEquals("tool1", skill.getTools().get(0).getName());
        assertEquals("tool2", skill.getTools().get(1).getName());
    }

    @Test
    public void skillCanBeDescriptiveWithoutTools() {
        ExposedSkillSpec skill = new ExposedSkillSpec();
        skill.setName("descriptive-skill");
        skill.setDescription("A skill that only describes, no tools");
        skill.setLocation("file:///skills/descriptive");

        assertTrue(skill.getTools().isEmpty());
        assertNotNull(skill.getDescription());
        assertNotNull(skill.getLocation());
    }
}
