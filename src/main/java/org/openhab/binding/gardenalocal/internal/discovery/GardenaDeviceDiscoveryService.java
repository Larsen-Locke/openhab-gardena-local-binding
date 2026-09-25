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
package org.openhab.binding.gardenalocal.internal.discovery;

import static org.openhab.binding.gardenalocal.internal.GardenaLocalBindingConstants.*;

import java.time.Instant;
import java.util.Collection;

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
 * Proposes every supported device paired with the gateway. There is nothing to search for: the gateway's device
 * list is exact, so a scan fetches it again and every device on it is proposed, while background discovery
 * proposes from each device list the gateway handler receives anyway (on connect, and when a device is paired).
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

    public GardenaDeviceDiscoveryService() {
        super(GardenaGatewayHandler.class, DEVICE_THING_TYPES, SCAN_TIMEOUT_SECONDS, true);
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
        Instant listTime = Instant.now();
        ThingUID bridgeUID = thingHandler.getThing().getUID();
        for (GardenaDevice device : devices) {
            ThingTypeUID thingTypeUID = new ThingTypeUID(BINDING_ID, device.getModel().getFamily().getThingTypeId());
            String serialNumber = device.getSerialNumber();
            DiscoveryResultBuilder builder = DiscoveryResultBuilder
                    .create(new ThingUID(thingTypeUID, bridgeUID, device.getId())).withBridge(bridgeUID)
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
        // Devices unpaired from the gateway since the last list.
        removeOlderResults(listTime, bridgeUID);
    }
}
