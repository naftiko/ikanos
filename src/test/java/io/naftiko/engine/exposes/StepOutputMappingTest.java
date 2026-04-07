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
package io.naftiko.engine.exposes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.exposes.McpServerSpec;
import io.naftiko.spec.exposes.McpServerToolSpec;

/**
 * Non-regression tests for step output mapping deserialization and resolution.
 *
 * <p>These tests document the bug reported in #243: the {@code mappings} field declared
 * in the JSON schema for orchestrated tools is silently dropped during YAML
 * deserialization because {@link McpServerToolSpec} has no corresponding Java field.</p>
 *
 * <p>Before the fix, both tests fail — proving the bug exists.
 * After the fix, both tests pass — proving the bug is resolved.</p>
 */
public class StepOutputMappingTest {

    /**
     * Proves that {@link McpServerToolSpec} exposes a {@code getMappings()} accessor.
     *
     * <p>Before the fix this test fails because the class has no such method.</p>
     */
    @Test
    void mcpToolSpecShouldHaveGetMappingsAccessor() {
        boolean hasMappings = Arrays.stream(McpServerToolSpec.class.getMethods())
                .map(Method::getName)
                .anyMatch("getMappings"::equals);

        assertTrue(hasMappings,
                "McpServerToolSpec must expose getMappings() for orchestrated mode");
    }

    /**
     * Proves that the YAML {@code mappings} block round-trips through Jackson
     * deserialization and reserialization without data loss.
     *
     * <p>Before the fix this test fails because Jackson silently ignores the
     * unknown {@code mappings} property.</p>
     */
    @Test
    void deserializedToolSpecShouldPreserveMappingsOnRoundTrip() throws Exception {
        NaftikoSpec spec = loadTutorialStep7();

        McpServerSpec mcpSpec = (McpServerSpec) spec.getCapability().getExposes().get(0);
        McpServerToolSpec getShipWithCrew = mcpSpec.getTools().stream()
                .filter(t -> "get-ship-with-crew".equals(t.getName()))
                .findFirst().orElseThrow();

        // Reserialize the tool spec to JSON and check for mappings
        ObjectMapper jsonMapper = new ObjectMapper();
        JsonNode toolJson = jsonMapper.valueToTree(getShipWithCrew);

        assertTrue(toolJson.has("mappings"),
                "Reserialized tool spec must contain 'mappings' field");
        assertTrue(toolJson.get("mappings").isArray(),
                "'mappings' must be an array");
        assertFalse(toolJson.get("mappings").isEmpty(),
                "'mappings' must not be empty");
    }

    private static NaftikoSpec loadTutorialStep7() throws Exception {
        File file = new File(
                "src/main/resources/tutorial/step-7-shipyard-orchestrated-lookup.yml");
        assertTrue(file.exists(), "Tutorial step-7 file must exist");

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper.readValue(file, NaftikoSpec.class);
    }
}
