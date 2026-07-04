/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.DeviceRegistry;
import com.homesynapse.integration.CommandHandler;
import com.homesynapse.integration.IntegrationContext;
import com.homesynapse.integration.PermanentIntegrationException;
import com.homesynapse.platform.identity.EntityId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/**
 * The zigbee {@link ZigbeeAdapter} (M9.4a §4.1 — the minimal composition): wires the
 * M9.2 transport/protocol, the M9.3 interview/ingestion/adoption/profile layers, and
 * the M9.4a command write path into one supervisor-hosted adapter.
 *
 * <ul>
 *   <li><strong>{@code initialize()}</strong> (INV-RF-03: no serial/coordinator I/O):
 *       loads the bundled profile corpus, opens the device cache, and composes the
 *       slices. An unbound transport channel (the M9.4a public-constructor path — the
 *       real serial orchestration is M9.4b's) throws {@link
 *       PermanentIntegrationException} here: honest FAILED-no-retry, never a deaf
 *       radio that looks paired.</li>
 *   <li><strong>{@code run()}</strong>: opens the injected channel, negotiates the
 *       EZSP session (checked PIE propagates BARE — the classifier seam rule), then
 *       parks on the stop latch. The ingestion/interview cycle is DRIVEN
 *       ({@link #runCycleOnce()}) rather than free-running in M9.4a — the
 *       hardware-free gates own the cadence deterministically under the injected
 *       clock; the production cadence binds with the real transport at M9.4b.</li>
 *   <li><strong>{@code commandHandler()}</strong>: the §3.3 {@link
 *       ZigbeeCommandHandler} over the protocol's boolean dispatch seam.</li>
 * </ul>
 *
 * <p>Thread-safe: lifecycle methods are supervisor-serialized; the driven cycle and
 * the command handler touch individually thread-safe collaborators.</p>
 */
final class ZigbeeIntegrationAdapter implements ZigbeeAdapter {

    private static final Logger log =
            LoggerFactory.getLogger(ZigbeeIntegrationAdapter.class);

    private final IntegrationContext context;
    private final DeviceRegistry deviceRegistry;
    private final Path dataDirectory;
    private final Clock clock;
    private final Function<Object, SerialByteChannel> channelOpener;
    private final CountDownLatch stopSignal = new CountDownLatch(1);

    private StandardDeviceProfileRegistry profileRegistry;
    private ZigbeeDeviceCache cache;
    private ZigbeeAdoptionSlice adoption;
    private EzspAshTransport transport;
    private EzspCoordinatorProtocol protocol;
    private PendingInterviewQueue interviewQueue;
    private ZclIngestionUnit ingestion;
    private ZigbeeCommandHandler commandHandler;

    ZigbeeIntegrationAdapter(IntegrationContext context, DeviceRegistry deviceRegistry,
            Path dataDirectory, Clock clock,
            Function<Object, SerialByteChannel> channelOpener) {
        this.context = context;
        this.deviceRegistry = deviceRegistry;
        this.dataDirectory = dataDirectory;
        this.clock = clock;
        this.channelOpener = channelOpener;
    }

    // ── IntegrationAdapter lifecycle ────────────────────────────────────────

    @Override
    public void initialize() throws PermanentIntegrationException {
        if (channelOpener == null) {
            throw new PermanentIntegrationException("zigbee.transport_unbound",
                    "The zigbee serial transport binds at M9.4b; this build runs "
                            + "only with an injected transport channel (bench/test). "
                            + "Configure no zigbee integration, or await M9.4b.");
        }
        profileRegistry = new StandardDeviceProfileRegistry();
        profileRegistry.register(new ZigbeeProfileLoader().loadBundled());
        cache = new ZigbeeDeviceCache(
                dataDirectory.resolve("zigbee-devices.json"), clock);
        adoption = new ZigbeeAdoptionSlice(context.integrationId(), deviceRegistry,
                context.entityRegistry(), profileRegistry, context.eventPublisher(),
                clock);
        transport = new EzspAshTransport(clock, channelOpener);
        protocol = new EzspCoordinatorProtocol(transport,
                new InMemoryParameterStore(), clock);
        interviewQueue = new PendingInterviewQueue(clock);
        ingestion = new ZclIngestionUnit(() -> protocol.drainPendingCallbacks(),
                new CacheDeviceResolver(), new AdapterIngestionListener(),
                new ReportDeduplicator(), context.eventPublisher(), clock);
        commandHandler = new ZigbeeCommandHandler(adoption, cache, profileRegistry,
                protocol::sendZclFrame, protocol::lookupNetworkAddress,
                context.eventPublisher(), clock);
        log.info("zigbee.initialized: integration_id={} data_dir={}",
                context.integrationId(), dataDirectory);
    }

    @Override
    public void run() throws Exception {
        transport.open(new Object());   // the injected opener supplies the channel
        protocol.startSession();        // checked PIE propagates BARE (the seam rule)
        log.info("zigbee.session_started: protocolVersion={}",
                protocol.negotiatedVersion());
        // M9.4a: the cycle is driven (runCycleOnce) — the free-running cadence and
        // network resume/formation orchestration bind with the real transport (M9.4b).
        stopSignal.await();
    }

    @Override
    public void close() {
        stopSignal.countDown();
        if (cache != null) {
            cache.flush();
        }
        if (transport != null) {
            transport.close();
        }
    }

    @Override
    public CommandHandler commandHandler() {
        return commandHandler;
    }

    // ── ZigbeeAdapter queries ───────────────────────────────────────────────

    @Override
    public Optional<ZigbeeDeviceRecord> device(IEEEAddress ieeeAddress) {
        return cache == null ? Optional.empty() : cache.device(ieeeAddress);
    }

    @Override
    public Collection<ZigbeeDeviceRecord> allDevices() {
        return cache == null ? java.util.List.of() : cache.all();
    }

    @Override
    public Optional<DeviceProfile> deviceProfile(IEEEAddress ieeeAddress) {
        if (cache == null) {
            return Optional.empty();
        }
        return cache.device(ieeeAddress)
                .map(ZigbeeDeviceRecord::matchedProfileId)
                .flatMap(profileId -> profileRegistry.allProfiles().stream()
                        .filter(profile -> profile.profileId().equals(profileId))
                        .findFirst());
    }

    @Override
    public NetworkParameters networkParameters() {
        throw new UnsupportedOperationException(
                "network parameter queries bind with the real transport at M9.4b");
    }

    @Override
    public boolean isPermitJoinActive() {
        return false;   // the permit-join surface binds with the REST path (post-M9.4a)
    }

    // ── The driven cycle (§G — M9.4a: gates own the cadence) ───────────────

    /** One §G pass: drain → route → interviews due/expire → cache flush check. */
    void runCycleOnce() {
        ingestion.processCycle();
        for (PendingInterviewQueue.Pending due : interviewQueue.due()) {
            interviewDevice(due.ieeeAddress());
        }
        interviewQueue.expireStale();
        cache.maybeFlush();
    }

    /** The E2E/composition drive seams (package-private — testFixtures reach them). */
    ZigbeeAdoptionSlice adoptionSlice() {
        return adoption;
    }

    ZigbeeDeviceCache deviceCache() {
        return cache;
    }

    EzspCoordinatorProtocol coordinatorProtocol() {
        return protocol;
    }

    private void interviewDevice(IEEEAddress ieee) {
        try {
            InterviewResult interview = protocol.interview(ieee);
            String matchedProfileId = profileRegistry.findProfile(interview)
                    .map(DeviceProfile::profileId).orElse(null);
            cache.recordInterview(interview, matchedProfileId);
            interviewQueue.complete(ieee);
            adoption.onDeviceDiscovered(interview, matchedProfileId);
        } catch (RuntimeException failure) {
            log.warn("zigbee.interview_failed: device={}: {}", ieee,
                    failure.getMessage());
            interviewQueue.recordFailure(ieee);
        }
    }

    /** NWK→IEEE and entity resolution over the cache + adoption slice. */
    private final class CacheDeviceResolver implements ZclIngestionUnit.DeviceResolver {
        @Override
        public Optional<IEEEAddress> deviceForNetworkAddress(int networkAddress) {
            return cache.deviceForNetworkAddress(networkAddress);
        }

        @Override
        public Optional<EntityId> entityFor(IEEEAddress device, int endpoint) {
            return adoption.entityFor(device, endpoint);
        }

        @Override
        public ZoneType zoneTypeFor(IEEEAddress device) {
            // M9.4a default: the IAS zone-type learning path is F-7/M9.4b.
            return ZoneType.MOTION;
        }
    }

    /** Announce → cache + interview schedule; every frame feeds queue liveness. */
    private final class AdapterIngestionListener
            implements ZclIngestionUnit.IngestionListener {
        @Override
        public void onDeviceAnnounce(ZdoCodec.DeviceAnnounce announce) {
            cache.recordAnnounce(announce.ieeeAddress(), announce.networkAddress());
            interviewQueue.schedule(announce.ieeeAddress(), announce.networkAddress());
        }

        @Override
        public void onFrame(IEEEAddress device) {
            cache.recordFrame(device);
            interviewQueue.onFrameReceived(device);
        }
    }

    /**
     * The M9.4a in-memory parameter store: network resume/formation orchestration
     * (and the INV-SE-03 SecretStore-backed custody) bind with the real transport at
     * M9.4b — nothing in the hardware-free loop forms or resumes a network.
     */
    private static final class InMemoryParameterStore implements NetworkParameterStore {
        private NetworkParameters parameters;
        private final java.util.Map<String, byte[]> keys = new java.util.HashMap<>();

        @Override
        public Optional<NetworkParameters> load() {
            return Optional.ofNullable(parameters);
        }

        @Override
        public void save(NetworkParameters saved) {
            this.parameters = saved;
        }

        @Override
        public void saveNetworkKey(String keyRef, byte[] keyMaterial) {
            keys.put(keyRef, keyMaterial.clone());
        }

        @Override
        public Optional<byte[]> loadNetworkKey(String keyRef) {
            byte[] material = keys.get(keyRef);
            return material == null ? Optional.empty()
                    : Optional.of(material.clone());
        }
    }
}
