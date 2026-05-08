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
package io.ikanos.spec.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Operation Step Lookup Specification Element
 * 
 * Represents a lookup operation that cross-references the output of a previous call step.
 * Performs value matching and extraction within an orchestration step.
 */
public class OperationStepLookupSpec extends OperationStepSpec {

    @JsonProperty("index")
    private volatile String index;

    @JsonProperty("match")
    private volatile String match;

    @JsonProperty("lookupValue")
    private volatile String lookupValue;

    @JsonProperty("outputParameters")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<String> outputParameters;

    public OperationStepLookupSpec() {
        this(null, null, null, null, null);
    }

    public OperationStepLookupSpec(String name, String index, String match, String lookupValue) {
        this(name, index, match, lookupValue, null);
    }

    public OperationStepLookupSpec(String name, String index, String match, String lookupValue, List<String> outputParameters) {
        this("lookup", name, index, match, lookupValue, outputParameters);
    }

    public OperationStepLookupSpec(String type, String name, String index, String match, String lookupValue, List<String> outputParameters) {
        super(type, name);
        this.index = index;
        this.match = match;
        this.lookupValue = lookupValue;
        this.outputParameters = new CopyOnWriteArrayList<>();
        if (outputParameters != null) {
            this.outputParameters.addAll(outputParameters);
        }
    }

    public String getIndex() {
        return index;
    }

    public void setIndex(String index) {
        this.index = index;
    }

    public String getMatch() {
        return match;
    }

    public void setMatch(String match) {
        this.match = match;
    }

    public String getLookupValue() {
        return lookupValue;
    }

    public void setLookupValue(String lookupValue) {
        this.lookupValue = lookupValue;
    }

    public List<String> getOutputParameters() {
        return outputParameters;
    }

}
