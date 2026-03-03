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
package io.naftiko.spec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * File-resolved External Reference Specification Element.
 * Variables are extracted from a file at the specified URI during capability loading.
 */
public class FileExternalRefSpec extends ExternalRefSpec {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String uri;

    public FileExternalRefSpec() {
        super(null, null, null, "file");
    }

    public FileExternalRefSpec(String name, String description, String type, String uri) {
        super(name, description, type, "file");
        this.uri = uri;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

}
