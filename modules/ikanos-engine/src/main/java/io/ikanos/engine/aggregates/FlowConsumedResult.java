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
package io.ikanos.engine.aggregates;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.Outcome;
import io.ikanos.engine.util.OperationStepExecutor;
import io.ikanos.spec.OutputParameterSpec;

/**
 * The result of calling an aggregate flow from a step, seen through {@link ConsumedResult}.
 *
 * <p>When the flow produced its own output (mapped from steps, mapped from a single call, or
 * mocked), that output is the body. It is already shaped by the flow's
 * {@code outputParameters}, the same object an MCP client receives as {@code structuredContent}
 * from a tool that {@code ref}s the flow, so {@link #operation()} declares no output parameters
 * and the calling step reads it as is.</p>
 *
 * <p>When the flow produced no output of its own, the result of its last consumed call is passed
 * through unchanged, including its status, its operation view, and binary bodies.</p>
 *
 * <p>A flow that fails does not produce a result: the failure propagates as an exception, as it
 * would for the flow's own caller.</p>
 */
public class FlowConsumedResult implements ConsumedResult {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JSON_MEDIA_TYPE = "application/json";

    private final AggregateFlow flow;
    private final FlowResult result;
    /** The flow's own output (mapped or mocked), or {@code null} to pass the last result through. */
    private final String ownOutput;

    FlowConsumedResult(AggregateFlow flow, FlowResult result) throws IOException {
        this.flow = flow;
        this.result = result;
        if (result.hasMappedOutput()) {
            this.ownOutput = result.mappedOutput;
        } else if (result.isMock()) {
            this.ownOutput = JSON.writeValueAsString(result.mockOutput);
        } else {
            this.ownOutput = null;
        }
    }

    /**
     * A view of a flow as a called operation. It declares no output parameters, because the
     * flow's output is already shaped when the calling step reads it.
     *
     * @param flow the flow
     * @return the view
     */
    static ConsumedOperationView viewOf(AggregateFlow flow) {
        return new ConsumedOperationView() {
            @Override
            public String name() {
                return flow.getName();
            }

            @Override
            public List<OutputParameterSpec> outputParameters() {
                return List.of();
            }

            @Override
            public String outputRawFormat() {
                return null;
            }

            @Override
            public String outputSchema() {
                return null;
            }

            @Override
            public String outputMediaType() {
                return null;
            }

            @Override
            public String maxBinarySize() {
                return null;
            }
        };
    }

    private ConsumedResult passThrough() {
        return ownOutput == null ? result.lastResult : null;
    }

    /**
     * The result that decides the status: the last consumed result when it is passed through, or
     * when it failed. A flow in single-call mode does not check its upstream status itself, so a
     * non-2xx answer must still fail the calling step even if the flow mapped an output from it.
     */
    private ConsumedResult statusSource() {
        ConsumedResult last = result.lastResult;
        if (last == null) {
            return null;
        }
        return ownOutput == null || !last.isSuccessful() ? last : null;
    }

    @Override
    public Outcome outcome() {
        ConsumedResult source = statusSource();
        return source != null ? source.outcome() : Outcome.SUCCESS;
    }

    @Override
    public int status() {
        ConsumedResult source = statusSource();
        return source != null ? source.status() : 200;
    }

    @Override
    public String reason() {
        ConsumedResult source = statusSource();
        return source != null ? source.reason() : "OK";
    }

    @Override
    public boolean hasBody() {
        if (ownOutput != null) {
            return true;
        }
        ConsumedResult last = passThrough();
        return last != null && last.hasBody();
    }

    @Override
    public String mediaType() {
        if (ownOutput != null) {
            return JSON_MEDIA_TYPE;
        }
        ConsumedResult last = passThrough();
        return last != null ? last.mediaType() : null;
    }

    @Override
    public String text() throws IOException {
        if (ownOutput != null) {
            return ownOutput;
        }
        ConsumedResult last = passThrough();
        return last != null ? last.text() : null;
    }

    @Override
    public JsonNode document() throws IOException {
        if (ownOutput != null) {
            return JSON.readTree(ownOutput);
        }
        ConsumedResult last = passThrough();
        return last != null ? last.document() : null;
    }

    @Override
    public byte[] bytes(long maxBytes) throws IOException {
        if (ownOutput != null) {
            byte[] bytes = ownOutput.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > maxBytes) {
                throw new OperationStepExecutor.BinarySizeExceededException(bytes.length,
                        maxBytes);
            }
            return bytes;
        }
        ConsumedResult last = passThrough();
        return last != null ? last.bytes(maxBytes) : null;
    }

    @Override
    public ConsumedOperationView operation() {
        ConsumedResult last = passThrough();
        return last != null ? last.operation() : viewOf(flow);
    }

    @Override
    public void release() {
        if (result.lastResult != null) {
            result.lastResult.release();
        }
    }
}
