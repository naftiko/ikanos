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
package io.naftiko.engine;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.spec.ExternalRefSpec;
import io.naftiko.spec.FileExternalRefSpec;
import io.naftiko.spec.RuntimeExternalRefSpec;

/**
 * Resolver for external references that supports both file-based and runtime-based injection.
 * File-based refs load configuration from JSON/YAML files.
 * Runtime-based refs extract values from environment variables or system properties.
 */
public class ExternalRefResolver {

    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;

    public ExternalRefResolver() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.jsonMapper = new ObjectMapper();
    }

    /**
     * Resolves all external references and returns a map of injected variables.
     * Variables are keyed by their injection name (e.g., "notion_token").
     * 
     * @param externalRefs List of external reference specifications to resolve
     * @param capabilityDir Directory containing the capability file, used as base for relative URIs
     * @return Map of variable name to resolved value
     * @throws IOException if file-based ref cannot be read
     * @throws IllegalArgumentException if ref is invalid or cannot be resolved
     */
    public Map<String, Object> resolveExternalRefs(List<ExternalRefSpec> externalRefs,
            String capabilityDir) throws IOException {
        Map<String, Object> injectedVars = new HashMap<>();

        if (externalRefs == null || externalRefs.isEmpty()) {
            return injectedVars;
        }

        for (ExternalRefSpec ref : externalRefs) {
            if (ref instanceof FileExternalRefSpec) {
                resolveFileRef((FileExternalRefSpec) ref, injectedVars, capabilityDir);
            } else if (ref instanceof RuntimeExternalRefSpec) {
                resolveRuntimeRef((RuntimeExternalRefSpec) ref, injectedVars);
            }
        }

        return injectedVars;
    }

    /**
     * Resolves a file-based external reference by reading the specified file and extracting
     * variables according to the keys mapping. Supports JSON, YAML, and Java properties file formats.
     * 
     * @param ref The file-based external reference specification
     * @param injectedVars Map to populate with resolved variables
     * @param capabilityDir Base directory for relative file paths
     * @throws IOException if file cannot be read
     */
    private void resolveFileRef(FileExternalRefSpec ref, Map<String, Object> injectedVars,
            String capabilityDir) throws IOException {
        String uri = ref.getUri();
        if (uri == null || uri.isEmpty()) {
            throw new IllegalArgumentException("File external reference '" + ref.getName()
                    + "' requires a 'uri' field");
        }

        // Resolve relative paths against the capability directory
        Path filePath = Paths.get(uri);
        if (!filePath.isAbsolute() && capabilityDir != null && !capabilityDir.isEmpty()) {
            filePath = Paths.get(capabilityDir, uri);
        }

        // Read the file
        File file = filePath.toFile();
        if (!file.exists()) {
            throw new IOException("External ref file not found: " + file.getAbsolutePath());
        }

        // Route to appropriate parser based on file extension
        if (uri.endsWith(".properties")) {
            resolvePropertiesRef(file, ref, injectedVars);
        } else {
            resolveJsonOrYamlRef(file, uri, ref, injectedVars);
        }
    }

    /**
     * Resolves variables from a Java properties file.
     * 
     * @param file The properties file to read
     * @param ref The external reference specification
     * @param injectedVars Map to populate with resolved variables
     * @throws IOException if the properties file cannot be read
     */
    private void resolvePropertiesRef(File file, FileExternalRefSpec ref,
            Map<String, Object> injectedVars) throws IOException {
        Properties props = new Properties();
        try (FileReader reader = new FileReader(file)) {
            props.load(reader);
        }

        // Extract variables according to keys mapping
        for (Map.Entry<String, String> keyMapping : ref.getKeys().entrySet()) {
            String injectionKey = keyMapping.getKey(); // e.g., "api_token"
            String sourceKey = keyMapping.getValue(); // e.g., "API_TOKEN"
            String value = props.getProperty(sourceKey);

            if (value == null) {
                throw new IllegalArgumentException("Key '" + sourceKey
                        + "' not found in external ref properties file: " + file.getAbsolutePath());
            }else if (injectedVars.containsKey(sourceKey)) {
                throw new IllegalArgumentException("Key '" + sourceKey
                        + "' is already defined in injected variables, cannot override with value from: "
                        + file.getAbsolutePath());
            }

            injectedVars.put(injectionKey, value);
        }
    }

    /**
     * Resolves variables from JSON or YAML files.
     * 
     * @param file The JSON or YAML file to read
     * @param uri The file URI (used to detect format)
     * @param ref The external reference specification
     * @param injectedVars Map to populate with resolved variables
     * @throws IOException if the file cannot be read
     */
    private void resolveJsonOrYamlRef(File file, String uri, FileExternalRefSpec ref,
            Map<String, Object> injectedVars) throws IOException {
        // Parse JSON or YAML based on file extension
        ObjectMapper mapper = uri.endsWith(".json") ? jsonMapper : yamlMapper;
        JsonNode root = mapper.readTree(file);

        // Extract variables according to keys mapping
        for (Map.Entry<String, String> keyMapping : ref.getKeys().entrySet()) {
            String injectionKey = keyMapping.getKey(); // e.g., "notion_token"
            String sourceKey = keyMapping.getValue(); // e.g., "NOTION_INTEGRATION_TOKEN"
            JsonNode value = root.at("/" + sourceKey.replace(".", "/"));
            
            if (value != null && !value.isMissingNode() && !value.isNull()) {
                if (injectedVars.containsKey(injectionKey)) {
                    throw new IllegalArgumentException("Key '" + injectionKey
                            + "' is already defined in injected variables, cannot override with value from: "
                            + file.getAbsolutePath());
                }
                
                injectedVars.put(injectionKey, value.asText());
            } else {
                throw new IllegalArgumentException("Key '" + sourceKey
                        + "' not found in external ref file: " + file.getAbsolutePath());
            }
        }
    }

    /**
     * Resolves a runtime-based external reference by extracting variables from environment
     * variables or system properties.
     * 
     * @param ref The runtime-based external reference specification
     * @param injectedVars Map to populate with resolved variables
     */
    private void resolveRuntimeRef(RuntimeExternalRefSpec ref, Map<String, Object> injectedVars) {
        // Extract variables according to keys mapping
        for (Map.Entry<String, String> keyMapping : ref.getKeys().entrySet()) {
            String injectionKey = keyMapping.getKey(); // e.g., "aws_access_key"
            String sourceKey = keyMapping.getValue(); // e.g., "AWS_ACCESS_KEY_ID"

            // Try environment variable first, then system property
            String value = System.getenv(sourceKey);
            if (value == null) {
                value = System.getProperty(sourceKey);
            }

            if (value == null) {
                throw new IllegalArgumentException(
                        "Runtime external ref '" + ref.getName()
                                + "' requires environment variable or system property: "
                                + sourceKey);
            }

            injectedVars.put(injectionKey, value);
        }
    }

}
