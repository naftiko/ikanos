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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class VersionHelper {
    private static final String ENGINE_VERSION;

    // ikanos-schema.json must be on the classpath. Any module that transitively depends on
    // ikanos-spec must bundle this resource (it ships under ikanos-spec/src/main/resources/schemas/),
    // otherwise the static initializer below throws ExceptionInInitializerError, crashing the JVM
    // at startup for every consumer.
    private static final String SCHEMA_VERSION;

    static {
        // Initialize ENGINE_VERSION by reading the "version" property from the parent pom.xml file.
        try (InputStream versionStream = VersionHelper.class.getClassLoader().getResourceAsStream("version.properties")) {
            Properties props = new Properties();
            props.load(versionStream);
            String v = props.getProperty("version");
            ENGINE_VERSION = v.endsWith("-SNAPSHOT") ? v.substring(0, v.length() - "-SNAPSHOT".length()) : v;
        } catch (IOException e) {
            throw new ExceptionInInitializerError("Could not load version.properties: " + e.getMessage());
        }

        // Initialize SCHEMA_VERSION by reading the "const" value of the "ikanos" property in the ikanos-schema.json file.
        try (InputStream schemaStream = VersionHelper.class.getClassLoader().getResourceAsStream("schemas/ikanos-schema.json")) {
            if (schemaStream == null) {
                throw new ExceptionInInitializerError("Could not find schemas/ikanos-schema.json on classpath");
            }
            JsonNode root = new ObjectMapper().readTree(schemaStream);
            JsonNode constNode = root.path("properties").path("ikanos").path("const");
            if (constNode.isMissingNode()) {
                throw new ExceptionInInitializerError("Could not find properties.ikanos.const in ikanos-schema.json");
            }
            SCHEMA_VERSION = constNode.asText();
        } catch (IOException e) {
            throw new ExceptionInInitializerError("Could not load ikanos-schema.json: " + e.getMessage());
        }
    }

    public static String getEngineVersion() {
        return ENGINE_VERSION;
    }

    public static String getSchemaVersion() {
        return SCHEMA_VERSION;
    }
}
