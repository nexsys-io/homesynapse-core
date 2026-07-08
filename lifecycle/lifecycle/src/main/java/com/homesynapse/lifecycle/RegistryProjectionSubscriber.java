/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.device.DeviceRegistry;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.event.DegradedEvent;
import com.homesynapse.event.DeviceRegisteredEvent;
import com.homesynapse.event.DeviceRemovedEvent;
import com.homesynapse.event.EntityRegisteredEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriptionFilter;
import com.homesynapse.platform.identity.DeviceId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * The bus-facing wrapper of the {@link RegistryProjection} (AMD-99 §5 /
 * REG-INV-1): consumes the three registration/removal event types and
 * delegates every apply to the single projection-apply path. Because the
 * in-memory registries start empty every boot, the composition root resets
 * this subscriber's checkpoint to 0 BEFORE registration, so every boot replays
 * the full registration history — the Phase-3 boot rebuild.
 *
 * <p>The subscriber NEVER publishes (INV-ES-09 — a pure projection: replay
 * and live delivery run the identical apply). The tombstone's device identity
 * rides the envelope's {@code SubjectRef.device(...)} (G-DUR7), extracted
 * here.</p>
 *
 * <p><strong>Positive evidence (anti-vacuous).</strong> On the AMD-42
 * TRANSITION&rarr;LIVE callback ({@link #onCaughtUp()}) this logs ONE
 * {@code registry.projection_live} INFO with device/entity counts and the
 * replay head position — the boot glance-point proving the rebuild ran (a
 * silently-succeedable arm ships positive evidence).</p>
 */
final class RegistryProjectionSubscriber implements Subscriber {

    /** The stable subscriber id — the checkpoint key the boot reset targets. */
    static final String SUBSCRIBER_ID = "registry_projection";

    private static final Logger LOG =
            LoggerFactory.getLogger(RegistryProjectionSubscriber.class);

    private final RegistryProjection projection;
    private final DeviceRegistry deviceRegistry;
    private final EntityRegistry entityRegistry;
    private volatile long lastAppliedPosition;

    /**
     * Creates the wrapper.
     *
     * @param projection the single apply path, never {@code null}
     * @param deviceRegistry the device registry (read-only here — count
     *        evidence for the LIVE INFO), never {@code null}
     * @param entityRegistry the entity registry (read-only here), never {@code null}
     */
    RegistryProjectionSubscriber(RegistryProjection projection,
            DeviceRegistry deviceRegistry, EntityRegistry entityRegistry) {
        this.projection = Objects.requireNonNull(projection, "projection");
        this.deviceRegistry = Objects.requireNonNull(deviceRegistry, "deviceRegistry");
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
    }

    /** @return the type filter for the three registration/removal event types. */
    static SubscriptionFilter subscriptionFilter() {
        return SubscriptionFilter.forTypes(
                EventTypes.DEVICE_REGISTERED,
                EventTypes.ENTITY_REGISTERED,
                EventTypes.DEVICE_REMOVED);
    }

    @Override
    public void onEvent(EventEnvelope event) {
        switch (event.payload()) {
            case DeviceRegisteredEvent registered ->
                    projection.applyDeviceRegistered(registered);
            case EntityRegisteredEvent registered ->
                    projection.applyEntityRegistered(registered);
            case DeviceRemovedEvent removed -> projection.applyDeviceRemoved(
                    new DeviceId(event.subjectRef().id()), removed);
            case DegradedEvent degraded ->
                    // A registration row that failed decode cannot rebuild its
                    // registry entry — loud, never silent (the registry view is
                    // missing a device the log intended to carry).
                    LOG.warn("registry.projection_degraded_event: type={} position={} "
                                    + "reason={}",
                            degraded.eventType(), event.globalPosition(),
                            degraded.failureReason());
            default -> LOG.warn("registry.projection_unexpected_payload: type={} "
                            + "payloadClass={} position={}",
                    event.eventType(), event.payload().getClass().getSimpleName(),
                    event.globalPosition());
        }
        lastAppliedPosition = event.globalPosition();
    }

    @Override
    public void onCaughtUp() {
        LOG.info("registry.projection_live: devices={} entities={} position={}",
                deviceRegistry.listAllDevices().size(),
                entityRegistry.listAllEntities().size(),
                lastAppliedPosition);
    }
}
