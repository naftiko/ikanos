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

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * A {@code parallel} step: runs its tracks at the same time, and continues once every track has
 * finished.
 *
 * <p>{@code tracks:} is a list of named steps, like {@code steps:}. A track is one step, or a
 * {@code sequence} of steps. Each track sees the outputs of the steps before the
 * {@code parallel} step, never another track's; once the group finishes, the steps after it read
 * each track's output under the track's name. If one track fails, the {@code parallel} step
 * fails.</p>
 */
public class OperationStepParallelSpec extends OperationStepSpec implements NestedSteps {

    @JsonProperty("tracks")
    @JsonDeserialize(using = OperationStepMapDeserializer.class)
    @JsonSerialize(using = OperationStepListSerializer.class)
    private final Map<String, OperationStepSpec> tracks = StepOrder.newSteps();

    public OperationStepParallelSpec() {
        this(null);
    }

    public OperationStepParallelSpec(String name) {
        super("parallel", name);
    }

    public Map<String, OperationStepSpec> getTracks() {
        return tracks;
    }

    public void setTracks(Map<String, OperationStepSpec> tracks) {
        if (tracks == null) {
            return;
        }
        StepOrder.requireOrdered(tracks);
        synchronized (this.tracks) {
            this.tracks.clear();
            this.tracks.putAll(tracks);
        }
    }

    @Override
    public Map<String, OperationStepSpec> nestedSteps() {
        return tracks;
    }
}
