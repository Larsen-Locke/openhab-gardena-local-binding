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
package org.openhab.binding.gardenalocal.internal.discovery;

import static org.openhab.binding.gardenalocal.internal.GardenaLocalBindingConstants.*;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.gardenalocal.internal.handler.GardenaGatewayHandler;
import org.openhab.binding.gardenalocal.internal.protocol.GardenaDevice;
import org.openhab.core.config.discovery.AbstractThingHandlerDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;

/**
 * Proposes every supported device paired with the gateway that has no thing yet. There is nothing to search for:
 * the gateway's device list is exact, so a scan fetches it again and proposes what is on it.
 *
 * <p>
 * Only while a scan started from the UI is running. Background discovery is off by default: the gateway handler
 * re-reads the device list every few minutes, and proposing from each of those would keep the Inbox busy for
 * devices set up long ago.
 *
 * <p>
 * This class deliberately does not implement {@link GardenaGatewayHandler.DeviceListListener}: openHAB registers
 * every service returned from {@code getServices()} under every interface it implements, and fails to enable
 * the bridge when one of those is not an OSGi service interface. {@link #listener} provides the callback instead.
 *
 * @author Carsten Mogge - Initial contribution
 */
@Component(scope = ServiceScope.PROTOTYPE, service = GardenaDeviceDiscoveryService.class)
@NonNullByDefault
public class GardenaDeviceDiscoveryService extends AbstractThingHandlerDiscoveryService<GardenaGatewayHandler> {

    private static final int SCAN_TIMEOUT_SECONDS = 15;

    private final GardenaGatewayHandler.DeviceListListener listener = this::onDeviceList;
    private volatile boolean scanning;
    /** What the last device list proposed, to withdraw devices that have since been unpaired. */
    private final Set<ThingUID> proposed = ConcurrentHashMap.newKeySet();

    public GardenaDeviceDiscoveryService() {
        super(GardenaGatewayHandler.class, DEVICE_THING_TYPES, SCAN_TIMEOUT_SECONDS, false);
    }

    @Override
    public void initialize() {
        thingHandler.registerDeviceListListener(listener);
        super.initialize();
    }

    @Override
    public void dispose() {
        super.dispose();
        thingHandler.unregisterDeviceListListener();
    }

    @Override
    protected void startScan() {
        scanning = true;
        thingHandler.refreshDeviceList();
    }

    @Override
    protected void stopScan() {
        scanning = false;
        super.stopScan();
    }

    @Override
    public void abortScan() {
        // AbstractDiscoveryService.abortScan() does not call stopScan().
        scanning = false;
        super.abortScan();
    }

    private void onDeviceList(Collection<GardenaDevice> devices) {
        if (!scanning && !isBackgroundDiscoveryEnabled()) {
            return;
        }
        ThingUID bridgeUID = thingHandler.getThing().getUID();
        Set<ThingUID> current = new HashSet<>();
        for (GardenaDevice device : devices) {
            ThingTypeUID thingTypeUID = new ThingTypeUID(BINDING_ID, device.getModel().getFamily().getThingTypeId());
            ThingUID thingUID = new ThingUID(thingTypeUID, bridgeUID, device.getId());
            if (thingHandler.hasThingFor(device.getId())) {
                // Proposing a device that already has a thing only gets the proposal auto-ignored, and with the
                // device list re-read every few minutes the Inbox then added and dropped it in turn. Any
                // proposal still there from before the thing was created goes as well.
                thingRemoved(thingUID);
                continue;
            }
            current.add(thingUID);
            String serialNumber = device.getSerialNumber();
            DiscoveryResultBuilder builder = DiscoveryResultBuilder.create(thingUID).withBridge(bridgeUID)
                    .withLabel("@text/discovery.gardenalocal.device.label [\"" + device.getModel().getProductName()
                            + "\", \"" + (serialNumber == null ? device.getId() : serialNumber) + "\"]")
                    .withProperty(CONFIG_DEVICE_ID, device.getId())
                    .withProperty(Thing.PROPERTY_MODEL_ID, device.getModel().getModelNumber())
                    .withRepresentationProperty(CONFIG_DEVICE_ID);
            if (serialNumber != null) {
                builder.withProperty(Thing.PROPERTY_SERIAL_NUMBER, serialNumber);
            }
            DiscoveryResult result = builder.build();
            thingDiscovered(result);
        }
        // Devices unpaired from the gateway since the last list. Withdrawn by id rather than by result age: on the
        // real gateway an age cut-off also dropped results the Inbox already held, whose timestamps it had kept.
        for (ThingUID thingUID : proposed) {
            if (!current.contains(thingUID)) {
                thingRemoved(thingUID);
            }
        }
        proposed.clear();
        proposed.addAll(current);
    }
}
