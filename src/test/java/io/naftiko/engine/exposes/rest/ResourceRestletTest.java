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
package io.naftiko.engine.exposes.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.ServerSocket;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Restlet;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.routing.Router;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.Capability;
import io.naftiko.engine.Resolver;
import io.naftiko.engine.exposes.OperationStepExecutor;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.OutputParameterSpec;
import io.naftiko.spec.exposes.RestServerOperationSpec;
import io.naftiko.spec.exposes.RestServerForwardSpec;
import io.naftiko.spec.exposes.RestServerSpec;
import io.naftiko.spec.exposes.ServerCallSpec;
import io.naftiko.util.VersionHelper;

public class ResourceRestletTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static String schemaVersion;

  @BeforeEach
  public void setUp() {
    schemaVersion = VersionHelper.getSchemaVersion();
  }

  @Test
  public void handleShouldBuildMockResponseFromConstOutputParameters() throws Exception {
    Capability capability = capabilityFromYaml(minimalCapabilityYaml());
    RestServerSpec serverSpec = (RestServerSpec) capability.getServerAdapters().get(0)
        .getSpec();
    ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec,
        serverSpec.getResources().get(0));

    RestServerOperationSpec operation = new RestServerOperationSpec();
    operation.getOutputParameters().add(stringOutput("status", "ready"));

    OutputParameterSpec payload = new OutputParameterSpec();
    payload.setName("payload");
    payload.setType("object");
    payload.getProperties().add(stringOutput("id", "42"));

    OutputParameterSpec tags = new OutputParameterSpec();
    tags.setName("tags");
    tags.setType("array");
    OutputParameterSpec tagItem = new OutputParameterSpec();
    tagItem.setType("string");
    tagItem.setValue("active");
    tags.setItems(tagItem);
    payload.getProperties().add(tags);
    operation.getOutputParameters().add(payload);

    Request request = new Request(Method.GET, "http://localhost/preview");
    Response response = new Response(request);

    restlet.sendMockResponse(operation, response, Map.of());

    assertEquals(Status.SUCCESS_OK, response.getStatus());
    assertNotNull(response.getEntity());

    JsonNode parsedPayload = JSON.readTree(response.getEntity().getText());
    assertEquals("ready", parsedPayload.path("status").asText());
    assertEquals("42", parsedPayload.path("payload").path("id").asText());
    assertEquals("active", parsedPayload.path("payload").path("tags").get(0).asText());
  }

  @Test
  public void handleShouldMapOutputParametersFromClientResponse() throws Exception {
    Capability capability = capabilityFromYaml(minimalCapabilityYaml());
    RestServerSpec serverSpec =
        (RestServerSpec) capability.getServerAdapters().get(0).getSpec();
    ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec,
        serverSpec.getResources().get(0));

    RestServerOperationSpec operation = new RestServerOperationSpec();
    OutputParameterSpec mappedBody = new OutputParameterSpec();
    mappedBody.setType("object");
    mappedBody.setIn("body");

    OutputParameterSpec id = new OutputParameterSpec();
    id.setName("id");
    id.setType("string");
    id.setMapping("$.user.id");
    mappedBody.getProperties().add(id);

    OutputParameterSpec name = new OutputParameterSpec();
    name.setName("name");
    name.setType("string");
    name.setMapping("$.user.name");
    mappedBody.getProperties().add(name);

    operation.getOutputParameters().add(mappedBody);

    OperationStepExecutor.HandlingContext handlingContext =
        new OperationStepExecutor.HandlingContext();
    Request clientRequest = new Request(Method.GET, "http://localhost/internal");
    handlingContext.clientResponse = new Response(clientRequest);
    handlingContext.clientResponse.setEntity(
        "{\"user\":{\"id\":\"u-1\",\"name\":\"Alice\"}}",
        MediaType.APPLICATION_JSON);

    String mapped = restlet.mapOutputParameters(operation, handlingContext);

    JsonNode payload = JSON.readTree(mapped);
    assertEquals("u-1", payload.path("id").asText());
    assertEquals("Alice", payload.path("name").asText());
  }

  @Test
  public void handleShouldReturnNotFoundWhenNoOperationMatchesAndNoForward() throws Exception {
    Capability capability = capabilityFromYaml(minimalCapabilityYaml());
    RestServerSpec serverSpec = (RestServerSpec) capability.getServerAdapters().get(0)
        .getSpec();
    ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec,
        serverSpec.getResources().get(0));

    Request request = new Request(Method.POST, "http://localhost/test");
    Response response = new Response(request);

    restlet.handle(request, response);

    assertEquals(Status.CLIENT_ERROR_NOT_FOUND, response.getStatus());
    assertEquals("Unable to handle the request. Please check the capability specification.",
        response.getEntity().getText());
  }

  @Test
  public void handleShouldReturnBadRequestForInvalidCallFormat() throws Exception {
    Capability capability = capabilityFromYaml(minimalCapabilityYaml());
    RestServerSpec serverSpec = (RestServerSpec) capability.getServerAdapters().get(0)
        .getSpec();
    serverSpec.getResources().get(0).getOperations().get(0)
        .setCall(new ServerCallSpec("invalidCallFormat"));
    ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec,
        serverSpec.getResources().get(0));

    Request request = new Request(Method.GET, "http://localhost/test");
    Response response = new Response(request);

    restlet.handle(request, response);

    assertEquals(Status.CLIENT_ERROR_BAD_REQUEST, response.getStatus());
    assertEquals("Invalid call format: invalidCallFormat", response.getEntity().getText());
  }

  @Test
  public void mapOutputParametersShouldReturnNullWhenClientEntityIsMissing() throws Exception {
    Capability capability = capabilityFromYaml(minimalCapabilityYaml());
    RestServerSpec serverSpec = (RestServerSpec) capability.getServerAdapters().get(0)
        .getSpec();
    ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec,
        serverSpec.getResources().get(0));

    RestServerOperationSpec operation = new RestServerOperationSpec();
    OutputParameterSpec body = new OutputParameterSpec();
    body.setType("string");
    body.setIn("body");
    body.setMapping("$.id");
    operation.getOutputParameters().add(body);

    String nullContext = restlet.mapOutputParameters(operation, null);
    assertNull(nullContext);

    OperationStepExecutor.HandlingContext context = new OperationStepExecutor.HandlingContext();
    context.clientResponse = new Response(new Request(Method.GET, "http://localhost/internal"));
    String noEntity = restlet.mapOutputParameters(operation, context);
    assertNull(noEntity);
  }

  @Test
  public void canBuildMockResponseShouldCheckNestedConstValues() throws Exception {
    Capability capability = capabilityFromYaml(minimalCapabilityYaml());
    RestServerSpec serverSpec = (RestServerSpec) capability.getServerAdapters().get(0)
        .getSpec();
    ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec,
        serverSpec.getResources().get(0));

    RestServerOperationSpec noOutput = new RestServerOperationSpec();
    assertFalse(restlet.canBuildMockResponse(noOutput));

    RestServerOperationSpec nestedConst = new RestServerOperationSpec();
    OutputParameterSpec root = new OutputParameterSpec();
    root.setName("payload");
    root.setType("object");
    OutputParameterSpec child = new OutputParameterSpec();
    child.setName("status");
    child.setType("string");
    child.setValue("ok");
    root.getProperties().add(child);
    nestedConst.getOutputParameters().add(root);

    assertTrue(restlet.canBuildMockResponse(nestedConst));
  }

  @Test
  public void buildMockValueShouldHandleObjectArrayAndPrimitiveFallback() throws Exception {
    ObjectMapper mapper = new ObjectMapper();

    OutputParameterSpec objectParam = new OutputParameterSpec();
    objectParam.setType("object");
    OutputParameterSpec field = new OutputParameterSpec();
    field.setName("status");
    field.setType("string");
    field.setValue("ok");
    objectParam.getProperties().add(field);

    JsonNode objectNode = Resolver.buildMockValue(objectParam, mapper, Map.of());
    assertEquals("ok", objectNode.path("status").asText());

    OutputParameterSpec arrayParam = new OutputParameterSpec();
    arrayParam.setType("array");
    OutputParameterSpec item = new OutputParameterSpec();
    item.setType("string");
    item.setValue("v");
    arrayParam.setItems(item);

    JsonNode arrayNode = Resolver.buildMockValue(arrayParam, mapper, Map.of());
    assertTrue(arrayNode.isArray());
    assertEquals("v", arrayNode.get(0).asText());

    OutputParameterSpec primitiveNoConst = new OutputParameterSpec();
    primitiveNoConst.setType("string");
    JsonNode primitive = Resolver.buildMockValue(primitiveNoConst, mapper, Map.of());
    assertTrue(primitive.isNull());
  }

  @Test
  public void copyTrustedHeadersShouldSkipMissingAndHandleNullTrustedList() throws Exception {
    Capability capability = capabilityFromYaml(minimalCapabilityYaml());
    RestServerSpec serverSpec = (RestServerSpec) capability.getServerAdapters().get(0)
        .getSpec();
    ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec,
        serverSpec.getResources().get(0));

    Request from = new Request(Method.GET, "http://localhost/source");
    from.getHeaders().add("X-Trace", "abc");
    Request to = new Request(Method.GET, "http://localhost/target");

    restlet.copyTrustedHeaders(from, to, java.util.List.of("X-Trace", "X-Missing"));
    assertEquals("abc", to.getHeaders().getFirstValue("X-Trace", true));
    assertNull(to.getHeaders().getFirstValue("X-Missing", true));

    Request toNoop = new Request(Method.GET, "http://localhost/noop");
    restlet.copyTrustedHeaders(from, toNoop, null);
    assertNull(toNoop.getHeaders().getFirstValue("X-Trace", true));
  }

  @Test
  public void sendResponseShouldFallbackToRawEntityAndHandleMappingExceptions() throws Exception {
    Capability capability = capabilityFromYaml(minimalCapabilityYaml());
    RestServerSpec serverSpec = (RestServerSpec) capability.getServerAdapters().get(0)
        .getSpec();
    ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec,
        serverSpec.getResources().get(0));

    OperationStepExecutor.HandlingContext handlingContext =
        new OperationStepExecutor.HandlingContext();
    Request clientRequest = new Request(Method.GET, "http://localhost/internal");
    handlingContext.clientResponse = new Response(clientRequest);
    handlingContext.clientResponse.setEntity("{\"id\":\"u-1\"}", MediaType.APPLICATION_JSON);

    RestServerOperationSpec rawOperation = new RestServerOperationSpec();
    Response rawResponse = new Response(new Request(Method.GET, "http://localhost/test"));
    restlet.sendResponse(rawOperation, rawResponse, handlingContext);
    assertEquals("{\"id\":\"u-1\"}", rawResponse.getEntity().getText());

    // Unsupported output format triggers mapping exception path.
    RestServerOperationSpec failingOperation = new RestServerOperationSpec();
    failingOperation.setOutputRawFormat("INI");
    OutputParameterSpec output = new OutputParameterSpec();
    output.setType("string");
    output.setIn("body");
    output.setMapping("$.id");
    failingOperation.getOutputParameters().add(output);

    Response errorResponse = new Response(new Request(Method.GET, "http://localhost/test"));
    restlet.sendResponse(failingOperation, errorResponse, handlingContext);
    assertEquals(Status.SERVER_ERROR_INTERNAL, errorResponse.getStatus());
    assertTrue(errorResponse.getEntity().getText().contains("Failed to map output parameters"));
  }

  @Test
  public void inOrDefaultShouldFallbackToBody() throws Exception {
    Capability capability = capabilityFromYaml(minimalCapabilityYaml());
    RestServerSpec serverSpec = (RestServerSpec) capability.getServerAdapters().get(0)
        .getSpec();
    ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec,
        serverSpec.getResources().get(0));

    OutputParameterSpec explicit = new OutputParameterSpec();
    explicit.setIn("header");

    assertEquals("body", restlet.inOrDefault(null));
    assertEquals("body", restlet.inOrDefault(new OutputParameterSpec()));
    assertEquals("header", restlet.inOrDefault(explicit));
  }

  @Test
  public void handleShouldForwardRequestAndPropagateTrustedHeaders() throws Exception {
    int port = findFreePort();
    Component upstream = createHeaderEchoServer(port);
    upstream.start();

    try {
      Capability capability = capabilityFromYaml("""
              naftiko: "%s"
              capability:
                exposes:
                  - type: "rest"
                    address: "localhost"
                    port: 0
                    namespace: "test"
                    resources:
                      - path: "/proxy"
                        operations: []
                consumes:
                  - type: "http"
                    namespace: "upstream"
                    baseUri: "http://localhost:%d"
                    resources:
                      - path: "/echo"
                        name: "echo"
                        operations:
                          - method: "GET"
                            name: "get"
              """.formatted(schemaVersion, port));

      RestServerSpec serverSpec = (RestServerSpec) capability.getServerAdapters().get(0)
          .getSpec();
      serverSpec.getResources().get(0).getOperations().clear();

      RestServerForwardSpec forward = new RestServerForwardSpec();
      forward.setTargetNamespace("upstream");
      forward.getTrustedHeaders().add("X-Trace");
      serverSpec.getResources().get(0).setForward(forward);

      ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec,
          serverSpec.getResources().get(0));

      Request request = new Request(Method.GET, "http://localhost/proxy");
      request.getAttributes().put("path", "echo");
      request.getHeaders().add("X-Trace", "abc-123");
      Response response = new Response(request);

      restlet.handle(request, response);

      assertEquals(Status.SUCCESS_OK, response.getStatus());
      assertEquals("abc-123", response.getEntity().getText());
    } finally {
      upstream.stop();
    }
  }

  private static Component createHeaderEchoServer(int port) throws Exception {
    Component component = new Component();
    component.getServers().add(Protocol.HTTP, port);
    component.getDefaultHost().attach(new Application() {
      @Override
      public Restlet createInboundRoot() {
        Router router = new Router(getContext());
        router.attach("/echo", new Restlet() {
          @Override
          public void handle(Request request, Response response) {
            String trace = request.getHeaders().getFirstValue("X-Trace", true);
            response.setStatus(Status.SUCCESS_OK);
            response.setEntity(trace != null ? trace : "", MediaType.TEXT_PLAIN);
          }
        });
        return router;
      }
    });
    return component;
  }

  private static int findFreePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  @Test
  public void buildMockValueShouldResolveMustacheTemplatesInValue() throws Exception {
    ObjectMapper mapper = new ObjectMapper();

    OutputParameterSpec param = new OutputParameterSpec();
    param.setName("greeting");
    param.setType("string");
    param.setValue("Hello, {{name}}!");

    Map<String, Object> inputParameters = Map.of("name", "Alice");
    JsonNode node = Resolver.buildMockValue(param, mapper, inputParameters);
    assertEquals("Hello, Alice!", node.asText());
  }

  private static Capability capabilityFromYaml(String yaml) throws Exception {
    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    NaftikoSpec spec = mapper.readValue(yaml, NaftikoSpec.class);
    return new Capability(spec);
  }

  private static String minimalCapabilityYaml() {
    return """
                naftiko: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "test"
                      resources:
                        - path: "/test"
                          operations:
                            - method: "GET"
                              name: "test"
                  consumes: []
                """.formatted(schemaVersion);
  }

  private static OutputParameterSpec stringOutput(String name, String value) {
    OutputParameterSpec spec = new OutputParameterSpec();
    spec.setName(name);
    spec.setType("string");
    spec.setValue(value);
    return spec;
  }
}