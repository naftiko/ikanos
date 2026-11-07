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
package io.ikanos.engine.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;
import io.ikanos.utils.TestUtils;

/**
 * An import source is read like the engine reads a document: a capability document's sections
 * under {@code capability} only, a standalone file's at its root. A root section next to
 * {@code capability} is never imported (#955).
 */
class ImportStrategyReadSectionTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    static Stream<Arguments> strategies() {
        return Stream.of(
                Arguments.of("consumes", new ConsumesImportStrategy()),
                Arguments.of("exposes", new ExposesImportStrategy()),
                Arguments.of("aggregates", new AggregatesImportStrategy()),
                Arguments.of("binds", new BindsImportStrategy()));
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void readSectionShouldIgnoreRootSectionOfCapabilityDocument(String section,
            ImportStrategy<?> strategy) throws Exception {
        IkanosSpec source = TestUtils.parseYaml("""
                ikanos: "%s"
                %s:
                  - from: "./a.yml"
                    import: "root-a"
                  - from: "./b.yml"
                    import: "root-b"
                capability:
                  %s:
                    - from: "./c.yml"
                      import: "capability-c"
                """.formatted(IKANOS, section, section));

        assertEquals(1, strategy.readSection(source).size());
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void readSectionShouldReadRootSectionOfStandaloneFile(String section,
            ImportStrategy<?> strategy) throws Exception {
        IkanosSpec source = TestUtils.parseYaml("""
                ikanos: "%s"
                %s:
                  - from: "./a.yml"
                    import: "root-a"
                  - from: "./b.yml"
                    import: "root-b"
                """.formatted(IKANOS, section));

        assertEquals(2, strategy.readSection(source).size());
    }
}
