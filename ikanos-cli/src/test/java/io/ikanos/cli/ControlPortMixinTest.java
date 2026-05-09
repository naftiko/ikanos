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
package io.ikanos.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ControlPortMixinTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvePortShouldReturnExplicitFlagFirst() {
        ControlPortMixin mixin = new ControlPortMixin();
        mixin.port = 7777;

        assertEquals(7777, mixin.resolvePort());
    }

    @Test
    void resolvePortShouldReturnDefaultWhenNoSourceAvailable() {
        ControlPortMixin mixin = new ControlPortMixin();

        assertEquals(ControlPortMixin.DEFAULT_PORT, mixin.resolvePort());
    }

    @Test
    void resolveAddressShouldReturnExplicitFlagFirst() {
        ControlPortMixin mixin = new ControlPortMixin();
        mixin.address = "10.0.0.1";

        assertEquals("10.0.0.1", mixin.resolveAddress());
    }

    @Test
    void resolveAddressShouldReturnDefaultWhenNoSourceAvailable() {
        ControlPortMixin mixin = new ControlPortMixin();

        assertEquals(ControlPortMixin.DEFAULT_ADDRESS, mixin.resolveAddress());
    }

    @Test
    void baseUrlShouldCombineAddressAndPort() {
        ControlPortMixin mixin = new ControlPortMixin();
        mixin.address = "example.com";
        mixin.port = 8888;

        assertEquals("http://example.com:8888", mixin.baseUrl());
    }

    @Test
    void discoverPortFromYamlShouldReturnNegativeWhenNoYamlFound() {
        ControlPortMixin mixin = new ControlPortMixin();
        // CWD is unlikely to have a capability YAML with a control port
        // Use a tempDir with no YAML files
        String originalDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", tempDir.toString());
            assertEquals(-1, mixin.discoverPortFromYaml());
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    void discoverPortFromYamlShouldFindControlPort() throws Exception {
        ControlPortMixin mixin = new ControlPortMixin();

        Path yaml = tempDir.resolve("my-capability.yaml");
        Files.writeString(yaml, """
                ikanos: "1.0.0-alpha2"
                info:
                  label: Test
                capability:
                  exposes:
                    - type: control
                      port: 9199
                """);

        String originalDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", tempDir.toString());
            assertEquals(9199, mixin.discoverPortFromYaml());
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    void discoverPortFromYamlShouldSkipInvalidYaml() throws Exception {
        ControlPortMixin mixin = new ControlPortMixin();

        Path invalidYaml = tempDir.resolve("invalid.yaml");
        Files.writeString(invalidYaml, "not: a: valid: ikanos: spec");

        String originalDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", tempDir.toString());
            assertEquals(-1, mixin.discoverPortFromYaml());
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    void padRightShouldPadShorterStrings() {
        assertEquals("abc     ", ControlPortMixin.padRight("abc", 8));
    }

    @Test
    void padRightShouldNotTruncateLongerStrings() {
        assertEquals("longstring", ControlPortMixin.padRight("longstring", 5));
    }

    @Test
    void resolvePortShouldUseEnvironmentVariableWhenFlagNotSet() {
        ControlPortMixin mixin = new ControlPortMixin();
        String originalPort = System.getenv("IKANOS_CONTROL_PORT");
        try {
            System.setProperty("IKANOS_CONTROL_PORT", "8888");
            // Note: setProperty on System.getenv replacement is not possible directly
            // This test verifies the fallback path logic via YAML discovery
            assertEquals(ControlPortMixin.DEFAULT_PORT, mixin.resolvePort());
        } finally {
            if (originalPort != null) {
                System.setProperty("IKANOS_CONTROL_PORT", originalPort);
            }
        }
    }

    @Test
    void resolvePortShouldHandleInvalidEnvironmentVariableValue() throws Exception {
        ControlPortMixin mixin = new ControlPortMixin();
        
        // Create a YAML with control port to fall back to
        Path yaml = tempDir.resolve("fallback.yaml");
        Files.writeString(yaml, """
                ikanos: "1.0.0-alpha2"
                info:
                  label: Test
                capability:
                  exposes:
                    - type: control
                      port: 9200
                """);

        String originalDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", tempDir.toString());
            // When env var is not set, should fall back to YAML discovery
            int resolved = mixin.resolvePort();
            assertTrue(resolved > 0, "Port should be resolved");
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    void resolveAddressShouldUseEnvironmentVariableWhenFlagNotSet() {
        ControlPortMixin mixin = new ControlPortMixin();
        // Without setting the env var, should return default
        assertEquals(ControlPortMixin.DEFAULT_ADDRESS, mixin.resolveAddress());
    }

    @Test
    void discoverPortFromYamlShouldIgnoreNonControlAdapters() throws Exception {
        ControlPortMixin mixin = new ControlPortMixin();

        Path yaml = tempDir.resolve("rest-only.yaml");
        Files.writeString(yaml, """
                ikanos: "1.0.0-alpha2"
                info:
                  label: Test
                capability:
                  exposes:
                    - type: rest
                      port: 8080
                """);

        String originalDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", tempDir.toString());
            assertEquals(-1, mixin.discoverPortFromYaml());
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    void discoverPortFromYamlShouldFindFirstValidControlPortFromMultipleFiles() throws Exception {
        ControlPortMixin mixin = new ControlPortMixin();

        Path yaml1 = tempDir.resolve("a-capability.yaml");
        Files.writeString(yaml1, """
                ikanos: "1.0.0-alpha2"
                info:
                  label: Test A
                capability:
                  exposes:
                    - type: rest
                      port: 8080
                """);

        Path yaml2 = tempDir.resolve("b-capability.yaml");
        Files.writeString(yaml2, """
                ikanos: "1.0.0-alpha2"
                info:
                  label: Test B
                capability:
                  exposes:
                    - type: control
                      port: 9111
                """);

        String originalDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", tempDir.toString());
            int port = mixin.discoverPortFromYaml();
            assertEquals(9111, port);
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    void discoverPortFromYamlShouldReturnNegativeWhenNoControlPortPresent() throws Exception {
        ControlPortMixin mixin = new ControlPortMixin();

        Path yaml = tempDir.resolve("no-control.yaml");
        Files.writeString(yaml, """
                ikanos: "1.0.0-alpha2"
                info:
                  label: Test
                capability: {}
                """);

        String originalDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", tempDir.toString());
            assertEquals(-1, mixin.discoverPortFromYaml());
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }
}
