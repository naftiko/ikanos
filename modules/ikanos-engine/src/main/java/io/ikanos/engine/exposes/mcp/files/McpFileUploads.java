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
package io.ikanos.engine.exposes.mcp.files;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import io.ikanos.Capability;
import io.ikanos.engine.aggregates.AggregateFlow;
import io.ikanos.engine.aggregates.FlowUpload;
import io.ikanos.engine.exposes.mcp.McpRequestState;
import io.ikanos.engine.util.FileInputs;
import io.ikanos.engine.util.InputDefaults;
import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The out-of-band upload side of the MCP files extension (experimental): a tool that takes files
 * out of band answers a call that sends a file's metadata without its bytes with an
 * {@code upload_required} result, then runs on the retry once the bytes are confirmed. A tool
 * whose flow declares {@code upload} gets its targets from the flow's presign call (presigned
 * URLs on the upstream's storage); on an adapter that declares {@code files.uploads.hosted}, any
 * other tool that takes a file gets targets on the adapter itself ({@link HostedUploads}).
 *
 * <p>The initial call checks what needs no bytes ({@link FileInputs#pending}), asks the flow's
 * presign call for one target per file, each under a new {@code uploadId}, and seals in the
 * {@code requestState} the record that the upload request was issued: a random id, and each
 * file's input, id, size and {@code uploadId}. The state is also bound to the principal, the tool
 * and its arguments, and the spec digest, like any multi round-trip state. The retry
 * ({@link #confirm}) runs the flow's verify call for each recorded file, then
 * {@link #claim}s the state when the flow is unsafe to run twice, before the tool runs with the
 * confirmed files ({@link FileInputs#withUploads}).</p>
 *
 * <p>A hosted upload differs in three ways: its targets are {@code path} targets with
 * {@code credentials: include}, issued only when the adapter's quota has room for the files;
 * {@link #confirm} reads the bytes, so the tool sees each file as if it had been sent inline;
 * and its {@code requestState} is always single use, since the bytes are deleted once the call
 * completes ({@link #release}).</p>
 */
public final class McpFileUploads {

    private static final ObjectMapper MAPPER = IkanosMappers.json();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Member of a file of the upload record: the token of its hosted target. */
    private static final String HOSTED_TOKEN = "h";

    /** The flows of the tools that take files through presigned URLs, by tool name. */
    private final Map<String, AggregateFlow> flows;

    /** The file-checked inputs of the tools that take hosted uploads, by tool name. */
    private final Map<String, List<InputParameterSpec>> hostedInputs;

    /** The hosted uploads of the adapter, or {@code null} when no tool takes them. */
    private final HostedUploads hosted;
    private final McpRequestState requestState;
    private final UploadClaims claims;
    private final long maxInlineBytes;

    McpFileUploads(Map<String, AggregateFlow> flows, McpRequestState requestState,
            UploadClaims claims, long maxInlineBytes) {
        this(flows, Map.of(), null, requestState, claims, maxInlineBytes);
    }

    McpFileUploads(Map<String, AggregateFlow> flows,
            Map<String, List<InputParameterSpec>> hostedInputs, HostedUploads hosted,
            McpRequestState requestState, UploadClaims claims, long maxInlineBytes) {
        this.flows = Map.copyOf(flows);
        this.hostedInputs = Map.copyOf(hostedInputs);
        this.hosted = hosted;
        this.requestState = requestState;
        this.claims = claims;
        this.maxInlineBytes = maxInlineBytes;
    }

    /**
     * The upload side of an MCP adapter, or {@code null} when it declares no {@code files} or
     * exposes no tool that takes files out of band.
     *
     * @param toolInputs the inputs a {@code tools/call} of each tool checks, by tool name
     */
    public static McpFileUploads of(Capability capability, McpServerSpec serverSpec,
            Function<String, List<InputParameterSpec>> toolInputs, McpRequestState requestState,
            long maxInlineBytes) {
        if (serverSpec.getFiles() == null) {
            return null;
        }
        HostedUploads hosted = HostedUploads.of(serverSpec, Clock.systemUTC());
        Map<String, AggregateFlow> flows = new HashMap<>();
        Map<String, List<InputParameterSpec>> hostedInputs = new HashMap<>();
        for (McpServerToolSpec tool : serverSpec.getTools().values()) {
            if (tool == null || tool.getName() == null) {
                continue;
            }
            AggregateFlow flow = tool.getRef() != null ? capability.lookupFlow(tool.getRef())
                    : null;
            if (flow != null && flow.getUpload() != null) {
                flows.put(tool.getName(), flow);
            } else if (hosted != null) {
                // The inputs the tool call checks first: the tool's own when they take a file,
                // else its flow's, which a ref tool sends whatever it declares
                List<InputParameterSpec> inputs = toolInputs.apply(tool.getName());
                if (!FileInputs.takesFiles(inputs) && flow != null) {
                    inputs = flow.getInputParameters();
                }
                if (FileInputs.takesFiles(inputs)) {
                    hostedInputs.put(tool.getName(), List.copyOf(inputs));
                }
            }
        }
        return flows.isEmpty() && hostedInputs.isEmpty() ? null
                : new McpFileUploads(flows, hostedInputs,
                        hostedInputs.isEmpty() ? null : hosted, requestState,
                        new UploadClaims(Clock.systemUTC()), maxInlineBytes);
    }

    /** Whether the tool takes files out of band. */
    public boolean takesUploads(String toolName) {
        return toolName != null
                && (flows.containsKey(toolName) || hostedInputs.containsKey(toolName));
    }

    /** Whether the tool takes files out of band through targets hosted on the adapter. */
    public boolean takesHostedUploads(String toolName) {
        return toolName != null && hostedInputs.containsKey(toolName);
    }

    /** The names of the tools that take files out of band. */
    public Set<String> tools() {
        Set<String> tools = new java.util.HashSet<>(flows.keySet());
        tools.addAll(hostedInputs.keySet());
        return Set.copyOf(tools);
    }

    /** The hosted uploads of the adapter, or {@code null} when no tool takes them. */
    public HostedUploads hosted() {
        return hosted;
    }

    /**
     * Whether a {@code requestState} that answers an upload request of the tool may run it only
     * once: see {@link FlowUpload#singleUse()}. A hosted upload always is: its bytes are deleted
     * once the call completes.
     */
    public boolean singleUse(String toolName) {
        return hostedInputs.containsKey(toolName)
                || flows.get(toolName).getUpload().singleUse();
    }

    /**
     * The files of a call of the tool to send out of band, after every check that needs no
     * bytes; the inline files are checked in full.
     *
     * @throws FileInputs.InvalidFileException when a file is refused
     * @throws IllegalArgumentException when an argument does not match its declared type
     */
    public List<FileInputs.PendingFile> pending(String toolName, Map<String, Object> arguments) {
        List<InputParameterSpec> inputs = hostedInputs.get(toolName);
        if (inputs != null) {
            return FileInputs.pending(inputs, arguments, maxInlineBytes);
        }
        AggregateFlow flow = flows.get(toolName);
        return FileInputs.pending(flow.getInputParameters(), flowArguments(flow, arguments),
                maxInlineBytes);
    }

    /**
     * The {@code upload_required} result for the files of a call: one target per file, from the
     * flow's presign call, and the sealed {@code requestState} the retry presents.
     *
     * @param digest the digest of the request: the tool and its arguments
     * @throws FileInputs.InvalidFileException when a file cannot be given a target
     */
    public ObjectNode uploadRequired(String toolName, Map<String, Object> arguments,
            List<FileInputs.PendingFile> pending, String principal, String digest)
            throws Exception {
        Instant stateExpiresAt = requestState.now().plus(requestState.ttl())
                .truncatedTo(ChronoUnit.SECONDS);
        ArrayNode targets = MAPPER.createArrayNode();
        ObjectNode record = MAPPER.createObjectNode();
        String stateId = newId();
        record.put("n", stateId);
        ArrayNode files = record.putArray("f");

        if (hostedInputs.containsKey(toolName)) {
            // Reserved against the quota before any target is returned
            List<HostedUploads.Issued> issued = hosted.issue(MAPPER, pending, principal,
                    stateId, stateExpiresAt);
            for (int i = 0; i < pending.size(); i++) {
                FileInputs.PendingFile file = pending.get(i);
                targets.add(issued.get(i).target());
                files.addObject().put("p", file.input()).put("i", file.id())
                        .put("s", file.size()).put(HOSTED_TOKEN, issued.get(i).token());
            }
            return uploadRequiredResult(targets, requestState.sealUploads(principal, digest,
                    record, stateExpiresAt));
        }

        AggregateFlow flow = flows.get(toolName);
        Map<String, Object> callArguments = flowArguments(flow, arguments);
        for (FileInputs.PendingFile file : pending) {
            String uploadId = FlowUpload.newUploadId();
            FlowUpload.Target target = flow.getUpload().presign(callArguments, file.input(),
                    FileInputs.argument(file.id(), file.name(), file.mimeType(), file.size(),
                            null, null, uploadId), stateExpiresAt);
            // A target that expires after the state is refused by presign; without an expiry
            // from the upstream, the state's is advertised
            Instant expiresAt = target.expiresAt() == null ? stateExpiresAt
                    : target.expiresAt().truncatedTo(ChronoUnit.SECONDS);
            targets.add(McpFiles.uploadTarget(MAPPER, file.id(), target.url(), target.headers(),
                    expiresAt));
            files.addObject().put("p", file.input()).put("i", file.id()).put("s", file.size())
                    .put("u", uploadId);
        }

        return uploadRequiredResult(targets,
                requestState.sealUploads(principal, digest, record, stateExpiresAt));
    }

    private static ObjectNode uploadRequiredResult(ArrayNode targets, String sealedState) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("resultType", "upload_required");
        result.set(McpFiles.UPLOADS, targets);
        result.put("requestState", sealedState);
        return result;
    }

    /**
     * Confirms that the bytes of each file of an upload request arrived, with the flow's verify
     * call or, for a hosted upload, by reading them: the retry may then run the tool with them.
     *
     * @param uploads the upload record of the {@code requestState} the retry presents
     * @param principal the principal of the retry, which the state is bound to already
     * @return the confirmed files, by file id, for {@link FileInputs#withUploads}
     * @throws FileInputs.InvalidFileException when a file is not confirmed: the tool must not run
     * @throws IllegalArgumentException when the record does not match the arguments
     */
    public Map<String, FileInputs.Uploaded> confirm(String toolName, JsonNode uploads,
            Map<String, Object> arguments, String principal) throws Exception {
        List<InputParameterSpec> inputs = hostedInputs.get(toolName);
        if (inputs != null) {
            return confirmHosted(inputs, uploads, arguments, principal);
        }
        AggregateFlow flow = flows.get(toolName);
        Map<String, Object> callArguments = flowArguments(flow, arguments);
        Map<String, FileInputs.Uploaded> confirmed = new LinkedHashMap<>();
        for (JsonNode file : uploads.path("f")) {
            String inputName = file.path("p").asString("");
            InputParameterSpec input = flow.getInputParameters().stream()
                    .filter(candidate -> candidate.isFile()
                            && inputName.equals(candidate.getName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "requestState does not match this request"));
            Map<?, ?> argument = callArguments.get(inputName) instanceof Map<?, ?> map ? map
                    : Map.of();
            String id = file.path("i").asString("");
            long size = file.path("s").asLong(-1);
            String uploadId = file.path("u").asString("");
            Object name = argument.get("name");
            Object mimeType = argument.get("mimeType");
            flow.getUpload().verify(callArguments, input, FileInputs.argument(id,
                    name instanceof String text ? text : null,
                    mimeType instanceof String text ? text : "application/octet-stream", size,
                    null, null, uploadId), size);
            confirmed.put(id, new FileInputs.Uploaded(uploadId, size));
        }
        return confirmed;
    }

    private Map<String, FileInputs.Uploaded> confirmHosted(List<InputParameterSpec> inputs,
            JsonNode uploads, Map<String, Object> arguments, String principal) {
        String stateId = uploads.path("n").asString("");
        Map<String, FileInputs.Uploaded> confirmed = new LinkedHashMap<>();
        for (JsonNode file : uploads.path("f")) {
            String inputName = file.path("p").asString("");
            InputParameterSpec input = inputs.stream()
                    .filter(candidate -> candidate.isFile()
                            && inputName.equals(candidate.getName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "requestState does not match this request"));
            Object value = arguments != null ? arguments.get(inputName) : null;
            Map<?, ?> argument = value instanceof Map<?, ?> map ? map : Map.of();
            confirmed.put(file.path("i").asString(""), hosted.confirm(input,
                    file.path(HOSTED_TOKEN).asString(""), stateId, principal, argument,
                    file.path("s").asLong(-1)));
        }
        return confirmed;
    }

    /**
     * Deletes the hosted bytes of an upload request, once the retry that presented its
     * {@code requestState} completed or was refused: a hosted upload runs a tool once only.
     * Nothing to do for presigned uploads, which the upstream's storage keeps.
     */
    public void release(String toolName, JsonNode uploads) {
        if (hosted == null || !hostedInputs.containsKey(toolName)) {
            return;
        }
        List<String> tokens = new java.util.ArrayList<>();
        for (JsonNode file : uploads.path("f")) {
            String token = file.path(HOSTED_TOKEN).asString("");
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        hosted.release(tokens);
    }

    /** Starts purging the expired hosted uploads on a schedule, when the adapter hosts any. */
    public void start() {
        if (hosted != null) {
            hosted.start();
        }
    }

    /** Stops the purge schedule. */
    public void stop() {
        if (hosted != null) {
            hosted.stop();
        }
    }

    /**
     * Accepts the {@code requestState} of an upload request once: its record is claimed, in
     * {@link UploadClaims}, until the state expires.
     *
     * @return {@code false} when another retry claimed it first
     */
    public boolean claim(JsonNode uploads) {
        return claims.claim(uploads.path("n").asString(""),
                requestState.now().plus(requestState.ttl()));
    }

    /**
     * Whether the {@code requestState} of an upload request was claimed by an earlier retry: a
     * consumed state fails verification, before anything else is checked.
     */
    public boolean claimed(JsonNode uploads) {
        return claims.isClaimed(uploads.path("n").asString(""));
    }

    /**
     * The arguments as the flow takes them: only its declared inputs, with their defaults, each
     * checked against its declared type, as the tool call does before it runs.
     */
    static Map<String, Object> flowArguments(AggregateFlow flow, Map<String, Object> arguments) {
        Map<String, Object> kept = new HashMap<>();
        if (arguments != null) {
            for (InputParameterSpec input : flow.getInputParameters()) {
                if (arguments.containsKey(input.getName())) {
                    kept.put(input.getName(), arguments.get(input.getName()));
                }
            }
        }
        Map<String, Object> withDefaults = InputDefaults.apply(flow.getInputParameters(), kept);
        Map<String, Object> checked = new HashMap<>();
        for (InputParameterSpec input : flow.getInputParameters()) {
            Object value = withDefaults.get(input.getName());
            if (value != null) {
                checked.put(input.getName(), Resolver.requireDeclaredInputType(input, value));
            }
        }
        return checked;
    }

    /** A new random id: 128 bits, base64url without padding. */
    private static String newId() {
        byte[] id = new byte[16];
        RANDOM.nextBytes(id);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(id);
    }
}
