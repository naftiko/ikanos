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

import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A {@code forEach} step: runs {@code do} once for each item of a list, in order.
 *
 * <p>{@code items:} names the list, as a guard does: a bare name ({@code merged}) or a CEL
 * expression that gives a list. Each run reads the current item under {@code as}
 * ({@code item} by default). {@code do:} is one step or a {@code sequence}; the step's output
 * is the list of each item's output. An optional {@code limit} runs the first items only, as
 * SQL's {@code LIMIT} does: a number, or the name of an input that gives one. If an item fails,
 * the {@code forEach} step fails.</p>
 */
public class OperationStepForEachSpec extends OperationStepSpec implements NestedSteps {

    /** The name the current item is read under when the step declares no {@code as}. */
    public static final String DEFAULT_ITEM = "item";

    /** The name of {@code do} when it declares none. */
    public static final String DEFAULT_DO = "do";

    @JsonProperty("items")
    private volatile String items;

    @JsonProperty("as")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String as;

    @JsonProperty("limit")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile Object limit;

    @JsonProperty("do")
    private volatile OperationStepSpec body;

    public OperationStepForEachSpec() {
        this(null);
    }

    public OperationStepForEachSpec(String name) {
        super("forEach", name);
    }

    public String getItems() {
        return items;
    }

    public void setItems(String items) {
        this.items = items;
    }

    public String getAs() {
        return as;
    }

    public void setAs(String as) {
        this.as = as;
    }

    /** @return the name the current item is read under: {@code as}, or {@code item} */
    @JsonIgnore
    public String itemName() {
        return as != null && !as.isBlank() ? as : DEFAULT_ITEM;
    }

    /** @return the limit: a number, the name of an input that gives one, or {@code null} */
    public Object getLimit() {
        return limit;
    }

    public void setLimit(Object limit) {
        this.limit = limit;
    }

    public OperationStepSpec getDo() {
        return body;
    }

    /** Sets {@code do}, naming it {@code do} when it declares no name of its own. */
    public void setDo(OperationStepSpec body) {
        if (body != null && body.getName() == null) {
            body.setName(DEFAULT_DO);
        }
        this.body = body;
    }

    /** {@code do}, named {@code do} when it declares no name of its own. */
    @Override
    public Map<String, OperationStepSpec> nestedSteps() {
        Map<String, OperationStepSpec> nested = new LinkedHashMap<>();
        if (body != null) {
            nested.put(body.getName(), body);
        }
        return nested;
    }
}
