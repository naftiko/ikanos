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
package io.ikanos.engine.exposes.skill;

import java.nio.file.Path;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.restlet.data.MediaType;
import org.restlet.representation.Representation;
import org.restlet.resource.ServerResource;
import org.restlet.service.MetadataService;
import io.ikanos.engine.observability.RestletHeaderGetter;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.engine.util.SafePathResolver;
import io.ikanos.spec.exposes.skill.ExposedSkillSpec;
import io.ikanos.spec.exposes.skill.SkillServerSpec;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import javax.annotation.Nonnull;

/**
 * Abstract base for all skill server handler resources.
 *
 * <p>Provides shared utilities for context attribute access, skill lookup, path traversal
 * validation, and MIME type detection. Each subclass is instantiated per request by Restlet's
 * routing layer, receiving dependencies from the shared {@link org.restlet.Context} that is
 * populated by {@link SkillServerAdapter} at construction time.</p>
 */
abstract class SkillServerResource extends ServerResource {

    private static final MetadataService METADATA_SERVICE = new MetadataService();

    private final ObjectMapper mapper = new ObjectMapper();

    public ObjectMapper getMapper() {
        return mapper;
    }

    @Override
    public Representation handle() {
        TelemetryBootstrap telemetry = TelemetryBootstrap.get();
        io.opentelemetry.context.Context extractedContext = java.util.Objects.requireNonNull(
                telemetry.getOpenTelemetry()
                        .getPropagators().getTextMapPropagator()
                .extract(currentTelemetryContext(), getRequest(),
                    restletHeaderGetter()));

        String operationId = getRequest().getResourceRef() != null
                ? getRequest().getResourceRef().getPath() : "unknown";
        String capabilityName = (String) getContext().getAttributes().get("capabilityName");
        Span span = telemetry.startServerSpan("skill", operationId, extractedContext,
                getRequest().getMethod().getName(), capabilityName);

        long startNanos = System.nanoTime();
        boolean error = false;
        try (Scope scope = span.makeCurrent()) {
            return super.handle();
        } catch (Exception e) {
            error = true;
            TelemetryBootstrap.recordError(span, e);
            telemetry.getMetrics().recordRequestError("skill", operationId,
                    e.getClass().getSimpleName());
            throw e;
        } finally {
            double durationSec = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            String status = error ? "500"
                    : getResponse() != null && getResponse().getStatus() != null
                            ? String.valueOf(getResponse().getStatus().getCode()) : "200";
            telemetry.getMetrics().recordRequest("skill", operationId, status, durationSec);
            TelemetryBootstrap.endSpan(span);
        }
    }

    @Nonnull
    private io.opentelemetry.context.Context currentTelemetryContext() {
        return java.util.Objects.requireNonNull(io.opentelemetry.context.Context.current());
    }

    @Nonnull
    private TextMapGetter<org.restlet.Request> restletHeaderGetter() {
        return java.util.Objects.requireNonNull(RestletHeaderGetter.INSTANCE);
    }

    protected SkillServerSpec getSkillServerSpec() {
        return (SkillServerSpec) getContext().getAttributes().get("skillServerSpec");
    }

    @SuppressWarnings("unchecked")
    protected Map<String, String> getNamespaceMode() {
        return (Map<String, String>) getContext().getAttributes().get("namespaceMode");
    }

    protected ExposedSkillSpec findSkill(String name) {
        if (name == null) {
            return null;
        }
        for (ExposedSkillSpec skill : getSkillServerSpec().getSkills()) {
            if (name.equals(skill.getName())) {
                return skill;
            }
        }
        return null;
    }

    /**
     * Resolves {@code file} relative to the given {@code locationUri} and validates that the
     * resolved path stays within the location root (path traversal protection).
     *
     * @param locationUri {@code file:///} URI of the skill's location directory
     * @param file        relative file path (e.g. {@code "README.md"} or
     *                    {@code "examples/usage.md"})
     * @return the resolved absolute path
     * @throws SecurityException if any path segment is unsafe or the resolved path escapes the root
     */
    protected Path resolveAndValidate(String locationUri, String file) {
        return SafePathResolver.resolveAndValidate(locationUri, file);
    }

    protected MediaType detectMediaType(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot >= 0) {
            String extension = filename.substring(dot + 1);
            MediaType type = METADATA_SERVICE.getMediaType(extension);
            if (type != null) {
                return type;
            }
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
