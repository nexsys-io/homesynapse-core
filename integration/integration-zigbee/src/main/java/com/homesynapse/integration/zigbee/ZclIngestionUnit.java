/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.EntityId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ObjLongConsumer;
import java.util.function.Supplier;

/**
 * The M9.3 ingestion unit: callback drain → dedup → cluster-handler dispatch →
 * {@code state_reported} publish (Doc 08 §3.5, §4.4; the §G bound-and-drain
 * contract — {@link #processCycle()} drains the protocol's bounded callback
 * queue FIRST each cycle, before any live NCP interaction the cycle's later
 * stages perform).
 *
 * <p>Every publish is a ROOT event over the injected {@link EventPublisher}
 * (LIVE frames only by construction: this unit consumes NCP callbacks, never
 * bus redeliveries — replay safety is upstream bus discipline, untouched).
 * The envelope's {@code eventTime} is the frame-receive approximation from the
 * injected {@link Clock} (EZSP frames carry no device timestamp; INV-ES-08 best
 * approximation), and the raw protocol value rides the payload's
 * {@code rawProtocolValue}/{@code rawProtocolUnit} slots (Doc 02 §3.7).
 *
 * <p>Unknown clusters (the measured 0xFC01/0xFC04/0xFC57 deltas), unknown
 * senders, and unadopted endpoints are skipped with structured logs — never
 * ingestion failures. F-R4-1 (R-10 Row 10 (a)): an unknown sender ALSO raises
 * {@link IngestionListener#onRejoinCandidate(int, int)} before its skip — the
 * silent-rejoiner admission hook (H-ii); the adapter gates it on the
 * permit-join window and admits through the SAME announce path.
 *
 * <p>M9.4-TCJ §A.2, as AMENDED by F-R4-1: {@code trustCenterJoinHandler}
 * (0x0024) and {@code childJoinHandler} (0x0023) callbacks are routed to
 * LOG-ONLY handlers — honest join observability (including failed joins,
 * which never announce). The join handlers never synthesize a device, an
 * interview, or an event; the ONE amendment: an ACCEPTED rejoin raises
 * {@link IngestionListener#onRejoinCandidate(IEEEAddress, int)} (H-i), an
 * admission TRIGGER the adapter gates on the open window and the adoption
 * maps — never a bypass of interview → proposal → adopted.
 *
 * <p>Dedup guards ONLY the unsolicited 0x0A report channel (F-4, M9.4b §6.1);
 * the 0x01 Read-Attributes-Response — the readback/VERIFY channel, AMD-97
 * caveat 1's confirm path — bypasses it entirely. IAS Zone (0x0500) devices
 * are enrolled on request (ZoneEnrollRequest → ZoneEnrollResponse, F-7a; the
 * request's zoneType payload feeds the learn — W2-LEARN) and their
 * wire-observed ZoneType outranks the resolver's default; the per-device
 * handler table invalidates on announce and on a zone-type change (F-8).
 * Learns PERSIST across restarts (LEARN-PERSIST): every successful learn
 * writes through the injected sink, and the persisted set seeds the map at
 * construction — silently, before any cycle can run.
 *
 * <p>Thread-safe for the intended single-cycle-driver use: the per-device
 * handler table and the learned zone-type map are confined to the cycle
 * thread.
 *
 * @see ReportDeduplicator
 * @see ClusterHandlers
 */
final class ZclIngestionUnit {

    /**
     * Resolution surface the ingestion needs from the device/adoption layer.
     */
    interface DeviceResolver {

        /**
         * Resolves a sender network address to its device.
         *
         * @param networkAddress the 16-bit sender address
         * @return the device, or empty when unknown
         */
        Optional<IEEEAddress> deviceForNetworkAddress(int networkAddress);

        /**
         * Resolves the adopted entity for a device endpoint.
         *
         * @param device the device
         * @param endpoint the application endpoint
         * @return the entity id, or empty before adoption
         */
        Optional<EntityId> entityFor(IEEEAddress device, int endpoint);

        /**
         * Returns the device's IAS zone type ({@link ZoneType#MOTION} when
         * unenrolled/unknown — the tolerate default).
         *
         * @param device the device
         * @return the zone type, never {@code null}
         */
        ZoneType zoneTypeFor(IEEEAddress device);
    }

    /** Ingestion signals the surrounding adapter layers consume. */
    interface IngestionListener {

        /**
         * A ZDP Device_annce arrived (join/rejoin): the cache updates the NWK
         * address, the pending-interview queue schedules/resumes, reporting
         * re-applies on rejoin.
         *
         * @param announce the parsed announce
         */
        void onDeviceAnnounce(ZdoCodec.DeviceAnnounce announce);

        /**
         * ANY frame arrived from a known device (availability + wake signal).
         *
         * @param device the sending device
         */
        void onFrame(IEEEAddress device);

        /**
         * F-R4-1 (R-10 Row 10 (a)) — interview-on-rejoin, hook H-ii, the
         * evidenced case: a Home Automation frame arrived from a network
         * address the device cache cannot resolve — the silent rejoiner (a
         * mains router that holds the network key rejoins on its own
         * authority, usually before the service listens, and never announces;
         * R-3a: the mains fleet silent after the outage; R-4 D-g: the
         * Hue-class device spoke ONLY as {@code ingestion_unknown_sender}
         * inside a 254 s window). Raised on EVERY such frame, BEFORE the
         * unknown-sender WARN, which continues as today — this unit detects,
         * the adapter decides: the permit-join window is the FIRST gate
         * (closed ⇒ noted once per nwk per invocation and ignored for
         * adoption), then the once-per-nwk IEEE resolution (the cache's
         * NWK→IEEE view, then the coordinator's address table), then the
         * SAME {@code recordAnnounce} + {@code schedule} an announce takes.
         * Doctrine: relink ≠ adopt — an admission TRIGGER into the interview
         * → proposal → adopted path, never a bypass; the frame itself is
         * still skipped.
         *
         * @param networkAddress the unresolvable 16-bit sender address
         * @param clusterId the frame's cluster (the log's device-class hint)
         */
        void onRejoinCandidate(int networkAddress, int clusterId);

        /**
         * F-R4-1 hook H-i, the cheap secondary on the same path: the Trust
         * Center reported an ACCEPTED rejoin (0x0024 {@code SECURED_REJOIN} /
         * {@code UNSECURED_REJOIN}, not denied) — the callback carries the
         * EUI64, so no lookup is needed. The adapter gates on the open window
         * and on the adoption maps (a device already adopted never re-enters
         * — today's relink path is untouched), then admits through the SAME
         * announce path. Denied joins, fresh joins (the announce follows), and
         * leaves never reach here — the M9.4-TCJ §A.2 pin's surviving half.
         *
         * @param device the rejoined device's IEEE address, never {@code null}
         * @param networkAddress the device's 16-bit network address
         */
        void onRejoinCandidate(IEEEAddress device, int networkAddress);
    }

    /** Sends one ZCL frame; {@code true} = the NCP accepted it (the F-7a response seam). */
    interface ZclFrameSender {
        boolean send(ZclFrame frame, int networkAddress);
    }

    private static final Logger log = LoggerFactory.getLogger(ZclIngestionUnit.class);

    /** The coordinator's application endpoint (the adapter-wide EP-1 convention). */
    private static final int COORDINATOR_ENDPOINT = 1;

    private final Supplier<List<EzspFrame>> callbackDrain;
    private final DeviceResolver resolver;
    private final IngestionListener listener;
    private final ReportDeduplicator deduplicator;
    private final EventPublisher publisher;
    private final Clock clock;
    private final ZclFrameSender frameSender;
    private final Map<Long, Map<Integer, ZigbeeClusterHandler>> handlersByDevice =
            new HashMap<>();
    // F-7a: wire-learned IAS zone types by IEEE — consulted before the
    // resolver's default when the handler table builds.
    private final Map<Long, ZoneType> learnedZoneTypes = new HashMap<>();
    // LEARN-PERSIST DP-LP-3: every successful wire learn writes through here.
    private final ObjLongConsumer<IEEEAddress> learnSink;

    /**
     * Creates the ingestion unit with no persisted seed and a no-op learn sink
     * — the pre-LEARN-PERSIST shape; pre-existing construction sites behave
     * byte-identically.
     *
     * @param callbackDrain drains the protocol's bounded callback queue, never {@code null}
     * @param resolver the device/entity resolution surface, never {@code null}
     * @param listener the announce/frame signal sink, never {@code null}
     * @param deduplicator the measured-contract deduplicator, never {@code null}
     * @param publisher the event publisher from the integration context, never {@code null}
     * @param clock the time source, never {@code null}
     * @param frameSender the outbound ZCL send surface for protocol-mandated
     *        responses (the F-7a ZoneEnrollResponse), never {@code null}
     */
    ZclIngestionUnit(Supplier<List<EzspFrame>> callbackDrain,
            DeviceResolver resolver, IngestionListener listener,
            ReportDeduplicator deduplicator, EventPublisher publisher,
            Clock clock, ZclFrameSender frameSender) {
        this(callbackDrain, resolver, listener, deduplicator, publisher, clock,
                frameSender, Map.of(), (device, zclId) -> { });
    }

    /**
     * Creates the ingestion unit with a persisted learned-zone-type seed and a
     * write-through learn sink (LEARN-PERSIST).
     *
     * <p>The seed applies at construction — structurally BEFORE
     * {@link #processCycle()} can run — through the same candidate-scan
     * tolerance the wire learn uses (DP-LP-2: an unknown persisted id is
     * skipped with a DEBUG, never a crash, never a learn). Seeding is SILENT:
     * no {@code ias_zone_type_learned} INFO (that line means exactly one
     * thing — a wire learn), no handler invalidation (no handlers exist
     * pre-cycle; DP-LP-4).
     *
     * @param callbackDrain drains the protocol's bounded callback queue, never {@code null}
     * @param resolver the device/entity resolution surface, never {@code null}
     * @param listener the announce/frame signal sink, never {@code null}
     * @param deduplicator the measured-contract deduplicator, never {@code null}
     * @param publisher the event publisher from the integration context, never {@code null}
     * @param clock the time source, never {@code null}
     * @param frameSender the outbound ZCL send surface for protocol-mandated
     *        responses (the F-7a ZoneEnrollResponse), never {@code null}
     * @param learnedZoneTypeSeed persisted zone-type ids by IEEE value
     *        (the cache's {@code learnedZoneTypeIds()} snapshot), never {@code null}
     * @param learnSink invoked with (device, learned zclId) after EVERY
     *        successful wire learn — the change case AND the same-as-effective
     *        silent case (DP-LP-3); state-mutation-only on this thread, never
     *        I/O; never {@code null}
     */
    ZclIngestionUnit(Supplier<List<EzspFrame>> callbackDrain,
            DeviceResolver resolver, IngestionListener listener,
            ReportDeduplicator deduplicator, EventPublisher publisher,
            Clock clock, ZclFrameSender frameSender,
            Map<Long, Long> learnedZoneTypeSeed,
            ObjLongConsumer<IEEEAddress> learnSink) {
        this.callbackDrain = Objects.requireNonNull(callbackDrain, "callbackDrain");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.deduplicator = Objects.requireNonNull(deduplicator, "deduplicator");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.frameSender = Objects.requireNonNull(frameSender, "frameSender");
        this.learnSink = Objects.requireNonNull(learnSink, "learnSink");
        Objects.requireNonNull(learnedZoneTypeSeed, "learnedZoneTypeSeed");
        // DP-LP-4: apply the persisted seed at construction — structurally
        // BEFORE processCycle() can run, so a cached-interview fast propose
        // reads the learned truth. A plain put, NEVER the learn path: no
        // ias_zone_type_learned INFO (it means exactly one thing — a wire
        // learn), no invalidation (no handlers exist yet), no sink invocation
        // (the entry came FROM the cache).
        learnedZoneTypeSeed.forEach((ieee, zclId) -> {
            ZoneType seeded = zoneTypeForZclId(zclId);
            if (seeded == null) {
                log.debug("zigbee.ias_zone_type_seed_unknown: device={} "
                        + "zclId=0x{}; skipped", new IEEEAddress(ieee),
                        Long.toHexString(zclId));
                return;
            }
            learnedZoneTypes.put(ieee, seeded);
        });
    }

    /**
     * The count of learned zone types currently held — rehydration
     * observability: the adapter's DP-LP-6 boot INFO reads it right after
     * construction, when the map holds exactly the APPLIED seed (tolerance-
     * skipped entries excluded). Cycle-thread/construction confined like the
     * map it reads.
     */
    int learnedZoneTypeCount() {
        return learnedZoneTypes.size();
    }

    /**
     * Runs one ingestion pass: drains the callback queue and processes every
     * frame. The §G contract: this runs FIRST in the adapter's cycle, before
     * interview/reporting work touches the live NCP. The drain has exactly ONE
     * consumer (this loop) — the M9.4-TCJ join callbacks are routed HERE, never
     * through a second drain that would steal frames from ingestion.
     */
    void processCycle() {
        for (EzspFrame frame : callbackDrain.get()) {
            int frameId = frame.frameId();
            if (frameId == EzspCoordinatorProtocol.FRAME_TRUST_CENTER_JOIN_HANDLER) {
                handleTrustCenterJoin(frame);
                continue;
            }
            if (frameId == EzspCoordinatorProtocol.FRAME_CHILD_JOIN_HANDLER) {
                handleChildJoin(frame);
                continue;
            }
            if (frameId == EzspCoordinatorProtocol
                    .FRAME_ZIGBEE_KEY_ESTABLISHMENT_HANDLER) {
                handleKeyEstablishment(frame);
                continue;
            }
            if (frameId != EzspCoordinatorProtocol.FRAME_INCOMING_MESSAGE_HANDLER) {
                continue;
            }
            EzspIncomingMessage.parse(frame.parameters())
                    .ifPresent(this::route);
        }
    }

    /**
     * M9.4-TCJ §A.2 — {@code trustCenterJoinHandler} (0x0024) observability.
     * <strong>THE PIN, AS AMENDED BY F-R4-1 (R-10 Row 10 (a), 2026-09-02):</strong>
     * this handler NEVER creates a device, NEVER feeds availability, and NEVER
     * publishes an event — it renders join outcomes visible, including the
     * failed joins that never announce; a device that fails key exchange must
     * never render as joined. The amendment covers exactly ONE case: an
     * ACCEPTED rejoin ({@code SECURED_REJOIN}/{@code UNSECURED_REJOIN}, not
     * denied) raises {@link IngestionListener#onRejoinCandidate(IEEEAddress,
     * int)} — an admission TRIGGER the adapter gates on the open permit-join
     * window and on the adoption maps, then feeds into the SAME
     * {@link #handleAnnounce} path (cache + queue). This handler itself still
     * schedules nothing: a fresh join waits for its announce, a denied join
     * and a leave stay observability-only. Every other sentence of the pin
     * stands.
     */
    private void handleTrustCenterJoin(EzspFrame frame) {
        Optional<EzspCoordinatorProtocol.TrustCenterJoin> parsed =
                EzspCoordinatorProtocol.TrustCenterJoin.parse(frame.parameters());
        if (parsed.isEmpty()) {
            log.debug("zigbee.tc_join_malformed: {} parameter bytes; dropped",
                    frame.parameters().length);
            return;
        }
        EzspCoordinatorProtocol.TrustCenterJoin join = parsed.get();
        if (join.deviceLeft()) {
            log.info("zigbee.device_left: device={} nwk=0x{}",
                    join.newNodeEui64(), Integer.toHexString(join.newNodeId()));
            return;
        }
        if (join.joinStarted()) {
            log.info("zigbee.device_join: device={} nwk=0x{} status={} decision={}",
                    join.newNodeEui64(), Integer.toHexString(join.newNodeId()),
                    join.statusName(), join.decisionName());
            if (join.rejoined()) {
                // F-R4-1 H-i: the accepted-rejoin admission trigger — the
                // adapter owns the window and adoption-map gates.
                listener.onRejoinCandidate(join.newNodeEui64(), join.newNodeId());
            }
            return;
        }
        // DENY_JOIN or an unknown status: honest failed-join surfacing — the
        // observability the escalation asked for, never a device.
        log.warn("zigbee.device_join_failed: device={} status={} decision={}",
                join.newNodeEui64(), join.statusName(), join.decisionName());
    }

    /**
     * M9.4-TCJ §A.2 — {@code childJoinHandler} (0x0023) observability for the
     * coordinator's own end-device children. Same never-synthesize rule as
     * {@link #handleTrustCenterJoin}.
     */
    private void handleChildJoin(EzspFrame frame) {
        Optional<EzspCoordinatorProtocol.ChildJoin> parsed =
                EzspCoordinatorProtocol.ChildJoin.parse(frame.parameters());
        if (parsed.isEmpty()) {
            log.debug("zigbee.child_join_malformed: {} parameter bytes; dropped",
                    frame.parameters().length);
            return;
        }
        EzspCoordinatorProtocol.ChildJoin child = parsed.get();
        if (child.joining()) {
            log.info("zigbee.child_join: child={} nwk=0x{} type={}",
                    child.childEui64(), Integer.toHexString(child.childId()),
                    child.typeName());
        } else {
            log.info("zigbee.child_left: child={} nwk=0x{} type={}",
                    child.childEui64(), Integer.toHexString(child.childId()),
                    child.typeName());
        }
    }

    /**
     * M9.4-RPT §B — {@code zigbeeKeyEstablishmentHandler} (0x009B)
     * observability, the OBS-2 discriminator instrument: if a device's
     * post-join leave is the TCLK-update class, THIS line shows it.
     * <strong>THE PIN:</strong> the same never-synthesize rule as
     * {@link #handleTrustCenterJoin} — this handler NEVER creates a device,
     * NEVER schedules an interview, NEVER publishes an event, and NEVER alters
     * adoption or availability. Pure observability.
     */
    private void handleKeyEstablishment(EzspFrame frame) {
        Optional<EzspCoordinatorProtocol.KeyEstablishment> parsed =
                EzspCoordinatorProtocol.KeyEstablishment.parse(frame.parameters());
        if (parsed.isEmpty()) {
            log.debug("zigbee.key_establishment_malformed: {} parameter bytes; "
                    + "dropped", frame.parameters().length);
            return;
        }
        EzspCoordinatorProtocol.KeyEstablishment key = parsed.get();
        if (key.established()) {
            log.info("zigbee.key_established: device={} status={}",
                    key.partner(), key.statusName());
            return;
        }
        if (key.progress()) {
            log.debug("zigbee.key_establishment_progress: device={} status={}",
                    key.partner(), key.statusName());
            return;
        }
        log.warn("zigbee.key_establishment_failed: device={} status={}",
                key.partner(), key.statusName());
    }

    private void route(EzspIncomingMessage message) {
        if (message.profileId() == EzspCoordinatorProtocol.ZDO_PROFILE_ID) {
            if (message.clusterId() == ZdoCodec.CLUSTER_DEVICE_ANNOUNCE) {
                ZdoCodec.parseDeviceAnnounce(message.message())
                        .ifPresent(this::handleAnnounce);
            }
            return;
        }
        if (message.profileId() != EzspCoordinatorProtocol.HA_PROFILE_ID) {
            // W2-LEARN §3: the drop stands unchanged — Green-Power frames
            // (0xA1E0) route here lawfully, so never above DEBUG. The line is
            // the standing instrument if a non-HA emitter ever appears
            // (the DP-WL-5 S31 closure).
            log.debug("zigbee.ingestion_profile_skipped: nwk=0x{} profile=0x{} "
                            + "cluster=0x{}; non-HA frame skipped",
                    Integer.toHexString(message.sender()),
                    Integer.toHexString(message.profileId()),
                    Integer.toHexString(message.clusterId()));
            return;
        }
        Optional<IEEEAddress> device =
                resolver.deviceForNetworkAddress(message.sender());
        if (device.isEmpty()) {
            // F-R4-1 H-ii: the silent-rejoiner admission trigger, raised
            // BEFORE the skip — the adapter owns the window gate, the
            // once-per-nwk resolution, and the admission; this frame is
            // still skipped exactly as before.
            listener.onRejoinCandidate(message.sender(), message.clusterId());
            log.warn("zigbee.ingestion_unknown_sender: nwk=0x{} cluster=0x{}; "
                            + "frame skipped",
                    Integer.toHexString(message.sender()),
                    Integer.toHexString(message.clusterId()));
            return;
        }
        listener.onFrame(device.get());
        handleZcl(device.get(), message);
    }

    private void handleAnnounce(ZdoCodec.DeviceAnnounce announce) {
        // TSN state from before the power-cycle is stale truth (the measured
        // reset-on-rejoin rule).
        deduplicator.clearDevice(announce.ieeeAddress());
        // F-8: the handler table is stale for the same reason the dedup scope
        // is — a rejoin can follow a re-pair that changed the zone-type truth.
        invalidateHandlers(announce.ieeeAddress());
        listener.onFrame(announce.ieeeAddress());
        listener.onDeviceAnnounce(announce);
        log.info("zigbee.device_announce: device={} nwk=0x{}",
                announce.ieeeAddress(),
                Integer.toHexString(announce.networkAddress()));
    }

    private void handleZcl(IEEEAddress device, EzspIncomingMessage message) {
        byte[] zcl = message.message();
        Optional<ZclCodec.ZclHeader> parsed = ZclCodec.parseHeader(zcl);
        if (parsed.isEmpty()) {
            log.debug("zigbee.ingestion_malformed_frame: device={} cluster=0x{}; "
                            + "dropped", device,
                    Integer.toHexString(message.clusterId()));
            return;
        }
        ZclCodec.ZclHeader header = parsed.get();
        // Dedup on the COMMAND PAYLOAD (the attribute records), not the whole
        // frame: the measured twins differ only in their header TSN byte.
        // Scope (F-4): ONLY the unsolicited 0x0A report channel — the measured
        // ×2 twins live there; the 0x01 Read-Attributes-Response is the
        // readback/VERIFY channel (AMD-97 caveat 1's confirm path), where a
        // frame byte-identical to a prior report is a deliberate second
        // observation, never a twin.
        if (!header.clusterSpecific()
                && header.commandId() == ZclCodec.COMMAND_REPORT_ATTRIBUTES) {
            byte[] commandPayload = java.util.Arrays.copyOfRange(zcl,
                    header.payloadOffset(), zcl.length);
            if (deduplicator.isDuplicate(device, message.sourceEndpoint(),
                    message.clusterId(), header.transactionSequence(),
                    commandPayload)) {
                log.debug("zigbee.ingestion_duplicate: device={} endpoint={} "
                                + "cluster=0x{} tsn={}",
                        device, message.sourceEndpoint(),
                        Integer.toHexString(message.clusterId()),
                        header.transactionSequence());
                return;
            }
        }

        List<NormalizedAttribute> normalized;
        if (!header.clusterSpecific()
                && (header.commandId() == ZclCodec.COMMAND_REPORT_ATTRIBUTES
                        || header.commandId()
                                == ZclCodec.COMMAND_READ_ATTRIBUTES_RESPONSE)) {
            Map<Integer, Object> attributes =
                    header.commandId() == ZclCodec.COMMAND_REPORT_ATTRIBUTES
                            ? ZclCodec.parseAttributeReports(zcl,
                                    header.payloadOffset())
                            : ZclCodec.parseReadAttributesResponse(zcl,
                                    header.payloadOffset());
            if (message.clusterId() == IasZoneHandler.CLUSTER_ID) {
                // F-7a: learn the wire-observed ZoneType BEFORE the handler
                // lookup so this frame's own dispatch already sees the truth.
                learnZoneType(device, attributes);
            }
            ZigbeeClusterHandler handler =
                    handlersFor(device).get(message.clusterId());
            if (handler == null) {
                log.debug("zigbee.ingestion_unhandled_cluster: device={} "
                                + "cluster=0x{}; skipped gracefully", device,
                        Integer.toHexString(message.clusterId()));
                return;
            }
            normalized = handler.normalize(message.sourceEndpoint(),
                    message.clusterId(), attributes);
        } else if (header.clusterSpecific()
                && message.clusterId() == IasZoneHandler.CLUSTER_ID
                && header.commandId() == 0x00
                && zcl.length >= header.payloadOffset() + 2) {
            // ZoneStatusChangeNotification — ingested regardless of enrollment
            // state (§3.12 tolerate-not-require; the measured dual-path rule).
            int zoneStatus = (zcl[header.payloadOffset()] & 0xFF)
                    | ((zcl[header.payloadOffset() + 1] & 0xFF) << 8);
            IasZoneHandler handler = (IasZoneHandler)
                    handlersFor(device).get(IasZoneHandler.CLUSTER_ID);
            normalized = handler.normalizeZoneStatus(message.sourceEndpoint(),
                    zoneStatus);
        } else if (header.clusterSpecific()
                && message.clusterId() == IasZoneHandler.CLUSTER_ID
                && header.commandId()
                        == IasZoneHandler.COMMAND_ZONE_ENROLL_REQUEST) {
            // W2-LEARN §1 — ZCL8 §8.2.2.3: the request payload is
            // [zoneType u16 LE][manufacturerCode u16 LE], the wire truth no
            // real device volunteers as an attribute (the joins-night record).
            // The learn precedes the response and never depends on the send
            // outcome (the F-7a learn-before-dispatch principle); a short
            // payload learns nothing and still enrolls (§3.12 tolerate — a
            // malformed request never blocks enrollment).
            if (zcl.length >= header.payloadOffset() + 2) {
                int zoneType = (zcl[header.payloadOffset()] & 0xFF)
                        | ((zcl[header.payloadOffset() + 1] & 0xFF) << 8);
                learnZoneType(device, zoneType);
            }
            respondZoneEnroll(device, message);
            return;
        } else {
            return;
        }

        for (NormalizedAttribute attribute : normalized) {
            publishStateReported(device, message.sourceEndpoint(), attribute);
        }
    }

    private void publishStateReported(IEEEAddress device, int endpoint,
            NormalizedAttribute attribute) {
        Optional<EntityId> entity = resolver.entityFor(device, endpoint);
        if (entity.isEmpty()) {
            log.debug("zigbee.ingestion_unadopted: device={} endpoint={} "
                            + "attribute={}; report observed before adoption",
                    device, endpoint, attribute.attributeKey());
            return;
        }
        EventDraft draft = new EventDraft(
                EventTypes.STATE_REPORTED,
                1,
                clock.instant(),
                SubjectRef.entity(entity.get()),
                EventPriority.NORMAL,
                originFor(attribute.attributeKey()),
                new StateReportedEvent(
                        attribute.attributeKey(),
                        String.valueOf(attribute.value()),
                        attribute.unit(),
                        attribute.rawProtocolValue(),
                        attribute.rawProtocolUnit()),
                null,
                null);
        try {
            publisher.publishRoot(draft);
        } catch (SequenceConflictException e) {
            log.error("zigbee.ingestion_publish_conflict: device={} endpoint={} "
                            + "attribute={}: {}",
                    device, endpoint, attribute.attributeKey(), e.getMessage());
        }
    }

    private static EventOrigin originFor(String attributeKey) {
        // Battery levels arrive on the device's own reporting schedule; state
        // observations reflect the physical world (Doc 08 §4.4).
        return "battery_pct".equals(attributeKey)
                ? EventOrigin.DEVICE_AUTONOMOUS
                : EventOrigin.PHYSICAL;
    }

    /**
     * ZCL8 §8.2.2.3: the CIE answers ZoneEnrollRequest with ZoneEnrollResponse
     * — enrollment is GRANTED unconditionally (§3.12 tolerate-not-require:
     * enrollment is a device fact the CIE acknowledges, never an ingestion
     * gate). A rejected send is logged and dropped — the device re-requests or
     * auto-enrolls (F-7a).
     */
    private void respondZoneEnroll(IEEEAddress device,
            EzspIncomingMessage message) {
        ZclFrame response = new ZclFrame(COORDINATOR_ENDPOINT,
                message.sourceEndpoint(), IasZoneHandler.CLUSTER_ID,
                IasZoneHandler.COMMAND_ZONE_ENROLL_RESPONSE, true, 0,
                new byte[] {IasZoneHandler.ENROLL_RESPONSE_SUCCESS,
                        IasZoneHandler.ENROLL_ZONE_ID});
        if (frameSender.send(response, message.sender())) {
            log.info("zigbee.ias_zone_enrolled: device={} endpoint={} zoneId={}",
                    device, message.sourceEndpoint(),
                    IasZoneHandler.ENROLL_ZONE_ID);
        } else {
            log.warn("zigbee.ias_enroll_response_rejected: device={} nwk=0x{}; "
                            + "the NCP refused the ZoneEnrollResponse — the "
                            + "device re-requests or auto-enrolls",
                    device, Integer.toHexString(message.sender()));
        }
    }

    /**
     * F-7a, the ATTRIBUTE path: the ZoneType attribute (0x0001) observed via
     * report/readback extracts to the shared learn core. Non-{@code Long}
     * values (absent attribute, malformed record) learn nothing.
     */
    private void learnZoneType(IEEEAddress device,
            Map<Integer, Object> attributes) {
        Object value = attributes.get(IasZoneHandler.ATTRIBUTE_ZONE_TYPE);
        if (!(value instanceof Long zclId)) {
            return;
        }
        learnZoneType(device, zclId);
    }

    /**
     * The shared learn core (W2-LEARN) — fed by the attribute path above and
     * by the ZoneEnrollRequest payload parse (ZCL8 §8.2.2.3): the wire-observed
     * ZoneType outranks the resolver's adoption-time default — the sensor
     * itself is the authority on what it is. A learn that CHANGES the
     * effective type invalidates the device's handler table (F-8) so
     * subsequent frames normalize under the new type; a same-as-effective
     * learn stores silently; unknown zone-type values never learn (the
     * tolerate default stands).
     */
    private void learnZoneType(IEEEAddress device, long zclId) {
        ZoneType learned = zoneTypeForZclId(zclId);
        if (learned == null) {
            log.debug("zigbee.ias_zone_type_unknown: device={} zclId=0x{}; "
                    + "ignored", device, Long.toHexString(zclId));
            return;
        }
        ZoneType previous = effectiveZoneType(device);
        learnedZoneTypes.put(device.value(), learned);
        // DP-LP-3 (LEARN-PERSIST): EVERY successful learn persists — the
        // change case AND the same-as-effective silent case (a first wire
        // learn matching the resolver default is still a learned fact). The
        // sink mutates cache state only; the debounced flush carries the I/O
        // off this cycle-thread call.
        learnSink.accept(device, learned.zclId());
        if (learned != previous) {
            // F-8: the cached table was built under the previous type — drop
            // it so the rebuild picks up the learned truth.
            invalidateHandlers(device);
            log.info("zigbee.ias_zone_type_learned: device={} zoneType={} "
                    + "(was {})", device, learned, previous);
        }
    }

    /**
     * The candidate scan — DP-LP-2's ONE tolerance code path: resolves a ZCL
     * zone-type id to the enum, {@code null} when unknown. Shared by the wire
     * learn and the construction seed, so persisted ids and wire ids tolerate
     * identically and a {@link ZoneType} change can never split the behavior.
     */
    private static ZoneType zoneTypeForZclId(long zclId) {
        for (ZoneType candidate : ZoneType.values()) {
            if (candidate.zclId() == zclId) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Drops the device's cluster-handler table so the next frame rebuilds it
     * with current zone-type truth (F-8) — invoked on device-announce, on a
     * zone-type learn that changes the effective type, and by the adapter's
     * adoption-completion hook.
     *
     * @param device the device whose table is stale, never {@code null}
     */
    void invalidateHandlers(IEEEAddress device) {
        Objects.requireNonNull(device, "device");
        handlersByDevice.remove(device.value());
    }

    private Map<Integer, ZigbeeClusterHandler> handlersFor(IEEEAddress device) {
        return handlersByDevice.computeIfAbsent(device.value(),
                key -> ClusterHandlers.forDevice(device, clock,
                        effectiveZoneType(device)));
    }

    /**
     * The wire-learned IAS zone type for a device, when a ZoneType attribute
     * has been observed (F-7a) — LEARNED state ONLY, never the resolver
     * default, so adoption-time classification (M9.7-W2 §4) can distinguish
     * unlearned (the DP-6 motion fallback) from a learned type.
     *
     * <p>Cycle-thread confined like the map it reads (the class contract):
     * the production caller is the adoption path, which runs on the same
     * ingestion cycle thread as {@link #processCycle()}.
     *
     * @param device the device, never {@code null}
     * @return the learned zone type, or empty when none was observed
     */
    Optional<ZoneType> learnedZoneType(IEEEAddress device) {
        Objects.requireNonNull(device, "device");
        return Optional.ofNullable(learnedZoneTypes.get(device.value()));
    }

    /**
     * The F-7a precedence: a wire-learned zone type beats the resolver's
     * default ({@link ZoneType#MOTION} stays the unenrolled fallback, inside
     * the resolver).
     */
    private ZoneType effectiveZoneType(IEEEAddress device) {
        ZoneType learned = learnedZoneTypes.get(device.value());
        return learned != null ? learned : resolver.zoneTypeFor(device);
    }
}
