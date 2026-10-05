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
package io.ikanos.spec.consumes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.spec.consumes.http.HttpClientSpec;

class ClientSpecTypesTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static ClientSpecType provider(String type, Class<? extends ClientSpec> specClass) {
        return new ClientSpecType() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public Class<? extends ClientSpec> specClass() {
                return specClass;
            }
        };
    }

    @Test
    void loadShouldAlwaysRegisterHttpFirst() {
        Map<String, Class<? extends ClientSpec>> registry = ClientSpecTypes.load(List.of());

        assertEquals(HttpClientSpec.class, registry.get("http"));
    }

    @Test
    void loadShouldRejectProviderClaimingHttp() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ClientSpecTypes.load(List.of(provider("http", HttpClientSpec.class))));

        assertTrue(error.getMessage().contains("Duplicate consumes type 'http'"));
    }

    @Test
    void loadShouldRejectProviderWithBlankType() {
        assertThrows(IllegalStateException.class,
                () -> ClientSpecTypes.load(List.of(provider(" ", HttpClientSpec.class))));
    }

    @Test
    void resolveShouldTreatMissingTypeAsHttp() {
        assertEquals(HttpClientSpec.class, ClientSpecTypes.resolve(null));
    }

    @Test
    void deserializerShouldTreatMissingTypeAsHttp() throws Exception {
        ClientSpec spec = YAML.readValue("""
                namespace: svc
                baseUri: https://api.example.com
                """, ClientSpec.class);

        assertInstanceOf(HttpClientSpec.class, spec);
    }

    @Test
    void deserializerShouldRejectUnknownTypeNamingIt() {
        Exception error = assertThrows(Exception.class, () -> YAML.readValue("""
                type: carrier-pigeon
                namespace: svc
                """, ClientSpec.class));

        assertTrue(error.getMessage().contains("Unknown consumes type 'carrier-pigeon'"),
                error.getMessage());
    }
}
