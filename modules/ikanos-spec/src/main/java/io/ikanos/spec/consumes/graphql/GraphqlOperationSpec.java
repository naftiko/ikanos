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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.ikanos.spec.consumes.ContractOperationSpec;

/**
 * One consumed GraphQL operation: a {@code document}, or named {@code documents} and the
 * {@code select} rule choosing one per call.
 */
public class GraphqlOperationSpec extends ContractOperationSpec {

    /** {@code partialData}: a response with {@code errors} and some {@code data} fails. */
    public static final String PARTIAL_FAIL = "fail";

    /** {@code partialData}: a response with {@code errors} and some {@code data} succeeds. */
    public static final String PARTIAL_ALLOW = "allow";

    private final AtomicReference<String> document = new AtomicReference<>();
    private final Map<String, String> documents =
            Collections.synchronizedMap(new LinkedHashMap<>());
    private final AtomicReference<GraphqlSelectSpec> select = new AtomicReference<>();
    private final AtomicReference<String> operationName = new AtomicReference<>();
    private final AtomicReference<String> partialData = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getDocument() {
        return document.get();
    }

    public void setDocument(String document) {
        this.document.set(document);
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, String> getDocuments() {
        return documents;
    }

    public void setDocuments(Map<String, String> values) {
        documents.clear();
        if (values != null) {
            documents.putAll(values);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public GraphqlSelectSpec getSelect() {
        return select.get();
    }

    public void setSelect(GraphqlSelectSpec select) {
        this.select.set(select);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getOperationName() {
        return operationName.get();
    }

    public void setOperationName(String operationName) {
        this.operationName.set(operationName);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getPartialData() {
        return partialData.get();
    }

    public void setPartialData(String partialData) {
        this.partialData.set(partialData);
    }

    /** @return whether a response with errors and some data succeeds */
    @JsonIgnore
    public boolean allowsPartialData() {
        return PARTIAL_ALLOW.equalsIgnoreCase(partialData.get());
    }
}
