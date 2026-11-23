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
package io.ikanos.spec.consumes.graphql;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import io.ikanos.spec.consumes.ContractClientSpec;

/**
 * A {@code consumes: type: graphql} entry: static GraphQL documents sent to one endpoint, with
 * the step's values as variables, optionally checked against a local SDL schema.
 */
@JsonDeserialize(using = ValueDeserializer.None.class)
public class GraphqlClientSpec extends ContractClientSpec {

    public static final String TYPE = "graphql";

    private final AtomicReference<String> endpoint = new AtomicReference<>();
    private final AtomicReference<String> schema = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonDeserialize(using = GraphqlOperationMapDeserializer.class)
    private final Map<String, GraphqlOperationSpec> operations =
            Collections.synchronizedMap(new LinkedHashMap<>());

    public GraphqlClientSpec() {
        super(TYPE);
    }

    /** @return the GraphQL endpoint URL, used as is */
    public String getEndpoint() {
        return endpoint.get();
    }

    public void setEndpoint(String endpoint) {
        this.endpoint.set(endpoint);
    }

    /** @return the path of the SDL schema, or {@code null} */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getSchema() {
        return schema.get();
    }

    public void setSchema(String schema) {
        this.schema.set(schema);
    }

    public Map<String, GraphqlOperationSpec> getOperations() {
        return operations;
    }

    public void setOperations(Map<String, GraphqlOperationSpec> values) {
        operations.clear();
        if (values != null) {
            values.forEach((name, operation) -> {
                operation.setName(name);
                operations.put(name, operation);
            });
        }
    }

    @Override
    public Map<String, GraphqlOperationSpec> operations() {
        return operations;
    }
}
