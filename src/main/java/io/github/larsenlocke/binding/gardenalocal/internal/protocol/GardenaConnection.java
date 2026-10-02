/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package io.github.larsenlocke.binding.gardenalocal.internal.protocol;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.WebSocketPingPongListener;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The WebSocket connection to the gateway's {@code websocketd}. Owns reconnecting, the keepalive and matching
 * replies to the requests they answer; everything else that arrives is handed to the {@link Listener}.
 *
 * <p>
 * Every connection attempt gets a {@link Socket} object of its own, and only the current one's callbacks count.
 * A late callback from a socket that has already been given up on - a close or error racing a reconnect - is
 * therefore ignored instead of tearing down its successor.
 *
 * @author Carsten Mogge - Initial contribution
 */
@NonNullByDefault
// Sockets are compared by identity on purpose: the question is always "is this still the current attempt?"
@SuppressWarnings("PMD.CompareObjectsWithEquals")
public class GardenaConnection {

    /** Receives what happens on the connection. Called from the WebSocket's own threads. */
    public interface Listener {
        /** The WebSocket is open; nothing has been exchanged yet. */
        void onConnected();

        /** The connection was lost or could not be established; a reconnect is already scheduled. */
        void onDisconnected(String reason);

        /** The gateway rejected the password. Nothing is retried until the connection is restarted. */
        void onAuthenticationFailed();

        /** Events, and replies nobody is waiting for any more, from one frame. */
        void onMessages(List<GardenaMessage> messages);
    }

    private static final long CONNECT_TIMEOUT_SECONDS = 15;
    private static final long KEEPALIVE_INTERVAL_SECONDS = 30;
    /** Three missed keepalives: the gateway answers every ping, so silence this long means the link is gone. */
    private static final long STALE_AFTER_NANOS = TimeUnit.SECONDS.toNanos(3 * KEEPALIVE_INTERVAL_SECONDS);
    /** Delays between reconnect attempts, the last one repeating. */
    private static final long[] RECONNECT_DELAYS_SECONDS = { 5, 10, 20, 40, 60 };
    /**
     * The device-list reply carries every device's full data tree in one frame, well beyond Jetty's 64 KiB
     * default once a few devices are paired.
     */
    private static final int MAX_MESSAGE_SIZE = 8 * 1024 * 1024;

    private final Logger logger = LoggerFactory.getLogger(GardenaConnection.class);

    private final WebSocketClient client;
    private final URI uri;
    /** The complete Authorization header value. Contains the password - never log it. */
    private final String authorization;
    private final Listener listener;
    private final ScheduledExecutorService scheduler;

    private final Object lock = new Object();
    // All guarded by lock.
    private boolean running;
    private @Nullable Socket currentSocket;
    private @Nullable Session session;
    private @Nullable ScheduledFuture<?> connectJob;
    private @Nullable ScheduledFuture<?> keepaliveJob;
    private int failedAttempts;

    /** Serializes writes: Jetty's blocking send refuses a second message while one is still going out. */
    private final Object sendLock = new Object();
    private final Map<String, PendingFrame> pendingReplies = new ConcurrentHashMap<>();
    private volatile long lastReceivedNanos;

    public GardenaConnection(WebSocketClient client, URI uri, String authorization, Listener listener,
            ScheduledExecutorService scheduler) {
        this.client = client;
        this.uri = uri;
        this.authorization = authorization;
        this.listener = listener;
        this.scheduler = scheduler;
        client.getPolicy().setMaxTextMessageSize(MAX_MESSAGE_SIZE);
        client.getPolicy().setMaxBinaryMessageSize(MAX_MESSAGE_SIZE);
    }

    public void start() {
        synchronized (lock) {
            running = true;
            failedAttempts = 0;
            connectJob = scheduler.schedule(this::connect, 0, TimeUnit.SECONDS);
        }
    }

    /** Closes the connection for good and stops the WebSocket client, which this connection owns. */
    public void stop() {
        synchronized (lock) {
            running = false;
            cancel(connectJob);
            connectJob = null;
            cancel(keepaliveJob);
            keepaliveJob = null;
            currentSocket = null;
            closeQuietly(session);
            session = null;
        }
        failPendingReplies("connection closed");
        try {
            client.stop();
        } catch (Exception e) {
            logger.debug("Failed to stop the WebSocket client: {}", e.getMessage());
        }
    }

    /**
     * Called once the gateway has actually answered on this connection. Only then does the reconnect back-off
     * start over: a gateway that accepts the WebSocket but never answers would otherwise be retried at the
     * shortest interval forever.
     */
    public void markHealthy() {
        synchronized (lock) {
            failedAttempts = 0;
        }
    }

    /** Drops the current connection and reconnects, e.g. because the gateway stopped answering requests. */
    public void reconnect(String reason) {
        Socket socket;
        synchronized (lock) {
            socket = currentSocket;
        }
        if (socket != null) {
            connectionLost(socket, reason);
        }
    }

    public boolean isConnected() {
        synchronized (lock) {
            Session current = session;
            return current != null && current.isOpen();
        }
    }

    /**
     * Sends a frame and completes with the replies to all of its requests, in request order. Completes
     * exceptionally if not connected, on a send error, when the connection drops first, or after
     * {@code timeout}. A reply with {@code success=false} still completes normally - check
     * {@link GardenaMessage#isSuccess()}.
     */
    public CompletableFuture<List<GardenaMessage>> send(GardenaRequests.Frame frame, Duration timeout) {
        Session current;
        synchronized (lock) {
            current = session;
        }
        if (current == null || !current.isOpen()) {
            return CompletableFuture.failedFuture(new IOException("not connected to the gateway"));
        }

        PendingFrame pending = new PendingFrame(frame.requestIds());
        for (String requestId : frame.requestIds()) {
            pendingReplies.put(requestId, pending);
        }
        pending.future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete((replies, error) -> {
            for (String requestId : frame.requestIds()) {
                pendingReplies.remove(requestId);
            }
        });

        try {
            synchronized (sendLock) {
                current.getRemote().sendString(frame.json());
            }
            logger.trace("Sent to gateway: {}", frame.json());
        } catch (IOException | RuntimeException e) {
            pending.future.completeExceptionally(e);
        }
        return pending.future;
    }

    private void connect() {
        Socket socket = new Socket();
        synchronized (lock) {
            if (!running) {
                return;
            }
            currentSocket = socket;
        }

        Future<Session> connecting = null;
        try {
            if (!client.isStarted()) {
                client.start();
            }
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            request.setHeader("Authorization", authorization);
            logger.debug("Connecting to GARDENA smart Gateway at {}", uri);
            connecting = client.connect(socket, uri, request);
            connecting.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (isUnauthorized(e)) {
                authenticationFailed(socket);
            } else {
                connectionLost(socket, describe(e.getCause() != null ? e.getCause() : e));
            }
        } catch (TimeoutException e) {
            connecting.cancel(true);
            connectionLost(socket, "no answer within " + CONNECT_TIMEOUT_SECONDS + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // client.start() and client.connect() declare plain Exception/IOException.
            connectionLost(socket, describe(e));
        }
    }

    private static boolean isUnauthorized(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof UpgradeException upgrade && upgrade.getResponseStatusCode() == 401) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private void opened(Socket socket, Session newSession) {
        synchronized (lock) {
            if (socket != currentSocket || !running) {
                closeQuietly(newSession);
                return;
            }
            session = newSession;
            lastReceivedNanos = System.nanoTime();
            cancel(keepaliveJob);
            keepaliveJob = scheduler.scheduleWithFixedDelay(() -> keepalive(socket), KEEPALIVE_INTERVAL_SECONDS,
                    KEEPALIVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
        logger.debug("Connected to GARDENA smart Gateway at {}", uri);
        listener.onConnected();
    }

    private void keepalive(Socket socket) {
        Session current;
        synchronized (lock) {
            if (socket != currentSocket) {
                return;
            }
            current = session;
        }
        if (current == null) {
            return;
        }
        if (System.nanoTime() - lastReceivedNanos > STALE_AFTER_NANOS) {
            connectionLost(socket,
                    "no data from the gateway for " + TimeUnit.NANOSECONDS.toSeconds(STALE_AFTER_NANOS) + " s");
            return;
        }
        try {
            synchronized (sendLock) {
                current.getRemote().sendPing(ByteBuffer.allocate(0));
            }
        } catch (IOException | RuntimeException e) {
            connectionLost(socket, "keepalive failed: " + describe(e));
        }
    }

    private void connectionLost(Socket socket, String reason) {
        long delay;
        synchronized (lock) {
            if (socket != currentSocket) {
                return;
            }
            currentSocket = null;
            cancel(keepaliveJob);
            keepaliveJob = null;
            closeQuietly(session);
            session = null;
            if (!running) {
                return;
            }
            delay = RECONNECT_DELAYS_SECONDS[Math.min(failedAttempts, RECONNECT_DELAYS_SECONDS.length - 1)];
            failedAttempts++;
            connectJob = scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
        }
        logger.debug("Connection to GARDENA smart Gateway lost ({}), reconnecting in {} s", reason, delay);
        failPendingReplies(reason);
        listener.onDisconnected(reason);
    }

    private void authenticationFailed(Socket socket) {
        synchronized (lock) {
            if (socket != currentSocket) {
                return;
            }
            currentSocket = null;
            // Retrying the same rejected password achieves nothing but a stream of failed logins on the gateway.
            running = false;
        }
        listener.onAuthenticationFailed();
    }

    private void received(Socket socket, String text) {
        synchronized (lock) {
            if (socket != currentSocket) {
                return;
            }
        }
        lastReceivedNanos = System.nanoTime();
        logger.trace("Received from gateway: {}", text);

        List<GardenaMessage> messages;
        try {
            messages = GardenaMessage.parseFrame(text);
        } catch (IllegalArgumentException e) {
            logger.debug("Ignoring a frame that is not a message list: {}", e.getMessage());
            return;
        }

        List<GardenaMessage> unmatched = new ArrayList<>();
        for (GardenaMessage message : messages) {
            String requestId = message.getRequestId();
            PendingFrame pending = requestId == null ? null : pendingReplies.remove(requestId);
            if (pending != null) {
                pending.add(message);
            } else {
                unmatched.add(message);
            }
        }
        if (!unmatched.isEmpty()) {
            listener.onMessages(unmatched);
        }
    }

    private void failPendingReplies(String reason) {
        IOException error = new IOException("connection lost before the gateway replied: " + reason);
        for (PendingFrame pending : List.copyOf(pendingReplies.values())) {
            pending.future.completeExceptionally(error);
        }
        pendingReplies.clear();
    }

    private static void cancel(@Nullable ScheduledFuture<?> job) {
        if (job != null) {
            job.cancel(false);
        }
    }

    private void closeQuietly(@Nullable Session toClose) {
        if (toClose != null) {
            try {
                toClose.close();
            } catch (RuntimeException e) {
                logger.debug("Failed to close the WebSocket session: {}", e.getMessage());
            }
        }
    }

    /** Collects the replies to the requests of one frame. */
    private static class PendingFrame {
        private final List<String> requestIds;
        private final Map<String, GardenaMessage> replies = new ConcurrentHashMap<>();
        private final CompletableFuture<List<GardenaMessage>> future = new CompletableFuture<>();

        PendingFrame(List<String> requestIds) {
            this.requestIds = requestIds;
        }

        void add(GardenaMessage reply) {
            String requestId = reply.getRequestId();
            if (requestId == null) {
                return;
            }
            replies.put(requestId, reply);
            if (replies.size() == requestIds.size()) {
                List<GardenaMessage> ordered = new ArrayList<>();
                for (String id : requestIds) {
                    GardenaMessage message = replies.get(id);
                    if (message != null) {
                        ordered.add(message);
                    }
                }
                future.complete(ordered);
            }
        }
    }

    /** The Jetty endpoint for one connection attempt. */
    private class Socket implements WebSocketListener, WebSocketPingPongListener {

        @Override
        public void onWebSocketConnect(@Nullable Session newSession) {
            if (newSession != null) {
                opened(this, newSession);
            }
        }

        @Override
        public void onWebSocketText(@Nullable String message) {
            if (message != null) {
                process(message);
            }
        }

        @Override
        public void onWebSocketBinary(byte @Nullable [] payload, int offset, int length) {
            if (payload != null) {
                process(new String(payload, offset, length, StandardCharsets.UTF_8));
            }
        }

        private void process(String text) {
            // Nothing that goes wrong with one frame may escape into Jetty and end this connection's message
            // delivery - the connection would then sit there looking healthy while nothing is processed.
            try {
                received(this, text);
            } catch (RuntimeException e) {
                logger.warn("Failed to process a message from the GARDENA smart Gateway: {}", e.getMessage());
                logger.debug("Message processing failure", e);
            }
        }

        @Override
        public void onWebSocketPing(@Nullable ByteBuffer payload) {
            lastReceivedNanos = System.nanoTime();
        }

        @Override
        public void onWebSocketPong(@Nullable ByteBuffer payload) {
            lastReceivedNanos = System.nanoTime();
        }

        @Override
        public void onWebSocketClose(int statusCode, @Nullable String reason) {
            connectionLost(this, "closed by the gateway (" + statusCode
                    + (reason == null || reason.isBlank() ? "" : ", " + reason) + ")");
        }

        @Override
        public void onWebSocketError(@Nullable Throwable cause) {
            if (cause != null && isUnauthorized(cause)) {
                authenticationFailed(this);
            } else {
                connectionLost(this, cause == null ? "unknown error" : describe(cause));
            }
        }
    }
}
