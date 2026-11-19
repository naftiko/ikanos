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
package io.ikanos.engine.util;

/**
 * A template, a {@code with} value or a parameter lookup would pass on the engine's
 * {@link CallerRoot} whole, which would send every claim of the caller's token to a consumed API
 * (#998), for example through {@code {{.}}}, {@code {{auth.}}} or a {@code with} alias of the
 * root. The call fails instead. It is a configuration error, not a shortfall of the token, so the
 * adapters answer it as a failed call.
 *
 * <p>It is an {@link IllegalArgumentException}, like every bad template, and a type of its own
 * so that a consumed parameter that fails with it fails the call rather than being left out.</p>
 */
public class CallerRootExposedException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public CallerRootExposedException() {
        super("A template or a 'with' value passes '" + Resolver.CALLER_ROOT + "' whole, which"
                + " would send every claim of the caller's token. Read each claim as a value,"
                + " such as {{" + Resolver.CALLER_CLAIMS + ".sub}}");
    }
}
