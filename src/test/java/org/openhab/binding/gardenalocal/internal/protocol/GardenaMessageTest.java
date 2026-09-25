/**
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
package org.openhab.binding.gardenalocal.internal.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests parsing incoming frames ({@link GardenaMessage}), the data-tree helpers ({@link GardenaJson}) and
 * building outgoing frames ({@link GardenaRequests}).
 *
 * @author Carsten Mogge - Initial contribution
 */
@NonNullByDefault
class GardenaMessageTest {

    @Test
    void parsesEventsAndReplies() {
        List<GardenaMessage> messages = GardenaMessage.parseFrame("""
                [
                  {"entity": {"device": "ABC", "path": "lemonbeat/0", "service": "lemonbeatd"},
                   "metadata": {"sequence": 1}, "op": "update", "payload": {"battery_level": {"vi": 50}}},
                  {"request_id": "r1", "success": true, "payload": {}},
                  {"request_id": "r2", "success": false, "payload": {"vs": "Invalid path"},
                   "metadata": {"error_source": "lemonbeatd"}},
                  {"something": "else"},
                  42
                ]
                """);
        assertEquals(3, messages.size());

        GardenaMessage event = messages.get(0);
        assertEquals(GardenaMessage.Kind.EVENT, event.getKind());
        assertEquals("ABC", event.getDevice());
        assertEquals("lemonbeat/0", event.getPath());
        assertEquals("update", event.getOp());

        assertEquals(GardenaMessage.Kind.REPLY, messages.get(1).getKind());
        assertTrue(messages.get(1).isSuccess());

        GardenaMessage failure = messages.get(2);
        assertFalse(failure.isSuccess());
        assertEquals("r2", failure.getRequestId());
        assertEquals("Invalid path", failure.getErrorText());
    }

    @Test
    void rejectsFramesThatAreNotLists() {
        assertThrows(IllegalArgumentException.class, () -> GardenaMessage.parseFrame("{\"op\": \"update\"}"));
        assertThrows(IllegalArgumentException.class, () -> GardenaMessage.parseFrame("not json at all"));
    }

    @Test
    void deleteEventWithoutPathRemovesWholeDevice() {
        GardenaMessage event = GardenaMessage
                .parseFrame("[{\"entity\": {\"device\": \"ABC\", \"path\": \"\"}, \"op\": \"delete\"}]").get(0);
        assertEquals(GardenaMessage.OP_DELETE, event.getOp());
        assertEquals("", event.getPath());
    }

    @Test
    void getValueIgnoresTimestamp() {
        JsonObject data = JsonParser.parseString("{\"a\": {\"0\": {\"x\": {\"ts\": 5, \"vi\": 7}}}}").getAsJsonObject();
        assertEquals(7, GardenaJson.asInteger(GardenaJson.getValue(data, GardenaJson.segments("a/0/x"))));
        assertNull(GardenaJson.getValue(data, GardenaJson.segments("a/0/y")));
        assertNull(GardenaJson.getValue(data, GardenaJson.segments("a/1/x")));
    }

    @Test
    void deletePrunesEmptyParents() {
        JsonObject data = JsonParser.parseString("{\"t\": {\"1\": {\"s\": {\"vi\": 1}}}, \"k\": {}}")
                .getAsJsonObject();
        GardenaJson.delete(data, GardenaJson.segments("t/1/s"));
        assertFalse(data.has("t"));
        assertTrue(data.has("k"), "unrelated empty objects are left alone");
    }

    @Test
    void deepMergeKeepsSiblings() {
        JsonObject target = JsonParser.parseString("{\"a\": {\"x\": 1, \"y\": 2}}").getAsJsonObject();
        GardenaJson.deepMerge(target, JsonParser.parseString("{\"a\": {\"y\": 3, \"z\": 4}}").getAsJsonObject());
        assertEquals("{\"a\":{\"x\":1,\"y\":3,\"z\":4}}", target.toString());
    }

    @Test
    void discoveryAsksBothServices() {
        GardenaRequests.Frame frame = GardenaRequests.discovery();
        var requests = JsonParser.parseString(frame.json()).getAsJsonArray();
        assertEquals(2, requests.size());
        assertEquals(2, frame.requestIds().size());
        assertNotEquals(frame.requestIds().get(0), frame.requestIds().get(1));
        JsonObject first = requests.get(0).getAsJsonObject();
        assertEquals("read", first.get("op").getAsString());
        assertEquals("devices", first.getAsJsonObject("entity").get("path").getAsString());
        assertEquals("lemonbeatd", first.getAsJsonObject("entity").get("service").getAsString());
        assertEquals("lwm2mserver",
                requests.get(1).getAsJsonObject().getAsJsonObject("entity").get("service").getAsString());
    }

    @Test
    void gen1ValveCommandsWriteTheWateringTimer() {
        JsonObject open = single(GardenaRequests.openValve("DEV", GardenaModel.IRRIGATION_CONTROL_GEN1, 3, 900));
        assertEquals("write", open.get("op").getAsString());
        assertEquals("lemonbeat/0/watering_timer_3", open.getAsJsonObject("entity").get("path").getAsString());
        assertEquals("lemonbeatd", open.getAsJsonObject("entity").get("service").getAsString());
        assertEquals("DEV", open.getAsJsonObject("entity").get("device").getAsString());
        assertEquals(900, open.getAsJsonObject("payload").get("vi").getAsInt());

        JsonObject close = single(GardenaRequests.closeValve("DEV", GardenaModel.WATER_CONTROL_GEN1, 1));
        assertEquals("lemonbeat/0/watering_timer_1", close.getAsJsonObject("entity").get("path").getAsString());
        assertEquals(0, close.getAsJsonObject("payload").get("vi").getAsInt());
    }

    @Test
    void gen2ValveCommandsExecuteActuatorActions() {
        JsonObject open = single(GardenaRequests.openValve("DEV", GardenaModel.DUAL_WATER_CONTROL, 2, 600));
        assertEquals("execute", open.get("op").getAsString());
        assertEquals("actuator/1/start", open.getAsJsonObject("entity").get("path").getAsString());
        assertEquals("lwm2mserver", open.getAsJsonObject("entity").get("service").getAsString());
        assertEquals("[\"0='18',1='600'\"]", open.getAsJsonObject("payload").get("as").toString());

        JsonObject close = single(GardenaRequests.closeValve("DEV", GardenaModel.IRRIGATION_CONTROL_GEN2, 6));
        assertEquals("actuator/5/stop", close.getAsJsonObject("entity").get("path").getAsString());
        assertEquals("[\"0='18'\"]", close.getAsJsonObject("payload").get("as").toString());
    }

    private static JsonObject single(GardenaRequests.Frame frame) {
        var requests = JsonParser.parseString(frame.json()).getAsJsonArray();
        assertEquals(1, requests.size());
        JsonObject request = requests.get(0).getAsJsonObject();
        assertEquals(frame.requestIds().get(0), request.get("request_id").getAsString());
        return request;
    }
}
