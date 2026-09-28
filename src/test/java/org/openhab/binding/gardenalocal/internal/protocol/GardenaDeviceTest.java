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
package org.openhab.binding.gardenalocal.internal.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;

/**
 * Tests {@link GardenaDevice} against a device-list reply shaped like the gateway's, and against the events that
 * follow it.
 *
 * @author Carsten Mogge - Initial contribution
 */
@NonNullByDefault
class GardenaDeviceTest {

    private static final String IRRIGATION_CONTROL = "3034F8EE90126D400001737D";
    private static final String SENSOR = "3034F8EE901267400000B333";
    private static final String POWER_ADAPTER = "3034F8EE902273C000009390";
    private static final String WATER_CONTROL_GEN2 = "3034F8319C02BF00000033FF";

    private Map<String, GardenaDevice> devices = new HashMap<>();

    @BeforeEach
    void loadDeviceList() throws IOException {
        devices = new HashMap<>();
        for (GardenaMessage reply : GardenaMessage.parseFrame(resource("device-list.json"))) {
            for (Map.Entry<String, JsonElement> entry : reply.getPayload().entrySet()) {
                GardenaDevice device = GardenaDevice.fromDiscovery(entry.getKey(), entry.getValue().getAsJsonObject());
                if (device != null) {
                    devices.put(device.getId(), device);
                }
            }
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(GardenaDeviceTest.class.getResourceAsStream("/" + name))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private GardenaDevice device(String id) {
        return Objects.requireNonNull(devices.get(id));
    }

    private static GardenaMessage event(String deviceId, String op, String path, String payload) {
        List<GardenaMessage> messages = GardenaMessage.parseFrame("[{\"entity\": {\"device\": \"" + deviceId
                + "\", \"path\": \"" + path + "\"}, \"op\": \"" + op + "\", \"payload\": " + payload + "}]");
        assertEquals(1, messages.size());
        return messages.get(0);
    }

    @Test
    void unsupportedModelsAreSkipped() {
        assertEquals(3, devices.size());
        assertFalse(devices.containsKey(POWER_ADAPTER));
        assertEquals(GardenaModel.IRRIGATION_CONTROL_GEN1, device(IRRIGATION_CONTROL).getModel());
        assertEquals(GardenaModel.SENSOR, device(SENSOR).getModel());
        assertEquals(GardenaModel.WATER_CONTROL_GEN2, device(WATER_CONTROL_GEN2).getModel());
    }

    @Test
    void gen1IrrigationControlValves() {
        GardenaDevice device = device(IRRIGATION_CONTROL);
        assertEquals("00095101", device.getSerialNumber());
        assertEquals("2.5.0", device.getSoftwareVersion());
        assertEquals(Boolean.TRUE, device.isOnline());

        assertEquals(Boolean.FALSE, device.isValveOpen(1));
        assertEquals(Boolean.TRUE, device.isValveOpen(2));
        assertNull(device.isValveOpen(4), "no watering timer reported for valve 4");

        assertEquals(0, device.getValveError(1));
        assertEquals(2, device.getValveError(2));

        // valves_connected = 0b1110: master (bit 0) absent, valves 1-3 wired.
        assertEquals(Boolean.TRUE, device.isValveConnected(1));
        assertEquals(Boolean.TRUE, device.isValveConnected(3));
        assertEquals(Boolean.FALSE, device.isValveConnected(4));

        assertEquals("Rasen vorn", device.getValveName(1));
        assertNull(device.getValveName(2));

        assertEquals(80.0, device.getRfLinkQuality());
        assertNull(device.getSignalStrength());
    }

    @Test
    void gen1WateringTimerEventOpensAndClosesValve() {
        GardenaDevice device = device(IRRIGATION_CONTROL);
        device.apply(event(IRRIGATION_CONTROL, "update", "lemonbeat/0",
                "{\"watering_timer_1\": {\"ts\": 1770000000, \"vi\": 1800}}"));
        assertEquals(Boolean.TRUE, device.isValveOpen(1));
        // Other resources of the same object survive an update.
        assertEquals(Boolean.TRUE, device.isValveOpen(2));

        device.apply(event(IRRIGATION_CONTROL, "update", "lemonbeat/0/watering_timer_1", "{\"vi\": 0}"));
        assertEquals(Boolean.FALSE, device.isValveOpen(1));
    }

    @Test
    void sensorValues() {
        GardenaDevice device = device(SENSOR);
        assertEquals(34.0, device.getSoilMoisture());
        assertEquals(15.0, device.getSoilTemperature());
        assertEquals(18.0, device.getAmbientTemperature());
        assertEquals(1200.0, device.getLightIntensity());
        assertEquals(57.0, device.getBatteryLevel());
        assertEquals(Boolean.FALSE, device.getFrostWarning());
    }

    @Test
    void lastReportIsTheNewestTimestamp() {
        GardenaDevice device = device(SENSOR);
        assertEquals(java.time.Instant.ofEpochSecond(1770223054), device.getLastReport());
        device.apply(event(SENSOR, "update", "lemonbeat/0", "{\"soil_humidity\": {\"ts\": 1780000000, \"vi\": 40}}"));
        assertEquals(java.time.Instant.ofEpochSecond(1780000000), device.getLastReport());
        assertNull(device(WATER_CONTROL_GEN2).getLastReport(), "no timestamps in that fixture");
    }

    @Test
    void connectionStatusEvent() {
        GardenaDevice device = device(SENSOR);
        device.apply(event(SENSOR, "update", "connection_status/0", "{\"online\": {\"vb\": false}}"));
        assertEquals(Boolean.FALSE, device.isOnline());
    }

    @Test
    void gen2WaterControlValueLocations() {
        GardenaDevice device = device(WATER_CONTROL_GEN2);
        assertEquals(91.0, device.getBatteryLevel());
        assertEquals(21.5, device.getTemperature());
        assertEquals(-73.0, device.getSignalStrength());
        assertNull(device.getRfLinkQuality(), "Gen2 link_quality is not a percentage");
        assertNull(device.getFrostWarning());
        assertEquals("Hochbeet", device.getValveName(1));
        assertEquals(Boolean.TRUE, device.isValveConnected(1));
        assertEquals(0, device.getValveError(1));
    }

    @Test
    void gen2ValveFollowsRunningTimeslot() {
        GardenaDevice device = device(WATER_CONTROL_GEN2);
        // Timeslot 7 is only scheduled.
        assertEquals(Boolean.FALSE, device.isValveOpen(1));
        assertNull(device.isValveOpen(2), "the water control has only actuator 0");

        device.apply(event(WATER_CONTROL_GEN2, "update", "timeslot/8",
                "{\"actuator\": {\"vi\": 0}, \"state\": {\"vi\": 5}}"));
        assertEquals(Boolean.TRUE, device.isValveOpen(1));

        device.apply(event(WATER_CONTROL_GEN2, "delete", "timeslot/8", "{}"));
        assertEquals(Boolean.FALSE, device.isValveOpen(1));
    }

    @Test
    void overwriteReplacesSubtree() {
        GardenaDevice device = device(WATER_CONTROL_GEN2);
        device.apply(event(WATER_CONTROL_GEN2, "overwrite", "actuator/0", "{\"name\": {\"vs\": \"Tomaten\"}}"));
        assertEquals("Tomaten", device.getValveName(1));
        assertNull(device.getValveError(1), "overwrite drops what the new payload does not carry");
    }
}
