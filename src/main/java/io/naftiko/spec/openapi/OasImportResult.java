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
import io.naftiko.spec.consumes.HttpClientSpec;

/**
 * Result of an OpenAPI import conversion. Contains the converted HttpClientSpec
 * and any warnings generated during conversion.
 */
public class OasImportResult {

    private final HttpClientSpec httpClient;
    private final List<String> warnings;

    public OasImportResult(HttpClientSpec httpClient, List<String> warnings) {
        this.httpClient = httpClient;
        this.warnings = List.copyOf(warnings);
    }

    public HttpClientSpec getHttpClient() {
        return httpClient;
    }

    public List<String> getWarnings() {
        return warnings;
    }

}
