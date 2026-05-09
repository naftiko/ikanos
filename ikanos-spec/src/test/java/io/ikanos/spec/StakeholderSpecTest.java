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
package io.ikanos.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Unit tests for {@link StakeholderSpec}.
 */
public class StakeholderSpecTest {

    @Test
    public void noArgConstructorShouldLeaveAllFieldsNull() {
        StakeholderSpec spec = new StakeholderSpec();
        assertNull(spec.getRole());
        assertNull(spec.getFullName());
        assertNull(spec.getEmail());
    }

    @Test
    public void allArgsConstructorShouldAssignAllFields() {
        StakeholderSpec spec = new StakeholderSpec("owner", "Jane Doe", "jane@example.com");

        assertEquals("owner", spec.getRole());
        assertEquals("Jane Doe", spec.getFullName());
        assertEquals("jane@example.com", spec.getEmail());
    }

    @Test
    public void settersShouldRoundTripValues() {
        StakeholderSpec spec = new StakeholderSpec();
        spec.setRole("maintainer");
        spec.setFullName("John Smith");
        spec.setEmail("john@example.com");

        assertEquals("maintainer", spec.getRole());
        assertEquals("John Smith", spec.getFullName());
        assertEquals("john@example.com", spec.getEmail());
    }

    @Test
    public void shouldDeserializeFromYaml() throws Exception {
        String yaml = """
                role: owner
                fullName: Jane Doe
                email: jane@example.com
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        StakeholderSpec spec = mapper.readValue(yaml, StakeholderSpec.class);

        assertEquals("owner", spec.getRole());
        assertEquals("Jane Doe", spec.getFullName());
        assertEquals("jane@example.com", spec.getEmail());
    }
}
