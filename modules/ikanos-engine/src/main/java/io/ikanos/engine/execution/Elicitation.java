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
package io.ikanos.engine.execution;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.durable.NodePath;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.Capability;
import io.ikanos.engine.aggregates.AggregateFlow;
import io.ikanos.engine.consumes.http.PathSegmentEncoder;
import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.mcp.McpClientSpec;
import io.ikanos.spec.consumes.mcp.McpClientToolSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepElicitSpec;
import io.ikanos.spec.util.OperationStepSpec;

/**
 * Whether an exposed entry may ask its caller for input: its steps, the flow it references, or
 * the target of its singleton {@code call:}, hold an {@code elicit} step, or call a consumed MCP
 * tool declared {@code mayElicit: true}, whose questions pass through, directly or through flows
 * they call, at any depth. Only such an entry needs the machinery of input requests; one that
 * cannot ask runs as before. Also renders the message an {@code elicit} step asks with and,
 * in URL mode, the URL it sends its caller to, with the question's id.
 */
public final class Elicitation {

    private static final ObjectMapper JSON = IkanosMappers.json();

    /** The variable a URL mode step's {@code url} and {@code check} read the question's id in. */
    static final String ELICITATION = "elicitation";

    /** Key prefix, in the state a replay carries, of the value a URL question's id derives from. */
    static final String CARRY_PREFIX = "elicitation/";

    /** Key prefix, in the state a replay carries, of the output of a finished URL mode step. */
    static final String DONE_PREFIX = "elicitation-done/";

    private static final int NONCE_BYTES = 16;
    private static final int ID_LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private Elicitation() {
    }

    /**
     * The text an {@code elicit} step shows its caller: its {@code message} rendered as a
     * Mustache template against the run's parameters (the inputs, then the outputs of the steps
     * that ran before it), or its name when it declares no message. A variable that nothing
     * provides fails the step rather than reach the caller blank; a part that may be missing goes
     * in a section, such as {@code {{#post.title}} '{{post.title}}'{{/post.title}}}.
     *
     * @param step the elicit step
     * @param parameters what the message renders against, see {@link #parameters(StepRun)};
     *        may be {@code null}
     * @throws IllegalStateException naming the variables nothing provides
     * @throws IllegalArgumentException when the message reads the caller's claims in a way
     *         {@link Resolver#requireCallerValues} refuses, such as
     *         {@link io.ikanos.engine.util.CallerClaimMissingException} for a claim the token
     *         lacks, checked first as for every other template
     */
    static String message(OperationStepElicitSpec step, Map<String, Object> parameters) {
        String template = step.getMessage();
        if (template == null) {
            return step.getName();
        }
        return render(step, template, "message", parameters);
    }

    /**
     * What an {@code elicit} message renders against: the run's parameters (the inputs, and the
     * outputs of call, script and elicit steps), plus the outputs kept only in the step context,
     * such as a {@code lookup} step's. A run parameter wins over a step output of the same name.
     */
    static Map<String, Object> parameters(StepRun run) {
        Map<String, Object> parameters = new HashMap<>(run.runtimeParameters());
        run.stepContext().getAllStepOutputs().forEach((name, output) -> {
            if (!parameters.containsKey(name) && output != null && !output.isNull()) {
                parameters.put(name, JSON.convertValue(output, Object.class));
            }
        });
        return parameters;
    }

    /**
     * The {@code {{elicitation.id}}} of the URL mode question asked under {@code key}, the full
     * key of its first visit: an opaque value, the same on every retry of the call, that the
     * page behind the URL and the step's {@code check} use to correlate the interaction.
     *
     * <p>In a journaled call it is derived from the call's execution, which is bound to its
     * caller. In a call that replays, it is derived from a random value generated when the
     * question is first asked and carried in the caller's sealed {@code requestState}, which
     * binds it to the principal, the request and an expiry.</p>
     *
     * @param newCarry where to put a value generated for the sealed state, when asking;
     *        {@code null} when answering, where the value must already be carried
     * @throws IllegalStateException when answering a call whose state carries no value
     */
    static String id(String key, Map<String, JsonNode> newCarry) {
        DurableScope scope = DurableScope.current();
        String base;
        if (scope != null) {
            base = "execution:" + scope.root().value();
        } else {
            String carryKey = CARRY_PREFIX + key;
            Object carried = ExecutionInputs.carried().get(new NodePath(carryKey));
            if (carried instanceof JsonNode node && node.isString()) {
                base = node.asString();
            } else if (carried instanceof String text) {
                base = text;
            } else if (newCarry != null) {
                byte[] nonce = new byte[NONCE_BYTES];
                RANDOM.nextBytes(nonce);
                base = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
                newCarry.put(carryKey, JSON.getNodeFactory().stringNode(base));
            } else {
                throw new IllegalStateException("The URL question '" + key
                        + "' was answered without the state it was asked with; call again");
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((base + "\n" + key).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
                    .substring(0, ID_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }

    /**
     * What a URL mode step's {@code url} and {@code check} render against: the run's parameters
     * ({@link #parameters(StepRun)}), plus {@code elicitation.id}.
     */
    static Map<String, Object> urlParameters(StepRun run, String id) {
        Map<String, Object> parameters = parameters(run);
        parameters.put(ELICITATION, Map.of("id", id));
        return parameters;
    }

    /**
     * What the {@code check} of the URL mode step at {@code node} renders against when the
     * caller answered it: the run's parameters, plus the {@code elicitation.id} its question was
     * asked with.
     *
     * @throws IllegalStateException when the call's state no longer carries the question's id
     */
    public static Map<String, Object> checkParameters(StepNode node, StepRun run) {
        return urlParameters(run, id(ExecutionInputs.qualify(node.path()).value(), null));
    }

    /**
     * The output of the URL mode step at {@code node} when an earlier round of the call found
     * it done, or {@code null}: it is restored, not decided again, so a check whose answer
     * changed since never asks the caller again nor gives later steps another output than the
     * one earlier rounds used.
     */
    public static JsonNode finishedOutput(StepNode node) {
        Object done = ExecutionInputs.carried()
                .get(new NodePath(DONE_PREFIX + ExecutionInputs.qualify(node.path()).value()));
        return done instanceof JsonNode json && json.isObject() ? json : null;
    }

    /**
     * Records that the URL mode step at {@code node} is done with {@code output}, to be kept in
     * the state the call carries to its next round.
     */
    public static void recordFinished(StepNode node, StepRun run, JsonNode output) {
        run.recordFinished(DONE_PREFIX + ExecutionInputs.qualify(node.path()).value(), output);
    }

    /**
     * The URL a URL mode step sends its caller to: its {@code url} rendered as a Mustache
     * template against {@code parameters}, as the message is.
     *
     * @throws IllegalStateException naming the variables nothing provides, or when the URL is
     *         not an absolute {@code https} URL; plain {@code http} is only accepted for a local
     *         host, in development
     */
    static String url(OperationStepElicitSpec step, Map<String, Object> parameters) {
        // Each value is percent-encoded by position, as in a consumed URI (GHSA-rvg4-2rxw-mc64):
        // an input or a step output stays one path segment or one query value, so it can add no
        // parameter and change no path; the template's literal text is kept as is.
        String url;
        try {
            url = PathSegmentEncoder.encodeMarkedValues(render(step, step.getUrl(), "URL",
                    parameters, PathSegmentEncoder::mark));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Step '" + step.getName() + "': " + e.getMessage());
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Step '" + step.getName()
                    + "': the URL is not a valid URI");
        }
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        boolean secure = "https".equals(scheme) && uri.getHost() != null;
        boolean local = "http".equals(scheme) && uri.getHost() != null
                && LOCAL_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
        if (!secure && !local) {
            throw new IllegalStateException("Step '" + step.getName()
                    + "': the URL must be an absolute https:// URL");
        }
        return url;
    }

    private static String render(OperationStepElicitSpec step, String template, String what,
            Map<String, Object> parameters) {
        return render(step, template, what, parameters, null);
    }

    private static String render(OperationStepElicitSpec step, String template, String what,
            Map<String, Object> parameters, java.util.function.UnaryOperator<String> encoder) {
        Resolver.requireCallerValues(template, parameters);
        List<String> unresolved = Resolver.unresolvedVariables(template, parameters);
        if (!unresolved.isEmpty()) {
            throw new IllegalStateException("Step '" + step.getName() + "': the " + what
                    + " reads " + String.join(", ", unresolved)
                    + ", which no input or earlier step provides");
        }
        return encoder == null ? Resolver.resolveMustacheTemplate(template, parameters)
                : Resolver.resolveMustacheTemplate(template, parameters, encoder);
    }

    /**
     * Whether an exposed entry may ask its caller for input.
     *
     * @param ref the flow the entry references with {@code ref:}, or {@code null}
     * @param call the entry's singleton {@code call:}, used when it references no flow; may be
     *        {@code null}
     * @param steps the entry's own steps, used when it references no flow; may be {@code null}
     */
    public static boolean mayAsk(Capability capability, String ref, ServerCallSpec call,
            Map<String, OperationStepSpec> steps) {
        return !modes(capability, ref, call, steps).isEmpty();
    }

    /**
     * The elicitation modes an exposed entry may ask its caller in, {@code form} and
     * {@code url}: those of its {@code elicit} steps, and form for a consumed MCP tool declared
     * {@code mayElicit: true}, whose questions pass through in form mode only. Empty when the
     * entry cannot ask.
     *
     * @param ref the flow the entry references with {@code ref:}, or {@code null}
     * @param call the entry's singleton {@code call:}, used when it references no flow; may be
     *        {@code null}
     * @param steps the entry's own steps, used when it references no flow; may be {@code null}
     */
    public static Set<String> modes(Capability capability, String ref, ServerCallSpec call,
            Map<String, OperationStepSpec> steps) {
        Set<AggregateFlow> seen = new HashSet<>();
        Set<String> modes = new TreeSet<>();
        if (ref != null) {
            collect(capability, findFlow(capability, ref), seen, modes);
        } else {
            collectCall(capability, call, seen, modes);
            collect(capability, steps, seen, modes);
        }
        return modes;
    }

    private static void collect(Capability capability, AggregateFlow flow,
            Set<AggregateFlow> seen, Set<String> modes) {
        if (flow == null || !seen.add(flow)) {
            return;
        }
        collectCall(capability, flow.getCall(), seen, modes);
        collect(capability, flow.getSteps(), seen, modes);
    }

    /** The modes of a singleton {@code call:} to a tool or a flow that may ask. */
    private static void collectCall(Capability capability, ServerCallSpec call,
            Set<AggregateFlow> seen, Set<String> modes) {
        if (call != null && call.getOperation() != null) {
            collectTarget(capability, call.getOperation(), seen, modes);
        }
    }

    private static void collectTarget(Capability capability, String target,
            Set<AggregateFlow> seen, Set<String> modes) {
        if (consumedToolAsks(capability, target)) {
            modes.add(OperationStepElicitSpec.FORM);
        }
        collect(capability, findFlow(capability, target), seen, modes);
    }

    private static void collect(Capability capability, Map<String, OperationStepSpec> steps,
            Set<AggregateFlow> seen, Set<String> modes) {
        if (steps == null) {
            return;
        }
        for (OperationStepSpec step : steps.values()) {
            if (step instanceof OperationStepElicitSpec elicit) {
                modes.add(elicit.isUrlMode() ? OperationStepElicitSpec.URL
                        : OperationStepElicitSpec.FORM);
            } else if (step instanceof OperationStepCallSpec call && call.getCall() != null) {
                collectTarget(capability, call.getCall(), seen, modes);
            }
        }
    }

    /** Whether {@code call} names a consumed MCP tool declared {@code mayElicit: true}. */
    public static boolean consumedToolAsks(Capability capability, String call) {
        int dot = call.indexOf('.');
        if (dot <= 0 || capability.getSpec() == null || capability.getSpec().getCapability() == null
                || capability.getSpec().getCapability().getConsumes() == null) {
            return false;
        }
        String namespace = call.substring(0, dot);
        String tool = call.substring(dot + 1);
        for (ClientSpec client : capability.getSpec().getCapability().getConsumes()) {
            if (client instanceof McpClientSpec mcp && namespace.equals(mcp.getNamespace())
                    && mcp.getTools() != null) {
                McpClientToolSpec spec = mcp.getTools().get(tool);
                return spec != null && spec.mayElicit();
            }
        }
        return false;
    }

    private static AggregateFlow findFlow(Capability capability, String ref) {
        try {
            return capability.lookupFlow(ref);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
