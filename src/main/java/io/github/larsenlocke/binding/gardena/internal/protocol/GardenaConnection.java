/*
 * Copyright (c) 2026 Larsen-Locke and contributors
 *
 * See the NOTICE file distributed with this work for additional information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package io.github.larsenlocke.binding.gardena.internal.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The WebSocket connection to the gateway's {@code websocketd}. Owns reconnecting, the keepalive and matching
 * replies to the requests they answer; everything else that arrives is handed to the {@link Listener}.
 *
 * <p>
 * Uses the JDK's own WebSocket client ({@code java.net.http}) rather than the Jetty client openHAB provides: the
 * binding then has no Jetty imports at all, so the same bundle runs on openHAB 5.2 (Jetty 9) and 5.3 (Jetty 12).
 *
 * <p>
 * Every connection attempt gets a {@link Endpoint} object of its own, and only the current one's callbacks count.
 * A late callback from an attempt that has already been given up on - a close or error racing a reconnect - is
 * therefore ignored instead of tearing down its successor.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
// Endpoints are compared by identity on purpose: the question is always "is this still the current attempt?"
@SuppressWarnings("PMD.CompareObjectsWithEquals")
public class GardenaConnection {

    /** Receives what happens on the connection. Called from the WebSocket client's own threads. */
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
    /** How long a polite close may take before the connection is simply dropped. */
    private static final long CLOSE_TIMEOUT_SECONDS = 2;
    /**
     * The device-list reply carries every device's full data tree in one frame. The JDK client has no size limit
     * of its own; this only stops a broken peer from filling the memory with one endless message.
     */
    private static final int MAX_MESSAGE_SIZE = 8 * 1024 * 1024;

    private final Logger logger = LoggerFactory.getLogger(GardenaConnection.class);

    private final HttpClient client;
    private final URI uri;
    /** The complete Authorization header value. Contains the password - never log it. */
    private final String authorization;
    private final Listener listener;
    private final ScheduledExecutorService scheduler;

    private final Object lock = new Object();
    // All guarded by lock.
    private boolean running;
    private @Nullable Endpoint currentEndpoint;
    private @Nullable WebSocket webSocket;
    private @Nullable ScheduledFuture<?> connectJob;
    private @Nullable ScheduledFuture<?> keepaliveJob;
    private int failedAttempts;

    private final Map<String, PendingFrame> pendingReplies = new ConcurrentHashMap<>();
    private volatile long lastReceivedNanos;

    public GardenaConnection(URI uri, String authorization, Listener listener, ScheduledExecutorService scheduler) {
        this.uri = uri;
        this.authorization = authorization;
        this.listener = listener;
        this.scheduler = scheduler;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .sslContext(trustingSslContext()).build();
    }

    public void start() {
        synchronized (lock) {
            running = true;
            failedAttempts = 0;
            connectJob = scheduler.schedule(this::connect, 0, TimeUnit.SECONDS);
        }
    }

    /** Closes the connection for good and shuts down the HTTP client, which this connection owns. */
    public void stop() {
        synchronized (lock) {
            running = false;
            cancel(connectJob);
            connectJob = null;
            cancel(keepaliveJob);
            keepaliveJob = null;
            currentEndpoint = null;
            closeQuietly(webSocket);
            webSocket = null;
        }
        failPendingReplies("connection closed");
        // Orderly: lets the close above go out; the client's threads end once nothing is left to do.
        client.shutdown();
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
        Endpoint endpoint;
        synchronized (lock) {
            endpoint = currentEndpoint;
        }
        if (endpoint != null) {
            connectionLost(endpoint, reason);
        }
    }

    public boolean isConnected() {
        synchronized (lock) {
            return isOpen(webSocket);
        }
    }

    /**
     * Sends a frame and completes with the replies to all of its requests, in request order. Completes
     * exceptionally if not connected, on a send error, when the connection drops first, or after
     * {@code timeout}. A reply with {@code success=false} still completes normally - check
     * {@link GardenaMessage#isSuccess()}.
     */
    public CompletableFuture<List<GardenaMessage>> send(GardenaRequests.Frame frame, Duration timeout) {
        Endpoint endpoint;
        WebSocket current;
        synchronized (lock) {
            endpoint = currentEndpoint;
            current = webSocket;
        }
        if (endpoint == null || current == null || !isOpen(current)) {
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

        endpoint.enqueue(current, ws -> ws.sendText(frame.json(), true)).whenComplete((ws, error) -> {
            if (error != null) {
                pending.future.completeExceptionally(error);
            } else {
                logger.trace("Sent to gateway: {}", frame.json());
            }
        });
        return pending.future;
    }

    private void connect() {
        Endpoint endpoint = new Endpoint();
        synchronized (lock) {
            if (!running) {
                return;
            }
            currentEndpoint = endpoint;
        }

        CompletableFuture<WebSocket> connecting = null;
        try {
            logger.debug("Connecting to GARDENA smart Gateway at {}", uri);
            connecting = client.newWebSocketBuilder().header("Authorization", authorization)
                    .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).buildAsync(uri, endpoint);
            // The TLS and WebSocket handshakes come after the TCP connect the timeout above covers.
            connecting.get(2 * CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (isUnauthorized(cause)) {
                authenticationFailed(endpoint);
            } else {
                connectionLost(endpoint, describe(cause));
            }
        } catch (TimeoutException e) {
            connecting.cancel(true);
            connectionLost(endpoint, "no answer within " + 2 * CONNECT_TIMEOUT_SECONDS + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // e.g. a header or URI the builder refuses
            connectionLost(endpoint, describe(e));
        }
    }

    private static boolean isUnauthorized(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebSocketHandshakeException handshake && handshake.getResponse().statusCode() == 401) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private void opened(Endpoint endpoint, WebSocket newWebSocket) {
        synchronized (lock) {
            if (endpoint != currentEndpoint || !running) {
                closeQuietly(newWebSocket);
                return;
            }
            webSocket = newWebSocket;
            lastReceivedNanos = System.nanoTime();
            cancel(keepaliveJob);
            keepaliveJob = scheduler.scheduleWithFixedDelay(() -> keepalive(endpoint), KEEPALIVE_INTERVAL_SECONDS,
                    KEEPALIVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
        logger.debug("Connected to GARDENA smart Gateway at {}", uri);
        listener.onConnected();
    }

    private void keepalive(Endpoint endpoint) {
        WebSocket current;
        synchronized (lock) {
            if (endpoint != currentEndpoint) {
                return;
            }
            current = webSocket;
        }
        if (current == null) {
            return;
        }
        if (System.nanoTime() - lastReceivedNanos > STALE_AFTER_NANOS) {
            connectionLost(endpoint,
                    "no data from the gateway for " + TimeUnit.NANOSECONDS.toSeconds(STALE_AFTER_NANOS) + " s");
            return;
        }
        endpoint.enqueue(current, ws -> ws.sendPing(ByteBuffer.allocate(0))).whenComplete((ws, error) -> {
            if (error != null) {
                connectionLost(endpoint, "keepalive failed: " + describe(error));
            }
        });
    }

    private void connectionLost(Endpoint endpoint, String reason) {
        long delay;
        synchronized (lock) {
            if (endpoint != currentEndpoint) {
                return;
            }
            currentEndpoint = null;
            cancel(keepaliveJob);
            keepaliveJob = null;
            closeQuietly(webSocket);
            webSocket = null;
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

    private void authenticationFailed(Endpoint endpoint) {
        synchronized (lock) {
            if (endpoint != currentEndpoint) {
                return;
            }
            currentEndpoint = null;
            // Retrying the same rejected password achieves nothing but a stream of failed logins on the gateway.
            running = false;
        }
        listener.onAuthenticationFailed();
    }

    private void received(Endpoint endpoint, String text) {
        synchronized (lock) {
            if (endpoint != currentEndpoint) {
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

    private static boolean isOpen(@Nullable WebSocket candidate) {
        return candidate != null && !candidate.isOutputClosed() && !candidate.isInputClosed();
    }

    /** Says goodbye if possible and drops the connection after {@link #CLOSE_TIMEOUT_SECONDS} at the latest. */
    private void closeQuietly(@Nullable WebSocket toClose) {
        if (toClose == null) {
            return;
        }
        if (toClose.isOutputClosed()) {
            toClose.abort();
            return;
        }
        try {
            toClose.sendClose(WebSocket.NORMAL_CLOSURE, "").orTimeout(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .whenComplete((ws, error) -> toClose.abort());
        } catch (RuntimeException e) {
            logger.debug("Failed to close the WebSocket: {}", e.getMessage());
            toClose.abort();
        }
    }

    /**
     * The gateway serves a self-signed certificate issued to its own name, which no trust store knows and which
     * does not match an IP address. The password authenticates this side; nothing authenticates the gateway, the
     * same trade-off GARDENA's own Home Assistant integration makes.
     *
     * <p>
     * The trust manager must be an {@link X509ExtendedTrustManager}: for a plain {@code X509TrustManager} the JDK
     * adds its own host name check, which the gateway's certificate fails.
     */
    private static SSLContext trustingSslContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] { new TrustAnyCertificate() }, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS is not available: " + e.getMessage(), e);
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

    /** The WebSocket listener for one connection attempt. */
    private class Endpoint implements WebSocket.Listener {

        /**
         * The JDK client allows one outgoing message at a time; a second send while the first is still going out
         * fails. Every send waits for the one before it, whatever its outcome.
         */
        private CompletableFuture<?> lastSend = CompletableFuture.completedFuture(null);
        private final StringBuilder text = new StringBuilder();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();

        synchronized CompletableFuture<WebSocket> enqueue(WebSocket target,
                Function<WebSocket, CompletableFuture<WebSocket>> sending) {
            CompletableFuture<WebSocket> next = lastSend.handle((ignored, error) -> target).thenCompose(sending::apply);
            lastSend = next;
            return next;
        }

        @Override
        public void onOpen(@Nullable WebSocket newWebSocket) {
            if (newWebSocket != null) {
                opened(this, newWebSocket);
                newWebSocket.request(1);
            }
        }

        @Override
        public @Nullable CompletionStage<?> onText(@Nullable WebSocket source, @Nullable CharSequence data,
                boolean last) {
            if (data != null) {
                text.append(data);
            }
            if (text.length() > MAX_MESSAGE_SIZE) {
                text.setLength(0);
                connectionLost(this, "message from the gateway larger than " + MAX_MESSAGE_SIZE + " characters");
                return null;
            }
            if (last) {
                String message = text.toString();
                text.setLength(0);
                process(message);
            }
            requestNext(source);
            return null;
        }

        @Override
        public @Nullable CompletionStage<?> onBinary(@Nullable WebSocket source, @Nullable ByteBuffer data,
                boolean last) {
            if (data != null) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                binary.writeBytes(bytes);
            }
            if (binary.size() > MAX_MESSAGE_SIZE) {
                binary.reset();
                connectionLost(this, "message from the gateway larger than " + MAX_MESSAGE_SIZE + " bytes");
                return null;
            }
            if (last) {
                String message = binary.toString(StandardCharsets.UTF_8);
                binary.reset();
                process(message);
            }
            requestNext(source);
            return null;
        }

        private void process(String message) {
            // Nothing that goes wrong with one frame may escape into the WebSocket client and end this
            // connection's message delivery - the connection would then sit there looking healthy while nothing
            // is processed.
            try {
                received(this, message);
            } catch (RuntimeException e) {
                logger.warn("Failed to process a message from the GARDENA smart Gateway: {}", e.getMessage());
                logger.debug("Message processing failure", e);
            }
        }

        @Override
        public @Nullable CompletionStage<?> onPing(@Nullable WebSocket source, @Nullable ByteBuffer message) {
            // The JDK client answers with a pong by itself.
            lastReceivedNanos = System.nanoTime();
            requestNext(source);
            return null;
        }

        @Override
        public @Nullable CompletionStage<?> onPong(@Nullable WebSocket source, @Nullable ByteBuffer message) {
            lastReceivedNanos = System.nanoTime();
            requestNext(source);
            return null;
        }

        @Override
        public @Nullable CompletionStage<?> onClose(@Nullable WebSocket source, int statusCode,
                @Nullable String reason) {
            connectionLost(this, "closed by the gateway (" + statusCode
                    + (reason == null || reason.isBlank() ? "" : ", " + reason) + ")");
            return null;
        }

        @Override
        public void onError(@Nullable WebSocket source, @Nullable Throwable error) {
            if (error != null && isUnauthorized(error)) {
                authenticationFailed(this);
            } else {
                connectionLost(this, error == null ? "unknown error" : describe(error));
            }
        }

        private void requestNext(@Nullable WebSocket source) {
            if (source != null) {
                source.request(1);
            }
        }
    }

    /** Accepts whatever certificate the gateway presents - see {@link #trustingSslContext()}. */
    private static class TrustAnyCertificate extends X509ExtendedTrustManager {

        @Override
        public void checkClientTrusted(X509Certificate @Nullable [] chain, @Nullable String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate @Nullable [] chain, @Nullable String authType) {
        }

        @Override
        public void checkClientTrusted(X509Certificate @Nullable [] chain, @Nullable String authType,
                @Nullable Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate @Nullable [] chain, @Nullable String authType,
                @Nullable Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate @Nullable [] chain, @Nullable String authType,
                @Nullable SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate @Nullable [] chain, @Nullable String authType,
                @Nullable SSLEngine engine) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
