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
package io.naftiko.spec.exposes;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for OperationStepScriptSpec deserialization.
 * Validates polymorphic deserialization of script steps from YAML.
 */
public class OperationStepScriptDeserializationTest {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Test
    void scriptStepShouldDeserializeWithAllProperties() throws Exception {
        String yaml = """
                type: script
                name: filter-active
                language: javascript
                location: "file:///app/scripts"
                file: "filter-active.js"
                dependencies:
                  - "lib/array-utils.js"
                with:
                  threshold: "{{minStock}}"
                """;

        OperationStepSpec step = yamlMapper.readValue(yaml, OperationStepSpec.class);

        assertNotNull(step);
        assertInstanceOf(OperationStepScriptSpec.class, step);

        OperationStepScriptSpec scriptStep = (OperationStepScriptSpec) step;
        assertEquals("script", scriptStep.getType());
        assertEquals("filter-active", scriptStep.getName());
        assertEquals("javascript", scriptStep.getLanguage());
        assertEquals("file:///app/scripts", scriptStep.getLocation());
        assertEquals("filter-active.js", scriptStep.getFile());
        assertEquals(1, scriptStep.getDependencies().size());
        assertEquals("lib/array-utils.js", scriptStep.getDependencies().get(0));
        assertNotNull(scriptStep.getWith());
        assertEquals("{{minStock}}", scriptStep.getWith().get("threshold"));
    }

    @Test
    void scriptStepShouldDeserializeWithMinimalProperties() throws Exception {
        String yaml = """
                type: script
                name: transform
                language: javascript
                location: "file:///app/scripts"
                file: "transform.js"
                """;

        OperationStepSpec step = yamlMapper.readValue(yaml, OperationStepSpec.class);

        assertInstanceOf(OperationStepScriptSpec.class, step);
        OperationStepScriptSpec scriptStep = (OperationStepScriptSpec) step;
        assertEquals("transform", scriptStep.getName());
        assertEquals("javascript", scriptStep.getLanguage());
        assertEquals("file:///app/scripts", scriptStep.getLocation());
        assertEquals("transform.js", scriptStep.getFile());
        assertTrue(scriptStep.getDependencies().isEmpty());
        assertNull(scriptStep.getWith());
    }

    @Test
    void scriptStepShouldDeserializeGroovyLanguage() throws Exception {
        String yaml = """
                type: script
                name: enrich
                language: groovy
                location: "file:///app/scripts"
                file: "enrich.groovy"
                """;

        OperationStepSpec step = yamlMapper.readValue(yaml, OperationStepSpec.class);

        assertInstanceOf(OperationStepScriptSpec.class, step);
        OperationStepScriptSpec scriptStep = (OperationStepScriptSpec) step;
        assertEquals("groovy", scriptStep.getLanguage());
    }

    @Test
    void scriptStepShouldDeserializePythonLanguage() throws Exception {
        String yaml = """
                type: script
                name: analyze
                language: python
                location: "file:///app/scripts"
                file: "analyze.py"
                """;

        OperationStepSpec step = yamlMapper.readValue(yaml, OperationStepSpec.class);

        assertInstanceOf(OperationStepScriptSpec.class, step);
        OperationStepScriptSpec scriptStep = (OperationStepScriptSpec) step;
        assertEquals("python", scriptStep.getLanguage());
    }

    @Test
    void scriptStepShouldDeserializeMultipleDependencies() throws Exception {
        String yaml = """
                type: script
                name: complex
                language: javascript
                location: "file:///app/scripts"
                file: "main.js"
                dependencies:
                  - "lib/utils.js"
                  - "lib/helpers.js"
                  - "lib/formatters.js"
                """;

        OperationStepSpec step = yamlMapper.readValue(yaml, OperationStepSpec.class);

        assertInstanceOf(OperationStepScriptSpec.class, step);
        OperationStepScriptSpec scriptStep = (OperationStepScriptSpec) step;
        assertEquals(3, scriptStep.getDependencies().size());
        assertEquals("lib/utils.js", scriptStep.getDependencies().get(0));
        assertEquals("lib/helpers.js", scriptStep.getDependencies().get(1));
        assertEquals("lib/formatters.js", scriptStep.getDependencies().get(2));
    }

    @Test
    void scriptStepShouldDeserializeWithoutLocationAndLanguage() throws Exception {
        String yaml = """
                type: script
                name: transform
                file: "transform.js"
                """;

        OperationStepSpec step = yamlMapper.readValue(yaml, OperationStepSpec.class);

        assertInstanceOf(OperationStepScriptSpec.class, step);
        OperationStepScriptSpec scriptStep = (OperationStepScriptSpec) step;
        assertNull(scriptStep.getLanguage());
        assertNull(scriptStep.getLocation());
        assertEquals("transform.js", scriptStep.getFile());
    }

}
