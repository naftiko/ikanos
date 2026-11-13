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
package io.ikanos.spec.consumes.sql;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Connection of a {@code consumes: type: sql} entry: the JDBC URL, the credentials and optional
 * driver properties. Each value may hold {@code {{…}}} placeholders, resolved once from the
 * capability bindings when the adapter is built, never from call parameters.
 */
public class SqlConnectionSpec {

    private final AtomicReference<String> url = new AtomicReference<>();
    private final AtomicReference<String> username = new AtomicReference<>();
    private final AtomicReference<String> password = new AtomicReference<>();
    private final AtomicReference<String> driverClassName = new AtomicReference<>();
    private final Map<String, String> properties =
            Collections.synchronizedMap(new LinkedHashMap<>());

    public SqlConnectionSpec() {
    }

    public SqlConnectionSpec(String url) {
        this.url.set(url);
    }

    /** @return the JDBC URL, such as {@code jdbc:postgresql://db:5432/erp} */
    public String getUrl() {
        return url.get();
    }

    public void setUrl(String url) {
        this.url.set(url);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getUsername() {
        return username.get();
    }

    public void setUsername(String username) {
        this.username.set(username);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getPassword() {
        return password.get();
    }

    public void setPassword(String password) {
        this.password.set(password);
    }

    /** @return the driver class, for drivers that do not register themselves, or {@code null} */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getDriverClassName() {
        return driverClassName.get();
    }

    public void setDriverClassName(String driverClassName) {
        this.driverClassName.set(driverClassName);
    }

    /** @return driver properties passed to the driver as written */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> values) {
        properties.clear();
        if (values != null) {
            properties.putAll(values);
        }
    }
}
