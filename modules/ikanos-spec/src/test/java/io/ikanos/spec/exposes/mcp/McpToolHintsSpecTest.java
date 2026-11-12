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
package io.ikanos.spec.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import io.ikanos.spec.util.IkanosMappers;

/**
 * Unit tests for {@link McpToolHintsSpec}.
 */
public class McpToolHintsSpecTest {

    @Test
    public void noArgConstructorShouldLeaveAllHintsNull() {
        McpToolHintsSpec spec = new McpToolHintsSpec();

        assertNull(spec.getReadOnly());
        assertNull(spec.getDestructive());
        assertNull(spec.getIdempotent());
        assertNull(spec.getOpenWorld());
    }

    @Test
    public void allArgsConstructorShouldAssignEachHint() {
        McpToolHintsSpec spec = new McpToolHintsSpec(
                Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE);

        assertEquals(Boolean.TRUE, spec.getReadOnly());
        assertEquals(Boolean.FALSE, spec.getDestructive());
        assertEquals(Boolean.TRUE, spec.getIdempotent());
        assertEquals(Boolean.FALSE, spec.getOpenWorld());
    }

    @Test
    public void settersShouldRoundTripValues() {
        McpToolHintsSpec spec = new McpToolHintsSpec();
        spec.setReadOnly(Boolean.FALSE);
        spec.setDestructive(Boolean.TRUE);
        spec.setIdempotent(Boolean.FALSE);
        spec.setOpenWorld(Boolean.TRUE);

        assertEquals(Boolean.FALSE, spec.getReadOnly());
        assertEquals(Boolean.TRUE, spec.getDestructive());
        assertEquals(Boolean.FALSE, spec.getIdempotent());
        assertEquals(Boolean.TRUE, spec.getOpenWorld());
    }

    @Test
    public void shouldDeserializeFromYaml() throws Exception {
        String yaml = """
                readOnly: true
                destructive: false
                idempotent: true
                openWorld: false
                """;

        ObjectMapper mapper = IkanosMappers.yaml();
        McpToolHintsSpec spec = mapper.readValue(yaml, McpToolHintsSpec.class);

        assertEquals(Boolean.TRUE, spec.getReadOnly());
        assertEquals(Boolean.FALSE, spec.getDestructive());
        assertEquals(Boolean.TRUE, spec.getIdempotent());
        assertEquals(Boolean.FALSE, spec.getOpenWorld());
    }

    @Test
    public void mergeShouldReturnNullWhenBothSidesAreNull() {
        assertNull(McpToolHintsSpec.merge(null, null));
    }

    @Test
    public void mergeShouldReturnBaseWhenOverrideIsNull() {
        McpToolHintsSpec base = new McpToolHintsSpec(true, null, null, true);

        assertSame(base, McpToolHintsSpec.merge(base, null));
    }

    @Test
    public void mergeShouldReturnOverrideWhenBaseIsNull() {
        McpToolHintsSpec override = new McpToolHintsSpec(false, true, null, null);

        assertSame(override, McpToolHintsSpec.merge(null, override));
    }

    @Test
    public void mergeShouldLetEachDeclaredOverrideFieldWinWhenBothSidesAreSet() {
        McpToolHintsSpec base = new McpToolHintsSpec(true, false, false, true);
        McpToolHintsSpec override = new McpToolHintsSpec(false, null, true, null);

        McpToolHintsSpec merged = McpToolHintsSpec.merge(base, override);

        assertEquals(Boolean.FALSE, merged.getReadOnly());
        assertEquals(Boolean.FALSE, merged.getDestructive());
        assertEquals(Boolean.TRUE, merged.getIdempotent());
        assertEquals(Boolean.TRUE, merged.getOpenWorld());
    }

    @Test
    public void mergeShouldLeaveBaseUnchangedWhenOverrideDeclaresFields() {
        // Server hints are shared by every tool of the server: resolving one tool must not
        // leak its overrides into the next one.
        McpToolHintsSpec base = new McpToolHintsSpec(true, null, null, true);

        McpToolHintsSpec.merge(base, new McpToolHintsSpec(false, true, true, false));

        assertEquals(Boolean.TRUE, base.getReadOnly());
        assertNull(base.getDestructive());
        assertNull(base.getIdempotent());
        assertEquals(Boolean.TRUE, base.getOpenWorld());
    }

    @Test
    public void mergeShouldResolveReadOnlyFalseWhenOverrideDeclaresDestructiveWithoutReadOnly() {
        // MCP reads destructiveHint only when readOnlyHint is false: a tool declared destructive
        // must not inherit readOnly: true from a broader level (#981 review).
        McpToolHintsSpec base = new McpToolHintsSpec(true, null, null, null);
        McpToolHintsSpec override = new McpToolHintsSpec(null, true, null, null);

        McpToolHintsSpec merged = McpToolHintsSpec.merge(base, override);

        assertEquals(Boolean.FALSE, merged.getReadOnly());
        assertEquals(Boolean.TRUE, merged.getDestructive());
    }

    @Test
    public void mergeShouldKeepDeclaredReadOnlyWhenOverrideDeclaresBothReadOnlyAndDestructive() {
        McpToolHintsSpec base = new McpToolHintsSpec(false, null, null, null);
        McpToolHintsSpec override = new McpToolHintsSpec(true, true, null, null);

        McpToolHintsSpec merged = McpToolHintsSpec.merge(base, override);

        assertEquals(Boolean.TRUE, merged.getReadOnly());
        assertEquals(Boolean.TRUE, merged.getDestructive());
    }

    @Test
    public void mergeShouldInheritReadOnlyWhenOverrideDeclaresDestructiveFalse() {
        McpToolHintsSpec base = new McpToolHintsSpec(true, null, null, null);
        McpToolHintsSpec override = new McpToolHintsSpec(null, false, null, null);

        McpToolHintsSpec merged = McpToolHintsSpec.merge(base, override);

        assertEquals(Boolean.TRUE, merged.getReadOnly());
        assertEquals(Boolean.FALSE, merged.getDestructive());
    }
}
