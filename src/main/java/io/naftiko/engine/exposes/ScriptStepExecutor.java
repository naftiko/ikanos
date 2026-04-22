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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import io.naftiko.spec.exposes.ScriptingManagementSpec;

/**
 * Executor for script operation steps.
 *
 * <p>Executes JavaScript and Python scripts in a sandboxed GraalVM polyglot context and Groovy
 * scripts via {@link GroovyShell} with AST security restrictions. Scripts are loaded from external
 * files referenced by a {@code file:///} URI directory and a relative file path.</p>
 *
 * <p>The executor injects previous step outputs and runtime parameters into the script context as
 * a {@code context} binding. The script must assign its output to a {@code result} variable.</p>
 *
 * <p>When a {@link ScriptingManagementSpec} is configured via the Control Port, the executor
 * uses its settings for defaults, limits, allowed languages, and the enabled toggle. The Control
 * Port settings override the {@code NAFTIKO_SCRIPTING} environment variable.</p>
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
    private volatile ScriptingManagementSpec scriptingSpec;

    /**
     * Called by the capability loader when a capability containing script steps is being
     * initialized — before any request is served. The Control Port {@code scripting.enabled}
     * overrides the environment variable when set.
     */
    static void requireScriptingPermitted(String stepName,
            ScriptingManagementSpec scriptingSpec) {
        boolean permitted;
        if (scriptingSpec != null) {
            permitted = scriptingSpec.isEnabled();
        } else {
            permitted = SCRIPTING_PERMITTED;
        }
        if (!permitted) {
            throw new IllegalStateException(
                    "Script step '" + stepName
                            + "' requires scripting support. Scripting is disabled"
                            + (scriptingSpec != null
                                    ? " via management.scripting.enabled=false on the control adapter."
                                    : " via NAFTIKO_SCRIPTING=false.")
                            + " Enable it to use script steps.");
        }
    }

    void setScriptingSpec(ScriptingManagementSpec scriptingSpec) {
        this.scriptingSpec = scriptingSpec;
    }

    ScriptingManagementSpec getScriptingSpec() {
        return scriptingSpec;
    }

    JsonNode execute(OperationStepScriptSpec scriptStep, Map<String, Object> runtimeParameters,
            StepExecutionContext stepContext) {

        // Resolve language — step-level overrides default
        String language = scriptStep.getLanguage();
        if ((language == null || language.isBlank()) && scriptingSpec != null) {
            language = scriptingSpec.getDefaultLanguage();
        }

        // Resolve location — step-level overrides default
        String locationUri = scriptStep.getLocation();
        if ((locationUri == null || locationUri.isBlank()) && scriptingSpec != null) {
            locationUri = scriptingSpec.getDefaultLocation();
        }

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

        // Check allowed languages
        if (scriptingSpec != null && !scriptingSpec.getAllowedLanguages().isEmpty()
                && !scriptingSpec.getAllowedLanguages().contains(language)) {
            throw new IllegalArgumentException(
                    "Script step '" + scriptStep.getName()
                            + "' uses language '" + language
                            + "' which is not in the allowed languages: "
                            + scriptingSpec.getAllowedLanguages());
        }

        // Resolve statement limit from governance
        long statementLimit = DEFAULT_STATEMENT_LIMIT;
        if (scriptingSpec != null && scriptingSpec.getStatementLimit() > 0) {
            statementLimit = scriptingSpec.getStatementLimit();
        }

        // Resolve timeout from governance (0 = no timeout when governance is not configured)
        int timeoutMs = 0;
        if (scriptingSpec != null && scriptingSpec.getTimeout() > 0) {
            timeoutMs = scriptingSpec.getTimeout();
        }

        String mainSource = readScript(locationUri, file, scriptStep.getName());

        Map<String, Object> bindings =
                buildBindings(runtimeParameters, stepContext, scriptStep.getWith());

        long startNanos = System.nanoTime();
        boolean error = false;
        try {
            JsonNode result;
            if ("groovy".equals(language)) {
                result = executeGroovy(mainSource, bindings, scriptStep, locationUri,
                        timeoutMs);
            } else {
                String polyglotId = LANGUAGE_ID_MAP.getOrDefault(language, language);
                result = executePolyglot(polyglotId, mainSource, bindings, scriptStep,
                        locationUri, statementLimit, timeoutMs);
            }
            return result;
        } catch (Exception e) {
            error = true;
            throw e;
        } finally {
            if (scriptingSpec != null) {
                scriptingSpec.recordExecution(System.nanoTime() - startNanos, error);
            }
        }
    }

    private JsonNode executePolyglot(String language, String mainSource,
            Map<String, Object> bindings, OperationStepScriptSpec scriptStep,
            String locationUri, long statementLimit, int timeoutMs) {

        boolean isPython = "python".equals(language);

        Context.Builder builder = Context.newBuilder(language)
                .allowAllAccess(false)
                .allowHostAccess(HostAccess.NONE)
                .allowIO(IOAccess.NONE)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowNativeAccess(false)
                .option("engine.WarnInterpreterOnly", "false")
                .resourceLimits(ResourceLimits.newBuilder()
                        .statementLimit(statementLimit, null)
                        .build());

        if (isPython) {
            builder.option("python.ForceImportSite", "false");
        }

        try (Context context = builder.build()) {

            // Schedule a watchdog that cancels the context after the timeout
            ScheduledExecutorService watchdog = null;
            ScheduledFuture<?> timeoutTask = null;
            if (timeoutMs > 0) {
                watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "script-timeout-watchdog");
                    t.setDaemon(true);
                    return t;
                });
                timeoutTask = watchdog.schedule(() -> context.close(true), timeoutMs,
                        TimeUnit.MILLISECONDS);
            }

            try {
                // Inject bindings
                String bindingsJson = mapper.writeValueAsString(bindings);
                if (isPython) {
                    injectPythonBindings(context, language, bindingsJson);
                } else {
                    context.eval(language, "var context = " + bindingsJson + ";");
                }

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
                Value resultValue = extractPolyglotResult(context, language, isPython);
                return convertPolyglotValue(resultValue);
            } finally {
                if (timeoutTask != null) {
                    timeoutTask.cancel(false);
                }
                if (watchdog != null) {
                    watchdog.shutdownNow();
                }
            }

        } catch (PolyglotException e) {
            if (e.isResourceExhausted()) {
                throw new IllegalArgumentException(
                        "Script step '" + scriptStep.getName()
                                + "' exceeded the statement limit ("
                                + statementLimit + ")",
                        e);
            }
            if (e.isCancelled()) {
                throw new IllegalArgumentException(
                        "Script step '" + scriptStep.getName()
                                + "' exceeded the timeout limit ("
                                + timeoutMs + " ms)",
                        e);
            }
            throw new IllegalArgumentException(
                    "Script step '" + scriptStep.getName() + "' failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Script step '" + scriptStep.getName()
                            + "' failed to serialize bindings: " + e.getMessage(),
                    e);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                throw e;
            }
            throw new IllegalArgumentException(
                    "Script step '" + scriptStep.getName() + "' failed: " + e.getMessage(), e);
        }
    }

    /**
     * Injects bindings into a Python context. Python cannot parse JSON literals as native syntax,
     * so the JSON string is passed as a binding variable and parsed via {@code json.loads()}.
     */
    private void injectPythonBindings(Context context, String language, String bindingsJson) {
        context.getBindings(language).putMember("__ctx_json", bindingsJson);
        context.eval(language, "import json as __json\n"
                + "context = __json.loads(__ctx_json)\n"
                + "del __ctx_json\n"
                + "del __json");
    }

    /**
     * Extracts the {@code result} variable from the polyglot context. Python uses different scoping
     * — module-level variables are not exposed via {@code getBindings()}, so we evaluate
     * {@code result} as an expression instead.
     */
    private Value extractPolyglotResult(Context context, String language, boolean isPython) {
        if (isPython) {
            try {
                return context.eval(language, "result");
            } catch (PolyglotException e) {
                if (e.isGuestException()) {
                    return null;
                }
                throw e;
            }
        }
        return context.getBindings(language).getMember("result");
    }

    private JsonNode executeGroovy(String mainSource, Map<String, Object> bindings,
            OperationStepScriptSpec scriptStep, String locationUri, int timeoutMs) {

        CompilerConfiguration config = new CompilerConfiguration();
        SecureASTCustomizer secure = new SecureASTCustomizer();
        secure.setDisallowedImports(List.of("java.io.**", "java.nio.**",
                "java.net.**", "java.lang.Process", "java.lang.Runtime"));
        secure.setDisallowedStarImports(List.of("java.io.", "java.nio.", "java.net.",
                "java.lang.reflect.", "javax."));
        secure.setDisallowedStaticImports(List.of(
                "java.lang.System.*", "java.lang.Runtime.*",
                "java.lang.ProcessBuilder.*"));
        secure.setDisallowedStaticStarImports(List.of(
                "java.lang.System.", "java.lang.Runtime.",
                "java.lang.ProcessBuilder.", "java.lang.reflect."));
        secure.setIndirectImportCheckEnabled(true);
        secure.setPackageAllowed(false);
        secure.setMethodDefinitionAllowed(false);

        // Block dangerous receivers — prevents FQN access to System, Runtime, Process,
        // reflection, classloading, threading, and scripting/compilation APIs.
        secure.setDisallowedReceivers(List.of(
                "java.lang.System",
                "java.lang.Runtime",
                "java.lang.ProcessBuilder",
                "java.lang.Process",
                "java.lang.Thread",
                "java.lang.ThreadGroup",
                "java.lang.ClassLoader",
                "java.lang.reflect.Field",
                "java.lang.reflect.Method",
                "java.lang.reflect.Constructor",
                "java.lang.reflect.Proxy",
                "java.lang.Class",
                "groovy.lang.GroovyShell",
                "groovy.lang.GroovyClassLoader",
                "groovy.util.Eval",
                "javax.script.ScriptEngineManager",
                "org.codehaus.groovy.runtime.InvokerHelper"
        ));

        // Block dangerous method calls that bypass static receiver checks at runtime:
        // - execute/execute(String[]): Groovy GDK adds these to String for process execution
        // - getClass: prevents dynamic reflection access via any object
        // - forName/getDeclaredMethod/getDeclaredField: Class reflection methods
        secure.addExpressionCheckers(new GroovySandboxExpressionChecker());
        config.addCompilationCustomizers(secure);

        Binding binding = new Binding();
        binding.setVariable("context", bindings);

        GroovyShell shell = new GroovyShell(binding, config);

        try {
            if (timeoutMs > 0) {
                executeGroovyWithTimeout(shell, mainSource, scriptStep, locationUri, timeoutMs);
            } else {
                executeGroovyDirect(shell, mainSource, scriptStep, locationUri);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception | Error e) {
            // Groovy's SecureASTCustomizer and compiler may throw Error subtypes
            // (e.g. GroovyBugError) when sandbox rules reject code at compile time.
            throw new IllegalArgumentException(
                    "Script step '" + scriptStep.getName() + "' failed: " + e.getMessage(), e);
        }

        // Extract result
        Object resultValue = binding.getVariable("result");
        return mapper.convertValue(resultValue, JsonNode.class);
    }

    private void executeGroovyDirect(GroovyShell shell, String mainSource,
            OperationStepScriptSpec scriptStep, String locationUri) {
        List<String> dependencies = scriptStep.getDependencies();
        if (dependencies != null) {
            for (String depPath : dependencies) {
                String depSource = readScript(locationUri, depPath, scriptStep.getName());
                shell.evaluate(depSource);
            }
        }
        shell.evaluate(mainSource);
    }

    private void executeGroovyWithTimeout(GroovyShell shell, String mainSource,
            OperationStepScriptSpec scriptStep, String locationUri, int timeoutMs) {

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "groovy-script-executor");
            t.setDaemon(true);
            return t;
        });

        try {
            Future<?> future = executor.submit(
                    () -> executeGroovyDirect(shell, mainSource, scriptStep, locationUri));

            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalArgumentException(
                    "Script step '" + scriptStep.getName()
                            + "' exceeded the timeout limit ("
                            + timeoutMs + " ms)",
                    e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) cause;
            }
            throw new IllegalArgumentException(
                    "Script step '" + scriptStep.getName() + "' failed: "
                            + (cause != null ? cause.getMessage() : e.getMessage()),
                    cause != null ? cause : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException(
                    "Script step '" + scriptStep.getName()
                            + "' was interrupted",
                    e);
        } finally {
            executor.shutdownNow();
        }
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
        if (value.hasHashEntries()) {
            ObjectNode obj = mapper.createObjectNode();
            Value iterator = value.getHashKeysIterator();
            while (iterator.hasIteratorNextElement()) {
                Value key = iterator.getIteratorNextElement();
                obj.set(key.asString(), convertPolyglotValue(value.getHashValue(key)));
            }
            return obj;
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
