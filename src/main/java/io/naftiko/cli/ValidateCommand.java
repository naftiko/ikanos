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
package io.naftiko.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

@Command(
    name = "validate",
    mixinStandardHelpOptions = true,
    aliases = {"v", "val"},
    description = "Validate a YAML or JSON capability configuration file against a JSON Schema"
)
public class ValidateCommand implements Runnable {
    
    @Parameters(index = "0", description = "Path to the YAML or JSON capability configuration file to validate")
    private String filePath;

    @Parameters(index = "1", description = "Version of the schema to use for validation (expected format: x.x). If not set this is the lastest.", defaultValue = "")
    private String schemaVersion;
    
    @Override
    public void run() {
        try {
            // Check that file to validate exist and load it.
            Path fileToValidate = Paths.get(filePath);
            if (!Files.exists(fileToValidate)) {
                System.err.println("Error: File not found: " + filePath);
                System.exit(1);
            }
            JsonNode dataNode = loadFile(fileToValidate.toFile());

            // Load schema.
            String schemaFileName = schemaVersion.isEmpty() ? "capability-schema.json" : "capability-schema-v" + schemaVersion + ".json";
            InputStream schemaInputStream = getClass().getClassLoader().getResourceAsStream("schemas/" + schemaFileName);
            if (schemaInputStream == null) {
                System.err.println("Error: Scheam version " + schemaVersion + " is not supported");
                System.exit(1);
            }
            JsonNode schemaNode = new ObjectMapper().readTree(schemaInputStream);

            // Create JSON Schema validator. Read version from $schema of file.
            JsonSchemaFactory factory;
            JsonNode schemaField = schemaNode.get("$schema");
            if (schemaField != null && schemaField.asText().contains("2020-12")) {
                factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            } else if (schemaField != null && schemaField.asText().contains("2019-09")) {
                factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V201909);
            } else {
                factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            }
            JsonSchema schema = factory.getSchema(schemaNode);
            
            // Validate.
            Set<ValidationMessage> errors = schema.validate(dataNode);
            
            // Display validation result.
            if (errors.isEmpty()) {
                System.out.println("✓ Validation successful!");
                System.out.println("  File: " + filePath);
                System.out.println("  Status: OK");
            } else {
                System.err.println("✗ Validation failed!");
                System.err.println("  File: " + filePath);
                System.err.println("\nErrors found:");
                
                int errorCount = 1;
                for (ValidationMessage error : errors) {
                    System.err.println("\n  [" + errorCount + "] " + error.getMessage());
                    System.err.println("      Path: " + error.getPath());
                    errorCount++;
                }
                System.exit(1);
            }
            
        } catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Validation error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private JsonNode loadFile(File file) throws IOException {
        String fileName = file.getName().toLowerCase();
        
        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            // Parser YAML
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            return yamlMapper.readTree(file);
        } else if (fileName.endsWith(".json")) {
            // Parser JSON
            ObjectMapper jsonMapper = new ObjectMapper();
            return jsonMapper.readTree(file);
        } else {
            throw new IllegalArgumentException(
                "Unsupported file format. Only .yaml, .yml, and .json are supported."
            );
        }
    }
}