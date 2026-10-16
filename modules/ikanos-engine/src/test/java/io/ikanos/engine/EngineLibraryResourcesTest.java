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
package io.ikanos.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Regression test for #801: the engine is a library, so its jar must not carry a root Logback
 * configuration that competes with the embedding application's own.
 *
 * <p>Checks {@code target/classes}, which is exactly what the {@code ikanos-engine} jar packages.
 * The runtime configuration belongs to {@code ikanos-cli} ({@code ikanos.jar}, Docker).</p>
 */
class EngineLibraryResourcesTest {

    private static final Path ENGINE_CLASSES = Path.of("target", "classes");

    @Test
    void engineJarShouldNotShipRootLogbackConfiguration() {
        assertTrue(Files.isDirectory(ENGINE_CLASSES.resolve("io")),
                "test setup: expected the engine's compiled classes in " + ENGINE_CLASSES.toAbsolutePath());

        for (String name : new String[] {"logback.xml", "logback-test.xml", "logback.groovy"}) {
            assertFalse(Files.exists(ENGINE_CLASSES.resolve(name)),
                    "#801: ikanos-engine packages a root " + name + "; a library must leave Logback "
                            + "configuration to the application (ikanos-cli owns the runtime one)");
        }
    }
}
