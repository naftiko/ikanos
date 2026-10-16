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
package io.ikanos.spec.util;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.dialect.BasicDialectRegistry;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.keyword.NonValidationKeyword;
import com.networknt.schema.path.PathType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides the networknt {@link SchemaRegistry} used to validate Ikanos capabilities.
 *
 * <p>This is the same networknt engine as {@code SchemaRegistry.withDefaultDialect(...)}: the
 * only difference is that {@code name} is registered as an extra non-validating keyword. The
 * Ikanos schema uses the JSON Structure {@code name} annotation instead of {@code title}, and
 * networknt would otherwise log an "Unknown keyword name" warning on every run. No validation rule
 * is added, removed or relaxed.
 *
 * <p>Instance paths in validation errors use the {@link PathType#LEGACY} format ({@code $.info}),
 * which is what {@code ikanos validate} has always printed. json-schema-validator 2.x defaults to
 * JSON Pointer ({@code /info}).
 *
 * <p>Temporary: remove once schema validation migrates to Polychro.
 */
public final class IkanosMetaSchemaFactory {

    private static final Map<SpecificationVersion, SchemaRegistry> REGISTRIES =
            new ConcurrentHashMap<>();

    private static final SchemaRegistryConfig CONFIG =
            SchemaRegistryConfig.builder().pathType(PathType.LEGACY).build();

    private IkanosMetaSchemaFactory() {}

    /**
     * Returns a (cached) registry for the given JSON Schema version that recognizes the JSON
     * Structure {@code name} annotation.
     */
    public static SchemaRegistry getInstance(SpecificationVersion version) {
        return REGISTRIES.computeIfAbsent(version, IkanosMetaSchemaFactory::create);
    }

    private static SchemaRegistry create(SpecificationVersion version) {
        Dialect standard = switch (version) {
            case DRAFT_2020_12 -> Dialects.getDraft202012();
            case DRAFT_2019_09 -> Dialects.getDraft201909();
            case DRAFT_7 -> Dialects.getDraft7();
            case DRAFT_6 -> Dialects.getDraft6();
            case DRAFT_4 -> Dialects.getDraft4();
        };
        Dialect dialect = Dialect.builder(standard)
                .keyword(new NonValidationKeyword("name"))
                .build();
        return SchemaRegistry.withDefaultDialect(dialect, builder -> builder
                .dialectRegistry(new BasicDialectRegistry(dialect))
                .schemaRegistryConfig(CONFIG));
    }
}
