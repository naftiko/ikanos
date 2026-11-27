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

/**
 * An {@code end} step: ends the flow early, as a success. The steps after it do not run, and the
 * flow's output is built from what the steps before it gathered.
 *
 * <p>It is usually guarded, such as {@code onlyIf: "outcome.find == 'not-found'"}: an
 * unguarded {@code end} makes every step after it unreachable.</p>
 */
public class OperationStepEndSpec extends OperationStepSpec {

    public OperationStepEndSpec() {
        this(null);
    }

    public OperationStepEndSpec(String name) {
        super("end", name);
    }
}
