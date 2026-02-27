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
package io.naftiko.spec.exposes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.naftiko.spec.InputParameterSpec;

/**
 * Base Exposed Adapter Specification Element
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY, // Include the type identifier as a property in the JSON
    property = "type" // The name of the JSON property holding the type identifier
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ApiServerSpec.class, name = "api"),
    @JsonSubTypes.Type(value = McpServerSpec.class, name = "mcp")
})
public abstract class ServerSpec {

    private volatile String type;

    private volatile String address;

    private volatile int port;
    
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<InputParameterSpec> inputParameters;

    public ServerSpec() {
        this(null, "localhost", 0);
    }

    public ServerSpec(String type) {
        this();
        this.type = type;
    }

    public ServerSpec(String type, String address, int port) {
        this.type = type;
        this.address = address;
        this.port = port;
        this.inputParameters = new CopyOnWriteArrayList<>();
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public List<InputParameterSpec> getInputParameters() {
        return inputParameters;
    }

}
