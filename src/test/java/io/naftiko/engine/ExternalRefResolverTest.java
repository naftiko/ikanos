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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.naftiko.spec.ExternalRefSpec;
import io.naftiko.spec.FileExternalRefSpec;
import io.naftiko.spec.RuntimeExternalRefSpec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for ExternalRefResolver engine component.
 * Validates file-based and runtime-based external reference resolution.
 */
public class ExternalRefResolverTest {

    private ExternalRefResolver resolver;

    @BeforeEach
    public void setUp() {
        resolver = new ExternalRefResolver();
    }

    @Test
    public void testResolveFileExternalRefJson(@TempDir Path tempDir) throws Exception {
        // Create a JSON file with credentials
        String jsonContent = """
                {
                  "NOTION_INTEGRATION_TOKEN": "secret-notion-token-123",
                  "DATABASE_ID": "notion-db-456"
                }
                """;
        
        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, jsonContent);

        // Create file ref spec
        FileExternalRefSpec fileRef = new FileExternalRefSpec();
        fileRef.setName("notion-config");
        fileRef.setUri("config.json");
        fileRef.getKeys().put("notion_token", "NOTION_INTEGRATION_TOKEN");
        fileRef.getKeys().put("db_id", "DATABASE_ID");

        List<ExternalRefSpec> refs = new ArrayList<>();
        refs.add(fileRef);

        // Resolve
        Map<String, Object> result = resolver.resolveExternalRefs(refs, tempDir.toString());

        assertEquals(2, result.size());
        assertEquals("secret-notion-token-123", result.get("notion_token"));
        assertEquals("notion-db-456", result.get("db_id"));
    }

    @Test
    public void testResolveFileExternalRefYaml(@TempDir Path tempDir) throws Exception {
        // Create a YAML file with credentials
        String yamlContent = """
                GITHUB_TOKEN: "github-pat-xyz789"
                REPO_NAME: "my-awesome-repo"
                """;
        
        Path configFile = tempDir.resolve("github.yaml");
        Files.writeString(configFile, yamlContent);

        // Create file ref spec
        FileExternalRefSpec fileRef = new FileExternalRefSpec();
        fileRef.setName("github-config");
        fileRef.setUri("github.yaml");
        fileRef.getKeys().put("gh_token", "GITHUB_TOKEN");
        fileRef.getKeys().put("repo", "REPO_NAME");

        List<ExternalRefSpec> refs = new ArrayList<>();
        refs.add(fileRef);

        // Resolve
        Map<String, Object> result = resolver.resolveExternalRefs(refs, tempDir.toString());

        assertEquals(2, result.size());
        assertEquals("github-pat-xyz789", result.get("gh_token"));
        assertEquals("my-awesome-repo", result.get("repo"));
    }

    @Test
    public void testResolveRuntimeExternalRefFromEnvironment() throws Exception {
        // Set environment variables
        String envVarName = "TEST_EXTERNAL_REF_TOKEN_" + System.currentTimeMillis();
        String envVarValue = "runtime-token-secret";
        
        // Create a runtime ref spec
        RuntimeExternalRefSpec runtimeRef = new RuntimeExternalRefSpec();
        runtimeRef.setName("env-config");
        runtimeRef.getKeys().put("token", envVarName);

        List<ExternalRefSpec> refs = new ArrayList<>();
        refs.add(runtimeRef);

        // Set environment variable
        ProcessBuilder pb = new ProcessBuilder();
        Map<String, String> env = pb.environment();
        env.put(envVarName, envVarValue);

        // We can't directly set env vars in Java, so we test with system properties instead
        System.setProperty(envVarName, envVarValue);
        try {
            Map<String, Object> result = resolver.resolveExternalRefs(refs, ".");
            assertEquals(1, result.size());
            assertEquals(envVarValue, result.get("token"));
        } finally {
            System.clearProperty(envVarName);
        }
    }

    @Test
    public void testResolveMultipleExternalRefs(@TempDir Path tempDir) throws Exception {
        // Create a config file
        String jsonContent = """
                {
                  "API_KEY": "key-12345",
                  "API_SECRET": "secret-67890"
                }
                """;
        
        Path configFile = tempDir.resolve("api.json");
        Files.writeString(configFile, jsonContent);

        // Create file ref
        FileExternalRefSpec fileRef = new FileExternalRefSpec();
        fileRef.setName("api-config");
        fileRef.setUri("api.json");
        fileRef.getKeys().put("api_key", "API_KEY");
        fileRef.getKeys().put("api_secret", "API_SECRET");

        // Create runtime ref
        String envVarName = "CUSTOM_ENV_VAR_" + System.currentTimeMillis();
        System.setProperty(envVarName, "custom-value");
        
        RuntimeExternalRefSpec runtimeRef = new RuntimeExternalRefSpec();
        runtimeRef.setName("runtime-config");
        runtimeRef.getKeys().put("custom", envVarName);

        List<ExternalRefSpec> refs = new ArrayList<>();
        refs.add(fileRef);
        refs.add(runtimeRef);

        try {
            // Resolve
            Map<String, Object> result = resolver.resolveExternalRefs(refs, tempDir.toString());

            assertEquals(3, result.size());
            assertEquals("key-12345", result.get("api_key"));
            assertEquals("secret-67890", result.get("api_secret"));
            assertEquals("custom-value", result.get("custom"));
        } finally {
            System.clearProperty(envVarName);
        }
    }

    @Test
    public void testEmptyExternalRefsList() throws Exception {
        List<ExternalRefSpec> refs = new ArrayList<>();
        
        Map<String, Object> result = resolver.resolveExternalRefs(refs, ".");
        
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testNullExternalRefsList() throws Exception {
        Map<String, Object> result = resolver.resolveExternalRefs(null, ".");
        
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testFileExternalRefMissingFile(@TempDir Path tempDir) {
        // Create a file ref pointing to non-existent file
        FileExternalRefSpec fileRef = new FileExternalRefSpec();
        fileRef.setName("missing-config");
        fileRef.setUri("does-not-exist.json");
        fileRef.getKeys().put("token", "TOKEN");

        List<ExternalRefSpec> refs = new ArrayList<>();
        refs.add(fileRef);

        // Should throw IOException
        assertThrows(IOException.class, () -> {
            resolver.resolveExternalRefs(refs, tempDir.toString());
        });
    }

    @Test
    public void testFileExternalRefMissingKey(@TempDir Path tempDir) throws Exception {
        // Create a config file
        String jsonContent = """
                {
                  "PRESENT_KEY": "value123"
                }
                """;
        
        Path configFile = tempDir.resolve("incomplete.json");
        Files.writeString(configFile, jsonContent);

        // Create file ref looking for missing key
        FileExternalRefSpec fileRef = new FileExternalRefSpec();
        fileRef.setName("incomplete-config");
        fileRef.setUri("incomplete.json");
        fileRef.getKeys().put("token", "MISSING_KEY");

        List<ExternalRefSpec> refs = new ArrayList<>();
        refs.add(fileRef);

        // Should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> {
            resolver.resolveExternalRefs(refs, tempDir.toString());
        });
    }

    @Test
    public void testRuntimeExternalRefMissingEnvVar() {
        String missingVarName = "THIS_VAR_DEFINITELY_DOES_NOT_EXIST_" + System.currentTimeMillis();
        
        RuntimeExternalRefSpec runtimeRef = new RuntimeExternalRefSpec();
        runtimeRef.setName("missing-config");
        runtimeRef.getKeys().put("token", missingVarName);

        List<ExternalRefSpec> refs = new ArrayList<>();
        refs.add(runtimeRef);

        // Should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> {
            resolver.resolveExternalRefs(refs, ".");
        });
    }

    @Test
    public void testFileExternalRefWithoutUri() {
        // Create file ref without URI
        FileExternalRefSpec fileRef = new FileExternalRefSpec();
        fileRef.setName("invalid-config");
        fileRef.getKeys().put("token", "TOKEN");

        List<ExternalRefSpec> refs = new ArrayList<>();
        refs.add(fileRef);

        // Should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> {
            resolver.resolveExternalRefs(refs, ".");
        });
    }

    @Test
    public void testResolveFileExternalRefProperties(@TempDir Path tempDir) throws Exception {
        // Create a properties file with credentials
        String propsContent = "API_TOKEN=secret-api-token-xyz\n"
                + "API_ENDPOINT=https://api.example.com\n"
                + "API_VERSION=v2\n";
        
        Path configFile = tempDir.resolve("config.properties");
        Files.writeString(configFile, propsContent);

        // Create file ref spec
        FileExternalRefSpec fileRef = new FileExternalRefSpec();
        fileRef.setName("api-config");
        fileRef.setUri("config.properties");
        fileRef.getKeys().put("api_token", "API_TOKEN");
        fileRef.getKeys().put("endpoint", "API_ENDPOINT");
        fileRef.getKeys().put("version", "API_VERSION");

        List<ExternalRefSpec> refs = new ArrayList<>();
        refs.add(fileRef);

        // Resolve
        Map<String, Object> result = resolver.resolveExternalRefs(refs, tempDir.toString());

        assertEquals(3, result.size());
        assertEquals("secret-api-token-xyz", result.get("api_token"));
        assertEquals("https://api.example.com", result.get("endpoint"));
        assertEquals("v2", result.get("version"));
    }

    @Test
    public void testResolvePropertiesFileWithMissingKey(@TempDir Path tempDir) throws Exception {
        // Create a properties file
        String propsContent = "EXISTING_KEY=some-value\n";
        
        Path configFile = tempDir.resolve("incomplete.properties");
        Files.writeString(configFile, propsContent);

        // Create file ref spec with missing key
        FileExternalRefSpec fileRef = new FileExternalRefSpec();
        fileRef.setName("incomplete-config");
        fileRef.setUri("incomplete.properties");
        fileRef.getKeys().put("missing", "MISSING_KEY");

        List<ExternalRefSpec> refs = new ArrayList<>();
        refs.add(fileRef);

        // Should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> {
            resolver.resolveExternalRefs(refs, tempDir.toString());
        });
    }

    @Test
    public void testRelativeFilePathResolution(@TempDir Path tempDir) throws Exception {
        // Create a subdirectory and config file
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        
        String jsonContent = """
                {
                  "API_KEY": "key-from-subdir"
                }
                """;
        
        Path configFile = configDir.resolve("api.json");
        Files.writeString(configFile, jsonContent);

        // Create file ref with relative path
        FileExternalRefSpec fileRef = new FileExternalRefSpec();
        fileRef.setName("api-config");
        fileRef.setUri("config/api.json");
        fileRef.getKeys().put("api_key", "API_KEY");

        List<ExternalRefSpec> refs = new ArrayList<>();
        refs.add(fileRef);

        // Resolve from temp directory
        Map<String, Object> result = resolver.resolveExternalRefs(refs, tempDir.toString());

        assertEquals("key-from-subdir", result.get("api_key"));
    }

}
