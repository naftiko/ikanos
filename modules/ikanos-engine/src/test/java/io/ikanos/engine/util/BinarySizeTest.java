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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BinarySize}, the {@code maxBinarySize} sized-string parser used by the
 * binary-content blueprint (§4.7 / §5.1).
 */
public class BinarySizeTest {

    @Test
    public void parseShouldReturnRawByteCountWhenNoUnitGiven() {
        assertEquals(4096L, BinarySize.parse("4096"));
    }

    @Test
    public void parseShouldHonorBytesSuffix() {
        assertEquals(512L, BinarySize.parse("512B"));
    }

    @Test
    public void parseShouldConvertKibToBytes() {
        assertEquals(512L * 1024, BinarySize.parse("512KiB"));
    }

    @Test
    public void parseShouldConvertMibToBytes() {
        assertEquals(10L * 1024 * 1024, BinarySize.parse("10MiB"));
    }

    @Test
    public void parseShouldConvertGibToBytes() {
        assertEquals(1L * 1024 * 1024 * 1024, BinarySize.parse("1GiB"));
    }

    @Test
    public void parseShouldSupportFractionalMagnitudes() {
        assertEquals((long) (1.5 * 1024 * 1024), BinarySize.parse("1.5MiB"));
    }

    @Test
    public void parseShouldThrowWhenSizeIsBlank() {
        assertThrows(IllegalArgumentException.class, () -> BinarySize.parse("  "));
    }

    @Test
    public void parseShouldThrowWhenSizeIsNull() {
        assertThrows(IllegalArgumentException.class, () -> BinarySize.parse(null));
    }

    @Test
    public void parseShouldThrowWhenUnitIsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> BinarySize.parse("10TB"));
    }

    @Test
    public void parseShouldThrowWhenMagnitudeIsMissing() {
        assertThrows(IllegalArgumentException.class, () -> BinarySize.parse("MiB"));
    }

    @Test
    public void parseOrDefaultShouldReturnDefaultWhenNull() {
        assertEquals(BinarySize.DEFAULT_MAX_BINARY_SIZE_BYTES, BinarySize.parseOrDefault(null));
    }

    @Test
    public void parseOrDefaultShouldReturnDefaultWhenBlank() {
        assertEquals(BinarySize.DEFAULT_MAX_BINARY_SIZE_BYTES, BinarySize.parseOrDefault("   "));
    }

    @Test
    public void parseOrDefaultShouldParseWhenPresent() {
        assertEquals(25L * 1024 * 1024, BinarySize.parseOrDefault("25MiB"));
    }

    @Test
    public void defaultShouldBeTenMebibytes() {
        assertEquals(10L * 1024 * 1024, BinarySize.DEFAULT_MAX_BINARY_SIZE_BYTES);
    }
}
