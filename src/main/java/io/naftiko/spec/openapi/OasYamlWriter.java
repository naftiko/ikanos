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
package io.naftiko.spec.openapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;

/**
 * Serializes an OpenAPI POJO to YAML or JSON on disk.
 */
public class OasYamlWriter {

    /**
     * Write the OpenAPI document as YAML.
     */
    public void writeYaml(OpenAPI openApi, Path outputPath) throws IOException {
        String yaml = openApi.getSpecVersion() == SpecVersion.V31
                ? Yaml31.pretty(openApi)
                : Yaml.pretty(openApi);
        Files.writeString(outputPath, yaml);
    }

    /**
     * Write the OpenAPI document as JSON.
     */
    public void writeJson(OpenAPI openApi, Path outputPath) throws IOException {
        String json = openApi.getSpecVersion() == SpecVersion.V31
                ? Json31.pretty(openApi)
                : Json.pretty(openApi);
        Files.writeString(outputPath, json);
    }

}
