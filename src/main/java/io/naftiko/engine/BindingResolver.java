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
import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.spec.BindingSpec;
import io.naftiko.spec.BindingKeysSpec;
import io.naftiko.spec.ExecutionContext;

/**
 * Resolver for bindings that supports both file-based and runtime-based injection. File-based
 * bindings load configuration from JSON/YAML files (when location is present). Runtime-based
 * bindings extract values from an ExecutionContext (when location is absent).
 */
public class BindingResolver {

    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;

    public BindingResolver() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.jsonMapper = new ObjectMapper();
    }

    /**
     * Resolves all bindings and returns a map of resolved variables.
     * 
     * @param binds List of bind specifications to resolve
     * @param context Execution context for runtime variable resolution
     * @return Map of variable name to resolved value
     * @throws IOException if file-based binding cannot be read
     */
    public Map<String, String> resolve(Iterable<BindingSpec> binds, ExecutionContext context)
            throws IOException {
        Map<String, String> resolved = new HashMap<>();

        if (binds == null) {
            return resolved;
        }

        for (BindingSpec binding : binds) {
            if (binding.getLocation() != null && !binding.getLocation().isEmpty()) {
                Map<String, String> fileVars = resolveFileBinding(binding);
                resolved.putAll(fileVars);
            } else {
                Map<String, String> runtimeVars = resolveRuntimeBinding(binding, context);
                resolved.putAll(runtimeVars);
            }
        }

        return resolved;
    }

    /**
     * Resolves a file-based binding by reading the specified file and extracting variables
     * according to the keys mapping. Supports JSON and YAML formats.
     * 
     * @param binding The binding specification with a location URI
     * @return Map of variable name to resolved value
     * @throws IOException if file cannot be read or key is missing
     */
    public Map<String, String> resolveFileBinding(BindingSpec binding) throws IOException {
        if (binding.getLocation() == null || binding.getLocation().isEmpty()) {
            throw new IOException("Invalid bind: missing location");
        }

        BindingKeysSpec keysSpec = binding.getKeys();
        if (keysSpec == null || keysSpec.getKeys() == null || keysSpec.getKeys().isEmpty()) {
            throw new IOException("Invalid bind: missing keys");
        }

        // Parse file content
        Map<String, Object> fileContent = parseFileContent(binding.getLocation());

        // Extract variables using the key mappings
        Map<String, String> resolved = new HashMap<>();
        for (Map.Entry<String, String> mapping : keysSpec.getKeys().entrySet()) {
            String variableName = mapping.getKey();
            String fileKey = mapping.getValue();

            Object value = fileContent.get(fileKey);
            if (value == null) {
                throw new IOException("Invalid bind: key '" + fileKey + "' not found in file");
            }

            resolved.put(variableName, String.valueOf(value));
        }

        return resolved;
    }

    /**
     * Resolves a runtime-based binding by extracting variables from an ExecutionContext.
     * 
     * @param binding The bind specification without a location (runtime injection)
     * @param context The execution context providing variable values
     * @return Map of variable name to resolved value
     * @throws IOException if variable is missing
     */
    public Map<String, String> resolveRuntimeBinding(BindingSpec binding, ExecutionContext context)
            throws IOException {
        BindingKeysSpec keysSpec = binding.getKeys();
        if (keysSpec == null || keysSpec.getKeys() == null || keysSpec.getKeys().isEmpty()) {
            throw new IOException("Invalid bind: missing keys");
        }

        Map<String, String> resolved = new HashMap<>();
        for (Map.Entry<String, String> mapping : keysSpec.getKeys().entrySet()) {
            String variableName = mapping.getKey();
            String contextKey = mapping.getValue();

            String value = context.getVariable(contextKey);
            if (value == null) {
                throw new IOException(
                        "Invalid bind: context variable '" + contextKey + "' not found");
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
            Path filePath;
            if (uriString.startsWith("file:///./") || uriString.startsWith("file://./")) {
                // Relative path: resolve against current working directory
                String relative = uriString.replaceFirst("file:///\\./|file://\\./", "");
                filePath = Path.of(System.getProperty("user.dir")).resolve(relative);
            } else {
                filePath = Path.of(URI.create(uriString));
            }

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
            throw new IOException("Failed to parse bind file: " + e.getMessage(), e);
        }
    }

}
