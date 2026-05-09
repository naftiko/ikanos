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
package io.ikanos.spec.exposes.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Unit tests for {@link ScriptingManagementSpec}.
 */
public class ScriptingManagementSpecTest {

    @Test
    public void newSpecShouldHaveDefaultsAndEmptyAllowedLanguages() {
        ScriptingManagementSpec spec = new ScriptingManagementSpec();

        assertNull(spec.getEnabled());
        assertTrue(spec.isEnabled(), "isEnabled() should default to true when enabled is null");
        assertNull(spec.getDefaultLocation());
        assertNull(spec.getDefaultLanguage());
        assertEquals(60_000, spec.getTimeout());
        assertEquals(100_000L, spec.getStatementLimit());
        assertNotNull(spec.getAllowedLanguages());
        assertTrue(spec.getAllowedLanguages().isEmpty());
        assertEquals(0L, spec.getTotalExecutions());
        assertEquals(0L, spec.getTotalErrors());
        assertEquals(0.0, spec.getAverageDurationMs(), 0.0001);
        assertNull(spec.getLastExecutionAt());
    }

    @Test
    public void isEnabledShouldReturnFalseWhenExplicitlyDisabled() {
        ScriptingManagementSpec spec = new ScriptingManagementSpec();
        spec.setEnabled(false);
        assertFalse(spec.isEnabled());
        assertEquals(Boolean.FALSE, spec.getEnabled());
    }

    @Test
    public void settersShouldRoundTripValues() {
        ScriptingManagementSpec spec = new ScriptingManagementSpec();
        spec.setDefaultLocation("file:///scripts");
        spec.setDefaultLanguage("groovy");
        spec.setTimeout(15_000);
        spec.setStatementLimit(50_000L);
        spec.getAllowedLanguages().add("groovy");
        spec.getAllowedLanguages().add("js");

        assertEquals("file:///scripts", spec.getDefaultLocation());
        assertEquals("groovy", spec.getDefaultLanguage());
        assertEquals(15_000, spec.getTimeout());
        assertEquals(50_000L, spec.getStatementLimit());
        assertEquals(2, spec.getAllowedLanguages().size());
    }

    @Test
    public void shouldDeserializeFromYaml() throws Exception {
        String yaml = """
                enabled: true
                defaultLocation: file:///scripts
                defaultLanguage: groovy
                timeout: 5000
                statementLimit: 1000
                allowedLanguages:
                  - groovy
                  - js
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        ScriptingManagementSpec spec = mapper.readValue(yaml, ScriptingManagementSpec.class);

        assertEquals(Boolean.TRUE, spec.getEnabled());
        assertEquals("file:///scripts", spec.getDefaultLocation());
        assertEquals("groovy", spec.getDefaultLanguage());
        assertEquals(5_000, spec.getTimeout());
        assertEquals(1_000L, spec.getStatementLimit());
        assertEquals(2, spec.getAllowedLanguages().size());
    }

    @Test
    public void recordExecutionShouldUpdateCountersAndAverage() {
        ScriptingManagementSpec spec = new ScriptingManagementSpec();

        spec.recordExecution(2_000_000L, false); // 2ms, success
        spec.recordExecution(4_000_000L, true);  // 4ms, error

        assertEquals(2L, spec.getTotalExecutions());
        assertEquals(1L, spec.getTotalErrors());
        assertEquals(3.0, spec.getAverageDurationMs(), 0.0001);
        assertNotNull(spec.getLastExecutionAt(),
                "lastExecutionAt should be set after recording an execution");
    }

    @Test
    public void averageDurationShouldBeZeroWhenNoExecutionsRecorded() {
        ScriptingManagementSpec spec = new ScriptingManagementSpec();
        assertEquals(0.0, spec.getAverageDurationMs(), 0.0001);
    }
}
