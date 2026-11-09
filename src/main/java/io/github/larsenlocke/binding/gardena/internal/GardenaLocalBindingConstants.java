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
package io.github.larsenlocke.binding.gardena.internal;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaModel;

/**
 * Constants used across the whole binding.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
public class GardenaLocalBindingConstants {

    public static final String BINDING_ID = "gardenalocal";

    // -- Thing type UIDs --
    public static final ThingTypeUID THING_TYPE_GATEWAY = new ThingTypeUID(BINDING_ID, "gateway");
    public static final ThingTypeUID THING_TYPE_WATER_CONTROL = thingType(GardenaModel.Family.WATER_CONTROL);
    public static final ThingTypeUID THING_TYPE_DUAL_WATER_CONTROL = thingType(GardenaModel.Family.DUAL_WATER_CONTROL);
    public static final ThingTypeUID THING_TYPE_IRRIGATION_CONTROL = thingType(GardenaModel.Family.IRRIGATION_CONTROL);
    public static final ThingTypeUID THING_TYPE_SENSOR = thingType(GardenaModel.Family.SENSOR);
    public static final ThingTypeUID THING_TYPE_POWER_ADAPTER = thingType(GardenaModel.Family.POWER_ADAPTER);
    public static final ThingTypeUID THING_TYPE_PUMP = thingType(GardenaModel.Family.PUMP);
    public static final ThingTypeUID THING_TYPE_MOWER = thingType(GardenaModel.Family.MOWER);

    /** Valves and measurements, handled by GardenaDeviceHandler. */
    public static final Set<ThingTypeUID> VALVE_AND_SENSOR_THING_TYPES = Set.of(THING_TYPE_WATER_CONTROL,
            THING_TYPE_DUAL_WATER_CONTROL, THING_TYPE_IRRIGATION_CONTROL, THING_TYPE_SENSOR);

    /** Every thing type underneath the gateway. */
    public static final Set<ThingTypeUID> DEVICE_THING_TYPES = Stream
            .concat(VALVE_AND_SENSOR_THING_TYPES.stream(),
                    Stream.of(THING_TYPE_POWER_ADAPTER, THING_TYPE_PUMP, THING_TYPE_MOWER))
            .collect(Collectors.toUnmodifiableSet());

    // -- Gateway configuration and properties --
    public static final String CONFIG_HOST = "host";
    public static final int DEFAULT_PORT = 8443;
    /** mDNS service type the gateway announces itself under. */
    public static final String MDNS_SERVICE_TYPE = "_gardena-smart._tcp.local.";
    public static final String PROPERTY_GATEWAY_NAME = "gatewayName";

    // -- Device configuration --
    public static final String CONFIG_DEVICE_ID = "deviceId";

    // -- Channel group ids: valve1 ... valve6, plus "device" on things with valves --
    public static final String GROUP_VALVE_PREFIX = "valve";
    public static final String GROUP_DEVICE = "device";

    // -- Channel ids inside a valve group --
    public static final String CHANNEL_VALVE_STATE = "state";
    public static final String CHANNEL_VALVE_DURATION = "duration";
    public static final String CHANNEL_VALVE_ERROR = "error";
    public static final String CHANNEL_VALVE_NAME = "name";
    public static final String CHANNEL_VALVE_CONNECTED = "connected";

    // -- Device-level channel ids --
    public static final String CHANNEL_LAST_SEEN = "last-seen";
    public static final String CHANNEL_BATTERY_LEVEL = "battery-level";
    public static final String CHANNEL_RF_LINK_QUALITY = "rf-link-quality";
    public static final String CHANNEL_SIGNAL_STRENGTH = "signal-strength";
    public static final String CHANNEL_FROST_WARNING = "frost-warning";
    public static final String CHANNEL_TEMPERATURE = "temperature";
    public static final String CHANNEL_SOIL_MOISTURE = "soil-moisture";
    public static final String CHANNEL_SOIL_TEMPERATURE = "soil-temperature";
    public static final String CHANNEL_AMBIENT_TEMPERATURE = "ambient-temperature";
    public static final String CHANNEL_LIGHT_INTENSITY = "light-intensity";

    // -- Power adapter, pump and mower channel ids (no groups) --
    public static final String CHANNEL_STATE = "state";
    public static final String CHANNEL_DURATION = "duration";
    public static final String CHANNEL_COMMAND = "command";
    public static final String CHANNEL_ERROR = "error";
    public static final String CHANNEL_OPERATING_MODE = "operating-mode";
    public static final String CHANNEL_TURN_ON_PRESSURE = "turn-on-pressure";
    public static final String CHANNEL_DRIPPING_ALERT = "dripping-alert";
    public static final String CHANNEL_PUMP_STATE = "pump-state";
    public static final String CHANNEL_OUTLET_PRESSURE = "outlet-pressure";
    public static final String CHANNEL_OUTLET_TEMPERATURE = "outlet-temperature";
    public static final String CHANNEL_FLOW_RATE = "flow-rate";
    public static final String CHANNEL_FLOW_TOTAL = "flow-total";
    public static final String CHANNEL_FLOW_SINCE_RESET = "flow-since-reset";
    public static final String CHANNEL_ACTIVITY = "activity";
    public static final String CHANNEL_ERROR_CODE = "error-code";
    public static final String CHANNEL_CUTTING_TIME = "cutting-time";
    public static final String CHANNEL_CHARGING_CYCLES = "charging-cycles";
    public static final String CHANNEL_COLLISIONS = "collisions";

    private static ThingTypeUID thingType(GardenaModel.Family family) {
        return new ThingTypeUID(BINDING_ID, family.getThingTypeId());
    }
}
