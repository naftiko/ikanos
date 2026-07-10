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
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON Structure Specification Element.
 *
 * <h2>Thread safety</h2>
 * Each scalar field is held in an {@link AtomicReference}. List fields use
 * {@link CopyOnWriteArrayList}. This satisfies SonarQube rule {@code java:S3077}.
 */
public class StructureSpec<T extends StructureSpec<T>> {

    private final AtomicReference<String> name = new AtomicReference<>();
    private final AtomicReference<String> type = new AtomicReference<>();
    private final AtomicReference<T> items = new AtomicReference<>();
    private final AtomicReference<T> values = new AtomicReference<>();
    private final AtomicReference<String> constant = new AtomicReference<>();
    private final AtomicReference<String> selector = new AtomicReference<>();
    private final AtomicReference<String> maxLength = new AtomicReference<>();
    private final AtomicReference<Integer> precision = new AtomicReference<>();
    private final AtomicReference<Integer> scale = new AtomicReference<>();
    private final AtomicReference<String> contentEncoding = new AtomicReference<>();
    private final AtomicReference<String> contentCompression = new AtomicReference<>();
    private final AtomicReference<String> contentMediaType = new AtomicReference<>();
    private final AtomicReference<String> description = new AtomicReference<>();

    @JsonProperty("properties")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final List<T> properties;

    @JsonProperty("required")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final List<String> required;

    @JsonProperty("choices")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final List<T> choices;

    @JsonProperty("enum")
    private final List<String> enumeration;

    @JsonProperty("tuple")
    private final List<String> tuple;

    @JsonProperty("examples")
    private final List<String> examples;

    public StructureSpec() {
        this(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public StructureSpec(String name, String type, T items, T values,
            String constant, String selector, String maxLength, Integer precision, Integer scale,
            String contentEncoding, String contentCompression, String contentMediaType,
            String description) {
        this.name.set(name);
        this.type.set(type);
        this.properties = new CopyOnWriteArrayList<>();
        this.required = new CopyOnWriteArrayList<>();
        this.items.set(items);
        this.values.set(values);
        this.constant.set(constant);
        this.enumeration = new CopyOnWriteArrayList<>();
        this.choices = new CopyOnWriteArrayList<>();
        this.selector.set(selector);
        this.tuple = new CopyOnWriteArrayList<>();
        this.maxLength.set(maxLength);
        this.precision.set(precision);
        this.scale.set(scale);
        this.contentEncoding.set(contentEncoding);
        this.contentCompression.set(contentCompression);
        this.contentMediaType.set(contentMediaType);
        this.description.set(description);
        this.examples = new CopyOnWriteArrayList<>();
    }

    @JsonProperty("name")
    public String getName() {
        return name.get();
    }

    @JsonProperty("name")
    public void setName(String name) {
        this.name.set(name);
    }

    @JsonProperty("type")
    public String getType() {
        return type.get();
    }

    @JsonProperty("type")
    public void setType(String type) {
        this.type.set(type);
    }

    public List<T> getProperties() {
        return properties;
    }

    public List<String> getRequired() {
        return required;
    }

    @JsonProperty("items")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public T getItems() {
        return items.get();
    }

    @JsonProperty("items")
    public void setItems(T items) {
        this.items.set(items);
    }

    @JsonProperty("values")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public T getValues() {
        return values.get();
    }

    @JsonProperty("values")
    public void setValues(T values) {
        this.values.set(values);
    }

    @JsonProperty("const")
    public String getConstant() {
        return constant.get();
    }

    @JsonProperty("const")
    public void setConstant(String constant) {
        this.constant.set(constant);
    }

    public List<String> getEnumeration() {
        return this.enumeration;
    }

    public List<T> getChoices() {
        return choices;
    }

    @JsonProperty("selector")
    public String getSelector() {
        return selector.get();
    }

    @JsonProperty("selector")
    public void setSelector(String selector) {
        this.selector.set(selector);
    }

    @JsonProperty("maxLength")
    public String getMaxLength() {
        return maxLength.get();
    }

    @JsonProperty("maxLength")
    public void setMaxLength(String maxLength) {
        this.maxLength.set(maxLength);
    }

    @JsonProperty("precision")
    public Integer getPrecision() {
        return precision.get();
    }

    @JsonProperty("precision")
    public void setPrecision(Integer precision) {
        this.precision.set(precision);
    }

    @JsonProperty("scale")
    public Integer getScale() {
        return scale.get();
    }

    @JsonProperty("scale")
    public void setScale(Integer scale) {
        this.scale.set(scale);
    }

    @JsonProperty("contentEncoding")
    public String getContentEncoding() {
        return contentEncoding.get();
    }

    @JsonProperty("contentEncoding")
    public void setContentEncoding(String contentEncoding) {
        this.contentEncoding.set(contentEncoding);
    }

    @JsonProperty("contentCompression")
    public String getContentCompression() {
        return contentCompression.get();
    }

    @JsonProperty("contentCompression")
    public void setContentCompression(String contentCompression) {
        this.contentCompression.set(contentCompression);
    }

    @JsonProperty("contentMediaType")
    public String getContentMediaType() {
        return contentMediaType.get();
    }

    @JsonProperty("contentMediaType")
    public void setContentMediaType(String contentMediaType) {
        this.contentMediaType.set(contentMediaType);
    }

    @JsonProperty("description")
    public String getDescription() {
        return description.get();
    }

    @JsonProperty("description")
    public void setDescription(String description) {
        this.description.set(description);
    }

    public List<String> getExamples() {
        return examples;
    }

    public List<String> getTuple() {
        return tuple;
    }

}
