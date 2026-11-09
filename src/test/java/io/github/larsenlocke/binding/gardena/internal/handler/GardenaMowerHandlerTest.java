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

import static io.github.larsenlocke.binding.gardena.internal.GardenaLocalBindingConstants.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;

import com.google.gson.JsonObject;

/**
 * How {@link GardenaMowerHandler} drives first- and second-generation SILENO mowers and what it publishes.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
class GardenaMowerHandlerTest {

    private static final String SILENO = "3034F8EE901EE84000003333";
    private static final String SILENO_LONA = "3034F8EE9020F94000004444";
    private static final String SILENO_GEN2 = "3034F8319C02C1C000005555";
    private static final List<String> CHANNELS = List.of(CHANNEL_ACTIVITY, CHANNEL_COMMAND, CHANNEL_DURATION,
            CHANNEL_BATTERY_LEVEL, CHANNEL_ERROR_CODE, CHANNEL_CUTTING_TIME, CHANNEL_CHARGING_CYCLES,
            CHANNEL_COLLISIONS, CHANNEL_RF_LINK_QUALITY, CHANNEL_SIGNAL_STRENGTH, CHANNEL_LAST_SEEN);

    private HandlerHarness harness;

    @BeforeEach
    void setUp() throws IOException {
        harness = new HandlerHarness("appliances-list.json");
    }

    private GardenaMowerHandler mower(String deviceId, int mowingDuration) {
        Thing thing = HandlerHarness.thing(THING_TYPE_MOWER, "mower", CHANNELS,
                Map.of(CONFIG_DEVICE_ID, deviceId, "mowingDuration", mowingDuration));
        return harness.start(new GardenaMowerHandler(thing));
    }

    private static void command(GardenaMowerHandler handler, String command) {
        handler.handleCommand(new ChannelUID(handler.getThing().getUID(), CHANNEL_COMMAND), new StringType(command));
    }

    private JsonObject lastRequest(String op, String path) {
        JsonObject request = harness.lastRequest();
        assertEquals(op, request.get("op").getAsString());
        assertEquals(path, HandlerHarness.path(request));
        return request;
    }

    @Test
    void gen1PublishesAllChannels() {
        mower(SILENO, 3);
        assertEquals(ThingStatus.ONLINE, harness.status().getStatus());
        assertEquals(new StringType("parked"), harness.state(CHANNEL_ACTIVITY));
        harness.assertQuantity(3, Units.HOUR, CHANNEL_DURATION);
        harness.assertQuantity(95, Units.PERCENT, CHANNEL_BATTERY_LEVEL);
        assertEquals(new DecimalType(0), harness.state(CHANNEL_ERROR_CODE));
        harness.assertQuantity(412, Units.HOUR, CHANNEL_CUTTING_TIME);
        assertEquals(new DecimalType(230), harness.state(CHANNEL_CHARGING_CYCLES));
        assertEquals(new DecimalType(1520), harness.state(CHANNEL_COLLISIONS));
        harness.assertQuantity(70, Units.PERCENT, CHANNEL_RF_LINK_QUALITY);
        assertFalse(harness.states.containsKey(CHANNEL_SIGNAL_STRENGTH), "Gen1 has no signal strength");
        assertFalse(harness.states.containsKey(CHANNEL_COMMAND));
    }

    @Test
    void gen1Commands() {
        GardenaMowerHandler handler = mower(SILENO, 3);
        command(handler, "start");
        assertEquals(10800,
                lastRequest("write", "lemonbeat/0/mower_timer").getAsJsonObject("payload").get("vi").getAsInt());

        command(handler, "park-until-next-schedule");
        assertEquals(0,
                lastRequest("write", "lemonbeat/0/mower_timer").getAsJsonObject("payload").get("vi").getAsInt());

        command(handler, "park-until-further-notice");
        assertEquals("+gcMHxYA", lastRequest("write", "lemonbeat/0/action_paused_until_1").getAsJsonObject("payload")
                .get("vo").getAsString());
        assertEquals(3, harness.sentRequests.size());
    }

    @Test
    void gen1CannotBePaused() {
        GardenaMowerHandler handler = mower(SILENO, 3);
        command(handler, "pause");
        command(handler, "dance");
        assertTrue(harness.sentRequests.isEmpty());
    }

    @Test
    void durationChannelOnlyRemembersTheDuration() {
        GardenaMowerHandler handler = mower(SILENO, 3);
        ChannelUID duration = new ChannelUID(handler.getThing().getUID(), CHANNEL_DURATION);
        handler.handleCommand(duration, new DecimalType(2));
        assertTrue(harness.sentRequests.isEmpty(), "setting the duration must not start the mower");
        harness.assertQuantity(2, Units.HOUR, CHANNEL_DURATION);

        handler.handleCommand(duration, new QuantityType<>(30, Units.MINUTE));
        handler.handleCommand(duration, new QuantityType<>(7, Units.HOUR));
        command(handler, "start");
        assertEquals(7200, harness.lastRequest().getAsJsonObject("payload").get("vi").getAsInt());
    }

    @Test
    void lonaStartsWithADistanceTimer() {
        GardenaMowerHandler handler = mower(SILENO_LONA, 3);
        assertEquals(new StringType("mowing"), harness.state(CHANNEL_ACTIVITY));
        command(handler, "start");
        // distance 0 (2 bytes) followed by 10800 s (4 bytes), both big-endian
        assertEquals("AAAAACow", lastRequest("write", "lemonbeat/0/mower_timer_with_distance")
                .getAsJsonObject("payload").get("vo").getAsString());
    }

    @Test
    void gen2PublishesAndCommands() {
        GardenaMowerHandler handler = mower(SILENO_GEN2, 3);
        assertEquals(ThingStatus.ONLINE, harness.status().getStatus());
        assertEquals(new StringType("parked"), harness.state(CHANNEL_ACTIVITY));
        harness.assertQuantity(85, Units.PERCENT, CHANNEL_BATTERY_LEVEL);
        harness.assertQuantity(-67, Units.DECIBEL_MILLIWATTS, CHANNEL_SIGNAL_STRENGTH);
        assertFalse(harness.states.containsKey(CHANNEL_CUTTING_TIME), "Gen2 reports no cutting time");

        command(handler, "start");
        assertEquals("[\"0='10800'\"]", lastRequest("execute", "smart_system_mower_api/0/manual_start")
                .getAsJsonObject("payload").get("as").toString());

        command(handler, "park-until-further-notice");
        lastRequest("execute", "smart_system_mower_api/0/park_until_further_notice");

        command(handler, "pause");
        lastRequest("execute", "mower_app/0/pause");
    }

    @Test
    void refusedOrFailedCommandChangesNothing() {
        GardenaMowerHandler handler = mower(SILENO, 3);
        harness.stateUpdates.clear();
        harness.replies = HandlerHarness::refuse;
        command(handler, "start");
        harness.replies = HandlerHarness::fail;
        command(handler, "park-until-further-notice");
        assertEquals(2, harness.sentRequests.size(), "each command is sent once, not retried");
        assertTrue(harness.stateUpdates.isEmpty(), "the command channel has no state, activity stays as reported");
        assertEquals(ThingStatus.ONLINE, harness.status().getStatus());
    }

    @Test
    void activityIsLeftAloneUntilTheMowerReportsIt() throws IOException {
        harness.device(SILENO_GEN2).apply(HandlerHarness.event(SILENO_GEN2, "delete", "mower_app/0", "{}"));
        mower(SILENO_GEN2, 3);
        assertFalse(harness.states.containsKey(CHANNEL_ACTIVITY));
    }

    @Test
    void invalidMowingDurationIsAConfigurationError() {
        mower(SILENO, 0);
        assertEquals(ThingStatusDetail.CONFIGURATION_ERROR, harness.status().getStatusDetail());
        assertTrue(harness.states.isEmpty());
    }
}
