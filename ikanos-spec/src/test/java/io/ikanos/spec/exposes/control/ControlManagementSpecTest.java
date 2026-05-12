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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ControlManagementSpecTest {

    private ControlManagementSpec spec;

    @BeforeEach
    void setUp() {
        spec = new ControlManagementSpec();
    }

    // ── Health toggle ──

    @Test
    void isHealthShouldReturnTrueByDefault() {
        assertTrue(spec.isHealth());
    }

    @Test
    void setHealthShouldToggleValue() {
        spec.setHealth(false);
        assertFalse(spec.isHealth());
    }

    // ── Info toggle ──

    @Test
    void isInfoShouldReturnFalseByDefault() {
        assertFalse(spec.isInfo());
    }

    @Test
    void setInfoShouldToggleValue() {
        spec.setInfo(true);
        assertTrue(spec.isInfo());
    }

    // ── Reload toggle ──

    @Test
    void isReloadShouldReturnFalseByDefault() {
        assertFalse(spec.isReload());
    }

    @Test
    void setReloadShouldToggleValue() {
        spec.setReload(true);
        assertTrue(spec.isReload());
    }

    // ── Validate toggle ──

    @Test
    void isValidateShouldReturnFalseByDefault() {
        assertFalse(spec.isValidate());
    }

    @Test
    void setValidateShouldToggleValue() {
        spec.setValidate(true);
        assertTrue(spec.isValidate());
    }

    // ── Logs (lazy init) ──

    @Test
    void getLogsShouldLazyInitializeWithLevelControlFalse() {
        // Lazy-init explicitly sets levelControl=false (via getLogs() implementation)
        // even though the POJO default is true. This is intentional and documented in
        // ControlManagementSpec.getLogs().
        ControlLogsEndpointSpec logs = spec.getLogs();
        assertNotNull(logs);
        assertFalse(logs.isLevelControl());
    }

    @Test
    void getLogsShouldReturnSameInstanceOnSubsequentCalls() {
        ControlLogsEndpointSpec first = spec.getLogs();
        ControlLogsEndpointSpec second = spec.getLogs();
        assertSame(first, second);
    }

    @Test
    void setLogsShouldOverrideLazyDefault() {
        ControlLogsEndpointSpec custom = new ControlLogsEndpointSpec();
        custom.setLevelControl(true);
        spec.setLogs(custom);
        assertTrue(spec.getLogs().isLevelControl());
    }

    @Test
    void setLogsShouldAcceptNull() {
        spec.getLogs(); // trigger lazy init
        spec.setLogs(null);
        // After null set, getLogs() should lazy-init again
        ControlLogsEndpointSpec fresh = spec.getLogs();
        assertNotNull(fresh);
        assertFalse(fresh.isLevelControl());
    }

    // ── Scripting ──

    @Test
    void getScriptingShouldReturnNullByDefault() {
        assertNull(spec.getScripting());
    }

    @Test
    void setScriptingShouldStoreValue() {
        ScriptingManagementSpec scripting = new ScriptingManagementSpec();
        spec.setScripting(scripting);
        assertSame(scripting, spec.getScripting());
    }

    // ── isLogging ──

    @Test
    void isLoggingShouldReturnFalseWhenLogsNotSet() {
        // Direct check — logs AtomicReference is null until getLogs() is called
        ControlManagementSpec freshSpec = new ControlManagementSpec();
        // Set logs explicitly to null to avoid lazy init
        freshSpec.setLogs(null);
        assertFalse(freshSpec.isLogging());
    }

    @Test
    void isLoggingShouldReturnFalseWhenLevelControlIsFalse() {
        spec.getLogs(); // lazy-init with levelControl=false
        assertFalse(spec.isLogging());
    }

    @Test
    void isLoggingShouldReturnTrueWhenLevelControlIsTrue() {
        ControlLogsEndpointSpec logs = new ControlLogsEndpointSpec();
        logs.setLevelControl(true);
        spec.setLogs(logs);
        assertTrue(spec.isLogging());
    }
}
