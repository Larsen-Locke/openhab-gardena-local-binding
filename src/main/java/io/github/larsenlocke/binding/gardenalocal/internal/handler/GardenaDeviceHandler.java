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
package io.github.larsenlocke.binding.gardenalocal.internal.handler;

import static io.github.larsenlocke.binding.gardenalocal.internal.GardenaLocalBindingConstants.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
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

import io.github.larsenlocke.binding.gardenalocal.internal.config.GardenaDeviceConfiguration;
import io.github.larsenlocke.binding.gardenalocal.internal.protocol.GardenaDevice;
import io.github.larsenlocke.binding.gardenalocal.internal.protocol.GardenaMessage;
import io.github.larsenlocke.binding.gardenalocal.internal.protocol.GardenaModel;
import io.github.larsenlocke.binding.gardenalocal.internal.protocol.GardenaModel.Family;
import io.github.larsenlocke.binding.gardenalocal.internal.protocol.GardenaRequests;

/**
 * One GARDENA device underneath the gateway: a water control, dual water control, irrigation control or sensor.
 * All state comes from the gateway handler, which calls {@link #deviceUpdated} whenever the device reports
 * anything; commands go out through the gateway's connection.
 *
 * @author Carsten Mogge - Initial contribution
 */
@NonNullByDefault
public class GardenaDeviceHandler extends BaseThingHandler {

    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("[0-9A-F]{24}");
    private static final int MIN_WATERING_MINUTES = 1;
    private static final int MAX_WATERING_MINUTES = GardenaRequests.MAX_WATERING_SECONDS / 60;
    private static final String VENDOR = "GARDENA";

    private final Logger logger = LoggerFactory.getLogger(GardenaDeviceHandler.class);

    private volatile String deviceId = "";
    private volatile int wateringSeconds = GardenaDeviceConfiguration.DEFAULT_WATERING_DURATION_MINUTES * 60;

    /**
     * Last state published per channel id, used to answer REFRESH and to publish only what changed: every
     * event for the device - a link-quality report, say - otherwise republishes every channel.
     */
    private final Map<String, State> lastStates = new ConcurrentHashMap<>();

    /**
     * Durations set through a valve's duration channel, in seconds. Not persisted: after a restart every valve
     * starts from the thing's configured watering duration again.
     */
    private final Map<Integer, Integer> valveDurations = new ConcurrentHashMap<>();

    public GardenaDeviceHandler(Thing thing) {
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
        if (hasValves()
                && (config.wateringDuration < MIN_WATERING_MINUTES || config.wateringDuration > MAX_WATERING_MINUTES)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.invalid-watering-duration [\"" + MIN_WATERING_MINUTES + "\", \""
                            + MAX_WATERING_MINUTES + "\"]");
            return;
        }
        deviceId = id;
        wateringSeconds = config.wateringDuration * 60;
        lastStates.clear();
        valveDurations.clear();
        refreshFromGateway();
    }

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
                Integer seconds = toSeconds(command);
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

    /** A quantity in any time unit, or a plain number meaning minutes. */
    private static @Nullable Integer toSeconds(Command command) {
        if (command instanceof QuantityType<?> quantity) {
            QuantityType<?> inSeconds = quantity.toUnit(Units.SECOND);
            return inSeconds == null ? null : (int) Math.round(inSeconds.doubleValue());
        }
        if (command instanceof DecimalType number) {
            return (int) Math.round(number.doubleValue() * 60);
        }
        return null;
    }

    /** Opens a valve for {@code seconds}, or closes it for 0. */
    private void sendValveCommand(int valve, int seconds) {
        Bridge bridge = getBridge();
        GardenaGatewayHandler gateway = bridge != null && bridge.getHandler() instanceof GardenaGatewayHandler g ? g
                : null;
        GardenaDevice device = gateway == null ? null : gateway.getDevice(deviceId);
        if (gateway == null || device == null) {
            logger.warn("Cannot switch valve {} of {}: the gateway does not know the device right now", valve,
                    getThing().getUID());
            return;
        }
        if (valve < 1 || valve > device.getModel().getFamily().getValveCount()) {
            return;
        }
        GardenaRequests.Frame frame = seconds > 0
                ? GardenaRequests.openValve(deviceId, device.getModel(), valve, seconds)
                : GardenaRequests.closeValve(deviceId, device.getModel(), valve);
        String action = seconds > 0 ? "open valve " + valve + " for " + seconds + " s" : "close valve " + valve;
        logger.debug("Sending '{}' to {}", action, device);
        gateway.send(frame).whenComplete((replies, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause()
                        : error;
                logger.warn("Could not {} of {}: {}", action, getThing().getUID(),
                        cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
                restoreValveState(valve);
                return;
            }
            for (GardenaMessage reply : replies) {
                if (!reply.isSuccess()) {
                    logger.warn("The gateway refused to {} of {}: {}", action, getThing().getUID(),
                            reply.getErrorText());
                    restoreValveState(valve);
                }
            }
        });
    }

    /**
     * Puts the valve's switch back to what the device last reported. The item was already auto-updated to the
     * commanded state; after a failed command that would claim water is flowing when it is not, or the reverse.
     */
    private void restoreValveState(int valve) {
        String channelId = GROUP_VALVE_PREFIX + valve + ChannelUID.CHANNEL_GROUP_SEPARATOR + CHANNEL_VALVE_STATE;
        State state = lastStates.get(channelId);
        if (state != null) {
            updateState(channelId, state);
        }
    }

    private boolean hasValves() {
        return !THING_TYPE_SENSOR.equals(getThing().getThingTypeUID());
    }

    private void publishStates(GardenaDevice device) {
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
        Instant lastReport = device.getLastReport();
        publish(prefix + CHANNEL_LAST_SEEN,
                lastReport == null ? null : new DateTimeType(lastReport.atZone(ZoneId.systemDefault())));
        publish(prefix + CHANNEL_RF_LINK_QUALITY, quantity(device.getRfLinkQuality(), Units.PERCENT));
        publish(prefix + CHANNEL_SIGNAL_STRENGTH, quantity(device.getSignalStrength(), Units.DECIBEL_MILLIWATTS));
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

    /**
     * Publishes a state if it changed. A value the device has not reported is left alone rather than set to
     * UNDEF: most values arrive with the device list, the rest with the device's next report.
     */
    private void publish(String channelId, @Nullable State state) {
        if (state == null || getThing().getChannel(channelId) == null) {
            return;
        }
        if (!state.equals(lastStates.put(channelId, state))) {
            updateState(channelId, state);
        }
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

    private static @Nullable State onOff(@Nullable Boolean value) {
        return value == null ? null : OnOffType.from(value);
    }

    private static @Nullable State decimal(@Nullable Number value) {
        return value == null ? null : new DecimalType(value);
    }

    private static <Q extends javax.measure.Quantity<Q>> @Nullable State quantity(@Nullable Double value,
            Unit<Q> unit) {
        return value == null ? null : new QuantityType<>(value, unit);
    }
}
