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
package io.ikanos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;
import io.ikanos.utils.TestUtils;

/**
 * A capability document declares its bindings under {@code capability.binds} only. Root
 * {@code binds}, {@code consumes}, {@code exposes} and {@code aggregates} belong to standalone
 * files, so the engine rejects them next to {@code capability} rather than ignore them (#955).
 */
class CapabilityBindsTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private static final String EXPOSES = """
              exposes:
                - type: "rest"
                  address: "localhost"
                  port: 0
                  namespace: "orders-api"
                  resources:
                    - path: "/orders"
                      operations:
                        - method: "GET"
                          name: "list-orders"
            """;

    @TempDir
    Path tempDir;

    @Test
    void bindingsShouldHoldValuesDeclaredUnderCapabilityBinds() throws Exception {
        Path secrets = tempDir.resolve("secrets.yaml");
        Files.writeString(secrets, "registry-bearer-token: \"inline-token\"\n");

        Capability capability = new Capability(TestUtils.parseYaml("""
                ikanos: "%s"
                capability:
                  binds:
                    - namespace: "registry-env"
                      location: "%s"
                      keys:
                        REGISTRY_TOKEN: "registry-bearer-token"
                %s""".formatted(IKANOS, secrets.toUri(), EXPOSES)));

        assertEquals("inline-token", capability.getBindings().get("REGISTRY_TOKEN"));
    }

    @Test
    void bindingsShouldHoldValuesImportedIntoCapabilityBinds() throws Exception {
        Path secrets = tempDir.resolve("secrets.yaml");
        Files.writeString(secrets, "registry-bearer-token: \"imported-token\"\n");
        Files.writeString(tempDir.resolve("shared.binds.yml"), """
                ikanos: "%s"
                binds:
                  - namespace: "registry-env"
                    location: "%s"
                    keys:
                      REGISTRY_TOKEN: "registry-bearer-token"
                """.formatted(IKANOS, secrets.toUri()));

        IkanosSpec spec = TestUtils.parseYaml("""
                ikanos: "%s"
                capability:
                  binds:
                    - from: "./shared.binds.yml"
                      import: "registry-env"
                %s""".formatted(IKANOS, EXPOSES));
        Capability capability = new Capability(spec, tempDir.toString());

        assertEquals("imported-token", capability.getBindings().get("REGISTRY_TOKEN"));
    }

    @Test
    void constructorShouldRejectBindNamespaceAlsoUsedByExposedAdapter() throws Exception {
        IkanosSpec spec = TestUtils.parseYaml("""
                ikanos: "%s"
                capability:
                  binds:
                    - namespace: "orders-api"
                      keys:
                        HOME_DIR: "PATH"
                %s""".formatted(IKANOS, EXPOSES));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Capability(spec));
        assertTrue(error.getMessage().contains("capability.binds[0]"), error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"binds", "consumes", "exposes", "aggregates"})
    void constructorShouldRejectRootSectionNextToCapability(String section) throws Exception {
        IkanosSpec spec = TestUtils.parseYaml("""
                ikanos: "%s"
                %s:
                  - from: "./shared.%s.yml"
                    import: "shared"
                capability:
                %s""".formatted(IKANOS, section, section, EXPOSES));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Capability(spec, tempDir.toString()));
        assertTrue(error.getMessage().contains("capability." + section), error.getMessage());
    }
}
