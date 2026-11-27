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
package io.ikanos.spec.exposes.control;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The store of a durability mode: its type (which selects the store module) and where it lives.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DurabilityStoreSpec {

    private String type;
    private String path;
    private String url;
    private String user;
    private String password;

    public DurabilityStoreSpec() {}

    /**
     * The JDBC URL of a shared store, such as the PostgreSQL database of {@code distributed},
     * usually a {@code {{...}}} reference to a {@code binds} variable.
     */
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    /** The database user of a shared store, usually a {@code binds} reference. */
    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    /** The database password of a shared store: a {@code binds} reference, never a literal. */
    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public DurabilityStoreSpec(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
