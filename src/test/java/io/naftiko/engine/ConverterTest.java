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
package io.naftiko.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.restlet.data.MediaType;
import org.restlet.representation.StringRepresentation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.naftiko.spec.OutputParameterSpec;

public class ConverterTest {

    @TempDir
    Path tempDir;

    @Test
    public void convertToJsonShouldRejectUnsupportedFormat() {
        StringRepresentation entity =
                new StringRepresentation("{}", MediaType.APPLICATION_JSON);

        IOException error = assertThrows(IOException.class,
                () -> Converter.convertToJson("INI", null, entity));

        assertEquals("Unsupported \"INI\" format specified", error.getMessage());
    }

    @Test
    public void convertToJsonShouldRequireSchemaForProtobuf() {
        StringRepresentation entity =
                new StringRepresentation("{}", MediaType.APPLICATION_OCTET_STREAM);

        IOException error = assertThrows(IOException.class,
                () -> Converter.convertToJson("Protobuf", null, entity));

        assertEquals(
                "Protobuf format requires outputSchema to be specified in operation specification",
                error.getMessage());
    }

    @Test
    public void convertToJsonShouldRequireSchemaForProtobufWhenSchemaIsEmpty() {
        StringRepresentation entity =
                new StringRepresentation("{}", MediaType.APPLICATION_OCTET_STREAM);

        IOException error = assertThrows(IOException.class,
                () -> Converter.convertToJson("Protobuf", "", entity));

        assertEquals(
                "Protobuf format requires outputSchema to be specified in operation specification",
                error.getMessage());
    }

    @Test
    public void convertToJsonShouldRequireSchemaForAvro() {
        StringRepresentation entity =
                new StringRepresentation("{}", MediaType.APPLICATION_OCTET_STREAM);

        IOException error = assertThrows(IOException.class,
                () -> Converter.convertToJson("Avro", "", entity));

        assertEquals("Avro format requires outputSchema to be specified in operation specification",
                error.getMessage());
    }

    @Test
    public void loadSchemaFileShouldPreferLocalFileAndSupportClasspathFallback() throws Exception {
        Path localSchema = tempDir.resolve("local-schema.avsc");
        Files.writeString(localSchema, "{\"type\":\"record\",\"name\":\"Local\",\"fields\":[]}");

        try (InputStream local = Converter.loadSchemaFile(localSchema.toString())) {
            assertNotNull(local);
            assertEquals('{', new String(local.readAllBytes(), StandardCharsets.UTF_8).charAt(0));
        }

        try (InputStream classpath = Converter.loadSchemaFile("schemas/test-records.avsc")) {
            assertNotNull(classpath);
            String content = new String(classpath.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(true, content.contains("record"));
        }
    }

    @Test
    public void applyMaxLengthIfNeededShouldTruncateAndIgnoreInvalidLengths() throws Exception {
        OutputParameterSpec truncatedSpec = new OutputParameterSpec();
        truncatedSpec.setMaxLength("5");

        JsonNode truncated = Converter.applyMaxLengthIfNeeded(truncatedSpec,
                new com.fasterxml.jackson.databind.ObjectMapper().readTree("\"abcdefgh\""));
        assertEquals("abcde", truncated.asText());

        OutputParameterSpec invalidSpec = new OutputParameterSpec();
        invalidSpec.setMaxLength("abc");

        JsonNode untouched = Converter.applyMaxLengthIfNeeded(invalidSpec,
                new com.fasterxml.jackson.databind.ObjectMapper().readTree("\"abcdefgh\""));
        assertEquals("abcdefgh", untouched.asText());
    }

    @Test
    public void jsonPathExtractShouldSupportMissingPathsAndPropertiesWithSpaces() throws Exception {
        JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {
                  "user details": {
                    "contact email": "alice@example.com"
                  }
                }
                """);

        JsonNode value = Converter.jsonPathExtract(root,
                "$['user details']['contact email']");
        JsonNode missing = Converter.jsonPathExtract(root, "$.missing.field");

        assertEquals("alice@example.com", value.asText());
        assertEquals(true, missing.isNull());
        assertEquals("$.['user details'].email",
                Converter.fixJsonPathWithSpaces("$.user details.email"));
    }

    @Test
    public void convertHtmlAndMarkdownShouldProduceSameTableContract() throws Exception {
        String html = """
                <table>
                  <thead>
                    <tr><th>Name</th><th>Price</th><th>Stock</th></tr>
                  </thead>
                  <tbody>
                    <tr><td>Widget</td><td>$42</td><td>150</td></tr>
                    <tr><td>Gadget</td><td>$99</td><td>30</td></tr>
                  </tbody>
                </table>
                """;

        String markdown = """
                | Name | Price | Stock |
                |------|-------|-------|
                | Widget | $42 | 150 |
                | Gadget | $99 | 30 |
                """;

        JsonNode htmlRoot = Converter.convertHtmlToJson(new StringReader(html), null);
        JsonNode markdownRoot = Converter.convertMarkdownToJson(new StringReader(markdown));

        assertEquals(htmlRoot.get("tables"), markdownRoot.get("tables"));
        assertEquals("$42", htmlRoot.get("tables").get(0).get(0).get("Price").asText());
    }

    @Test
    public void convertHtmlToJsonShouldApplyCssSelectorScoping() throws Exception {
        String html = """
                <div>
                  <table class="ignored">
                    <tr><th>Name</th><th>Stock</th></tr>
                    <tr><td>Ignored</td><td>0</td></tr>
                  </table>
                  <table class="products">
                    <tr><th>Name</th><th>Stock</th></tr>
                    <tr><td>Kept</td><td>42</td></tr>
                  </table>
                </div>
                """;

        JsonNode root = Converter.convertHtmlToJson(new StringReader(html), "table.products");

        assertEquals(1, root.get("tables").size());
        assertEquals("Kept", root.get("tables").get(0).get(0).get("Name").asText());
    }

    @Test
    public void convertMarkdownToJsonShouldExtractFrontMatterTablesAndSections() throws Exception {
        String markdown = """
                ---
                title: Release Notes
                version: 2.1.0
                ---

                ## Overview
                This **release** introduces [new features](https://example.com).

                | Feature | Status |
                |---------|--------|
                | Search  | GA     |
                """;

        JsonNode root = Converter.convertMarkdownToJson(new StringReader(markdown));

        assertEquals("Release Notes", root.get("frontMatter").get("title").asText());
        assertEquals("2.1.0", root.get("frontMatter").get("version").asText());
        assertEquals("GA", root.get("tables").get(0).get(0).get("Status").asText());
        assertEquals("Overview", root.get("sections").get(0).get("heading").asText());
        assertEquals("This release introduces new features.",
                root.get("sections").get(0).get("content").asText());
    }

    @Test
    public void convertToJsonShouldSupportHtmlAndMarkdownFormats() throws Exception {
        StringRepresentation htmlEntity =
                new StringRepresentation("<table><tr><th>Name</th></tr><tr><td>Alice</td></tr></table>",
                        MediaType.TEXT_HTML);
        StringRepresentation markdownEntity =
                new StringRepresentation("| Name | Role |\n|------|------|\n| Alice | User |",
                        MediaType.TEXT_PLAIN);

        JsonNode htmlRoot = Converter.convertToJson("html", null, htmlEntity);
        JsonNode markdownRoot = Converter.convertToJson("markdown", null, markdownEntity);

        assertEquals("Alice", htmlRoot.get("tables").get(0).get(0).get("Name").asText());
        assertEquals("Alice", markdownRoot.get("tables").get(0).get(0).get("Name").asText());
    }

    @Test
    public void convertDelimitedToJsonShouldParseTabSeparatedValues() throws Exception {
        String tsv = "id\tname\temail\n1\tAlice Smith\talice@example.com\n2\tBob Johnson\tbob@example.com\n";
        JsonNode root = Converter.convertDelimitedToJson(new StringReader(tsv), '\t');

        assertEquals(2, root.size());
        assertEquals("1", root.get(0).get("id").asText());
        assertEquals("Alice Smith", root.get(0).get("name").asText());
        assertEquals("alice@example.com", root.get(0).get("email").asText());
        assertEquals("Bob Johnson", root.get(1).get("name").asText());
    }

    @Test
    public void convertDelimitedToJsonShouldParsePipeSeparatedValues() throws Exception {
        String psv = "id|name|email\n1|Alice Smith|alice@example.com\n2|Bob Johnson|bob@example.com\n";
        JsonNode root = Converter.convertDelimitedToJson(new StringReader(psv), '|');

        assertEquals(2, root.size());
        assertEquals("1", root.get(0).get("id").asText());
        assertEquals("Alice Smith", root.get(0).get("name").asText());
        assertEquals("Bob Johnson", root.get(1).get("name").asText());
    }

    @Test
    public void convertToJsonShouldRouteTsvAndPsvFormats() throws Exception {
        StringRepresentation tsvEntity =
                new StringRepresentation("id\tname\n1\tAlice\n", MediaType.TEXT_PLAIN);
        StringRepresentation psvEntity =
                new StringRepresentation("id|name\n1|Alice\n", MediaType.TEXT_PLAIN);

        JsonNode tsvRoot = Converter.convertToJson("tsv", null, tsvEntity);
        JsonNode psvRoot = Converter.convertToJson("psv", null, psvEntity);

        assertEquals("Alice", tsvRoot.get(0).get("name").asText());
        assertEquals("Alice", psvRoot.get(0).get("name").asText());
    }

    @Test
    public void convertCsvToJsonShouldDelegateToDelimited() throws Exception {
        String csv = "id,name\n1,Alice\n";
        JsonNode root = Converter.convertCsvToJson(new StringReader(csv));

        assertEquals(1, root.size());
        assertEquals("Alice", root.get(0).get("name").asText());
    }

    @Test
    public void convertToJsonShouldSupportJsonWhenFormatIsNull() throws Exception {
        StringRepresentation entity =
                new StringRepresentation("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON);

        JsonNode root = Converter.convertToJson(null, null, entity);

        assertEquals("ok", root.get("status").asText());
    }

    @Test
    public void convertToJsonShouldSupportExplicitJsonFormat() throws Exception {
        StringRepresentation entity =
                new StringRepresentation("{\"status\":\"ok\",\"count\":2}",
                        MediaType.APPLICATION_JSON);

        JsonNode root = Converter.convertToJson("JSON", null, entity);

        assertEquals("ok", root.get("status").asText());
        assertEquals(2, root.get("count").asInt());
    }

    @Test
    public void convertToJsonShouldSupportXmlYamlAndCsvFormats() throws Exception {
        StringRepresentation xmlEntity =
                new StringRepresentation("<root><name>Alice</name></root>", MediaType.APPLICATION_XML);
        JsonNode xml = Converter.convertToJson("XML", null, xmlEntity);
        assertEquals("Alice", xml.get("name").asText());

        StringRepresentation yamlEntity = new StringRepresentation("name: Alice\nage: 42\n",
                MediaType.valueOf("application/yaml"));
        JsonNode yaml = Converter.convertToJson("YAML", null, yamlEntity);
        assertEquals("Alice", yaml.get("name").asText());
        assertEquals(42, yaml.get("age").asInt());

        StringRepresentation csvEntity =
                new StringRepresentation("name,age\nAlice,42\nBob,39\n", MediaType.TEXT_CSV);
        JsonNode csv = Converter.convertToJson("CSV", null, csvEntity);
        assertTrue(csv.isArray());
        assertEquals("Alice", csv.get(0).get("name").asText());
        assertEquals("39", csv.get(1).get("age").asText());
    }

    @Test
    public void convertProtobufToJsonShouldWrapMissingSchemaAsIOException() {
        ByteArrayInputStream payload = new ByteArrayInputStream(new byte[] {1, 2, 3});

        IOException error = assertThrows(IOException.class,
                () -> Converter.convertProtobufToJson(payload, "schemas/does-not-exist.proto"));

        assertTrue(error.getMessage().contains("Failed to deserialize Protocol Buffer"));
        assertTrue(error.getMessage().contains("Proto schema file not found"));
    }

    @Test
    public void convertAvroToJsonShouldWrapMissingSchemaAsIOException() {
        ByteArrayInputStream payload = new ByteArrayInputStream(new byte[] {1, 2, 3});

        IOException error = assertThrows(IOException.class,
                () -> Converter.convertAvroToJson(payload, "schemas/does-not-exist.avsc"));

        assertTrue(error.getMessage().contains("Failed to deserialize Avro data"));
        assertTrue(error.getMessage().contains("Avro schema file not found"));
    }

    @Test
    public void loadSchemaFileShouldReturnNullWhenSchemaCannotBeFound() throws Exception {
        InputStream stream = Converter.loadSchemaFile("schemas/does-not-exist.any");
        assertEquals(null, stream);
    }

    @Test
    public void jsonPathExtractShouldHandleRootSelectorsAndEmptyMapping() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree("{\"a\":1}");

        assertEquals(root, Converter.jsonPathExtract(root, "$"));
        assertEquals(root, Converter.jsonPathExtract(root, "$."));
        assertEquals(NullNode.instance, Converter.jsonPathExtract(root, ""));
        assertEquals(NullNode.instance, Converter.jsonPathExtract(root, null));
        assertEquals(NullNode.instance, Converter.jsonPathExtract(null, "$"));
    }

    @Test
    public void applyMaxLengthIfNeededShouldHandleNullSpecAndNonTextualValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode numberNode = mapper.readTree("123");
        JsonNode textNode = mapper.readTree("\"hello\"");

        assertEquals(numberNode, Converter.applyMaxLengthIfNeeded(new OutputParameterSpec(),
                numberNode));
        assertEquals(textNode, Converter.applyMaxLengthIfNeeded(null, textNode));
        assertEquals(NullNode.instance,
                Converter.applyMaxLengthIfNeeded(new OutputParameterSpec(), NullNode.instance));
    }

    @Test
    public void fixJsonPathWithSpacesShouldLeaveMappingsWithoutSpacesUntouched() {
        assertEquals(null, Converter.fixJsonPathWithSpaces(null));
        assertEquals("$.user.email", Converter.fixJsonPathWithSpaces("$.user.email"));
    }

    @Test
    public void convertHtmlToJsonShouldSkipRowsContainingThElements() throws Exception {
        String html = """
                <table>
                  <tr><th>Name</th><th>Price</th></tr>
                  <tr><th>Widget</th><td>$42</td></tr>
                  <tr><td>Gadget</td><td>$99</td></tr>
                </table>
                """;

        JsonNode root = Converter.convertHtmlToJson(new StringReader(html), null);

        assertEquals(1, root.get("tables").get(0).size());
        assertEquals("Gadget", root.get("tables").get(0).get(0).get("Name").asText());
    }

    @Test
    public void convertMarkdownToJsonShouldFilterSectionsByHeadingPrefix() throws Exception {
        String markdown = """
                ## Overview
                General introduction.

                ## API Reference
                Details about the API.

                ## API Usage
                How to use the API.
                """;

        JsonNode root = Converter.convertMarkdownToJson(new StringReader(markdown), "API");

        assertEquals(2, root.get("sections").size());
        assertEquals("API Reference", root.get("sections").get(0).get("heading").asText());
        assertEquals("API Usage", root.get("sections").get(1).get("heading").asText());
    }

    @Test
    public void convertMarkdownToJsonWithNullFilterShouldReturnAllSections() throws Exception {
        String markdown = """
                ## First
                Content one.

                ## Second
                Content two.
                """;

        JsonNode root = Converter.convertMarkdownToJson(new StringReader(markdown), null);

        assertEquals(2, root.get("sections").size());
    }
}
