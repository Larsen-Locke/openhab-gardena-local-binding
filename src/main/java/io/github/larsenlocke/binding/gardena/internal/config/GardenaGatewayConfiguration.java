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
package io.github.larsenlocke.binding.gardena.internal.config;

import org.eclipse.jdt.annotation.NonNullByDefault;

import io.github.larsenlocke.binding.gardena.internal.GardenaLocalBindingConstants;

/**
 * Configuration of the gateway bridge.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
public class GardenaGatewayConfiguration {

    /** IP address or host name of the gateway. */
    public String host = "";

    /** Port of the gateway's WebSocket service. */
    public int port = GardenaLocalBindingConstants.DEFAULT_PORT;

    /** The first eight characters of the gateway id printed on the back of the gateway. */
    public String password = "";

    /** How often the device list is read again, in minutes; 0 turns it off. */
    public int refreshInterval = 10;
}
