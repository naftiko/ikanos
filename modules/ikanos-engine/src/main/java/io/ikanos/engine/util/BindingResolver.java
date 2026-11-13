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
package io.ikanos.engine.util;

import java.io.IOException;
import java.lang.Iterable;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.restlet.Context;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.spec.util.BindingSpec;
import io.ikanos.spec.util.BindingKeysSpec;
import io.ikanos.spec.util.ExecutionContext;
import io.ikanos.spec.util.IkanosMappers;

/**
 * Resolver for bindings that supports both file-based and runtime-based injection. File-based
 * bindings load configuration from JSON/YAML files (when location is present). Runtime-based
 * bindings extract values from an ExecutionContext (when location is absent).
 */
public class BindingResolver {

    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;

    public BindingResolver() {
        this.yamlMapper = IkanosMappers.yaml();
        this.jsonMapper = IkanosMappers.json();
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
            resolved.putAll(resolveOne(binding, context));
        }

        return resolved;
    }

    /**
     * The bindings a capability renders its templates with: each key under its own name, as in
     * {@code {{CRM_TOKEN}}}, and under its bind's namespace, as in {@code {{secrets.CRM_TOKEN}}},
     * the form the examples, the schema and the rules teach (#942).
     *
     * <p>When two binds declare the same key, {@code {{KEY}}} gets the later one, as before; the
     * namespaced form tells them apart. A key named like a namespace keeps its value under that
     * name, so nothing that resolved before changes, and that bind is then only offered by key, with
     * a warning that names the namespace but never a value.</p>
     *
     * @param binds the bind specifications, may be null
     * @param context execution context for runtime bindings
     * @return a new mutable map: each key to its value, each namespace to its keys and values
     * @throws IOException if a file-based binding cannot be read
     */
    public Map<String, Object> resolveBindings(Iterable<BindingSpec> binds,
            ExecutionContext context) throws IOException {
        Map<String, Object> bindings = new HashMap<>();
        if (binds == null) {
            return bindings;
        }
        Map<String, Map<String, String>> byNamespace = new LinkedHashMap<>();
        for (BindingSpec binding : binds) {
            Map<String, String> values = resolveOne(binding, context);
            bindings.putAll(values);
            String namespace = binding.getNamespace();
            if (namespace != null && !namespace.isBlank()) {
                byNamespace.computeIfAbsent(namespace, name -> new LinkedHashMap<>())
                        .putAll(values);
            }
        }
        byNamespace.forEach((namespace, values) -> {
            if (bindings.containsKey(namespace)) {
                Context.getCurrentLogger().warning("The bind namespace '" + namespace
                        + "' has the name of a bound key, which keeps that name: write its keys as "
                        + String.join(", ", values.keySet().stream()
                                .map(key -> "{{" + key + "}}").toList())
                        + ", or rename the namespace or the key.");
                return;
            }
            bindings.put(namespace, Collections.unmodifiableMap(values));
        });
        return bindings;
    }

    private Map<String, String> resolveOne(BindingSpec binding, ExecutionContext context)
            throws IOException {
        return binding.getLocation() != null && !binding.getLocation().isEmpty()
                ? resolveFileBinding(binding)
                : resolveRuntimeBinding(binding, context);
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
        } catch (JacksonException | IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse bind file: " + e.getMessage(), e);
        }
    }

}
