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
package io.github.larsenlocke.binding.gardena.internal.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.State;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.larsenlocke.binding.gardena.internal.GardenaLocalBindingConstants;
import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaDevice;
import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaMessage;
import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaRequests;

/**
 * Runs a device thing handler against stand-ins for openHAB and the gateway: a mocked callback records every
 * state and status the handler publishes, a mocked gateway handler hands out devices from a test fixture and
 * records every frame the handler sends.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
final class HandlerHarness {

    static final ThingUID BRIDGE_UID = new ThingUID(GardenaLocalBindingConstants.THING_TYPE_GATEWAY, "garden");

    final Map<String, GardenaDevice> devices = new HashMap<>();
    final Map<String, State> states = new HashMap<>();
    final List<String> stateUpdates = new ArrayList<>();
    final List<JsonObject> sentRequests = new ArrayList<>();
    @Nullable
    ThingStatusInfo status;

    final ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
    final Bridge bridge = mock(Bridge.class);
    final GardenaGatewayHandler gateway = mock(GardenaGatewayHandler.class);

    /** What the gateway answers to a sent frame; by default a success reply to every request in it. */
    Function<GardenaRequests.Frame, CompletableFuture<List<GardenaMessage>>> replies = HandlerHarness::succeed;

    HandlerHarness(String... fixtures) throws IOException {
        for (String fixture : fixtures) {
            for (GardenaMessage reply : GardenaMessage.parseFrame(resource(fixture))) {
                for (Map.Entry<String, JsonElement> entry : reply.getPayload().entrySet()) {
                    GardenaDevice device = GardenaDevice.fromDiscovery(entry.getKey(),
                            entry.getValue().getAsJsonObject());
                    if (device != null) {
                        devices.put(device.getId(), device);
                    }
                }
            }
        }
        when(callback.getBridge(BRIDGE_UID)).thenReturn(bridge);
        when(bridge.getHandler()).thenReturn(gateway);
        when(bridge.getStatus()).thenReturn(ThingStatus.ONLINE);
        when(gateway.getDevice(anyString())).thenAnswer(call -> devices.get(call.getArgument(0)));
        when(gateway.send(any())).thenAnswer(call -> {
            GardenaRequests.Frame frame = call.getArgument(0);
            for (JsonElement request : JsonParser.parseString(frame.json()).getAsJsonArray()) {
                sentRequests.add(request.getAsJsonObject());
            }
            return replies.apply(frame);
        });
        doAnswer(call -> {
            ChannelUID channel = call.getArgument(0);
            states.put(channel.getId(), call.getArgument(1));
            stateUpdates.add(channel.getId());
            return null;
        }).when(callback).stateUpdated(any(), any());
        doAnswer(call -> {
            status = call.getArgument(1);
            return null;
        }).when(callback).statusUpdated(any(), any());
    }

    static String resource(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(HandlerHarness.class.getResourceAsStream("/" + name))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    GardenaDevice device(String id) {
        return Objects.requireNonNull(devices.get(id));
    }

    /** A thing of {@code thingType} under the test gateway, with the given channel ids and configuration. */
    static Thing thing(ThingTypeUID thingType, String thingId, List<String> channelIds, Map<String, Object> config) {
        ThingUID uid = new ThingUID(thingType, BRIDGE_UID, thingId);
        ThingBuilder builder = ThingBuilder.create(thingType, uid).withBridge(BRIDGE_UID)
                .withConfiguration(new Configuration(config));
        for (String channelId : channelIds) {
            builder.withChannel(ChannelBuilder.create(new ChannelUID(uid, channelId)).build());
        }
        return builder.build();
    }

    /** Connects the handler to the stand-ins and initializes it, as the framework does. */
    <H extends BaseThingHandler> H start(H handler) {
        handler.setCallback(callback);
        handler.initialize();
        return handler;
    }

    ThingStatusInfo status() {
        return Objects.requireNonNull(status);
    }

    State state(String channelId) {
        State state = states.get(channelId);
        assertNotNull(state, "no state published for " + channelId);
        return Objects.requireNonNull(state);
    }

    JsonObject lastRequest() {
        assertFalse(sentRequests.isEmpty(), "nothing was sent");
        return sentRequests.get(sentRequests.size() - 1);
    }

    static GardenaMessage event(String deviceId, String op, String path, String payload) {
        return GardenaMessage.parseFrame("[{\"entity\": {\"device\": \"" + deviceId + "\", \"path\": \"" + path
                + "\"}, \"op\": \"" + op + "\", \"payload\": " + payload + "}]").get(0);
    }

    static CompletableFuture<List<GardenaMessage>> succeed(GardenaRequests.Frame frame) {
        return CompletableFuture.completedFuture(reply(frame, true));
    }

    static CompletableFuture<List<GardenaMessage>> refuse(GardenaRequests.Frame frame) {
        return CompletableFuture.completedFuture(reply(frame, false));
    }

    static CompletableFuture<List<GardenaMessage>> fail(GardenaRequests.Frame frame) {
        return CompletableFuture.failedFuture(new IOException("not connected to the gateway"));
    }

    private static List<GardenaMessage> reply(GardenaRequests.Frame frame, boolean success) {
        JsonArray replies = new JsonArray();
        for (String requestId : frame.requestIds()) {
            JsonObject reply = new JsonObject();
            reply.addProperty("request_id", requestId);
            reply.addProperty("success", success);
            if (!success) {
                JsonObject payload = new JsonObject();
                payload.addProperty("vs", "Invalid value");
                reply.add("payload", payload);
            }
            replies.add(reply);
        }
        return GardenaMessage.parseFrame(replies.toString());
    }
}
