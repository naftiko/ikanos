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
package io.ikanos.client.smithy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.java.core.schema.Schema;
import software.amazon.smithy.java.core.serde.document.Document;
import software.amazon.smithy.java.dynamicschemas.SchemaConverter;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ShapeId;
import tools.jackson.databind.JsonNode;
import io.ikanos.spec.util.IkanosMappers;

/** The conversions between JSON trees and smithy-java documents, guided by the schema. */
class SmithyDocumentsTest {

    private static final Schema NUMBERS = numbers();

    private static Schema numbers() {
        Model model = Model.assembler().addUnparsedModel("numbers.smithy", """
                $version: "2"

                namespace example.numbers

                structure Numbers {
                    b: Byte
                    s: Short
                    i: Integer
                    l: Long
                    big: BigInteger
                    f: Float
                    d: Double
                    dec: BigDecimal
                }
                """).assemble().unwrap();
        return new SchemaConverter(model).getSchema(
                model.expectShape(ShapeId.from("example.numbers#Numbers")));
    }

    /** The member {@code name} of the document converted from {@code {"name": value}}. */
    private static Document member(String name, String value) {
        JsonNode tree = IkanosMappers.json().readTree("{\"" + name + "\":" + value + "}");
        return SmithyDocuments.toDocument(tree, NUMBERS).asStringMap().get(name);
    }

    @Test
    void toDocumentShouldKeepNumbersThatFitTheirMemberType() {
        assertEquals(-128, member("b", "-128").asByte());
        assertEquals(32767, member("s", "32767").asShort());
        assertEquals(2147483647, member("i", "2147483647").asInteger());
        assertEquals(Long.MAX_VALUE, member("l", "9223372036854775807").asLong());
        assertEquals(new BigInteger("123456789012345678901234567890"),
                member("big", "123456789012345678901234567890").asBigInteger());
        assertEquals(1.5f, member("f", "1.5").asFloat());
        assertEquals(0.1d, member("d", "0.1").asDouble());
        assertEquals(new BigDecimal("0.1"), member("dec", "0.1").asBigDecimal());
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void toDocumentShouldRefuseANumberTooLargeToConvertQuickly() {
        assertThrows(IllegalArgumentException.class, () -> member("big", "\"1e20000000\""),
                "as text");
        assertThrows(IllegalArgumentException.class, () -> member("big", "1e20000000"),
                "as a JSON number");
    }

    @Test
    void toDocumentShouldAcceptAnIntegralNumberWrittenWithAFraction() {
        assertEquals(2, member("i", "2.0").asInteger());
    }

    @Test
    void toDocumentShouldRefuseAByteOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> member("b", "128"));
    }

    @Test
    void toDocumentShouldRefuseAShortOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> member("s", "-32769"));
    }

    @Test
    void toDocumentShouldRefuseAnIntegerOutOfRangeRatherThanWrapIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> member("i", "3000000000"));
        assertTrue(e.getMessage().contains("out of range for integer"), e.getMessage());
    }

    @Test
    void toDocumentShouldRefuseALongOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> member("l", "9223372036854775808"));
    }

    @Test
    void toDocumentShouldRefuseAFractionForAnIntegerRatherThanTruncateIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> member("i", "1.9"));
        assertTrue(e.getMessage().contains("not an integer"), e.getMessage());
    }

    @Test
    void toDocumentShouldRefuseAFractionForABigInteger() {
        assertThrows(IllegalArgumentException.class, () -> member("big", "1.5"));
    }

    @Test
    void toDocumentShouldRefuseAFractionGivenAsTextForALong() {
        assertThrows(IllegalArgumentException.class, () -> member("l", "\"1.9\""));
    }

    @Test
    void toDocumentShouldRefuseAFloatThatOverflowsToInfinity() {
        assertThrows(IllegalArgumentException.class, () -> member("f", "1e39"));
    }

    @Test
    void toDocumentShouldRefuseADoubleThatOverflowsToInfinity() {
        assertThrows(IllegalArgumentException.class, () -> member("d", "\"1e309\""));
    }

    @Test
    void toDocumentShouldRefuseANumberTextThatDoesNotParse() {
        assertThrows(IllegalArgumentException.class, () -> member("i", "\"twelve\""));
    }

    @Test
    void nonFiniteDoublesShouldRoundTripThroughTheirJsonText() {
        Schema d = NUMBERS.member("d");
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY}) {
            JsonNode json = SmithyDocuments.toJson(Document.of(value));

            assertEquals(Double.valueOf(value),
                    Double.valueOf(SmithyDocuments.toDocument(json, d).asDouble()), json.toString());
        }
    }

    @Test
    void nonFiniteFloatsShouldRoundTripThroughTheirJsonText() {
        Schema f = NUMBERS.member("f");
        for (float value : new float[] {Float.NaN, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY}) {
            JsonNode json = SmithyDocuments.toJson(Document.of(value));

            assertEquals(Float.valueOf(value),
                    Float.valueOf(SmithyDocuments.toDocument(json, f).asFloat()), json.toString());
        }
    }
}
