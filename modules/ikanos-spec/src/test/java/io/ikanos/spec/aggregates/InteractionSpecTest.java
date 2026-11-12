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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;

/** Unit tests for {@link InteractionSpec}. */
public class InteractionSpecTest {

    @Test
    public void serializationShouldWriteOnlyTheSchemaProperties() throws Exception {
        InteractionSpec spec = IkanosMappers.yaml().readValue("""
                mode: task
                expectedDurationMs: 60000
                """, InteractionSpec.class);

        JsonNode written = IkanosMappers.json().valueToTree(spec);

        // The Interaction schema allows only these two: a derived predicate such as isTask()
        // must not surface as a "task" property, or a serialized capability fails validation.
        Set<String> names = new TreeSet<>(written.propertyNames());
        assertEquals(Set.of("expectedDurationMs", "mode"), names, written.toString());
    }

    @Test
    public void aSerializedInteractionShouldReadBackTheSame() throws Exception {
        InteractionSpec spec = new InteractionSpec(InteractionSpec.MODE_TASK, 60000L);

        String json = IkanosMappers.json().writeValueAsString(spec);
        InteractionSpec read = IkanosMappers.json().readValue(json, InteractionSpec.class);

        assertEquals(InteractionSpec.MODE_TASK, read.getMode());
        assertEquals(60000L, read.getExpectedDurationMs());
        assertTrue(read.isTask());
    }
}
