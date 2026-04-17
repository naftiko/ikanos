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

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.swagger.v3.oas.models.SpecVersion;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.openapi.OasExportBuilder;
import io.naftiko.spec.openapi.OasExportResult;
import io.naftiko.spec.openapi.OasYamlWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
    name = "openapi",
    mixinStandardHelpOptions = true,
    description = "Export a Naftiko capability's REST adapter as an OpenAPI specification."
)
public class ExportOpenApiCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the Naftiko capability YAML file")
    private String capability;

    @Option(names = {"-o", "--output"}, description = "Output file path (default: ./openapi.yaml)")
    private String output;

    @Option(names = {"-f", "--format"}, description = "Output format: yaml or json (default: yaml)")
    private String format = "yaml";

    @Option(names = {"-a", "--adapter"}, description = "Namespace of the REST adapter to export")
    private String adapter;

    @Option(names = {"--spec-version"}, description = "OpenAPI Specification version: 3.0 or 3.1 (default: 3.0)")
    private String specVersionOption = "3.0";

    @Override
    public Integer call() {
        try {
            // Load the capability YAML
            File capabilityFile = new File(capability);
            if (!capabilityFile.exists()) {
                System.err.println("Error: File not found: " + capability);
                return 1;
            }

            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            NaftikoSpec naftikoSpec = yamlMapper.readValue(capabilityFile, NaftikoSpec.class);

            // Validate and parse spec version
            SpecVersion specVersion;
            if ("3.1".equals(specVersionOption)) {
                specVersion = SpecVersion.V31;
            } else if ("3.0".equals(specVersionOption)) {
                specVersion = SpecVersion.V30;
            } else {
                System.err.println("Error: Unsupported spec version '" + specVersionOption + "'. Supported versions: 3.0, 3.1");
                return 1;
            }

            // Build the OpenAPI document
            OasExportBuilder builder = new OasExportBuilder();
            OasExportResult result = builder.build(naftikoSpec, adapter, specVersion);

            // Print warnings to stderr
            for (String warning : result.getWarnings()) {
                System.err.println("Warning: " + warning);
            }

            // Determine output path
            String outputPath = output != null ? output : "./openapi." + format;
            Path path = Paths.get(outputPath);

            // Write output
            OasYamlWriter writer = new OasYamlWriter();
            if ("json".equalsIgnoreCase(format)) {
                writer.writeJson(result.getOpenApi(), path);
            } else {
                writer.writeYaml(result.getOpenApi(), path);
            }

            System.out.println("✓ Exported OpenAPI specification successfully");
            System.out.println("  Output: " + path.toAbsolutePath());
            return 0;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
