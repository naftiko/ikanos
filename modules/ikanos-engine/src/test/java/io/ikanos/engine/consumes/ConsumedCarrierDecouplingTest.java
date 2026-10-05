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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Guards the decoupling from {@code consumed-invocation-carrier.md} §9: the protocol-neutral
 * engine classes must execute consumed calls through {@link ConsumedInvocation} and
 * {@link ConsumedResult} only, never through HTTP adapter types.
 *
 * <p>{@code ResourceRestlet} is checked separately because its REST forward mode is HTTP-to-HTTP
 * by design (§10.4) and is the single allowed exception.</p>
 */
class ConsumedCarrierDecouplingTest {

    private static final Path MAIN = Path.of("src/main/java/io/ikanos/engine");

    private static final List<String> FORBIDDEN = List.of(
            "HttpClientAdapter", "HttpClientOperationSpec", "HttpInvocation",
            "HttpConsumedResult", "HandlingContext");

    private static List<String> violations(String source) {
        List<String> found = new ArrayList<>();
        for (String type : FORBIDDEN) {
            if (source.contains(type)) {
                found.add(type);
            }
        }
        return found;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "util/OperationStepExecutor.java",
            "aggregates/AggregateFlow.java",
            "aggregates/FlowResult.java",
            "exposes/mcp/ToolHandler.java",
            "exposes/mcp/ResourceHandler.java"})
    void neutralEngineClassShouldNotReferenceHttpCarrierTypes(String relativePath)
            throws Exception {
        String source = Files.readString(MAIN.resolve(relativePath), StandardCharsets.UTF_8);

        List<String> found = violations(source);

        assertTrue(found.isEmpty(), relativePath + " references " + found);
    }

    @ParameterizedTest
    @ValueSource(strings = {"exposes/rest/ResourceRestlet.java"})
    void restExposerShouldReferenceHttpAdapterOnlyInForwardMode(String relativePath)
            throws Exception {
        String source = Files.readString(MAIN.resolve(relativePath), StandardCharsets.UTF_8);
        int forward = source.indexOf("boolean handleFromForwardSpec(");
        int afterForward = source.indexOf("void copyTrustedHeaders(");
        assertTrue(forward > 0 && afterForward > forward, "forward-mode method not found");

        String outsideForward = source.substring(0, forward) + source.substring(afterForward);
        List<String> found = violations(outsideForward.replace(
                "import io.ikanos.engine.consumes.http.HttpClientAdapter;", ""));

        assertTrue(found.isEmpty(), relativePath + " references " + found
                + " outside forward mode");
    }
}
