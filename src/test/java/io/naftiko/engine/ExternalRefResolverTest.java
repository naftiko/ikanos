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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.naftiko.spec.ExecutionContext;
import io.naftiko.spec.ExternalRefKeysSpec;
import io.naftiko.spec.FileResolvedExternalRefSpec;
import io.naftiko.spec.RuntimeResolvedExternalRefSpec;

public class ExternalRefResolverTest {

    @TempDir
    Path tempDir;

    @Test
    public void resolveShouldLoadJsonAndYamlFileReferences() throws Exception {
        Path jsonFile = tempDir.resolve("secrets.json");
        Files.writeString(jsonFile, """
                {"NOTION_TOKEN":"json-token"}
                """);

        Path yamlFile = tempDir.resolve("secrets.yaml");
        Files.writeString(yamlFile, """
                API_KEY: yaml-key
                """);

        ExternalRefResolver resolver = new ExternalRefResolver();
        Map<String, String> resolved = resolver.resolve(List.of(
                fileRef(jsonFile, Map.of("notion_token", "NOTION_TOKEN")),
                fileRef(yamlFile, Map.of("api_key", "API_KEY"))),
                key -> null);

        assertEquals("json-token", resolved.get("notion_token"));
        assertEquals("yaml-key", resolved.get("api_key"));
    }

    @Test
    public void resolveFileReferenceShouldFailWhenMappedKeyIsMissing() throws Exception {
        Path jsonFile = tempDir.resolve("missing.json");
        Files.writeString(jsonFile, "{}\n");

        ExternalRefResolver resolver = new ExternalRefResolver();

        IOException error = assertThrows(IOException.class,
                () -> resolver.resolveFileReference(
                        fileRef(jsonFile, Map.of("notion_token", "NOTION_TOKEN"))));

        assertEquals("Invalid ExternalRef: key 'NOTION_TOKEN' not found in file",
                error.getMessage());
    }

    @Test
    public void resolveRuntimeReferenceShouldUseExecutionContextVariables() throws Exception {
        ExternalRefResolver resolver = new ExternalRefResolver();

        Map<String, String> resolved = resolver.resolve(
                List.of(runtimeRef(Map.of("api_key", "API_KEY"))),
                new ExecutionContext() {
                    @Override
                    public String getVariable(String key) {
                        return "API_KEY".equals(key) ? "runtime-value" : null;
                    }
                });

        assertEquals("runtime-value", resolved.get("api_key"));
    }

    @Test
    public void resolveRuntimeReferenceShouldFailWhenVariableIsMissing() {
        ExternalRefResolver resolver = new ExternalRefResolver();

        IOException error = assertThrows(IOException.class,
                () -> resolver.resolveRuntimeReference(runtimeRef(Map.of("api_key", "API_KEY")),
                        key -> null));

        assertEquals("Invalid ExternalRef: context variable 'API_KEY' not found",
                error.getMessage());
    }

    private static FileResolvedExternalRefSpec fileRef(Path path, Map<String, String> keys) {
        FileResolvedExternalRefSpec ref = new FileResolvedExternalRefSpec();
        ref.setUri(toResolverFriendlyFileUri(path));
        ref.setKeys(keysSpec(keys));
        return ref;
    }

    private static RuntimeResolvedExternalRefSpec runtimeRef(Map<String, String> keys) {
        RuntimeResolvedExternalRefSpec ref = new RuntimeResolvedExternalRefSpec();
        ref.setKeys(keysSpec(keys));
        return ref;
    }

    private static ExternalRefKeysSpec keysSpec(Map<String, String> keys) {
        ExternalRefKeysSpec spec = new ExternalRefKeysSpec();
        spec.setKeys(keys);
        return spec;
    }

    private static String toResolverFriendlyFileUri(Path path) {
        String normalized = path.toAbsolutePath().toString().replace('\\', '/');
        if (normalized.length() > 2 && normalized.charAt(1) == ':') {
            normalized = normalized.substring(2);
        }
        return "file://" + normalized;
    }
}