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
package io.github.larsenlocke.binding.gardena.internal.protocol;

import java.util.Locale;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * What a mower is doing, the same for both radio generations. The mapping from the raw values follows GARDENA's
 * gardena-smart-local-api library.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
public enum MowerActivity {
    UNKNOWN,
    PARKED,
    LEAVING,
    MOWING,
    PAUSED,
    RETURNING,
    CHARGING,
    ERROR;

    /** The channel value, e.g. "parked". */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * From a Gen1 mower's {@code lemonbeat/0/status}. The library's mapping, which differs from the order the
     * value list in the device schema's description suggests; the library's is the one its tests pin down.
     */
    static MowerActivity fromGen1Status(@Nullable Integer status) {
        if (status == null) {
            return UNKNOWN;
        }
        return switch (status) {
            case 7, 8, 16, 17, 18 -> PARKED; // week timer, by user, auto timer, day limit, frost
            case 4 -> LEAVING; // leaving the charging station
            case 1, 15 -> MOWING; // automatic, manual
            case 0, 5, 6, 9, 10, 14 -> PAUSED; // paused, waiting (download, power-up, stop pressed), main switch off
            case 2 -> RETURNING; // searching the charging station
            case 3 -> CHARGING;
            case 12, 13 -> ERROR; // error, error at power-up
            default -> UNKNOWN; // 11 is "unknown" on the mower itself
        };
    }

    /**
     * From a Gen2 mower's {@code mower_app/0/activity}, and {@code mower_app/0/state} for when the activity
     * itself says nothing.
     */
    static MowerActivity fromGen2(@Nullable Integer activity, @Nullable Integer state) {
        if (activity == null) {
            return UNKNOWN;
        }
        return switch (activity) {
            case 1 -> CHARGING;
            case 2 -> LEAVING; // going out
            case 3 -> MOWING;
            case 4 -> RETURNING; // going home
            case 5 -> PARKED;
            case 0 -> state == null ? UNKNOWN : switch (state) {
                case 5 -> PAUSED;
                case 3, 8 -> ERROR; // fatal error, error
                default -> UNKNOWN;
            };
            default -> UNKNOWN; // 6 is "stopped in garden"
        };
    }
}
