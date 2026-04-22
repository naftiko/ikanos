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
package io.naftiko.engine.exposes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import io.naftiko.engine.util.Resolver;
import io.naftiko.engine.util.SafePathResolver;
import io.naftiko.engine.util.StepExecutionContext;
import io.naftiko.spec.exposes.OperationStepScriptSpec;

/**
 * Executor for script operation steps.
 *
 * <p>Executes JavaScript scripts in a sandboxed GraalVM polyglot context and Groovy scripts via
 * {@link GroovyShell} with AST security restrictions. Scripts are loaded from external files
 * referenced by a {@code file:///} URI directory and a relative file path.</p>
 *
 * <p>The executor injects previous step outputs and runtime parameters into the script context as
 * a {@code context} binding. The script must assign its output to a {@code result} variable.</p>
 */
class ScriptStepExecutor {

    private static final long DEFAULT_STATEMENT_LIMIT = 100_000;

    private static final Map<String, String> LANGUAGE_ID_MAP = Map.of(
            "javascript", "js",
            "js", "js",
            "python", "python",
            "groovy", "groovy"
    );

    private static final boolean SCRIPTING_PERMITTED =
            !"false".equalsIgnoreCase(System.getenv("NAFTIKO_SCRIPTING"));

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Called by the capability loader when a capability containing script steps is being
     * initialized — before any request is served.
     */
    static void requireScriptingPermitted(String stepName) {
        if (!SCRIPTING_PERMITTED) {
            throw new IllegalStateException(
                    "Script step '" + stepName
                            + "' requires scripting support. Scripting is disabled via "
                            + "NAFTIKO_SCRIPTING=false. Remove the override or set it to "
                            + "'true' to enable it.");
        }
    }

    JsonNode execute(OperationStepScriptSpec scriptStep, Map<String, Object> runtimeParameters,
            StepExecutionContext stepContext) {

        String language = scriptStep.getLanguage();
        String locationUri = scriptStep.getLocation();
        String file = scriptStep.getFile();

        if (locationUri == null || locationUri.isBlank()) {
            throw new IllegalArgumentException(
                    "Script step '" + scriptStep.getName()
                            + "' has no 'location'. Set 'location' on the step or configure "
                            + "'management.scripting.defaultLocation' on the control adapter.");
        }
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException(
                    "Script step '" + scriptStep.getName()
                            + "' has no 'language'. Set 'language' on the step or configure "
                            + "'management.scripting.defaultLanguage' on the control adapter.");
        }

        String mainSource = readScript(locationUri, file, scriptStep.getName());

        Map<String, Object> bindings =
                buildBindings(runtimeParameters, stepContext, scriptStep.getWith());

        if ("groovy".equals(language)) {
            return executeGroovy(mainSource, bindings, scriptStep);
        }
        String polyglotId = LANGUAGE_ID_MAP.getOrDefault(language, language);
        return executePolyglot(polyglotId, mainSource, bindings, scriptStep);
    }

    private JsonNode executePolyglot(String language, String mainSource,
            Map<String, Object> bindings, OperationStepScriptSpec scriptStep) {

        String locationUri = scriptStep.getLocation();

        try (Context context = Context.newBuilder(language)
                .allowAllAccess(false)
                .allowHostAccess(HostAccess.NONE)
                .allowIO(IOAccess.NONE)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowNativeAccess(false)
                .option("engine.WarnInterpreterOnly", "false")
                .resourceLimits(ResourceLimits.newBuilder()
                        .statementLimit(DEFAULT_STATEMENT_LIMIT, null)
                        .build())
                .build()) {

            // Inject bindings as a JavaScript object via JSON round-trip
            String bindingsJson = mapper.writeValueAsString(bindings);
            context.eval(language, "var context = " + bindingsJson + ";");

            // Evaluate dependent scripts in order
            List<String> dependencies = scriptStep.getDependencies();
            if (dependencies != null) {
                for (String depPath : dependencies) {
                    String depSource =
                            readScript(locationUri, depPath, scriptStep.getName());
                    context.eval(language, depSource);
                }
            }

            // Evaluate main script
            context.eval(language, mainSource);

            // Extract result
            Value resultValue = context.getBindings(language).getMember("result");
            return convertPolyglotValue(resultValue);

        } catch (PolyglotException e) {
            if (e.isResourceExhausted()) {
                throw new IllegalArgumentException(
                        "Script step '" + scriptStep.getName()
                                + "' exceeded the statement limit ("
                                + DEFAULT_STATEMENT_LIMIT + ")",
                        e);
            }
            throw new IllegalArgumentException(
                    "Script step '" + scriptStep.getName() + "' failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Script step '" + scriptStep.getName()
                            + "' failed to serialize bindings: " + e.getMessage(),
                    e);
        }
    }

    private JsonNode executeGroovy(String mainSource, Map<String, Object> bindings,
            OperationStepScriptSpec scriptStep) {

        String locationUri = scriptStep.getLocation();

        CompilerConfiguration config = new CompilerConfiguration();
        SecureASTCustomizer secure = new SecureASTCustomizer();
        secure.setDisallowedImports(List.of("java.io.**", "java.nio.**",
                "java.net.**", "java.lang.Process", "java.lang.Runtime"));
        config.addCompilationCustomizers(secure);

        Binding binding = new Binding();
        binding.setVariable("context", bindings);

        GroovyShell shell = new GroovyShell(binding, config);

        // Evaluate dependent scripts in order
        List<String> dependencies = scriptStep.getDependencies();
        if (dependencies != null) {
            for (String depPath : dependencies) {
                String depSource = readScript(locationUri, depPath, scriptStep.getName());
                shell.evaluate(depSource);
            }
        }

        // Evaluate main script
        shell.evaluate(mainSource);

        // Extract result
        Object resultValue = binding.getVariable("result");
        return mapper.convertValue(resultValue, JsonNode.class);
    }

    Map<String, Object> buildBindings(Map<String, Object> runtimeParameters,
            StepExecutionContext stepContext, Map<String, Object> with) {

        Map<String, Object> bindings = new LinkedHashMap<>();

        // Add all runtime parameters
        if (runtimeParameters != null) {
            bindings.putAll(runtimeParameters);
        }

        // Add step outputs (converted to plain Java objects)
        if (stepContext != null) {
            for (Map.Entry<String, JsonNode> entry : stepContext.getAllStepOutputs().entrySet()) {
                bindings.put(entry.getKey(), mapper.convertValue(entry.getValue(), Object.class));
            }
        }

        // Apply 'with' overrides (Mustache-resolved)
        if (with != null) {
            for (Map.Entry<String, Object> entry : with.entrySet()) {
                Object rawValue = entry.getValue();
                if (rawValue instanceof String rawStringValue) {
                    bindings.put(entry.getKey(),
                            Resolver.resolveMustacheTemplate(rawStringValue, bindings));
                } else {
                    bindings.put(entry.getKey(), rawValue);
                }
            }
        }

        return bindings;
    }

    private String readScript(String locationUri, String relativePath, String stepName) {
        Path resolved = SafePathResolver.resolveAndValidate(locationUri, relativePath);
        try {
            return Files.readString(resolved, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Script step '" + stepName + "' cannot read file '"
                            + relativePath + "' from location '" + locationUri
                            + "': " + e.getMessage(),
                    e);
        }
    }

    JsonNode convertPolyglotValue(Value value) {
        if (value == null || value.isNull()) {
            return NullNode.instance;
        }
        if (value.isBoolean()) {
            return BooleanNode.valueOf(value.asBoolean());
        }
        if (value.isNumber()) {
            if (value.fitsInLong()) {
                return LongNode.valueOf(value.asLong());
            }
            return DoubleNode.valueOf(value.asDouble());
        }
        if (value.isString()) {
            return TextNode.valueOf(value.asString());
        }
        if (value.hasArrayElements()) {
            ArrayNode array = mapper.createArrayNode();
            for (long i = 0; i < value.getArraySize(); i++) {
                array.add(convertPolyglotValue(value.getArrayElement(i)));
            }
            return array;
        }
        if (value.hasMembers()) {
            ObjectNode obj = mapper.createObjectNode();
            for (String key : value.getMemberKeys()) {
                obj.set(key, convertPolyglotValue(value.getMember(key)));
            }
            return obj;
        }
        // Fallback: try to convert to string
        return TextNode.valueOf(value.toString());
    }

}
