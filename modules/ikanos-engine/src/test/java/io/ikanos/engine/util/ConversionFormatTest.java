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
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConversionFormat#fromDisplay(String)}.
 */
public class ConversionFormatTest {

    @Test
    void fromDisplayShouldReturnNullForNullInput() {
        assertNull(ConversionFormat.fromDisplay(null),
                "null input must return null — callers treat null as 'use default JSON'");
    }

    @Test
    void fromDisplayShouldReturnNullForUnknownFormat() {
        assertNull(ConversionFormat.fromDisplay("ini"),
                "unknown format must return null, not throw");
    }

    @Test
    void fromDisplayShouldMatchCaseInsensitively() {
        assertEquals(ConversionFormat.JSON, ConversionFormat.fromDisplay("JSON"));
        assertEquals(ConversionFormat.JSON, ConversionFormat.fromDisplay("json"));
        assertEquals(ConversionFormat.XML,  ConversionFormat.fromDisplay("Xml"));
        assertEquals(ConversionFormat.CSV,  ConversionFormat.fromDisplay("CSV"));
    }

    @Test
    void fromDisplayShouldReturnCorrectConstantForEachKnownFormat() {
        assertEquals(ConversionFormat.YAML,     ConversionFormat.fromDisplay("yaml"));
        assertEquals(ConversionFormat.TSV,      ConversionFormat.fromDisplay("tsv"));
        assertEquals(ConversionFormat.PSV,      ConversionFormat.fromDisplay("psv"));
        assertEquals(ConversionFormat.HTML,     ConversionFormat.fromDisplay("html"));
        assertEquals(ConversionFormat.MARKDOWN, ConversionFormat.fromDisplay("markdown"));
        assertEquals(ConversionFormat.PROTOBUF, ConversionFormat.fromDisplay("protobuf"));
        assertEquals(ConversionFormat.AVRO,     ConversionFormat.fromDisplay("avro"));
    }
}
