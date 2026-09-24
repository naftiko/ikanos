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

/** Temporary networknt configuration until schema validation migrates to Polychro. */
public final class SchemaValidatorFactory {

    private SchemaValidatorFactory() {}

    /** Creates a validator factory that recognizes the JSON Structure {@code name} annotation. */
    public static JsonSchemaFactory getInstance(VersionFlag version) {
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
