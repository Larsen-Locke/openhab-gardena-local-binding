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
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;

import com.google.gson.JsonObject;

import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaRequests;

/**
 * How {@link GardenaPowerAdapterHandler} switches a smart Power Adapter and what it publishes.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
class GardenaPowerAdapterHandlerTest {

    private static final String POWER_ADAPTER = "3034F8EE90226A0000001111";
    private static final List<String> CHANNELS = List.of(CHANNEL_STATE, CHANNEL_DURATION, CHANNEL_RF_LINK_QUALITY,
            CHANNEL_LAST_SEEN);

    private HandlerHarness harness;

    @BeforeEach
    void setUp() throws IOException {
        harness = new HandlerHarness("appliances-list.json");
    }

    private GardenaPowerAdapterHandler powerAdapter(int onDuration) {
        Thing thing = HandlerHarness.thing(THING_TYPE_POWER_ADAPTER, "outlet", CHANNELS,
                Map.of(CONFIG_DEVICE_ID, POWER_ADAPTER, "onDuration", onDuration));
        return harness.start(new GardenaPowerAdapterHandler(thing));
    }

    private static ChannelUID channel(GardenaPowerAdapterHandler handler, String channelId) {
        return new ChannelUID(handler.getThing().getUID(), channelId);
    }

    private int lastTimer() {
        JsonObject request = harness.lastRequest();
        assertEquals("write", request.get("op").getAsString());
        assertEquals("lemonbeat/0/power_timer", HandlerHarness.path(request));
        assertEquals(POWER_ADAPTER, request.getAsJsonObject("entity").get("device").getAsString());
        return request.getAsJsonObject("payload").get("vi").getAsInt();
    }

    @Test
    void publishesStateDurationAndRadio() {
        GardenaPowerAdapterHandler handler = powerAdapter(0);
        assertEquals(ThingStatus.ONLINE, harness.status().getStatus());
        assertEquals(OnOffType.OFF, harness.state(CHANNEL_STATE));
        harness.assertQuantity(0, Units.MINUTE, CHANNEL_DURATION);
        harness.assertQuantity(90, Units.PERCENT, CHANNEL_RF_LINK_QUALITY);
        assertInstanceOf(DateTimeType.class, harness.state(CHANNEL_LAST_SEEN));
        assertEquals("35279", handler.getThing().getProperties().get(Thing.PROPERTY_MODEL_ID));
    }

    @Test
    void onWithoutDurationStaysOnUntilSwitchedOff() {
        GardenaPowerAdapterHandler handler = powerAdapter(0);
        handler.handleCommand(channel(handler, CHANNEL_STATE), OnOffType.ON);
        assertEquals(GardenaRequests.POWER_ON_INDEFINITELY, lastTimer());
        handler.handleCommand(channel(handler, CHANNEL_STATE), OnOffType.OFF);
        assertEquals(0, lastTimer());
    }

    @Test
    void onUsesTheConfiguredDurationAndTheDurationChannel() {
        GardenaPowerAdapterHandler handler = powerAdapter(10);
        handler.handleCommand(channel(handler, CHANNEL_STATE), OnOffType.ON);
        assertEquals(600, lastTimer());

        handler.handleCommand(channel(handler, CHANNEL_DURATION), new DecimalType(30));
        assertEquals(1, harness.sentRequests.size(), "setting the duration must not switch the outlet");
        harness.assertQuantity(30, Units.MINUTE, CHANNEL_DURATION);
        handler.handleCommand(channel(handler, CHANNEL_STATE), OnOffType.ON);
        assertEquals(1800, lastTimer());

        handler.handleCommand(channel(handler, CHANNEL_DURATION), new QuantityType<>(0, Units.MINUTE));
        handler.handleCommand(channel(handler, CHANNEL_STATE), OnOffType.ON);
        assertEquals(GardenaRequests.POWER_ON_INDEFINITELY, lastTimer(), "0 means until switched off");
    }

    @Test
    void durationOutsideTheRangeIsIgnored() {
        GardenaPowerAdapterHandler handler = powerAdapter(10);
        handler.handleCommand(channel(handler, CHANNEL_DURATION), new QuantityType<>(25, Units.HOUR));
        handler.handleCommand(channel(handler, CHANNEL_DURATION), new DecimalType(-5));
        handler.handleCommand(channel(handler, CHANNEL_STATE), OnOffType.ON);
        assertEquals(600, lastTimer());
    }

    @Test
    void reportsOnWhenTheTimerRuns() {
        GardenaPowerAdapterHandler handler = powerAdapter(0);
        harness.device(POWER_ADAPTER).apply(HandlerHarness.event(POWER_ADAPTER, "update", "lemonbeat/0",
                "{\"power_timer\": {\"ts\": 1770101000, \"vi\": 600}}"));
        handler.deviceUpdated(harness.device(POWER_ADAPTER));
        assertEquals(OnOffType.ON, harness.state(CHANNEL_STATE));
    }

    @Test
    void refusedCommandPutsTheSwitchBack() {
        GardenaPowerAdapterHandler handler = powerAdapter(0);
        harness.replies = HandlerHarness::refuse;
        harness.stateUpdates.clear();
        handler.handleCommand(channel(handler, CHANNEL_STATE), OnOffType.ON);
        assertEquals(List.of(CHANNEL_STATE), harness.stateUpdates);
        assertEquals(OnOffType.OFF, harness.state(CHANNEL_STATE));
    }

    @Test
    void invalidOnDurationIsAConfigurationError() {
        powerAdapter(1441);
        assertEquals(ThingStatusDetail.CONFIGURATION_ERROR, harness.status().getStatusDetail());
        assertTrue(Objects.requireNonNull(harness.status().getDescription()).contains("invalid-on-duration"));
        assertTrue(harness.states.isEmpty());
    }
}
