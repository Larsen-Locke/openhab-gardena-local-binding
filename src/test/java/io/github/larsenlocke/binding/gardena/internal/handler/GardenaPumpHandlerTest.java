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
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;

import com.google.gson.JsonObject;

/**
 * How {@link GardenaPumpHandler} drives a smart Automatic Home &amp; Garden Pump and what it publishes.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
class GardenaPumpHandlerTest {

    private static final String PUMP = "3034F8EE902261C000002222";
    private static final List<String> CHANNELS = List.of(CHANNEL_STATE, CHANNEL_DURATION, CHANNEL_OPERATING_MODE,
            CHANNEL_TURN_ON_PRESSURE, CHANNEL_DRIPPING_ALERT, CHANNEL_PUMP_STATE, CHANNEL_OUTLET_PRESSURE,
            CHANNEL_OUTLET_TEMPERATURE, CHANNEL_FLOW_RATE, CHANNEL_FLOW_TOTAL, CHANNEL_FLOW_SINCE_RESET, CHANNEL_ERROR,
            CHANNEL_COMMAND, CHANNEL_FROST_WARNING, CHANNEL_RF_LINK_QUALITY, CHANNEL_LAST_SEEN);

    private HandlerHarness harness;

    @BeforeEach
    void setUp() throws IOException {
        harness = new HandlerHarness("appliances-list.json");
    }

    private GardenaPumpHandler pump(int runDuration) {
        Thing thing = HandlerHarness.thing(THING_TYPE_PUMP, "pump", CHANNELS,
                Map.of(CONFIG_DEVICE_ID, PUMP, "runDuration", runDuration));
        return harness.start(new GardenaPumpHandler(thing));
    }

    private static ChannelUID channel(GardenaPumpHandler handler, String channelId) {
        return new ChannelUID(handler.getThing().getUID(), channelId);
    }

    private JsonObject lastWrite(String resource) {
        JsonObject request = harness.lastRequest();
        assertEquals("write", request.get("op").getAsString());
        assertEquals("lemonbeat/0/" + resource, HandlerHarness.path(request));
        return request.getAsJsonObject("payload");
    }

    private void update(GardenaPumpHandler handler, String resources) {
        harness.device(PUMP).apply(HandlerHarness.event(PUMP, "update", "lemonbeat/0", resources));
        handler.deviceUpdated(harness.device(PUMP));
    }

    @Test
    void publishesAllChannels() {
        pump(30);
        assertEquals(ThingStatus.ONLINE, harness.status().getStatus());
        assertEquals(OnOffType.OFF, harness.state(CHANNEL_STATE));
        harness.assertQuantity(30, Units.MINUTE, CHANNEL_DURATION);
        assertEquals(new StringType("scheduled"), harness.state(CHANNEL_OPERATING_MODE));
        assertEquals(new StringType("standard"), harness.state(CHANNEL_DRIPPING_ALERT));
        assertEquals(new StringType("water-in-pump"), harness.state(CHANNEL_PUMP_STATE));
        harness.assertQuantity(2.0, Units.BAR, CHANNEL_TURN_ON_PRESSURE);
        harness.assertQuantity(0.2, Units.BAR, CHANNEL_OUTLET_PRESSURE);
        harness.assertQuantity(14, SIUnits.CELSIUS, CHANNEL_OUTLET_TEMPERATURE);
        harness.assertQuantity(0, GardenaPumpHandler.LITRE_PER_HOUR, CHANNEL_FLOW_RATE);
        harness.assertQuantity(12, SIUnits.CUBIC_METRE, CHANNEL_FLOW_TOTAL);
        harness.assertQuantity(3, SIUnits.CUBIC_METRE, CHANNEL_FLOW_SINCE_RESET);
        assertEquals(new StringType("none"), harness.state(CHANNEL_ERROR));
        assertEquals(OnOffType.OFF, harness.state(CHANNEL_FROST_WARNING));
        harness.assertQuantity(80, Units.PERCENT, CHANNEL_RF_LINK_QUALITY);
        assertFalse(harness.states.containsKey(CHANNEL_COMMAND));
    }

    @Test
    void flowRateIsReportedOnlyWhileRunning() {
        GardenaPumpHandler handler = pump(30);
        update(handler, "{\"pump_on_off\": {\"ts\": 1770101000, \"vi\": 1}}");
        assertEquals(OnOffType.ON, harness.state(CHANNEL_STATE));
        harness.assertQuantity(100, GardenaPumpHandler.LITRE_PER_HOUR, CHANNEL_FLOW_RATE);
    }

    @Test
    void switchRunsThePumpForTheDurationAndStopsIt() {
        GardenaPumpHandler handler = pump(30);
        handler.handleCommand(channel(handler, CHANNEL_STATE), OnOffType.ON);
        assertEquals(1800, lastWrite("watering_timer_1").get("vi").getAsInt());
        handler.handleCommand(channel(handler, CHANNEL_STATE), OnOffType.OFF);
        assertEquals(0, lastWrite("watering_timer_1").get("vi").getAsInt());

        handler.handleCommand(channel(handler, CHANNEL_DURATION), new DecimalType(15));
        assertEquals(2, harness.sentRequests.size(), "setting the duration must not start the pump");
        handler.handleCommand(channel(handler, CHANNEL_STATE), OnOffType.ON);
        assertEquals(900, lastWrite("watering_timer_1").get("vi").getAsInt());
    }

    @Test
    void durationOutsideTheRangeIsIgnored() {
        GardenaPumpHandler handler = pump(30);
        handler.handleCommand(channel(handler, CHANNEL_DURATION), new QuantityType<>(30, Units.SECOND));
        handler.handleCommand(channel(handler, CHANNEL_DURATION), new QuantityType<>(2, Units.HOUR));
        handler.handleCommand(channel(handler, CHANNEL_STATE), OnOffType.ON);
        assertEquals(1800, lastWrite("watering_timer_1").get("vi").getAsInt());
    }

    @Test
    void automaticModeRefusesManualStartAndPutsTheSwitchBack() {
        GardenaPumpHandler handler = pump(30);
        update(handler, "{\"operating_mode\": {\"ts\": 1770101000, \"vi\": 1}}");
        assertEquals(new StringType("automatic"), harness.state(CHANNEL_OPERATING_MODE));
        harness.stateUpdates.clear();

        handler.handleCommand(channel(handler, CHANNEL_STATE), OnOffType.ON);
        assertTrue(harness.sentRequests.isEmpty());
        assertEquals(List.of(CHANNEL_STATE), harness.stateUpdates);
        assertEquals(OnOffType.OFF, harness.state(CHANNEL_STATE));
    }

    @Test
    void settings() {
        GardenaPumpHandler handler = pump(30);
        handler.handleCommand(channel(handler, CHANNEL_OPERATING_MODE), new StringType("automatic"));
        assertEquals(1, lastWrite("operating_mode").get("vi").getAsInt());

        handler.handleCommand(channel(handler, CHANNEL_DRIPPING_ALERT), new StringType("off"));
        assertEquals(2, lastWrite("dripping_alert").get("vi").getAsInt());

        handler.handleCommand(channel(handler, CHANNEL_TURN_ON_PRESSURE), new QuantityType<>(2.46, Units.BAR));
        assertEquals(2.5, lastWrite("turn_on_pressure").get("vf").getAsDouble(), 1e-9);

        handler.handleCommand(channel(handler, CHANNEL_TURN_ON_PRESSURE), new DecimalType(3));
        assertEquals(3.0, lastWrite("turn_on_pressure").get("vf").getAsDouble(), 1e-9);
    }

    @Test
    void invalidSettingsAreNotSentAndPutBack() {
        GardenaPumpHandler handler = pump(30);
        harness.stateUpdates.clear();
        handler.handleCommand(channel(handler, CHANNEL_OPERATING_MODE), new StringType("turbo"));
        handler.handleCommand(channel(handler, CHANNEL_DRIPPING_ALERT), new StringType("loud"));
        handler.handleCommand(channel(handler, CHANNEL_TURN_ON_PRESSURE), new QuantityType<>(12, Units.BAR));
        assertTrue(harness.sentRequests.isEmpty());
        assertEquals(List.of(CHANNEL_OPERATING_MODE, CHANNEL_DRIPPING_ALERT, CHANNEL_TURN_ON_PRESSURE),
                harness.stateUpdates);
        assertEquals(new StringType("scheduled"), harness.state(CHANNEL_OPERATING_MODE));
    }

    @Test
    void commands() {
        GardenaPumpHandler handler = pump(30);
        handler.handleCommand(channel(handler, CHANNEL_COMMAND), new StringType("reset-flow"));
        assertEquals(27, lastWrite("command").get("vi").getAsInt());
        handler.handleCommand(channel(handler, CHANNEL_COMMAND), new StringType("reset-errors"));
        assertEquals(36, lastWrite("command").get("vi").getAsInt());
        handler.handleCommand(channel(handler, CHANNEL_COMMAND), new StringType("reset-temperature-min-max"));
        assertEquals(28, lastWrite("command").get("vi").getAsInt());

        handler.handleCommand(channel(handler, CHANNEL_COMMAND), new StringType("self-destruct"));
        assertEquals(3, harness.sentRequests.size());
    }

    @Test
    void pumpErrorIsNamed() {
        GardenaPumpHandler handler = pump(30);
        update(handler, "{\"valve_error_1\": {\"ts\": 1770101000, \"vi\": 4}}");
        assertEquals(new StringType("small-leakage"), harness.state(CHANNEL_ERROR));
    }

    @Test
    void invalidRunDurationIsAConfigurationError() {
        pump(91);
        assertEquals(ThingStatusDetail.CONFIGURATION_ERROR, harness.status().getStatusDetail());
        assertTrue(harness.states.isEmpty());
    }
}
