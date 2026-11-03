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
package io.ikanos.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class NodePathTest {

    @Test
    void childShouldAppendASegment() {
        assertEquals("steps/gather/cargo",
                NodePath.of("steps").child("gather").child("cargo").value());
    }

    @Test
    void itemShouldAppendAnIndexedSegment() {
        assertEquals("steps/gather/cargo/page[4]",
                new NodePath("steps/gather/cargo").item("page", 4).value());
    }

    @Test
    void itemShouldRejectANegativeIndex() {
        assertThrows(IllegalArgumentException.class, () -> NodePath.of("steps").item("map", -1));
    }

    @Test
    void visitShouldReturnTheSamePathForTheFirstVisit() {
        NodePath path = new NodePath("steps/poll");
        assertSame(path, path.visit(1));
    }

    @Test
    void visitShouldSuffixLaterVisits() {
        assertEquals("steps/poll@3", new NodePath("steps/poll").visit(3).value());
    }

    @Test
    void visitShouldRejectZero() {
        assertThrows(IllegalArgumentException.class, () -> NodePath.of("steps").visit(0));
    }

    @Test
    void containsShouldMatchItselfAndDescendantsOnly() {
        NodePath gather = new NodePath("steps/gather");
        assertTrue(gather.contains(gather));
        assertTrue(gather.contains(new NodePath("steps/gather/cargo/page[1]")));
        assertFalse(gather.contains(new NodePath("steps/gathering")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/steps", "steps/", "steps//a", " ", "steps/ /a"})
    void constructorShouldRejectMalformedPaths(String value) {
        assertThrows(IllegalArgumentException.class, () -> new NodePath(value));
    }

    @Test
    void childShouldRejectASegmentWithASeparator() {
        assertThrows(IllegalArgumentException.class, () -> NodePath.of("steps").child("a/b"));
    }

    @Test
    void toStringShouldReturnTheValue() {
        assertEquals("steps/a", new NodePath("steps/a").toString());
    }
}
