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
package io.ikanos.engine.consumes;

/**
 * Protocol-neutral outcome of a consumed invocation.
 *
 * <p>Every client adapter maps its native result to one of these values at the source, where the
 * protocol knowledge lives. Exposers use it for success/failure decisions and use
 * {@link ConsumedResult#status()} when they need a number to put on the wire.</p>
 */
public enum Outcome {

    /** The upstream call succeeded (HTTP 1xx-3xx, or the protocol equivalent). */
    SUCCESS,

    /** The upstream rejected the request (HTTP 4xx, or the protocol equivalent). */
    CLIENT_ERROR,

    /** The upstream failed (HTTP 5xx, a tool-level error, a JSON-RPC error, ...). */
    UPSTREAM_ERROR;

    /**
     * Map an HTTP status code to an outcome.
     *
     * @param status the HTTP status code
     * @return {@link #SUCCESS} below 400, {@link #CLIENT_ERROR} for 4xx, otherwise
     *         {@link #UPSTREAM_ERROR}
     */
    public static Outcome fromHttpStatus(int status) {
        if (status < 400) {
            return SUCCESS;
        }
        return status < 500 ? CLIENT_ERROR : UPSTREAM_ERROR;
    }
}
