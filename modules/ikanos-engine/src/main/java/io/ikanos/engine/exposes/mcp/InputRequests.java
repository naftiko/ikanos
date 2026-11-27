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
package io.ikanos.engine.exposes.mcp;

import io.ikanos.engine.execution.InputRequiredException;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code inputRequests} map an MCP server sends when a run stops at {@code elicit} steps: one
 * {@code elicitation/create} request per question, keyed by the question's key: in form mode
 * with a {@code requestedSchema}, or in URL mode with the page's {@code url}. Multi round-trip
 * requests, tasks and REST operations that ask send the same map.
 */
public final class InputRequests {

    private static final ObjectMapper MAPPER = IkanosMappers.json();

    private InputRequests() {
    }

    /** The {@code inputRequests} for the questions a run stopped at. */
    public static ObjectNode of(InputRequiredException e) {
        ObjectNode requests = MAPPER.createObjectNode();
        for (InputRequiredException.Question question : e.questions()) {
            if (question.request() != null) {
                // An upstream's question, passed through unchanged.
                requests.set(question.key(), question.request());
                continue;
            }
            ObjectNode request = requests.putObject(question.key());
            request.put("method", "elicitation/create");
            ObjectNode requestParams = request.putObject("params");
            if (question.url() != null) {
                // URL mode: the caller completes a step out of band, on the page. Its accept only
                // means consent; the step's check decides on the retry.
                requestParams.put("mode", "url");
                requestParams.put("url", question.url().url());
                requestParams.put("elicitationId", question.url().elicitationId());
                requestParams.put("message", question.message() != null ? question.message()
                        : question.step().getName());
                continue;
            }
            requestParams.put("mode", "form");
            // Never the step's message as written: a template would reach the caller unrendered.
            requestParams.put("message", question.message() != null ? question.message()
                    : question.step().getName());
            ObjectNode schema = requestParams.putObject("requestedSchema");
            schema.put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            ArrayNode required = schema.putArray("required");
            if (question.step().getFields() != null) {
                question.step().getFields().forEach((name, property) -> {
                    properties.set(name, MAPPER.valueToTree(property));
                    required.add(name);
                });
            }
        }
        return requests;
    }
}
