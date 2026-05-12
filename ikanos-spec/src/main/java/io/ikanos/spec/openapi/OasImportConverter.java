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
package io.ikanos.spec.openapi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.ikanos.spec.consumes.http.BasicAuthenticationSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.consumes.http.DigestAuthenticationSpec;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;
import io.ikanos.spec.consumes.http.HttpClientResourceSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;

/**
 * Stateless converter: OpenAPI {@link OpenAPI} POJO → Naftiko {@link HttpClientSpec} object tree.
 */
public class OasImportConverter {

    private static final int MAX_DEPTH = 10;

    /**
     * Convert an OpenAPI document to an Ikanos HttpClientSpec.
     *
     * @param openApi the parsed OpenAPI document (pre-resolved by Swagger Parser)
     * @return conversion result containing the HttpClientSpec and any warnings
     */
    public OasImportResult convert(OpenAPI openApi) {
        if (openApi == null) {
            throw new IllegalArgumentException("OpenAPI document must not be null");
        }

        List<String> warnings = new ArrayList<>();

        String namespace = deriveNamespace(openApi, warnings);
        String baseUri = deriveBaseUri(openApi, warnings);
        AuthenticationSpec authentication = deriveAuthentication(openApi, warnings);

        HttpClientSpec httpClient = new HttpClientSpec(namespace, baseUri, authentication);

        Map<String, List<OperationEntry>> grouped = groupOperationsByPath(openApi, warnings);
        for (Map.Entry<String, List<OperationEntry>> entry : grouped.entrySet()) {
            String path = entry.getKey();
            List<OperationEntry> ops = entry.getValue();

            HttpClientResourceSpec resource = new HttpClientResourceSpec();
            resource.setPath(path.replaceAll("\\{([^}]+)}", "{{$1}}"));
            resource.setName(deriveResourceName(path));

            Map<String, HttpClientOperationSpec> operations = new LinkedHashMap<>();
            for (OperationEntry opEntry : ops) {
                HttpClientOperationSpec opSpec = convertOperation(
                        opEntry.path, opEntry.method, opEntry.operation, warnings);
                opSpec.setParentResource(resource);
                operations.put(opSpec.getName(), opSpec);
            }
            resource.setOperations(operations);

            String resourceName = resource.getName();
            httpClient.getResources().put(resourceName, resource);
        }

        return new OasImportResult(httpClient, warnings);
    }

    String deriveNamespace(OpenAPI openApi, List<String> warnings) {
        if (openApi.getInfo() != null && openApi.getInfo().getTitle() != null) {
            return toKebabCase(openApi.getInfo().getTitle());
        }
        warnings.add("No info.title found; using 'unknown-api' as namespace");
        return "unknown-api";
    }

    String deriveBaseUri(OpenAPI openApi, List<String> warnings) {
        if (openApi.getServers() != null && !openApi.getServers().isEmpty()) {
            Server firstServer = openApi.getServers().get(0);
            String url = firstServer != null ? firstServer.getUrl() : null;
            if (url != null && !url.isEmpty() && !"/".equals(url)) {
                // Strip trailing slash
                if (url.endsWith("/")) {
                    url = url.substring(0, url.length() - 1);
                }
                return url;
            }
        }
        warnings.add("No servers defined; using placeholder baseUri 'https://api.example.com'");
        return "https://api.example.com";
    }

    AuthenticationSpec deriveAuthentication(OpenAPI openApi, List<String> warnings) {
        if (openApi.getComponents() == null
                || openApi.getComponents().getSecuritySchemes() == null
                || openApi.getComponents().getSecuritySchemes().isEmpty()) {
            return null;
        }

        Map<String, SecurityScheme> schemes = openApi.getComponents().getSecuritySchemes();
        // Use the first scheme
        Map.Entry<String, SecurityScheme> first =
                schemes.entrySet().iterator().next();
        SecurityScheme scheme = first.getValue();

        if (scheme == null || scheme.getType() == null) {
            warnings.add("Security scheme '" + first.getKey()
                + "' is missing type information and was ignored");
            return null;
        }

        switch (scheme.getType()) {
            case APIKEY:
                ApiKeyAuthenticationSpec apiKey = new ApiKeyAuthenticationSpec();
                apiKey.setKey(scheme.getName());
                apiKey.setValue("{{API_KEY}}");
                if (scheme.getIn() != null) {
                    apiKey.setPlacement(scheme.getIn().toString());
                }
                return apiKey;
            case HTTP:
                if ("bearer".equalsIgnoreCase(scheme.getScheme())) {
                    BearerAuthenticationSpec bearer = new BearerAuthenticationSpec();
                    bearer.setToken("{{BEARER_TOKEN}}");
                    return bearer;
                } else if ("digest".equalsIgnoreCase(scheme.getScheme())) {
                    DigestAuthenticationSpec digest = new DigestAuthenticationSpec();
                    digest.setUsername("{{USERNAME}}");
                    digest.setPassword("{{PASSWORD}}".toCharArray());
                    warnings.add("Digest authentication mapped; credentials must be configured via binds");
                    return digest;
                } else if ("basic".equalsIgnoreCase(scheme.getScheme())) {
                    BasicAuthenticationSpec basic = new BasicAuthenticationSpec();
                    basic.setUsername("{{USERNAME}}");
                    basic.setPassword("{{PASSWORD}}".toCharArray());
                    warnings.add("Basic authentication mapped; credentials must be configured via binds");
                    return basic;
                } else {
                    warnings.add("Unsupported HTTP security scheme: " + scheme.getScheme());
                    return null;
                }
            case OAUTH2:
                warnings.add("oauth2 authentication not yet supported — configure manually");
                return null;
            case OPENIDCONNECT:
                warnings.add("openIdConnect authentication not yet supported — configure manually");
                return null;
            default:
                warnings.add("Unsupported security scheme type: " + scheme.getType());
                return null;
        }
    }

    Map<String, List<OperationEntry>> groupOperationsByPath(OpenAPI openApi, List<String> warnings) {
        Map<String, List<OperationEntry>> grouped = new LinkedHashMap<>();

        if (openApi.getPaths() == null) {
            return grouped;
        }

        for (Map.Entry<String, PathItem> pathEntry : openApi.getPaths().entrySet()) {
            String path = pathEntry.getKey();
            PathItem pathItem = pathEntry.getValue();

            addOperation(grouped, path, "GET", pathItem.getGet());
            addOperation(grouped, path, "POST", pathItem.getPost());
            addOperation(grouped, path, "PUT", pathItem.getPut());
            addOperation(grouped, path, "DELETE", pathItem.getDelete());
            addOperation(grouped, path, "PATCH", pathItem.getPatch());
            addOperation(grouped, path, "HEAD", pathItem.getHead());
            addOperation(grouped, path, "OPTIONS", pathItem.getOptions());
        }

        return grouped;
    }

    private void addOperation(Map<String, List<OperationEntry>> grouped,
            String path, String method, Operation operation) {
        if (operation == null) {
            return;
        }

        grouped.computeIfAbsent(path, k -> new ArrayList<>())
                .add(new OperationEntry(path, method, operation));
    }

    String deriveResourceName(String path) {
        // Defensive: fall back to "root" when the input path is null
        // (fixes S2259: dereferencing path.replaceAll without a null guard).
        if (path == null) {
            return "root";
        }
        // Use the path template without parameter placeholders as resource name
        String slug = toKebabCase(path.replaceAll("[{}]", ""));
        if (slug == null) {
            return "root";
        }
        // Remove leading hyphen from the leading slash
        if (slug.startsWith("-")) {
            slug = slug.substring(1);
        }
        return slug.isEmpty() ? "root" : slug;
    }

    HttpClientOperationSpec convertOperation(
            String path, String method, Operation operation, List<String> warnings) {

        String name = deriveOperationName(path, method, operation);

        HttpClientOperationSpec opSpec = new HttpClientOperationSpec();
        opSpec.setMethod(method);
        opSpec.setName(name);

        if (operation.getSummary() != null) {
            opSpec.setLabel(operation.getSummary());
        }
        if (operation.getDescription() != null) {
            opSpec.setDescription(operation.getDescription());
        }

        // Input parameters from path/query/header/cookie
        Map<String, InputParameterSpec> inputMap = new LinkedHashMap<>();
        if (operation.getParameters() != null) {
            for (Parameter param : operation.getParameters()) {
                InputParameterSpec inputParam = convertInputParameter(param);
                inputMap.put(inputParam.getName(), inputParam);
            }
        }

        // Request body → input parameters with in: body
        if (operation.getRequestBody() != null) {
            List<InputParameterSpec> bodyParams =
                    convertRequestBody(operation.getRequestBody(), warnings);
            for (InputParameterSpec bp : bodyParams) {
                inputMap.put(bp.getName(), bp);
            }
        }

        if (!inputMap.isEmpty()) {
            opSpec.setInputParameters(inputMap);
        }

        // Output parameters from success response
        List<OutputParameterSpec> outputParams =
                convertOutputParameters(operation.getResponses(), warnings);
        if (outputParams != null) {
            opSpec.getOutputParameters().addAll(outputParams);
        }

        return opSpec;
    }

    String deriveOperationName(String path, String method, Operation operation) {
        if (operation.getOperationId() != null) {
            return toKebabCase(operation.getOperationId());
        }
        // Synthesize: {method}-{slug}, keeping path parameter names in kebab-case
        String slug = toKebabCase(path.replaceAll("[{}]", ""));
        return (method.toLowerCase() + "-" + slug).replaceAll("-+", "-");
    }

    InputParameterSpec convertInputParameter(Parameter param) {
        InputParameterSpec spec = new InputParameterSpec();
        spec.setName(param.getName());
        if (param.getIn() != null) {
            spec.setIn(param.getIn());
        }
        if (param.getSchema() != null && resolveSchemaType(param.getSchema()) != null) {
            spec.setType(mapSchemaType(resolveSchemaType(param.getSchema())));
        }
        if (param.getDescription() != null) {
            spec.setDescription(param.getDescription());
        }
        boolean required = param.getRequired() != null
                ? param.getRequired()
                : "path".equals(param.getIn());
        spec.setRequired(required);
        return spec;
    }

    @SuppressWarnings("rawtypes")
    List<InputParameterSpec> convertRequestBody(RequestBody requestBody, List<String> warnings) {
        List<InputParameterSpec> params = new ArrayList<>();
        if (requestBody.getContent() == null) {
            return params;
        }

        MediaType mediaType = requestBody.getContent().get("application/json");
        if (mediaType == null) {
            mediaType = requestBody.getContent().get("multipart/form-data");
        }
        if (mediaType == null && !requestBody.getContent().isEmpty()) {
            mediaType = requestBody.getContent().values().iterator().next();
        }
        if (mediaType == null || mediaType.getSchema() == null) {
            return params;
        }

        Schema<?> schema = mediaType.getSchema();
        if ("object".equals(resolveSchemaType(schema)) && schema.getProperties() != null) {
            for (Map.Entry<String, Schema> prop : schema.getProperties().entrySet()) {
                InputParameterSpec param = new InputParameterSpec();
                param.setName(prop.getKey());
                param.setIn("body");
                if (resolveSchemaType(prop.getValue()) != null) {
                    param.setType(mapSchemaType(resolveSchemaType(prop.getValue())));
                }
                if (prop.getValue().getDescription() != null) {
                    param.setDescription(prop.getValue().getDescription());
                }
                if (schema.getRequired() != null
                        && schema.getRequired().contains(prop.getKey())) {
                    param.setRequired(true);
                } else {
                    param.setRequired(false);
                }
                params.add(param);
            }
        } else {
            // Non-object body — single param
            InputParameterSpec param = new InputParameterSpec();
            param.setName("body");
            param.setIn("body");
            String resolvedType = resolveSchemaType(schema);
            if (resolvedType != null) {
                param.setType(mapSchemaType(resolvedType));
            }
            param.setRequired(requestBody.getRequired() != null && requestBody.getRequired());
            params.add(param);
        }

        return params;
    }

    List<OutputParameterSpec> convertOutputParameters(ApiResponses responses,
            List<String> warnings) {
        if (responses == null) {
            return null;
        }

        // Find first success response (2xx)
        ApiResponse successResponse = null;
        for (Map.Entry<String, ApiResponse> entry : responses.entrySet()) {
            String code = entry.getKey();
            if (code.startsWith("2")) {
                successResponse = entry.getValue();
                break;
            }
        }
        if (successResponse == null || successResponse.getContent() == null) {
            return null;
        }

        MediaType mediaType = successResponse.getContent().get("application/json");
        if (mediaType == null && !successResponse.getContent().isEmpty()) {
            mediaType = successResponse.getContent().values().iterator().next();
        }
        if (mediaType == null || mediaType.getSchema() == null) {
            return null;
        }

        return convertSchemaToOutputParameters(mediaType.getSchema(), "$", 0, warnings);
    }

    @SuppressWarnings("rawtypes")
    List<OutputParameterSpec> convertSchemaToOutputParameters(
            Schema<?> schema, String jsonPathPrefix, int depth, List<String> warnings) {
        if (depth > MAX_DEPTH) {
            warnings.add("Maximum nesting depth reached at " + jsonPathPrefix);
            return List.of();
        }

        List<OutputParameterSpec> params = new ArrayList<>();

        // Handle allOf — merge properties
        if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
            for (Schema<?> subSchema : schema.getAllOf()) {
                params.addAll(convertSchemaToOutputParameters(
                        subSchema, jsonPathPrefix, depth + 1, warnings));
            }
            return params;
        }

        // Handle oneOf — use first variant
        if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
            warnings.add("oneOf at " + jsonPathPrefix + ": using first variant only");
            return convertSchemaToOutputParameters(
                    schema.getOneOf().get(0),
                    jsonPathPrefix, depth + 1, warnings);
        }

        if ("array".equals(resolveSchemaType(schema)) && schema.getItems() != null) {
            OutputParameterSpec arrayParam = new OutputParameterSpec();
            arrayParam.setType("array");
            arrayParam.setMapping(jsonPathPrefix + "[*]");

            Schema<?> itemSchema = schema.getItems();
            if ("object".equals(resolveSchemaType(itemSchema)) && itemSchema.getProperties() != null) {
                OutputParameterSpec itemSpec = new OutputParameterSpec();
                itemSpec.setType("object");
                List<OutputParameterSpec> itemProps = convertSchemaToOutputParameters(
                        itemSchema, jsonPathPrefix + "[*]", depth + 1, warnings);
                itemSpec.getProperties().addAll(itemProps);
                arrayParam.setItems(itemSpec);
            } else if (resolveSchemaType(itemSchema) != null) {
                OutputParameterSpec itemSpec = new OutputParameterSpec();
                itemSpec.setType(mapSchemaType(resolveSchemaType(itemSchema)));
                arrayParam.setItems(itemSpec);
            }

            params.add(arrayParam);
            return params;
        }

        if (schema.getProperties() != null) {
            for (Map.Entry<String, Schema> prop : schema.getProperties().entrySet()) {
                if (isWriteOnly(prop.getValue())) {
                    continue;
                }
                String propName = prop.getKey();
                Schema<?> propSchema = prop.getValue();
                String propPath = jsonPathPrefix + "." + propName;

                OutputParameterSpec outParam = new OutputParameterSpec();
                outParam.setName(toKebabCase(propName));
                outParam.setMapping(propPath);

                if (propSchema.getDescription() != null) {
                    outParam.setDescription(propSchema.getDescription());
                }

                if ("object".equals(resolveSchemaType(propSchema)) && propSchema.getProperties() != null) {
                    outParam.setType("object");
                    List<OutputParameterSpec> nested = convertSchemaToOutputParameters(
                            propSchema, propPath, depth + 1, warnings);
                    outParam.getProperties().addAll(nested);
                } else if ("array".equals(resolveSchemaType(propSchema))) {
                    outParam.setType("array");
                    outParam.setMapping(propPath + "[*]");

                    if (propSchema.getItems() != null) {
                        Schema<?> itemSchema = propSchema.getItems();

                        if ("object".equals(resolveSchemaType(itemSchema))
                                && itemSchema.getProperties() != null) {
                            OutputParameterSpec itemSpec = new OutputParameterSpec();
                            itemSpec.setType("object");
                            List<OutputParameterSpec> itemProps =
                                    convertSchemaToOutputParameters(
                                            itemSchema, propPath + "[*]",
                                            depth + 1, warnings);
                            itemSpec.getProperties().addAll(itemProps);
                            outParam.setItems(itemSpec);
                        } else if (resolveSchemaType(itemSchema) != null) {
                            OutputParameterSpec itemSpec = new OutputParameterSpec();
                            itemSpec.setType(mapSchemaType(
                                    resolveSchemaType(itemSchema)));
                            outParam.setItems(itemSpec);
                        }
                    }
                } else {
                    outParam.setType(mapSchemaType(
                            resolveSchemaType(propSchema) != null ? resolveSchemaType(propSchema) : "string"));
                }

                params.add(outParam);
            }
        } else if (resolveSchemaType(schema) != null
                && !"object".equals(resolveSchemaType(schema))
                && !"array".equals(resolveSchemaType(schema))) {
            // Scalar response
            OutputParameterSpec scalar = new OutputParameterSpec();
            scalar.setType(mapSchemaType(resolveSchemaType(schema)));
            scalar.setMapping(jsonPathPrefix);
            params.add(scalar);
        }

        return params;
    }

    private boolean isWriteOnly(Schema<?> schema) {
        return schema.getWriteOnly() != null && schema.getWriteOnly();
    }

    static String toKebabCase(String input) {
        if (input == null) {
            return null;
        }
        return input
                // Insert hyphen before uppercase letters (camelCase → camel-Case)
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                // Replace non-alphanumeric with hyphens
                .replaceAll("[^a-zA-Z0-9]+", "-")
                // Collapse multiple hyphens
                .replaceAll("-+", "-")
                // Trim leading/trailing hyphens
                // Use explicit grouping to avoid ambiguity (S5850):
                // the regex "^-|-$" is parsed as "(^-)|(-$)" but reads ambiguously.
                .replaceAll("(^-)|(-$)", "")
                .toLowerCase();
    }

    static String mapSchemaType(String oasType) {
        if (oasType == null) {
            return "string";
        }
        return switch (oasType) {
            case "integer" -> "number";
            case "number" -> "number";
            case "boolean" -> "boolean";
            case "array" -> "array";
            case "object" -> "object";
            default -> "string";
        };
    }

    /**
     * Resolve the effective type string from a schema, handling both OAS 3.0 ({@code getType()})
     * and OAS 3.1 ({@code getTypes()}, which returns a set like {@code ["string", "null"]}).
     * Returns null if neither accessor yields a usable type.
     */
    static String resolveSchemaType(Schema<?> schema) {
        String type = schema.getType();
        if (type != null) {
            return type;
        }
        // OAS 3.1: type is an array in getTypes()
        if (schema.getTypes() != null && !schema.getTypes().isEmpty()) {
            // Pick the first non-null type
            for (String t : schema.getTypes()) {
                if (!"null".equals(t)) {
                    return t;
                }
            }
            // All types were "null"
            return schema.getTypes().iterator().next();
        }
        return null;
    }

    static class OperationEntry {
        final String path;
        final String method;
        final Operation operation;

        OperationEntry(String path, String method, Operation operation) {
            this.path = path;
            this.method = method;
            this.operation = operation;
        }
    }

}
