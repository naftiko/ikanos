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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.consumes.HttpClientSpec;
import io.naftiko.spec.openapi.OasImportConverter;
import io.naftiko.spec.openapi.OasImportResult;
import io.naftiko.util.VersionHelper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
    name = "openapi",
    mixinStandardHelpOptions = true,
    description = "Import an OpenAPI specification into a Naftiko consumes YAML file."
)
public class ImportOpenApiCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path or URL to the OpenAPI specification file")
    private String source;

    @Option(names = {"-o", "--output"}, description = "Output file path (default: ./<namespace>-consumes.yml)")
    private String output;

    @Option(names = {"-n", "--namespace"}, description = "Override the derived namespace")
    private String namespace;

    @Option(names = {"-f", "--format"}, description = "Output format: yaml or json (default: yaml)")
    private String format = "yaml";

    @Override
    public Integer call() {
        try {
            // Parse the OpenAPI document (supports Swagger 2.0 and OAS 3.x)
            SwaggerParseResult parseResult = new OpenAPIParser().readLocation(source, null, null);

            if (parseResult.getOpenAPI() == null) {
                System.err.println("Error: Failed to parse OpenAPI specification from: " + source);
                if (parseResult.getMessages() != null) {
                    for (String msg : parseResult.getMessages()) {
                        System.err.println("  " + msg);
                    }
                }
                return 1;
            }

            OpenAPI openApi = parseResult.getOpenAPI();

            // Convert to Naftiko HttpClientSpec
            OasImportConverter converter = new OasImportConverter();
            OasImportResult result = converter.convert(openApi);

            HttpClientSpec httpClient = result.getHttpClient();

            // Override namespace if specified
            if (namespace != null) {
                httpClient.setNamespace(namespace);
            }

            // Print warnings to stderr
            for (String warning : result.getWarnings()) {
                System.err.println("Warning: " + warning);
            }

            // Determine output path
            String outputPath;
            if (output != null) {
                outputPath = output;
            } else {
                String ext = "json".equalsIgnoreCase(format) ? "json" : "yml";
                outputPath = "./" + httpClient.getNamespace() + "-consumes." + ext;
            }

            // Build the appropriate mapper based on format
            ObjectMapper mapper;
            if ("json".equalsIgnoreCase(format)) {
                mapper = new ObjectMapper();
                mapper.enable(SerializationFeature.INDENT_OUTPUT);
            } else {
                YAMLFactory yamlFactory = YAMLFactory.builder()
                        .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                        .build();
                mapper = new ObjectMapper(yamlFactory);
            }
            mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_EMPTY);
            mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

            // Wrap in a NaftikoSpec document
            NaftikoSpec spec = new NaftikoSpec();
            spec.setNaftiko(VersionHelper.getSchemaVersion());
            spec.getConsumes().add(httpClient);

            Path path = Paths.get(outputPath);
            mapper.writeValue(path.toFile(), spec);

            System.out.println("✓ Imported OpenAPI specification successfully");
            System.out.println("  Output: " + path.toAbsolutePath());
            return 0;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
