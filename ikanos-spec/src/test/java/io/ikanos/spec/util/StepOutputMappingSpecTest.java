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
package io.ikanos.spec.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Unit tests for {@link StepOutputMappingSpec}.
 */
public class StepOutputMappingSpecTest {

    @Test
    public void noArgConstructorShouldLeaveAllFieldsNull() {
        StepOutputMappingSpec spec = new StepOutputMappingSpec();
        assertNull(spec.getTargetName());
        assertNull(spec.getValue());
    }

    @Test
    public void allArgsConstructorShouldAssignFields() {
        StepOutputMappingSpec spec = new StepOutputMappingSpec("petName", "{{step1.name}}");

        assertEquals("petName", spec.getTargetName());
        assertEquals("{{step1.name}}", spec.getValue());
    }

    @Test
    public void settersShouldRoundTripValues() {
        StepOutputMappingSpec spec = new StepOutputMappingSpec();
        spec.setTargetName("statusCode");
        spec.setValue("{{step1.status}}");

        assertEquals("statusCode", spec.getTargetName());
        assertEquals("{{step1.status}}", spec.getValue());
    }

    @Test
    public void shouldDeserializeFromYaml() throws Exception {
        String yaml = """
                targetName: statusCode
                value: "{{step1.status}}"
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        StepOutputMappingSpec spec = mapper.readValue(yaml, StepOutputMappingSpec.class);

        assertEquals("statusCode", spec.getTargetName());
        assertEquals("{{step1.status}}", spec.getValue());
    }
}
