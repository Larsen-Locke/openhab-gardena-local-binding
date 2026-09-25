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

import java.net.Inet4Address;
import java.util.Set;

import javax.jmdns.ServiceInfo;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.mdns.MDNSDiscoveryParticipant;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;

/**
 * Finds GARDENA smart Gateways on the local network by the mDNS service they announce. The password is not
 * part of the announcement, so a discovered gateway still has to be given it before it can connect.
 *
 * @author Carsten Mogge - Initial contribution
 */
@NonNullByDefault
@Component(service = MDNSDiscoveryParticipant.class)
public class GardenaGatewayDiscoveryParticipant implements MDNSDiscoveryParticipant {

    @Override
    public Set<ThingTypeUID> getSupportedThingTypeUIDs() {
        return Set.of(THING_TYPE_GATEWAY);
    }

    @Override
    public String getServiceType() {
        return MDNS_SERVICE_TYPE;
    }

    @Override
    public @Nullable DiscoveryResult createResult(ServiceInfo service) {
        ThingUID thingUID = getThingUID(service);
        String host = hostOf(service);
        if (thingUID == null || host == null) {
            return null;
        }
        String name = gatewayName(service);
        // No port: the announcement is not documented to carry the WebSocket service's, so the configuration
        // default (8443, where websocketd listens) applies.
        return DiscoveryResultBuilder.create(thingUID)
                .withLabel("@text/discovery.gardenalocal.gateway.label [\"" + name + "\"]")
                .withProperty(CONFIG_HOST, host).withProperty(PROPERTY_GATEWAY_NAME, name)
                .withRepresentationProperty(CONFIG_HOST).build();
    }

    @Override
    public @Nullable ThingUID getThingUID(ServiceInfo service) {
        String name = gatewayName(service);
        return name.isEmpty() ? null : new ThingUID(THING_TYPE_GATEWAY, name.replaceAll("[^A-Za-z0-9_-]", "_"));
    }

    /**
     * The gateway's own name, e.g. "GARDENA-123456". The service instance name repeats the product name in
     * front of it ("GARDENA smart Gateway GARDENA-123456"); the host name is just the name.
     */
    private static String gatewayName(ServiceInfo service) {
        String server = service.getServer();
        if (server != null && !server.isBlank()) {
            String host = server.endsWith(".") ? server.substring(0, server.length() - 1) : server;
            host = host.endsWith(".local") ? host.substring(0, host.length() - ".local".length()) : host;
            if (!host.isBlank()) {
                return host;
            }
        }
        String name = service.getName();
        return name == null ? "" : name.trim();
    }

    private static @Nullable String hostOf(ServiceInfo service) {
        for (Inet4Address address : service.getInet4Addresses()) {
            return address.getHostAddress();
        }
        return null;
    }
}
