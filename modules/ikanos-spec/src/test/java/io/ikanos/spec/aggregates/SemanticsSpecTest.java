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
package io.ikanos.spec.aggregates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Unit tests for {@link SemanticsSpec}.
 */
public class SemanticsSpecTest {

    @Test
    public void noArgConstructorShouldLeaveAllFlagsNull() {
        SemanticsSpec spec = new SemanticsSpec();

        assertNull(spec.getSafe());
        assertNull(spec.getIdempotent());
        assertNull(spec.getCacheable());
    }

    @Test
    public void allArgsConstructorShouldAssignEachFlag() {
        SemanticsSpec spec = new SemanticsSpec(Boolean.TRUE, Boolean.FALSE, Boolean.TRUE);

        assertEquals(Boolean.TRUE, spec.getSafe());
        assertEquals(Boolean.FALSE, spec.getIdempotent());
        assertEquals(Boolean.TRUE, spec.getCacheable());
    }

    @Test
    public void settersShouldRoundTripValues() {
        SemanticsSpec spec = new SemanticsSpec();
        spec.setSafe(Boolean.FALSE);
        spec.setIdempotent(Boolean.TRUE);
        spec.setCacheable(Boolean.FALSE);

        assertEquals(Boolean.FALSE, spec.getSafe());
        assertEquals(Boolean.TRUE, spec.getIdempotent());
        assertEquals(Boolean.FALSE, spec.getCacheable());
    }

    @Test
    public void shouldDeserializeFromYaml() throws Exception {
        String yaml = """
                safe: true
                idempotent: true
                cacheable: false
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        SemanticsSpec spec = mapper.readValue(yaml, SemanticsSpec.class);

        assertEquals(Boolean.TRUE, spec.getSafe());
        assertEquals(Boolean.TRUE, spec.getIdempotent());
        assertEquals(Boolean.FALSE, spec.getCacheable());
    }
}
