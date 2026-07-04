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
 * ingestion failures.
 *
 * <p>Thread-safe for the intended single-cycle-driver use: the per-device
 * handler table is confined to the cycle thread.
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
    }

    private static final Logger log = LoggerFactory.getLogger(ZclIngestionUnit.class);

    private final Supplier<List<EzspFrame>> callbackDrain;
    private final DeviceResolver resolver;
    private final IngestionListener listener;
    private final ReportDeduplicator deduplicator;
    private final EventPublisher publisher;
    private final Clock clock;
    private final Map<Long, Map<Integer, ZigbeeClusterHandler>> handlersByDevice =
            new HashMap<>();

    /**
     * Creates the ingestion unit.
     *
     * @param callbackDrain drains the protocol's bounded callback queue, never {@code null}
     * @param resolver the device/entity resolution surface, never {@code null}
     * @param listener the announce/frame signal sink, never {@code null}
     * @param deduplicator the measured-contract deduplicator, never {@code null}
     * @param publisher the event publisher from the integration context, never {@code null}
     * @param clock the time source, never {@code null}
     */
    ZclIngestionUnit(Supplier<List<EzspFrame>> callbackDrain,
            DeviceResolver resolver, IngestionListener listener,
            ReportDeduplicator deduplicator, EventPublisher publisher,
            Clock clock) {
        this.callbackDrain = Objects.requireNonNull(callbackDrain, "callbackDrain");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.deduplicator = Objects.requireNonNull(deduplicator, "deduplicator");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs one ingestion pass: drains the callback queue and processes every
     * frame. The §G contract: this runs FIRST in the adapter's cycle, before
     * interview/reporting work touches the live NCP.
     */
    void processCycle() {
        for (EzspFrame frame : callbackDrain.get()) {
            if (frame.frameId()
                    != EzspCoordinatorProtocol.FRAME_INCOMING_MESSAGE_HANDLER) {
                continue;
            }
            EzspIncomingMessage.parse(frame.parameters())
                    .ifPresent(this::route);
        }
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
            return;
        }
        Optional<IEEEAddress> device =
                resolver.deviceForNetworkAddress(message.sender());
        if (device.isEmpty()) {
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

    private Map<Integer, ZigbeeClusterHandler> handlersFor(IEEEAddress device) {
        return handlersByDevice.computeIfAbsent(device.value(),
                key -> ClusterHandlers.forDevice(device, clock,
                        resolver.zoneTypeFor(device)));
    }
}
