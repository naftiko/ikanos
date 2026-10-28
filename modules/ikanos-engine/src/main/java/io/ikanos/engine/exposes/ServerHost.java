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
package io.ikanos.engine.exposes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Pattern;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.Server;
import org.restlet.routing.Router;
import org.restlet.routing.Template;
import org.restlet.routing.VirtualHost;
import org.restlet.service.StatusService;
import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.exposes.ServerSpec;

/**
 * Owns the listeners that several exposed adapters share. Adapters are grouped by
 * {@code (address, port)}. A listener used by a single adapter mounted at the root on any host
 * stays that adapter's own server, exactly as before. Any other listener gets one Restlet
 * {@link Component} that routes each request by {@code Host} header, then by path prefix:
 * <ul>
 * <li>one {@link VirtualHost} per host name or wildcard, exact names before wildcards, and the
 * default host for adapters without {@code hosts};</li>
 * <li>within a host, a {@link SegmentRoute} per adapter {@code path}, longest prefix first;</li>
 * <li>well-known paths declared by adapters (such as RFC 9728 metadata), routed to their adapter
 * from the host root because they sit outside its prefix.</li>
 * </ul>
 * Each adapter keeps its own handler chain, authentication included.
 */
public class ServerHost {

    /** A host name, exact or with one leading {@code *.} label, without a port. */
    static final Pattern HOST_PATTERN = Pattern.compile(
            "^(\\*\\.)?([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)"
                    + "(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$");

    /** {@code /} or full path segments without a trailing slash or template variables. */
    static final Pattern PATH_PATTERN = Pattern.compile("^(/|(/[^/{}?#;\\s]+)+)$");

    /** Marks the default host, which answers any host name. */
    static final String ANY_HOST = "";

    /**
     * A listener's address and port. {@code ephemeral} tells apart adapters on port 0, which
     * each get their own port; it is 0 for a fixed port.
     */
    record ListenerKey(String address, int port, int ephemeral) {

        @Override
        public String toString() {
            return (address != null ? address : "*") + ":" + port;
        }
    }

    /** One adapter as mounted on a shared listener. */
    record Mount(ServerAdapter adapter, String path, List<String> hosts) {
    }

    /** A listener shared by several adapters, or restricted by path or host. */
    record SharedListener(ListenerKey key, Server server, Component component, List<Mount> mounts) {
    }

    private final List<SharedListener> listeners;

    private ServerHost(List<SharedListener> listeners) {
        this.listeners = Collections.unmodifiableList(listeners);
    }

    /**
     * Groups the HTTP adapters by listener, validates their mounts and builds a component for
     * each listener that needs routing. Adapters on such a listener stop opening their own
     * server.
     *
     * @param adapters the capability's server adapters
     * @param bindings the resolved capability bindings, used by Mustache {@code hosts} entries
     * @return the server host, not started
     * @throws IllegalArgumentException if a mount is invalid or conflicts with another
     */
    public static ServerHost build(List<ServerAdapter> adapters, Map<String, Object> bindings) {
        Map<ListenerKey, List<Mount>> byListener = new LinkedHashMap<>();
        for (ServerAdapter adapter : adapters) {
            if (adapter.getHandler() == null) {
                continue; // no HTTP transport, e.g. MCP over stdio
            }
            Server own = adapter.getServer();
            Mount mount = new Mount(adapter, normalizePath(adapter),
                    resolveHosts(adapter, bindings));
            String address = own.getAddress() != null
                    ? own.getAddress().toLowerCase(Locale.ROOT) : null;
            // An ephemeral port (0) is picked per server, so such a listener is never shared.
            int ephemeral = own.getPort() <= 0 ? byListener.size() + 1 : 0;
            ListenerKey key = new ListenerKey(address, own.getPort(), ephemeral);
            byListener.computeIfAbsent(key, k -> new ArrayList<>()).add(mount);
        }

        List<SharedListener> shared = new ArrayList<>();
        for (Map.Entry<ListenerKey, List<Mount>> entry : byListener.entrySet()) {
            List<Mount> mounts = entry.getValue();
            if (mounts.size() == 1 && mounts.get(0).path().isEmpty()
                    && mounts.get(0).hosts().isEmpty()) {
                continue; // a dedicated listener, as before shared listeners existed
            }
            shared.add(buildListener(entry.getKey(), mounts));
        }
        return new ServerHost(shared);
    }

    static SharedListener buildListener(ListenerKey key, List<Mount> mounts) {
        for (Mount mount : mounts) {
            if ("control".equals(mount.adapter().getSpec().getType()) && mounts.size() > 1) {
                throw new IllegalArgumentException("The control adapter on " + key
                        + " cannot share its listener with other adapters."
                        + " Use a dedicated port for the control adapter.");
            }
        }

        Map<String, List<Mount>> byHost = groupByHost(mounts);
        for (Map.Entry<String, List<Mount>> entry : byHost.entrySet()) {
            validateHostMounts(key, entry.getKey(), entry.getValue());
        }

        Component component = new Component();
        // A dedicated server answers with no access log and no generated error pages; keep the
        // shared listener identical so moving an adapter onto it changes nothing for clients.
        component.getLogService().setEnabled(false);
        StatusService statusService = component.getServices().get(StatusService.class);
        if (statusService != null) {
            statusService.setEnabled(false);
        }

        Server server = ServerAdapter.createServer(key.address(), key.port());
        component.getServers().add(server);

        for (Map.Entry<String, List<Mount>> entry : byHost.entrySet()) {
            VirtualHost host;
            if (ANY_HOST.equals(entry.getKey())) {
                host = component.getDefaultHost();
            } else {
                host = new VirtualHost(component.getContext().createChildContext());
                host.setHostDomain(toHostRegex(entry.getKey()));
                component.getHosts().add(host);
            }
            host.attachDefault(buildPrefixRouter(host.getContext(), entry.getValue()));
        }

        for (Mount mount : mounts) {
            mount.adapter().useSharedServer(server);
        }

        Context.getCurrentLogger().log(Level.INFO,
                () -> "Shared listener on " + key + ": " + describe(byHost));
        return new SharedListener(key, server, component, List.copyOf(mounts));
    }

    /**
     * Groups mounts by host name. Exact names come first, then wildcards, then the default
     * host: a component tries its virtual hosts in order, so the most specific name wins.
     */
    static Map<String, List<Mount>> groupByHost(List<Mount> mounts) {
        Map<String, List<Mount>> exact = new LinkedHashMap<>();
        Map<String, List<Mount>> wildcard = new LinkedHashMap<>();
        List<Mount> any = new ArrayList<>();
        for (Mount mount : mounts) {
            if (mount.hosts().isEmpty()) {
                any.add(mount);
            }
            for (String host : mount.hosts()) {
                Map<String, List<Mount>> target = host.startsWith("*.") ? wildcard : exact;
                List<Mount> list = target.computeIfAbsent(host, h -> new ArrayList<>());
                if (!list.contains(mount)) {
                    list.add(mount);
                }
            }
        }

        Map<String, List<Mount>> result = new LinkedHashMap<>(exact);
        result.putAll(wildcard);
        if (!any.isEmpty()) {
            result.put(ANY_HOST, any);
        }
        return result;
    }

    /**
     * Rejects two adapters on the same path of a host, and a path nested inside another one:
     * an explicit error is easier to understand than routing by prefix length. The root mount
     * may hold other mounts. Two adapters cannot declare the same well-known path either.
     */
    static void validateHostMounts(ListenerKey key, String host, List<Mount> mounts) {
        String where = (ANY_HOST.equals(host) ? "any host" : "host '" + host + "'")
                + " of listener " + key;
        Map<String, Mount> wellKnown = new LinkedHashMap<>();
        for (int i = 0; i < mounts.size(); i++) {
            Mount a = mounts.get(i);
            for (int j = i + 1; j < mounts.size(); j++) {
                Mount b = mounts.get(j);
                if (a.path().equals(b.path())) {
                    throw new IllegalArgumentException(describe(a) + " and " + describe(b)
                            + " are both mounted at path '" + displayPath(a.path()) + "' on "
                            + where + ". Give each adapter a distinct 'path' or 'hosts'.");
                }
                if (isNested(a.path(), b.path()) || isNested(b.path(), a.path())) {
                    throw new IllegalArgumentException(describe(a) + " at path '"
                            + displayPath(a.path()) + "' and " + describe(b) + " at path '"
                            + displayPath(b.path()) + "' overlap on " + where
                            + ". One path must not be a prefix of the other.");
                }
            }
            for (String path : a.adapter().getWellKnownPaths()) {
                Mount previous = wellKnown.putIfAbsent(path, a);
                if (previous != null) {
                    throw new IllegalArgumentException(describe(previous) + " and "
                            + describe(a) + " both serve '" + path + "' on " + where + ".");
                }
            }
        }
    }

    /** @return {@code true} when {@code inner} is a segment-wise extension of {@code outer} */
    static boolean isNested(String outer, String inner) {
        return !outer.isEmpty() && inner.startsWith(outer + "/");
    }

    static Router buildPrefixRouter(Context context, List<Mount> mounts) {
        Router router = new Router(context);
        router.setRoutingMode(Router.MODE_BEST_MATCH);
        for (Mount mount : mounts) {
            Restlet handler = mount.adapter().getHandler();
            for (String path : mount.adapter().getWellKnownPaths()) {
                // Exact match on the full path; it scores 1 and beats any prefix.
                router.attach(path, handler, Template.MODE_EQUALS);
            }
            router.getRoutes().add(new SegmentRoute(router, mount.path(), handler));
        }
        return router;
    }

    /**
     * @return the adapter's mount prefix, {@code ""} for the root
     * @throws IllegalArgumentException if the path is malformed
     */
    static String normalizePath(ServerAdapter adapter) {
        String path = adapter.getSpec().getPath();
        if (path == null) {
            return "";
        }
        if (!PATH_PATTERN.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid 'path' '" + path + "' on "
                    + describe(adapter.getSpec()) + ". Use '/' or full path segments such as"
                    + " '/api', without a trailing slash or template variables.");
        }
        return "/".equals(path) ? "" : path;
    }

    /**
     * Resolves Mustache entries against the capability bindings, then checks every host name.
     * A value that resolves to an empty or invalid name fails instead of falling back to any
     * host. The resolved value is not echoed, in case a binding holds a secret by mistake.
     *
     * @return the lower-cased host names, empty for any host
     */
    static List<String> resolveHosts(ServerAdapter adapter, Map<String, Object> bindings) {
        List<String> hosts = adapter.getSpec().getHosts();
        if (hosts == null || hosts.isEmpty()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        for (String entry : hosts) {
            if (entry == null) {
                throw new IllegalArgumentException(
                        "Empty 'hosts' entry on " + describe(adapter.getSpec()) + ".");
            }
            boolean template = entry.contains("{{");
            String host = template
                    ? Resolver.resolveMustacheTemplate(entry, bindings)
                    : entry;
            host = host != null ? host.trim().toLowerCase(Locale.ROOT) : "";
            if (!HOST_PATTERN.matcher(host).matches()) {
                if (template) {
                    throw new IllegalArgumentException("'hosts' entry '" + entry + "' on "
                            + describe(adapter.getSpec()) + " resolved to an empty or invalid"
                            + " host name. Check the binding it references.");
                }
                throw new IllegalArgumentException("Invalid 'hosts' entry '" + entry + "' on "
                        + describe(adapter.getSpec()) + ". Use a host name without a port,"
                        + " optionally with one leading '*.' label.");
            }
            if (!result.contains(host)) {
                result.add(host);
            }
        }
        return List.copyOf(result);
    }

    /**
     * @param host an exact host name or {@code *.domain}
     * @return the case-insensitive regex Restlet matches against the request host
     */
    static String toHostRegex(String host) {
        if (host.startsWith("*.")) {
            return "[^.]+" + Pattern.quote(host.substring(1));
        }
        return Pattern.quote(host);
    }

    private static String describe(Map<String, List<Mount>> byHost) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, List<Mount>> entry : byHost.entrySet()) {
            for (Mount mount : entry.getValue()) {
                if (!result.isEmpty()) {
                    result.append(", ");
                }
                result.append(ANY_HOST.equals(entry.getKey()) ? "*" : entry.getKey())
                        .append(displayPath(mount.path())).append(" -> ")
                        .append(describe(mount));
            }
        }
        return result.toString();
    }

    private static String describe(Mount mount) {
        return describe(mount.adapter().getSpec());
    }

    static String describe(ServerSpec spec) {
        return spec.getNamespace() != null
                ? spec.getType() + " adapter '" + spec.getNamespace() + "'"
                : spec.getType() + " adapter";
    }

    private static String displayPath(String path) {
        return path.isEmpty() ? "/" : path;
    }

    /** Starts every shared listener. Dedicated listeners are started by their adapter. */
    public void start() throws Exception {
        for (SharedListener listener : listeners) {
            listener.component().start();
        }
    }

    /** Stops every shared listener. */
    public void stop() throws Exception {
        for (SharedListener listener : listeners) {
            listener.component().stop();
        }
    }

    /** @return the shared listeners, empty when every adapter has a dedicated one */
    public List<SharedListener> getSharedListeners() {
        return listeners;
    }

}
