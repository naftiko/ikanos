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

import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Marker subclass of {@link BindingSpec} used purely as a Jackson deserialization target for
 * inline binding entries.
 *
 * <p>Its only job is to opt out of the custom {@link BindingSpecDeserializer} discriminator
 * (via {@code @JsonDeserialize(using = JsonDeserializer.None.class)}) so the default mapper
 * can populate fields directly. This is the same pattern used by {@code HttpClientSpec} relative
 * to {@code ClientSpec}.</p>
 *
 * <p>The runtime, ruleset, and API only ever observe instances of the public {@link BindingSpec}
 * type; this subclass adds no fields and no behavior.</p>
 */
@JsonDeserialize(using = JsonDeserializer.None.class)
public class InlineBindingSpec extends BindingSpec {

    public InlineBindingSpec() {
        super();
    }
}
