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
package io.ikanos.engine.consumes.fake;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.ikanos.spec.OperationSpec;
import io.ikanos.spec.consumes.ClientSpec;

/**
 * Test-only {@code consumes: type: fake} spec. Each operation returns a canned body declared in
 * YAML, so the engine can be exercised end to end through a consumed adapter that has nothing to
 * do with HTTP.
 *
 * <pre>
 * consumes:
 *   - type: fake
 *     namespace: canned
 *     operations:
 *       get-user:
 *         outputParameters: ...
 *     responses:
 *       get-user: '{"id":"u-1"}'
 * </pre>
 */
@JsonDeserialize(using = JsonDeserializer.None.class)
public class FakeClientSpec extends ClientSpec {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final Map<String, OperationSpec> operations =
            Collections.synchronizedMap(new LinkedHashMap<>());

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final Map<String, String> responses =
            Collections.synchronizedMap(new LinkedHashMap<>());

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final Map<String, String> mediaTypes =
            Collections.synchronizedMap(new LinkedHashMap<>());

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final Map<String, Integer> statuses =
            Collections.synchronizedMap(new LinkedHashMap<>());

    public FakeClientSpec() {
        super("fake", null);
    }

    public Map<String, OperationSpec> getOperations() {
        return operations;
    }

    public void setOperations(Map<String, OperationSpec> ops) {
        operations.clear();
        if (ops != null) {
            ops.forEach((name, op) -> {
                op.setName(name);
                operations.put(name, op);
            });
        }
    }

    /** @return canned response bodies keyed by operation name */
    public Map<String, String> getResponses() {
        return responses;
    }

    public void setResponses(Map<String, String> values) {
        responses.clear();
        if (values != null) {
            responses.putAll(values);
        }
    }

    /** @return media types of the canned bodies keyed by operation name (default JSON) */
    public Map<String, String> getMediaTypes() {
        return mediaTypes;
    }

    public void setMediaTypes(Map<String, String> values) {
        mediaTypes.clear();
        if (values != null) {
            mediaTypes.putAll(values);
        }
    }

    /** @return HTTP-equivalent statuses keyed by operation name (default 200) */
    public Map<String, Integer> getStatuses() {
        return statuses;
    }

    public void setStatuses(Map<String, Integer> values) {
        statuses.clear();
        if (values != null) {
            statuses.putAll(values);
        }
    }
}
