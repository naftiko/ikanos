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
package io.ikanos.engine.consumes.http;

import org.restlet.Request;
import org.restlet.data.ChallengeResponse;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.Header;
import org.restlet.engine.Engine;
import org.restlet.engine.header.ChallengeWriter;
import org.restlet.engine.security.AuthenticatorHelper;
import org.restlet.util.Series;

/**
 * Writes an {@code apikey}-authenticated value verbatim onto the wire, with no scheme prefix
 * and no separator, when it must be routed through the {@code Authorization} header.
 *
 * <p><b>Why this exists.</b> {@code Authorization} is one of Restlet's {@code STANDARD_HEADERS}:
 * a plain {@code request.getHeaders().add("Authorization", value)} is silently dropped (only a
 * warning is logged) — see #698. The only supported way to control that header's content is a
 * {@link org.restlet.data.ChallengeResponse} formatted by an {@link AuthenticatorHelper}.
 *
 * <p>The default formatting path ({@link org.restlet.engine.security.AuthenticatorUtils#formatResponse})
 * always writes {@code technicalName + " " + rawValue} — even when {@code technicalName} is
 * empty — so a naive {@code new ChallengeScheme("HTTP_APIKEY", "")} plus {@code setRawValue(...)}
 * still puts a leading space on the wire (e.g. {@code " SENTINEL-TOKEN"} instead of
 * {@code "SENTINEL-TOKEN"}). That contradicts the goal of emitting the value verbatim (#698) and
 * can break strict APIs.
 *
 * <p>This helper takes full control of the {@link ChallengeWriter} buffer instead: it clears
 * whatever the engine already wrote (the empty technical name + the separating space) and
 * writes the raw identifier only. For this helper to actually run, the {@link ChallengeResponse}
 * must carry its value via {@link ChallengeResponse#setIdentifier(String)} — {@code not}
 * {@code setRawValue(...)} — because {@code formatResponse()} short-circuits to the raw value
 * and never calls a registered helper when one is already set.
 */
class ApiKeyAuthorizationHeaderHelper extends AuthenticatorHelper {

    /**
     * Synthetic scheme (not an IANA-registered one) used only to route apikey values that must
     * land in the reserved {@code Authorization} header through this helper instead of through
     * {@code request.getHeaders()}.
     */
    static final ChallengeScheme SCHEME = new ChallengeScheme("HTTP_APIKEY", "");

    private ApiKeyAuthorizationHeaderHelper() {
        super(SCHEME, true, false);
    }

    /**
     * Registers this helper with the Restlet {@link Engine} singleton, once per JVM.
     *
     * <p>{@code Engine.getInstance()} is process-wide, so registering unconditionally on every
     * {@link HttpClientAdapter} construction (hence on every test) would append a duplicate
     * entry each time. {@link Engine#findHelper} is used as the idempotency check.
     */
    static void ensureRegistered() {
        Engine engine = Engine.getInstance();
        if (engine.findHelper(SCHEME, true, false) == null) {
            engine.getRegisteredAuthenticators().add(new ApiKeyAuthorizationHeaderHelper());
        }
    }

    @Override
    public void formatResponse(ChallengeWriter cw, ChallengeResponse challenge, Request request,
            Series<Header> httpHeaders) {
        // The engine already wrote "<technicalName> " (empty name + a separating space) into
        // the buffer before calling this helper. Discard it and write the raw identifier only.
        cw.getBuffer().setLength(0);
        cw.append(challenge.getIdentifier());
    }
}
