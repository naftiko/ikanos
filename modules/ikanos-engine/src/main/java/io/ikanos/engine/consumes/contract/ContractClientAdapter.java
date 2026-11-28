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
package io.ikanos.engine.consumes.contract;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.restlet.Client;
import org.restlet.Context;
import org.restlet.data.Protocol;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.ClientAdapter;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.http.ConsumedAuthentication;
import io.ikanos.engine.consumes.tunnel.Tunnel;
import io.ikanos.engine.consumes.tunnel.TunnelClientTransport;
import io.ikanos.engine.consumes.tunnel.TunnelRouteTable;
import io.ikanos.engine.observability.OtelRestletBridge;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.engine.util.Secrets;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.ContractClientSpec;
import io.ikanos.spec.consumes.ContractOperationSpec;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.ikanos.spec.consumes.http.BasicAuthenticationSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;

/**
 * Base of the consumed adapters described by a local contract file ({@code grpc},
 * {@code graphql}, {@code smithy}), which the optional {@code ikanos-client-*} modules extend.
 *
 * <p>It holds what the three share: placeholders resolved once from {@code binds}, the static
 * header {@code inputParameters}, the credential as headers, the contract file read from the
 * capability's directory with a size cap, the Restlet {@link Client} each adapter borrows (with
 * the {@code tunnel} routed through {@link TunnelClientTransport}), and {@code readOnly},
 * declared or derived from the contract.</p>
 */
public abstract class ContractClientAdapter extends ClientAdapter {

    /** Largest contract file read, in bytes. */
    public static final long MAX_CONTRACT_BYTES = 16L * 1024 * 1024;

    private static final Pattern LOOPBACK_IPV4 = Pattern.compile(
            "127(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");

    private final Map<String, Tunnel> tunnels;
    private final Map<String, String> staticHeaders;

    protected ContractClientAdapter(Capability capability, ContractClientSpec spec,
            Map<String, Tunnel> tunnels) {
        super(capability, spec);
        this.tunnels = tunnels != null ? tunnels : Map.of();
        Map<String, String> headers = new LinkedHashMap<>();
        for (InputParameterSpec header : spec.getInputParameters()) {
            if (header.getIn() != null && !"header".equals(header.getIn())) {
                throw new IllegalArgumentException(where() + " input parameter '"
                        + header.getName() + "' must be a header (in: header)");
            }
            headers.put(header.getName(), resolve("inputParameters." + header.getName(),
                    header.getValue()));
        }
        this.staticHeaders = Collections.unmodifiableMap(headers);
    }

    /** @return the spec of this adapter */
    public ContractClientSpec getContractSpec() {
        return (ContractClientSpec) getSpec();
    }

    /** @return {@code consumes type <type> '<namespace>'}, for messages */
    protected String where() {
        return "consumes type " + getSpec().getType() + " '" + getNamespace() + "'";
    }

    /** @return the capability's bindings, never {@code null} */
    protected Map<String, Object> bindings() {
        Capability capability = getCapability();
        Map<String, Object> bindings = capability != null ? capability.getBindings() : null;
        return bindings != null ? bindings : Map.of();
    }

    /** Resolves the placeholders of a field from the bindings, once, at load time. */
    protected String resolve(String field, String value) {
        return resolveFromBindings(getNamespace(), field, value, bindings());
    }

    /** Resolves an absolute http(s) URI field from the bindings, once, at load time. */
    protected String resolveUri(String field, String value) {
        return resolveUriFromBindings(getNamespace(), field, value, bindings());
    }

    /** @return the static headers, resolved, keyed by header name */
    public Map<String, String> staticHeaders() {
        return staticHeaders;
    }

    /**
     * The credential as headers ({@code basic}, {@code bearer}, {@code apikey} in a header),
     * rendered from the bindings: what gRPC metadata and Smithy interceptors send.
     *
     * @return the headers, empty without authentication
     * @throws IllegalArgumentException for a credential that cannot be sent as a header
     */
    public Map<String, String> credentialHeaders() {
        AuthenticationSpec authentication = getContractSpec().getAuthentication();
        if (authentication == null) {
            return Map.of();
        }
        String prefix = where() + " authentication";
        if (authentication instanceof BasicAuthenticationSpec basic) {
            String user = render(basic.getUsername(), prefix + " username");
            String password = render(Secrets.password(basic.getPassword()),
                    prefix + " password");
            return Map.of("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
        if (authentication instanceof BearerAuthenticationSpec bearer) {
            return Map.of("Authorization", "Bearer " + render(bearer.getToken(),
                    prefix + " token"));
        }
        if (authentication instanceof ApiKeyAuthenticationSpec apiKey
                && !apiKey.isQueryPlacement()) {
            return Map.of(render(apiKey.getKey(), prefix + " key"),
                    render(apiKey.getValue(), prefix + " value"));
        }
        throw new IllegalArgumentException(where() + " cannot send an "
                + authentication.getType() + " credential as a header");
    }

    private String render(String template, String what) {
        String value = resolveFromBindings(getNamespace(), what, template, bindings());
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " is empty");
        }
        return value;
    }

    /**
     * Resolves a contract file path, which must stay inside the capability's directory (the
     * working directory when the capability has none): a relative path, or a relative
     * {@code file:} URI ({@code file:///./contracts/x.binpb}), resolved against that directory.
     * An absolute path, a {@code file:} URI naming one, a {@code ..} that leaves the directory and
     * a symbolic link that points outside it are refused.
     *
     * @param field the spec field, for messages
     * @param path the path as written, with placeholders
     * @return the resolved, normalized path
     * @throws IllegalArgumentException for a path that is empty, remote, or leaves the directory
     */
    protected Path contractPath(String field, String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(where() + " requires " + field);
        }
        String value = resolve(field, path);
        if (value.regionMatches(true, 0, "file:", 0, 5)) {
            // file:///./contracts/x.binpb is relative, as in binds; file:///abs/x.binpb is not
            value = value.substring(5).replaceFirst("^//", "");
            if (value.startsWith("/./") || value.startsWith("/../")) {
                value = value.substring(1);
            }
        } else if (value.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*")) {
            throw new IllegalArgumentException(where() + " " + field
                    + " must be a local file: contracts are never fetched");
        }
        String outside = where() + " " + field + " '" + value
                + "' must be a relative path inside the capability's directory";
        Path file;
        try {
            file = Path.of(value);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(outside, e);
        }
        if (file.isAbsolute() || file.getRoot() != null) {
            throw new IllegalArgumentException(outside);
        }
        Capability capability = getCapability();
        Path dir = capability != null ? capability.getCapabilityDir() : null;
        Path base = (dir != null ? dir : Path.of("")).toAbsolutePath().normalize();
        Path resolved = base.resolve(file).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException(outside);
        }
        if (Files.exists(resolved)) {
            try {
                if (!resolved.toRealPath().startsWith(base.toRealPath())) {
                    throw new IllegalArgumentException(outside
                            + ": it links outside the directory");
                }
            } catch (IOException e) {
                throw new IllegalArgumentException(where() + " cannot read " + field + " '"
                        + resolved + "': " + e.getMessage(), e);
            }
        }
        return resolved;
    }

    /**
     * Reads a contract file: a regular file inside the capability's directory
     * ({@link #contractPath}), refused when larger than {@link #MAX_CONTRACT_BYTES}.
     */
    protected byte[] readContract(String field, String path) {
        Path file = contractPath(field, path);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException(where() + " cannot read " + field + " '" + file
                    + "': not a regular file");
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes((int) MAX_CONTRACT_BYTES + 1);
            if (bytes.length > MAX_CONTRACT_BYTES) {
                throw new IllegalArgumentException(where() + " " + field + " is more than "
                        + MAX_CONTRACT_BYTES + " bytes");
            }
            return bytes;
        } catch (IOException e) {
            throw new IllegalArgumentException(where() + " cannot read " + field + " '" + file
                    + "': " + e.getMessage(), e);
        }
    }

    /**
     * Creates the Restlet client this adapter borrows. With a {@code tunnel} whose type was
     * started, connections to the endpoint's host go through it.
     *
     * @param endpoint the resolved endpoint, whose host is tunneled
     * @param context the client context, from {@link ConsumedAuthentication#clientContext}
     * @param protocols the client protocols
     * @return the client, not started
     */
    protected Client newRestletClient(String endpoint, Context context, List<Protocol> protocols) {
        Tunnel tunnel = tunnel();
        if (tunnel != null) {
            TunnelRouteTable routes = new TunnelRouteTable();
            routes.register(URI.create(endpoint).getHost(), tunnel);
            TunnelClientTransport.install(context, routes);
        }
        return new Client(context, protocols);
    }

    /** @return the started tunnel this adapter routes through, or {@code null} */
    protected Tunnel tunnel() {
        ContractClientSpec spec = getContractSpec();
        return spec.getTunnel() != null ? tunnels.get(spec.getTunnel().getType()) : null;
    }

    @Override
    public boolean declaresOperation(String operationName) {
        return getContractSpec().operations().containsKey(operationName);
    }

    /**
     * Whether an operation only reads: its declared {@code readOnly}, or what the contract says.
     *
     * @param operationName the operation's call name
     * @return whether it only reads; {@code false} for an unknown operation
     */
    public boolean isReadOnly(String operationName) {
        ContractOperationSpec operation = getContractSpec().operations().get(operationName);
        if (operation == null) {
            return false;
        }
        Boolean declared = operation.getReadOnly();
        return declared != null ? declared : contractReadOnly(operationName);
    }

    @Override
    public boolean isWriteOperation(String operationName) {
        return declaresOperation(operationName) && !isReadOnly(operationName);
    }

    /**
     * @param operationName the operation's call name
     * @return whether the contract says the operation only reads
     */
    protected abstract boolean contractReadOnly(String operationName);

    /**
     * Checks a declared {@code readOnly} against the contract: an operation the contract says
     * writes cannot be declared read-only, unless the contract cannot tell ({@code trusting}).
     */
    protected void checkReadOnly(String operationName, boolean contractReadOnly,
            boolean trusting) {
        ContractOperationSpec operation = getContractSpec().operations().get(operationName);
        if (operation != null && Boolean.TRUE.equals(operation.getReadOnly())
                && !contractReadOnly && !trusting) {
            throw new IllegalArgumentException(where() + " operation '" + operationName
                    + "' declares readOnly: true, but its contract says it writes");
        }
    }

    /** @return the output declarations of an operation */
    protected static ConsumedOperationView view(ContractOperationSpec operation) {
        return ConsumedOperationView.of(operation, null, null);
    }

    /** @return {@code text}, cut to at most {@code max} characters, for a reason */
    public static String bounded(String text, int max) {
        if (text == null) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }

    /**
     * The W3C trace context of the current span, as headers ({@code traceparent},
     * {@code tracestate}), for protocols whose requests Restlet does not build itself.
     *
     * @return the headers, empty without a current span
     */
    public static Map<String, String> traceHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        TelemetryBootstrap.get().getOpenTelemetry().getPropagators().getTextMapPropagator()
                .inject(OtelRestletBridge.currentContext(), headers, (carrier, key, value) -> {
                    if (carrier != null) {
                        carrier.put(key, value);
                    }
                });
        return headers;
    }

    /**
     * Whether the host of a URI is a loopback address: {@code localhost}, an IPv4 literal in
     * {@code 127.0.0.0/8}, or {@code ::1} (bracketed or not). Never asks DNS, so a host name
     * such as {@code 127.example.com} is not loopback.
     *
     * @param host the host
     * @return whether it is a loopback address
     */
    public static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(h) || "::1".equals(h) || "[::1]".equals(h)
                || LOOPBACK_IPV4.matcher(h).matches();
    }
}
