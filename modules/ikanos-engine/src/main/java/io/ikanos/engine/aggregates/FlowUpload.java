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
package io.ikanos.engine.aggregates;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import org.restlet.Context;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.exposes.CallerClaims;
import io.ikanos.engine.util.Converter;
import io.ikanos.engine.util.FileInputs;
import io.ikanos.engine.util.OperationStepExecutor;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.aggregates.FlowUploadSpec;
import io.ikanos.spec.aggregates.SemanticsSpec;
import io.ikanos.spec.aggregates.UploadPresignSpec;
import io.ikanos.spec.aggregates.UploadStepSpec;
import io.ikanos.spec.aggregates.UploadVerifySpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import tools.jackson.databind.JsonNode;

/**
 * The {@code upload} block of a flow (experimental): its file inputs are stored by an upstream
 * that issues presigned upload URLs, so their bytes need not pass through Ikanos.
 *
 * <p>For each file, {@link #presign} calls the upstream's presign operation and reads the upload
 * target from its answer, and {@link #verify} calls its verify operation, such as a {@code HEAD}
 * on the stored object, and checks the reported size and type. Both calls see the call's
 * arguments, where the file argument has {@code id}, {@code name}, {@code mimeType}, {@code size}
 * and {@code uploadId}: the server-chosen name of the stored object, never the client's
 * {@code id}, so two calls never write to the same object and a client cannot choose where its
 * bytes land.</p>
 *
 * <p>The MCP adapter returns the targets to a client that sends the bytes itself
 * ({@code upload_required}). A file that arrives inline, over MCP or REST, is stored the same way
 * by {@link #stage}, so the flow always sees a stored file: {@code uploadId}, never
 * {@code data}.</p>
 */
public final class FlowUpload {

    /** Headers a target may not ask the client to send (the Fetch forbidden headers and more). */
    static final Set<String> FORBIDDEN_HEADERS = Set.of("accept-charset", "accept-encoding",
            "access-control-request-headers", "access-control-request-method", "authorization",
            "connection", "content-length", "cookie", "cookie2", "date", "dnt", "expect", "host",
            "keep-alive", "origin", "referer", "set-cookie", "te", "trailer",
            "transfer-encoding", "upgrade", "via", "forwarded", "x-original-url",
            "x-rewrite-url", "x-http-method-override", "x-http-method", "x-method-override");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(2);

    /** Taken off {@link #UPLOAD_EXPIRES_IN}, for clock skew and the time the presign takes. */
    static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    /** How long the target of a file that arrived inline may stay valid: Ikanos sends it now. */
    static final Duration STAGE_TTL = Duration.ofMinutes(10);

    /**
     * Member of the file a presign call sees: the seconds left until its target must expire,
     * for an upstream that takes the URL's lifetime in seconds.
     */
    public static final String UPLOAD_EXPIRES_IN = "uploadExpiresIn";

    private final FlowUploadSpec spec;
    private final OperationStepExecutor stepExecutor;
    private final String namespace;
    private final String flowRef;
    private final SemanticsSpec semantics;
    private volatile HttpClient http;

    FlowUpload(FlowUploadSpec spec, OperationStepExecutor stepExecutor, String namespace,
            String flowRef, SemanticsSpec semantics) {
        this.spec = spec;
        this.stepExecutor = stepExecutor;
        this.namespace = namespace;
        this.flowRef = flowRef;
        this.semantics = semantics;
    }

    /**
     * An upload target.
     *
     * @param url the absolute URL the client sends the bytes to, with {@code PUT}
     * @param headers the headers the client sends with them
     * @param expiresAt when the URL expires, or {@code null} when the upstream does not say
     */
    public record Target(String url, Map<String, String> headers, Instant expiresAt) {
    }

    /**
     * Whether a {@code requestState} that answers an upload request may run the flow only once:
     * unless the flow's {@code semantics} say it is {@code safe} or {@code idempotent}, running it
     * twice may be unsafe, as MCP derives {@code idempotentHint} from the same fields.
     */
    public boolean singleUse() {
        return semantics == null || !(Boolean.TRUE.equals(semantics.getSafe())
                || Boolean.TRUE.equals(semantics.getIdempotent()));
    }

    /** A new {@code uploadId}: 128 random bits, base64url without padding. */
    public static String newUploadId() {
        byte[] id = new byte[16];
        RANDOM.nextBytes(id);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(id);
    }

    /**
     * Asks the upstream for the target of one file.
     *
     * @param arguments the call's arguments
     * @param input the name of the file input
     * @param file the file as the presign call sees it: its metadata and {@code uploadId}
     * @param notAfter the latest the target may stay valid: the expiry of the
     *        {@code requestState} it is issued under. The presign call sees the seconds left
     *        until then, less {@link #EXPIRY_MARGIN}, as {@code {{<input>.uploadExpiresIn}}}, to
     *        ask for a URL that expires in time
     * @throws FileInputs.InvalidFileException when the upstream gives no usable target, or one
     *         that expires after {@code notAfter}; the call is then answered with an error
     *         result, the detail logged
     */
    public Target presign(Map<String, Object> arguments, String input, Map<String, Object> file,
            Instant notAfter) throws Exception {
        Map<String, Object> presigned = new LinkedHashMap<>(file);
        // Less a margin, so an upstream whose clock or answer runs a little late still presigns
        // a URL that expires before the state
        presigned.put(UPLOAD_EXPIRES_IN, Math.max(0,
                Duration.between(Instant.now(), notAfter).minus(EXPIRY_MARGIN).getSeconds()));
        ConsumedResult result = run(spec.getPresign(), arguments, input, presigned, "presign");
        try {
            if (result == null || !result.isSuccessful()) {
                Context.getCurrentLogger().warning("Upload presign of flow '" + flowRef
                        + "' failed with status " + (result != null ? result.status() : 0));
                throw refused(input, "could not be given an upload target");
            }
            return targetOf(result.document(), spec.getPresign(), input, notAfter);
        } finally {
            if (result != null) {
                result.release();
            }
        }
    }

    /**
     * Confirms with the upstream that the bytes of one file arrived, with the size the client
     * declared and, when the input restricts its type, an accepted type.
     *
     * @throws FileInputs.InvalidFileException when they did not; the flow must not run
     */
    public void verify(Map<String, Object> arguments, InputParameterSpec input,
            Map<String, Object> file, long size) throws Exception {
        ConsumedResult result = run(spec.getVerify(), arguments, input.getName(), file,
                "verify");
        try {
            checkAnswer(result, spec.getVerify(), input, size);
        } finally {
            if (result != null) {
                result.release();
            }
        }
    }

    /**
     * Checks the answer of a verify call: successful, and reporting the declared size and an
     * accepted type, read through the {@code size} and {@code mimeType} JsonPath expressions of
     * its JSON body, or, for one left out, the {@code Content-Length} or {@code Content-Type}
     * header.
     *
     * @throws FileInputs.InvalidFileException when the file is not confirmed
     */
    static void checkAnswer(ConsumedResult result, UploadVerifySpec verify,
            InputParameterSpec input, long size) throws java.io.IOException {
        if (result == null || !result.isSuccessful()) {
            throw refused(input.getName(), "has not arrived in storage");
        }
        JsonNode document = verify.getSize() != null || verify.getMimeType() != null
                ? result.document() : null;
        String reportedSize = verify.getSize() != null
                ? text(Converter.jsonPathExtract(document, verify.getSize()))
                : result.header("Content-Length");
        String reportedType = verify.getMimeType() != null
                ? text(Converter.jsonPathExtract(document, verify.getMimeType()))
                : result.header("Content-Type");
        checkVerified(input, size, reportedSize, reportedType);
    }

    /**
     * Stores a file that arrived inline as the client would have: presign, {@code PUT} of its
     * bytes to the target, verify.
     *
     * @param checked the file argument, checked, with its bytes in base64 in {@code data}
     * @return the file as the flow sees it: without {@code data}, with its {@code uploadId}
     */
    public Map<String, Object> stage(Map<String, Object> arguments, InputParameterSpec input,
            Map<String, Object> checked) throws Exception {
        String uploadId = newUploadId();
        Map<String, Object> stored = new LinkedHashMap<>(checked);
        Object data = stored.remove("data");
        stored.put(FileInputs.UPLOAD_ID, uploadId);
        byte[] bytes = data instanceof String text ? Base64.getDecoder().decode(text)
                : new byte[0];
        Target target = presign(arguments, input.getName(), stored,
                Instant.now().plus(STAGE_TTL));
        transfer(input.getName(), target, bytes);
        verify(arguments, input, stored, bytes.length);
        return stored;
    }

    /** Sends {@code bytes} to {@code target} with {@code PUT}, as a client would. */
    void transfer(String input, Target target, byte[] bytes) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target.url()))
                .timeout(TRANSFER_TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        target.headers().forEach(request::header);
        HttpResponse<Void> response =
                client().send(request.build(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            Context.getCurrentLogger().warning("Upload of a file of flow '" + flowRef
                    + "' was answered with status " + response.statusCode());
            throw refused(input, "could not be stored");
        }
    }

    private HttpClient client() {
        HttpClient client = http;
        if (client == null) {
            synchronized (this) {
                if (http == null) {
                    // Redirects are not followed, as the draft asks of a client
                    http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                            .connectTimeout(Duration.ofSeconds(30)).build();
                }
                client = http;
            }
        }
        return client;
    }

    /** Runs a presign or verify call for one file. */
    private ConsumedResult run(UploadStepSpec step, Map<String, Object> arguments, String input,
            Map<String, Object> file, String what) throws Exception {
        Map<String, Object> parameters = parameters(arguments, input, file);
        OperationStepExecutor.mergeWithParameters(step.getWith(), parameters, namespace);
        return stepExecutor.execute(new ServerCallSpec(step.getCall()), null, parameters,
                "Upload " + what + " of flow '" + flowRef + "'", namespace);
    }

    /**
     * The parameters of a presign or verify call: the call's arguments, where {@code input} is
     * {@code file}, and the caller's validated claims for {@code {{auth.claims...}}}.
     */
    static Map<String, Object> parameters(Map<String, Object> arguments, String input,
            Map<String, Object> file) {
        Map<String, Object> parameters = new HashMap<>();
        if (arguments != null) {
            parameters.putAll(arguments);
        }
        parameters.put(input, file);
        CallerClaims.putInto(parameters, CallerClaims.current());
        return parameters;
    }

    /**
     * The target in a presign answer.
     *
     * @throws FileInputs.InvalidFileException when it has no usable URL or headers
     */
    static Target targetOf(JsonNode document, UploadPresignSpec presign, String input,
            Instant notAfter) {
        String url = text(Converter.jsonPathExtract(document, presign.urlOrDefault()));
        if (url == null || !isUploadUrl(url)) {
            Context.getCurrentLogger().warning("Upload presign answer has no absolute https URL"
                    + " at " + presign.urlOrDefault());
            throw refused(input, "could not be given an upload target");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (presign.getHeaders() != null) {
            JsonNode node = Converter.jsonPathExtract(document, presign.getHeaders());
            if (node != null && !node.isNull()) {
                if (!node.isObject()) {
                    throw refused(input, "could not be given an upload target");
                }
                for (Map.Entry<String, JsonNode> header : node.properties()) {
                    String value = header.getValue().isValueNode()
                            ? header.getValue().asString() : null;
                    if (value == null || !isSendableHeader(header.getKey(), value)) {
                        Context.getCurrentLogger().warning("Upload presign answer asks for a "
                                + "header a client may not send");
                        throw refused(input, "could not be given an upload target");
                    }
                    headers.put(header.getKey(), value);
                }
            }
        }
        Instant expiresAt = null;
        if (presign.getExpiresAt() != null) {
            String text = text(Converter.jsonPathExtract(document, presign.getExpiresAt()));
            if (text != null) {
                try {
                    expiresAt = OffsetDateTime.parse(text).toInstant();
                } catch (DateTimeParseException e) {
                    Context.getCurrentLogger().log(Level.FINE,
                            "Upload presign answer has an expiresAt that is not a timestamp", e);
                }
            }
        }
        if (expiresAt != null && expiresAt.isAfter(notAfter)) {
            // The draft: a target must not stay valid after the requestState it was issued
            // under expires, and a presigned URL cannot be revoked
            Context.getCurrentLogger().warning("Upload presign answer expires after the "
                    + "requestState; ask for a shorter expiry, such as {{<input>."
                    + UPLOAD_EXPIRES_IN + "}} seconds");
            throw refused(input, "could not be given an upload target that expires in time");
        }
        return new Target(url, Map.copyOf(headers), expiresAt);
    }

    /**
     * Checks what the verify call reported against the file the client declared: the same size,
     * and, when the input declares {@code accept}, an accepted type.
     *
     * @throws FileInputs.InvalidFileException when it does not match
     */
    static void checkVerified(InputParameterSpec input, long size, String reportedSize,
            String reportedType) {
        Long stored = null;
        if (reportedSize != null) {
            try {
                stored = Long.parseLong(reportedSize.trim());
            } catch (NumberFormatException e) {
                stored = null;
            }
        }
        if (stored == null) {
            throw refused(input.getName(), "could not be confirmed in storage");
        }
        if (stored != size) {
            throw refused(input.getName(), "was stored with " + stored + " bytes, not the "
                    + size + " of its 'size'");
        }
        if (!input.getAccept().isEmpty() && (reportedType == null || reportedType.isBlank()
                || !FileInputs.accepts(input, reportedType, null))) {
            throw refused(input.getName(), "was stored with a type it does not accept");
        }
    }

    /**
     * Whether {@code url} can be an upload target: an absolute {@code https} URL without
     * userinfo, or an {@code http} one on a loopback host, which the draft lets an operator
     * allow.
     */
    static boolean isUploadUrl(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null || uri.getRawUserInfo() != null) {
                return false;
            }
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT)
                    : "";
            return "https".equals(scheme) || ("http".equals(scheme) && isLoopback(uri.getHost()));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean isLoopback(String host) {
        String bare = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        // Literal addresses only: a name other than localhost is never resolved here
        return "localhost".equalsIgnoreCase(bare) || "::1".equals(bare)
                || bare.matches("127(\\.\\d{1,3}){3}");
    }

    /**
     * Whether a client may send this header with a transfer: a valid RFC 9110 name and value,
     * not a credential, a forbidden header or a method or URL override.
     */
    static boolean isSendableHeader(String name, String value) {
        if (name == null || name.isEmpty() || value == null) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c > 126 || c <= 32 || "\"(),/:;<=>?@[\\]{}".indexOf(c) >= 0) {
                return false;
            }
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == 0) {
                return false;
            }
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return !FORBIDDEN_HEADERS.contains(lower) && !lower.startsWith("proxy-")
                && !lower.startsWith("sec-") && !lower.startsWith("x-forwarded-");
    }

    private static String text(JsonNode node) {
        return node != null && node.isValueNode() && !node.isNull() ? node.asString() : null;
    }

    private static FileInputs.InvalidFileException refused(String input, String reason) {
        return new FileInputs.InvalidFileException("File input '" + input + "' " + reason);
    }
}
