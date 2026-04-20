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
package io.naftiko.engine.telemetry;

import io.opentelemetry.context.propagation.TextMapSetter;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.restlet.Request;

/**
 * Injects W3C trace context headers into an outbound Restlet {@link Request}.
 *
 * <p>Used by the HTTP client adapter to propagate trace context to consumed APIs.</p>
 */
public class RestletHeaderSetter implements TextMapSetter<Request> {

    public static final RestletHeaderSetter INSTANCE = new RestletHeaderSetter();

    @Override
    public void set(@Nullable Request request, @Nonnull String key, @Nonnull String value) {
        if (request != null && key != null && value != null) {
            request.getHeaders().set(key, value);
        }
    }
}
