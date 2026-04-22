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
package io.naftiko.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Validates that the GraalVM native-image reflect-config.json contains all classes
 * required for Jackson serialization in the import openapi command.
 */
public class NativeImageReflectConfigTest {

    private static Set<String> registeredClasses;

    @BeforeAll
    static void loadReflectConfig() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = NativeImageReflectConfigTest.class.getClassLoader()
                .getResourceAsStream("META-INF/native-image/reflect-config.json")) {
            assertNotNull(is, "reflect-config.json must be on the classpath");
            List<ReflectEntry> entries = mapper.readValue(is,
                    new TypeReference<List<ReflectEntry>>() {});
            registeredClasses = entries.stream()
                    .map(e -> e.name)
                    .collect(Collectors.toSet());
        }
    }

    @Test
    void reflectConfigShouldContainNaftikoSpecClasses() {
        List<String> requiredClasses = List.of(
                "io.naftiko.spec.NaftikoSpec",
                "io.naftiko.spec.consumes.ClientSpec",
                "io.naftiko.spec.consumes.HttpClientSpec",
                "io.naftiko.spec.consumes.HttpClientResourceSpec",
                "io.naftiko.spec.consumes.HttpClientOperationSpec",
                "io.naftiko.spec.OperationSpec",
                "io.naftiko.spec.ResourceSpec",
                "io.naftiko.spec.InputParameterSpec",
                "io.naftiko.spec.OutputParameterSpec",
                "io.naftiko.spec.util.StructureSpec");

        List<String> missing = requiredClasses.stream()
                .filter(c -> !registeredClasses.contains(c))
                .toList();

        assertTrue(missing.isEmpty(),
                "reflect-config.json is missing Naftiko spec classes required for "
                        + "Jackson serialization in native mode: " + missing);
    }

    @Test
    void reflectConfigShouldContainAuthenticationSpecClasses() {
        List<String> requiredClasses = List.of(
                "io.naftiko.spec.consumes.AuthenticationSpec",
                "io.naftiko.spec.consumes.ApiKeyAuthenticationSpec",
                "io.naftiko.spec.consumes.BearerAuthenticationSpec",
                "io.naftiko.spec.consumes.BasicAuthenticationSpec",
                "io.naftiko.spec.consumes.DigestAuthenticationSpec");

        List<String> missing = requiredClasses.stream()
                .filter(c -> !registeredClasses.contains(c))
                .toList();

        assertTrue(missing.isEmpty(),
                "reflect-config.json is missing authentication spec classes: " + missing);
    }

    @Test
    void reflectConfigShouldContainCustomSerializers() {
        List<String> requiredClasses = List.of(
                "io.naftiko.spec.InputParameterSerializer",
                "io.naftiko.spec.OutputParameterSerializer");

        List<String> missing = requiredClasses.stream()
                .filter(c -> !registeredClasses.contains(c))
                .toList();

        assertTrue(missing.isEmpty(),
                "reflect-config.json is missing custom Jackson serializers: " + missing);
    }

    /**
     * Minimal POJO for deserializing reflect-config.json entries.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ReflectEntry {
        public String name;
    }
}
