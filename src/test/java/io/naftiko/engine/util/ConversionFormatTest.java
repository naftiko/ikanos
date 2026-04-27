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
package io.naftiko.engine.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConversionFormat#fromLabel(String)}.
 */
public class ConversionFormatTest {

    @Test
    void fromLabelShouldReturnNullForNullInput() {
        assertNull(ConversionFormat.fromLabel(null),
                "null input must return null — callers treat null as 'use default JSON'");
    }

    @Test
    void fromLabelShouldReturnNullForUnknownFormat() {
        assertNull(ConversionFormat.fromLabel("ini"),
                "unknown format must return null, not throw");
    }

    @Test
    void fromLabelShouldMatchCaseInsensitively() {
        assertEquals(ConversionFormat.JSON, ConversionFormat.fromLabel("JSON"));
        assertEquals(ConversionFormat.JSON, ConversionFormat.fromLabel("json"));
        assertEquals(ConversionFormat.XML,  ConversionFormat.fromLabel("Xml"));
        assertEquals(ConversionFormat.CSV,  ConversionFormat.fromLabel("CSV"));
    }

    @Test
    void fromLabelShouldReturnCorrectConstantForEachKnownFormat() {
        assertEquals(ConversionFormat.YAML,     ConversionFormat.fromLabel("yaml"));
        assertEquals(ConversionFormat.TSV,      ConversionFormat.fromLabel("tsv"));
        assertEquals(ConversionFormat.PSV,      ConversionFormat.fromLabel("psv"));
        assertEquals(ConversionFormat.HTML,     ConversionFormat.fromLabel("html"));
        assertEquals(ConversionFormat.MARKDOWN, ConversionFormat.fromLabel("markdown"));
        assertEquals(ConversionFormat.PROTOBUF, ConversionFormat.fromLabel("protobuf"));
        assertEquals(ConversionFormat.AVRO,     ConversionFormat.fromLabel("avro"));
    }
}
