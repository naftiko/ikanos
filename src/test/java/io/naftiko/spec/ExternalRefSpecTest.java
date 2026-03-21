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
package io.naftiko.spec;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

public class ExternalRefSpecTest {

    @Test
    public void fileResolvedExternalRefShouldDeserializeFromYaml() throws Exception {
        String yaml = """
                type: environment
                name: my-env
                description: Load from .env file
                uri: file:///.env
                keys:
                  database_url: DB_URL
                  api_key: API_KEY
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        ExternalRefSpec spec = mapper.readValue(yaml, ExternalRefSpec.class);

        assertNotNull(spec);
        assertEquals("environment", spec.getType());
        FileResolvedExternalRefSpec fileSpec = (FileResolvedExternalRefSpec) spec;
        assertEquals("my-env", fileSpec.getName());
        assertEquals("Load from .env file", fileSpec.getDescription());
        assertEquals("file:///.env", fileSpec.getUri());
        assertNotNull(fileSpec.getKeys());
    }

    @Test
    public void runtimeResolvedExternalRefShouldDeserializeFromYaml() throws Exception {
        String yaml = """
                type: variables
                name: my-vars
                keys:
                  env_token: ENV_VAR_TOKEN
                  port: PORT
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        ExternalRefSpec spec = mapper.readValue(yaml, ExternalRefSpec.class);

        assertNotNull(spec);
        assertEquals("variables", spec.getType());
        RuntimeResolvedExternalRefSpec runtimeSpec = (RuntimeResolvedExternalRefSpec) spec;
        assertEquals("my-vars", runtimeSpec.getName());
        assertNotNull(runtimeSpec.getKeys());
    }

    @Test
    public void fileResolvedExternalRefConstructorShouldSetDefaults() {
        ExternalRefKeysSpec keys = new ExternalRefKeysSpec();
        FileResolvedExternalRefSpec spec =
                new FileResolvedExternalRefSpec("env-file", "Load .env", "file:///.env", keys);

        assertEquals("env-file", spec.getName());
        assertEquals("environment", spec.getType());
        assertEquals("file", spec.getResolution());
        assertEquals("Load .env", spec.getDescription());
        assertEquals("file:///.env", spec.getUri());
        assertEquals(keys, spec.getKeys());
    }

    @Test
    public void runtimeResolvedExternalRefConstructorShouldSetDefaults() {
        ExternalRefKeysSpec keys = new ExternalRefKeysSpec();
        RuntimeResolvedExternalRefSpec spec =
                new RuntimeResolvedExternalRefSpec("env-vars", keys);

        assertEquals("env-vars", spec.getName());
        assertEquals("variables", spec.getType());
        assertEquals("runtime", spec.getResolution());
        assertEquals(keys, spec.getKeys());
    }
}
