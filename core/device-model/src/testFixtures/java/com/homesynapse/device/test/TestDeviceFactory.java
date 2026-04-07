/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device.test;

import com.homesynapse.device.Device;
import com.homesynapse.device.HardwareIdentifier;
import com.homesynapse.platform.identity.AreaId;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.UlidFactory;

import java.time.Instant;
import java.util.List;

/**
 * Static factory methods and builder for creating {@link Device} instances
 * with sensible defaults in tests.
 *
 * <p>Without this class, every test that needs a {@code Device} must construct
 * the full 14-field record manually. This class provides one-liner defaults for
 * the common case and a builder for full customization.</p>
 *
 * <p>This class is a test fixture — it lives in the {@code testFixtures} source set
 * and is consumed by downstream modules via
 * {@code testFixtures(project(":core:device-model"))}.</p>
 *
 * @see Device
 * @see TestEntityFactory
 * @see TestCapabilityFactory
 */
public final class TestDeviceFactory {

    /** Default manufacturer for test devices. */
    private static final String DEFAULT_MANUFACTURER = "TestMfr";

    /** Default model for test devices. */
    private static final String DEFAULT_MODEL = "TestModel-1";

    /** Default firmware version for test devices. */
    private static final String DEFAULT_FIRMWARE_VERSION = "1.0.0";

    /** Default hardware version for test devices. */
    private static final String DEFAULT_HARDWARE_VERSION = "1.0";

    /** Fixed timestamp for deterministic tests. */
    private static final Instant DEFAULT_CREATED_AT =
            Instant.parse("2026-04-07T12:00:00Z");

    private TestDeviceFactory() {
        // Utility class — no instantiation.
    }

    // ──────────────────────────────────────────────────────────────────
    // Device creation
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates a minimal {@link Device} with all default values.
     *
     * <p>Each call generates a fresh {@link DeviceId} and {@link IntegrationId}
     * to ensure test isolation. Nullable fields ({@code areaId}, {@code viaDeviceId})
     * default to {@code null}.</p>
     *
     * @return a valid Device with sensible defaults
     */
    public static Device device() {
        return builder().build();
    }

    /**
     * Returns a builder for full customization of a {@link Device}.
     *
     * <p>All fields are pre-initialized to sensible defaults. Override only
     * the fields you need.</p>
     *
     * @return a new DeviceBuilder with default values
     */
    public static DeviceBuilder builder() {
        return new DeviceBuilder();
    }

    // ──────────────────────────────────────────────────────────────────
    // DeviceBuilder
    // ──────────────────────────────────────────────────────────────────

    /**
     * Mutable builder for {@link Device} instances with fluent API.
     *
     * <p>All 14 fields are pre-initialized to sensible defaults. Call setters
     * only for the fields you want to override, then call {@link #build()}.</p>
     */
    public static final class DeviceBuilder {

        private DeviceId deviceId;
        private String deviceSlug = "test-device";
        private String displayName = "Test Device";
        private String manufacturer = DEFAULT_MANUFACTURER;
        private String model = DEFAULT_MODEL;
        private String serialNumber = "SN-001";
        private String firmwareVersion = DEFAULT_FIRMWARE_VERSION;
        private String hardwareVersion = DEFAULT_HARDWARE_VERSION;
        private IntegrationId integrationId;
        private AreaId areaId;
        private DeviceId viaDeviceId;
        private List<String> labels = List.of();
        private List<HardwareIdentifier> hardwareIdentifiers;
        private Instant createdAt = DEFAULT_CREATED_AT;

        DeviceBuilder() {
            // Package-private — created via TestDeviceFactory.builder()
        }

        /** Sets the device identifier. */
        public DeviceBuilder deviceId(DeviceId deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        /** Sets the device slug. */
        public DeviceBuilder deviceSlug(String deviceSlug) {
            this.deviceSlug = deviceSlug;
            return this;
        }

        /** Sets the display name. */
        public DeviceBuilder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        /** Sets the manufacturer. */
        public DeviceBuilder manufacturer(String manufacturer) {
            this.manufacturer = manufacturer;
            return this;
        }

        /** Sets the model. */
        public DeviceBuilder model(String model) {
            this.model = model;
            return this;
        }

        /** Sets the serial number ({@code null} for devices that don't report it). */
        public DeviceBuilder serialNumber(String serialNumber) {
            this.serialNumber = serialNumber;
            return this;
        }

        /** Sets the firmware version ({@code null} for devices that don't report it). */
        public DeviceBuilder firmwareVersion(String firmwareVersion) {
            this.firmwareVersion = firmwareVersion;
            return this;
        }

        /** Sets the hardware version ({@code null} for devices that don't report it). */
        public DeviceBuilder hardwareVersion(String hardwareVersion) {
            this.hardwareVersion = hardwareVersion;
            return this;
        }

        /** Sets the integration identifier. */
        public DeviceBuilder integrationId(IntegrationId integrationId) {
            this.integrationId = integrationId;
            return this;
        }

        /** Sets the area identifier ({@code null} for unassigned devices). */
        public DeviceBuilder areaId(AreaId areaId) {
            this.areaId = areaId;
            return this;
        }

        /** Sets the via-device identifier ({@code null} for directly-connected devices). */
        public DeviceBuilder viaDeviceId(DeviceId viaDeviceId) {
            this.viaDeviceId = viaDeviceId;
            return this;
        }

        /** Sets the labels list. */
        public DeviceBuilder labels(List<String> labels) {
            this.labels = labels;
            return this;
        }

        /** Sets the hardware identifiers list. */
        public DeviceBuilder hardwareIdentifiers(List<HardwareIdentifier> hardwareIdentifiers) {
            this.hardwareIdentifiers = hardwareIdentifiers;
            return this;
        }

        /** Sets the creation timestamp. */
        public DeviceBuilder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        /**
         * Builds the {@link Device} record.
         *
         * <p>If {@code deviceId} or {@code integrationId} were not explicitly set,
         * fresh ULIDs are generated. If {@code hardwareIdentifiers} was not set,
         * a single test identifier is generated using the device slug.</p>
         *
         * @return a valid Device
         */
        public Device build() {
            DeviceId effectiveDeviceId = deviceId != null
                    ? deviceId : DeviceId.of(UlidFactory.generate());
            IntegrationId effectiveIntegrationId = integrationId != null
                    ? integrationId : IntegrationId.of(UlidFactory.generate());
            List<HardwareIdentifier> effectiveHwIds = hardwareIdentifiers != null
                    ? hardwareIdentifiers
                    : List.of(new HardwareIdentifier("test", "hw-" + deviceSlug));

            return new Device(
                    effectiveDeviceId,
                    deviceSlug,
                    displayName,
                    manufacturer,
                    model,
                    serialNumber,
                    firmwareVersion,
                    hardwareVersion,
                    effectiveIntegrationId,
                    areaId,
                    viaDeviceId,
                    labels,
                    effectiveHwIds,
                    createdAt);
        }
    }
}
