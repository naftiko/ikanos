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
package io.ikanos.spec.openapi;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.ikanos.spec.consumes.http.BasicAuthenticationSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.consumes.http.DigestAuthenticationSpec;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;
import io.ikanos.spec.consumes.http.HttpClientResourceSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * Writes an imported {@link HttpClientSpec} as a standalone consumes document in the shape
 * required by {@code ikanos-schema.json}.
 *
 * <p>The spec model's default Jackson serialization keeps the legacy list layout (named
 * parameter lists, {@code name} on resources and operations, polymorphic type tags), which the
 * schema rejects. This writer builds the document explicitly instead:</p>
 * <ul>
 *   <li>resources, operations and parameters are maps keyed by name, without a {@code name}
 *       property;</li>
 *   <li>input parameters keep only {@code in} and {@code value} ({@code ConsumedInputParameter});
 *   </li>
 *   <li>named output parameters become {@code {type, value}} with the JsonPath as {@code value}
 *       ({@code ConsumedOutputParameter}); unnamed outputs (a whole-response array or scalar) are
 *       dropped, since the raw response is already returned when none are declared.</li>
 * </ul>
 */
public class OasImportWriter {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /**
     * Build the consumes document for an imported adapter.
     *
     * @param httpClient the adapter produced by {@link OasImportConverter}
     * @return a document with the schema version and a single {@code consumes} entry
     */
    public ObjectNode toDocument(HttpClientSpec httpClient) {
        ObjectNode document = NODES.objectNode();
        document.put("ikanos", VersionHelper.getSchemaVersion());
        document.putArray("consumes").add(adapterNode(httpClient));
        return document;
    }

    ObjectNode adapterNode(HttpClientSpec httpClient) {
        ObjectNode adapter = NODES.objectNode();
        adapter.put("type", "http");
        putIfPresent(adapter, "namespace", httpClient.getNamespace());
        putIfPresent(adapter, "baseUri", httpClient.getBaseUri());
        putInputParameters(adapter, httpClient.getInputParameters());
        if (httpClient.getAuthentication() != null) {
            adapter.set("authentication", authenticationNode(httpClient.getAuthentication()));
        }

        ObjectNode resources = NODES.objectNode();
        for (Map.Entry<String, HttpClientResourceSpec> entry
                : httpClient.getResources().entrySet()) {
            resources.set(entry.getKey(), resourceNode(entry.getValue()));
        }
        if (!resources.isEmpty()) {
            adapter.set("resources", resources);
        }
        return adapter;
    }

    ObjectNode resourceNode(HttpClientResourceSpec resource) {
        ObjectNode node = NODES.objectNode();
        putIfPresent(node, "path", resource.getPath());
        putIfPresent(node, "display", resource.getDisplay());
        putIfPresent(node, "description", resource.getDescription());
        putTags(node, resource.getTags());
        putInputParameters(node, resource.getInputParameters());

        ObjectNode operations = node.putObject("operations");
        for (Map.Entry<String, HttpClientOperationSpec> entry
                : resource.getOperations().entrySet()) {
            operations.set(entry.getKey(), operationNode(entry.getValue()));
        }
        return node;
    }

    ObjectNode operationNode(HttpClientOperationSpec operation) {
        ObjectNode node = NODES.objectNode();
        putIfPresent(node, "method", operation.getMethod());
        putIfPresent(node, "display", operation.getDisplay());
        putIfPresent(node, "description", operation.getDescription());
        putTags(node, operation.getTags());
        putInputParameters(node, operation.getInputParameters());
        putIfPresent(node, "outputRawFormat", operation.getOutputRawFormat());
        putIfPresent(node, "outputSchema", operation.getOutputSchema());

        ObjectNode outputs = NODES.objectNode();
        for (OutputParameterSpec output : operation.getOutputParameters()) {
            if (output.getName() == null || output.getName().isBlank()) {
                continue;
            }
            ObjectNode outputNode = NODES.objectNode();
            putIfPresent(outputNode, "type", output.getType());
            putIfPresent(outputNode, "value", output.getMapping());
            outputs.set(output.getName(), outputNode);
        }
        if (!outputs.isEmpty()) {
            node.set("outputParameters", outputs);
        }
        return node;
    }

    ObjectNode authenticationNode(AuthenticationSpec authentication) {
        ObjectNode node = NODES.objectNode();
        putIfPresent(node, "type", authentication.getType());
        if (authentication instanceof BearerAuthenticationSpec bearer) {
            putIfPresent(node, "token", bearer.getToken());
        } else if (authentication instanceof ApiKeyAuthenticationSpec apiKey) {
            putIfPresent(node, "key", apiKey.getKey());
            putIfPresent(node, "value", apiKey.getValue());
            putIfPresent(node, "placement", apiKey.getPlacement());
        } else if (authentication instanceof BasicAuthenticationSpec basic) {
            putIfPresent(node, "username", basic.getUsername());
            putIfPresent(node, "password", toText(basic.getPassword()));
        } else if (authentication instanceof DigestAuthenticationSpec digest) {
            putIfPresent(node, "username", digest.getUsername());
            putIfPresent(node, "password", toText(digest.getPassword()));
        }
        return node;
    }

    private static void putInputParameters(ObjectNode parent, List<InputParameterSpec> params) {
        if (params == null || params.isEmpty()) {
            return;
        }
        ObjectNode inputs = parent.putObject("inputParameters");
        for (InputParameterSpec param : params) {
            ObjectNode input = NODES.objectNode();
            putIfPresent(input, "in", param.getIn());
            putIfPresent(input, "value", param.getValue());
            inputs.set(param.getName(), input);
        }
    }

    private static void putTags(ObjectNode parent, List<String> tags) {
        if (tags != null && !tags.isEmpty()) {
            tags.forEach(parent.putArray("tags")::add);
        }
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static String toText(char[] value) {
        return value != null ? new String(value) : null;
    }

}
