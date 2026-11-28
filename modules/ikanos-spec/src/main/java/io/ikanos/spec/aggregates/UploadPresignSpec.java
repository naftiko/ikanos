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
package io.ikanos.spec.aggregates;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The presign call of a flow's {@code upload} block (experimental): the consumed operation that
 * returns a presigned upload URL for one file, and where its JSON response carries the URL, the
 * headers the client sends with the transfer and when the URL expires (JsonPath expressions).
 */
public class UploadPresignSpec extends UploadStepSpec {

    /** Where the response carries the URL when {@code url} is left out. */
    public static final String DEFAULT_URL = "$.url";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String url;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String headers;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String expiresAt;

    /** @return the JsonPath of the upload URL in the response, or {@code null} for the default */
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    /** @return the JsonPath of the upload URL, {@link #DEFAULT_URL} when not declared */
    public String urlOrDefault() {
        return url != null ? url : DEFAULT_URL;
    }

    /** @return the JsonPath of the headers object in the response, or {@code null} */
    public String getHeaders() {
        return headers;
    }

    public void setHeaders(String headers) {
        this.headers = headers;
    }

    /** @return the JsonPath of the URL's expiry timestamp in the response, or {@code null} */
    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }
}
