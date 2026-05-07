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
package io.naftiko.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.naftiko.spec.aggregates.AggregateFunctionSpec;
import io.naftiko.spec.aggregates.AggregateSpec;
import io.naftiko.spec.consumes.http.HttpClientOperationSpec;
import io.naftiko.spec.consumes.http.HttpClientResourceSpec;
import io.naftiko.spec.consumes.http.HttpClientSpec;
import io.naftiko.spec.consumes.http.OAuth2AuthenticationSpec;
import io.naftiko.spec.exposes.ServerCallSpec;
import io.naftiko.spec.exposes.ServerSpec;
import io.naftiko.spec.exposes.control.ControlManagementSpec;
import io.naftiko.spec.exposes.control.ControlServerSpec;
import io.naftiko.spec.exposes.mcp.McpServerResourceSpec;
import io.naftiko.spec.exposes.mcp.McpServerToolSpec;
import io.naftiko.spec.exposes.rest.RestServerOperationSpec;
import io.naftiko.spec.exposes.rest.RestServerResourceSpec;
import io.naftiko.spec.exposes.rest.RestServerStepSpec;
import io.naftiko.spec.exposes.skill.ExposedSkillSpec;
import io.naftiko.spec.exposes.skill.SkillToolSpec;
import io.naftiko.spec.observability.ObservabilityExportersSpec;
import io.naftiko.spec.observability.ObservabilityMetricsSpec;
import io.naftiko.spec.observability.ObservabilitySpec;
import io.naftiko.spec.observability.ObservabilityTracesSpec;
import io.naftiko.spec.scripting.OperationStepScriptSpec;
import io.naftiko.spec.util.BindingSpec;
import io.naftiko.spec.util.OperationStepCallSpec;
import io.naftiko.spec.util.StructureSpec;

/**
 * Meta-test for SonarQube rule {@code java:S3077} — Phase 2 of the Sonar bug remediation
 * blueprint. Verifies that every spec POJO listed in issue #422 has migrated away from the
 * {@code volatile} keyword to {@link java.util.concurrent.atomic.AtomicReference} holding an
 * immutable snapshot.
 *
 * <p>This test deliberately uses reflection because the migration target is a structural
 * property of the class itself (no field may be {@code volatile}), not a behavioral
 * outcome of any single method. It guards against regressions where a future contributor
 * re-introduces {@code volatile} on a non-thread-safe type.
 *
 * <p>Behavioral assertions for the migrated classes (Jackson round-trip, defensive copy
 * of incoming collections) are covered by the existing extensive test suite — every
 * touched class is exercised by at least one round-trip test.
 */
class SpecFieldThreadSafetyTest {

    /**
     * The 27 spec POJO classes covered by Phase 2 (issue #422). Engine-layer classes
     * ({@code Capability}, etc.) and {@code char[]} auth password fields are tracked
     * separately in Phase 3 and Phase 4.
     */
    private static Stream<Class<?>> phase2SpecClasses() {
        return Stream.of(
                NaftikoSpec.class,
                OperationSpec.class,
                AggregateFunctionSpec.class,
                AggregateSpec.class,
                HttpClientSpec.class,
                HttpClientResourceSpec.class,
                HttpClientOperationSpec.class,
                OAuth2AuthenticationSpec.class,
                ServerSpec.class,
                ServerCallSpec.class,
                ControlServerSpec.class,
                ControlManagementSpec.class,
                McpServerResourceSpec.class,
                McpServerToolSpec.class,
                RestServerResourceSpec.class,
                RestServerOperationSpec.class,
                RestServerStepSpec.class,
                ExposedSkillSpec.class,
                SkillToolSpec.class,
                ObservabilitySpec.class,
                ObservabilityTracesSpec.class,
                ObservabilityMetricsSpec.class,
                ObservabilityExportersSpec.class,
                OperationStepScriptSpec.class,
                BindingSpec.class,
                OperationStepCallSpec.class,
                StructureSpec.class);
    }

    @ParameterizedTest(name = "{0} should declare no volatile fields")
    @MethodSource("phase2SpecClasses")
    @DisplayName("Phase 2 spec POJOs must not use volatile (S3077)")
    void specClassShouldNotDeclareVolatileFields(Class<?> specClass) {
        List<String> volatileFields = new ArrayList<>();
        for (Field field : specClass.getDeclaredFields()) {
            if (Modifier.isVolatile(field.getModifiers())) {
                volatileFields.add(field.getName() + " : " + field.getType().getSimpleName());
            }
        }
        assertEquals(
                List.of(),
                volatileFields,
                () -> specClass.getSimpleName()
                        + " still declares volatile fields (S3077). "
                        + "Migrate each one to AtomicReference<T> with an immutable snapshot. "
                        + "See sonar-bug-remediation.md, Phase 2, Pattern A/B/C.");
    }

    @ParameterizedTest(name = "{0} default constructor should not throw")
    @MethodSource("phase2SpecClasses")
    @DisplayName("Phase 2 spec POJOs must remain constructible by Jackson")
    void specClassShouldHaveUsableDefaultConstructor(Class<?> specClass) throws Exception {
        if (Modifier.isAbstract(specClass.getModifiers())) {
            return; // abstract bases (e.g. ServerSpec) are instantiated through subclasses
        }
        // Jackson requires a no-arg constructor. The migration must not break it.
        Object instance = specClass.getDeclaredConstructor().newInstance();
        assertTrue(
                specClass.isInstance(instance),
                () -> "Default constructor of " + specClass.getSimpleName() + " did not return an instance");
    }
}
