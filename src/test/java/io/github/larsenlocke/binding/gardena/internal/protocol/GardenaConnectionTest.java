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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketHandler;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Runs {@link GardenaConnection} against a stand-in gateway: a local Jetty WebSocket server that checks the
 * Authorization header like websocketd does, answers the device-list request from {@code device-list.json} and
 * acknowledges writes with an event, the way the real gateway reports a valve opening.
 *
 * <p>
 * Plain {@code ws://} rather than {@code wss://}: TLS is Jetty's business, the protocol handling is this
 * binding's.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
class GardenaConnectionTest {

    private static final String PASSWORD = "1234abcd";
    private static final String VALVE_DEVICE = "3034F8EE90126D400001737D";

    private @Nullable Server server;
    private @Nullable WebSocketClient client;
    private @Nullable GardenaConnection connection;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AtomicInteger connectionsAccepted = new AtomicInteger();
    private volatile boolean closeRightAway;
    private int port;

    @BeforeEach
    void startGateway() throws Exception {
        Server newServer = new Server(0);
        newServer.setHandler(new WebSocketHandler() {
            @Override
            public void configure(@Nullable WebSocketServletFactory factory) {
                Objects.requireNonNull(factory).setCreator((request, response) -> {
                    String expected = "Basic "
                            + Base64.getEncoder().encodeToString(("_:" + PASSWORD).getBytes(StandardCharsets.UTF_8));
                    if (!expected.equals(request.getHeader("Authorization"))) {
                        try {
                            response.sendError(401, "Unauthorized");
                        } catch (IOException e) {
                            // the client sees the failed upgrade either way
                        }
                        return null;
                    }
                    connectionsAccepted.incrementAndGet();
                    return new FakeGateway();
                });
            }
        });
        newServer.start();
        port = ((ServerConnector) newServer.getConnectors()[0]).getLocalPort();
        server = newServer;
    }

    @AfterEach
    void stopAll() throws Exception {
        GardenaConnection currentConnection = connection;
        if (currentConnection != null) {
            currentConnection.stop();
        }
        Server currentServer = server;
        if (currentServer != null) {
            currentServer.stop();
        }
        scheduler.shutdownNow();
    }

    private RecordingListener connect(String password) {
        RecordingListener listener = new RecordingListener();
        WebSocketClient newClient = new WebSocketClient();
        client = newClient;
        String authorization = "Basic "
                + Base64.getEncoder().encodeToString(("_:" + password).getBytes(StandardCharsets.UTF_8));
        GardenaConnection newConnection = new GardenaConnection(newClient, URI.create("ws://127.0.0.1:" + port + "/"),
                authorization, listener, scheduler);
        connection = newConnection;
        newConnection.start();
        return listener;
    }

    @Test
    void readsDeviceListAndSendsCommands() throws Exception {
        RecordingListener listener = connect(PASSWORD);
        assertTrue(listener.connected.await(10, TimeUnit.SECONDS), "connected");
        GardenaConnection currentConnection = Objects.requireNonNull(connection);

        List<GardenaMessage> replies = currentConnection.send(GardenaRequests.discovery(), Duration.ofSeconds(5))
                .get(10, TimeUnit.SECONDS);
        assertEquals(2, replies.size());
        assertTrue(replies.get(0).isSuccess());
        assertTrue(replies.get(0).getPayload().has(VALVE_DEVICE));
        assertTrue(replies.get(1).getPayload().has("3034F8319C02BF00000033FF"));
        assertTrue(listener.messages.isEmpty(), "matched replies are not passed on as unsolicited messages");

        List<GardenaMessage> commandReplies = currentConnection
                .send(GardenaRequests.openValve(VALVE_DEVICE, GardenaModel.IRRIGATION_CONTROL_GEN1, 1, 600),
                        Duration.ofSeconds(5))
                .get(10, TimeUnit.SECONDS);
        assertTrue(commandReplies.get(0).isSuccess());

        assertTrue(listener.messageReceived.await(10, TimeUnit.SECONDS), "event pushed after the write");
        GardenaMessage event = listener.messages.get(0);
        assertEquals(GardenaMessage.Kind.EVENT, event.getKind());
        assertEquals(VALVE_DEVICE, event.getDevice());
        assertEquals("lemonbeat/0", event.getPath());
    }

    @Test
    void rejectedPasswordIsNotRetried() throws Exception {
        RecordingListener listener = connect("wrong");
        assertTrue(listener.authenticationFailed.await(10, TimeUnit.SECONDS), "authentication failure reported");
        // The first retry would come after 5 s; nothing may follow.
        Thread.sleep(6000);
        assertEquals(0, connectionsAccepted.get());
        assertEquals(1, listener.authenticationFailures.get());
        assertEquals(0, listener.disconnects.get());
    }

    @Test
    void reconnectsAfterTheGatewayClosesTheConnection() throws Exception {
        closeRightAway = true;
        RecordingListener listener = connect(PASSWORD);
        assertTrue(listener.disconnected.await(10, TimeUnit.SECONDS), "disconnect reported");
        closeRightAway = false;
        // Reconnect after the first back-off step of 5 s.
        assertTrue(listener.reconnected.await(15, TimeUnit.SECONDS), "reconnected");
        assertTrue(Objects.requireNonNull(connection).isConnected());
    }

    @Test
    void sendFailsWhenNotConnected() {
        GardenaConnection notStarted = new GardenaConnection(new WebSocketClient(),
                URI.create("ws://127.0.0.1:" + port + "/"), "Basic x", new RecordingListener(), scheduler);
        assertTrue(notStarted.send(GardenaRequests.discovery(), Duration.ofSeconds(1)).isCompletedExceptionally());
    }

    private class FakeGateway extends WebSocketAdapter {

        @Override
        public void onWebSocketConnect(@Nullable Session session) {
            super.onWebSocketConnect(session);
            if (closeRightAway && session != null) {
                session.close(1001, "going away");
            }
        }

        @Override
        public void onWebSocketText(@Nullable String message) {
            if (message == null) {
                return;
            }
            try {
                JsonArray replies = new JsonArray();
                JsonArray events = new JsonArray();
                JsonArray deviceList = JsonParser.parseString(resource("device-list.json")).getAsJsonArray();
                for (JsonElement element : JsonParser.parseString(message).getAsJsonArray()) {
                    JsonObject request = element.getAsJsonObject();
                    JsonObject entity = request.getAsJsonObject("entity");
                    JsonObject reply = new JsonObject();
                    reply.add("entity", entity);
                    reply.add("request_id", request.get("request_id"));
                    reply.addProperty("success", true);
                    if ("read".equals(request.get("op").getAsString())) {
                        String service = entity.get("service").getAsString();
                        for (JsonElement listed : deviceList) {
                            JsonObject listedReply = listed.getAsJsonObject();
                            if (service.equals(listedReply.getAsJsonObject("entity").get("service").getAsString())) {
                                reply.add("payload", listedReply.get("payload"));
                            }
                        }
                    } else if ("write".equals(request.get("op").getAsString())) {
                        String[] path = entity.get("path").getAsString().split("/");
                        JsonObject payload = new JsonObject();
                        payload.add(path[2], request.get("payload"));
                        JsonObject eventEntity = new JsonObject();
                        eventEntity.add("device", entity.get("device"));
                        eventEntity.addProperty("path", path[0] + "/" + path[1]);
                        JsonObject event = new JsonObject();
                        event.add("entity", eventEntity);
                        event.addProperty("op", "update");
                        event.add("payload", payload);
                        events.add(event);
                    }
                    replies.add(reply);
                }
                Session session = Objects.requireNonNull(getSession());
                session.getRemote().sendString(replies.toString());
                if (!events.isEmpty()) {
                    session.getRemote().sendString(events.toString());
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(GardenaConnectionTest.class.getResourceAsStream("/" + name))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static class RecordingListener implements GardenaConnection.Listener {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch reconnected = new CountDownLatch(2);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final CountDownLatch authenticationFailed = new CountDownLatch(1);
        final CountDownLatch messageReceived = new CountDownLatch(1);
        final AtomicInteger disconnects = new AtomicInteger();
        final AtomicInteger authenticationFailures = new AtomicInteger();
        final List<GardenaMessage> messages = new CopyOnWriteArrayList<>();

        @Override
        public void onConnected() {
            connected.countDown();
            reconnected.countDown();
        }

        @Override
        public void onDisconnected(String reason) {
            disconnects.incrementAndGet();
            disconnected.countDown();
        }

        @Override
        public void onAuthenticationFailed() {
            authenticationFailures.incrementAndGet();
            authenticationFailed.countDown();
        }

        @Override
        public void onMessages(List<GardenaMessage> received) {
            messages.addAll(received);
            messageReceived.countDown();
        }
    }
}
