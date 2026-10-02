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
package io.github.larsenlocke.binding.gardena.internal.protocol;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * The GARDENA smart devices this binding understands, keyed by the model number the gateway reports in
 * {@code device/0/model_number} (the EAN suffix, not the article number printed on the box).
 *
 * <p>
 * Mowers, the pump and the power adapter are reported by the gateway too, but are not supported yet and are
 * therefore not listed here - a device with an unknown model number is simply not offered for discovery.
 *
 * @author Carsten Mogge - Initial contribution
 */
@NonNullByDefault
public enum GardenaModel {

    WATER_CONTROL_GEN1("18869", Family.WATER_CONTROL, Generation.GEN1, "smart Water Control"),
    WATER_CONTROL_GEN2("2812", Family.WATER_CONTROL, Generation.GEN2, "smart Water Control"),
    PIPELINE_WATER_CONTROL("2826", Family.WATER_CONTROL, Generation.GEN2, "smart Pipeline Water Control"),
    DUAL_WATER_CONTROL("2814", Family.DUAL_WATER_CONTROL, Generation.GEN2, "smart Dual Water Control"),
    IRRIGATION_CONTROL_GEN1("31653", Family.IRRIGATION_CONTROL, Generation.GEN1, "smart Irrigation Control"),
    IRRIGATION_CONTROL_GEN2("469", Family.IRRIGATION_CONTROL, Generation.GEN2, "smart Irrigation Control"),
    SENSOR("18845", Family.SENSOR, Generation.GEN1, "smart Sensor"),
    SENSOR_II("19040", Family.SENSOR, Generation.GEN1, "smart Sensor II");

    /** Which openHAB thing type a model is represented by, and how many valves that thing type has. */
    public enum Family {
        WATER_CONTROL("water-control", 1),
        DUAL_WATER_CONTROL("dual-water-control", 2),
        IRRIGATION_CONTROL("irrigation-control", 6),
        SENSOR("sensor", 0);

        private final String thingTypeId;
        private final int valveCount;

        Family(String thingTypeId, int valveCount) {
            this.thingTypeId = thingTypeId;
            this.valveCount = valveCount;
        }

        public String getThingTypeId() {
            return thingTypeId;
        }

        public int getValveCount() {
            return valveCount;
        }
    }

    /**
     * The radio stack a device uses. It decides which gateway service owns the device and where every value
     * lives in its data tree - the two generations share almost no resource paths.
     */
    public enum Generation {
        /** Classic Lemonbeat devices, everything under {@code lemonbeat/0/...}. */
        GEN1("lemonbeatd"),
        /** LwM2M devices with multi-instance {@code actuator}/{@code timeslot} objects. */
        GEN2("lwm2mserver");

        private final String service;

        Generation(String service) {
            this.service = service;
        }

        /** The gateway service requests for a device of this generation are addressed to. */
        public String getService() {
            return service;
        }
    }

    private final String modelNumber;
    private final Family family;
    private final Generation generation;
    private final String productName;

    GardenaModel(String modelNumber, Family family, Generation generation, String productName) {
        this.modelNumber = modelNumber;
        this.family = family;
        this.generation = generation;
        this.productName = productName;
    }

    public String getModelNumber() {
        return modelNumber;
    }

    public Family getFamily() {
        return family;
    }

    public Generation getGeneration() {
        return generation;
    }

    /** GARDENA's product name, e.g. "smart Irrigation Control". */
    public String getProductName() {
        return productName;
    }

    public static @Nullable GardenaModel fromModelNumber(@Nullable String modelNumber) {
        if (modelNumber == null) {
            return null;
        }
        for (GardenaModel model : values()) {
            if (model.modelNumber.equals(modelNumber)) {
                return model;
            }
        }
        return null;
    }
}
