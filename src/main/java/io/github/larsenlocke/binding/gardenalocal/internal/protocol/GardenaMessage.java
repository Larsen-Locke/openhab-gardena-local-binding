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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * One message received from the gateway. Every WebSocket frame carries a JSON array of these, and each is one of
 * two kinds:
 * <ul>
 * <li>an <b>event</b> ({@code entity} + {@code op}), which the gateway pushes whenever a device's data changes:
 * {@code op} is {@code update} (merge the payload in), {@code overwrite} (replace the subtree) or
 * {@code delete} (remove it);</li>
 * <li>a <b>reply</b> ({@code request_id} + {@code success}) to a request this binding sent. A failed reply
 * carries the gateway's error text as {@code payload.vs}.</li>
 * </ul>
 *
 * @author Carsten Mogge - Initial contribution
 */
@NonNullByDefault
public final class GardenaMessage {

    public enum Kind {
        EVENT,
        REPLY
    }

    public static final String OP_UPDATE = "update";
    public static final String OP_OVERWRITE = "overwrite";
    public static final String OP_DELETE = "delete";

    private final Kind kind;
    private final @Nullable String device;
    private final @Nullable String service;
    private final String path;
    private final @Nullable String op;
    private final JsonObject payload;
    private final @Nullable String requestId;
    private final boolean success;

    private GardenaMessage(Kind kind, @Nullable String device, @Nullable String service, String path,
            @Nullable String op, JsonObject payload, @Nullable String requestId, boolean success) {
        this.kind = kind;
        this.device = device;
        this.service = service;
        this.path = path;
        this.op = op;
        this.payload = payload;
        this.requestId = requestId;
        this.success = success;
    }

    /**
     * Parses one frame. Entries that are neither an event nor a reply are skipped rather than failing the whole
     * frame, so one unexpected entry cannot hide the others.
     *
     * @throws IllegalArgumentException if the frame is not a JSON array at all
     */
    public static List<GardenaMessage> parseFrame(String text) {
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("Frame is not valid JSON", e);
        }
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("Frame is not a JSON array");
        }
        List<GardenaMessage> messages = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            if (element.isJsonObject()) {
                GardenaMessage message = parse(element.getAsJsonObject());
                if (message != null) {
                    messages.add(message);
                }
            }
        }
        return messages;
    }

    private static @Nullable GardenaMessage parse(JsonObject object) {
        JsonObject entity = object.get("entity") instanceof JsonObject e ? e : null;
        JsonObject payload = object.get("payload") instanceof JsonObject p ? p : new JsonObject();
        String device = entity == null ? null : GardenaJson.asString(entity.get("device"));
        String service = entity == null ? null : GardenaJson.asString(entity.get("service"));
        String path = entity == null ? "" : pathOf(entity.get("path"));
        String op = GardenaJson.asString(object.get("op"));

        if (entity != null && (OP_UPDATE.equals(op) || OP_OVERWRITE.equals(op) || OP_DELETE.equals(op))) {
            return new GardenaMessage(Kind.EVENT, device, service, path, op, payload, null, true);
        }

        String requestId = GardenaJson.asString(object.get("request_id"));
        Boolean success = GardenaJson.asBoolean(object.get("success"));
        if (requestId != null && success != null) {
            return new GardenaMessage(Kind.REPLY, device, service, path, null, payload, requestId, success);
        }
        return null;
    }

    private static String pathOf(@Nullable JsonElement path) {
        String asString = GardenaJson.asString(path);
        return asString == null ? "" : asString;
    }

    public Kind getKind() {
        return kind;
    }

    /** The device id (SGTIN-96 in hex) the message is about, if any. */
    public @Nullable String getDevice() {
        return device;
    }

    public @Nullable String getService() {
        return service;
    }

    /** The path inside the device's data tree, e.g. {@code "timeslot/3"}; "" for the device as a whole. */
    public String getPath() {
        return path;
    }

    /** {@code update}, {@code overwrite} or {@code delete} for an event; null for a reply. */
    public @Nullable String getOp() {
        return op;
    }

    public JsonObject getPayload() {
        return payload;
    }

    public @Nullable String getRequestId() {
        return requestId;
    }

    public boolean isSuccess() {
        return success;
    }

    /** The gateway's own description of why a request failed, if it gave one. */
    public String getErrorText() {
        String text = GardenaJson.asString(payload.get("vs"));
        return text == null ? "unknown error" : text;
    }

    @Override
    public String toString() {
        return kind == Kind.EVENT ? "event " + op + " " + device + " " + path
                : "reply " + requestId + " success=" + success;
    }
}
