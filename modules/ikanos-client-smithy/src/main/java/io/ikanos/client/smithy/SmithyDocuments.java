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

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.smithy.java.core.schema.Schema;
import software.amazon.smithy.java.core.serde.document.Document;
import software.amazon.smithy.model.shapes.ShapeType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.spec.util.IkanosMappers;

/**
 * Converts between the JSON trees of Ikanos and smithy-java {@link Document}s, which hold the
 * Smithy data model: member names (never {@code @jsonName}), typed scalars, no wire format.
 *
 * <p>Into a document, a JSON tree follows the input shape's schema only where JSON cannot say
 * the type: a blob is given as base64 text, and a number or a boolean may be given as text.
 * smithy-java then converts the rest ({@code StructDocument}), a timestamp from ISO-8601 text
 * or epoch seconds included.</p>
 *
 * <p>Out of a document, members keep their names, a timestamp becomes ISO-8601 text, a blob
 * base64 text, and big integers and big decimals exact JSON numbers.</p>
 */
final class SmithyDocuments {

    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final JsonNodeFactory NODES = JSON.getNodeFactory();

    private SmithyDocuments() {
    }

    /**
     * @param node a JSON value
     * @param schema the schema of the shape it fills, or {@code null} when unknown
     * @return the untyped document
     * @throws IllegalArgumentException for a blob that is not base64, or a number or boolean
     *         text that does not parse
     */
    static Document toDocument(JsonNode node, Schema schema) {
        ShapeType type = schema != null ? schema.type() : null;
        if (node.isObject()) {
            Map<String, Document> members = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (!field.getValue().isNull()) {
                    members.put(field.getKey(),
                            toDocument(field.getValue(), memberOf(schema, field.getKey())));
                }
            }
            return Document.of(members);
        }
        if (node.isArray()) {
            Schema item = type == ShapeType.LIST || type == ShapeType.SET ? schema.listMember()
                    : null;
            List<Document> items = new ArrayList<>();
            for (JsonNode element : node) {
                items.add(element.isNull() ? null : toDocument(element, item));
            }
            return Document.of(items);
        }
        if (node.isString()) {
            return fromText(node.asString(), type);
        }
        if (node.isBoolean()) {
            return Document.of(node.asBoolean());
        }
        if (node.isIntegralNumber()) {
            return node.canConvertToLong() ? Document.of(node.asLong())
                    : Document.of(node.bigIntegerValue());
        }
        if (node.isNumber()) {
            return Document.of(node.decimalValue());
        }
        throw new IllegalArgumentException("Unsupported JSON value " + node.getNodeType());
    }

    private static Schema memberOf(Schema schema, String name) {
        if (schema == null) {
            return null;
        }
        return switch (schema.type()) {
            case STRUCTURE, UNION -> schema.member(name);
            case MAP -> schema.mapValueMember();
            default -> null;
        };
    }

    private static Document fromText(String text, ShapeType type) {
        if (type == null) {
            return Document.of(text);
        }
        try {
            return switch (type) {
                case BLOB -> Document.of(Base64.getDecoder().decode(text));
                case BOOLEAN -> {
                    if (!"true".equalsIgnoreCase(text.trim())
                            && !"false".equalsIgnoreCase(text.trim())) {
                        throw new IllegalArgumentException("'" + text + "' is not a boolean");
                    }
                    yield Document.of(Boolean.parseBoolean(text.trim()));
                }
                case BYTE, SHORT, INTEGER, INT_ENUM, LONG, BIG_INTEGER -> Document.of(
                        new BigDecimal(text.trim()).toBigIntegerExact());
                case FLOAT, DOUBLE, BIG_DECIMAL -> Document.of(new BigDecimal(text.trim()));
                default -> Document.of(text);
            };
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("'" + text + "' is not an integer", e);
        }
    }

    /**
     * @param document a document of the Smithy data model
     * @return its JSON tree, keyed by member names
     */
    static JsonNode toJson(Document document) {
        if (document == null) {
            return NODES.nullNode();
        }
        return switch (document.type()) {
            case STRUCTURE, UNION -> {
                ObjectNode object = JSON.createObjectNode();
                for (String name : document.getMemberNames()) {
                    Document member = document.getMember(name);
                    if (member != null) {
                        object.set(name, toJson(member));
                    }
                }
                yield object;
            }
            case MAP -> {
                ObjectNode object = JSON.createObjectNode();
                for (Map.Entry<String, Document> entry : document.asStringMap().entrySet()) {
                    object.set(entry.getKey(), toJson(entry.getValue()));
                }
                yield object;
            }
            case LIST, SET -> {
                ArrayNode array = JSON.createArrayNode();
                for (Document item : document.asList()) {
                    array.add(toJson(item));
                }
                yield array;
            }
            case BOOLEAN -> NODES.booleanNode(document.asBoolean());
            case STRING, ENUM -> NODES.stringNode(document.asString());
            case TIMESTAMP -> NODES.stringNode(document.asTimestamp().toString());
            case BYTE, SHORT, INTEGER, INT_ENUM, LONG -> NODES.numberNode(document.asLong());
            case FLOAT -> finite(document.asFloat(), new BigDecimal(
                    Float.toString(document.asFloat())));
            case DOUBLE -> finite(document.asDouble(), null);
            case BIG_INTEGER -> NODES.numberNode(document.asBigInteger());
            case BIG_DECIMAL -> NODES.numberNode(document.asBigDecimal());
            case BLOB -> NODES.stringNode(base64(document));
            case DOCUMENT -> JSON.valueToTree(document.asObject());
            default -> NODES.nullNode();
        };
    }

    /** A float or double; NaN and the infinities, which JSON has no number for, as text. */
    private static JsonNode finite(double value, BigDecimal exact) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return NODES.stringNode(Double.isNaN(value) ? "NaN"
                    : value > 0 ? "Infinity" : "-Infinity");
        }
        return exact != null ? NODES.numberNode(exact) : NODES.numberNode(value);
    }

    private static String base64(Document document) {
        ByteBuffer buffer = document.asBlob().duplicate();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
