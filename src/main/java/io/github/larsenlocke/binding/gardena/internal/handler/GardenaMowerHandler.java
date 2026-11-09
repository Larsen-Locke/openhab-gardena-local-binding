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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.larsenlocke.binding.gardena.internal.config.GardenaApplianceConfiguration;
import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaDevice;
import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaModel.Generation;
import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaRequests;
import io.github.larsenlocke.binding.gardena.internal.protocol.MowerActivity;

/**
 * A SILENO robotic mower, first or second generation: started for a duration, parked until the next scheduled
 * start or until further notice, and (second generation) paused.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
public class GardenaMowerHandler extends GardenaThingHandler {

    static final String COMMAND_START = "start";
    static final String COMMAND_PARK_UNTIL_NEXT_SCHEDULE = "park-until-next-schedule";
    static final String COMMAND_PARK_UNTIL_FURTHER_NOTICE = "park-until-further-notice";
    static final String COMMAND_PAUSE = "pause";

    private final Logger logger = LoggerFactory.getLogger(GardenaMowerHandler.class);

    /** How long a start mows, in seconds. Starts at the configured value; not persisted. */
    private volatile int mowingSeconds = 3 * 3600;

    public GardenaMowerHandler(Thing thing) {
        super(thing);
    }

    @Override
    protected boolean readConfiguration() {
        GardenaApplianceConfiguration config = getConfigAs(GardenaApplianceConfiguration.class);
        int minHours = GardenaRequests.MIN_MOWING_SECONDS / 3600;
        int maxHours = GardenaRequests.MAX_MOWING_SECONDS / 3600;
        if (config.mowingDuration < minHours || config.mowingDuration > maxHours) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.invalid-mowing-duration [\"" + minHours + "\", \"" + maxHours + "\"]");
            return false;
        }
        mowingSeconds = config.mowingDuration * 3600;
        return true;
    }

    @Override
    protected void handleDeviceCommand(ChannelUID channelUID, Command command) {
        String id = getDeviceId();
        switch (channelUID.getId()) {
            case CHANNEL_COMMAND -> {
                String name = command.toString();
                switch (name) {
                    case COMMAND_START -> {
                        int seconds = mowingSeconds;
                        send("start mowing for " + seconds + " s", null,
                                device -> GardenaRequests.mowerStart(id, device.getModel(), seconds));
                    }
                    case COMMAND_PARK_UNTIL_NEXT_SCHEDULE -> send("park until the next scheduled start", null,
                            device -> GardenaRequests.mowerStart(id, device.getModel(), 0));
                    case COMMAND_PARK_UNTIL_FURTHER_NOTICE -> send("park until further notice", null,
                            device -> GardenaRequests.mowerParkUntilFurtherNotice(id, device.getModel()));
                    case COMMAND_PAUSE -> send("pause", null, device -> {
                        if (device.getModel().getGeneration() != Generation.GEN2) {
                            logger.warn("Cannot pause {}: only second-generation mowers can be paused, park it "
                                    + "instead", getThing().getUID());
                            return null;
                        }
                        return GardenaRequests.mowerPause(id, device.getModel());
                    });
                    default -> logger.warn("Ignoring unknown command '{}' for {}", name, channelUID);
                }
            }
            case CHANNEL_DURATION -> {
                Integer seconds = durationSeconds(channelUID, command, Units.HOUR, GardenaRequests.MIN_MOWING_SECONDS,
                        GardenaRequests.MAX_MOWING_SECONDS);
                if (seconds != null) {
                    mowingSeconds = seconds;
                    publish(CHANNEL_DURATION, new QuantityType<>(seconds / 3600.0, Units.HOUR));
                }
            }
            default -> logger.debug("Channel {} cannot be commanded", channelUID);
        }
    }

    @Override
    protected void publishStates(GardenaDevice device) {
        MowerActivity activity = device.getMowerActivity();
        publish(CHANNEL_ACTIVITY, activity == null ? null : new StringType(activity.value()));
        publish(CHANNEL_DURATION, new QuantityType<>(mowingSeconds / 3600.0, Units.HOUR));
        publish(CHANNEL_BATTERY_LEVEL, quantity(device.getBatteryLevel(), Units.PERCENT));
        publish(CHANNEL_ERROR_CODE, decimal(device.getMowerErrorCode()));
        publish(CHANNEL_CUTTING_TIME, quantity(device.getCuttingHours(), Units.HOUR));
        publish(CHANNEL_CHARGING_CYCLES, decimal(device.getChargingCycles()));
        publish(CHANNEL_COLLISIONS, decimal(device.getCollisions()));
        publishRadioStates(device, "");
    }
}
