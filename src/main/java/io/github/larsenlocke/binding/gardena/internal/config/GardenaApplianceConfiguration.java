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
 * Configuration of a power adapter, pump or mower thing, besides the device id read by every device handler. Each
 * thing type uses its own duration parameter only.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
public class GardenaApplianceConfiguration {

    /** Power adapter: how long the output stays on when switched on, in minutes; 0 = until switched off. */
    public int onDuration = 0;

    /** Pump: how long the pump runs when switched on, in minutes. */
    public int runDuration = 30;

    /** Mower: how long the mower mows when started, in hours. */
    public int mowingDuration = 3;
}
