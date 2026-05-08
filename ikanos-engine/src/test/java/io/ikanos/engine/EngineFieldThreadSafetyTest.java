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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.ikanos.Capability;
import io.ikanos.engine.consumes.ClientAdapter;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.engine.scripting.ScriptStepExecutor;

/**
 * Meta-test for SonarQube rule {@code java:S3077} — Phase 3 of the Sonar bug remediation
 * blueprint. Verifies that every engine-layer class listed in issue #424 has migrated away
 * from the {@code volatile} keyword to {@link java.util.concurrent.atomic.AtomicReference}
 * (or the initialization-on-demand holder idiom for true singletons).
 *
 * <p>This test deliberately uses reflection because the migration target is a structural
 * property of the class itself (no field may be {@code volatile}), not a behavioral
 * outcome of any single method. It guards against regressions where a future contributor
 * re-introduces {@code volatile} on a non-thread-safe type.
 *
 * <p>Behavioral assertions for the migrated classes are covered by the existing extensive
 * test suite — every touched class is exercised by at least one engine integration test.
 */
class EngineFieldThreadSafetyTest {

    /**
     * The 5 engine classes covered by Phase 3 (issue #424). Spec POJOs (Phase 2) and
     * {@code char[]} auth password fields (Phase 4) are tracked separately.
     */
    private static Stream<Class<?>> phase3EngineClasses() {
        return Stream.of(
                Capability.class,
                ClientAdapter.class,
                McpServerAdapter.class,
                TelemetryBootstrap.class,
                ScriptStepExecutor.class);
    }

    @ParameterizedTest(name = "{0} should declare no volatile fields")
    @MethodSource("phase3EngineClasses")
    @DisplayName("Phase 3 engine classes must not use volatile (S3077)")
    void engineClassShouldNotDeclareVolatileFields(Class<?> engineClass) {
        List<String> volatileFields = new ArrayList<>();
        for (Field field : engineClass.getDeclaredFields()) {
            if (Modifier.isVolatile(field.getModifiers())) {
                volatileFields.add(field.getName() + " : " + field.getType().getSimpleName());
            }
        }
        assertEquals(
                List.of(),
                volatileFields,
                () -> engineClass.getSimpleName()
                        + " still declares volatile fields (S3077). "
                        + "Migrate each one to AtomicReference<T> with an immutable snapshot, or "
                        + "use the initialization-on-demand holder idiom for true singletons. "
                        + "See sonar-bug-remediation.md, Phase 3.");
    }
}
