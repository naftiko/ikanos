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
package io.ikanos.engine.definitions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Keeps the expansion bounds that Polychro's {@code definitions.js} copies equal to the engine's:
 * Polychro must never report a budget or depth error on a document the engine loads.
 */
class DefinitionsBudgetParityTest {

    private static final String RULE_FUNCTION = "rules/functions/definitions.js";

    @Test
    void polychroBindingNodesShouldEqualTheEngineExpansionBudget() throws Exception {
        assertEquals(DefinitionsExpander.MAX_EXPANDED_NODES,
                constant(ruleFunction(), "MAX_BINDING_NODES"));
    }

    @Test
    void polychroBindingDepthShouldEqualTheEngineExpansionDepth() throws Exception {
        assertEquals(DefinitionsExpander.MAX_EXPANDED_DEPTH,
                constant(ruleFunction(), "MAX_BINDING_DEPTH"));
    }

    /** The rule function as shipped in ikanos-spec, read from the class path. */
    private static String ruleFunction() throws Exception {
        try (InputStream in = DefinitionsBudgetParityTest.class.getClassLoader()
                .getResourceAsStream(RULE_FUNCTION)) {
            assertNotNull(in, RULE_FUNCTION + " is not on the class path");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int constant(String source, String name) {
        Matcher matcher = Pattern.compile("(?m)^const " + name + " = ([0-9_]+);").matcher(source);
        assertTrue(matcher.find(), name + " is not declared in " + RULE_FUNCTION);
        return Integer.parseInt(matcher.group(1).replace("_", ""));
    }
}
