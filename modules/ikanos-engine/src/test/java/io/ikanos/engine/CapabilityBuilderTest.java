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
package io.ikanos.engine;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.StringNode;
import io.ikanos.Capability;
import io.ikanos.engine.step.StepHandler;

class CapabilityBuilderTest {

    @Test
    void buildShouldThrowWhenNoCapabilityLoaded() {
        assertThrows(IllegalStateException.class, () -> Capability.builder().build());
    }

    @Test
    void capabilityFromClasspathShouldLoadYaml() {
        Capability capability = Capability.builder()
                .loadFromClasspath("/embedding/embedding-capability.yaml")
                .build();

        assertNotNull(capability);
        assertEquals("Embedding Test", capability.getSpec().getInfo().getDisplay());
    }

    @Test
    void capabilityFromClasspathShouldThrowOnMissingResource() {
        assertThrows(IllegalArgumentException.class,
                () -> Capability.builder().loadFromClasspath("/nonexistent.yaml"));
    }

    @Test
    void capabilityFromClasspathShouldRejectYamlAlias() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Capability.builder()
                        .loadFromClasspath("/yaml-aliases/alias-capability.yaml"));

        String message = error.getCause().getMessage();
        assertTrue(message.contains("*list-inputs"), message);
        assertTrue(message.contains("line: 36"), message);
    }

    @Test
    void capabilityFromFileShouldRejectYamlAlias() throws Exception {
        java.nio.file.Path file = java.nio.file.Path.of(getClass()
                .getResource("/yaml-aliases/alias-capability.yaml").toURI());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Capability.builder().loadFromFile(file));

        assertTrue(error.getCause().getMessage().contains("*list-inputs"),
                error.getCause().getMessage());
    }

    @Test
    void stepHandlerShouldRegisterHandler() {
        StepHandler handler = ctx -> StringNode.valueOf("test");

        Capability capability = Capability.builder()
                .loadFromClasspath("/embedding/embedding-capability.yaml")
                .stepHandler("do-greet", handler)
                .build();

        assertNotNull(capability.getStepHandlerRegistry());
        assertTrue(capability.getStepHandlerRegistry().has("do-greet"));
    }
}
