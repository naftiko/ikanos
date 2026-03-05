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

import java.io.IOException;
import java.lang.Iterable;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.spec.ExecutionContext;
import io.naftiko.spec.ExternalRefKeysSpec;
import io.naftiko.spec.ExternalRefSpec;
import io.naftiko.spec.FileResolvedExternalRefSpec;
import io.naftiko.spec.RuntimeResolvedExternalRefSpec;

/**
 * Resolver for external references that supports both file-based and runtime-based injection.
 * File-based refs load configuration from JSON/YAML files.
 * Runtime-based refs extract values from an ExecutionContext (environment variables, secrets, etc.).
 */
public class ExternalRefResolver {

    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;

    public ExternalRefResolver() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.jsonMapper = new ObjectMapper();
    }

    /**
     * Resolves all external references and returns a map of resolved variables.
     * 
     * @param externalRefs List of external reference specifications to resolve
     * @param context Execution context for runtime variable resolution
     * @return Map of variable name to resolved value
     * @throws IOException if file-based ref cannot be read
     */
    public Map<String, String> resolve(Iterable<ExternalRefSpec> externalRefs, ExecutionContext context)
            throws IOException {
        Map<String, String> resolved = new HashMap<>();

        if (externalRefs == null) {
            return resolved;
        }

        for (ExternalRefSpec ref : externalRefs) {
            if (ref instanceof FileResolvedExternalRefSpec) {
                Map<String, String> fileVars = resolveFileReference((FileResolvedExternalRefSpec) ref);
                resolved.putAll(fileVars);
            } else if (ref instanceof RuntimeResolvedExternalRefSpec) {
                Map<String, String> runtimeVars = resolveRuntimeReference((RuntimeResolvedExternalRefSpec) ref,
                        context);
                resolved.putAll(runtimeVars);
            }
        }

        return resolved;
    }

    /**
     * Resolves a file-based external reference by reading the specified file and extracting variables
     * according to the keys mapping. Supports JSON and YAML formats.
     * 
     * @param ref The file-resolved external reference specification
     * @return Map of variable name to resolved value
     * @throws IOException if file cannot be read or key is missing
     */
    public Map<String, String> resolveFileReference(FileResolvedExternalRefSpec ref) throws IOException {
        if (ref.getUri() == null || ref.getUri().isEmpty()) {
            throw new IOException("Invalid ExternalRef: missing uri");
        }

        ExternalRefKeysSpec keysSpec = ref.getKeys();
        if (keysSpec == null || keysSpec.getKeys() == null || keysSpec.getKeys().isEmpty()) {
            throw new IOException("Invalid ExternalRef: missing keys");
        }

        // Parse file content
        Map<String, Object> fileContent = parseFileContent(ref.getUri());

        // Extract variables using the key mappings
        Map<String, String> resolved = new HashMap<>();
        for (Map.Entry<String, String> mapping : keysSpec.getKeys().entrySet()) {
            String variableName = mapping.getKey(); // e.g., "notion_token"
            String fileKey = mapping.getValue(); // e.g., "NOTION_TOKEN"

            Object value = fileContent.get(fileKey);
            if (value == null) {
                throw new IOException("Invalid ExternalRef: key '" + fileKey + "' not found in file");
            }

            resolved.put(variableName, String.valueOf(value));
        }

        return resolved;
    }

    /**
     * Resolves a runtime-based external reference by extracting variables from an ExecutionContext.
     * 
     * @param ref The runtime-resolved external reference specification
     * @param context The execution context providing variable values
     * @return Map of variable name to resolved value
     * @throws IOException if variable is missing
     */
    public Map<String, String> resolveRuntimeReference(RuntimeResolvedExternalRefSpec ref,
            ExecutionContext context) throws IOException {
        ExternalRefKeysSpec keysSpec = ref.getKeys();
        if (keysSpec == null || keysSpec.getKeys() == null || keysSpec.getKeys().isEmpty()) {
            throw new IOException("Invalid ExternalRef: missing keys");
        }

        Map<String, String> resolved = new HashMap<>();
        for (Map.Entry<String, String> mapping : keysSpec.getKeys().entrySet()) {
            String variableName = mapping.getKey(); // e.g., "api_key"
            String contextKey = mapping.getValue(); // e.g., "API_KEY"

            String value = context.getVariable(contextKey);
            if (value == null) {
                throw new IOException("Invalid ExternalRef: context variable '" + contextKey
                        + "' not found");
            }

            resolved.put(variableName, value);
        }

        return resolved;
    }

    /**
     * Parses file content (JSON or YAML) and returns a flat map of key-value pairs.
     * 
     * @param uriString The file URI (file:// format)
     * @return Map of keys to values
     * @throws IOException if file cannot be read or parsed
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFileContent(String uriString) throws IOException {
        try {
            URI uri = URI.create(uriString);
            Path filePath = Paths.get(uri.getPath());

            if (!Files.exists(filePath)) {
                throw new IOException("File not found: " + filePath);
            }

            String content = Files.readString(filePath);

            // Detect format by file extension
            if (uriString.endsWith(".yaml") || uriString.endsWith(".yml")) {
                Object parsed = yamlMapper.readValue(content, Object.class);
                if (parsed instanceof Map) {
                    return (Map<String, Object>) parsed;
                }
                throw new IOException("YAML file does not contain a map at root level");
            } else { // JSON by default
                Object parsed = jsonMapper.readValue(content, Object.class);
                if (parsed instanceof Map) {
                    return (Map<String, Object>) parsed;
                }
                throw new IOException("JSON file does not contain an object at root level");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse external ref file: " + e.getMessage(), e);
        }
    }

}
