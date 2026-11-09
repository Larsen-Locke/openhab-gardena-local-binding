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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.larsenlocke.binding.gardena.internal.config.GardenaDeviceConfiguration;
import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaDevice;
import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaModel.Family;
import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaRequests;

/**
 * A water control, dual water control, irrigation control or sensor: valves that open for a duration, and
 * measurements.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
public class GardenaDeviceHandler extends GardenaThingHandler {

    private static final int MIN_WATERING_MINUTES = 1;
    private static final int MAX_WATERING_MINUTES = GardenaRequests.MAX_WATERING_SECONDS / 60;

    private final Logger logger = LoggerFactory.getLogger(GardenaDeviceHandler.class);

    private volatile int wateringSeconds = GardenaDeviceConfiguration.DEFAULT_WATERING_DURATION_MINUTES * 60;

    /**
     * Durations set through a valve's duration channel, in seconds. Not persisted: after a restart every valve
     * starts from the thing's configured watering duration again.
     */
    private final Map<Integer, Integer> valveDurations = new ConcurrentHashMap<>();

    public GardenaDeviceHandler(Thing thing) {
        super(thing);
    }

    @Override
    protected boolean readConfiguration() {
        GardenaDeviceConfiguration config = getConfigAs(GardenaDeviceConfiguration.class);
        if (hasValves()
                && (config.wateringDuration < MIN_WATERING_MINUTES || config.wateringDuration > MAX_WATERING_MINUTES)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.invalid-watering-duration [\"" + MIN_WATERING_MINUTES + "\", \""
                            + MAX_WATERING_MINUTES + "\"]");
            return false;
        }
        wateringSeconds = config.wateringDuration * 60;
        valveDurations.clear();
        return true;
    }

    @Override
    protected void handleDeviceCommand(ChannelUID channelUID, Command command) {
        String group = channelUID.getGroupId();
        if (group == null || !group.startsWith(GROUP_VALVE_PREFIX)) {
            return;
        }
        int valve;
        try {
            valve = Integer.parseInt(group.substring(GROUP_VALVE_PREFIX.length()));
        } catch (NumberFormatException e) {
            return;
        }

        switch (channelUID.getIdWithoutGroup()) {
            case CHANNEL_VALVE_STATE -> {
                if (command instanceof OnOffType onOff) {
                    sendValveCommand(valve, onOff == OnOffType.ON ? durationSeconds(valve) : 0);
                }
            }
            case CHANNEL_VALVE_DURATION -> {
                // Only remembers the duration for the next ON - it never opens the valve by itself. Rules set it
                // right before switching ON, and an item linked with the follow profile forwards every update.
                Integer seconds = toSeconds(command, Units.MINUTE);
                if (seconds == null) {
                    logger.debug("Ignoring command {} for {}: not a duration", command, channelUID);
                } else if (seconds < 60 || seconds > GardenaRequests.MAX_WATERING_SECONDS) {
                    logger.warn("Ignoring watering duration of {} s for {}: allowed are 1 to {} min", seconds,
                            channelUID, MAX_WATERING_MINUTES);
                } else {
                    valveDurations.put(valve, seconds);
                    publish(channelUID.getId(), durationState(seconds));
                }
            }
            default -> logger.debug("Channel {} cannot be commanded", channelUID);
        }
    }

    /** How long {@code valve} opens for on ON: its own duration if one was set, else the thing's. */
    private int durationSeconds(int valve) {
        return valveDurations.getOrDefault(valve, wateringSeconds);
    }

    private static State durationState(int seconds) {
        return new QuantityType<>(seconds / 60.0, Units.MINUTE);
    }

    /** Opens a valve for {@code seconds}, or closes it for 0. */
    private void sendValveCommand(int valve, int seconds) {
        String action = seconds > 0 ? "open valve " + valve + " for " + seconds + " s" : "close valve " + valve;
        String stateChannel = GROUP_VALVE_PREFIX + valve + ChannelUID.CHANNEL_GROUP_SEPARATOR + CHANNEL_VALVE_STATE;
        send(action, stateChannel, device -> {
            if (valve < 1 || valve > device.getModel().getFamily().getValveCount()) {
                return null;
            }
            return seconds > 0 ? GardenaRequests.openValve(getDeviceId(), device.getModel(), valve, seconds)
                    : GardenaRequests.closeValve(getDeviceId(), device.getModel(), valve);
        });
    }

    private boolean hasValves() {
        return !THING_TYPE_SENSOR.equals(getThing().getThingTypeUID());
    }

    @Override
    protected void publishStates(GardenaDevice device) {
        Family family = device.getModel().getFamily();
        for (int valve = 1; valve <= family.getValveCount(); valve++) {
            String group = GROUP_VALVE_PREFIX + valve + ChannelUID.CHANNEL_GROUP_SEPARATOR;
            publish(group + CHANNEL_VALVE_STATE, onOff(device.isValveOpen(valve)));
            publish(group + CHANNEL_VALVE_DURATION, durationState(durationSeconds(valve)));
            publish(group + CHANNEL_VALVE_ERROR, decimal(device.getValveError(valve)));
            if (family == Family.IRRIGATION_CONTROL) {
                String name = device.getValveName(valve);
                publish(group + CHANNEL_VALVE_NAME, name == null ? null : new StringType(name));
                publish(group + CHANNEL_VALVE_CONNECTED, onOff(device.isValveConnected(valve)));
            }
        }
        // A thing type has either channel groups or plain channels, so on things with valves the device-level
        // channels sit in a group of their own.
        String prefix = family == Family.SENSOR ? "" : GROUP_DEVICE + ChannelUID.CHANNEL_GROUP_SEPARATOR;
        publishRadioStates(device, prefix);
        if (family != Family.IRRIGATION_CONTROL) {
            publish(prefix + CHANNEL_BATTERY_LEVEL, quantity(device.getBatteryLevel(), Units.PERCENT));
            publish(prefix + CHANNEL_FROST_WARNING, onOff(device.getFrostWarning()));
        }
        if (family == Family.SENSOR) {
            publish(CHANNEL_SOIL_MOISTURE, quantity(device.getSoilMoisture(), Units.PERCENT));
            publish(CHANNEL_SOIL_TEMPERATURE, quantity(device.getSoilTemperature(), SIUnits.CELSIUS));
            publish(CHANNEL_AMBIENT_TEMPERATURE, quantity(device.getAmbientTemperature(), SIUnits.CELSIUS));
            publish(CHANNEL_LIGHT_INTENSITY, quantity(device.getLightIntensity(), Units.LUX));
        } else if (family != Family.IRRIGATION_CONTROL) {
            publish(prefix + CHANNEL_TEMPERATURE, quantity(device.getTemperature(), SIUnits.CELSIUS));
        }
    }
}
