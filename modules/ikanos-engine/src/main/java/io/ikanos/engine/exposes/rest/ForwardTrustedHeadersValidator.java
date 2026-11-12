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
package io.ikanos.engine.exposes.rest;

import java.util.ArrayList;
import java.util.List;
import io.ikanos.engine.consumes.http.StandardRequestHeaders;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.exposes.rest.RestServerForwardSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;

/**
 * Refuses a REST {@code forward.trustedHeaders} entry that forward mode can never send upstream,
 * such as {@code Host} or a response header (#1001). Such a header used to be dropped on every
 * request with only a log warning; it now fails the capability at load time and
 * {@code ikanos validate}.
 */
public final class ForwardTrustedHeadersValidator {

    private ForwardTrustedHeadersValidator() {
    }

    /**
     * @param spec the capability spec; {@code null} is a no-op
     * @throws IllegalArgumentException listing every trusted header that cannot be forwarded
     */
    public static void validate(IkanosSpec spec) {
        List<String> errors = findErrors(spec);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    /**
     * @param spec the capability spec, may be null
     * @return one message per trusted header that cannot be forwarded, in declaration order
     */
    public static List<String> findErrors(IkanosSpec spec) {
        List<String> errors = new ArrayList<>();
        if (spec == null) {
            return errors;
        }
        List<ServerSpec> servers = new ArrayList<>();
        if (spec.getExposes() != null) {
            servers.addAll(spec.getExposes());
        }
        if (spec.getCapability() != null && spec.getCapability().getExposes() != null) {
            servers.addAll(spec.getCapability().getExposes());
        }
        for (ServerSpec server : servers) {
            if (server instanceof RestServerSpec rest && rest.getResources() != null) {
                for (RestServerResourceSpec resource : rest.getResources().values()) {
                    check(resource.getForward(), "REST resource '" + resource.getPath() + "' in '"
                            + rest.getNamespace() + "'", errors);
                }
            }
        }
        return errors;
    }

    private static void check(RestServerForwardSpec forward, String where, List<String> errors) {
        if (forward == null || forward.getTrustedHeaders() == null) {
            return;
        }
        for (String header : forward.getTrustedHeaders()) {
            String reason = StandardRequestHeaders.notSendableReason(header);
            if (reason != null) {
                errors.add(where + " lists '" + header + "' in forward.trustedHeaders, but "
                        + header + " " + reason + ", so forward mode never sends it upstream");
            }
        }
    }
}
