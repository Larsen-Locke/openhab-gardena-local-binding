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
package io.github.larsenlocke.binding.gardena.internal;

import static io.github.larsenlocke.binding.gardena.internal.GardenaLocalBindingConstants.DEVICE_THING_TYPES;
import static io.github.larsenlocke.binding.gardena.internal.GardenaLocalBindingConstants.THING_TYPE_GATEWAY;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.io.net.http.WebSocketFactory;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.github.larsenlocke.binding.gardena.internal.handler.GardenaDeviceHandler;
import io.github.larsenlocke.binding.gardena.internal.handler.GardenaGatewayHandler;

/**
 * Creates the handlers for this binding's thing types.
 *
 * @author Carsten Mogge - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.gardenalocal", service = ThingHandlerFactory.class)
public class GardenaLocalHandlerFactory extends BaseThingHandlerFactory {

    private final WebSocketFactory webSocketFactory;

    @Activate
    public GardenaLocalHandlerFactory(@Reference WebSocketFactory webSocketFactory) {
        this.webSocketFactory = webSocketFactory;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return THING_TYPE_GATEWAY.equals(thingTypeUID) || DEVICE_THING_TYPES.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();
        if (THING_TYPE_GATEWAY.equals(thingTypeUID) && thing instanceof Bridge bridge) {
            return new GardenaGatewayHandler(bridge, webSocketFactory);
        }
        if (DEVICE_THING_TYPES.contains(thingTypeUID)) {
            return new GardenaDeviceHandler(thing);
        }
        return null;
    }
}
