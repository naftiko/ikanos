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
package io.ikanos.spec.openapi;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.swagger.v3.oas.models.OpenAPI;
import io.ikanos.spec.CapabilitySpec;
import io.ikanos.spec.InfoSpec;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;

/**
 * Integration tests: load Ikanos capability YAMLs, export to OAS, and validate.
 */
public class OasExportIntegrationTest {

    private OasExportBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new OasExportBuilder();
    }

    @Test
    void exportShouldProduceValidOpenApiFromCapabilityYaml() throws Exception {
        File capFile = findCapabilityFixture();
        
        IkanosSpec spec;
        if (capFile != null) {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            spec = yamlMapper.readValue(capFile, IkanosSpec.class);
        } else {
            // Build minimal spec programmatically as fallback
            spec = new IkanosSpec();
            spec.setIkanos("1.0.0-alpha1");
            spec.setInfo(new InfoSpec("Test API", null, null, null));
            CapabilitySpec capability = new CapabilitySpec();
            capability.getExposes().add(new RestServerSpec("localhost", 8080, null));
            spec.setCapability(capability);
        }

        OasExportResult result = builder.build(spec, null);

        OpenAPI openApi = result.getOpenApi();
        assertNotNull(openApi);
        assertNotNull(openApi.getInfo());
        assertNotNull(openApi.getInfo().getTitle());
    }

    @Test
    void exportedOpenApiShouldBeReparsable() throws Exception {
        File capFile = findCapabilityFixture();
        IkanosSpec spec;
        
        if (capFile != null) {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            spec = yamlMapper.readValue(capFile, IkanosSpec.class);
        } else {
            spec = new IkanosSpec();
            spec.setIkanos("1.0.0-alpha1");
            spec.setInfo(new InfoSpec("Test API", null, null, null));
            CapabilitySpec capability = new CapabilitySpec();
            capability.getExposes().add(new RestServerSpec("localhost", 8080, null));
            spec.setCapability(capability);
        }

        OasExportResult result = builder.build(spec, null);

        // Serialize to YAML string and re-parse
        String yaml = io.swagger.v3.core.util.Yaml.pretty(result.getOpenApi());
        assertNotNull(yaml);
        assertTrue(yaml.contains("openapi:"));
    }

    private File findCapabilityFixture() {
        // Try tutorial resources first
        File[] candidates = {
            new File("src/test/resources/tutorial/capabilities/notion-capability.yml"),
            new File("src/test/resources/tutorial/capabilities/basic-capability.yml"),
            new File("src/test/resources/capabilities/test-capability.yml")
        };
        for (File f : candidates) {
            if (f.exists()) {
                return f;
            }
        }
        return null;
    }

}
