/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.Device;
import com.homesynapse.device.DeviceRegistry;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.device.HardwareIdentifier;
import com.homesynapse.event.AvailabilityChangedEvent;
import com.homesynapse.event.DeviceAdoptedEvent;
import com.homesynapse.event.DeviceDiscoveredEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.UlidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The Doc 02 §3.12 discovery/adoption slice, scoped INSIDE the zigbee ingestion
 * layer (A13 — no core-module discovery-pipeline build): detection publishes
 * {@code device_discovered}; proposal dedups on the IEEE hardware identifier
 * against the {@link DeviceRegistry} ({@code (namespace="zigbee", value=IEEE)}
 * — a match re-links to the existing device with an {@code availability_changed},
 * NO new adoption event); adoption mints identity, registers device + entities
 * + capabilities through the existing registry surfaces, and publishes
 * {@code device_adopted}.
 *
 * <p><strong>Identity-UNGATED (DP-B, permanent):</strong> device/entity ULIDs
 * mint locally from the injected clock — no identity file, no adoption
 * dependence on identity infrastructure. All published event types PRE-EXIST
 * (zero mint); the adoption entry point is callable (tests/M9.4 API) with no
 * composition-root wiring here.
 *
 * <p>The {@link DeviceRegistry} arrives constructor-injected: it is not an
 * {@code IntegrationContext} component — the M9.4 wiring decides which
 * instance (the AB-3 in-memory MVP substrate at the composition root) this
 * slice receives.
 *
 * <p>Thread-safe ({@link ReentrantLock} only, LTD-11).
 */
final class ZigbeeAdoptionSlice {

    /** The zigbee hardware-identifier namespace (Doc 08 §5). */
    static final String HARDWARE_NAMESPACE = "zigbee";
    /** The identity sentinel for PARTIAL interviews with unreadable Basic strings. */
    static final String UNKNOWN_IDENTITY = "unknown";

    /** Stage-2 outcome of a discovery. */
    enum DiscoveryOutcome {
        /** IEEE matched an existing device: re-linked, no new adoption. */
        LINKED,
        /** No match: proposed for adoption. */
        PROPOSED
    }

    /**
     * An adoption result.
     *
     * @param deviceId the minted device id
     * @param entityIds the minted entity ids keyed by endpoint
     */
    record AdoptedDevice(DeviceId deviceId, Map<Integer, EntityId> entityIds) {
    }

    private record Proposal(InterviewResult interview, String matchedProfileId) {
    }

    private static final Logger log =
            LoggerFactory.getLogger(ZigbeeAdoptionSlice.class);

    private final IntegrationId integrationId;
    private final DeviceRegistry deviceRegistry;
    private final EntityRegistry entityRegistry;
    private final EventPublisher publisher;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Long, Proposal> proposals = new HashMap<>();
    private final Map<Long, DeviceId> devicesByIeee = new HashMap<>();
    private final Map<Long, Map<Integer, EntityId>> entitiesByIeee = new HashMap<>();

    /**
     * Creates the slice.
     *
     * @param integrationId this integration's identity, never {@code null}
     * @param deviceRegistry the device registry (the DeviceIdentifiers dedup
     *        surface), never {@code null}
     * @param entityRegistry the entity registry, never {@code null}
     * @param publisher the event publisher, never {@code null}
     * @param clock the time source (identity minting + event time), never {@code null}
     */
    ZigbeeAdoptionSlice(IntegrationId integrationId, DeviceRegistry deviceRegistry,
            EntityRegistry entityRegistry, EventPublisher publisher, Clock clock) {
        this.integrationId = Objects.requireNonNull(integrationId, "integrationId");
        this.deviceRegistry = Objects.requireNonNull(deviceRegistry,
                "deviceRegistry");
        this.entityRegistry = Objects.requireNonNull(entityRegistry,
                "entityRegistry");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Stage 1+2: detection and proposal. Publishes {@code device_discovered}
     * for a new device; re-links a known IEEE with {@code availability_changed}
     * and NO new adoption event (re-pairing after power loss).
     *
     * @param interview the interview result, never {@code null}
     * @param matchedProfileId the matched profile id; {@code null} when none
     * @return the stage-2 outcome
     */
    DiscoveryOutcome onDeviceDiscovered(InterviewResult interview,
            String matchedProfileId) {
        Objects.requireNonNull(interview, "interview");
        IEEEAddress ieee = interview.ieeeAddress();
        Optional<Device> existing = deviceRegistry.findByHardwareIdentifier(
                HARDWARE_NAMESPACE, ieee.toHexString());
        if (existing.isPresent()) {
            relink(ieee, existing.get());
            return DiscoveryOutcome.LINKED;
        }
        lock.lock();
        try {
            proposals.put(ieee.value(), new Proposal(interview, matchedProfileId));
        } finally {
            lock.unlock();
        }
        publishRoot(new EventDraft(
                EventTypes.DEVICE_DISCOVERED,
                1,
                clock.instant(),
                SubjectRef.integration(integrationId),
                EventPriority.NORMAL,
                EventOrigin.INTEGRATION,
                new DeviceDiscoveredEvent(
                        integrationId.value(),
                        ieee.toHexString(),
                        sentinel(interview.manufacturerName()),
                        sentinel(interview.modelIdentifier())),
                null,
                null));
        log.info("zigbee.device_proposed: device={} manufacturer={} model={} "
                        + "profile={} status={}",
                ieee, sentinel(interview.manufacturerName()),
                sentinel(interview.modelIdentifier()), matchedProfileId,
                interview.interviewStatus());
        return DiscoveryOutcome.PROPOSED;
    }

    /**
     * Stage 3: adoption. Mints identity, registers the device and its
     * classified entities, publishes {@code device_adopted}.
     *
     * @param ieee the proposed device, never {@code null}
     * @return the adoption result
     * @throws IllegalStateException if the device was never proposed
     */
    AdoptedDevice adopt(IEEEAddress ieee) {
        Objects.requireNonNull(ieee, "ieee");
        Proposal proposal;
        lock.lock();
        try {
            proposal = proposals.get(ieee.value());
        } finally {
            lock.unlock();
        }
        if (proposal == null) {
            throw new IllegalStateException("Device " + ieee + " has not been "
                    + "proposed; adoption requires a prior device_discovered");
        }
        InterviewResult interview = proposal.interview();

        DeviceId deviceId = new DeviceId(UlidFactory.generate(clock));
        String hex = ieee.toHexString();
        String displayName = sentinel(interview.manufacturerName()) + " "
                + sentinel(interview.modelIdentifier());
        deviceRegistry.createDevice(new Device(
                deviceId,
                "zigbee-" + hex.toLowerCase(Locale.ROOT),
                displayName,
                sentinel(interview.manufacturerName()),
                sentinel(interview.modelIdentifier()),
                null,
                null,
                null,
                integrationId,
                null,
                null,
                List.of(),
                Set.of(new HardwareIdentifier(HARDWARE_NAMESPACE, hex)),
                clock.instant()));

        Map<Integer, EntityId> entityIds = new HashMap<>();
        List<EntityId> created = new ArrayList<>();
        for (EndpointDescriptor endpoint : interview.endpoints()) {
            Optional<EndpointClassifier.Classification> classification =
                    EndpointClassifier.classify(endpoint);
            if (classification.isEmpty()) {
                log.warn("zigbee.endpoint_unclassified: device={} endpoint={} "
                                + "deviceType=0x{}; endpoint skipped", ieee,
                        endpoint.endpointId(),
                        Integer.toHexString(endpoint.deviceTypeId()));
                continue;
            }
            EntityId entityId = EntityId.of(UlidFactory.generate(clock));
            entityRegistry.createEntity(new Entity(
                    entityId,
                    "zigbee-" + hex.toLowerCase(Locale.ROOT) + "-ep"
                            + endpoint.endpointId(),
                    classification.get().entityType(),
                    displayName,
                    deviceId,
                    endpoint.endpointId(),
                    null,
                    true,
                    List.of(),
                    classification.get().capabilities(),
                    clock.instant()));
            entityIds.put(endpoint.endpointId(), entityId);
            created.add(entityId);
        }

        lock.lock();
        try {
            devicesByIeee.put(ieee.value(), deviceId);
            entitiesByIeee.put(ieee.value(), Map.copyOf(entityIds));
            proposals.remove(ieee.value());
        } finally {
            lock.unlock();
        }

        if (!created.isEmpty()) {
            publishRoot(new EventDraft(
                    EventTypes.DEVICE_ADOPTED,
                    1,
                    clock.instant(),
                    SubjectRef.device(deviceId),
                    EventPriority.NORMAL,
                    EventOrigin.INTEGRATION,
                    new DeviceAdoptedEvent(created.get(0).value()),
                    null,
                    null));
        }
        log.info("zigbee.device_adopted: device={} deviceId={} entities={}",
                ieee, deviceId, entityIds.size());
        return new AdoptedDevice(deviceId, Map.copyOf(entityIds));
    }

    /**
     * Resolves the adopted entity for a device endpoint (the ingestion link).
     *
     * @param ieee the device, never {@code null}
     * @param endpoint the application endpoint
     * @return the entity id, or empty before adoption
     */
    Optional<EntityId> entityFor(IEEEAddress ieee, int endpoint) {
        Objects.requireNonNull(ieee, "ieee");
        lock.lock();
        try {
            Map<Integer, EntityId> entities = entitiesByIeee.get(ieee.value());
            return entities == null ? Optional.empty()
                    : Optional.ofNullable(entities.get(endpoint));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resolves the adopted device id for an IEEE address.
     *
     * @param ieee the device, never {@code null}
     * @return the device id, or empty before adoption/link
     */
    Optional<DeviceId> deviceIdFor(IEEEAddress ieee) {
        Objects.requireNonNull(ieee, "ieee");
        lock.lock();
        try {
            return Optional.ofNullable(devicesByIeee.get(ieee.value()));
        } finally {
            lock.unlock();
        }
    }

    private void relink(IEEEAddress ieee, Device device) {
        lock.lock();
        try {
            devicesByIeee.put(ieee.value(), device.deviceId());
            if (!entitiesByIeee.containsKey(ieee.value())) {
                Map<Integer, EntityId> links = new HashMap<>();
                for (Entity entity : entityRegistry
                        .listEntitiesByDevice(device.deviceId())) {
                    links.put(entity.endpointIndex(), entity.entityId());
                }
                entitiesByIeee.put(ieee.value(), Map.copyOf(links));
            }
        } finally {
            lock.unlock();
        }
        publishRoot(new EventDraft(
                EventTypes.AVAILABILITY_CHANGED,
                1,
                clock.instant(),
                SubjectRef.device(device.deviceId()),
                EventPriority.NORMAL,
                EventOrigin.INTEGRATION,
                new AvailabilityChangedEvent("unknown", "available"),
                null,
                null));
        log.info("zigbee.device_relinked: device={} deviceId={} — re-pairing, "
                + "no new adoption", ieee, device.deviceId());
    }

    private void publishRoot(EventDraft draft) {
        try {
            publisher.publishRoot(draft);
        } catch (SequenceConflictException e) {
            log.error("zigbee.adoption_publish_conflict: type={}: {}",
                    draft.eventType(), e.getMessage());
        }
    }

    private static String sentinel(String value) {
        return value == null || value.isBlank() ? UNKNOWN_IDENTITY : value;
    }
}
