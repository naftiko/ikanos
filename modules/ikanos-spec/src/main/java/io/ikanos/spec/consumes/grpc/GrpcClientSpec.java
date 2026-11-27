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
package io.ikanos.spec.consumes.grpc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import io.ikanos.spec.consumes.ContractClientSpec;

/**
 * A {@code consumes: type: grpc} entry: unary methods of one gRPC service, described by a local
 * protobuf descriptor set and called with binary protobuf over HTTP/2.
 */
@JsonDeserialize(using = ValueDeserializer.None.class)
public class GrpcClientSpec extends ContractClientSpec {

    public static final String TYPE = "grpc";

    private final AtomicReference<String> endpoint = new AtomicReference<>();
    private final AtomicReference<String> descriptorSet = new AtomicReference<>();
    private final AtomicReference<String> service = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonDeserialize(using = GrpcProcedureMapDeserializer.class)
    private final Map<String, GrpcProcedureSpec> procedures =
            Collections.synchronizedMap(new LinkedHashMap<>());

    public GrpcClientSpec() {
        super(TYPE);
    }

    /** @return the authority, {@code http://host:port} */
    public String getEndpoint() {
        return endpoint.get();
    }

    public void setEndpoint(String endpoint) {
        this.endpoint.set(endpoint);
    }

    /** @return the path of the binary {@code FileDescriptorSet} */
    public String getDescriptorSet() {
        return descriptorSet.get();
    }

    public void setDescriptorSet(String descriptorSet) {
        this.descriptorSet.set(descriptorSet);
    }

    /** @return the fully qualified service name */
    public String getService() {
        return service.get();
    }

    public void setService(String service) {
        this.service.set(service);
    }

    public Map<String, GrpcProcedureSpec> getProcedures() {
        return procedures;
    }

    public void setProcedures(Map<String, GrpcProcedureSpec> values) {
        procedures.clear();
        if (values != null) {
            values.forEach((name, procedure) -> {
                procedure.setName(name);
                procedures.put(name, procedure);
            });
        }
    }

    @Override
    public Map<String, GrpcProcedureSpec> operations() {
        return procedures;
    }
}
