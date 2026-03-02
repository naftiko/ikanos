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

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;

import io.naftiko.cli.enums.FileFormat;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class FileGenerator {
    public static void generateCapabilityFile(String capabilityName, FileFormat format, String baseUri, String port) throws IOException {
        String templatePath = "templates/capability." + format.pathName + ".mustache";
        String outputFileName = capabilityName + ".capability." + format.pathName;
        
        // Load template from resources.
        InputStream templateStream = FileGenerator.class
            .getClassLoader()
            .getResourceAsStream(templatePath);
        if (templateStream == null) {
            throw new FileNotFoundException("Template not found: " + templatePath);
        }
        
        // Render template and write file.
        Template mustache = Mustache.compiler().compile(new InputStreamReader(templateStream));
        Map<String, Object> scope = new HashMap<>();
        scope.put("capabilityName", capabilityName);
        scope.put("port", port);
        scope.put("baseUri", baseUri);
        scope.put("path", "{{path}}"); // Let this keyword as is.
        Path outputPath = Paths.get(outputFileName);
        try (Writer writer = Files.newBufferedWriter(outputPath)) {
            mustache.execute(scope, writer);
            writer.flush();
        }
        
        System.out.println("✓ File created successfully: " + outputPath.toAbsolutePath());
    }
}