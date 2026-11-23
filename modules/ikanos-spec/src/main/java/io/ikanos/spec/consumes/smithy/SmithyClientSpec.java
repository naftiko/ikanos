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
package io.ikanos.spec.consumes.smithy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import io.ikanos.spec.consumes.ContractClientSpec;

/**
 * A {@code consumes: type: smithy} entry: operations of one Smithy service, described by a local
 * model and called through smithy-java's dynamic client in the service's protocol.
 */
@JsonDeserialize(using = ValueDeserializer.None.class)
public class SmithyClientSpec extends ContractClientSpec {

    public static final String TYPE = "smithy";

    private final AtomicReference<String> baseUri = new AtomicReference<>();

    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private final List<String> model = new CopyOnWriteArrayList<>();

    private final AtomicReference<String> service = new AtomicReference<>();
    private final AtomicReference<String> protocol = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonDeserialize(using = SmithyOperationMapDeserializer.class)
    private final Map<String, SmithyOperationSpec> operations =
            Collections.synchronizedMap(new LinkedHashMap<>());

    public SmithyClientSpec() {
        super(TYPE);
    }

    public String getBaseUri() {
        return baseUri.get();
    }

    public void setBaseUri(String baseUri) {
        this.baseUri.set(baseUri);
    }

    /** @return the model files: one path, or several */
    public List<String> getModel() {
        return model;
    }

    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    public void setModel(List<String> values) {
        model.clear();
        if (values != null) {
            model.addAll(values);
        }
    }

    /** @return the service shape ID */
    public String getService() {
        return service.get();
    }

    public void setService(String service) {
        this.service.set(service);
    }

    /** @return the protocol trait shape ID, or {@code null} to take the service's only one */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getProtocol() {
        return protocol.get();
    }

    public void setProtocol(String protocol) {
        this.protocol.set(protocol);
    }

    public Map<String, SmithyOperationSpec> getOperations() {
        return operations;
    }

    public void setOperations(Map<String, SmithyOperationSpec> values) {
        operations.clear();
        if (values != null) {
            values.forEach((name, operation) -> {
                operation.setName(name);
                operations.put(name, operation);
            });
        }
    }

    @Override
    public Map<String, SmithyOperationSpec> operations() {
        return operations;
    }
}
