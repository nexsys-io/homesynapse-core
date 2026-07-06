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
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/**
 * The zigbee {@link ZigbeeAdapter} (M9.4a §4.1 minimal composition; M9.4b §5 real
 * transport): wires the M9.2 transport/protocol, the M9.3
 * interview/ingestion/adoption/profile layers, and the M9.4a command write path into
 * one supervisor-hosted adapter. One adapter, TWO run modes, selected by construction:
 *
 * <ul>
 *   <li><strong>Driven mode</strong> (an injected byte-channel opener — the
 *       hardware-free rig): {@code run()} opens the injected channel, negotiates the
 *       session, and parks on the stop latch; the gates own the §G cycle cadence via
 *       {@link #runCycleOnce()} (the M9.4a shape, unchanged).</li>
 *   <li><strong>Production mode</strong> (M9.4b §5.1 — no injected channel):
 *       {@code run()} resolves the port ({@code integrations.zigbee.serial_port},
 *       else the VID:PID locator — NEVER descriptor strings, AMD-96/E2), opens the
 *       channel, probes the transport kind, reuses the probe channel for the ASH
 *       session (never a double-open), negotiates, RESUMES the stored network or
 *       forms one (§5.2 — reopen NEVER re-forms), awaits {@code NETWORK_UP} (§5.3 —
 *       never-false-ALIVE), then loops the §G cycle with the read-as-park inbound
 *       pump and the {@link PortWatchdog} reopen backoff (§5.6).</li>
 * </ul>
 *
 * <p>{@code initialize()} performs no serial/coordinator I/O in either mode
 * (INV-RF-03): port location and opening happen in {@code run()}. Checked
 * {@link PermanentIntegrationException} propagates BARE from {@code run()} (the
 * classifier seam rule): unresolvable port / network mismatch / missing key custody
 * classify PERMANENT (FAILED-no-retry); the NETWORK_UP timeout throws
 * {@code IllegalStateException} — TRANSIENT, supervisor backoff.</p>
 *
 * <p>Thread-safe: lifecycle methods are supervisor-serialized; the cycle and the
 * command handler touch individually thread-safe collaborators.</p>
 */
final class ZigbeeIntegrationAdapter implements ZigbeeAdapter {

    private static final Logger log =
            LoggerFactory.getLogger(ZigbeeIntegrationAdapter.class);

    /** The {@code integrations.zigbee} config key naming the serial port (§5.1). */
    static final String SERIAL_PORT_KEY = "serial_port";

    /**
     * The {@code integrations.zigbee} config key naming the permit-join window
     * duration in seconds (M9.4-PJ — the headless/bench operator path). Conservative
     * default is LAW: an ABSENT key opens NOTHING; the schema's {@code default: 120}
     * is documentation-side and never auto-opens a join window.
     */
    static final String PERMIT_JOIN_DURATION_KEY = "permit_join_duration";

    /**
     * The permit-join window clamp bounds (M9.4-PJ; the schema validates upstream —
     * the clamp is the defensive floor/ceiling so a configured value never trips the
     * protocol's own range throw). Max 254 per the Zigbee spec.
     */
    static final int PERMIT_JOIN_MIN_SECONDS = 1;
    static final int PERMIT_JOIN_MAX_SECONDS = 254;

    /**
     * The production cycle cadence (chosen constant, §5.1): the inbound pump's
     * bounded read IS the park (the SERIAL platform thread blocks on the read —
     * no sleep, LTD-01/W4); while unhealthy, the latch-await park below stays
     * responsive to {@code close()}.
     */
    static final long PRODUCTION_CYCLE_MILLIS = 50;

    /**
     * Keepalive misses before the watchdog hears ASH-liveness loss (chosen
     * constant, §5.6): a deaf NCP behind a live port must still reach the
     * reopen path — {@code isOpen()} is never the sole health source (W5).
     */
    static final int KEEPALIVE_MISS_LIMIT = 3;

    /** Opens a byte channel over a located candidate (the §5.1 production seam). */
    interface PortChannelOpener {
        SerialByteChannel open(PortCandidate candidate);
    }

    private final IntegrationContext context;
    private final DeviceRegistry deviceRegistry;
    private final Path dataDirectory;
    private final Clock clock;
    private final Function<Object, SerialByteChannel> channelOpener;
    private final PortLocator.PortEnumerator portEnumerator;
    private final PortChannelOpener portChannelOpener;
    private final CountDownLatch stopSignal = new CountDownLatch(1);

    private StandardDeviceProfileRegistry profileRegistry;
    private ZigbeeDeviceCache cache;
    private ZigbeeAdoptionSlice adoption;
    private EzspAshTransport transport;
    private EzspCoordinatorProtocol protocol;
    private PendingInterviewQueue interviewQueue;
    private ZclIngestionUnit ingestion;
    private ZigbeeCommandHandler commandHandler;
    private NetworkParameterStore parameterStore;
    private PortLocator portLocator;
    private PortWatchdog watchdog;
    private volatile SerialByteChannel productionChannel;
    private PortIdentity portIdentity;
    /**
     * The permit-join window close instant (M9.4-PJ), or {@code null} when no
     * window is open. Written once by {@link #openPermitJoinWindow()} on the
     * production {@code run()} thread; read by {@link #isPermitJoinActive()} from
     * query threads — {@code volatile} for cross-thread visibility.
     */
    private volatile Instant permitJoinDeadline;

    /**
     * The canonical constructor. Exactly one transport source is bound:
     * {@code channelOpener} non-null selects DRIVEN mode (the rig's scripted
     * channel); otherwise {@code portEnumerator} + {@code portChannelOpener}
     * select PRODUCTION mode (§5.1).
     */
    ZigbeeIntegrationAdapter(IntegrationContext context, DeviceRegistry deviceRegistry,
            Path dataDirectory, Clock clock,
            Function<Object, SerialByteChannel> channelOpener,
            PortLocator.PortEnumerator portEnumerator,
            PortChannelOpener portChannelOpener) {
        this.context = context;
        this.deviceRegistry = deviceRegistry;
        this.dataDirectory = dataDirectory;
        this.clock = clock;
        this.channelOpener = channelOpener;
        this.portEnumerator = portEnumerator;
        this.portChannelOpener = portChannelOpener;
    }

    /** The M9.4a driven-mode shape (the rig path) — behavior-identical. */
    ZigbeeIntegrationAdapter(IntegrationContext context, DeviceRegistry deviceRegistry,
            Path dataDirectory, Clock clock,
            Function<Object, SerialByteChannel> channelOpener) {
        this(context, deviceRegistry, dataDirectory, clock, channelOpener, null, null);
    }

    // ── IntegrationAdapter lifecycle ────────────────────────────────────────

    @Override
    public void initialize() throws PermanentIntegrationException {
        if (channelOpener == null
                && (portEnumerator == null || portChannelOpener == null)) {
            // The truly-unbound case: neither a driven channel nor a locatable
            // production transport — honest FAILED-no-retry (INV-RF-01), never a
            // deaf radio that looks paired.
            throw new PermanentIntegrationException("zigbee.transport_unbound",
                    "No zigbee transport is bound: neither an injected byte channel "
                            + "(bench/test) nor a port enumerator + channel opener "
                            + "(production) was supplied at construction.");
        }
        profileRegistry = new StandardDeviceProfileRegistry();
        profileRegistry.register(new ZigbeeProfileLoader().loadBundled());
        cache = new ZigbeeDeviceCache(
                dataDirectory.resolve("zigbee-devices.json"), clock);
        adoption = new ZigbeeAdoptionSlice(context.integrationId(), deviceRegistry,
                context.entityRegistry(), profileRegistry, context.eventPublisher(),
                clock);
        // Driven mode rides the injected opener; production mode reuses the probe
        // channel (§5.1 — never a double-open): the opener returns the channel
        // bindTransport() already opened and probed.
        transport = new EzspAshTransport(clock,
                channelOpener != null ? channelOpener : ignored -> productionChannel);
        // Construction touches no files (INV-RF-03/INV-CE-02 hold).
        parameterStore = new PersistentNetworkParameterStore(dataDirectory, clock);
        protocol = new EzspCoordinatorProtocol(transport, parameterStore, clock);
        interviewQueue = new PendingInterviewQueue(clock);
        ingestion = new ZclIngestionUnit(() -> protocol.drainPendingCallbacks(),
                new CacheDeviceResolver(), new AdapterIngestionListener(),
                new ReportDeduplicator(clock), context.eventPublisher(), clock,
                protocol::sendZclFrame);   // F-7a: the enroll-response send seam
        // F-8: adoption completion invalidates the device's handler-table entry
        // (the classifier may have attached new capabilities; zone type may bind).
        adoption.onAdopted(ingestion::invalidateHandlers);
        commandHandler = new ZigbeeCommandHandler(adoption, cache, profileRegistry,
                protocol::sendZclFrame, protocol::lookupNetworkAddress,
                context.eventPublisher(), clock);
        if (channelOpener == null) {
            portLocator = new PortLocator(portEnumerator);
            watchdog = new PortWatchdog(clock, this::attemptReopen);
        }
        log.info("zigbee.initialized: integration_id={} data_dir={} mode={}",
                context.integrationId(), dataDirectory,
                channelOpener != null ? "driven" : "production");
    }

    @Override
    public void run() throws Exception {
        if (channelOpener != null) {
            // Driven mode (M9.4a): the rig owns the cycle cadence via runCycleOnce().
            transport.open(new Object());   // the injected opener supplies the channel
            protocol.startSession();        // checked PIE propagates BARE (the seam rule)
            log.info("zigbee.session_started: protocolVersion={}",
                    protocol.negotiatedVersion());
            stopSignal.await();
            return;
        }
        // Production mode (M9.4b §5.1): locate → probe → session → resume-or-form
        // → NETWORK_UP → the watchdog-armed cycle loop. Checked PIE propagates
        // BARE to the classifier (PERMANENT); the NETWORK_UP ISE classifies
        // TRANSIENT (supervisor backoff).
        PortCandidate port = resolvePort();
        bindTransport(port);
        protocol.startSession();
        resumeOrForm();
        protocol.awaitNetworkUp();
        log.info("zigbee.production_session_started: port={} protocolVersion={}",
                port.systemPath(), protocol.negotiatedVersion());
        openPermitJoinWindow();   // M9.4-PJ: the operator/bench join window (production only)
        productionLoop();
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
        if (parameterStore == null) {
            throw new IllegalStateException(
                    "the adapter is not initialized; no network parameters exist");
        }
        return parameterStore.load().orElseThrow(() -> new IllegalStateException(
                "no zigbee network has been formed yet"));
    }

    @Override
    public boolean isPermitJoinActive() {
        // Never-false-ALIVE: the honest clock-based window — never claims open when
        // closed. Null deadline (no window ever opened) reads false. The REST
        // permit-join surface remains the future UI mechanism (M9.4-PJ does not
        // preempt it).
        Instant deadline = permitJoinDeadline;
        return deadline != null && clock.instant().isBefore(deadline);
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

    // ── Production transport orchestration (M9.4b §5) ──────────────────────

    /**
     * Resolves the coordinator port: the {@code integrations.zigbee.serial_port}
     * key when present (authoritative — an unenumerated configured path is
     * synthesized so operator intent always wins), else the VID:PID locator
     * (AMD-96/E2 — never descriptor strings).
     *
     * @throws PermanentIntegrationException when neither path resolves a port —
     *         permanent until config or hardware changes (INV-RF-01)
     */
    PortCandidate resolvePort() throws PermanentIntegrationException {
        java.util.Optional<String> configured =
                context.configAccess().getString(SERIAL_PORT_KEY);
        if (configured.isPresent()) {
            String path = configured.get();
            return portEnumerator.enumerate().stream()
                    .filter(candidate -> path.equals(candidate.systemPath())
                            || path.equals(candidate.byIdPath()))
                    .findFirst()
                    .orElseGet(() -> new PortCandidate(path, null, -1, -1, null));
        }
        return portLocator.locate().orElseThrow(() -> new PermanentIntegrationException(
                "zigbee.transport_unbound",
                "No zigbee coordinator port: the integrations.zigbee.serial_port key "
                        + "is unset and no known coordinator bridge (VID:PID "
                        + "10c4:ea60) enumerated. Set the key or attach the "
                        + "coordinator."));
    }

    /**
     * Opens the located port's byte channel, probes the transport kind, and binds
     * the SAME channel into the ASH transport (§5.1 — the probe channel is reused,
     * never a double-open). Records the port identity for reopen (§5.6).
     *
     * @throws PermanentIntegrationException when the probe cannot characterize the
     *         transport, or the coordinator speaks ZNP (the Wave-2 transport)
     */
    void bindTransport(PortCandidate port) throws PermanentIntegrationException {
        SerialByteChannel channel = portChannelOpener.open(port);
        try {
            TransportProbe.Kind kind =
                    TransportProbe.detect(channel, port.systemPath(), clock);
            if (kind != TransportProbe.Kind.EZSP) {
                throw new PermanentIntegrationException("zigbee.transport_unsupported",
                        "Detected a " + kind + " coordinator on " + port.systemPath()
                                + "; the ZNP transport is Wave-2 — attach an EZSP "
                                + "coordinator or set integrations.zigbee.serial_port "
                                + "to one");
            }
        } catch (PermanentIntegrationException | RuntimeException probeFailure) {
            channel.close();
            throw probeFailure;
        }
        productionChannel = channel;
        transport.open(port);   // the opener returns productionChannel (§5.1 reuse)
        portIdentity = port.vendorId() >= 0
                ? PortLocator.identityFor(port, TransportProbe.Kind.EZSP.name())
                : new PortIdentity(0, 0, port.systemPath(),
                        TransportProbe.Kind.EZSP.name());
    }

    /**
     * §5.2 resume-or-form: stored parameters present &rarr; RESUME (a mismatch or
     * missing key custody propagates PERMANENT — never adopt a wrong network,
     * never silently re-form over corrupt custody: never-false-ALIVE); absent
     * (first run) &rarr; form with the §5.4 hashed-TCLK security state and persist
     * parameters + key through the store (§5.5).
     */
    void resumeOrForm() throws PermanentIntegrationException {
        if (parameterStore.load().isPresent()) {
            NetworkParameters resumed = protocol.resumeStored();
            log.info("zigbee.network_resumed: channel={} panId=0x{}",
                    resumed.channel(), Integer.toHexString(resumed.panId()));
        } else {
            NetworkParameters formed = protocol.formNetworkAutomatically();
            log.info("zigbee.network_formed: channel={} panId=0x{}",
                    formed.channel(), Integer.toHexString(formed.panId()));
        }
    }

    /**
     * Opens the permit-join window from the operator config key
     * ({@code integrations.zigbee.permit_join_duration}) — the headless/bench
     * operator path (M9.4-PJ), production mode ONLY, called once after
     * {@code NETWORK_UP} and before the watchdog-armed cycle loop.
     *
     * <p>Conservative default is LAW: an ABSENT key opens NOTHING (the schema's
     * documentation-side default never auto-opens a window). A present value is
     * clamped to [{@value #PERMIT_JOIN_MIN_SECONDS}, {@value #PERMIT_JOIN_MAX_SECONDS}]
     * (an out-of-range value logs one WARN and proceeds with the clamp), the
     * coordinator window is opened ONCE, and the real close instant is recorded so
     * {@link #isPermitJoinActive()} never claims open past close (never-false-ALIVE).
     * The deadline is recorded only AFTER the frame is accepted — a rejected open
     * leaves the window honestly closed.
     *
     * <p>A restart naturally re-opens the window while the key is present — the
     * designed bench semantic (the operator removes the key to stop re-opening on
     * boot). This is never called from {@code initialize()} (INV-RF-03) nor from the
     * M9.4a driven/test cadence ({@link #runCycleOnce()}); a watchdog reopen does not
     * renew the window (reopen &ne; boot).
     */
    void openPermitJoinWindow() {
        Optional<Integer> configured =
                context.configAccess().getInt(PERMIT_JOIN_DURATION_KEY);
        if (configured.isEmpty()) {
            return;   // conservative default: no key ⇒ the window NEVER opens
        }
        int requested = configured.get();
        int duration = Math.max(PERMIT_JOIN_MIN_SECONDS,
                Math.min(PERMIT_JOIN_MAX_SECONDS, requested));
        if (duration != requested) {
            log.warn("zigbee.permit_join_clamped: configured={} clamped={}",
                    requested, duration);
        }
        protocol.permitJoin(duration);
        permitJoinDeadline = clock.instant().plusSeconds(duration);
        log.info("zigbee.permit_join_opened: duration={}s", duration);
    }

    /**
     * The production cycle (§5.1): pump inbound (the bounded read IS the park —
     * LTD-01, no sleep), run one §G pass, keepalive-tick, and feed transport
     * failures to the watchdog; while unhealthy, tick the reopen backoff and park
     * on the stop latch (responsive to {@code close()}).
     */
    private void productionLoop() throws InterruptedException {
        while (stopSignal.getCount() > 0) {
            if (watchdog.isHealthy()) {
                try {
                    protocol.pumpInbound(PRODUCTION_CYCLE_MILLIS);
                    runCycleOnce();
                    protocol.maybeSendKeepalive();
                    if (protocol.keepaliveMisses() >= KEEPALIVE_MISS_LIMIT) {
                        log.warn("zigbee.ash_liveness_lost: {} consecutive keepalive "
                                + "misses — the watchdog owns recovery",
                                protocol.keepaliveMisses());
                        watchdog.onAshLivenessLost();
                    }
                } catch (TransportFailureException failure) {
                    log.warn("zigbee.transport_failed: {} — the watchdog owns "
                            + "recovery", failure.getMessage());
                    watchdog.onReadError();
                } catch (EzspCommandTimeoutException timeout) {
                    log.warn("zigbee.cycle_command_timeout: {}", timeout.getMessage());
                    watchdog.onAshLivenessLost();
                }
            } else {
                watchdog.tick();
                stopSignal.await(PRODUCTION_CYCLE_MILLIS,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * The composed {@link PortWatchdog.ReopenAction} (§5.6, the P24 contract):
     * close &rarr; re-locate by stable identity &rarr; reopen &rarr; fresh ASH
     * handshake &rarr; {@code resetSession()} &rarr; {@code startSession()} (UG100:
     * {@code version} must be the first command after an NCP reset) &rarr;
     * {@code resumeStored()} — RESUME, never re-form (a reopen that re-formed would
     * orphan the paired fleet). The ONE deliberate PIE catch: reopen failures are
     * the watchdog's backoff domain, never the classifier's — a PERMANENT mismatch
     * discovered here keeps failing and surfaces via WARNs + operator action
     * (recorded limitation, MODULE_CONTEXT).
     */
    boolean attemptReopen() {
        try {
            transport.close();
            java.util.Optional<PortCandidate> target =
                    portLocator.reopenTarget(portIdentity);
            if (target.isEmpty()) {
                log.warn("zigbee.reopen_no_target: the coordinator port did not "
                        + "re-enumerate; retrying on the watchdog backoff");
                return false;
            }
            productionChannel = portChannelOpener.open(target.get());
            transport.open(target.get());
            protocol.resetSession();
            protocol.startSession();
            protocol.resumeStored();
            log.info("zigbee.reopened: port={}", target.get().systemPath());
            return true;
        } catch (PermanentIntegrationException | RuntimeException failure) {
            log.warn("zigbee.reopen_failed: {}", failure.getMessage());
            try {
                transport.close();
            } catch (RuntimeException cleanup) {
                log.warn("zigbee.reopen_cleanup_failed: {}", cleanup.getMessage());
            }
            return false;
        }
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

}
