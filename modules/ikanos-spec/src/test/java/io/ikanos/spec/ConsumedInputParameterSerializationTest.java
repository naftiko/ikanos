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
package io.ikanos.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SpecificationVersion;

import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.IkanosMetaSchemaFactory;
import io.ikanos.spec.util.VersionHelper;

/**
 * Array query parameter serialization hints on consumed input parameters (#851).
 */
class ConsumedInputParameterSerializationTest {

  private static Schema schema;

  @BeforeAll
  static void loadSchema() throws Exception {
    InputStream schemaStream = ConsumedInputParameterSerializationTest.class.getClassLoader()
        .getResourceAsStream("schemas/ikanos-schema.json");
    JsonNode schemaNode = IkanosMappers.json().readTree(schemaStream);
    schema = IkanosMetaSchemaFactory.getInstance(SpecificationVersion.DRAFT_2020_12)
        .getSchema(schemaNode);
  }

  @Test
  void validateShouldAcceptWireNameStyleAndExplode() throws Exception {
    List<Error> errors = schema.validate(capability("""
        status:
          in: "query"
          wireName: "status[]"
          style: "form"
          explode: true
        """));
    assertTrue(errors.isEmpty(), errors.toString());
  }

  @Test
  void validateShouldRejectUnknownStyle() throws Exception {
    List<Error> errors = schema.validate(capability("""
        status:
          in: "query"
          style: "matrix"
        """));
    assertFalse(errors.isEmpty(), "Only form and the delimited styles are supported");
  }

  @Test
  void deserializeShouldReadWireNameStyleAndExplode() throws Exception {
    IkanosSpec spec = IkanosMappers.lenientYaml().treeToValue(capability("""
        status:
          in: "query"
          wireName: "status[]"
          style: "pipeDelimited"
          explode: false
        """), IkanosSpec.class);

    HttpClientSpec http = (HttpClientSpec) spec.getCapability().getConsumes().get(0);
    InputParameterSpec status = http.getResources().get("subs").getOperations()
        .get("list").getInputParameters().get(0);
    assertEquals("status", status.getName());
    assertEquals("status[]", status.getWireName());
    assertEquals("pipeDelimited", status.getStyle());
    assertEquals(Boolean.FALSE, status.getExplode());
  }

  @Test
  void serializeShouldWriteWireNameStyleAndExplode() throws Exception {
    InputParameterSpec spec = new InputParameterSpec();
    spec.setIn("query");
    spec.setWireName("status[]");
    spec.setStyle("form");
    spec.setExplode(Boolean.TRUE);

    JsonNode node = IkanosMappers.json().valueToTree(spec);

    assertEquals("status[]", node.path("wireName").asString());
    assertEquals("form", node.path("style").asString());
    assertTrue(node.path("explode").asBoolean());
  }

  private static JsonNode capability(String inputParameters) throws Exception {
    String yaml = """
        ikanos: "%s"
        capability:
          consumes:
            - type: "http"
              namespace: "lago"
              baseUri: "https://api.example.com"
              resources:
                subs:
                  path: "/subscriptions"
                  operations:
                    list:
                      method: "GET"
                      inputParameters:
        %s
          exposes:
            - type: "mcp"
              port: 3000
              namespace: "tools"
              tools:
                list:
                  description: "List subscriptions"
                  call: "lago.list"
        """.formatted(VersionHelper.getSchemaVersion(), inputParameters.indent(24).stripTrailing());
    return IkanosMappers.yaml().readTree(yaml);
  }
}
