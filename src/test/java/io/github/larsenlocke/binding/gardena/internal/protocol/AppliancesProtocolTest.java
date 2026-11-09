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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests the power adapter, pump and mower support of {@link GardenaDevice}, {@link MowerActivity} and
 * {@link GardenaRequests}. Expected payloads marked "reference" are the test vectors of GARDENA's
 * gardena-smart-local-api library, so they check the encoding against an outside source.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
class AppliancesProtocolTest {

    private static final String POWER_ADAPTER = "3034F8EE90226A0000001111";
    private static final String PUMP = "3034F8EE902261C000002222";
    private static final String SILENO = "3034F8EE901EE84000003333";
    private static final String SILENO_LONA = "3034F8EE9020F94000004444";
    private static final String SILENO_GEN2 = "3034F8319C02C1C000005555";

    private Map<String, GardenaDevice> devices = new HashMap<>();

    @BeforeEach
    void loadDeviceList() throws IOException {
        devices = new HashMap<>();
        String json;
        try (InputStream in = Objects
                .requireNonNull(AppliancesProtocolTest.class.getResourceAsStream("/appliances-list.json"))) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (GardenaMessage reply : GardenaMessage.parseFrame(json)) {
            for (Map.Entry<String, JsonElement> entry : reply.getPayload().entrySet()) {
                GardenaDevice device = GardenaDevice.fromDiscovery(entry.getKey(), entry.getValue().getAsJsonObject());
                if (device != null) {
                    devices.put(device.getId(), device);
                }
            }
        }
    }

    private GardenaDevice device(String id) {
        return Objects.requireNonNull(devices.get(id));
    }

    private void apply(String id, String path, String payload) {
        device(id).apply(GardenaMessage.parseFrame("[{\"entity\": {\"device\": \"" + id + "\", \"path\": \"" + path
                + "\"}, \"op\": \"update\", \"payload\": " + payload + "}]").get(0));
    }

    private static JsonObject single(GardenaRequests.Frame frame) {
        var requests = JsonParser.parseString(frame.json()).getAsJsonArray();
        assertEquals(1, requests.size());
        return requests.get(0).getAsJsonObject();
    }

    private static String path(JsonObject request) {
        return request.getAsJsonObject("entity").get("path").getAsString();
    }

    private static String service(JsonObject request) {
        return request.getAsJsonObject("entity").get("service").getAsString();
    }

    private static @Nullable JsonElement payload(JsonObject request) {
        return request.get("payload");
    }

    @Test
    void modelsAreRecognized() {
        assertEquals(GardenaModel.POWER_ADAPTER, device(POWER_ADAPTER).getModel());
        assertEquals(GardenaModel.PUMP, device(PUMP).getModel());
        assertEquals(GardenaModel.SILENO, device(SILENO).getModel());
        assertEquals(GardenaModel.SILENO_CITY_LIFE_LONA, device(SILENO_LONA).getModel());
        assertEquals(GardenaModel.SILENO_GEN2, device(SILENO_GEN2).getModel());
        assertEquals(GardenaModel.Family.MOWER, GardenaModel.SILENO_CITY_LIFE.getFamily());
        assertEquals("mower", GardenaModel.Family.MOWER.getThingTypeId());
        assertEquals("pump", GardenaModel.Family.PUMP.getThingTypeId());
        assertEquals("power-adapter", GardenaModel.Family.POWER_ADAPTER.getThingTypeId());
    }

    @Test
    void powerAdapter() {
        GardenaDevice adapter = device(POWER_ADAPTER);
        assertEquals(Boolean.FALSE, adapter.isPowerOn());
        assertEquals(90.0, adapter.getRfLinkQuality());

        apply(POWER_ADAPTER, "lemonbeat/0", "{\"power_timer\": {\"vi\": 16777216}}");
        assertEquals(Boolean.TRUE, adapter.isPowerOn(), "indefinitely on");
        apply(POWER_ADAPTER, "lemonbeat/0", "{\"power_timer\": {\"vi\": -1200}}");
        assertEquals(Boolean.TRUE, adapter.isPowerOn(), "on by schedule");
        apply(POWER_ADAPTER, "lemonbeat/0", "{\"power_timer\": {\"vi\": 0}}");
        assertEquals(Boolean.FALSE, adapter.isPowerOn());
    }

    @Test
    void pump() {
        GardenaDevice pump = device(PUMP);
        assertEquals(Boolean.FALSE, pump.isPumpRunning());
        assertEquals(0, pump.getPumpOperatingMode());
        assertEquals(1, pump.getPumpState());
        assertEquals(0.2, pump.getOutletPressure());
        assertEquals(14.0, pump.getOutletTemperature());
        assertEquals(12.0, pump.getFlowTotal());
        assertEquals(3.0, pump.getFlowSinceReset());
        assertEquals(2.0, pump.getTurnOnPressure());
        assertEquals(0, pump.getDrippingAlert());
        assertEquals(0, pump.getValveError(1));
        assertEquals(Boolean.FALSE, pump.getFrostWarning());
        assertNull(pump.getBatteryLevel(), "mains powered");
    }

    @Test
    void pumpFlowRateCountsOnlyWhileRunning() {
        GardenaDevice pump = device(PUMP);
        assertEquals(0.0, pump.getFlowRate(), "the reported 100 l/h of a standing pump is not real");
        apply(PUMP, "lemonbeat/0", "{\"pump_on_off\": {\"vi\": 1}, \"flow_rate\": {\"vi\": 1450}}");
        assertEquals(Boolean.TRUE, pump.isPumpRunning());
        assertEquals(1450.0, pump.getFlowRate());
    }

    @Test
    void gen1Mower() {
        GardenaDevice mower = device(SILENO);
        assertEquals(MowerActivity.PARKED, mower.getMowerActivity());
        assertEquals(95.0, mower.getBatteryLevel());
        assertEquals(0, mower.getMowerErrorCode());
        assertEquals(412.0, mower.getCuttingHours());
        assertEquals(230, mower.getChargingCycles());
        assertEquals(1520, mower.getCollisions());
        assertEquals(70.0, mower.getRfLinkQuality());
        assertNull(mower.getSignalStrength());

        assertEquals(MowerActivity.MOWING, device(SILENO_LONA).getMowerActivity());
    }

    @Test
    void gen2Mower() {
        GardenaDevice mower = device(SILENO_GEN2);
        assertEquals(MowerActivity.PARKED, mower.getMowerActivity());
        assertEquals(85.0, mower.getBatteryLevel());
        assertEquals(0, mower.getMowerErrorCode());
        assertEquals(-67.0, mower.getSignalStrength());
        assertNull(mower.getRfLinkQuality());
        assertNull(mower.getCuttingHours(), "Gen1 statistics only");
    }

    /** The reference library's table for Gen1 status values. */
    @ParameterizedTest
    @CsvSource({ "0,PAUSED", "1,MOWING", "2,RETURNING", "3,CHARGING", "4,LEAVING", "5,PAUSED", "6,PAUSED", "7,PARKED",
            "8,PARKED", "9,PAUSED", "10,PAUSED", "11,UNKNOWN", "12,ERROR", "13,ERROR", "14,PAUSED", "15,MOWING",
            "16,PARKED", "17,PARKED", "18,PARKED", "99,UNKNOWN" })
    void gen1StatusMapping(int status, MowerActivity expected) {
        assertEquals(expected, MowerActivity.fromGen1Status(status));
    }

    /** The reference library's table for Gen2 activity values; 0 defers to the state. */
    @ParameterizedTest
    @CsvSource({ "1,0,CHARGING", "2,0,LEAVING", "3,0,MOWING", "4,0,RETURNING", "5,0,PARKED", "6,0,UNKNOWN",
            "99,0,UNKNOWN", "0,2,UNKNOWN", "0,3,ERROR", "0,5,PAUSED", "0,8,ERROR" })
    void gen2ActivityMapping(int activity, int state, MowerActivity expected) {
        assertEquals(expected, MowerActivity.fromGen2(activity, state));
    }

    @Test
    void missingValuesAreUnknown() {
        assertEquals(MowerActivity.UNKNOWN, MowerActivity.fromGen1Status(null));
        assertEquals(MowerActivity.UNKNOWN, MowerActivity.fromGen2(null, 5));
        assertEquals(MowerActivity.UNKNOWN, MowerActivity.fromGen2(0, null));
        assertEquals("parked", MowerActivity.PARKED.value());
    }

    @Test
    void powerAdapterRequests() {
        JsonObject on = single(GardenaRequests.powerTimer(POWER_ADAPTER, 120));
        assertEquals("write", on.get("op").getAsString());
        assertEquals("lemonbeatd", service(on));
        assertEquals("lemonbeat/0/power_timer", path(on));
        assertEquals("{\"vi\":120}", String.valueOf(payload(on))); // reference
        assertEquals("{\"vi\":16777216}",
                String.valueOf(payload(single(GardenaRequests.powerTimer(POWER_ADAPTER, 16777216)))));
        assertEquals("{\"vi\":0}", String.valueOf(payload(single(GardenaRequests.powerTimer(POWER_ADAPTER, 0)))));
    }

    @Test
    void pumpRequests() {
        JsonObject start = single(GardenaRequests.pumpTimer(PUMP, 120));
        assertEquals("lemonbeat/0/watering_timer_1", path(start));
        assertEquals("{\"vi\":120}", String.valueOf(payload(start))); // reference
        assertEquals("{\"vi\":0}", String.valueOf(payload(single(GardenaRequests.pumpTimer(PUMP, 0)))));

        JsonObject mode = single(GardenaRequests.pumpOperatingMode(PUMP, 1));
        assertEquals("lemonbeat/0/operating_mode", path(mode));
        assertEquals("{\"vi\":1}", String.valueOf(payload(mode)));

        JsonObject pressure = single(GardenaRequests.pumpTurnOnPressure(PUMP, 2.5));
        assertEquals("lemonbeat/0/turn_on_pressure", path(pressure));
        assertEquals("{\"vf\":2.5}", String.valueOf(payload(pressure)));

        JsonObject alert = single(GardenaRequests.pumpDrippingAlert(PUMP, 2));
        assertEquals("lemonbeat/0/dripping_alert", path(alert));
        assertEquals("{\"vi\":2}", String.valueOf(payload(alert)));

        JsonObject reset = single(GardenaRequests.gen1Command(PUMP, GardenaRequests.PUMP_RESET_FLOW));
        assertEquals("lemonbeat/0/command", path(reset));
        assertEquals("{\"vi\":27}", String.valueOf(payload(reset)));
    }

    @Test
    void gen1MowerRequests() {
        JsonObject start = single(GardenaRequests.mowerStart(SILENO, GardenaModel.SILENO, 300));
        assertEquals("write", start.get("op").getAsString());
        assertEquals("lemonbeat/0/mower_timer", path(start));
        assertEquals("{\"vi\":300}", String.valueOf(payload(start))); // reference

        JsonObject park = single(GardenaRequests.mowerParkUntilFurtherNotice(SILENO, GardenaModel.SILENO));
        assertEquals("lemonbeat/0/action_paused_until_1", path(park));
        assertEquals("{\"vo\":\"+gcMHxYA\"}", String.valueOf(payload(park))); // reference: 2042-12-31 22:00

        assertThrows(IllegalArgumentException.class,
                () -> GardenaRequests.mowerPause(SILENO, GardenaModel.SILENO_CITY_LIFE));
    }

    @Test
    void lonaMowerStartsWithTheDistance() {
        JsonObject start = single(GardenaRequests.mowerStart(SILENO_LONA, GardenaModel.SILENO_CITY_LIFE_LONA, 300));
        assertEquals("lemonbeat/0/mower_timer_with_distance", path(start));
        assertEquals("{\"vo\":\"AAAAAAEs\"}", String.valueOf(payload(start))); // reference: 0 m, 300 s
    }

    @Test
    void gen2MowerRequests() {
        JsonObject start = single(GardenaRequests.mowerStart(SILENO_GEN2, GardenaModel.SILENO_GEN2, 300));
        assertEquals("execute", start.get("op").getAsString());
        assertEquals("lwm2mserver", service(start));
        assertEquals("smart_system_mower_api/0/manual_start", path(start));
        assertEquals("{\"as\":[\"0='300'\"]}", String.valueOf(payload(start))); // reference

        JsonObject park = single(GardenaRequests.mowerParkUntilFurtherNotice(SILENO_GEN2, GardenaModel.SILENO_GEN2));
        assertEquals("execute", park.get("op").getAsString());
        assertEquals("smart_system_mower_api/0/park_until_further_notice", path(park));
        assertNull(payload(park)); // reference

        JsonObject pause = single(GardenaRequests.mowerPause(SILENO_GEN2, GardenaModel.SILENO_GEN2));
        assertEquals("mower_app/0/pause", path(pause));
        assertNull(payload(pause)); // reference
    }
}
