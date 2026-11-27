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
 * The verify call of a flow's {@code upload} block (experimental): the consumed operation that
 * confirms the bytes of one file arrived, such as a {@code HEAD} on the stored object. A
 * successful answer must report the declared size and, when the input restricts its type, an
 * accepted media type. They are read from the {@code Content-Length} and {@code Content-Type}
 * headers, or from the JSON response where {@code size} and {@code mimeType} say (JsonPath).
 */
public class UploadVerifySpec extends UploadStepSpec {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String size;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String mimeType;

    /** @return the JsonPath of the stored size, or {@code null} for {@code Content-Length} */
    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    /** @return the JsonPath of the stored media type, or {@code null} for {@code Content-Type} */
    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }
}
