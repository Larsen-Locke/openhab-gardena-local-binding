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

/**
 * Configuration of a device thing underneath the gateway.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
public class GardenaDeviceConfiguration {

    /** Default for {@link #wateringDuration}, the same as the GARDENA app's. */
    public static final int DEFAULT_WATERING_DURATION_MINUTES = 30;

    /** The device's id on the gateway, 24 hex digits. */
    public String deviceId = "";

    /** How long a valve stays open when switched on, in minutes. Only used by things with valves. */
    public int wateringDuration = DEFAULT_WATERING_DURATION_MINUTES;
}
