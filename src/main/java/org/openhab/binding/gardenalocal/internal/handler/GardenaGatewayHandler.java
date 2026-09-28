/**
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
package org.openhab.binding.gardenalocal.internal.handler;

import static org.openhab.binding.gardenalocal.internal.GardenaLocalBindingConstants.CONFIG_DEVICE_ID;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.openhab.binding.gardenalocal.internal.config.GardenaGatewayConfiguration;
import org.openhab.binding.gardenalocal.internal.discovery.GardenaDeviceDiscoveryService;
import org.openhab.binding.gardenalocal.internal.protocol.GardenaConnection;
import org.openhab.binding.gardenalocal.internal.protocol.GardenaDevice;
import org.openhab.binding.gardenalocal.internal.protocol.GardenaMessage;
import org.openhab.binding.gardenalocal.internal.protocol.GardenaRequests;
import org.openhab.core.io.net.http.WebSocketFactory;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;

/**
 * The GARDENA smart Gateway. Owns the WebSocket connection, keeps every paired device's data tree current and
 * hands each device's updates to the thing configured for it.
 *
 * <p>
 * The bridge only reports ONLINE once the gateway has returned its device list. An open WebSocket alone proves
 * little - the gateway accepts the connection before it has anything to say - and the child things need the
 * device list to know whether their device still exists.
 *
 * @author Carsten Mogge - Initial contribution
 */
@NonNullByDefault
public class GardenaGatewayHandler extends BaseBridgeHandler implements GardenaConnection.Listener {

    /** Receives the device list whenever the gateway reports it. Implemented for {@link GardenaDeviceDiscoveryService}. */
    public interface DeviceListListener {
        void onDeviceList(Collection<GardenaDevice> devices);
    }

    /** Covers both services answering; the reference implementation allows the same. */
    private static final Duration DEVICE_LIST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    /**
     * A device paired in the GARDENA app while connected shows up as a burst of events for an id this handler
     * does not know. The device list is fetched again once that burst is over rather than once per event.
     */
    private static final long UNKNOWN_DEVICE_DEBOUNCE_SECONDS = 3;
    private static final String WEBSOCKET_CONSUMER = "gardenalocal";

    private final Logger logger = LoggerFactory.getLogger(GardenaGatewayHandler.class);
    private final WebSocketFactory webSocketFactory;

    private volatile @Nullable GardenaConnection connection;
    private volatile @Nullable DeviceListListener deviceListListener;
    private final Map<String, GardenaDevice> devices = new ConcurrentHashMap<>();
    private @Nullable ScheduledFuture<?> deviceListJob;
    private volatile @Nullable ScheduledFuture<?> refreshJob;

    public GardenaGatewayHandler(Bridge bridge, WebSocketFactory webSocketFactory) {
        super(bridge);
        this.webSocketFactory = webSocketFactory;
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Set.of(GardenaDeviceDiscoveryService.class);
    }

    @Override
    public void initialize() {
        GardenaGatewayConfiguration config = getConfigAs(GardenaGatewayConfiguration.class);
        String host = config.host.trim();
        if (host.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.missing-host");
            return;
        }
        if (config.password.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.missing-password");
            return;
        }
        if (config.port < 1 || config.port > 65535) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.invalid-port");
            return;
        }
        if (config.refreshInterval < 0) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.invalid-refresh-interval");
            return;
        }
        URI uri;
        try {
            uri = new URI("wss", null, host, config.port, "/", null, null);
        } catch (URISyntaxException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.invalid-host");
            return;
        }

        updateStatus(ThingStatus.UNKNOWN);

        // The gateway serves a self-signed certificate issued to its own name, which no trust store knows and
        // which does not match an IP address. The password authenticates this side; nothing authenticates the
        // gateway, the same trade-off GARDENA's own Home Assistant integration makes.
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(true);
        sslContextFactory.setEndpointIdentificationAlgorithm(null);
        WebSocketClient client = webSocketFactory.createWebSocketClient(WEBSOCKET_CONSUMER, sslContextFactory);

        String credentials = "_:" + config.password.trim();
        String authorization = "Basic "
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        // The scheduler inherited from BaseThingHandler, which openHAB owns: this handler instance is reused
        // across a config change (dispose() then initialize()), so a private executor shut down in dispose()
        // would be gone for the second initialize().
        GardenaConnection newConnection = new GardenaConnection(client, uri, authorization, this, scheduler);
        connection = newConnection;
        newConnection.start();

        // The gateway pushes an event only when a value changes. A device reporting the same values again - a
        // sensor in steady weather, an idle irrigation control - refreshes only the timestamps kept on the
        // gateway, so without re-reading the list "last seen" would stay at the last change, hours or days old.
        if (config.refreshInterval > 0) {
            refreshJob = scheduler.scheduleWithFixedDelay(() -> requestDeviceList(false), config.refreshInterval,
                    config.refreshInterval, TimeUnit.MINUTES);
        }
    }

    @Override
    public void dispose() {
        ScheduledFuture<?> refresh = refreshJob;
        if (refresh != null) {
            refresh.cancel(false);
        }
        refreshJob = null;
        synchronized (this) {
            ScheduledFuture<?> job = deviceListJob;
            if (job != null) {
                job.cancel(false);
            }
            deviceListJob = null;
        }
        GardenaConnection currentConnection = connection;
        connection = null;
        if (currentConnection != null) {
            currentConnection.stop();
        }
        devices.clear();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // The gateway thing has no channels.
    }

    public void registerDeviceListListener(DeviceListListener listener) {
        deviceListListener = listener;
    }

    public void unregisterDeviceListListener() {
        deviceListListener = null;
    }

    /** The device with this id as last reported, or null if the gateway does not know it. */
    public @Nullable GardenaDevice getDevice(String deviceId) {
        return devices.get(deviceId);
    }

    /**
     * Whether a thing under this gateway is configured for the device, judged by its configuration: a thing's
     * configuration exists the moment it is created, before its handler has read it.
     */
    public boolean hasThingFor(String deviceId) {
        for (Thing child : getThing().getThings()) {
            if (child.getConfiguration().get(CONFIG_DEVICE_ID) instanceof String configured
                    && deviceId.equalsIgnoreCase(configured.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Asks the gateway for its device list again, e.g. for a discovery scan. */
    public void refreshDeviceList() {
        requestDeviceList(false);
    }

    /**
     * Sends a command frame and completes with the gateway's replies. Completes exceptionally when not connected,
     * or when the gateway does not answer within 10 seconds.
     */
    public CompletableFuture<List<GardenaMessage>> send(GardenaRequests.Frame frame) {
        GardenaConnection currentConnection = connection;
        if (currentConnection == null) {
            return CompletableFuture.failedFuture(new IOException("not connected to the gateway"));
        }
        return currentConnection.send(frame, COMMAND_TIMEOUT);
    }

    @Override
    public void onConnected() {
        // Off the WebSocket's thread: this callback runs inside Jetty's connect handling.
        scheduler.execute(() -> requestDeviceList(true));
    }

    @Override
    public void onDisconnected(String reason) {
        String description = reason.toLowerCase(Locale.ROOT).contains("refused")
                ? "@text/offline.comm-error.connection-refused"
                : "@text/offline.comm-error.connection-lost [\"" + reason.replace("\"", "'") + "\"]";
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, description);
    }

    @Override
    public void onAuthenticationFailed() {
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                "@text/offline.conf-error.password-rejected");
    }

    private void requestDeviceList(boolean afterConnect) {
        GardenaConnection currentConnection = connection;
        if (currentConnection == null || !currentConnection.isConnected()) {
            return;
        }
        currentConnection.send(GardenaRequests.discovery(), DEVICE_LIST_TIMEOUT).whenComplete((replies, error) -> {
            if (currentConnection != connection) {
                return; // disposed or reinitialized meanwhile
            }
            if (error != null) {
                String reason = describe(error);
                if (afterConnect) {
                    // Without its device list the gateway is of no use; a fresh connection is the best chance.
                    currentConnection.reconnect("the gateway did not return its device list (" + reason + ")");
                } else {
                    logger.debug("Refreshing the device list failed: {}", reason);
                }
                return;
            }
            deviceListReceived(currentConnection, replies, afterConnect);
        });
    }

    private void deviceListReceived(GardenaConnection currentConnection, List<GardenaMessage> replies,
            boolean afterConnect) {
        Map<String, GardenaDevice> reported = new HashMap<>();
        boolean anyAnswered = false;
        for (GardenaMessage reply : replies) {
            if (!reply.isSuccess()) {
                logger.debug("The gateway could not list the devices of {}: {}", reply.getService(),
                        reply.getErrorText());
                continue;
            }
            anyAnswered = true;
            for (Map.Entry<String, JsonElement> entry : reply.getPayload().entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                GardenaDevice device = GardenaDevice.fromDiscovery(entry.getKey(), entry.getValue().getAsJsonObject());
                if (device == null) {
                    logger.debug("Ignoring device {}: model {} is not supported", entry.getKey(),
                            GardenaDevice.modelNumberOf(entry.getValue().getAsJsonObject()));
                } else {
                    reported.put(device.getId(), device);
                }
            }
        }
        if (!anyAnswered) {
            if (afterConnect) {
                currentConnection.reconnect("the gateway refused to list its devices");
            }
            return;
        }

        List<String> vanished = new ArrayList<>(devices.keySet());
        vanished.removeAll(reported.keySet());
        vanished.forEach(devices::remove);
        devices.putAll(reported);
        logger.debug("The gateway reports {} supported device(s)", reported.size());

        currentConnection.markHealthy();
        updateStatus(ThingStatus.ONLINE);

        for (String deviceId : vanished) {
            for (GardenaDeviceHandler handler : findHandlers(deviceId)) {
                handler.deviceRemoved();
            }
        }
        for (GardenaDevice device : reported.values()) {
            for (GardenaDeviceHandler handler : findHandlers(device.getId())) {
                handler.deviceUpdated(device);
            }
        }
        DeviceListListener listener = deviceListListener;
        if (listener != null) {
            listener.onDeviceList(List.copyOf(reported.values()));
        }
    }

    @Override
    public void onMessages(List<GardenaMessage> messages) {
        Set<GardenaDevice> updated = new LinkedHashSet<>();
        for (GardenaMessage message : messages) {
            if (message.getKind() != GardenaMessage.Kind.EVENT) {
                logger.debug("Ignoring a reply nobody waits for any more: {}", message);
                continue;
            }
            String deviceId = message.getDevice();
            if (deviceId == null) {
                // Gateway-level events, e.g. devices announcing themselves for pairing.
                logger.trace("Ignoring {}", message);
                continue;
            }
            boolean deletesDevice = GardenaMessage.OP_DELETE.equals(message.getOp()) && message.getPath().isEmpty();
            GardenaDevice device = devices.get(deviceId);
            if (device == null) {
                if (!deletesDevice) {
                    scheduleDeviceListRefresh();
                }
            } else if (deletesDevice) {
                logger.debug("Device {} was removed from the gateway", deviceId);
                devices.remove(deviceId);
                updated.remove(device);
                for (GardenaDeviceHandler handler : findHandlers(deviceId)) {
                    handler.deviceRemoved();
                }
            } else {
                device.apply(message);
                updated.add(device);
            }
        }
        for (GardenaDevice device : updated) {
            for (GardenaDeviceHandler handler : findHandlers(device.getId())) {
                handler.deviceUpdated(device);
            }
        }
    }

    private synchronized void scheduleDeviceListRefresh() {
        ScheduledFuture<?> job = deviceListJob;
        if (job != null && !job.isDone()) {
            return; // one refresh per burst, not one per event
        }
        logger.debug("Event for an unknown device, fetching the device list again");
        deviceListJob = scheduler.schedule(() -> requestDeviceList(false), UNKNOWN_DEVICE_DEBOUNCE_SECONDS,
                TimeUnit.SECONDS);
    }

    private List<GardenaDeviceHandler> findHandlers(String deviceId) {
        List<GardenaDeviceHandler> handlers = new ArrayList<>();
        for (Thing child : getThing().getThings()) {
            if (child.getHandler() instanceof GardenaDeviceHandler handler && deviceId.equals(handler.getDeviceId())) {
                handlers.add(handler);
            }
        }
        return handlers;
    }

    private static String describe(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
