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

import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One {@code OUT} or {@code INOUT} parameter of a stored procedure, keyed under
 * {@code outParameters} by the name it takes in the row document's {@code out} object. An
 * {@code INOUT} parameter is also declared in {@code inputParameters}, at the same position.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SqlOutParameterSpec {

    private final AtomicReference<String> name = new AtomicReference<>();
    private final AtomicReference<Integer> position = new AtomicReference<>();
    private final AtomicReference<String> sqlType = new AtomicReference<>();
    private final AtomicReference<String> description = new AtomicReference<>();

    /** @return the map key, set when the procedure is read */
    @JsonIgnore
    public String getName() {
        return name.get();
    }

    public void setName(String name) {
        this.name.set(name);
    }

    /** @return the 1-based position in the call */
    public Integer getPosition() {
        return position.get();
    }

    public void setPosition(Integer position) {
        this.position.set(position);
    }

    /** @return the {@code java.sql.JDBCType} name the value is registered as */
    public String getSqlType() {
        return sqlType.get();
    }

    public void setSqlType(String sqlType) {
        this.sqlType.set(sqlType);
    }

    public String getDescription() {
        return description.get();
    }

    public void setDescription(String description) {
        this.description.set(description);
    }
}
