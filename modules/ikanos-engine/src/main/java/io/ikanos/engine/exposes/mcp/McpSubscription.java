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
package io.ikanos.engine.exposes.mcp;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.spec.util.IkanosMappers;

/**
 * One {@code subscriptions/listen} stream (MCP 2026-07-28) carrying {@code notifications/tasks}
 * (the tasks extension, SEP-2663) for the tasks its client named.
 *
 * <p>The subscription agrees to the tasks of its owner, at most {@link #MAX_TASKS}, and to no
 * other notification type. {@link #run} writes, in order: the
 * {@code notifications/subscriptions/acknowledged} naming the agreed tasks, the current state of
 * each of them, so a change made before the subscription opened is never missed, then a
 * notification each time a task's status or questions change, until every agreed task has ended,
 * the subscription is closed, or its token expires. It then answers the
 * {@code subscriptions/listen} request with an empty result, the graceful end the spec asks for.
 * A client that cancels gets no such result.</p>
 *
 * <p>Each notification is read with the subscription's owner, as {@code tasks/get} reads it, so
 * it carries what {@code tasks/get} would return at that moment, and never a task of another
 * owner. Every message carries the subscription ID, the JSON-RPC ID of the request, in
 * {@code _meta}.</p>
 *
 * <p>The subscription only hears of the changes its own process makes: with the {@code memory}
 * and {@code local} modes, which run on one replica, that is every change.</p>
 */
public final class McpSubscription {

    /** The {@code _meta} key naming the subscription of a message. */
    public static final String SUBSCRIPTION_ID = "io.modelcontextprotocol/subscriptionId";

    /** Method of a {@code subscriptions/listen} request. */
    public static final String LISTEN = "subscriptions/listen";

    /** Most tasks one subscription agrees to; the ones past it are left out of the agreement. */
    static final int MAX_TASKS = 100;

    /** Longest wait for a change, so a closed or expired subscription ends soon enough. */
    static final Duration WAKE_INTERVAL = Duration.ofSeconds(5);

    private static final ObjectMapper MAPPER = IkanosMappers.json();
    private static final Set<String> TERMINAL = Set.of("completed", "failed", "cancelled");

    /** A blocking writer of one message on the subscription's stream. */
    @FunctionalInterface
    public interface Sink {

        /** Writes one JSON-RPC message, or fails when the stream is gone. */
        void send(ObjectNode message) throws IOException;
    }

    private final JsonNode id;
    private final String owner;
    private final McpTaskService tasks;
    private final Clock clock;
    private final Set<String> watched = new LinkedHashSet<>();
    private final LinkedBlockingQueue<String> changes = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final Instant createdAt;
    private volatile Instant expiresAt;
    private Runnable unlisten = () -> {
    };
    private volatile Runnable onEnd = () -> {
    };

    /**
     * Opens a subscription and starts hearing of its tasks' changes at once, so none made before
     * {@link #run} is lost.
     *
     * @param id the JSON-RPC ID of the {@code subscriptions/listen} request
     * @param owner the caller, as {@code tasks/get} takes it
     * @param taskIds the task IDs the client asked for
     */
    McpSubscription(JsonNode id, String owner, List<String> taskIds, McpTaskService tasks,
            Clock clock) {
        this.id = id;
        this.owner = owner;
        this.tasks = tasks;
        this.clock = clock;
        this.createdAt = clock.instant();
        for (String taskId : taskIds) {
            if (watched.size() == MAX_TASKS) {
                break;
            }
            // The owner's tasks only: another owner's task is left out as an unknown one is,
            // so the agreement never tells whether it exists.
            if (tasks.entryOf(taskId, owner).isPresent()) {
                watched.add(taskId);
            }
        }
        if (!watched.isEmpty()) {
            Set<String> heard = Set.copyOf(watched);
            this.unlisten = tasks.onChange(taskId -> {
                if (heard.contains(taskId)) {
                    changes.offer(taskId);
                }
            });
        }
    }

    /** The JSON-RPC ID of the request that opened this subscription. */
    public JsonNode id() {
        return id;
    }

    /** The caller this subscription reads tasks for. */
    String owner() {
        return owner;
    }

    /** The tasks this subscription agrees to, before {@link #run}. */
    synchronized Set<String> agreed() {
        return Set.copyOf(watched);
    }

    /**
     * Keeps only the tasks {@code allowed} accepts, before {@link #run}: on HTTP, the tasks whose
     * tool's authorization the caller's token meets, as {@code tasks/get} requires.
     */
    public synchronized void retain(Predicate<String> allowed) {
        watched.removeIf(allowed.negate());
    }

    /** Ends the subscription gracefully at {@code instant}, such as when the caller's token expires. */
    public void expireAt(Instant instant) {
        this.expiresAt = instant;
    }

    /** When the subscription ends on its own, or {@code null} for never. */
    Instant expiresAt() {
        return expiresAt;
    }

    /** Ends the subscription gracefully: {@link #run} answers the request, then returns. */
    public void close() {
        closed.set(true);
        changes.offer("");
    }

    /** Ends the subscription at the client's request: {@link #run} returns without a result. */
    public void cancel() {
        cancelled.set(true);
        close();
    }

    /**
     * Writes the subscription's messages to {@code sink} until it ends. An interrupt ends it
     * abruptly, with no result, as a dropped stream does.
     *
     * @throws IOException when the stream is gone
     */
    public void run(Sink sink) throws IOException {
        if (!started.compareAndSet(false, true)) {
            // Already running, or given up because it never started (giveUpIfNotStartedBy).
            return;
        }
        try {
            Map<String, String> sent = new HashMap<>();
            sink.send(acknowledgement());
            for (String taskId : agreed()) {
                push(taskId, sink, sent);
            }
            while (watching() && !closed.get()) {
                long wait = WAKE_INTERVAL.toMillis();
                if (expiresAt != null) {
                    long left = Duration.between(clock.instant(), expiresAt).toMillis();
                    if (left <= 0) {
                        break;
                    }
                    wait = Math.min(wait, left);
                }
                String taskId;
                try {
                    taskId = changes.poll(wait, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                Set<String> changed = new LinkedHashSet<>();
                if (taskId != null) {
                    changed.add(taskId);
                }
                // A burst of changes to one task sends its latest state once.
                for (String next; (next = changes.poll()) != null;) {
                    changed.add(next);
                }
                for (String task : changed) {
                    if (!task.isEmpty() && isWatched(task)) {
                        push(task, sink, sent);
                    }
                }
            }
            if (!cancelled.get()) {
                sink.send(result());
            }
        } finally {
            unlisten.run();
            onEnd.run();
        }
    }

    /** Runs {@code hook} once {@link #run} returns, however it ends. */
    void whenEnded(Runnable hook) {
        this.onEnd = hook;
    }

    /**
     * Gives up a subscription that never runs, such as one whose request failed after it opened:
     * it stops hearing of changes.
     */
    public void discard() {
        unlisten.run();
        onEnd.run();
    }

    /**
     * Gives up this subscription when {@link #run} has not started by {@code cutoff}, such as
     * when its client left before the stream was written: it never will, and would otherwise
     * stay registered until the adapter stops. A {@link #run} that starts after it returns at
     * once, sending nothing.
     *
     * @return whether it was given up
     */
    boolean giveUpIfNotStartedBy(Instant cutoff) {
        if (createdAt.isBefore(cutoff) && started.compareAndSet(false, true)) {
            discard();
            return true;
        }
        return false;
    }

    /**
     * Sends a task's state if its status or questions changed since the last one sent, and stops
     * watching it once it has ended or can no longer be read by the owner.
     */
    private void push(String taskId, Sink sink, Map<String, String> sent) throws IOException {
        Optional<ObjectNode> task = tasks.notification(taskId, owner);
        if (task.isEmpty()) {
            unwatch(taskId);
            return;
        }
        String status = task.get().path("status").asString("");
        String state = status + task.get().path("inputRequests");
        if (!state.equals(sent.put(taskId, state))) {
            sink.send(notification(task.get()));
        }
        if (TERMINAL.contains(status)) {
            unwatch(taskId);
        }
    }

    private synchronized boolean watching() {
        return !watched.isEmpty();
    }

    private synchronized boolean isWatched(String taskId) {
        return watched.contains(taskId);
    }

    private synchronized void unwatch(String taskId) {
        watched.remove(taskId);
    }

    /** The {@code notifications/subscriptions/acknowledged} naming the agreed tasks. */
    ObjectNode acknowledgement() {
        ObjectNode message = message("notifications/subscriptions/acknowledged");
        ObjectNode params = (ObjectNode) message.get("params");
        ObjectNode notifications = params.putObject("notifications");
        Set<String> agreed = agreed();
        if (!agreed.isEmpty()) {
            agreed.forEach(notifications.putArray("taskIds")::add);
        }
        params.set("_meta", meta());
        return message;
    }

    /** A {@code notifications/tasks} carrying a task's state. */
    ObjectNode notification(ObjectNode task) {
        ObjectNode message = message("notifications/tasks");
        ObjectNode params = (ObjectNode) message.get("params");
        params.setAll(task);
        params.set("_meta", meta());
        return message;
    }

    /** The response to the {@code subscriptions/listen} request that ends it gracefully. */
    ObjectNode result() {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", ProtocolDispatcher.JSONRPC_VERSION);
        message.set("id", id);
        message.putObject("result").set("_meta", meta());
        return message;
    }

    private ObjectNode message(String method) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", ProtocolDispatcher.JSONRPC_VERSION);
        message.put("method", method);
        message.putObject("params");
        return message;
    }

    private ObjectNode meta() {
        ObjectNode meta = MAPPER.createObjectNode();
        meta.set(SUBSCRIPTION_ID, id);
        return meta;
    }
}
