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
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.core.library.types.DateTimeType;
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
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;

import com.google.gson.JsonObject;

/**
 * Pins down how {@link GardenaDeviceHandler} drives water controls, irrigation controls and sensors: which
 * channels it publishes from the device data, which frames its commands send, and which status it reports.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
class GardenaDeviceHandlerTest {

    private static final String IRRIGATION_CONTROL = "3034F8EE90126D400001737D";
    private static final String SENSOR = "3034F8EE901267400000B333";
    private static final String WATER_CONTROL_GEN2 = "3034F8319C02BF00000033FF";

    private HandlerHarness harness;

    @BeforeEach
    void setUp() throws IOException {
        harness = new HandlerHarness("device-list.json");
    }

    private static List<String> irrigationChannels() {
        List<String> channels = new ArrayList<>();
        for (int valve = 1; valve <= 6; valve++) {
            for (String id : List.of(CHANNEL_VALVE_STATE, CHANNEL_VALVE_DURATION, CHANNEL_VALVE_NAME,
                    CHANNEL_VALVE_CONNECTED, CHANNEL_VALVE_ERROR)) {
                channels.add(GROUP_VALVE_PREFIX + valve + "#" + id);
            }
        }
        for (String id : List.of(CHANNEL_RF_LINK_QUALITY, CHANNEL_SIGNAL_STRENGTH, CHANNEL_LAST_SEEN)) {
            channels.add(GROUP_DEVICE + "#" + id);
        }
        return channels;
    }

    private static List<String> waterControlChannels() {
        List<String> channels = new ArrayList<>();
        for (String id : List.of(CHANNEL_VALVE_STATE, CHANNEL_VALVE_DURATION, CHANNEL_VALVE_ERROR)) {
            channels.add("valve1#" + id);
        }
        for (String id : List.of(CHANNEL_TEMPERATURE, CHANNEL_FROST_WARNING, CHANNEL_BATTERY_LEVEL,
                CHANNEL_RF_LINK_QUALITY, CHANNEL_SIGNAL_STRENGTH, CHANNEL_LAST_SEEN)) {
            channels.add(GROUP_DEVICE + "#" + id);
        }
        return channels;
    }

    private static final List<String> SENSOR_CHANNELS = List.of(CHANNEL_SOIL_MOISTURE, CHANNEL_SOIL_TEMPERATURE,
            CHANNEL_AMBIENT_TEMPERATURE, CHANNEL_LIGHT_INTENSITY, CHANNEL_FROST_WARNING, CHANNEL_BATTERY_LEVEL,
            CHANNEL_RF_LINK_QUALITY, CHANNEL_LAST_SEEN);

    private GardenaDeviceHandler irrigationControl(int wateringDuration) {
        Thing thing = HandlerHarness.thing(THING_TYPE_IRRIGATION_CONTROL, "irrigation", irrigationChannels(),
                Map.of(CONFIG_DEVICE_ID, IRRIGATION_CONTROL, "wateringDuration", wateringDuration));
        return harness.start(new GardenaDeviceHandler(thing));
    }

    private void assertQuantity(double expected, Unit<?> unit, String channelId) {
        State state = harness.state(channelId);
        assertInstanceOf(QuantityType.class, state, channelId);
        QuantityType<?> quantity = (QuantityType<?>) state;
        assertEquals(unit, quantity.getUnit(), channelId);
        assertEquals(expected, quantity.doubleValue(), 0.001, channelId);
    }

    private static ChannelUID channel(GardenaDeviceHandler handler, String channelId) {
        return new ChannelUID(handler.getThing().getUID(), channelId);
    }

    @Test
    void irrigationControlPublishesValvesAndDevice() {
        GardenaDeviceHandler handler = irrigationControl(20);
        assertEquals(ThingStatus.ONLINE, harness.status().getStatus());

        assertEquals(OnOffType.OFF, harness.state("valve1#state"));
        assertEquals(OnOffType.ON, harness.state("valve2#state"));
        assertEquals(OnOffType.OFF, harness.state("valve3#state"));
        assertFalse(harness.states.containsKey("valve4#state"), "no watering timer reported for valve 4");

        assertEquals(new StringType("Rasen vorn"), harness.state("valve1#name"));
        assertEquals(OnOffType.ON, harness.state("valve1#connected"));
        assertEquals(OnOffType.OFF, harness.state("valve4#connected"));
        assertEquals(new DecimalType(2), harness.state("valve2#error"));
        assertQuantity(20, Units.MINUTE, "valve1#duration");
        assertQuantity(20, Units.MINUTE, "valve6#duration");

        assertQuantity(80, Units.PERCENT, "device#rf-link-quality");
        assertInstanceOf(DateTimeType.class, harness.state("device#last-seen"));
        assertFalse(harness.states.containsKey("device#signal-strength"), "Gen1 has no signal strength");

        Map<String, String> properties = handler.getThing().getProperties();
        assertEquals("GARDENA", properties.get(Thing.PROPERTY_VENDOR));
        assertEquals("31653", properties.get(Thing.PROPERTY_MODEL_ID));
        assertEquals("00095101", properties.get(Thing.PROPERTY_SERIAL_NUMBER));
        assertEquals("2.5.0", properties.get(Thing.PROPERTY_FIRMWARE_VERSION));
    }

    @Test
    void valveSwitchOpensForTheDurationAndCloses() {
        GardenaDeviceHandler handler = irrigationControl(20);

        handler.handleCommand(channel(handler, "valve3#state"), OnOffType.ON);
        JsonObject open = harness.lastRequest();
        assertEquals("write", open.get("op").getAsString());
        assertEquals("lemonbeat/0/watering_timer_3", open.getAsJsonObject("entity").get("path").getAsString());
        assertEquals(IRRIGATION_CONTROL, open.getAsJsonObject("entity").get("device").getAsString());
        assertEquals(1200, open.getAsJsonObject("payload").get("vi").getAsInt());

        handler.handleCommand(channel(handler, "valve3#state"), OnOffType.OFF);
        assertEquals(0, harness.lastRequest().getAsJsonObject("payload").get("vi").getAsInt());
    }

    @Test
    void durationChannelOnlyRemembersTheDuration() {
        GardenaDeviceHandler handler = irrigationControl(20);

        handler.handleCommand(channel(handler, "valve1#duration"), new DecimalType(15));
        assertTrue(harness.sentRequests.isEmpty(), "setting the duration must not open the valve");
        assertQuantity(15, Units.MINUTE, "valve1#duration");

        handler.handleCommand(channel(handler, "valve1#state"), OnOffType.ON);
        assertEquals(900, harness.lastRequest().getAsJsonObject("payload").get("vi").getAsInt());

        handler.handleCommand(channel(handler, "valve2#state"), OnOffType.ON);
        assertEquals(1200, harness.lastRequest().getAsJsonObject("payload").get("vi").getAsInt(),
                "other valves keep the thing's duration");
    }

    @Test
    void durationOutsideTheRangeIsIgnored() {
        GardenaDeviceHandler handler = irrigationControl(20);
        handler.handleCommand(channel(handler, "valve1#duration"), new QuantityType<>(30, Units.SECOND));
        handler.handleCommand(channel(handler, "valve1#duration"), new QuantityType<>(11, Units.HOUR));
        handler.handleCommand(channel(handler, "valve1#state"), OnOffType.ON);
        assertEquals(1200, harness.lastRequest().getAsJsonObject("payload").get("vi").getAsInt());
    }

    @Test
    void failedCommandPutsTheSwitchBack() {
        GardenaDeviceHandler handler = irrigationControl(20);
        harness.replies = HandlerHarness::fail;
        harness.stateUpdates.clear();
        handler.handleCommand(channel(handler, "valve1#state"), OnOffType.ON);
        assertEquals(List.of("valve1#state"), harness.stateUpdates);
        assertEquals(OnOffType.OFF, harness.state("valve1#state"));
    }

    @Test
    void refusedCommandPutsTheSwitchBack() {
        GardenaDeviceHandler handler = irrigationControl(20);
        harness.replies = HandlerHarness::refuse;
        harness.stateUpdates.clear();
        handler.handleCommand(channel(handler, "valve2#state"), OnOffType.OFF);
        assertEquals(List.of("valve2#state"), harness.stateUpdates);
        assertEquals(OnOffType.ON, harness.state("valve2#state"));
    }

    @Test
    void commandForADeviceTheGatewayLostPutsTheSwitchBack() {
        GardenaDeviceHandler handler = irrigationControl(20);
        harness.devices.remove(IRRIGATION_CONTROL);
        harness.stateUpdates.clear();
        handler.handleCommand(channel(handler, "valve1#state"), OnOffType.ON);
        assertTrue(harness.sentRequests.isEmpty());
        assertEquals(List.of("valve1#state"), harness.stateUpdates);
        assertEquals(OnOffType.OFF, harness.state("valve1#state"));
    }

    @Test
    void onlyChangedValuesArePublishedAgain() {
        GardenaDeviceHandler handler = irrigationControl(20);
        harness.stateUpdates.clear();
        handler.deviceUpdated(harness.device(IRRIGATION_CONTROL));
        assertTrue(harness.stateUpdates.isEmpty());

        harness.device(IRRIGATION_CONTROL).apply(HandlerHarness.event(IRRIGATION_CONTROL, "update", "lemonbeat/0",
                "{\"watering_timer_1\": {\"ts\": 1780000000, \"vi\": 600}}"));
        handler.deviceUpdated(harness.device(IRRIGATION_CONTROL));
        assertTrue(harness.stateUpdates.contains("valve1#state"));
        assertEquals(OnOffType.ON, harness.state("valve1#state"));
    }

    @Test
    void refreshReplaysTheLastState() {
        GardenaDeviceHandler handler = irrigationControl(20);
        harness.stateUpdates.clear();
        handler.handleCommand(channel(handler, "valve2#state"), RefreshType.REFRESH);
        assertEquals(List.of("valve2#state"), harness.stateUpdates);
        assertTrue(harness.sentRequests.isEmpty());
    }

    @Test
    void gen2WaterControl() {
        Thing thing = HandlerHarness.thing(THING_TYPE_WATER_CONTROL, "hochbeet", waterControlChannels(),
                Map.of(CONFIG_DEVICE_ID, WATER_CONTROL_GEN2, "wateringDuration", 10));
        GardenaDeviceHandler handler = harness.start(new GardenaDeviceHandler(thing));
        assertEquals(ThingStatus.ONLINE, harness.status().getStatus());
        assertEquals(OnOffType.OFF, harness.state("valve1#state"));
        assertQuantity(91, Units.PERCENT, "device#battery-level");
        assertQuantity(21.5, SIUnits.CELSIUS, "device#temperature");
        assertQuantity(-73, Units.DECIBEL_MILLIWATTS, "device#signal-strength");
        assertFalse(harness.states.containsKey("device#frost-warning"), "Gen2 reports no frost warning");

        handler.handleCommand(channel(handler, "valve1#state"), OnOffType.ON);
        JsonObject open = harness.lastRequest();
        assertEquals("execute", open.get("op").getAsString());
        assertEquals("actuator/0/start", open.getAsJsonObject("entity").get("path").getAsString());
        assertEquals("[\"0='18',1='600'\"]", open.getAsJsonObject("payload").get("as").toString());
    }

    @Test
    void sensor() {
        Thing thing = HandlerHarness.thing(THING_TYPE_SENSOR, "lawn", SENSOR_CHANNELS,
                Map.of(CONFIG_DEVICE_ID, SENSOR));
        harness.start(new GardenaDeviceHandler(thing));
        assertEquals(ThingStatus.ONLINE, harness.status().getStatus());
        assertQuantity(34, Units.PERCENT, CHANNEL_SOIL_MOISTURE);
        assertQuantity(15, SIUnits.CELSIUS, CHANNEL_SOIL_TEMPERATURE);
        assertQuantity(18, SIUnits.CELSIUS, CHANNEL_AMBIENT_TEMPERATURE);
        assertQuantity(1200, Units.LUX, CHANNEL_LIGHT_INTENSITY);
        assertQuantity(57, Units.PERCENT, CHANNEL_BATTERY_LEVEL);
        assertQuantity(100, Units.PERCENT, CHANNEL_RF_LINK_QUALITY);
        assertEquals(OnOffType.OFF, harness.state(CHANNEL_FROST_WARNING));
    }

    @Test
    void deviceOfAnotherThingTypeIsAConfigurationError() {
        Thing thing = HandlerHarness.thing(THING_TYPE_IRRIGATION_CONTROL, "wrong", irrigationChannels(),
                Map.of(CONFIG_DEVICE_ID, SENSOR, "wateringDuration", 20));
        harness.start(new GardenaDeviceHandler(thing));
        assertEquals(ThingStatusDetail.CONFIGURATION_ERROR, harness.status().getStatusDetail());
        assertTrue(harness.states.isEmpty());
    }

    @Test
    void deviceOutOfRadioRangeIsOffline() {
        GardenaDeviceHandler handler = irrigationControl(20);
        harness.device(IRRIGATION_CONTROL).apply(HandlerHarness.event(IRRIGATION_CONTROL, "update",
                "connection_status/0", "{\"online\": {\"vb\": false}}"));
        handler.deviceUpdated(harness.device(IRRIGATION_CONTROL));
        assertEquals(ThingStatus.OFFLINE, harness.status().getStatus());
        assertEquals(ThingStatusDetail.COMMUNICATION_ERROR, harness.status().getStatusDetail());
    }

    @Test
    void gatewayOfflineMakesTheDeviceOffline() {
        when(harness.bridge.getStatus()).thenReturn(ThingStatus.OFFLINE);
        irrigationControl(20);
        assertEquals(ThingStatusDetail.BRIDGE_OFFLINE, harness.status().getStatusDetail());
    }

    @Test
    void deviceUnknownToTheGatewayIsGone() {
        Thing thing = HandlerHarness.thing(THING_TYPE_SENSOR, "gone", SENSOR_CHANNELS,
                Map.of(CONFIG_DEVICE_ID, "3034F8EE901267400000FFFF"));
        harness.start(new GardenaDeviceHandler(thing));
        assertEquals(ThingStatusDetail.GONE, harness.status().getStatusDetail());
    }

    @Test
    void invalidConfiguration() {
        Thing badId = HandlerHarness.thing(THING_TYPE_SENSOR, "bad", SENSOR_CHANNELS,
                Map.of(CONFIG_DEVICE_ID, "not-an-id"));
        harness.start(new GardenaDeviceHandler(badId));
        assertEquals(ThingStatusDetail.CONFIGURATION_ERROR, harness.status().getStatusDetail());

        irrigationControl(0);
        assertEquals(ThingStatusDetail.CONFIGURATION_ERROR, harness.status().getStatusDetail());
    }
}
