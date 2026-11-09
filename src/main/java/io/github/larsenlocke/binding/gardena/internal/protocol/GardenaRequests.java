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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
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
        return writeGen1(deviceId, "watering_timer_" + valve, integer(seconds));
    }

    // -- Power adapter --

    /** Power timer value for "on until switched off", as the GARDENA app uses it (2^24). */
    public static final int POWER_ON_INDEFINITELY = 16777216;

    /** Switches the power adapter's output on for {@code seconds}, indefinitely, or (0) off. */
    public static Frame powerTimer(String deviceId, int seconds) {
        return frame(writeGen1(deviceId, "power_timer", integer(seconds)));
    }

    // -- Pump --

    /** Shortest and longest manual pump run the GARDENA app offers. */
    public static final int MIN_PUMP_SECONDS = 60;
    public static final int MAX_PUMP_SECONDS = 5400;

    /** Command codes of the pump's {@code lemonbeat/0/command} resource. */
    public static final int PUMP_RESET_FLOW = 27;
    public static final int PUMP_RESET_TEMPERATURE_MIN_MAX = 28;
    public static final int PUMP_RESET_VALVE_ERRORS = 36;

    /** Runs the pump for {@code seconds}, or (0) stops it. The pump ignores this in automatic mode. */
    public static Frame pumpTimer(String deviceId, int seconds) {
        return frame(writeWateringTimer(deviceId, 1, seconds));
    }

    /** 0 = scheduled, 1 = automatic. */
    public static Frame pumpOperatingMode(String deviceId, int mode) {
        return frame(writeGen1(deviceId, "operating_mode", integer(mode)));
    }

    /** Pressure below which the pump starts in automatic mode, in bar. */
    public static Frame pumpTurnOnPressure(String deviceId, double bar) {
        JsonObject payload = new JsonObject();
        payload.addProperty("vf", bar);
        return frame(writeGen1(deviceId, "turn_on_pressure", payload));
    }

    /** 0 = after 60 minutes, 1 = after 2 minutes, 2 = off. */
    public static Frame pumpDrippingAlert(String deviceId, int alert) {
        return frame(writeGen1(deviceId, "dripping_alert", integer(alert)));
    }

    /** Runs one of a Gen1 device's commands, e.g. {@link #PUMP_RESET_FLOW}. */
    public static Frame gen1Command(String deviceId, int command) {
        return frame(writeGen1(deviceId, "command", integer(command)));
    }

    // -- Mower --

    /** Shortest and longest manual mowing the GARDENA app offers. */
    public static final int MIN_MOWING_SECONDS = 3600;
    public static final int MAX_MOWING_SECONDS = 6 * 3600;

    /**
     * Mows for {@code seconds}; 0 parks the mower until its next scheduled start. The LONA models take the
     * distance from the charging station along with the duration, the default starting point being 0.
     */
    public static Frame mowerStart(String deviceId, GardenaModel model, int seconds) {
        if (model.getGeneration() == Generation.GEN2) {
            return frame(request(Generation.GEN2.getService(), deviceId, "smart_system_mower_api/0/manual_start",
                    "execute", stringArray(String.valueOf(seconds))));
        }
        if (model == GardenaModel.SILENO_CITY_LIFE_LONA) {
            ByteBuffer distanceAndDuration = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN);
            distanceAndDuration.putShort((short) 0).putInt(seconds);
            return frame(writeGen1(deviceId, "mower_timer_with_distance", opaque(distanceAndDuration.array())));
        }
        return frame(writeGen1(deviceId, "mower_timer", integer(seconds)));
    }

    /**
     * Parks the mower until further notice, ignoring its schedule. A Gen1 mower has no such command: its schedule
     * gets paused until a date far in the future (2042-12-31 22:00), which is what the GARDENA app does.
     */
    public static Frame mowerParkUntilFurtherNotice(String deviceId, GardenaModel model) {
        if (model.getGeneration() == Generation.GEN2) {
            return frame(request(Generation.GEN2.getService(), deviceId,
                    "smart_system_mower_api/0/park_until_further_notice", "execute", null));
        }
        ByteBuffer date = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        date.putShort((short) 2042).put((byte) 12).put((byte) 31).put((byte) 22).put((byte) 0);
        return frame(writeGen1(deviceId, "action_paused_until_1", opaque(date.array())));
    }

    /** Pauses the mower where it is. Gen2 only - a Gen1 mower has no pause command. */
    public static Frame mowerPause(String deviceId, GardenaModel model) {
        if (model.getGeneration() != Generation.GEN2) {
            throw new IllegalArgumentException(model.getProductName() + " cannot be paused");
        }
        return frame(request(Generation.GEN2.getService(), deviceId, "mower_app/0/pause", "execute", null));
    }

    private static JsonObject writeGen1(String deviceId, String resource, JsonObject payload) {
        return request(Generation.GEN1.getService(), deviceId, "lemonbeat/0/" + resource, "write", payload);
    }

    private static JsonObject integer(int value) {
        JsonObject payload = new JsonObject();
        payload.addProperty("vi", value);
        return payload;
    }

    private static JsonObject opaque(byte[] value) {
        JsonObject payload = new JsonObject();
        payload.addProperty("vo", Base64.getEncoder().encodeToString(value));
        return payload;
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
