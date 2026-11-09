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

import java.util.List;

import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.dimension.VolumetricFlowRate;
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
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.larsenlocke.binding.gardena.internal.config.GardenaApplianceConfiguration;
import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaDevice;
import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaRequests;

/**
 * A smart Automatic Home &amp; Garden Pump: runs for a duration when switched on (in scheduled mode), or starts by
 * itself when the outlet pressure drops (in automatic mode); reports pressure, temperature and flow.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
public class GardenaPumpHandler extends GardenaThingHandler {

    static final Unit<VolumetricFlowRate> LITRE_PER_HOUR = Units.LITRE.divide(Units.HOUR)
            .asType(VolumetricFlowRate.class);

    /** Channel values of the operating mode, indexed by the device's value. */
    static final List<String> OPERATING_MODES = List.of("scheduled", "automatic");
    /** Channel values of the dripping alert, indexed by the device's value (60 min, 2 min, off). */
    static final List<String> DRIPPING_ALERTS = List.of("standard", "sensitive", "off");
    /** Channel values of the pump state, indexed by the device's value. */
    static final List<String> PUMP_STATES = List.of("starting", "water-in-pump", "no-water-in-pump", "flow", "no-flow");

    /**
     * Channel values of the pump error, indexed by the device's {@code valve_error_1}: none, pump not filled, clean
     * the gasket, clean the fine mesh filter, small leakage.
     */
    static final List<String> PUMP_ERRORS = List.of("none", "pump-not-filled", "clean-gasket", "clean-fine-mesh",
            "small-leakage");

    static final String COMMAND_RESET_FLOW = "reset-flow";
    static final String COMMAND_RESET_ERRORS = "reset-errors";
    static final String COMMAND_RESET_TEMPERATURE_MIN_MAX = "reset-temperature-min-max";

    /** Range of the turn-on pressure the GARDENA app offers. */
    static final double MAX_TURN_ON_BAR = 10.0;

    private static final int AUTOMATIC = 1;

    private final Logger logger = LoggerFactory.getLogger(GardenaPumpHandler.class);

    /** How long ON runs the pump, in seconds. Starts at the configured value; not persisted. */
    private volatile int runSeconds = 30 * 60;

    public GardenaPumpHandler(Thing thing) {
        super(thing);
    }

    @Override
    protected boolean readConfiguration() {
        GardenaApplianceConfiguration config = getConfigAs(GardenaApplianceConfiguration.class);
        int minMinutes = GardenaRequests.MIN_PUMP_SECONDS / 60;
        int maxMinutes = GardenaRequests.MAX_PUMP_SECONDS / 60;
        if (config.runDuration < minMinutes || config.runDuration > maxMinutes) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.invalid-run-duration [\"" + minMinutes + "\", \"" + maxMinutes + "\"]");
            return false;
        }
        runSeconds = config.runDuration * 60;
        return true;
    }

    @Override
    protected void handleDeviceCommand(ChannelUID channelUID, Command command) {
        String id = getDeviceId();
        switch (channelUID.getId()) {
            case CHANNEL_STATE -> {
                if (command instanceof OnOffType onOff) {
                    int seconds = onOff == OnOffType.ON ? runSeconds : 0;
                    String action = seconds > 0 ? "run the pump for " + seconds + " s" : "stop the pump";
                    send(action, CHANNEL_STATE, device -> {
                        // In automatic mode the pump follows the outlet pressure and ignores manual starts and stops,
                        // so sending one would silently do nothing.
                        if (Integer.valueOf(AUTOMATIC).equals(device.getPumpOperatingMode())) {
                            logger.warn("Cannot {} of {}: it is in automatic mode and starts by itself; switch it to "
                                    + "scheduled mode first", action, getThing().getUID());
                            return null;
                        }
                        return GardenaRequests.pumpTimer(id, seconds);
                    });
                }
            }
            case CHANNEL_DURATION -> {
                Integer seconds = durationSeconds(channelUID, command, Units.MINUTE, GardenaRequests.MIN_PUMP_SECONDS,
                        GardenaRequests.MAX_PUMP_SECONDS);
                if (seconds != null) {
                    runSeconds = seconds;
                    publish(CHANNEL_DURATION, new QuantityType<>(seconds / 60.0, Units.MINUTE));
                }
            }
            case CHANNEL_OPERATING_MODE -> {
                int mode = OPERATING_MODES.indexOf(command.toString());
                if (mode < 0) {
                    logger.warn("Ignoring operating mode '{}' for {}: allowed are {}", command, channelUID,
                            OPERATING_MODES);
                    restoreState(CHANNEL_OPERATING_MODE);
                } else {
                    send("set the operating mode to " + command, CHANNEL_OPERATING_MODE,
                            device -> GardenaRequests.pumpOperatingMode(id, mode));
                }
            }
            case CHANNEL_DRIPPING_ALERT -> {
                int alert = DRIPPING_ALERTS.indexOf(command.toString());
                if (alert < 0) {
                    logger.warn("Ignoring dripping alert '{}' for {}: allowed are {}", command, channelUID,
                            DRIPPING_ALERTS);
                    restoreState(CHANNEL_DRIPPING_ALERT);
                } else {
                    send("set the dripping alert to " + command, CHANNEL_DRIPPING_ALERT,
                            device -> GardenaRequests.pumpDrippingAlert(id, alert));
                }
            }
            case CHANNEL_TURN_ON_PRESSURE -> {
                Double bar = toBar(command);
                if (bar == null || bar < 0 || bar > MAX_TURN_ON_BAR) {
                    logger.warn("Ignoring turn-on pressure {} for {}: allowed are 0 to {} bar", command, channelUID,
                            MAX_TURN_ON_BAR);
                    restoreState(CHANNEL_TURN_ON_PRESSURE);
                } else {
                    send("set the turn-on pressure to " + bar + " bar", CHANNEL_TURN_ON_PRESSURE,
                            device -> GardenaRequests.pumpTurnOnPressure(id, bar));
                }
            }
            case CHANNEL_COMMAND -> {
                String name = command.toString();
                int code = switch (name) {
                    case COMMAND_RESET_FLOW -> GardenaRequests.PUMP_RESET_FLOW;
                    case COMMAND_RESET_ERRORS -> GardenaRequests.PUMP_RESET_VALVE_ERRORS;
                    case COMMAND_RESET_TEMPERATURE_MIN_MAX -> GardenaRequests.PUMP_RESET_TEMPERATURE_MIN_MAX;
                    default -> -1;
                };
                if (code < 0) {
                    logger.warn("Ignoring unknown command '{}' for {}", name, channelUID);
                } else {
                    send(name, null, device -> GardenaRequests.gen1Command(id, code));
                }
            }
            default -> logger.debug("Channel {} cannot be commanded", channelUID);
        }
    }

    /** A quantity in any pressure unit, or a plain number meaning bar, rounded to 0.1 bar as the app does. */
    private static @Nullable Double toBar(Command command) {
        QuantityType<?> bar = null;
        if (command instanceof QuantityType<?> quantity) {
            bar = quantity.toUnit(Units.BAR);
        } else if (command instanceof DecimalType number) {
            bar = new QuantityType<>(number.toBigDecimal(), Units.BAR);
        }
        return bar == null ? null : Math.round(bar.doubleValue() * 10) / 10.0;
    }

    @Override
    protected void publishStates(GardenaDevice device) {
        publish(CHANNEL_STATE, onOff(device.isPumpRunning()));
        publish(CHANNEL_DURATION, new QuantityType<>(runSeconds / 60.0, Units.MINUTE));
        publish(CHANNEL_OPERATING_MODE, option(OPERATING_MODES, device.getPumpOperatingMode()));
        publish(CHANNEL_DRIPPING_ALERT, option(DRIPPING_ALERTS, device.getDrippingAlert()));
        publish(CHANNEL_PUMP_STATE, option(PUMP_STATES, device.getPumpState()));
        publish(CHANNEL_TURN_ON_PRESSURE, quantity(device.getTurnOnPressure(), Units.BAR));
        publish(CHANNEL_OUTLET_PRESSURE, quantity(device.getOutletPressure(), Units.BAR));
        publish(CHANNEL_OUTLET_TEMPERATURE, quantity(device.getOutletTemperature(), SIUnits.CELSIUS));
        publish(CHANNEL_FLOW_RATE, quantity(device.getFlowRate(), LITRE_PER_HOUR));
        publish(CHANNEL_FLOW_TOTAL, quantity(device.getFlowTotal(), SIUnits.CUBIC_METRE));
        publish(CHANNEL_FLOW_SINCE_RESET, quantity(device.getFlowSinceReset(), SIUnits.CUBIC_METRE));
        publish(CHANNEL_ERROR, option(PUMP_ERRORS, device.getValveError(1)));
        publish(CHANNEL_FROST_WARNING, onOff(device.getFrostWarning()));
        publishRadioStates(device, "");
    }

    /** The channel value for a device value, or null for one this binding does not know. */
    private static @Nullable State option(List<String> options, @Nullable Integer value) {
        return value == null || value < 0 || value >= options.size() ? null : new StringType(options.get(value));
    }
}
