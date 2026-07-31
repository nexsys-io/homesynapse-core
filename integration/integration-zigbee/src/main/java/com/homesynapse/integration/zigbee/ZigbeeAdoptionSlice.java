/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.Device;
import com.homesynapse.device.DeviceRegistry;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.device.HardwareIdentifier;
import com.homesynapse.device.RegistryEventMapper;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.event.DeviceAdoptedEvent;
import com.homesynapse.event.DeviceDiscoveredEvent;
import com.homesynapse.event.DeviceRegisteredEvent;
import com.homesynapse.event.EntityRegisteredEvent;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Doc 02 §3.12 discovery/adoption slice, scoped INSIDE the zigbee ingestion
 * layer (A13 — no core-module discovery-pipeline build): detection publishes
 * {@code device_discovered}; proposal dedups on the IEEE hardware identifier
 * against the {@link DeviceRegistry} ({@code (namespace="zigbee", value=IEEE)}
 * — a match re-links to the existing device with an {@code availability_changed},
 * NO new adoption event); adoption mints identity, publishes the AMD-99
 * full-fidelity registration facts ({@code device_registered}, then one
 * {@code entity_registered} per classified endpoint — durable BEFORE the
 * in-memory apply, REG-INV-1's write-ahead clause), applies each through the
 * single {@link RegistryProjection} path (never a direct registry write), and
 * publishes the existing {@code device_adopted} LAST, byte-unchanged.
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
    /** The stale-offer horizon (N-8, M9.4b §6.6): matches the sleepy-interview horizon. */
    static final Duration PROPOSAL_MAX_AGE = Duration.ofHours(24);

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

    private record Proposal(InterviewResult interview, String matchedProfileId,
            Instant offeredAt) {
    }

    private static final Logger log =
            LoggerFactory.getLogger(ZigbeeAdoptionSlice.class);

    /** An entity's protocol-side binding (the §3.3 command-path identity join). */
    record EntityBinding(IEEEAddress ieee, int endpoint) {
    }

    private final IntegrationId integrationId;
    private final DeviceRegistry deviceRegistry;
    private final EntityRegistry entityRegistry;
    private final RegistryProjection registryProjection;
    private final DeviceProfileRegistry profileRegistry;
    private final EventPublisher publisher;
    private final Clock clock;
    /**
     * The M9.7-W2 §4 learned-zoneType seam: the wire-learned IAS zone type for
     * a device, empty when none was learned. Consulted at classification time
     * for IAS-bearing endpoints only; reads in-memory learned state — never
     * blocks, never performs I/O.
     */
    private final Function<IEEEAddress, Optional<ZoneType>> zoneTypeSource;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Long, Proposal> proposals = new HashMap<>();
    private final Map<Long, DeviceId> devicesByIeee = new HashMap<>();
    private final Map<Long, Map<Integer, EntityId>> entitiesByIeee = new HashMap<>();
    private final Map<EntityId, EntityBinding> bindingsByEntity = new HashMap<>();
    private final Map<Long, String> profilesByIeee = new HashMap<>();
    /** The F-8 invalidation listener; null until the adapter wires it. */
    private Consumer<IEEEAddress> adoptionListener;

    /**
     * Creates the slice with no zone-type source — IAS classification takes
     * the motion fallback (byte-equivalent pre-M9.7-W2 semantics; callers with
     * no wire-learn surface).
     *
     * @param integrationId this integration's identity, never {@code null}
     * @param deviceRegistry the device registry, never {@code null}
     * @param entityRegistry the entity registry, never {@code null}
     * @param registryProjection the single registry-apply path, never {@code null}
     * @param profileRegistry the device-profile registry, never {@code null}
     * @param publisher the event publisher, never {@code null}
     * @param clock the time source, never {@code null}
     */
    ZigbeeAdoptionSlice(IntegrationId integrationId, DeviceRegistry deviceRegistry,
            EntityRegistry entityRegistry, RegistryProjection registryProjection,
            DeviceProfileRegistry profileRegistry,
            EventPublisher publisher, Clock clock) {
        this(integrationId, deviceRegistry, entityRegistry, registryProjection,
                profileRegistry, publisher, clock, ieee -> Optional.empty());
    }

    /**
     * Creates the slice.
     *
     * @param integrationId this integration's identity, never {@code null}
     * @param deviceRegistry the device registry (the DeviceIdentifiers dedup
     *        surface), never {@code null}
     * @param entityRegistry the entity registry, never {@code null}
     * @param registryProjection the single registry-apply path (AMD-99 /
     *        REG-INV-1) — adoption-time writes use the SAME apply function the
     *        boot rebuild replays through, never {@code null}
     * @param profileRegistry the device-profile registry (resolves the matched
     *        profile id to its AMD-97 confirmation characterizations — DP-a),
     *        never {@code null}
     * @param publisher the event publisher, never {@code null}
     * @param clock the time source (identity minting + event time), never {@code null}
     * @param zoneTypeSource the wire-learned IAS zone-type view (M9.7-W2 §4 —
     *        in-memory read only, never blocking, never I/O; empty means
     *        unlearned and classification takes the DP-6 motion fallback),
     *        never {@code null}
     */
    ZigbeeAdoptionSlice(IntegrationId integrationId, DeviceRegistry deviceRegistry,
            EntityRegistry entityRegistry, RegistryProjection registryProjection,
            DeviceProfileRegistry profileRegistry,
            EventPublisher publisher, Clock clock,
            Function<IEEEAddress, Optional<ZoneType>> zoneTypeSource) {
        this.integrationId = Objects.requireNonNull(integrationId, "integrationId");
        this.deviceRegistry = Objects.requireNonNull(deviceRegistry,
                "deviceRegistry");
        this.entityRegistry = Objects.requireNonNull(entityRegistry,
                "entityRegistry");
        this.registryProjection = Objects.requireNonNull(registryProjection,
                "registryProjection");
        this.profileRegistry = Objects.requireNonNull(profileRegistry,
                "profileRegistry");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zoneTypeSource = Objects.requireNonNull(zoneTypeSource,
                "zoneTypeSource");
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
            // DP-B1 (M9.5-DURb): LINKED requires at least one registered entity.
            // A device with NONE is the durable half-registration state (a death
            // between the device_registered publish and its entities' publishes)
            // — re-linking it would freeze the hole forever, because no removal
            // emitter exists and boot replay faithfully rebuilds it. The repair
            // is the normal proposal/adoption flow: fall through to propose, and
            // adopt() reuses the durable deviceId (DP-B2) so the re-emit is
            // log-clean idempotent (AMD-99 F1). Registry reads happen OUTSIDE
            // the slice lock (the existing lock discipline).
            if (!entityRegistry.listEntitiesByDevice(
                    existing.get().deviceId()).isEmpty()) {
                relink(ieee, existing.get(), matchedProfileId);
                return DiscoveryOutcome.LINKED;
            }
            log.warn("zigbee.half_registration_detected: device={} deviceId={} "
                            + "— re-proposing for repair",
                    ieee, existing.get().deviceId());
        }
        Instant offeredAt = clock.instant();
        lock.lock();
        try {
            // N-8 (M9.4b §6.6): a superseding discovery REPLACES the entry —
            // the put refreshes offeredAt, restarting the stale-offer horizon.
            proposals.put(ieee.value(),
                    new Proposal(interview, matchedProfileId, offeredAt));
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
     * @throws IllegalStateException if the device was never proposed, a
     *         concurrent adoption already claimed the proposal, or the
     *         proposal is older than {@link #PROPOSAL_MAX_AGE}
     */
    AdoptedDevice adopt(IEEEAddress ieee) {
        Objects.requireNonNull(ieee, "ieee");
        Proposal proposal;
        lock.lock();
        try {
            // F-11 (M9.4b §6.5): the remove IS the claim — atomic under the
            // lock, so a racing second adopt() finds no proposal and gets the
            // ISE below. A claimed proposal is never re-inserted on a
            // downstream failure (stale below, registry throw): the device
            // re-announces/re-interviews naturally, minting a fresh proposal.
            proposal = proposals.remove(ieee.value());
        } finally {
            lock.unlock();
        }
        if (proposal == null) {
            throw new IllegalStateException("Device " + ieee + " has not been "
                    + "proposed; adoption requires a prior device_discovered");
        }
        Duration age = Duration.between(proposal.offeredAt(), clock.instant());
        if (age.compareTo(PROPOSAL_MAX_AGE) > 0) {
            throw new IllegalStateException("Proposal for device " + ieee
                    + " is stale: offered " + age + " ago, maximum is "
                    + PROPOSAL_MAX_AGE + "; adoption requires a fresh "
                    + "device_discovered");
        }
        InterviewResult interview = proposal.interview();

        String hex = ieee.toHexString();
        // DP-B2 (M9.5-DURb): identity continuity — a hardware-id match here is
        // the half-registration repair (DP-B1 re-proposed a registry-known
        // device with zero entities). REUSING the durable deviceId makes the
        // device_registered re-emit idempotent-by-identity (REG-INV-1: apply
        // upserts on the same id — AMD-99 F1); entities mint fresh ids below
        // (none were ever durable — nothing to preserve). The read races
        // nothing: the proposal claim above is already atomic (F-11).
        DeviceId deviceId = deviceRegistry
                .findByHardwareIdentifier(HARDWARE_NAMESPACE, hex)
                .map(Device::deviceId)
                .orElseGet(() -> new DeviceId(UlidFactory.generate(clock)));
        String displayName = sentinel(interview.manufacturerName()) + " "
                + sentinel(interview.modelIdentifier());
        // AMD-99 §3 (DP-3): publish the full-fidelity registration fact FIRST
        // (durable at publishRoot return — the write-ahead half of REG-INV-1),
        // THEN apply through the single projection path. The apply consumes only
        // the payload, so the adoption-time write and the boot replay run the
        // IDENTICAL function; the live bus self-delivery of this very event is a
        // no-op by DP-8 idempotency. Causal order: device before its entities.
        DeviceRegisteredEvent deviceRegistered = RegistryEventMapper.toPayload(
                new Device(
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
        publishRegistration(new EventDraft(
                EventTypes.DEVICE_REGISTERED,
                1,
                clock.instant(),
                SubjectRef.device(deviceId),
                EventPriority.NORMAL,
                EventOrigin.INTEGRATION,
                deviceRegistered,
                null,
                null));
        registryProjection.applyDeviceRegistered(deviceRegistered);

        Map<Integer, EntityId> entityIds = new HashMap<>();
        List<EntityId> created = new ArrayList<>();
        for (EndpointDescriptor endpoint : interview.endpoints()) {
            // §4 (M9.7-W2): IAS-bearing endpoints classify under the wire-learned
            // zone type when one exists (DP-6 learned-first; empty ⇒ the motion
            // fallback inside the classifier). The source reads in-memory learned
            // state only — never blocking, never I/O.
            ZoneType learnedZoneType = endpoint.inputClusters()
                    .contains(IasZoneHandler.CLUSTER_ID)
                    ? zoneTypeSource.apply(ieee).orElse(null) : null;
            Optional<EndpointClassifier.Classification> classification =
                    EndpointClassifier.classify(endpoint, learnedZoneType);
            if (classification.isEmpty()) {
                log.warn("zigbee.endpoint_unclassified: device={} endpoint={} "
                                + "deviceType=0x{}; endpoint skipped", ieee,
                        endpoint.endpointId(),
                        Integer.toHexString(endpoint.deviceTypeId()));
                continue;
            }
            EntityId entityId = EntityId.of(UlidFactory.generate(clock));
            // DP-a (§2.2): the per-device confirmation tuning installs HERE, between
            // classification and registration — the only write; every downstream read
            // path (ledger, executor) consumes the tuned CapabilityInstance unchanged.
            List<CapabilityInstance> capabilities = installOverrides(
                    proposal.matchedProfileId(), classification.get().capabilities());
            EntityRegisteredEvent entityRegistered = RegistryEventMapper.toPayload(
                    new Entity(
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
                            capabilities,
                            clock.instant()));
            publishRegistration(new EventDraft(
                    EventTypes.ENTITY_REGISTERED,
                    1,
                    clock.instant(),
                    SubjectRef.entity(entityId),
                    EventPriority.NORMAL,
                    EventOrigin.INTEGRATION,
                    entityRegistered,
                    null,
                    null));
            registryProjection.applyEntityRegistered(entityRegistered);
            entityIds.put(endpoint.endpointId(), entityId);
            created.add(entityId);
        }

        Consumer<IEEEAddress> listener;
        lock.lock();
        try {
            devicesByIeee.put(ieee.value(), deviceId);
            entitiesByIeee.put(ieee.value(), Map.copyOf(entityIds));
            entityIds.forEach((endpoint, entityId) -> bindingsByEntity.put(entityId,
                    new EntityBinding(ieee, endpoint)));
            if (proposal.matchedProfileId() != null) {
                profilesByIeee.put(ieee.value(), proposal.matchedProfileId());
            }
            listener = adoptionListener;
        } finally {
            lock.unlock();
        }
        // F-8 (M9.4b): the invalidation hook fires OUTSIDE the lock (listener
        // code must never nest under the slice lock) and BEFORE the
        // device_adopted publish, so bus-driven consumers observe
        // post-invalidation handler state.
        if (listener != null) {
            listener.accept(ieee);
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
     * The F-8 handler-invalidation hook the adapter wires: {@code listener}
     * fires with the device IEEE after each successful {@link #adopt}.
     */
    void onAdopted(Consumer<IEEEAddress> listener) {
        Objects.requireNonNull(listener, "listener");
        lock.lock();
        try {
            this.adoptionListener = listener;
        } finally {
            lock.unlock();
        }
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
     * All adopted entities for a device, keyed by endpoint — the M9.6-AVAIL
     * per-entity availability fanout source (DP-5: one {@code
     * availability_changed} per adopted entity; a device with none publishes
     * nothing). Populated by {@link #adopt} and {@link #relink}; the returned
     * map is the stored immutable copy.
     *
     * @param ieee the device, never {@code null}
     * @return the endpoint&rarr;entity map, or an empty map when none
     */
    Map<Integer, EntityId> entitiesFor(IEEEAddress ieee) {
        Objects.requireNonNull(ieee, "ieee");
        lock.lock();
        try {
            return entitiesByIeee.getOrDefault(ieee.value(), Map.of());
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

    /**
     * Re-links a registry-known device to its protocol identity: rebuilds the
     * adapter-local IEEE&rarr;id / entity / binding maps FROM the registry view
     * and records the matched profile id. Package-private — the adapter's DP-6
     * startup rehydration invokes this per registry-carried device, which is
     * what makes ingestion and pin-2 work immediately post-restart.
     */
    void relink(IEEEAddress ieee, Device device, String matchedProfileId) {
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
            entitiesByIeee.get(ieee.value()).forEach((endpoint, entityId) ->
                    bindingsByEntity.put(entityId, new EntityBinding(ieee, endpoint)));
            if (matchedProfileId != null) {
                profilesByIeee.put(ieee.value(), matchedProfileId);
            }
        } finally {
            lock.unlock();
        }
        // DP-a pin 2, post-DUR (AMD-99): the pre-DUR fence ("the registry-empty-
        // post-restart rebuild is FENCED") is REALIZED — the registry projection
        // rebuilds both registries from the event log at boot (Phase 3), and this
        // relink rebuilds the adapter-local maps FROM that rebuilt registry view
        // (the DP-6 rehydration calls it per device at adapter startup). The
        // adoption-time tuning PERSISTS via replay, so the former override
        // re-install is redundant and was REMOVED (DP-4): a profile-file change
        // silently mutating the registries is exactly what REG-INV-1 bans — the
        // sanctioned path for a genuine capability change is an entity_registered
        // re-emit from a real re-interview (future work, Q10).
        // WU-AVAIL-SEED DP-3 (Branch STOP, Nick's ruling): the relink publishes
        // NO availability — the old device-grain "unknown"→"available" was an
        // evidence-free assertion (the F-14 L1 boot burst). Boot availability
        // is owned by the tracker seed (DP-1) and the served view's replayed
        // log; the log line below is the relink's sole observable.
        log.info("zigbee.device_relinked: device={} deviceId={} — re-pairing, "
                + "no new adoption", ieee, device.deviceId());
    }

    /**
     * Applies the matched profile's confirmation tuning (DP-a): no profile match means
     * standard defaults — a characterization is never synthesized.
     */
    private List<CapabilityInstance> installOverrides(String matchedProfileId,
            List<CapabilityInstance> capabilities) {
        if (matchedProfileId == null) {
            return capabilities;
        }
        Optional<DeviceProfile> profile = profileRegistry.allProfiles().stream()
                .filter(candidate -> matchedProfileId.equals(candidate.profileId()))
                .findFirst();
        if (profile.isEmpty()) {
            log.warn("zigbee.profile_unresolved: matched profile '{}' is not in the "
                    + "registry; standard confirmation defaults apply", matchedProfileId);
            return capabilities;
        }
        return ConfirmationOverrideInstaller.apply(profile.get(), capabilities);
    }

    /**
     * Resolves an adopted entity's protocol binding (the §3.3 command-path identity join).
     *
     * @param entityId the entity, never {@code null}
     * @return the (IEEE, endpoint) binding, or empty when the entity is not this
     *         integration's
     */
    Optional<EntityBinding> bindingFor(EntityId entityId) {
        Objects.requireNonNull(entityId, "entityId");
        lock.lock();
        try {
            return Optional.ofNullable(bindingsByEntity.get(entityId));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resolves the matched profile id recorded at adoption/re-link for a device (the
     * §3.3 characterization lookup input).
     *
     * @param ieee the device, never {@code null}
     * @return the matched profile id, or empty when none matched
     */
    Optional<String> matchedProfileIdFor(IEEEAddress ieee) {
        Objects.requireNonNull(ieee, "ieee");
        lock.lock();
        try {
            return Optional.ofNullable(profilesByIeee.get(ieee.value()));
        } finally {
            lock.unlock();
        }
    }

    private void publishRoot(EventDraft draft) {
        try {
            publisher.publishRoot(draft);
        } catch (SequenceConflictException e) {
            log.error("zigbee.adoption_publish_conflict: type={}: {}",
                    draft.eventType(), e.getMessage());
        }
    }

    /**
     * Publishes a registration fact with the write-ahead guarantee REG-INV-1
     * requires: the projection apply must never run for an event that failed to
     * persist, so — unlike {@link #publishRoot(EventDraft)}'s log-and-continue —
     * a publish failure here aborts the adoption. A sequence conflict on a
     * freshly minted subject cannot occur in practice; if it ever does, the
     * {@link IllegalStateException} reaches the cycle's existing
     * {@code interview_failed} WARN and the ingestion loop survives.
     *
     * <p><strong>The half-registration window is REPAIRED (M9.5-DURb, ruled
     * 2026-07-08):</strong> if the abort (or a process death) lands BETWEEN
     * the durable {@code device_registered} and its entities' publishes, the
     * log carries a device with zero entities — the announce path's LINKED
     * arm now requires at least one registered entity (DP-B1), so the next
     * re-announce re-proposes instead of freezing the hole, and
     * {@link #adopt} reuses the durable deviceId on the hardware-id match
     * (DP-B2), making the {@code device_registered} re-emit
     * idempotent-by-identity (AMD-99 F1). Recorded scope limit: a PARTIAL
     * multi-entity registration (some entities durable) still takes the
     * LINKED arm — Wave-1 devices are single-entity and the emission loop is
     * milliseconds; the projection-side count makes it detectable later.</p>
     */
    private void publishRegistration(EventDraft draft) {
        try {
            publisher.publishRoot(draft);
        } catch (SequenceConflictException e) {
            throw new IllegalStateException("Registration publish failed for "
                    + draft.eventType() + " (write-ahead precondition, REG-INV-1): "
                    + e.getMessage(), e);
        }
    }

    private static String sentinel(String value) {
        return value == null || value.isBlank() ? UNKNOWN_IDENTITY : value;
    }
}
