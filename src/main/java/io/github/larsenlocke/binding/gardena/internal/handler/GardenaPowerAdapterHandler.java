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
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
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
import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaRequests;

/**
 * A smart Power Adapter: an output that is switched on for a duration or until switched off.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
public class GardenaPowerAdapterHandler extends GardenaThingHandler {

    /** Longest on-duration accepted, one day; 0 means "until switched off". */
    static final int MAX_ON_MINUTES = 1440;

    private final Logger logger = LoggerFactory.getLogger(GardenaPowerAdapterHandler.class);

    /**
     * How long ON switches the output on, in seconds; 0 = until switched off. Starts at the configured value and
     * follows the duration channel; not persisted.
     */
    private volatile int onSeconds;

    public GardenaPowerAdapterHandler(Thing thing) {
        super(thing);
    }

    @Override
    protected boolean readConfiguration() {
        GardenaApplianceConfiguration config = getConfigAs(GardenaApplianceConfiguration.class);
        if (config.onDuration < 0 || config.onDuration > MAX_ON_MINUTES) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.invalid-on-duration [\"0\", \"" + MAX_ON_MINUTES + "\"]");
            return false;
        }
        onSeconds = config.onDuration * 60;
        return true;
    }

    @Override
    protected void handleDeviceCommand(ChannelUID channelUID, Command command) {
        switch (channelUID.getId()) {
            case CHANNEL_STATE -> {
                if (command instanceof OnOffType onOff) {
                    int seconds = onOff == OnOffType.OFF ? 0
                            : onSeconds > 0 ? onSeconds : GardenaRequests.POWER_ON_INDEFINITELY;
                    String action = seconds == 0 ? "switch the power adapter off"
                            : seconds == GardenaRequests.POWER_ON_INDEFINITELY ? "switch the power adapter on"
                                    : "switch the power adapter on for " + seconds + " s";
                    send(action, CHANNEL_STATE, device -> GardenaRequests.powerTimer(getDeviceId(), seconds));
                }
            }
            case CHANNEL_DURATION -> {
                // Only remembers the duration for the next ON, like a valve's duration channel.
                Integer seconds = durationSeconds(channelUID, command, Units.MINUTE, 0, MAX_ON_MINUTES * 60);
                if (seconds != null) {
                    onSeconds = seconds;
                    publish(CHANNEL_DURATION, new QuantityType<>(seconds / 60.0, Units.MINUTE));
                }
            }
            default -> logger.debug("Channel {} cannot be commanded", channelUID);
        }
    }

    @Override
    protected void publishStates(GardenaDevice device) {
        publish(CHANNEL_STATE, onOff(device.isPowerOn()));
        publish(CHANNEL_DURATION, new QuantityType<>(onSeconds / 60.0, Units.MINUTE));
        publishRadioStates(device, "");
    }
}
