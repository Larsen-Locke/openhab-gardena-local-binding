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

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

import javax.measure.Quantity;
import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.larsenlocke.binding.gardena.internal.config.GardenaDeviceConfiguration;
import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaDevice;
import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaMessage;
import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaModel;
import io.github.larsenlocke.binding.gardena.internal.protocol.GardenaRequests;

/**
 * What every GARDENA device thing underneath the gateway has in common. All state comes from the gateway handler,
 * which calls {@link #deviceUpdated} whenever the device reports anything; commands go out through the gateway's
 * connection. Subclasses decide which channels a device family has and what its commands send.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
public abstract class GardenaThingHandler extends BaseThingHandler {

    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("[0-9A-F]{24}");
    private static final String VENDOR = "GARDENA";

    private final Logger logger = LoggerFactory.getLogger(GardenaThingHandler.class);

    private volatile String deviceId = "";

    /**
     * Last state published per channel id, used to answer REFRESH and to publish only what changed: every
     * event for the device - a link-quality report, say - otherwise republishes every channel.
     */
    private final Map<String, State> lastStates = new ConcurrentHashMap<>();

    protected GardenaThingHandler(Thing thing) {
        super(thing);
    }

    /** The configured device id, upper case; "" before a valid configuration has been read. */
    public String getDeviceId() {
        return deviceId;
    }

    @Override
    public void initialize() {
        GardenaDeviceConfiguration config = getConfigAs(GardenaDeviceConfiguration.class);
        String id = config.deviceId.trim().toUpperCase(Locale.ROOT);
        if (!DEVICE_ID_PATTERN.matcher(id).matches()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.invalid-device-id");
            return;
        }
        if (!readConfiguration()) {
            return;
        }
        deviceId = id;
        lastStates.clear();
        refreshFromGateway();
    }

    /**
     * Reads the family's own configuration and resets what it remembers between commands. Returns false after
     * having set a CONFIGURATION_ERROR status for an invalid value.
     */
    protected abstract boolean readConfiguration();

    @Override
    public void dispose() {
        deviceId = "";
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (!deviceId.isEmpty()) {
            refreshFromGateway();
        }
    }

    /**
     * This thing has no connection of its own, so the gateway's status comes first. Once the gateway is online
     * it has the device list, and the device's own data decides.
     */
    private void refreshFromGateway() {
        Bridge bridge = getBridge();
        if (bridge == null || !(bridge.getHandler() instanceof GardenaGatewayHandler gateway)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED);
            return;
        }
        if (bridge.getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
            return;
        }
        GardenaDevice device = gateway.getDevice(deviceId);
        if (device == null) {
            deviceRemoved();
        } else {
            deviceUpdated(device);
        }
    }

    /** Called by the gateway whenever this device reported something. */
    public void deviceUpdated(GardenaDevice device) {
        if (deviceId.isEmpty()) {
            return; // not (validly) initialized
        }
        GardenaModel model = device.getModel();
        if (!getThing().getThingTypeUID().getId().equals(model.getFamily().getThingTypeId())) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.wrong-thing-type [\"" + model.getProductName() + "\"]");
            return;
        }
        updateDeviceProperties(device);
        if (Boolean.FALSE.equals(device.isOnline())) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/offline.comm-error.device-unreachable");
        } else if (getThing().getStatus() != ThingStatus.ONLINE) {
            // Checked first because this runs for every event the device sends.
            updateStatus(ThingStatus.ONLINE);
        }
        publishStates(device);
    }

    /** Called by the gateway when the device is no longer paired with it. */
    public void deviceRemoved() {
        if (!deviceId.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.GONE, "@text/offline.gone.device-not-paired");
        }
    }

    /** Publishes every channel of the family from the device's current data. */
    protected abstract void publishStates(GardenaDevice device);

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            // Nothing can be polled - devices report on their own schedule - so REFRESH replays the last value.
            State state = lastStates.get(channelUID.getId());
            if (state != null) {
                updateState(channelUID, state);
            }
            return;
        }
        handleDeviceCommand(channelUID, command);
    }

    /** Handles any command but REFRESH. */
    protected abstract void handleDeviceCommand(ChannelUID channelUID, Command command);

    /**
     * Sends what {@code build} makes of the device. If it cannot be sent, the gateway refuses it, or
     * {@code build} declines by returning null, {@code restoreChannelId} gets its last published state back:
     * its item was already auto-updated to the commanded state, which would then claim something the device is
     * not doing.
     *
     * @param action what the command does, for the log, e.g. "open valve 1 for 600 s"
     */
    protected void send(String action, @Nullable String restoreChannelId,
            Function<GardenaDevice, GardenaRequests.@Nullable Frame> build) {
        Bridge bridge = getBridge();
        GardenaGatewayHandler gateway = bridge != null && bridge.getHandler() instanceof GardenaGatewayHandler g ? g
                : null;
        GardenaDevice device = gateway == null ? null : gateway.getDevice(deviceId);
        if (gateway == null || device == null) {
            logger.warn("Cannot {} of {}: the gateway does not know the device right now", action, getThing().getUID());
            restoreState(restoreChannelId);
            return;
        }
        GardenaRequests.Frame frame = build.apply(device);
        if (frame == null) {
            restoreState(restoreChannelId);
            return;
        }
        logger.debug("Sending '{}' to {}", action, device);
        gateway.send(frame).whenComplete((replies, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause()
                        : error;
                logger.warn("Could not {} of {}: {}", action, getThing().getUID(),
                        cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
                restoreState(restoreChannelId);
                return;
            }
            for (GardenaMessage reply : replies) {
                if (!reply.isSuccess()) {
                    logger.warn("The gateway refused to {} of {}: {}", action, getThing().getUID(),
                            reply.getErrorText());
                    restoreState(restoreChannelId);
                }
            }
        });
    }

    /** Puts a channel back to the state last published for it, if any. */
    protected void restoreState(@Nullable String channelId) {
        State state = channelId == null ? null : lastStates.get(channelId);
        if (channelId != null && state != null) {
            updateState(channelId, state);
        }
    }

    /**
     * Publishes a state if it changed. A value the device has not reported is left alone rather than set to
     * UNDEF: most values arrive with the device list, the rest with the device's next report.
     */
    protected void publish(String channelId, @Nullable State state) {
        if (state == null || getThing().getChannel(channelId) == null) {
            return;
        }
        if (!state.equals(lastStates.put(channelId, state))) {
            updateState(channelId, state);
        }
    }

    /** {@code last-seen}, radio link quality (Gen1) and signal strength (Gen2), under {@code prefix}. */
    protected void publishRadioStates(GardenaDevice device, String prefix) {
        Instant lastReport = device.getLastReport();
        publish(prefix + CHANNEL_LAST_SEEN,
                lastReport == null ? null : new DateTimeType(lastReport.atZone(ZoneId.systemDefault())));
        publish(prefix + CHANNEL_RF_LINK_QUALITY, quantity(device.getRfLinkQuality(), Units.PERCENT));
        publish(prefix + CHANNEL_SIGNAL_STRENGTH, quantity(device.getSignalStrength(), Units.DECIBEL_MILLIWATTS));
    }

    private void updateDeviceProperties(GardenaDevice device) {
        Map<String, String> wanted = new HashMap<>();
        wanted.put(Thing.PROPERTY_VENDOR, VENDOR);
        wanted.put(Thing.PROPERTY_MODEL_ID, device.getModel().getModelNumber());
        putIfPresent(wanted, Thing.PROPERTY_SERIAL_NUMBER, device.getSerialNumber());
        putIfPresent(wanted, Thing.PROPERTY_FIRMWARE_VERSION, device.getSoftwareVersion());
        putIfPresent(wanted, Thing.PROPERTY_HARDWARE_VERSION, device.getHardwareVersion());
        Map<String, String> current = getThing().getProperties();
        if (wanted.entrySet().stream().anyMatch(e -> !e.getValue().equals(current.get(e.getKey())))) {
            Map<String, String> properties = editProperties();
            properties.putAll(wanted);
            updateProperties(properties);
        }
    }

    private static void putIfPresent(Map<String, String> map, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    /** A quantity in any time unit, or a plain number meaning {@code plainUnit}, in seconds. */
    protected static @Nullable Integer toSeconds(Command command, Unit<?> plainUnit) {
        if (command instanceof QuantityType<?> quantity) {
            QuantityType<?> inSeconds = quantity.toUnit(Units.SECOND);
            return inSeconds == null ? null : (int) Math.round(inSeconds.doubleValue());
        }
        if (command instanceof DecimalType number) {
            QuantityType<?> inSeconds = new QuantityType<>(number.toBigDecimal(), plainUnit).toUnit(Units.SECOND);
            return inSeconds == null ? null : (int) Math.round(inSeconds.doubleValue());
        }
        return null;
    }

    /**
     * The seconds a duration channel's command asks for, or null - logged - if the command is no duration or lies
     * outside {@code minSeconds} to {@code maxSeconds}.
     *
     * @param plainUnit what a plain number means, and the unit the allowed range is logged in
     */
    protected @Nullable Integer durationSeconds(ChannelUID channelUID, Command command, Unit<?> plainUnit,
            int minSeconds, int maxSeconds) {
        Integer seconds = toSeconds(command, plainUnit);
        if (seconds == null) {
            logger.debug("Ignoring command {} for {}: not a duration", command, channelUID);
            return null;
        }
        if (seconds < minSeconds || seconds > maxSeconds) {
            logger.warn("Ignoring duration {} for {}: allowed are {} to {}", command, channelUID,
                    new QuantityType<>(minSeconds, Units.SECOND).toUnit(plainUnit),
                    new QuantityType<>(maxSeconds, Units.SECOND).toUnit(plainUnit));
            return null;
        }
        return seconds;
    }

    protected static @Nullable State onOff(@Nullable Boolean value) {
        return value == null ? null : OnOffType.from(value);
    }

    protected static @Nullable State decimal(@Nullable Number value) {
        return value == null ? null : new DecimalType(value);
    }

    protected static <Q extends Quantity<Q>> @Nullable State quantity(@Nullable Double value, Unit<Q> unit) {
        return value == null ? null : new QuantityType<>(value, unit);
    }
}
