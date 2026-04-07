/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device.test;

import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityType;
import com.homesynapse.platform.identity.AreaId;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.UlidFactory;

import java.time.Instant;
import java.util.List;

/**
 * Static factory methods and builder for creating {@link Entity} instances
 * with sensible defaults in tests.
 *
 * <p>Without this class, every test that needs an {@code Entity} must construct
 * the full 11-field record manually. This class provides one-liner defaults for
 * the common case, convenience methods for common entity types ({@link #light()},
 * {@link #sensor()}, {@link #binarySensor()}), and a builder for full
 * customization.</p>
 *
 * <p>Convenience methods produce entities with appropriate {@link EntityType} and
 * pre-wired {@link CapabilityInstance} lists matching the capability requirements
 * documented in the device model (Doc 02 §3). For example, {@link #light()} creates
 * a LIGHT entity with OnOff capability; {@link #sensor()} creates a SENSOR with
 * TemperatureMeasurement.</p>
 *
 * <p>This class is a test fixture — it lives in the {@code testFixtures} source set
 * and is consumed by downstream modules via
 * {@code testFixtures(project(":core:device-model"))}.</p>
 *
 * @see Entity
 * @see TestDeviceFactory
 * @see TestCapabilityFactory
 */
public final class TestEntityFactory {

    /** Fixed timestamp for deterministic tests. */
    private static final Instant DEFAULT_CREATED_AT =
            Instant.parse("2026-04-07T12:00:00Z");

    private TestEntityFactory() {
        // Utility class — no instantiation.
    }

    // ──────────────────────────────────────────────────────────────────
    // Entity creation — generic default
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates a minimal {@link Entity} with all default values.
     *
     * <p>Defaults to {@link EntityType#LIGHT} with no capabilities attached.
     * Each call generates a fresh {@link EntityId} and {@link DeviceId}.</p>
     *
     * @return a valid Entity with sensible defaults
     */
    public static Entity entity() {
        return builder().build();
    }

    // ──────────────────────────────────────────────────────────────────
    // Convenience methods for common entity types
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates a LIGHT entity with an OnOff {@link CapabilityInstance}.
     *
     * <p>LIGHT requires OnOff per Doc 02 §3. Optional Brightness and
     * ColorTemperature capabilities can be added via the builder.</p>
     *
     * @return a LIGHT entity with OnOff capability
     */
    public static Entity light() {
        return builder()
                .entitySlug("test-light")
                .displayName("Test Light")
                .entityType(EntityType.LIGHT)
                .capabilities(List.of(TestCapabilityFactory.onOffInstance()))
                .build();
    }

    /**
     * Creates a SENSOR entity with a TemperatureMeasurement
     * {@link CapabilityInstance}.
     *
     * <p>SENSOR requires at least one measurement capability per Doc 02 §3.</p>
     *
     * @return a SENSOR entity with TemperatureMeasurement capability
     */
    public static Entity sensor() {
        return builder()
                .entitySlug("test-sensor")
                .displayName("Test Sensor")
                .entityType(EntityType.SENSOR)
                .capabilities(List.of(
                        TestCapabilityFactory.temperatureMeasurementInstance()))
                .build();
    }

    /**
     * Creates a BINARY_SENSOR entity with a Contact {@link CapabilityInstance}.
     *
     * <p>BINARY_SENSOR requires at least one of BinaryState, Contact, Motion,
     * or Occupancy per Doc 02 §3.</p>
     *
     * @return a BINARY_SENSOR entity with Contact capability
     */
    public static Entity binarySensor() {
        return builder()
                .entitySlug("test-binary-sensor")
                .displayName("Test Binary Sensor")
                .entityType(EntityType.BINARY_SENSOR)
                .capabilities(List.of(TestCapabilityFactory.contactInstance()))
                .build();
    }

    /**
     * Returns a builder for full customization of an {@link Entity}.
     *
     * <p>All fields are pre-initialized to sensible defaults. Override only
     * the fields you need.</p>
     *
     * @return a new EntityBuilder with default values
     */
    public static EntityBuilder builder() {
        return new EntityBuilder();
    }

    // ──────────────────────────────────────────────────────────────────
    // EntityBuilder
    // ──────────────────────────────────────────────────────────────────

    /**
     * Mutable builder for {@link Entity} instances with fluent API.
     *
     * <p>All 11 fields are pre-initialized to sensible defaults. Call setters
     * only for the fields you want to override, then call {@link #build()}.</p>
     */
    public static final class EntityBuilder {

        private EntityId entityId;
        private String entitySlug = "test-entity";
        private EntityType entityType = EntityType.LIGHT;
        private String displayName = "Test Entity";
        private DeviceId deviceId;
        private int endpointIndex = 1;
        private AreaId areaId;
        private boolean enabled = true;
        private List<String> labels = List.of();
        private List<CapabilityInstance> capabilities = List.of();
        private Instant createdAt = DEFAULT_CREATED_AT;

        EntityBuilder() {
            // Package-private — created via TestEntityFactory.builder()
        }

        /** Sets the entity identifier. */
        public EntityBuilder entityId(EntityId entityId) {
            this.entityId = entityId;
            return this;
        }

        /** Sets the entity slug. */
        public EntityBuilder entitySlug(String entitySlug) {
            this.entitySlug = entitySlug;
            return this;
        }

        /** Sets the entity type. */
        public EntityBuilder entityType(EntityType entityType) {
            this.entityType = entityType;
            return this;
        }

        /** Sets the display name. */
        public EntityBuilder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        /** Sets the device identifier ({@code null} for helper entities). */
        public EntityBuilder deviceId(DeviceId deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        /** Sets the endpoint index. */
        public EntityBuilder endpointIndex(int endpointIndex) {
            this.endpointIndex = endpointIndex;
            return this;
        }

        /** Sets the area identifier ({@code null} to inherit from device). */
        public EntityBuilder areaId(AreaId areaId) {
            this.areaId = areaId;
            return this;
        }

        /** Sets whether the entity is enabled. */
        public EntityBuilder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /** Sets the labels list. */
        public EntityBuilder labels(List<String> labels) {
            this.labels = labels;
            return this;
        }

        /** Sets the capabilities list. */
        public EntityBuilder capabilities(List<CapabilityInstance> capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        /** Sets the creation timestamp. */
        public EntityBuilder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        /**
         * Builds the {@link Entity} record.
         *
         * <p>If {@code entityId} was not explicitly set, a fresh ULID is generated.
         * If {@code deviceId} was not explicitly set, a fresh ULID is generated
         * (non-null default — set to {@code null} explicitly for helper entities).</p>
         *
         * @return a valid Entity
         */
        public Entity build() {
            EntityId effectiveEntityId = entityId != null
                    ? entityId : EntityId.of(UlidFactory.generate());
            DeviceId effectiveDeviceId = deviceId != null
                    ? deviceId : DeviceId.of(UlidFactory.generate());

            return new Entity(
                    effectiveEntityId,
                    entitySlug,
                    entityType,
                    displayName,
                    effectiveDeviceId,
                    endpointIndex,
                    areaId,
                    enabled,
                    labels,
                    capabilities,
                    createdAt);
        }
    }
}
