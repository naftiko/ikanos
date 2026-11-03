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
package io.ikanos.durable;

import java.util.Objects;

/**
 * The recorded outcome of an action: the value it produced, or the failure that ended it. The
 * engine never inspects a value; it only journals it through the {@code PayloadCodec}.
 */
public sealed interface ActionOutcome {

    /** The action produced a value. */
    record Succeeded(Object value) implements ActionOutcome {
    }

    /** The action failed after its last attempt. */
    record Failed(Failure failure) implements ActionOutcome {

        public Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
