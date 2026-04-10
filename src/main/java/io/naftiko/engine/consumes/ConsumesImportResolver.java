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
package io.naftiko.engine.consumes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.consumes.ClientSpec;
import io.naftiko.spec.consumes.HttpClientSpec;
import io.naftiko.spec.consumes.ImportedConsumesHttpSpec;

/**
 * Resolver for global consumes imports.
 * Loads standalone consumes files and resolves imported adapters.
 * 
 * Design:
 * - Detects imports by ClassType (ImportedConsumesHttpSpec)
 * - Loads source consumes file (standalone YAML with 'consumes' array at root)
 * - Finds matching namespace in source file
 * - Replaces ImportedConsumesHttpSpec with resolved HttpClientSpec
 * - Supports 'as' alias for namespace disambiguation
 */
public class ConsumesImportResolver {

    private final ObjectMapper yamlMapper;

    public ConsumesImportResolver() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Resolves all imports in a capability's consumes array.
     * Mutates the consumes list in-place, replacing imports with resolved clients.
     * 
     * @param consumes List of client specs (may contain imports)
     * @param capabilityDir Directory containing the capability file (for relative path resolution)
     * @throws IOException if import file cannot be loaded or namespace not found
     */
    public void resolveImports(List<ClientSpec> consumes, String capabilityDir) throws IOException {
        if (consumes == null || consumes.isEmpty()) {
            return;
        }

        // Process all imports and replace in-place
        for (int i = 0; i < consumes.size(); i++) {
            ClientSpec client = consumes.get(i);

            if (client instanceof ImportedConsumesHttpSpec) {
                ImportedConsumesHttpSpec importSpec = (ImportedConsumesHttpSpec) client;

                // Resolve the import
                HttpClientSpec resolved = resolveImport(importSpec, capabilityDir);

                // Replace in list
                consumes.set(i, resolved);
            }
        }
    }

    /**
     * Resolves a single import by loading the source consumes file and finding the namespace.
     * 
     * @param importSpec The import specification
     * @param capabilityDir Directory containing the importing capability
     * @return Resolved HttpClientSpec with effective namespace set
     * @throws IOException if file cannot be loaded or namespace not found
     */
    private HttpClientSpec resolveImport(ImportedConsumesHttpSpec importSpec, String capabilityDir)
            throws IOException {
        String location = importSpec.getLocation();
        String importNamespace = importSpec.getImportNamespace();
        String alias = importSpec.getAlias();

        if (location == null || location.isEmpty()) {
            throw new IOException("Import 'location' is required");
        }
        if (importNamespace == null || importNamespace.isEmpty()) {
            throw new IOException("Import 'import' (namespace) is required");
        }

        // Resolve path: relative to capability directory
        Path sourcePath = resolvePath(location, capabilityDir);
        if (!Files.exists(sourcePath)) {
            throw new IOException("Import source file not found: " + sourcePath);
        }

        // Load source consumes file
        NaftikoSpec sourceSpec = loadConsumesFile(sourcePath);

        // Find matching namespace in source
        HttpClientSpec sourceClient = findNamespace(sourceSpec, importNamespace);
        if (sourceClient == null) {
            throw new IOException(
                String.format(
                    "Namespace '%s' not found in source consumes file: %s",
                    importNamespace,
                    sourcePath
                )
            );
        }

        // Create a copy of the resolved client with effective namespace
        HttpClientSpec resolved = copyHttpClientSpec(sourceClient);
        if (alias != null && !alias.isEmpty()) {
            resolved.setNamespace(alias);
        }

        return resolved;
    }

    /**
     * Resolves an import path relative to the capability directory.
     * Examples:
     * - "./shared-adapters.consumes.yml" → /path/to/parent/shared-adapters.consumes.yml
     * - "../shared/notion.yml" → /path/to/shared/notion.yml
     * 
     * @param location The location string from the import
     * @param capabilityDir The directory of the importing capability (null = use current dir)
     * @return Absolute path to the import source file
     */
    private Path resolvePath(String location, String capabilityDir) {
        Path basePath = (capabilityDir != null && !capabilityDir.isEmpty())
            ? Paths.get(capabilityDir)
            : Paths.get(".");

        return basePath.resolve(location).normalize().toAbsolutePath();
    }

    /**
     * Loads a standalone consumes file.
     * The file should have 'consumes' array at root (no 'capability' key).
     * 
     * @param filePath Path to the consumes file
     * @return NaftikoSpec with consumes array populated (capability is null)
     * @throws IOException if file cannot be read or parsed
     */
    private NaftikoSpec loadConsumesFile(Path filePath) throws IOException {
        try {
            NaftikoSpec spec = yamlMapper.readValue(filePath.toFile(), NaftikoSpec.class);

            if (spec.getConsumes() == null || spec.getConsumes().isEmpty()) {
                throw new IOException("Consumes file has no 'consumes' array: " + filePath);
            }

            return spec;
        } catch (IOException e) {
            throw new IOException("Failed to load consumes file: " + filePath + " - " + e.getMessage(), e);
        }
    }

    /**
     * Finds a namespace in the consumes array.
     * Returns the first HttpClientSpec matching the namespace.
     * 
     * @param spec The loaded NaftikoSpec
     * @param namespace The namespace to find
     * @return HttpClientSpec if found, null otherwise
     */
    private HttpClientSpec findNamespace(NaftikoSpec spec, String namespace) {
        if (spec.getConsumes() == null) {
            return null;
        }

        return spec.getConsumes().stream()
            .filter(client -> client instanceof HttpClientSpec)
            .map(client -> (HttpClientSpec) client)
            .filter(client -> namespace.equals(client.getNamespace()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Creates a deep copy of an HttpClientSpec.
     * This prevents mutations to the original source spec.
     * 
     * Implementation: serialize/deserialize via Jackson.
     * 
     * @param original The spec to copy
     * @return A new independent copy
     * @throws IOException if serialization fails
     */
    private HttpClientSpec copyHttpClientSpec(HttpClientSpec original) throws IOException {
        // Serialize and deserialize to create independent copy
        String yaml = yamlMapper.writeValueAsString(original);
        return yamlMapper.readValue(yaml, HttpClientSpec.class);
    }
}
