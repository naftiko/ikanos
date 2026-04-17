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

import io.opentelemetry.context.propagation.TextMapGetter;
import org.restlet.Request;
import java.util.Collections;

/**
 * Extracts W3C trace context headers from an inbound Restlet {@link Request}.
 *
 * <p>Used by server adapters (REST, MCP HTTP) to continue an upstream trace.</p>
 */
@SuppressWarnings("null")
public class RestletHeaderGetter implements TextMapGetter<Request> {

    public static final RestletHeaderGetter INSTANCE = new RestletHeaderGetter();

    @Override
    public Iterable<String> keys(Request request) {
        if (request == null || request.getHeaders() == null) {
            return Collections.emptyList();
        }
        return request.getHeaders().getNames();
    }

    @Override
    public String get(Request request, String key) {
        if (request == null || request.getHeaders() == null || key == null) {
            return null;
        }
        return request.getHeaders().getFirstValue(key, true);
    }
}
