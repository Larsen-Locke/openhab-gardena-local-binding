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
package io.github.larsenlocke.binding.gardena.internal.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Helpers for the gateway's data tree. A device is one JSON object nested as
 * {@code object/instance/resource/value-field}, for example
 * {@code {"lemonbeat": {"0": {"battery_level": {"ts": 1770214801, "vi": 57}}}}}. The leaf holds the value under
 * a key naming its type ({@code vb} boolean, {@code vi} integer, {@code vf} float, {@code vs} string, {@code vo}
 * base64 opaque, {@code vt} time, {@code ai}/{@code as} arrays) next to an optional {@code ts} timestamp.
 *
 * @author Carsten Mogge - Initial contribution
 */
@NonNullByDefault
public final class GardenaJson {

    private GardenaJson() {
    }

    /** Splits a path such as {@code "lemonbeat/0/watering_timer_1"} into its segments; empty for "". */
    public static List<String> segments(String path) {
        List<String> result = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                result.add(segment);
            }
        }
        return result;
    }

    /** The JSON object at {@code path} below {@code root}, or null if any segment is missing or not an object. */
    public static @Nullable JsonObject getObject(JsonObject root, List<String> path) {
        JsonObject current = root;
        for (String segment : path) {
            JsonElement next = current.get(segment);
            if (next == null || !next.isJsonObject()) {
                return null;
            }
            current = next.getAsJsonObject();
        }
        return current;
    }

    /**
     * The typed value of the resource at {@code path} - whichever value field it carries, ignoring the
     * {@code ts} timestamp - or null if there is none.
     */
    public static @Nullable JsonElement getValue(JsonObject root, List<String> path) {
        JsonObject field = getObject(root, path);
        if (field == null) {
            return null;
        }
        for (Map.Entry<String, JsonElement> entry : field.entrySet()) {
            if (!"ts".equals(entry.getKey()) && !entry.getValue().isJsonNull()) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static @Nullable Integer asInteger(@Nullable JsonElement value) {
        if (value instanceof JsonPrimitive primitive && primitive.isNumber()) {
            return primitive.getAsInt();
        }
        return null;
    }

    public static @Nullable Double asDouble(@Nullable JsonElement value) {
        if (value instanceof JsonPrimitive primitive && primitive.isNumber()) {
            return primitive.getAsDouble();
        }
        return null;
    }

    public static @Nullable Boolean asBoolean(@Nullable JsonElement value) {
        if (value instanceof JsonPrimitive primitive && primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        return null;
    }

    public static @Nullable String asString(@Nullable JsonElement value) {
        if (value instanceof JsonPrimitive primitive && primitive.isString()) {
            return primitive.getAsString();
        }
        return null;
    }

    /**
     * Merges {@code source} into {@code target}: nested objects are merged key by key, anything else replaces
     * what was there. This is how the gateway's "update" events are meant to be applied.
     */
    public static void deepMerge(JsonObject target, JsonObject source) {
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            JsonElement existing = target.get(key);
            if (existing != null && existing.isJsonObject() && value.isJsonObject()) {
                deepMerge(existing.getAsJsonObject(), value.getAsJsonObject());
            } else {
                target.add(key, value.deepCopy());
            }
        }
    }

    /**
     * Removes whatever sits at {@code path} and prunes parent objects that became empty through that, so a
     * deleted timeslot or actuator instance leaves no empty shell behind.
     */
    public static void delete(JsonObject root, List<String> path) {
        if (path.isEmpty()) {
            return;
        }
        List<JsonObject> parents = new ArrayList<>();
        JsonObject current = root;
        for (String segment : path.subList(0, path.size() - 1)) {
            parents.add(current);
            JsonElement next = current.get(segment);
            if (next == null || !next.isJsonObject()) {
                return;
            }
            current = next.getAsJsonObject();
        }
        current.remove(path.get(path.size() - 1));
        for (int i = parents.size() - 1; i >= 0; i--) {
            JsonObject parent = parents.get(i);
            String key = path.get(i);
            JsonElement child = parent.get(key);
            if (child != null && child.isJsonObject() && child.getAsJsonObject().isEmpty()) {
                parent.remove(key);
            } else {
                break;
            }
        }
    }

    /** Wraps {@code payload} in one nested object per path segment, outermost first. */
    public static JsonObject nest(List<String> path, JsonObject payload) {
        JsonObject nested = payload;
        for (int i = path.size() - 1; i >= 0; i--) {
            JsonObject wrapper = new JsonObject();
            wrapper.add(path.get(i), nested);
            nested = wrapper;
        }
        return nested;
    }
}
