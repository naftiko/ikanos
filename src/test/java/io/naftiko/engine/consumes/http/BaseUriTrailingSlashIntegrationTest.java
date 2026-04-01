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
package io.naftiko.engine.consumes.http;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.naftiko.spec.NaftikoSpec;

/**
 * Integration test — non-regression for issue #184.
 *
 * Verifies that deserializing a capability YAML whose {@code baseUri} ends with a trailing slash
 * causes an {@link IllegalArgumentException} in the full deserialization chain
 * (YAML → NaftikoSpec → HttpClientSpec setter), rather than silently producing malformed URLs at
 * runtime.
 */
public class BaseUriTrailingSlashIntegrationTest {

    @Test
    public void deserializingYamlWithTrailingSlashBaseUriShouldFail() throws Exception {
        File fixture = new File("src/test/resources/baseuri-trailing-slash-capability.yaml");
        assertTrue(fixture.exists(), "Fixture file should exist: " + fixture.getPath());

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        Exception wrapping = assertThrows(Exception.class, () -> mapper.readValue(fixture, NaftikoSpec.class));

        Throwable cause = wrapping;
        boolean found = false;
        while (cause != null) {
            if (cause instanceof IllegalArgumentException
                    && cause.getMessage() != null
                    && cause.getMessage().contains("baseUri must not end with a trailing slash")) {
                found = true;
                break;
            }
            cause = cause.getCause();
        }
        assertTrue(found,
                "Expected an IllegalArgumentException about trailing slash in the cause chain, but got: "
                        + wrapping.getMessage());
    }
}
