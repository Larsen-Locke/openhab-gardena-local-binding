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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaModel.Generation;

/**
 * One device as the gateway reports it: its id (an SGTIN-96 in hex), its model, and its full data tree, which
 * starts out as the device-list reply and is kept current by applying every event the gateway pushes for it.
 *
 * <p>
 * The accessors translate between the two radio generations, which keep the same information in entirely
 * different places. Each returns null when the device has not reported the value (yet), or when the model does
 * not have it at all.
 *
 * <p>
 * Synchronized throughout: events are applied on the WebSocket's thread while handlers read on openHAB's.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
public final class GardenaDevice {

    /** {@code timeslot/N/state} of a timeslot whose actuator is currently watering. */
    static final int TIMESLOT_STATE_RUNNING = 5;

    private static final String GEN1 = "lemonbeat/0/";

    private final String id;
    private final GardenaModel model;
    private final JsonObject data;

    private GardenaDevice(String id, GardenaModel model, JsonObject data) {
        this.id = id;
        this.model = model;
        this.data = data;
    }

    /**
     * Creates a device from one entry of a device-list reply, or returns null for a model this binding does not
     * support (yet).
     */
    public static @Nullable GardenaDevice fromDiscovery(String id, JsonObject data) {
        GardenaModel model = GardenaModel.fromModelNumber(modelNumberOf(data));
        return model == null ? null : new GardenaDevice(id, model, data.deepCopy());
    }

    /** The model number inside a device's data tree, for logging devices this binding does not support. */
    public static @Nullable String modelNumberOf(JsonObject data) {
        return GardenaJson.asString(GardenaJson.getValue(data, GardenaJson.segments("device/0/model_number")));
    }

    public String getId() {
        return id;
    }

    public GardenaModel getModel() {
        return model;
    }

    /** Applies an {@code update}, {@code overwrite} or {@code delete} event for a path inside this device. */
    public synchronized void apply(GardenaMessage event) {
        List<String> path = GardenaJson.segments(event.getPath());
        String op = event.getOp();
        if (GardenaMessage.OP_DELETE.equals(op)) {
            GardenaJson.delete(data, path);
            return;
        }
        if (GardenaMessage.OP_OVERWRITE.equals(op)) {
            GardenaJson.delete(data, path);
        }
        if (!event.getPayload().isEmpty()) {
            GardenaJson.deepMerge(data, GardenaJson.nest(path, event.getPayload()));
        }
    }

    /** Whether the gateway currently reaches the device over the radio. */
    public synchronized @Nullable Boolean isOnline() {
        return GardenaJson.asBoolean(value("connection_status/0/online"));
    }

    public synchronized @Nullable String getSerialNumber() {
        return GardenaJson.asString(value("device/0/serial_number"));
    }

    public synchronized @Nullable String getSoftwareVersion() {
        return GardenaJson.asString(value("device/0/software_version"));
    }

    public synchronized @Nullable String getHardwareVersion() {
        return GardenaJson.asString(value("device/0/hardware_version"));
    }

    /**
     * When the device last reported anything: the newest {@code ts} anywhere in its data. Every value carries
     * the time of its last report, so unlike a timestamp taken when an event arrives here, this is right from
     * the first device list after an openHAB restart.
     */
    public synchronized @Nullable Instant getLastReport() {
        long newest = newestTimestamp(data);
        return newest > 0 ? Instant.ofEpochSecond(newest) : null;
    }

    private static long newestTimestamp(JsonObject object) {
        long newest = 0;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonObject()) {
                newest = Math.max(newest, newestTimestamp(value.getAsJsonObject()));
            } else if ("ts".equals(entry.getKey())) {
                Double ts = GardenaJson.asDouble(value);
                if (ts != null) {
                    newest = Math.max(newest, ts.longValue());
                }
            }
        }
        return newest;
    }

    // -- Valves (1-based) --

    /**
     * Whether a valve is open. A Gen1 device reports it through its watering timer, which is non-zero while
     * watering. A Gen2 device keeps one timeslot per planned or running watering; the valve is open while a
     * timeslot for its actuator is in the running state.
     */
    public synchronized @Nullable Boolean isValveOpen(int valve) {
        if (model.getGeneration() == Generation.GEN1) {
            Double timer = GardenaJson.asDouble(value(GEN1 + "watering_timer_" + valve));
            return timer == null ? null : timer != 0;
        }
        String actuator = String.valueOf(valve - 1);
        if (GardenaJson.getObject(data, List.of("actuator", actuator)) == null) {
            return null;
        }
        JsonObject timeslots = GardenaJson.getObject(data, List.of("timeslot"));
        if (timeslots != null) {
            for (Map.Entry<String, JsonElement> entry : timeslots.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue; // e.g. the object's "_urn"
                }
                String instance = "timeslot/" + entry.getKey() + "/";
                Integer state = GardenaJson.asInteger(value(instance + "state"));
                Integer slotActuator = GardenaJson.asInteger(value(instance + "actuator"));
                if (state != null && state == TIMESLOT_STATE_RUNNING && slotActuator != null
                        && String.valueOf(slotActuator).equals(actuator)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The valve's error code, 0 for none. */
    public synchronized @Nullable Integer getValveError(int valve) {
        String path = model.getGeneration() == Generation.GEN1 ? GEN1 + "valve_error_" + valve
                : "actuator/" + (valve - 1) + "/error";
        return toInteger(GardenaJson.asDouble(value(path)));
    }

    /** Whether a valve is wired up to an irrigation control. */
    public synchronized @Nullable Boolean isValveConnected(int valve) {
        if (model.getGeneration() == Generation.GEN2) {
            return GardenaJson.asBoolean(value("actuator/" + (valve - 1) + "/available"));
        }
        // Bit 0 is the master valve, bit n is valve n.
        Integer bits = toInteger(GardenaJson.asDouble(value(GEN1 + "valves_connected")));
        return bits == null ? null : ((bits >> valve) & 1) == 1;
    }

    /** The valve name set in the GARDENA app. */
    public synchronized @Nullable String getValveName(int valve) {
        if (model.getGeneration() == Generation.GEN2) {
            return GardenaJson.asString(value("actuator/" + (valve - 1) + "/name"));
        }
        // Gen1 keeps it as a fixed-size, zero-padded UTF-8 buffer, base64 encoded.
        String encoded = GardenaJson.asString(value(GEN1 + "valve_name_" + valve));
        if (encoded == null) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int length = 0;
        while (length < bytes.length && bytes[length] != 0) {
            length++;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8).trim();
    }

    // -- Device-level values --

    /** Battery charge in percent. */
    public synchronized @Nullable Double getBatteryLevel() {
        return GardenaJson.asDouble(
                value(model.getGeneration() == Generation.GEN1 ? GEN1 + "battery_level" : "device/0/battery_level"));
    }

    /** Radio link quality in percent, Gen1 only. */
    public synchronized @Nullable Double getRfLinkQuality() {
        return model.getGeneration() == Generation.GEN1 ? GardenaJson.asDouble(value(GEN1 + "rf_link_quality")) : null;
    }

    /** Received radio signal strength in dBm, Gen2 only. */
    public synchronized @Nullable Double getSignalStrength() {
        return model.getGeneration() == Generation.GEN2
                ? GardenaJson.asDouble(value("connectivity_monitoring/0/radio_signal_strength"))
                : null;
    }

    /** Frost warning (temperature below about 5 °C), Gen1 only. */
    public synchronized @Nullable Boolean getFrostWarning() {
        Double warning = model.getGeneration() == Generation.GEN1 ? GardenaJson.asDouble(value(GEN1 + "frost_warning"))
                : null;
        return warning == null ? null : warning != 0;
    }

    /**
     * A water control's ambient temperature in °C. It is meant for frost detection: a Gen2 device only reports
     * it every half hour or on a change of more than 2 °C.
     */
    public synchronized @Nullable Double getTemperature() {
        return GardenaJson.asDouble(value(model.getGeneration() == Generation.GEN1 ? GEN1 + "ambient_temperature"
                : "temperature/0/sensor_value"));
    }

    // -- Sensor values --

    /** Soil moisture in percent. */
    public synchronized @Nullable Double getSoilMoisture() {
        return GardenaJson
                .asDouble(value(model == GardenaModel.SENSOR_II ? GEN1 + "soil_moisture" : GEN1 + "soil_humidity"));
    }

    /** Soil temperature in °C. */
    public synchronized @Nullable Double getSoilTemperature() {
        return GardenaJson.asDouble(value(GEN1 + "soil_temperature"));
    }

    /** Air temperature in °C, smart Sensor only. */
    public synchronized @Nullable Double getAmbientTemperature() {
        return model == GardenaModel.SENSOR ? GardenaJson.asDouble(value(GEN1 + "ambient_temperature")) : null;
    }

    /** Light intensity in lux, smart Sensor only. */
    public synchronized @Nullable Double getLightIntensity() {
        return model == GardenaModel.SENSOR ? GardenaJson.asDouble(value(GEN1 + "light")) : null;
    }

    // -- Power adapter values --

    /**
     * Whether the power adapter's output is on. Its power timer is non-zero while on: the remaining seconds of a
     * manual run (positive), of a scheduled one (negative), or {@link GardenaRequests#POWER_ON_INDEFINITELY}.
     */
    public synchronized @Nullable Boolean isPowerOn() {
        Double timer = GardenaJson.asDouble(value(GEN1 + "power_timer"));
        return timer == null ? null : timer != 0;
    }

    // -- Pump values --

    /** Whether the pump's motor runs - also when it started by itself in automatic mode. */
    public synchronized @Nullable Boolean isPumpRunning() {
        Double motor = GardenaJson.asDouble(value(GEN1 + "pump_on_off"));
        return motor == null ? null : motor != 0;
    }

    /** 0 = scheduled, 1 = automatic (the pump starts by itself when the outlet pressure drops). */
    public synchronized @Nullable Integer getPumpOperatingMode() {
        return toInteger(GardenaJson.asDouble(value(GEN1 + "operating_mode")));
    }

    /**
     * 0 = just starting, 1 = water in the pump body, 2 = no water in the pump body, 3 = flow after 5 s,
     * 4 = no flow after 5 s.
     */
    public synchronized @Nullable Integer getPumpState() {
        return toInteger(GardenaJson.asDouble(value(GEN1 + "pump_state")));
    }

    /** Outlet pressure in bar. */
    public synchronized @Nullable Double getOutletPressure() {
        return GardenaJson.asDouble(value(GEN1 + "outlet_pressure"));
    }

    /** Outlet (water) temperature in °C. */
    public synchronized @Nullable Double getOutletTemperature() {
        return GardenaJson.asDouble(value(GEN1 + "outlet_temperature"));
    }

    /**
     * Flow rate in l/h, 0 while the pump stands. The pump reports values from 100 upwards even when it is not
     * running, so the raw value only counts while the motor runs.
     */
    public synchronized @Nullable Double getFlowRate() {
        if (Boolean.FALSE.equals(isPumpRunning())) {
            return 0.0;
        }
        return GardenaJson.asDouble(value(GEN1 + "flow_rate"));
    }

    /** Water pumped over the pump's lifetime, in m³. */
    public synchronized @Nullable Double getFlowTotal() {
        return GardenaJson.asDouble(value(GEN1 + "flow_total"));
    }

    /** Water pumped since the counter was last reset, in m³. */
    public synchronized @Nullable Double getFlowSinceReset() {
        return GardenaJson.asDouble(value(GEN1 + "flow_since_last_reset"));
    }

    /** Pressure below which the pump starts in automatic mode, in bar. */
    public synchronized @Nullable Double getTurnOnPressure() {
        return GardenaJson.asDouble(value(GEN1 + "turn_on_pressure"));
    }

    /** 0 = after 60 minutes (factory setting), 1 = after 2 minutes, 2 = off. */
    public synchronized @Nullable Integer getDrippingAlert() {
        return toInteger(GardenaJson.asDouble(value(GEN1 + "dripping_alert")));
    }

    // -- Mower values --

    /** What the mower is doing, or null before it has reported its status at all. */
    public synchronized @Nullable MowerActivity getMowerActivity() {
        if (model.getGeneration() == Generation.GEN1) {
            Integer status = toInteger(GardenaJson.asDouble(value(GEN1 + "status")));
            return status == null ? null : MowerActivity.fromGen1Status(status);
        }
        Integer activity = toInteger(GardenaJson.asDouble(value("mower_app/0/activity")));
        return activity == null ? null
                : MowerActivity.fromGen2(activity, toInteger(GardenaJson.asDouble(value("mower_app/0/state"))));
    }

    /** The mower's last error code (Gen1 {@code last_error_code}, Gen2 {@code mower_app/0/error}), 0 for none. */
    public synchronized @Nullable Integer getMowerErrorCode() {
        String path = model.getGeneration() == Generation.GEN1 ? GEN1 + "last_error_code" : "mower_app/0/error";
        return toInteger(GardenaJson.asDouble(value(path)));
    }

    /** Hours the cutting disc has been running, Gen1 only. */
    public synchronized @Nullable Double getCuttingHours() {
        return model.getGeneration() == Generation.GEN1 ? GardenaJson.asDouble(value(GEN1 + "cutting_time")) : null;
    }

    /** Completed charging cycles, Gen1 only. */
    public synchronized @Nullable Integer getChargingCycles() {
        return model.getGeneration() == Generation.GEN1
                ? toInteger(GardenaJson.asDouble(value(GEN1 + "charging_cycles")))
                : null;
    }

    /** Collisions so far, Gen1 only. */
    public synchronized @Nullable Integer getCollisions() {
        return model.getGeneration() == Generation.GEN1 ? toInteger(GardenaJson.asDouble(value(GEN1 + "collisions")))
                : null;
    }

    private @Nullable JsonElement value(String path) {
        return GardenaJson.getValue(data, GardenaJson.segments(path));
    }

    private static @Nullable Integer toInteger(@Nullable Double value) {
        return value == null ? null : value.intValue();
    }

    @Override
    public String toString() {
        return model.getProductName() + " " + id;
    }
}
