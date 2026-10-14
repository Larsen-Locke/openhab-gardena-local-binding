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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaModel.Generation;

/**
 * Builds the frames this binding sends to the gateway. A frame is a JSON array of requests, each addressing a
 * gateway service (and optionally a device) by path, with an operation ({@code read}, {@code write} or
 * {@code execute}), an optional payload and a request id the gateway echoes in its reply.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
public final class GardenaRequests {

    /**
     * The "command source" a Gen2 device records for a start or stop, 18 meaning the local WebSocket API. The
     * device reports it back on the timeslot it creates, so the GARDENA app can show where a run came from.
     */
    static final String COMMAND_SOURCE = "18";

    /** Longest watering run a Gen1 device accepts in its watering timer, 10 hours. */
    public static final int MAX_WATERING_SECONDS = 36000;

    private GardenaRequests() {
    }

    /** A serialized frame plus the ids of the requests in it, whose replies the sender waits for. */
    public record Frame(String json, List<String> requestIds) {
    }

    /** Reads every device of both services; each reply's payload maps device ids to their full data tree. */
    public static Frame discovery() {
        return frame(request(Generation.GEN1.getService(), null, "devices", "read", null),
                request(Generation.GEN2.getService(), null, "devices", "read", null));
    }

    /**
     * Opens one valve (1-based) for {@code seconds}. The device closes it again on its own when the time is up,
     * so a lost connection cannot leave the water running.
     */
    public static Frame openValve(String deviceId, GardenaModel model, int valve, int seconds) {
        if (model.getGeneration() == Generation.GEN1) {
            return frame(writeWateringTimer(deviceId, valve, seconds));
        }
        JsonObject payload = stringArray(COMMAND_SOURCE, String.valueOf(seconds));
        return frame(request(Generation.GEN2.getService(), deviceId, "actuator/" + (valve - 1) + "/start", "execute",
                payload));
    }

    /** Closes one valve (1-based) straight away. */
    public static Frame closeValve(String deviceId, GardenaModel model, int valve) {
        if (model.getGeneration() == Generation.GEN1) {
            return frame(writeWateringTimer(deviceId, valve, 0));
        }
        return frame(request(Generation.GEN2.getService(), deviceId, "actuator/" + (valve - 1) + "/stop", "execute",
                stringArray(COMMAND_SOURCE)));
    }

    private static JsonObject writeWateringTimer(String deviceId, int valve, int seconds) {
        JsonObject payload = new JsonObject();
        payload.addProperty("vi", seconds);
        return request(Generation.GEN1.getService(), deviceId, "lemonbeat/0/watering_timer_" + valve, "write", payload);
    }

    /**
     * Arguments of an execute request, as the gateway expects them: one string holding
     * {@code 0='first',1='second',...}.
     */
    static JsonObject stringArray(String... values) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                joined.append(',');
            }
            joined.append(i).append("='").append(values[i]).append('\'');
        }
        JsonArray array = new JsonArray();
        array.add(joined.toString());
        JsonObject payload = new JsonObject();
        payload.add("as", array);
        return payload;
    }

    private static JsonObject request(String service, @Nullable String deviceId, String path, String op,
            @Nullable JsonObject payload) {
        JsonObject entity = new JsonObject();
        entity.addProperty("path", path);
        entity.addProperty("service", service);
        if (deviceId != null) {
            entity.addProperty("device", deviceId);
        }
        JsonObject request = new JsonObject();
        request.add("entity", entity);
        request.addProperty("op", op);
        if (payload != null) {
            request.add("payload", payload);
        }
        request.addProperty("request_id", UUID.randomUUID().toString());
        return request;
    }

    private static Frame frame(JsonObject... requests) {
        JsonArray array = new JsonArray();
        List<String> ids = new ArrayList<>();
        for (JsonObject request : requests) {
            array.add(request);
            ids.add(request.get("request_id").getAsString());
        }
        return new Frame(array.toString(), List.copyOf(ids));
    }
}
