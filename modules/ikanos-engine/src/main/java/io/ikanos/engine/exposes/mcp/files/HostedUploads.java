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

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import io.ikanos.engine.util.BinarySize;
import io.ikanos.engine.util.FileInputs;
import io.ikanos.engine.util.FileSignatures;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.exposes.mcp.McpFileUploadsSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Uploads hosted on the MCP adapter itself (files extension, experimental): for a tool whose
 * flow has no presign call, the {@code upload_required} target is a path on the adapter's own
 * origin, {@code <MCP address>/files/<token>}, with {@code credentials: include}.
 *
 * <ul>
 * <li>{@link #issue}: one slot per file, reserved at its declared size against the adapter's
 * {@code quota} before any target is returned, bound to the caller and to the upload record of
 * the {@code requestState}. The target accepts its {@code PUT} until {@code ttl} has passed,
 * never after the {@code requestState} expires.</li>
 * <li>{@link #receive}: the {@code PUT}, behind the adapter's authentication chain, from the
 * principal the target was issued to only, once, with the declared {@code Content-Type} and a
 * {@code Content-Length} of exactly the declared size. The body is read up to that size and no
 * further.</li>
 * <li>{@link #confirm}: on the retry, the bytes are there, of the declared size, look like the
 * declared type when the input restricts it, and match the {@code digest} when one is given.</li>
 * <li>{@link #release}: the bytes are deleted once the call completes; a schedule purges the
 * slots whose {@code requestState} has expired, and those never written once their target has.
 * </li>
 * </ul>
 */
public final class HostedUploads {

    /** How often expired slots are purged. */
    static final Duration PURGE_PERIOD = Duration.ofSeconds(30);

    /** A media type, as a {@code Content-Type} value: type/subtype and optional parameters. */
    private static final Pattern MEDIA_TYPE = Pattern.compile(
            "[!#$%&'*+.^_`|~0-9A-Za-z-]+/[!#$%&'*+.^_`|~0-9A-Za-z-]+"
                    + "(\\s*;\\s*[!#$%&'*+.^_`|~0-9A-Za-z-]+=(\"[^\"\\\\\\p{Cntrl}]*\""
                    + "|[!#$%&'*+.^_`|~0-9A-Za-z-]+))*");

    private static final SecureRandom RANDOM = new SecureRandom();

    /** What a {@code PUT} to a hosted target came to, as an HTTP status. */
    public enum Received {
        /** Stored: {@code 204}. */
        STORED(204),
        /** No target for this principal, or it expired: {@code 404}, whatever the reason. */
        NOT_FOUND(404),
        /** The target was written already: {@code 409}. */
        ALREADY_WRITTEN(409),
        /** No {@code Content-Length}: {@code 411}. */
        LENGTH_REQUIRED(411),
        /** Another {@code Content-Type} than the target lists: {@code 415}. */
        WRONG_TYPE(415),
        /** More bytes than the declared size: {@code 413}. */
        TOO_LARGE(413),
        /** Fewer bytes than the declared size, or a body that broke off: {@code 400}. */
        TOO_SHORT(400);

        private final int status;

        Received(int status) {
            this.status = status;
        }

        /** @return the HTTP status code of the answer */
        public int status() {
            return status;
        }
    }

    /** A target issued for one file, and the token of its path. */
    public record Issued(String token, ObjectNode target) {
    }

    private final UploadStore store;
    private final Duration ttl;
    private final Clock clock;
    private final String namespace;
    private ScheduledExecutorService purger;

    HostedUploads(UploadStore store, Duration ttl, Clock clock, String namespace) {
        this.store = store;
        this.ttl = ttl;
        this.clock = clock;
        this.namespace = namespace;
    }

    /**
     * The hosted uploads of an MCP adapter, or {@code null} unless its {@code files.uploads}
     * declares {@code hosted: true}.
     *
     * @throws IllegalArgumentException when the adapter runs on stdio, which has no address to
     *         host a path target on, or when {@code quota} or {@code ttl} is invalid
     */
    public static HostedUploads of(McpServerSpec spec, Clock clock) {
        if (spec.getFiles() == null || !spec.getFiles().uploadsOrDefault().isHosted()) {
            return null;
        }
        McpFileUploadsSpec uploads = spec.getFiles().uploadsOrDefault();
        String where = "files.uploads of MCP namespace '" + spec.getNamespace() + "'";
        if (spec.isStdio()) {
            throw new IllegalArgumentException(where + " declares hosted: true, but stdio has no"
                    + " address to receive uploads on");
        }
        if (!McpFileDownloads.resendsCredential(spec.getAuthentication())) {
            throw new IllegalArgumentException(where + " declares hosted: true, but its"
                    + " authentication (" + spec.getAuthentication().getType() + ") is not one a"
                    + " client resends on an include target; use none, bearer, basic, oauth2 or"
                    + " a query apikey");
        }
        long quota = BinarySize.parse(uploads.quotaOrDefault());
        Duration ttl;
        try {
            ttl = Duration.parse(uploads.ttlOrDefault());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(where + ": ttl is not an ISO 8601 duration: "
                    + uploads.ttlOrDefault(), e);
        }
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException(where + ": ttl must be positive: "
                    + uploads.ttlOrDefault());
        }
        return new HostedUploads(new MemoryUploadStore(quota, MemoryUploadStore.DEFAULT_MAX_SLOTS,
                FileMemoryBudget.shared()), ttl, clock, spec.getNamespace());
    }

    /**
     * Reserves a slot for each file of a call and gives each one its target.
     *
     * @param stateId the id of the upload record the {@code requestState} seals
     * @param stateExpiresAt when that {@code requestState} expires: no target outlives it
     * @throws FileInputs.InvalidFileException when a file's type cannot be sent as a
     *         {@code Content-Type}, or when the files do not fit in the quota
     */
    public List<Issued> issue(ObjectMapper mapper, List<FileInputs.PendingFile> files,
            String principal, String stateId, Instant stateExpiresAt) {
        Instant now = clock.instant();
        Instant writableUntil = now.plus(ttl);
        if (writableUntil.isAfter(stateExpiresAt)) {
            writableUntil = stateExpiresAt;
        }
        // Advertised to the second, never later than the target really closes
        Instant advertised = writableUntil.truncatedTo(ChronoUnit.SECONDS);
        Map<String, UploadStore.Slot> slots = new LinkedHashMap<>();
        List<Issued> issued = new java.util.ArrayList<>();
        for (FileInputs.PendingFile file : files) {
            if (!MEDIA_TYPE.matcher(file.mimeType()).matches()) {
                throw new FileInputs.InvalidFileException("File input '" + file.input()
                        + "' has a 'mimeType' that is not a media type");
            }
            String token = newToken();
            slots.put(token, new UploadStore.Slot(principal, stateId, file.mimeType(),
                    file.size(), writableUntil, stateExpiresAt));
            issued.add(new Issued(token, McpFiles.hostedUploadTarget(mapper, file.id(),
                    "/" + McpFileDownloads.PATH_SEGMENT + "/" + token, file.mimeType(),
                    advertised)));
        }
        if (!store.reserve(slots, now)) {
            throw new FileInputs.InvalidFileException("The files of this call do not fit in the"
                    + " storage this server keeps for uploads; try again later");
        }
        return issued;
    }

    /**
     * Receives the {@code PUT} of a hosted target. The authentication chain in front has checked
     * the credential already, as for any MCP request.
     *
     * @param principal the principal of the request, as the MCP endpoint binds state to it
     * @param contentType the {@code Content-Type} header, or {@code null}
     * @param contentLength the {@code Content-Length}, or a negative value when unknown
     * @param body the request body, read up to the declared size and no further
     */
    public Received receive(String token, String principal, String contentType,
            long contentLength, InputStream body) {
        UploadStore.Started started = store.beginWrite(token, principal, clock.instant());
        if (started.write() == UploadStore.Write.NOT_FOUND) {
            return Received.NOT_FOUND;
        }
        if (started.write() == UploadStore.Write.TAKEN) {
            return Received.ALREADY_WRITTEN;
        }
        UploadStore.Slot slot = started.slot();
        boolean stored = false;
        try {
            if (contentType == null || !contentType.trim().equalsIgnoreCase(slot.mimeType())) {
                return Received.WRONG_TYPE;
            }
            if (contentLength < 0) {
                return Received.LENGTH_REQUIRED;
            }
            if (contentLength > slot.size()) {
                return Received.TOO_LARGE;
            }
            if (contentLength < slot.size()) {
                return Received.TOO_SHORT;
            }
            byte[] bytes = new byte[Math.toIntExact(slot.size())];
            int read = body != null ? body.readNBytes(bytes, 0, bytes.length) : 0;
            if (read < bytes.length) {
                return Received.TOO_SHORT;
            }
            if (body != null && body.read() != -1) {
                return Received.TOO_LARGE;
            }
            store.completeWrite(token, bytes);
            stored = true;
            return Received.STORED;
        } catch (IOException | ArithmeticException e) {
            return Received.TOO_SHORT;
        } finally {
            if (!stored) {
                store.abortWrite(token);
            }
        }
    }

    /**
     * Confirms the hosted bytes of one file on the retry, before the tool runs: they arrived, for
     * this principal and this {@code requestState}, are of the declared size, look like the
     * declared type when the input restricts it, and match the {@code digest} when the argument
     * gives one.
     *
     * @param argument the file argument of the retry
     * @throws FileInputs.InvalidFileException when the bytes cannot be confirmed: the tool must
     *         not run
     */
    public FileInputs.Uploaded confirm(InputParameterSpec input, String token, String stateId,
            String principal, Map<?, ?> argument, long size) {
        Optional<UploadStore.Stored> stored = store.read(token, clock.instant());
        if (stored.isEmpty() || !stored.get().slot().stateId().equals(stateId)
                || !stored.get().slot().principal().equals(principal)) {
            throw refused(input, "was not uploaded to its target");
        }
        byte[] bytes = stored.get().bytes();
        if (bytes.length != size) {
            throw refused(input, "was uploaded with another size than its 'size'");
        }
        if (!input.getAccept().isEmpty() && FileSignatures.check(stored.get().slot().mimeType(),
                bytes) == FileSignatures.Verdict.MISMATCH) {
            throw refused(input, "does not look like its declared 'mimeType'");
        }
        FileInputs.checkDigest(input, argument.get("digest"), bytes);
        return FileInputs.Uploaded.hosted(bytes);
    }

    /** Deletes the hosted bytes of a call that completed, or that can no longer run. */
    public void release(List<String> tokens) {
        for (String token : tokens) {
            store.delete(token);
        }
    }

    /** Starts purging expired slots on a schedule. */
    public synchronized void start() {
        if (purger == null) {
            purger = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "mcp-files-purge-" + namespace);
                thread.setDaemon(true);
                return thread;
            });
            purger.scheduleAtFixedRate(this::purge, PURGE_PERIOD.toMillis(),
                    PURGE_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Stops the purge schedule. */
    public synchronized void stop() {
        if (purger != null) {
            purger.shutdownNow();
            purger = null;
        }
    }

    /** Purges the expired slots now. */
    int purge() {
        return store.purge(clock.instant());
    }

    private static FileInputs.InvalidFileException refused(InputParameterSpec input,
            String reason) {
        return new FileInputs.InvalidFileException("File input '" + input.getName() + "' "
                + reason);
    }

    /** A new target token: 256 random bits, base64url without padding (43 characters). */
    private static String newToken() {
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }
}
