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
package io.naftiko.spec.openapi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.naftiko.spec.CapabilitySpec;
import io.naftiko.spec.InputParameterSpec;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.OutputParameterSpec;
import io.naftiko.spec.consumes.ApiKeyAuthenticationSpec;
import io.naftiko.spec.consumes.AuthenticationSpec;
import io.naftiko.spec.consumes.BasicAuthenticationSpec;
import io.naftiko.spec.consumes.BearerAuthenticationSpec;
import io.naftiko.spec.consumes.DigestAuthenticationSpec;
import io.naftiko.spec.exposes.RestServerOperationSpec;
import io.naftiko.spec.exposes.RestServerResourceSpec;
import io.naftiko.spec.exposes.RestServerSpec;
import io.naftiko.spec.exposes.ServerSpec;

/**
 * Builds an OpenAPI {@link OpenAPI} POJO from a Naftiko capability spec's REST adapter.
 */
public class OasExportBuilder {

    /**
     * Build an OpenAPI document from a Naftiko specification's REST adapter.
     * Defaults to OAS 3.0.3.
     *
     * @param naftikoSpec the full Naftiko specification
     * @param adapterNamespace the namespace of the REST adapter to export (null for first)
     * @return export result containing the OpenAPI POJO and any warnings
     */
    public OasExportResult build(NaftikoSpec naftikoSpec, String adapterNamespace) {
        return build(naftikoSpec, adapterNamespace, SpecVersion.V30);
    }

    /**
     * Build an OpenAPI document from a Naftiko specification's REST adapter.
     *
     * @param naftikoSpec the full Naftiko specification
     * @param adapterNamespace the namespace of the REST adapter to export (null for first)
     * @param specVersion the OAS version to produce (V30 or V31)
     * @return export result containing the OpenAPI POJO and any warnings
     */
    public OasExportResult build(NaftikoSpec naftikoSpec, String adapterNamespace,
            SpecVersion specVersion) {
        List<String> warnings = new ArrayList<>();

        RestServerSpec restServer = findRestServer(naftikoSpec, adapterNamespace, warnings);
        if (restServer == null) {
            return new OasExportResult(new OpenAPI(), warnings);
        }

        OpenAPI openApi = new OpenAPI(specVersion);
        if (specVersion == SpecVersion.V31) {
            openApi.setOpenapi("3.1.0");
        } else {
            openApi.setOpenapi("3.0.3");
        }

        // Info
        openApi.setInfo(buildInfo(naftikoSpec, warnings));

        // Server
        openApi.setServers(buildServers(restServer));

        // Paths
        openApi.setPaths(buildPaths(restServer, warnings));

        // Security
        buildSecurity(restServer, openApi, warnings);

        return new OasExportResult(openApi, warnings);
    }

    RestServerSpec findRestServer(NaftikoSpec naftikoSpec, String adapterNamespace,
            List<String> warnings) {
        CapabilitySpec capability = naftikoSpec.getCapability();
        if (capability == null || capability.getExposes() == null) {
            warnings.add("No capability or exposes section found");
            return null;
        }

        for (ServerSpec server : capability.getExposes()) {
            if (server instanceof RestServerSpec rest) {
                if (adapterNamespace == null || adapterNamespace.equals(rest.getNamespace())) {
                    return rest;
                }
            }
        }

        warnings.add("No REST adapter found"
                + (adapterNamespace != null ? " with namespace '" + adapterNamespace + "'" : ""));
        return null;
    }

    Info buildInfo(NaftikoSpec naftikoSpec, List<String> warnings) {
        Info info = new Info();
        if (naftikoSpec.getInfo() != null) {
            if (naftikoSpec.getInfo().getLabel() != null) {
                info.setTitle(naftikoSpec.getInfo().getLabel());
            }
            if (naftikoSpec.getInfo().getDescription() != null) {
                info.setDescription(naftikoSpec.getInfo().getDescription());
            }
        }
        if (info.getTitle() == null) {
            info.setTitle("Naftiko Capability");
            warnings.add("No info.label found; using default title");
        }
        info.setVersion("1.0.0");
        return info;
    }

    List<Server> buildServers(RestServerSpec restServer) {
        String address = restServer.getAddress();
        int port = restServer.getPort();

        // Normalize 0.0.0.0 to localhost
        if ("0.0.0.0".equals(address)) {
            address = "localhost";
        }
        if (address == null) {
            address = "localhost";
        }

        String url = "http://" + address + (port > 0 ? ":" + port : "");
        return List.of(new Server().url(url));
    }

    Paths buildPaths(RestServerSpec restServer, List<String> warnings) {
        Paths paths = new Paths();

        for (RestServerResourceSpec resource : restServer.getResources()) {
            String basePath = resource.getPath();
            if (basePath == null) {
                basePath = "/" + resource.getName();
            }

            for (RestServerOperationSpec opSpec : resource.getOperations()) {
                Operation operation = buildOperation(resource, opSpec);
                String pathKey = basePath;

                PathItem pathItem = paths.get(pathKey);
                if (pathItem == null) {
                    pathItem = new PathItem();
                    paths.addPathItem(pathKey, pathItem);
                }

                String method = opSpec.getMethod();
                if (method != null) {
                    setOperationOnPathItem(pathItem, method, operation);
                }
            }
        }

        return paths;
    }

    Operation buildOperation(RestServerResourceSpec resource, RestServerOperationSpec opSpec) {
        Operation operation = new Operation();

        if (opSpec.getName() != null) {
            operation.setOperationId(opSpec.getName());
        }
        if (opSpec.getDescription() != null) {
            operation.setDescription(opSpec.getDescription());
        }
        if (resource.getName() != null) {
            operation.setTags(List.of(resource.getName()));
        }

        // Parameters
        List<Parameter> params = new ArrayList<>();
        RequestBody requestBody = null;
        List<String> bodyRequired = new ArrayList<>();
        for (InputParameterSpec inputParam : opSpec.getInputParameters()) {
            if ("body".equals(inputParam.getIn())) {
                if (requestBody == null) {
                    requestBody = new RequestBody();
                    Schema<?> bodySchema = new Schema<>().type("object");
                    bodySchema.setProperties(new LinkedHashMap<>());
                    requestBody.setContent(new Content().addMediaType("application/json",
                            new MediaType().schema(bodySchema)));
                }
                // Add property to body schema
                Schema<?> propSchema = buildInputSchema(inputParam);
                @SuppressWarnings("unchecked")
                Schema<Object> bodySchema = (Schema<Object>) requestBody.getContent()
                        .get("application/json").getSchema();
                bodySchema.getProperties().put(inputParam.getName(), propSchema);
                if (inputParam.isRequired()) {
                    bodyRequired.add(inputParam.getName());
                }
            } else {
                Parameter param = new Parameter();
                param.setName(inputParam.getName());
                param.setIn(inputParam.getIn() != null ? inputParam.getIn() : "query");
                param.setRequired(inputParam.isRequired());
                param.setSchema(buildInputSchema(inputParam));
                if (inputParam.getDescription() != null) {
                    param.setDescription(inputParam.getDescription());
                }
                params.add(param);
            }
        }

        if (!params.isEmpty()) {
            operation.setParameters(params);
        }
        if (requestBody != null) {
            if (!bodyRequired.isEmpty()) {
                @SuppressWarnings("unchecked")
                Schema<Object> bodySchema = (Schema<Object>) requestBody.getContent()
                        .get("application/json").getSchema();
                bodySchema.setRequired(bodyRequired);
            }
            operation.setRequestBody(requestBody);
        }

        // Responses
        ApiResponses responses = new ApiResponses();
        if (opSpec.getOutputParameters() != null && !opSpec.getOutputParameters().isEmpty()) {
            Schema<?> responseSchema = buildOutputSchema(opSpec.getOutputParameters());
            responses.addApiResponse("200", new ApiResponse()
                    .description("Successful response")
                    .content(new Content().addMediaType("application/json",
                            new MediaType().schema(responseSchema))));
        } else {
            responses.addApiResponse("204", new ApiResponse()
                    .description("No content"));
        }
        operation.setResponses(responses);

        return operation;
    }

    Schema<?> buildInputSchema(InputParameterSpec inputParam) {
        Schema<?> schema = new Schema<>();
        schema.setType(mapNaftikoTypeToOas(inputParam.getType()));
        if (inputParam.getDescription() != null) {
            schema.setDescription(inputParam.getDescription());
        }
        return schema;
    }

    @SuppressWarnings("rawtypes")
    Schema<?> buildOutputSchema(List<OutputParameterSpec> outputParams) {
        Schema<?> schema = new Schema<>().type("object");
        Map<String, Schema> properties = new LinkedHashMap<>();

        for (OutputParameterSpec outParam : outputParams) {
            Schema<?> propSchema = buildOutputPropertySchema(outParam);
            String name = outParam.getName() != null ? outParam.getName() : "value";
            properties.put(name, propSchema);
        }

        schema.setProperties(properties);
        return schema;
    }

    @SuppressWarnings("rawtypes")
    Schema<?> buildOutputPropertySchema(OutputParameterSpec outParam) {
        Schema<?> schema = new Schema<>();

        if ("object".equals(outParam.getType()) && outParam.getProperties() != null
                && !outParam.getProperties().isEmpty()) {
            schema.setType("object");
            Map<String, Schema> nested = new LinkedHashMap<>();
            for (OutputParameterSpec child : outParam.getProperties()) {
                String name = child.getName() != null ? child.getName() : "value";
                nested.put(name, buildOutputPropertySchema(child));
            }
            schema.setProperties(nested);
        } else if ("array".equals(outParam.getType())) {
            schema.setType("array");
            if (outParam.getItems() != null) {
                schema.setItems(buildOutputPropertySchema(outParam.getItems()));
            }
        } else {
            schema.setType(mapNaftikoTypeToOas(outParam.getType()));
        }

        if (outParam.getDescription() != null) {
            schema.setDescription(outParam.getDescription());
        }

        return schema;
    }

    void buildSecurity(RestServerSpec restServer, OpenAPI openApi, List<String> warnings) {
        AuthenticationSpec auth = restServer.getAuthentication();
        if (auth == null) {
            return;
        }

        Components components = openApi.getComponents();
        if (components == null) {
            components = new Components();
            openApi.setComponents(components);
        }

        String schemeName;
        SecurityScheme securityScheme = new SecurityScheme();

        if (auth instanceof BearerAuthenticationSpec) {
            schemeName = "bearerAuth";
            securityScheme.setType(SecurityScheme.Type.HTTP);
            securityScheme.setScheme("bearer");
        } else if (auth instanceof BasicAuthenticationSpec) {
            schemeName = "basicAuth";
            securityScheme.setType(SecurityScheme.Type.HTTP);
            securityScheme.setScheme("basic");
        } else if (auth instanceof DigestAuthenticationSpec) {
            schemeName = "digestAuth";
            securityScheme.setType(SecurityScheme.Type.HTTP);
            securityScheme.setScheme("digest");
        } else if (auth instanceof ApiKeyAuthenticationSpec apiKey) {
            schemeName = "apiKeyAuth";
            securityScheme.setType(SecurityScheme.Type.APIKEY);
            securityScheme.setName(apiKey.getKey());
            if (apiKey.getPlacement() != null) {
                securityScheme.setIn(SecurityScheme.In.valueOf(apiKey.getPlacement().toUpperCase()));
            }
        } else {
            warnings.add("Unsupported authentication type for export: " + auth.getClass().getSimpleName());
            return;
        }

        components.addSecuritySchemes(schemeName, securityScheme);
        openApi.setSecurity(List.of(new SecurityRequirement().addList(schemeName)));
    }

    private void setOperationOnPathItem(PathItem pathItem, String method, Operation operation) {
        switch (method.toUpperCase()) {
            case "GET" -> pathItem.setGet(operation);
            case "POST" -> pathItem.setPost(operation);
            case "PUT" -> pathItem.setPut(operation);
            case "DELETE" -> pathItem.setDelete(operation);
            case "PATCH" -> pathItem.setPatch(operation);
            case "HEAD" -> pathItem.setHead(operation);
            case "OPTIONS" -> pathItem.setOptions(operation);
        }
    }

    private String mapNaftikoTypeToOas(String naftikoType) {
        if (naftikoType == null) {
            return "string";
        }
        return switch (naftikoType) {
            case "number" -> "number";
            case "boolean" -> "boolean";
            case "array" -> "array";
            case "object" -> "object";
            default -> "string";
        };
    }

}
