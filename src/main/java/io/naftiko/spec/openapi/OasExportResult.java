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

import java.util.List;
import io.swagger.v3.oas.models.OpenAPI;

/**
 * Result of an OpenAPI export build. Contains the OpenAPI POJO
 * and any warnings generated during conversion.
 */
public class OasExportResult {

    private final OpenAPI openApi;
    private final List<String> warnings;

    public OasExportResult(OpenAPI openApi, List<String> warnings) {
        this.openApi = openApi;
        this.warnings = List.copyOf(warnings);
    }

    public OpenAPI getOpenApi() {
        return openApi;
    }

    public List<String> getWarnings() {
        return warnings;
    }

}
