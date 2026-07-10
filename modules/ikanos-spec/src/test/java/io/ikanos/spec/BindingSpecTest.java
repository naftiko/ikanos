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

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.spec.util.BindingKeysSpec;
import io.ikanos.spec.util.BindingSpec;

public class BindingSpecTest {

    @Test
    public void bindingWithLocationShouldDeserializeFromYaml() throws Exception {
        String yaml = """
                namespace: my-env
                description: Load from .env file
                location: file:///.env
                keys:
                  database_url: DB_URL
                  api_key: API_KEY
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        BindingSpec spec = mapper.readValue(yaml, BindingSpec.class);

        assertNotNull(spec);
        assertEquals("my-env", spec.getNamespace());
        assertEquals("Load from .env file", spec.getDescription());
        assertEquals("file:///.env", spec.getLocation());
        assertNotNull(spec.getKeys());
        assertEquals("DB_URL", spec.getKeys().getKey("database_url"));
        assertEquals("API_KEY", spec.getKeys().getKey("api_key"));
    }

    @Test
    public void bindingWithoutLocationShouldDeserializeFromYaml() throws Exception {
        String yaml = """
                namespace: my-vars
                keys:
                  env_token: ENV_VAR_TOKEN
                  port: PORT
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        BindingSpec spec = mapper.readValue(yaml, BindingSpec.class);

        assertNotNull(spec);
        assertEquals("my-vars", spec.getNamespace());
        assertNull(spec.getLocation());
        assertNotNull(spec.getKeys());
        assertEquals("ENV_VAR_TOKEN", spec.getKeys().getKey("env_token"));
        assertEquals("PORT", spec.getKeys().getKey("port"));
    }

    @Test
    public void constructorWithLocationShouldSetAllFields() {
        BindingKeysSpec keys = new BindingKeysSpec();
        BindingSpec spec = new BindingSpec("env-file", "Load .env", "file:///.env", keys);

        assertEquals("env-file", spec.getNamespace());
        assertEquals("Load .env", spec.getDescription());
        assertEquals("file:///.env", spec.getLocation());
        assertEquals(keys, spec.getKeys());
    }

    @Test
    public void constructorWithoutLocationShouldLeaveLocationNull() {
        BindingKeysSpec keys = new BindingKeysSpec();
        BindingSpec spec = new BindingSpec("env-vars", null, null, keys);

        assertEquals("env-vars", spec.getNamespace());
        assertNull(spec.getDescription());
        assertNull(spec.getLocation());
        assertEquals(keys, spec.getKeys());
    }
}
