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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.core.exc.StreamReadException;

class YamlAliasesTest {

    private static byte[] yaml(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void rejectShouldReturnContentWithoutAlias() {
        byte[] content = yaml("a: 1\nb:\n  - x\n  - y\n");

        assertSame(content, YamlAliases.reject(content));
    }

    @Test
    void rejectShouldAcceptAnAnchorThatNoAliasUses() {
        byte[] content = yaml("a: &base\n  x: 1\n");

        assertSame(content, YamlAliases.reject(content));
    }

    @Test
    void rejectShouldNameTheAliasAndItsLocation() {
        StreamReadException error = assertThrows(StreamReadException.class,
                () -> YamlAliases.reject(yaml("a: &base\n  x: 1\nb: *base\n")));

        assertTrue(error.getMessage().contains("YAML alias '*base' is not supported"),
                error.getMessage());
        assertTrue(error.getMessage().contains("line: 3, column: 4"), error.getMessage());
    }

    @Test
    void rejectShouldFindAnAliasInASequenceAndAMergeKey() {
        assertThrows(StreamReadException.class,
                () -> YamlAliases.reject(yaml("a: &x 1\nb:\n  - *x\n")));
        assertThrows(StreamReadException.class,
                () -> YamlAliases.reject(yaml("a: &m\n  x: 1\nb:\n  <<: *m\n  y: 2\n")));
    }
}
