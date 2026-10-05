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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.ClientAdapter;
import io.ikanos.engine.consumes.ConsumedInvocation;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.Outcome;
import io.ikanos.engine.util.OperationStepExecutor.BinarySizeExceededException;
import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.OperationSpec;

/**
 * Test-only consumed adapter that returns canned bodies without any network I/O and without any
 * Restlet type. If the engine can serve a capability through this adapter, the execution path is
 * no longer coupled to HTTP (design doc {@code consumed-invocation-carrier.md} §9).
 *
 * <p>Canned bodies are Mustache templates resolved against the call parameters, which lets tests
 * assert that step {@code with} values reach the adapter. Every invocation is recorded.</p>
 */
public class FakeClientAdapter extends ClientAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Parameters of every invocation, across all fake adapters, in call order. */
    public static final List<Map<String, Object>> CALLS = new CopyOnWriteArrayList<>();

    public FakeClientAdapter(Capability capability, FakeClientSpec spec) {
        super(capability, spec);
    }

    FakeClientSpec fakeSpec() {
        return (FakeClientSpec) getSpec();
    }

    @Override
    public ConsumedInvocation prepare(String operationName, Map<String, Object> parameters) {
        OperationSpec op = fakeSpec().getOperations().get(operationName);
        if (op == null) {
            return null;
        }
        String template = fakeSpec().getResponses().get(operationName);
        String body = template != null ? Resolver.resolveMustacheTemplate(template, parameters)
                : null;
        String mediaType = fakeSpec().getMediaTypes().getOrDefault(operationName,
                "application/json");
        int status = fakeSpec().getStatuses().getOrDefault(operationName, 200);
        ConsumedOperationView view = ConsumedOperationView.of(op, mediaType, null);
        Map<String, Object> captured = parameters != null ? Map.copyOf(parameters) : Map.of();

        return new ConsumedInvocation() {
            @Override
            public String namespace() {
                return getNamespace();
            }

            @Override
            public ConsumedOperationView operation() {
                return view;
            }

            @Override
            protected ClientSpanDescriptor spanDescriptor() {
                return new ClientSpanDescriptor("fake", "fake://" + operationName);
            }

            @Override
            protected ConsumedResult doInvoke() {
                CALLS.add(captured);
                return new FakeResult(view, status, body, mediaType);
            }
        };
    }

    @Override
    public void start() {
        // no-op
    }

    @Override
    public void stop() {
        // no-op
    }

    /** A protocol-neutral result over an in-memory body. */
    static final class FakeResult implements ConsumedResult {
        private final ConsumedOperationView operation;
        private final int status;
        private final String body;
        private final String mediaType;

        FakeResult(ConsumedOperationView operation, int status, String body, String mediaType) {
            this.operation = operation;
            this.status = status;
            this.body = body;
            this.mediaType = mediaType;
        }

        @Override
        public Outcome outcome() {
            return Outcome.fromHttpStatus(status);
        }

        @Override
        public int status() {
            return status;
        }

        @Override
        public String reason() {
            return "fake";
        }

        @Override
        public boolean hasBody() {
            return body != null && !body.isEmpty();
        }

        @Override
        public String mediaType() {
            return mediaType;
        }

        @Override
        public String text() {
            return body;
        }

        @Override
        public JsonNode document() throws IOException {
            return hasBody() && !operation.isBinary() ? JSON.readTree(body) : null;
        }

        @Override
        public byte[] bytes(long maxBytes) throws IOException {
            if (body == null) {
                return null;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > maxBytes) {
                throw new BinarySizeExceededException(bytes.length, maxBytes);
            }
            return bytes;
        }

        @Override
        public ConsumedOperationView operation() {
            return operation;
        }
    }
}
