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
package io.naftiko.spec.consumes.http;

import java.util.concurrent.atomic.AtomicReference;

import io.naftiko.spec.OperationSpec;

/**
 * HTTP Operation Specification Element.
 *
 * <h2>Thread safety</h2>
 * The {@code body} field is held in an {@link AtomicReference}. This satisfies SonarQube rule
 * {@code java:S3077}.
 */
public class HttpClientOperationSpec extends OperationSpec {

    /**
     * Request body definition. Accepts either:
     * <ul>
     *   <li>a plain {@link String} — treated as a raw Mustache template sent as
     *       {@code application/json} (legacy form)</li>
     *   <li>a {@link java.util.Map} with {@code type} and {@code data} fields — the structured
     *       {@code RequestBody} object defined in the Naftiko specification</li>
     * </ul>
     */
    private final AtomicReference<Object> body = new AtomicReference<>();

    public HttpClientOperationSpec() {
        this(null, null, null, null, null, null, null);
    }

    public HttpClientOperationSpec(HttpClientResourceSpec parentResource, String method, String name, String label) {
        this(parentResource, method, name, label, null, null, null);
    }

    public HttpClientOperationSpec(HttpClientResourceSpec parentResource, String method, String name, String label, String description, Object body, String outputRawFormat) {
        this(parentResource, method, name, label, description, body, outputRawFormat, null);
    }

    public HttpClientOperationSpec(HttpClientResourceSpec parentResource, String method, String name, String label, String description, Object body, String outputRawFormat, String outputSchema) {
        super(parentResource, method, name, label, description, outputRawFormat, outputSchema);
        this.body.set(body);
    }

    public Object getBody() {
        return body.get();
    }

    public void setBody(Object body) {
        this.body.set(body);
    }

}
