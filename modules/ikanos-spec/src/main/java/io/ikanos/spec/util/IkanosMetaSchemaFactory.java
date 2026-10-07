/*
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
package io.ikanos.spec.util;

import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.NonValidationKeyword;
import com.networknt.schema.SpecVersion.VersionFlag;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides the networknt {@link JsonSchemaFactory} used to validate Ikanos capabilities.
 *
 * <p>This is the same networknt engine as {@code JsonSchemaFactory.getInstance(...)}: the only
 * difference is that {@code name} is registered as an extra non-validating keyword. The Ikanos
 * schema uses the JSON Structure {@code name} annotation instead of {@code title}, and networknt
 * would otherwise log an "Unknown keyword name" warning on every run. No validation rule is added,
 * removed or relaxed.
 *
 * <p>Temporary: remove once schema validation migrates to Polychro.
 */
public final class IkanosMetaSchemaFactory {

    private static final Map<VersionFlag, JsonSchemaFactory> FACTORIES = new ConcurrentHashMap<>();

    private IkanosMetaSchemaFactory() {}

    /**
     * Returns a (cached) factory for the given JSON Schema version that recognizes the JSON
     * Structure {@code name} annotation.
     */
    public static JsonSchemaFactory getInstance(VersionFlag version) {
        return FACTORIES.computeIfAbsent(version, IkanosMetaSchemaFactory::create);
    }

    private static JsonSchemaFactory create(VersionFlag version) {
        JsonMetaSchema standard = JsonSchemaFactory.checkVersion(version).getInstance();
        JsonMetaSchema metaSchema = JsonMetaSchema.builder(standard.getUri(), standard)
                .addKeyword(new NonValidationKeyword("name"))
                .build();
        return JsonSchemaFactory.builder()
                .defaultMetaSchemaURI(metaSchema.getUri())
                .addMetaSchema(metaSchema)
                .build();
    }
}
