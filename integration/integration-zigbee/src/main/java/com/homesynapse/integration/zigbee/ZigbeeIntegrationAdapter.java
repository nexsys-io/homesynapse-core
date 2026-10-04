/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.Device;
import com.homesynapse.device.DeviceRegistry;
import com.homesynapse.device.Entity;
import com.homesynapse.device.HardwareIdentifier;
import com.homesynapse.device.RegistryEventMapper;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.event.AvailabilityChangedEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EntityRegisteredEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.integration.CommandHandler;
import com.homesynapse.integration.IntegrationContext;
import com.homesynapse.integration.PairingWindow;
import com.homesynapse.integration.PairingWindowControl;
import com.homesynapse.integration.PairingWindowRequest;
import com.homesynapse.integration.PermitJoinClosed;
import com.homesynapse.integration.PermitJoinOpened;
import com.homesynapse.integration.PermanentIntegrationException;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.state.RegistryStalenessResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

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
final class ZigbeeIntegrationAdapter implements ZigbeeAdapter, PairingWindowControl {

    private static final Logger log =
            LoggerFactory.getLogger(ZigbeeIntegrationAdapter.class);

    /** The {@code integrations.zigbee} config key naming the serial port (§5.1). */
    static final String SERIAL_PORT_KEY = "serial_port";

    /**
     * The {@code integrations.zigbee} config key that USED to open the permit-join
     * window at production start (M9.4-PJ). IGNORED since PJ-2: a value present at
     * start logs ONE WARN ({@code zigbee.permit_join_key_ignored}) and opens nothing —
     * the window opens only by {@code POST /api/v1/integrations/{id}/permit-join}
     * ({@link #openPairingWindow}; the 1–254 s bounds live on
     * {@link PairingWindowRequest}). The key stays in the schema so a configuration
     * carrying it still validates; its removal is a later decision.
     */
    static final String PERMIT_JOIN_DURATION_KEY = "permit_join_duration";

    /**
     * F-R4-1b DP-4 — the bound on the ZDO {@code IEEE_addr_req} exchange the
     * interview-on-rejoin arm sends after a clean coordinator-table miss: the
     * interview's own per-step budget
     * ({@link InterviewStateMachine#STEP_TIMEOUT_MILLIS}, 10 s — Doc 08 §3.4).
     * The interview steps that follow an admission run under the same bound,
     * so a device that cannot answer inside it could not be interviewed
     * anyway; a router parent buffers for its own indirect timeout (the
     * coordinator's is 7680 ms,
     * {@link EzspCoordinatorProtocol#INDIRECT_TRANSMISSION_TIMEOUT_VALUE}) and
     * a sleepy end device that has just transmitted polls its parent. Not
     * re-tuned on the desk — the wire at R-4c decides.
     */
    static final long IEEE_ADDR_REQ_TIMEOUT_MILLIS =
            InterviewStateMachine.STEP_TIMEOUT_MILLIS;

    /**
     * The {@code integrations.zigbee} config key pinning the RF channel for FIRST
     * formation (M9.4-TCJ §B; schema'd 11–26). Read only on the form path: a
     * present, in-range value forms directly on that channel (the energy scan never
     * runs); an absent key leaves the §3.13 energy-scan selection unchanged. The
     * resume path never reads it — a formed network resumes on its STORED channel
     * regardless (RESUME-never-re-form).
     */
    static final String CHANNEL_KEY = "channel";

    /**
     * The Zigbee 2.4 GHz RF channel bounds (schema-mirrored; the schema validates
     * upstream — the guard is the defensive floor so a configured value never trips
     * the {@code NetworkParameters} range throw).
     */
    static final int CHANNEL_MIN = 11;
    static final int CHANNEL_MAX = 26;

    /**
     * The {@code integrations.zigbee} config key listing the IEEE addresses the
     * user ACCEPTS for adoption (M9.4-ADP — Doc 02 §3.12 Stage 3 realized as
     * config-declared consent, the Tier-1 headless surface). Adoption fires only
     * when a discovery mints a FRESH proposal for a listed device whose interview
     * is COMPLETE; Stage-2 re-links of already-adopted devices stay automatic
     * (consent is first-adoption-only). Conservative default is LAW: an absent or
     * empty list adopts NOTHING — proposals sit, honestly logged.
     */
    static final String ADOPT_DEVICES_KEY = "adopt_devices";

    /**
     * The accept-list entry shape: the log's own IEEE rendering — an optional
     * {@code 0x}/{@code 0X} prefix and exactly 16 hex characters, any case
     * (normalized to the raw 64-bit value before compare).
     */
    private static final Pattern ADOPT_ENTRY_SHAPE =
            Pattern.compile("(?:0[xX])?[0-9a-fA-F]{16}");

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

    /**
     * The availability ping's exchange deadline (M9.6-AVAIL DP-4; chosen
     * constant matching the reporting binding's 5 s sleepy-tolerant window).
     * The ping is one ZCL Basic read on the run thread; a lapsed deadline is
     * the honest {@code PING_TIMEOUT} verdict, never an exception.
     */
    static final long AVAILABILITY_PING_TIMEOUT_MILLIS = 5_000;

    /**
     * J1 (LINK-READ-2): {@code availability_changed} publishes at schema version
     * 2 — the v1 status pair plus the nullable {@code reason}, {@code lastSeenAt},
     * {@code lqi}, {@code rssiDbm}, {@code linkAt}. Additive-nullable: the
     * persistence codec's tolerant decode is the upcast (a v1 row reads as the
     * record with nulls; a v2 row read by a pre-J1 core ignores the extras).
     */
    static final int AVAILABILITY_CHANGED_SCHEMA_VERSION = 2;

    /**
     * LINK-READ: what {@code last_lqi=}, {@code last_rssi_dbm=} and
     * {@code last_link_at=} print for a device with no link reading this
     * process (a seeded device that has not spoken) — never a default number.
     */
    static final String NO_LINK_READING = "-";

    /** Opens a byte channel over a located candidate (the §5.1 production seam). */
    interface PortChannelOpener {
        SerialByteChannel open(PortCandidate candidate);
    }

    private final IntegrationContext context;
    private final DeviceRegistry deviceRegistry;
    private final RegistryProjection registryProjection;
    private final Path dataDirectory;
    private final Clock clock;
    private final Function<Object, SerialByteChannel> channelOpener;
    private final PortLocator.PortEnumerator portEnumerator;
    private final PortChannelOpener portChannelOpener;
    /**
     * Resolves a configured path to its canonical device node (M9.6-RO DP-4):
     * the production binding is {@link PortLocator#realPathCanonicalizer()}
     * (udev aliases resolve to their real nodes); tests inject pure maps.
     * Consulted only on the pinned-path capture arm and the pinned-only reopen
     * rule — never on the happy enumerated arm.
     */
    private final UnaryOperator<String> pathCanonicalizer;
    private final CountDownLatch stopSignal = new CountDownLatch(1);

    private StandardDeviceProfileRegistry profileRegistry;
    private ZigbeeDeviceCache cache;
    private ZigbeeAdoptionSlice adoption;
    /** IR-121: the declared-interval source behind {@link #expectedSilenceFor}. */
    private RegistryStalenessResolver declaredIntervals;
    private EzspAshTransport transport;
    private EzspCoordinatorProtocol protocol;
    private PendingInterviewQueue interviewQueue;
    private ZclIngestionUnit ingestion;
    private ZigbeeCommandHandler commandHandler;
    private ReportingConfigurator reporting;
    private StandardAvailabilityTracker availabilityTracker;
    private EntityAvailabilityPublisher availabilityPublisher;
    private NetworkParameterStore parameterStore;
    private PortLocator portLocator;
    private PortWatchdog watchdog;
    private volatile SerialByteChannel productionChannel;
    private PortIdentity portIdentity;
    /**
     * The normalized {@link #ADOPT_DEVICES_KEY} accept list (raw IEEE values),
     * read once at {@link #initialize()}; empty means nothing ever adopts.
     */
    private Set<Long> adoptAcceptList = Set.of();
    /**
     * The permit-join window close instant, or {@code null} when no window is open.
     * Since PJ-2 it is WRITTEN by {@link #openPairingWindow} on the supervisor's
     * command executor and NULLED by three closers on three threads — the executor
     * (a superseding open), the run thread ({@link #closeWindowIfElapsed()} at the
     * end of {@link #runCycleOnce()}; {@link #attemptReopen()}, DP-B5 — the reset NCP
     * holds no window) and the supervisor's close thread ({@link #close()}); read by
     * {@link #isPermitJoinActive()} from query threads — {@code volatile} for
     * cross-thread visibility. {@link #currentWindow} is the window of record; the
     * deadline is its derived, cheap read.
     */
    private volatile Instant permitJoinDeadline;
    /**
     * The open window of record (PJ-2), or {@code null}. Three closers on three
     * threads race to close it, so every close is a CAS ({@code compareAndSet(w,
     * null)} or {@code getAndSet(null)}) and only the winner publishes
     * {@code permit_join_closed} — ONE close per window, never two.
     */
    private final AtomicReference<PairingWindow> currentWindow = new AtomicReference<>();
    /**
     * The admission epoch (PJ-2): bumped by every {@link #openPairingWindow} on the
     * command executor (the single writer); {@link #runCycleOnce()} compares it
     * against {@link #seenPermitJoinEpoch} at its top and clears the two
     * run-thread-confined sets below when it moved — the F-R4-1 "a window (re)open is
     * a fresh admission epoch" semantics, kept without touching those sets from the
     * executor.
     */
    private volatile long permitJoinEpoch;
    /** The epoch {@link #runCycleOnce()} last observed. Run-thread confined. */
    private long seenPermitJoinEpoch;
    /**
     * F-R4-1 (R-10 Row 10 (a)) — the once-per-(invocation, nwk) note for the
     * silent-rejoiner hook (H-ii) while the door is closed. Keyed by the 16-bit
     * network address (bounded by construction: at most 65536 entries) and
     * CLEARED at the top of the first {@link #runCycleOnce()} after every
     * {@link #openPairingWindow} (the epoch compare) — a window epoch is the
     * admission scope, so a device noted while closed is re-evaluated when the
     * operator reopens. Run-thread confined: written by the ingestion cycle only
     * (driven mode: the gate thread, sequentially); the open, which runs on the
     * command executor since PJ-2, never touches it — never a wall clock (SK-INV-02).
     */
    private final Set<Integer> rejoinWindowClosedNoted = new HashSet<>();
    /**
     * The network addresses whose coordinator lookup already ran this window
     * epoch — hit or miss (F-R4-1 §3): the unknown-sender branch is hot, so
     * the protocol lookup runs LAST and at most once per nwk per epoch. Same
     * bound, same clearing, same confinement as the note above.
     */
    private final Set<Integer> rejoinLookupAttempted = new HashSet<>();
    /**
     * LINK-READ: the instant of the last {@code zigbee.link_summary} emission
     * — {@link #initialize()}'s instant until the first one. Run-thread
     * confined like the two sets above: written and read by
     * {@link #runCycleOnce()} only, always from the injected clock.
     */
    private Instant lastLinkSummaryAt;

    /**
     * The canonical constructor (M9.6-RO shape). Exactly one transport source is
     * bound: {@code channelOpener} non-null selects DRIVEN mode (the rig's
     * scripted channel); otherwise {@code portEnumerator} +
     * {@code portChannelOpener} select PRODUCTION mode (§5.1).
     * {@code pathCanonicalizer} feeds the identity capture and the pinned-only
     * reopen rule (tests inject pure maps; production binds
     * {@link PortLocator#realPathCanonicalizer()}).
     */
    ZigbeeIntegrationAdapter(IntegrationContext context, DeviceRegistry deviceRegistry,
            RegistryProjection registryProjection,
            Path dataDirectory, Clock clock,
            Function<Object, SerialByteChannel> channelOpener,
            PortLocator.PortEnumerator portEnumerator,
            PortChannelOpener portChannelOpener,
            UnaryOperator<String> pathCanonicalizer) {
        this.context = context;
        this.deviceRegistry = deviceRegistry;
        this.registryProjection = registryProjection;
        this.dataDirectory = dataDirectory;
        this.clock = clock;
        this.channelOpener = channelOpener;
        this.portEnumerator = portEnumerator;
        this.portChannelOpener = portChannelOpener;
        this.pathCanonicalizer = pathCanonicalizer;
    }

    /** The pre-M9.6-RO production shape: the real-path canonicalizer binds. */
    ZigbeeIntegrationAdapter(IntegrationContext context, DeviceRegistry deviceRegistry,
            RegistryProjection registryProjection,
            Path dataDirectory, Clock clock,
            Function<Object, SerialByteChannel> channelOpener,
            PortLocator.PortEnumerator portEnumerator,
            PortChannelOpener portChannelOpener) {
        this(context, deviceRegistry, registryProjection, dataDirectory, clock,
                channelOpener, portEnumerator, portChannelOpener,
                PortLocator.realPathCanonicalizer());
    }

    /** The M9.4a driven-mode shape (the rig path) — behavior-identical. */
    ZigbeeIntegrationAdapter(IntegrationContext context, DeviceRegistry deviceRegistry,
            RegistryProjection registryProjection,
            Path dataDirectory, Clock clock,
            Function<Object, SerialByteChannel> channelOpener) {
        this(context, deviceRegistry, registryProjection, dataDirectory, clock,
                channelOpener, null, null);
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
                context.entityRegistry(), registryProjection, profileRegistry,
                context.eventPublisher(), clock,
                // §4 (M9.7-W2): the learned-zoneType source resolves LAZILY —
                // the ingestion unit is constructed later in this method, and
                // adoption only runs on the ingestion cycle after run() starts
                // (the factory's Supplier<RegistryProjection> R4 shape).
                this::learnedZoneTypeFor);
        // IR-121: the per-device silence limit's source — source 2 of Doc 03
        // §3.8's chain ONLY (no per-entity overrides, no global default: the
        // staleness knobs do not bear on availability), over the same registry
        // and capability catalog the state projection's resolver reads.
        declaredIntervals = new RegistryStalenessResolver(context.entityRegistry(),
                StandardCapabilities.all(), Map.of(), Optional.empty());
        adoptAcceptList = readAdoptAcceptList();
        // DP-6 (AMD-99 §3 boundary note): the adapter-local IEEE->id / entity /
        // binding maps rebuild FROM the projection-rebuilt registries (Phase 3
        // caught them up to the log head before Phase 6 started this adapter) —
        // never from private events. This is what makes ingestion and the pin-2
        // re-link arm work immediately post-restart; the announce-time
        // findByHardwareIdentifier fork then takes the LINKED arm naturally.
        rehydrateAdoptionMaps();
        // Driven mode rides the injected opener; production mode reuses the probe
        // channel (§5.1 — never a double-open): the opener returns the channel
        // bindTransport() already opened and probed.
        transport = new EzspAshTransport(clock,
                channelOpener != null ? channelOpener : ignored -> productionChannel);
        // Construction touches no files (INV-RF-03/INV-CE-02 hold).
        parameterStore = new PersistentNetworkParameterStore(dataDirectory, clock);
        protocol = new EzspCoordinatorProtocol(transport, parameterStore, clock);
        interviewQueue = new PendingInterviewQueue(clock);
        // J2a (IR-114): the boot re-proposal — the maps have rehydrated (DP-6
        // above) and the queue now exists; nothing has opened a port yet.
        proposeListedCachedDevices();
        // WU-AVAIL-SEED DP-1/DP-2 (supersedes the M9.6-AVAIL empty-map
        // posture): the tracker seeds from the sidecar — EVERY cached device
        // enters tracking with its last-known availability plus the DP-4
        // persisted evidence recency, so the timeout arms can fire from the
        // first cycle and a device silent since before the restart still
        // reaches an honest verdict. Seeding publishes NOTHING and a seeded
        // value never counts as fresh evidence (unknown recency = infinitely
        // stale). The M9.6-era starve trap does not recur: the served view
        // replays the log's last availability, which the seeded tracker state
        // mirrors by construction (both are written by the same transition
        // path), so a steady-state boot is event-quiet and convergence rides
        // honest evidence or an honest timeout verdict within one window.
        // PowerSource resolves through the cache record — uninterviewed
        // records carry 0 and N-5 gives every non-mains-proven device the
        // conservative 25 h window.
        Map<Long, Boolean> persistedAvailability = cache.availabilitySnapshot();
        Map<Long, Instant> persistedEvidence = cache.lastEvidenceSnapshot();
        Map<Long, StandardAvailabilityTracker.Seed> availabilitySeed =
                new HashMap<>();
        for (ZigbeeDeviceRecord record : cache.all()) {
            long ieee = record.ieeeAddress().value();
            availabilitySeed.put(ieee, new StandardAvailabilityTracker.Seed(
                    persistedAvailability.get(ieee),
                    persistedEvidence.get(ieee)));
        }
        availabilityPublisher = new EntityAvailabilityPublisher();
        availabilityTracker = new StandardAvailabilityTracker(clock,
                ieee -> cache.device(ieee)
                        .map(ZigbeeDeviceRecord::powerSource).orElse(0),
                this::expectedSilenceFor,
                availabilitySeed,
                availabilityPublisher);
        long fromSidecar = availabilitySeed.values().stream()
                .filter(seed -> seed.available() != null).count();
        // DP-5(a): the once-per-boot seed glance-point (unconditional — a
        // zero count is honest evidence the mechanism ran).
        log.info("zigbee.availability_seeded: devices={} from_sidecar={} "
                        + "unknown={}", availabilitySeed.size(), fromSidecar,
                availabilitySeed.size() - fromSidecar);
        ingestion = new ZclIngestionUnit(() -> protocol.drainPendingCallbacks(),
                new CacheDeviceResolver(), new AdapterIngestionListener(),
                new ReportDeduplicator(clock), context.eventPublisher(), clock,
                protocol::sendZclFrame,   // F-7a: the enroll-response send seam
                // LEARN-PERSIST: the cache (constructed above) seeds the
                // learned map BEFORE the ingestion cycle can process any join
                // — rehydrate-before-joins holds by construction (DP-LP-4) —
                // and every successful wire learn writes back through the
                // cache's debounced persistence (DP-LP-3; no I/O on the
                // cycle thread).
                cache.learnedZoneTypeIds(),
                cache::recordLearnedZoneType,
                // ENERGY-READ R3/R4: what each metering endpoint declared at
                // adoption rehydrates under the same rule — seeded before any
                // cycle, so a plug's first report after a restart is scaled
                // with no read frame.
                cache.learnedMeteringFormatting());
        // DP-LP-6: the anti-vacuous boot glance-point — an operator confirms
        // persistence worked before opening any window. Count = entries
        // APPLIED post-tolerance; unconditional (count=0 is honest evidence
        // the mechanism ran, the adoption_maps_rehydrated precedent).
        log.info("zigbee.learned_zonetypes_rehydrated: count={}",
                ingestion.learnedZoneTypeCount());
        // ENERGY-READ: the same glance-point for the metering formatting —
        // count = (device, endpoint) records held; unconditional.
        log.info("zigbee.learned_metering_formatting_rehydrated: count={}",
                ingestion.learnedMeteringFormattingCount());
        // LINK-READ: the first link-summary period counts from here.
        lastLinkSummaryAt = clock.instant();
        // F-8: adoption completion invalidates the device's handler-table entry
        // (the classifier may have attached new capabilities; zone type may bind).
        // M9.6-AVAIL: it ALSO seeds the freshly adopted entities' availability —
        // on the bench the announce (the tracker's first contact) precedes
        // adoption, so the online edge fired while the device had no adopted
        // entities and published nothing; without the seed the new entities
        // would sit UNKNOWN in the view until the device's next offline/online
        // cycle.
        adoption.onAdopted(ieee -> {
            ingestion.invalidateHandlers(ieee);
            availabilityPublisher.seedFreshAdoption(ieee);
        });
        commandHandler = new ZigbeeCommandHandler(adoption,
                context.entityRegistry(), cache, profileRegistry,
                protocol::sendZclFrame, protocol::lookupNetworkAddress,
                context.eventPublisher(), clock);
        // M9.4-RPT §1/§2: the reporting configurator over its real EZSP binding
        // (cache-first address resolution — fresh from recordInterview at every
        // drive site; the protocol lookup is the fallback).
        // ENERGY-READ R3: the formatting store — the rejoin arm reads the
        // cache's persisted view; every formatting the drive READ lands in the
        // cache (debounced persistence — state mutation only on this thread)
        // AND in the ingestion unit, which drops the device's handler table.
        // The adoption listener above invalidates that table at adopt();
        // the drive runs after it, so the hand-off's own invalidation is what
        // guarantees the rebuilt table carries the formatting — whatever the
        // order, the next frame is scaled by what the device declared.
        reporting = new ReportingConfigurator(
                new EzspReportingOps(protocol, this::cachedNetworkAddress),
                new ReportingConfigurator.FormattingStore() {
                    @Override
                    public Optional<MeteringFormatting> cached(
                            IEEEAddress device, int endpoint) {
                        return cache.learnedMeteringFormatting(device, endpoint);
                    }

                    @Override
                    public void learned(IEEEAddress device, int endpoint,
                            MeteringFormatting formatting) {
                        cache.recordLearnedMeteringFormatting(device, endpoint,
                                formatting);
                        ingestion.recordLearnedMeteringFormatting(device,
                                endpoint, formatting);
                    }
                });
        if (channelOpener == null) {
            portLocator = new PortLocator(portEnumerator, pathCanonicalizer);
            watchdog = new PortWatchdog(clock, this::attemptReopen);
        }
        // IR-67: the boot-time capability reconcile — AFTER the ingestion unit
        // seeded the learned zone types (above; earlier, every IAS endpoint would
        // re-classify under the motion fallback) and the configurator, immediately
        // before zigbee.initialized. Per LAUNCH (the supervisor's transient
        // restart path runs initialize() too), pure CPU, before any cycle.
        reconcileCapabilities();
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
        warnIfPermitJoinKeyConfigured();   // PJ-2: the key opens nothing — one WARN if present
        productionLoop();
    }

    @Override
    public void close() {
        stopSignal.countDown();
        closeWindowOnShutdown();   // PJ-2: permit_join_closed(shutdown), best effort
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
        // closed. Null deadline (no window ever opened) reads false. Since PJ-2 the
        // deadline is written only by openPairingWindow (the endpoint's path).
        Instant deadline = permitJoinDeadline;
        return deadline != null && clock.instant().isBefore(deadline);
    }

    // ── The driven cycle (§G — M9.4a: gates own the cadence) ───────────────

    /**
     * One §G pass: drain → route → interviews due/expire → availability
     * timeouts (M9.6-AVAIL DP-4 — after the drain, so this cycle's RX evidence
     * counts before silence is judged) → the ten-minute link summary when due
     * (LINK-READ) → cache flush check → the elapsed-window close (PJ-2). The
     * admission-epoch compare runs FIRST (PJ-2): a window (re)open bumped the epoch
     * on the command executor; this cycle clears the once-per-epoch sets before it
     * ingests.
     */
    void runCycleOnce() {
        observeAdmissionEpoch();
        ingestion.processCycle();
        for (PendingInterviewQueue.Pending due : interviewQueue.due()) {
            interviewDevice(due.ieeeAddress(), due.source());
        }
        interviewQueue.expireStale();
        evaluateAvailabilityTimeouts();
        logLinkSummaryIfDue();
        cache.maybeFlush();
        closeWindowIfElapsed();
    }

    /**
     * LINK-READ — the ten-minute link summary
     * ({@link StandardAvailabilityTracker#LINK_SUMMARY_PERIOD}, where the
     * sampling rule and its basis live): once per period on the injected
     * clock, ONE INFO line per device — the Home Automation frames counted
     * since the previous summary (the ingestion unit's counters, drained and
     * reset here) and the last link reading the tracker keeps. The gate is
     * TIME, not cycles: one cycle emits at most one summary however many
     * periods elapsed, and the next period counts from this emission. The
     * first period counts from {@link #initialize()}.
     *
     * <p>"Per device" is every device the cache knows plus any the counters
     * hold, in unsigned IEEE order: a device silent for the whole period
     * prints {@code frames=0} (a silent period is a count, never an absence),
     * and one that has not spoken this process prints the {@code -}
     * placeholders — never a default number. Runs after the availability
     * evaluation, so a silence verdict reached in the same cycle still reads
     * the undrained count on its {@code zigbee.availability_link} line.
     */
    private void logLinkSummaryIfDue() {
        Instant now = clock.instant();
        if (Duration.between(lastLinkSummaryAt, now)
                .compareTo(StandardAvailabilityTracker.LINK_SUMMARY_PERIOD) < 0) {
            return;
        }
        lastLinkSummaryAt = now;
        Map<Long, Long> frames = ingestion.drainFrameCounts();
        Set<Long> devices = new TreeSet<>(Long::compareUnsigned);
        devices.addAll(frames.keySet());
        for (ZigbeeDeviceRecord record : cache.all()) {
            devices.add(record.ieeeAddress().value());
        }
        for (Long ieee : devices) {
            IEEEAddress device = new IEEEAddress(ieee);
            LinkFields link = linkFields(device);
            log.info("zigbee.link_summary: device={} frames={} last_lqi={} "
                            + "last_rssi_dbm={} last_link_at={}",
                    device, frames.getOrDefault(ieee, 0L), link.lqi(),
                    link.rssiDbm(), link.at());
        }
    }

    /**
     * A device's kept link reading as the three log fields the two LINK-READ
     * lines share ({@code last_lqi= last_rssi_dbm= last_link_at=}); the instant
     * is ISO-8601 UTC. With no reading this process every field is the
     * {@link #NO_LINK_READING} placeholder — never a default number.
     */
    private LinkFields linkFields(IEEEAddress device) {
        return linkFields(availabilityTracker.lastLink(device).orElse(null),
                availabilityTracker.lastLinkAt(device).orElse(null));
    }

    /**
     * LINK-READ-2: the same three fields rendered from a transition's snapshot
     * (the listener's arguments) — never re-read through the tracker after the
     * unlock. The two are {@code null} together; a {@code null} prints the
     * placeholder.
     */
    private static LinkFields linkFields(LinkReading link, Instant at) {
        return new LinkFields(
                link == null ? NO_LINK_READING : Integer.toString(link.lqi()),
                link == null ? NO_LINK_READING : Integer.toString(link.rssiDbm()),
                at == null ? NO_LINK_READING : at.toString());
    }

    /** The rendered fields of {@link #linkFields(IEEEAddress)}. */
    private record LinkFields(String lqi, String rssiDbm, String at) { }

    /**
     * The M9.6-AVAIL DP-4 absence evaluation: battery devices past their 25 h
     * silence transition offline inside {@code evaluateTimeouts()}; each
     * returned MAINS ping candidate gets ONE ZCL Basic read on this run thread
     * — the load-bearing arm of never-false-ALIVE in the other direction
     * (without it a dead mains device stays ALIVE forever; silence alone never
     * marks a mains device offline). The ping result is genuine
     * device-originated evidence and is the ONLY {@code recordCommandResult}
     * caller (DP-7: the dispatch path's boolean is NCP acceptance, not device
     * evidence — wiring it would fabricate liveness).
     */
    private void evaluateAvailabilityTimeouts() {
        for (IEEEAddress candidate : availabilityTracker.evaluateTimeouts()) {
            Instant pingStart = clock.instant();
            PingOutcome outcome = pingBasic(candidate);
            Instant pingEnd = clock.instant();
            // WU-AVAIL-SEED DP-5(b): the per-ping instrument — the arm F-14's
            // instruments could not see. rtt derives from the injected clock.
            log.debug("zigbee.availability_ping: device={} outcome={} rttMs={}",
                    candidate, outcome.token,
                    Duration.between(pingStart, pingEnd).toMillis());
            if (outcome == PingOutcome.OK) {
                // A ping reply is device-originated evidence (DP-4).
                cache.recordEvidence(candidate, pingEnd);
            }
            availabilityTracker.recordCommandResult(candidate,
                    outcome == PingOutcome.OK, pingEnd);
        }
    }

    /**
     * IR-121: a device's declared silence limit — the SMALLEST expected report
     * interval declared across its adopted entities' capabilities, resolved per
     * entity through a {@link RegistryStalenessResolver} built with NO overrides
     * and NO global default (source 2 of Doc 03 §3.8's chain only). Empty when no
     * entity declares one — the tracker's 25 h window then applies. Read at
     * every evaluation on the run thread, OUTSIDE the tracker's lock (LTD-11);
     * a registry failure propagates to the tracker's per-cycle WARN arm.
     */
    private Optional<Duration> expectedSilenceFor(IEEEAddress device) {
        Duration smallest = null;
        for (EntityId entityId : adoption.entitiesFor(device).values()) {
            Optional<Duration> declared = declaredIntervals.thresholdFor(entityId);
            if (declared.isPresent()
                    && (smallest == null || declared.get().compareTo(smallest) < 0)) {
                smallest = declared.get();
            }
        }
        return Optional.ofNullable(smallest);
    }

    /** The DP-5(b) per-ping outcome vocabulary ({@code ok|timeout|error}). */
    private enum PingOutcome {
        OK("ok"), TIMEOUT("timeout"), ERROR("error");

        private final String token;

        PingOutcome(String token) {
            this.token = token;
        }
    }

    /**
     * One availability ping: a ZCL global Read Attributes of Basic attribute
     * {@code 0x0000} (ZCLVersion) over the existing {@code zclGlobalExchange}
     * seam. {@code TIMEOUT} is wire silence; {@code ERROR} — the honest
     * unreachable-by-measurement verdict — covers the no-record /
     * unknown-address cases (a mains candidate is interviewed in practice; a
     * device whose address we lost self-heals on its next RX, which re-indexes
     * and re-edges). Every non-OK outcome records {@code responded=false}. An
     * {@link EzspCommandTimeoutException} (the NCP itself not answering)
     * propagates to the production loop's existing watchdog arm — coordinator
     * trouble is never device evidence.
     */
    private PingOutcome pingBasic(IEEEAddress device) {
        Optional<ZigbeeDeviceRecord> record = cache.device(device);
        if (record.isEmpty() || record.get().networkAddress()
                == ZigbeeDeviceCache.NETWORK_ADDRESS_UNKNOWN) {
            return PingOutcome.ERROR;
        }
        return protocol.zclGlobalExchange(record.get().networkAddress(),
                firstApplicationEndpoint(record.get()), 0x0000,
                ZclCodec.COMMAND_READ_ATTRIBUTES, new byte[] {0x00, 0x00},
                ZclCodec.COMMAND_READ_ATTRIBUTES_RESPONSE,
                AVAILABILITY_PING_TIMEOUT_MILLIS).isPresent()
                        ? PingOutcome.OK : PingOutcome.TIMEOUT;
    }

    /**
     * The ping target endpoint: the record's first application endpoint in
     * wire order (the interview's EP-selection rule — Green Power EP 242 and
     * GP-profile endpoints are never pinged), with the adapter-wide EP-1
     * convention as the defensive fallback for endpoint-less records.
     */
    private static int firstApplicationEndpoint(ZigbeeDeviceRecord record) {
        List<EndpointDescriptor> endpoints = record.endpoints();
        if (endpoints != null) {
            for (EndpointDescriptor endpoint : endpoints) {
                if (endpoint.endpointId()
                        != InterviewStateMachine.GREEN_POWER_ENDPOINT_ID
                        && endpoint.profileId()
                                != InterviewStateMachine.GREEN_POWER_PROFILE_ID) {
                    return endpoint.endpointId();
                }
            }
        }
        return 1;
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
     * never a double-open). Records the port identity for reopen (§5.6): an
     * enumerated port captures as-is; a synthesized/unidentified port resolves
     * through {@link #captureIdentity} (M9.6-RO DP-1) so an alias-pinned config
     * no longer captures an unmatchable identity. The one capture INFO
     * ({@code zigbee.port_identity_captured}, DP-5) fires on every arm.
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
        portIdentity = captureIdentity(port);
        log.info("zigbee.port_identity_captured: stableId={} vendorId={} "
                        + "productId={} pinnedOnly={}",
                portIdentity.stableId(),
                Integer.toHexString(portIdentity.vendorId()),
                Integer.toHexString(portIdentity.productId()),
                portIdentity.isPinnedOnly());
    }

    /**
     * Captures the reopen identity for the bound port (M9.6-RO DP-1).
     *
     * <p>An enumerated port ({@code vendorId() >= 0}) captures directly — the
     * pre-existing happy arm, untouched. A port WITHOUT a USB identity (the
     * {@code serial_port} synthesis arm, or an enumerated node the platform
     * could not identify) canonicalizes its path (udev alias → real device
     * node) and re-scans the enumeration for the candidate at that node: found
     * with a real USB identity ⇒ the capture is that candidate's REAL identity
     * (by-id stable path + VID:PID) and every reopen tier works. Only when the
     * path resolves to no identified candidate does the capture fall back to
     * the honest pinned-only sentinel ({@code 0/0} + the pinned path,
     * {@link PortIdentity#isPinnedOnly()}) — which reopens by canonicalized
     * path equality only.
     */
    private PortIdentity captureIdentity(PortCandidate port) {
        if (port.vendorId() >= 0) {
            return PortLocator.identityFor(port, TransportProbe.Kind.EZSP.name());
        }
        String canonical = pathCanonicalizer.apply(port.systemPath());
        return portEnumerator.enumerate().stream()
                .filter(c -> canonical.equals(c.systemPath()) && c.vendorId() >= 0)
                .findFirst()
                .map(real -> PortLocator.identityFor(real,
                        TransportProbe.Kind.EZSP.name()))
                .orElseGet(() -> new PortIdentity(0, 0, port.systemPath(),
                        TransportProbe.Kind.EZSP.name()));
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
            NetworkParameters formed = formNetwork();
            log.info("zigbee.network_formed: channel={} panId=0x{}",
                    formed.channel(), Integer.toHexString(formed.panId()));
        }
    }

    /**
     * First-run formation honoring the operator channel pin (M9.4-TCJ §B): a
     * present, in-range {@code integrations.zigbee.channel} key forms directly on
     * that channel (the energy scan never runs); an out-of-range value logs ONE
     * WARN and falls back to the scan — the schema validates upstream, so the
     * guard is the defensive floor, never the {@code NetworkParameters} range
     * throw; an absent key runs the §3.13 energy-scan selection unchanged.
     */
    private NetworkParameters formNetwork() {
        Optional<Integer> pinned = context.configAccess().getInt(CHANNEL_KEY);
        if (pinned.isPresent()) {
            int channel = pinned.get();
            if (channel >= CHANNEL_MIN && channel <= CHANNEL_MAX) {
                return protocol.formNetworkAutomatically(channel);
            }
            log.warn("zigbee.channel_pin_ignored: configured={} range={}-{}; the "
                            + "energy scan selects the channel",
                    channel, CHANNEL_MIN, CHANNEL_MAX);
        }
        return protocol.formNetworkAutomatically();
    }

    /**
     * PJ-2 (DP-PJ2-5) — the start path's ONLY use of {@link #PERMIT_JOIN_DURATION_KEY}:
     * a value present at production start is IGNORED with ONE WARN and NO frame; an
     * absent key logs nothing. Boot never opens a window — the window opens only by the
     * endpoint ({@link #openPairingWindow}). Package-private so the production-ladder
     * fixture (which drives the ladder to {@code awaitNetworkUp()} and never reaches
     * {@code run()}) can call it directly. Production only: the driven mode never
     * reaches the start path.
     */
    void warnIfPermitJoinKeyConfigured() {
        Optional<Integer> configured =
                context.configAccess().getInt(PERMIT_JOIN_DURATION_KEY);
        if (configured.isPresent()) {
            log.warn("zigbee.permit_join_key_ignored: configured={}s — the window opens only "
                    + "by POST /api/v1/integrations/{id}/permit-join (PJ-2)", configured.get());
        }
    }

    /**
     * PJ-2 (DP-PJ2-3) — opens the pairing window for an operator's request. Runs ONLY
     * on the supervisor's command executor (the {@link PairingWindowControl} contract)
     * and touches the protocol, {@link #permitJoinDeadline}, {@link #currentWindow} and
     * {@link #permitJoinEpoch} — nothing run-thread confined.
     *
     * <p>M9.4-TCJ §A.1: the Trust Center join enablement (policy &rarr; transient
     * well-known key) runs BEFORE the {@code permitJoin} frame — a MAC window without
     * the key-exchange enablement admits no Zigbee 3.0 device; a rejected enablement
     * propagates BEFORE the window opens, so a failed enablement never leaves a
     * half-open door and nothing here changes (never-false-ALIVE). Only after BOTH
     * frames are accepted: a prior window still of record closes first with ONE
     * {@code permit_join_closed} ({@code superseded} while its end is ahead, else
     * {@code elapsed} at its own end — the cycle had not yet observed it), then the
     * new window is stored, the admission epoch bumped, {@code permit_join_opened}
     * published and the window returned. The coordinator restarts its own timer on
     * the new {@code permitJoin}. Never called from {@code initialize()} (INV-RF-03),
     * from the start path, nor from the M9.4a driven/test cadence.</p>
     */
    @Override
    public PairingWindow openPairingWindow(PairingWindowRequest request) {
        Objects.requireNonNull(request, "request");
        int duration = request.durationSeconds();
        protocol.enablePreconfiguredKeyJoins();   // §A.1: policy → transient key
        protocol.permitJoin(duration);
        Instant opensAt = clock.instant();
        PairingWindow window = new PairingWindow(context.integrationId(), opensAt,
                opensAt.plusSeconds(duration), duration, request.reason(), request.actor());
        PairingWindow prior = currentWindow.get();
        if (prior != null && currentWindow.compareAndSet(prior, null)) {
            closePrior(prior, opensAt);
        }
        permitJoinDeadline = window.closesAt();
        currentWindow.set(window);
        permitJoinEpoch++;   // the executor is the single writer
        publishWindowEvent(EventTypes.PERMIT_JOIN_OPENED, new PermitJoinOpened(
                context.integrationId(), context.integrationType(), duration,
                request.reason(), request.actor(), opensAt, window.closesAt()));
        log.info("zigbee.permit_join_opened: duration={}s reason={} actor={}",
                duration, request.reason(), request.actor());
        return window;
    }

    /** Never-false-ALIVE: a window past its own end is not current, recorded or not. */
    @Override
    public Optional<PairingWindow> currentPairingWindow() {
        PairingWindow open = currentWindow.get();
        return open != null && clock.instant().isBefore(open.closesAt())
                ? Optional.of(open) : Optional.empty();
    }

    /**
     * PJ-2 — the run thread's half of the F-R4-1 epoch rule: a window (re)open bumped
     * {@link #permitJoinEpoch} on the command executor; the first cycle that observes
     * the move clears the closed-door notes and the once-per-nwk lookup set BEFORE this
     * cycle's ingestion, so a silent rejoiner noted while the door was closed is
     * re-evaluated now. The two sets stay run-thread confined.
     */
    private void observeAdmissionEpoch() {
        long epoch = permitJoinEpoch;
        if (epoch != seenPermitJoinEpoch) {
            rejoinWindowClosedNoted.clear();
            rejoinLookupAttempted.clear();
            seenPermitJoinEpoch = epoch;
        }
    }

    /**
     * PJ-2 (DP-PJ2-4) — the run thread's closer, at the end of every cycle: a window
     * whose end has passed is closed with {@code permit_join_closed(elapsed)} whose
     * {@code closedAt} is the window's own end, never the cycle's clock (the cycle
     * lags by up to {@value #PRODUCTION_CYCLE_MILLIS} ms). A lost CAS publishes
     * nothing; the deadline is nulled after the CAS.
     */
    private void closeWindowIfElapsed() {
        PairingWindow open = currentWindow.get();
        if (open == null || clock.instant().isBefore(open.closesAt())) {
            return;
        }
        if (currentWindow.compareAndSet(open, null)) {
            permitJoinDeadline = null;
            publishWindowEvent(EventTypes.PERMIT_JOIN_CLOSED, new PermitJoinClosed(
                    context.integrationId(), context.integrationType(),
                    PermitJoinClosed.CAUSE_ELAPSED, open.opensAt(), open.closesAt()));
        }
    }

    /** The superseding open's close of the prior window (DP-PJ2-3; E5). */
    private void closePrior(PairingWindow prior, Instant now) {
        boolean stillOpen = now.isBefore(prior.closesAt());
        publishWindowEvent(EventTypes.PERMIT_JOIN_CLOSED, new PermitJoinClosed(
                context.integrationId(), context.integrationType(),
                stillOpen ? PermitJoinClosed.CAUSE_SUPERSEDED : PermitJoinClosed.CAUSE_ELAPSED,
                prior.opensAt(), stillOpen ? now : prior.closesAt()));
    }

    /**
     * The two unconditional closers' shared body (the transport reopen on the run
     * thread; the shutdown on the supervisor's close thread): takes the window of
     * record with {@code getAndSet(null)} — the winner publishes
     * {@code permit_join_closed(cause, closedAt = now)}; a null means another closer
     * won or no window was open, and nothing is published. The deadline is nulled
     * after the CAS either way.
     */
    private void closeWindow(String cause) {
        PairingWindow open = currentWindow.getAndSet(null);
        if (open != null) {
            publishWindowEvent(EventTypes.PERMIT_JOIN_CLOSED, new PermitJoinClosed(
                    context.integrationId(), context.integrationType(), cause,
                    open.opensAt(), clock.instant()));
        }
        permitJoinDeadline = null;
    }

    /** {@link #close()}'s best-effort close: the record must never block the shutdown. */
    private void closeWindowOnShutdown() {
        try {
            closeWindow(PermitJoinClosed.CAUSE_SHUTDOWN);
        } catch (RuntimeException failure) {
            log.warn("zigbee.permit_join_close_failed: cause=shutdown: {} — the close "
                    + "proceeds; the record has a gap", failure.getMessage());
        }
    }

    /**
     * PJ-2 — the window events ride the adapter's own publish form (the posture
     * re-emit's): a root draft on the integration subject, origin INTEGRATION, event
     * time = the adapter's clock. The supervisor publishes its lifecycle events on the
     * SAME subject, so a sequence conflict is live, not theoretical: ONE WARN, and the
     * window state stands — the NCP's state is the truth; the record's gap is the
     * finding.
     */
    private void publishWindowEvent(String eventType, DomainEvent payload) {
        try {
            context.eventPublisher().publishRoot(new EventDraft(
                    eventType, 1, clock.instant(),
                    SubjectRef.integration(context.integrationId()),
                    EventPriority.NORMAL, EventOrigin.INTEGRATION,
                    payload, null, null));
        } catch (SequenceConflictException conflict) {
            log.warn("zigbee.permit_join_event_conflict: type={}: {} — the NCP's window "
                    + "state is the truth; the record has a gap", eventType,
                    conflict.getMessage());
        }
    }

    /**
     * The production cycle (§5.1): pump inbound (the bounded read IS the park —
     * LTD-01, no sleep), run one §G pass, keepalive-tick, and feed transport
     * failures to the watchdog; while unhealthy, tick the reopen backoff and park
     * on the stop latch (responsive to {@code close()}).
     *
     * <p>FAILCHAN §10-O: a transport failure or command timeout that surfaces AFTER
     * {@link #close()} counted the stop latch down is the orderly consequence of the
     * close racing the read-as-park — classified at the catch as
     * {@code zigbee.transport_closed_orderly} (INFO) and the loop exits; the watchdog
     * is never fed. Package-private: the §10-O scenario test drives the loop directly
     * over the scripted transport after the §5.1 ladder (the drive-seam pattern).</p>
     */
    void productionLoop() throws InterruptedException {
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
                    if (closeRequested()) {
                        // FAILCHAN §10-O: close() counted the latch down and closed the
                        // transport while this loop was parked in the read — the read
                        // failing is the ORDERLY consequence, not a port death. Never
                        // fed to the watchdog (a reopen would race the stop); the loop
                        // ends here instead of at the next latch check.
                        log.info("zigbee.transport_closed_orderly: {}", failure.getMessage());
                        break;
                    }
                    log.warn("zigbee.transport_failed: {} — the watchdog owns "
                            + "recovery", failure.getMessage());
                    watchdog.onReadError();
                } catch (EzspCommandTimeoutException timeout) {
                    if (closeRequested()) {
                        // The same guard: a command timing out against a transport a
                        // concurrent close() just took down is orderly too.
                        log.info("zigbee.transport_closed_orderly: {}", timeout.getMessage());
                        break;
                    }
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
     * True once {@link #close()} has counted the stop latch down — the §10-O
     * classifier: a transport failure observed after this point is the close, not
     * a port death.
     */
    private boolean closeRequested() {
        return stopSignal.getCount() == 0;
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
            // DP-B5 (M9.5-DURb): the reset NCP holds no join window, no TC
            // policy, no transient key — closing the window of record keeps
            // isPermitJoinActive() from reading stale-true past a reopen (the
            // M9.4-TCJ recorded limitation, closed). Reopen ≠ boot: the window
            // is NOT renewed; the operator re-opens by the endpoint (PJ-2), and
            // the close is recorded as permit_join_closed(transport_reopened).
            closeWindow(PermitJoinClosed.CAUSE_TRANSPORT_REOPENED);
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

    /** J2a's test seam: the interview queue as {@code initialize()} left it. */
    PendingInterviewQueue interviewQueue() {
        return interviewQueue;
    }

    EzspCoordinatorProtocol coordinatorProtocol() {
        return protocol;
    }

    /**
     * One interview attempt for a due queue entry; {@code source} is the
     * entry's admission provenance (F-R4-1 — announce or rejoin), which the
     * proposal renders on its LOG LINE only, never in an event payload.
     */
    private void interviewDevice(IEEEAddress ieee,
            PendingInterviewQueue.Source source) {
        try {
            InterviewResult interview = protocol.interview(ieee);
            DeviceProfile matchedProfile = profileRegistry.findProfile(interview)
                    .orElse(null);
            String matchedProfileId =
                    matchedProfile == null ? null : matchedProfile.profileId();
            cache.recordInterview(interview, matchedProfileId);
            interviewQueue.complete(ieee);
            ZigbeeAdoptionSlice.DiscoveryOutcome outcome =
                    adoption.onDeviceDiscovered(interview, matchedProfileId, source);
            adoptIfAccepted(interview, outcome, matchedProfile);
        } catch (RuntimeException failure) {
            log.warn("zigbee.interview_failed: device={}: {}", ieee,
                    failure.getMessage());
            interviewQueue.recordFailure(ieee);
        }
    }

    /**
     * The M9.4-ADP acceptance gate (Doc 02 §3.12 Stage 3) + the M9.4-RPT §2
     * reporting drive: a FRESH proposal for a config-listed device with a
     * COMPLETE interview adopts immediately, on this ingestion thread, and the
     * completed adoption drives {@code configureDevice}; a LINKED outcome never
     * adopts — the Stage-2 re-link creates no proposal and needs no consent
     * (first-adoption-only); the guard is outcome-driven, never
     * exception-driven — but a re-link DOES drive {@code onRejoin} (the
     * power-cycle re-announce is the measured reporting-config-wipe class).
     * Both drive arms run AFTER the adoption/re-link bookkeeping completes and
     * NEVER gate it (Doc 02 §3.12 staging — outcomes are recorded posture, not
     * failures). An unlisted device's proposal sits silently (the conservative
     * default); a listed device stalled PARTIAL is loud — the operator listed
     * it expecting adoption, and a later re-announce re-interviews and
     * re-proposes. An {@code adopt()} failure propagates to the cycle's
     * existing interview error handling (one WARN naming the device; the
     * ingestion loop survives — and skips the drive: nothing was adopted).
     */
    private void adoptIfAccepted(InterviewResult interview,
            ZigbeeAdoptionSlice.DiscoveryOutcome outcome, DeviceProfile profile) {
        if (outcome == ZigbeeAdoptionSlice.DiscoveryOutcome.LINKED) {
            driveReporting(interview, profile, true);
            return;
        }
        if (!adoptAcceptList.contains(interview.ieeeAddress().value())) {
            return;
        }
        if (interview.interviewStatus() != InterviewStatus.COMPLETE) {
            log.warn("zigbee.proposal_incomplete_not_adopted: device={} status={}",
                    interview.ieeeAddress(), interview.interviewStatus());
            return;
        }
        log.info("zigbee.proposal_accepted: device={} source=config",
                interview.ieeeAddress());
        adoption.adopt(interview.ieeeAddress());
        driveReporting(interview, profile, false);
    }

    /**
     * The M9.4-RPT §2 adoption-path drive: configures reporting for the freshly
     * adopted / re-linked device (synchronous, same ingestion thread), emits
     * the per-device positive-evidence INFO (the anti-vacuous mandate — the
     * next bench log is measurement, not absence-of-failure), and routes the
     * measured facts into the installed confirmation surface (§3). Reporting
     * outcomes NEVER gate or fail the adopt/re-link: the ops seam returns
     * typed results, and an abnormal throw (a dying transport mid-drive)
     * degrades to ONE WARN — the device stays adopted, the loop survives.
     */
    private void driveReporting(InterviewResult interview, DeviceProfile profile,
            boolean rejoin) {
        IEEEAddress ieee = interview.ieeeAddress();
        try {
            List<ReportingPostureFact> facts = rejoin
                    ? reporting.onRejoin(ieee, interview.endpoints(), profile)
                    : reporting.configureDevice(ieee, interview.endpoints(),
                            profile);
            long verified = facts.stream()
                    .filter(ConfirmationOverrideInstaller::verifiedFact)
                    .count();
            log.info("zigbee.reporting_configured: device={} clusters={} "
                            + "verified={} degraded={}",
                    ieee, facts.size(), verified, facts.size() - verified);
            // IR-123: the per-cluster line the aggregate only counts (J1's
            // fork 2 found none; the R6 reading) — one INFO per fact, the
            // enum names lower-cased as grep-stable tokens, ZCL ids as 4 hex.
            for (ReportingPostureFact fact : facts) {
                log.info("zigbee.reporting_cluster: device={} endpoint={} "
                                + "cluster=0x{} attribute=0x{} posture={} "
                                + "authoritative={}",
                        ieee, fact.endpoint(),
                        String.format("%04X", fact.clusterId()),
                        String.format("%04X", fact.attributeId()),
                        fact.reportingPosture().name().toLowerCase(Locale.ROOT),
                        fact.reportsAuthoritative().name().toLowerCase(Locale.ROOT));
            }
            applyPostureRouting(ieee, facts);
        } catch (RuntimeException failure) {
            log.warn("zigbee.reporting_drive_failed: device={}: {}", ieee,
                    failure.getMessage());
        }
    }

    /**
     * The M9.4-RPT §3 posture routing: overlays the drive's measured facts onto
     * every installed entity of the device (the install-time
     * never-false-CONFIRMED fence), re-registering an entity only when a
     * capability actually downgraded — the update-if-differs shape. Post-DUR
     * (AMD-99 F1 / DP-4) the update rides an idempotent {@code entity_registered}
     * RE-EMIT applied through the {@link RegistryProjection} — never a direct
     * {@code updateEntity} (REG-INV-1): publish first (durable — write-ahead),
     * apply second; a publish conflict skips the apply and degrades to ONE WARN
     * (the next drive re-measures; posture routing never gates the re-link).
     */
    private void applyPostureRouting(IEEEAddress ieee,
            List<ReportingPostureFact> facts) {
        Optional<DeviceId> deviceId = adoption.deviceIdFor(ieee);
        if (deviceId.isEmpty()) {
            return;   // defensive: no registered device — nothing installed
        }
        for (Entity entity : context.entityRegistry()
                .listEntitiesByDevice(deviceId.get())) {
            List<CapabilityInstance> routed =
                    ConfirmationOverrideInstaller.applyPostureFacts(ieee,
                            entity.endpointIndex(), facts, entity.capabilities());
            if (!routed.equals(entity.capabilities())) {
                EntityRegisteredEvent reEmit = RegistryEventMapper.toPayload(
                        new Entity(
                                entity.entityId(), entity.entitySlug(),
                                entity.entityType(), entity.displayName(),
                                entity.deviceId(), entity.endpointIndex(),
                                entity.areaId(), entity.enabled(), entity.labels(),
                                routed, entity.entityRole(), entity.createdAt()));
                try {
                    context.eventPublisher().publishRoot(new EventDraft(
                            EventTypes.ENTITY_REGISTERED, 1, clock.instant(),
                            SubjectRef.entity(entity.entityId()),
                            EventPriority.NORMAL, EventOrigin.INTEGRATION,
                            reEmit, null, null));
                } catch (SequenceConflictException conflict) {
                    log.warn("zigbee.posture_reemit_conflict: entity={}: {} — the "
                                    + "registry keeps the pre-routing posture; the "
                                    + "next drive re-measures",
                            entity.entityId(), conflict.getMessage());
                    continue;   // write-ahead: never apply an unpersisted re-emit
                }
                registryProjection.applyEntityRegistered(reEmit);
            }
        }
    }

    /**
     * DP-6 startup rehydration (AMD-99 §3 boundary note): enumerates the
     * registry devices this adapter owns (matching {@code integrationId} and
     * carrying a {@code zigbee} hardware identifier) and re-links each —
     * rebuilding the slice's IEEE&rarr;id / entity / binding maps from the
     * Phase-3-rebuilt registry view. The matched profile id comes from the
     * adapter-local {@code zigbee-devices.json} cache (already
     * restart-persistent), or {@code null} when the cache carries none.
     */
    private void rehydrateAdoptionMaps() {
        int rehydrated = 0;
        for (Device device : deviceRegistry.listAllDevices()) {
            if (!context.integrationId().equals(device.integrationId())) {
                continue;
            }
            Optional<HardwareIdentifier> zigbeeIdentity =
                    device.hardwareIdentifiers().stream()
                            .filter(id -> ZigbeeAdoptionSlice.HARDWARE_NAMESPACE
                                    .equals(id.namespace()))
                            .findFirst();
            if (zigbeeIdentity.isEmpty()) {
                continue;
            }
            IEEEAddress ieee =
                    IEEEAddress.fromHexString(zigbeeIdentity.get().value());
            String matchedProfileId = cache.device(ieee)
                    .map(ZigbeeDeviceRecord::matchedProfileId)
                    .orElse(null);
            adoption.relink(ieee, device, matchedProfileId);
            rehydrated++;
        }
        // DP-B3 (M9.5-DURb): UNCONDITIONAL INFO — the count prints at zero too.
        // Iteration 5b measured this line SILENT at devices=2 (DEBUG under the
        // bench's INFO root): absence read as absence-of-the-mechanism, the
        // vacuous-silence class. The token is FROZEN (the 5b/acceptance-run
        // boot glance-point).
        log.info("zigbee.adoption_maps_rehydrated: devices={}", rehydrated);
    }

    /**
     * J2a (IR-114) — the boot re-proposal. Once per {@code initialize()}, right
     * after {@link #rehydrateAdoptionMaps()} and the queue's construction: every
     * IEEE on the adopt-accept list that the announce cache knows with a usable
     * network address and the adoption maps do not is scheduled on the interview
     * queue with {@link PendingInterviewQueue.Source#BOOT_LISTED}; the interview
     * then proposes through the slice's one proposal site, and the config
     * acceptance gate adopts as it does for an announce. In-memory only — no
     * registry write, no event (REG-INV-1 holds by construction); a sleepy
     * device that does not answer rides the queue's retry ladder and parks.
     *
     * <p>The miss this closes: before J2a a listed, cached, unadopted device got
     * NOTHING at boot — the boot read the list once and rehydrated the REGISTRY
     * only, so a sensor that never re-announced stayed un-proposed. Every skip
     * is a DEBUG with its reason; every schedule is ONE INFO.
     */
    private void proposeListedCachedDevices() {
        for (long listed : adoptAcceptList) {
            IEEEAddress ieee = new IEEEAddress(listed);
            Optional<ZigbeeDeviceRecord> record = cache.device(ieee);
            if (record.isEmpty()) {
                log.debug("zigbee.boot_listed_skipped: device={} reason=not_cached", ieee);
                continue;
            }
            int networkAddress = record.get().networkAddress();
            if (networkAddress == ZigbeeDeviceCache.NETWORK_ADDRESS_UNKNOWN) {
                log.debug("zigbee.boot_listed_skipped: device={} reason=address_unknown",
                        ieee);
                continue;
            }
            if (adoption.deviceIdFor(ieee).isPresent()) {
                log.debug("zigbee.boot_listed_skipped: device={} reason=already_adopted",
                        ieee);
                continue;
            }
            interviewQueue.schedule(ieee, networkAddress,
                    PendingInterviewQueue.Source.BOOT_LISTED);
            log.info("zigbee.boot_listed_candidate: device={} nwk=0x{}",
                    ieee, Integer.toHexString(networkAddress));
        }
    }

    /**
     * IR-67 (DP-IR67-3): the boot-time capability reconcile through AMD-59's seam.
     * {@code context.discovery()} is null for the fixtures that build the context
     * with five null tails (row 29) — ONE WARN and no pass; production never takes
     * that arm (the descriptor declares DISCOVERY and the supervisor provisions
     * it). Otherwise the slice's pass runs over every cached record and ONE
     * unconditional INFO summarizes the launch — the count prints at zero too
     * (the DP-B3 precedent of {@code zigbee.adoption_maps_rehydrated}).
     */
    private void reconcileCapabilities() {
        if (context.discovery() == null) {
            log.warn("zigbee.capability_reconcile_skipped: reason=no_discovery_services");
            return;
        }
        ZigbeeAdoptionSlice.ReconcileSummary summary = adoption.reconcileCapabilities(
                cache.all(), context.discovery().capabilityPublisher());
        log.info("zigbee.capability_reconcile: endpoints={} entities={} added={} shrink={} "
                        + "relearned={} unbound={}",
                summary.endpoints(), summary.entities(), summary.added(), summary.shrink(),
                summary.relearned(), summary.unbound());
    }

    /**
     * The cache-first network-address view the reporting binding resolves
     * through (empty on a miss or the F-6 unknown sentinel — the protocol
     * lookup is the binding's fallback).
     */
    private OptionalInt cachedNetworkAddress(IEEEAddress ieee) {
        return cache.device(ieee)
                .filter(record -> record.networkAddress()
                        != ZigbeeDeviceCache.NETWORK_ADDRESS_UNKNOWN)
                .map(record -> OptionalInt.of(record.networkAddress()))
                .orElse(OptionalInt.empty());
    }

    /**
     * Reads {@link #ADOPT_DEVICES_KEY} once at initialize (the permit-join
     * pattern). The list is user input: a malformed entry logs ONE WARN and is
     * skipped, never failing the boot; an absent key (or a non-list value — the
     * schema validates upstream) reads as empty, so nothing ever adopts.
     * Device identities are user data: never logged at INFO here (the one
     * DEBUG line carries a count only).
     */
    private Set<Long> readAdoptAcceptList() {
        Object configured = context.configAccess().getConfig().get(ADOPT_DEVICES_KEY);
        if (!(configured instanceof List<?> entries)) {
            return Set.of();
        }
        Set<Long> accepted = new HashSet<>();
        for (Object entry : entries) {
            if (entry instanceof String text
                    && ADOPT_ENTRY_SHAPE.matcher(text).matches()) {
                accepted.add(IEEEAddress.fromHexString(text).value());
            } else {
                log.warn("zigbee.adopt_list_entry_invalid: value={}", entry);
            }
        }
        log.debug("zigbee.adopt_list_loaded: entries={}", accepted.size());
        return Set.copyOf(accepted);
    }

    /**
     * The adoption slice's §4 zone-type source (M9.7-W2): the ingestion unit's
     * wire-learned IAS zone type — LEARNED state only; empty before the unit
     * exists ({@code initialize()} constructs the slice first) or when nothing
     * was learned, and classification then takes the DP-6 motion fallback.
     * Reads in-memory cycle-thread state only: the adoption path runs on the
     * same ingestion cycle thread as {@code processCycle()}. The resolver's
     * {@code zoneTypeFor} MOTION stub below is a DIFFERENT seam — the
     * handler-table fallback, which the unit's learned-first read already
     * outranks (F-7a).
     */
    private Optional<ZoneType> learnedZoneTypeFor(IEEEAddress device) {
        ZclIngestionUnit unit = ingestion;
        return unit == null ? Optional.empty() : unit.learnedZoneType(device);
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
        public void onFrame(IEEEAddress device, Optional<LinkReading> link) {
            cache.recordFrame(device);   // the cache's last-seen — a DIFFERENT
            interviewQueue.onFrameReceived(device);   // class than the tracker's
            // M9.6-AVAIL DP-3: every device-originated RX is liveness — this
            // seam is the documented availability feed, pre-dedup by design
            // (a duplicate frame is still the device talking). WU-AVAIL-SEED
            // DP-4: the SAME instant rides the sidecar's evidence recency, so
            // tracker and persisted clock can never disagree. LINK-READ: the
            // frame's last-hop reading rides the same call — kept in memory
            // by the tracker, never persisted; an empty one is still liveness.
            Instant now = clock.instant();
            cache.recordEvidence(device, now);
            availabilityTracker.recordFrame(device, now, link);
        }

        /**
         * F-R4-1 hook H-ii (the evidenced case): the window is the FIRST
         * gate, the once-per-nwk set the second, the coordinator lookup next
         * and — F-R4-1b — the air LAST, after a clean table miss only; the
         * ONE set bounds the pair at most once per nwk per window epoch (the
         * branch is hot — every frame from every unknown device lands here).
         */
        @Override
        public void onRejoinCandidate(int networkAddress, int clusterId) {
            if (!isPermitJoinActive()) {
                if (rejoinWindowClosedNoted.add(networkAddress)) {
                    log.info("zigbee.rejoin_ignored_window_closed: nwk=0x{} "
                                    + "cluster=0x{}",
                            Integer.toHexString(networkAddress),
                            Integer.toHexString(clusterId));
                }
                return;
            }
            // Resolution order (F-R4-1 §3; F-R4-1b DP-2): (1) the cache's
            // NWK→IEEE view — a structural miss on this arm, re-checked so the
            // method stands on its own; (2) the coordinator's address table,
            // once per nwk per epoch, hit or miss; (3) the AIR — a ZDP
            // IEEE_addr_req to the device itself, ONLY after a clean (2) miss:
            // 0x0061 reads the coordinator's OWN table, which a router-parented
            // sleepy device never enters (R-4b's measured miss,
            // lookup_eui64_failed nwk=0x15ac status=0x1). The ONE set bounds
            // the pair — the coordinator is asked at most once and the air at
            // most once per nwk per epoch. A miss on both is an unresolved
            // candidate — never a synthesized identity. (3) blocks this run
            // thread for up to IEEE_ADDR_REQ_TIMEOUT_MILLIS, inside an open
            // window only — the interview that follows an admission already
            // does the same under the same bound.
            Optional<IEEEAddress> resolved =
                    cache.deviceForNetworkAddress(networkAddress);
            int admittedAddress = networkAddress;
            if (resolved.isEmpty()) {
                if (!rejoinLookupAttempted.add(networkAddress)) {
                    return;   // already asked this epoch: nothing new to say
                }
                try {
                    resolved = protocol.lookupIeee(networkAddress);
                } catch (EzspCommandException | EzspFormatException
                        | IllegalStateException failure) {
                    // A rejected or malformed exchange is an unresolved
                    // candidate and does NOT fall through to the air (DP-2: a
                    // rejected coordinator exchange is no evidence the air
                    // will answer). An unanswering NCP (the command timeout)
                    // and a dead transport propagate to the production loop's
                    // watchdog arms — coordinator trouble is never device
                    // evidence.
                    log.warn("zigbee.rejoin_candidate_unresolved: nwk=0x{} "
                                    + "cluster=0x{} reason={}",
                            Integer.toHexString(networkAddress),
                            Integer.toHexString(clusterId), failure.getMessage());
                    return;
                }
                if (resolved.isEmpty()) {
                    Optional<ZdoCodec.IeeeAddressResponse> answer;
                    try {
                        answer = protocol.requestIeeeAddress(networkAddress,
                                IEEE_ADDR_REQ_TIMEOUT_MILLIS);
                    } catch (EzspCommandException | EzspFormatException
                            | IllegalStateException failure) {
                        log.warn("zigbee.rejoin_candidate_unresolved: nwk=0x{} "
                                        + "cluster=0x{} reason={}",
                                Integer.toHexString(networkAddress),
                                Integer.toHexString(clusterId),
                                failure.getMessage());
                        return;
                    }
                    if (answer.isEmpty()) {
                        // A non-success status, an NCP rejection, or silence
                        // past the deadline — the protocol's line says which.
                        log.warn("zigbee.rejoin_candidate_unresolved: nwk=0x{} "
                                        + "cluster=0x{} reason=zdo_miss",
                                Integer.toHexString(networkAddress),
                                Integer.toHexString(clusterId));
                        return;
                    }
                    // DP-6/DP-8: the RESPONSE's pair is the admitted identity
                    // — a device answering after a re-address is still an
                    // answer; recordAnnounce takes the response's nwk.
                    resolved = Optional.of(answer.get().ieeeAddress());
                    admittedAddress = answer.get().networkAddress();
                }
            }
            admitRejoinCandidate(resolved.get(), admittedAddress, "unknown_sender");
        }

        /** F-R4-1 hook H-i: the callback carried the EUI64 — window gate, then admit. */
        @Override
        public void onRejoinCandidate(IEEEAddress device, int networkAddress) {
            if (!isPermitJoinActive()) {
                // The ingestion unit's zigbee.device_join INFO is the
                // once-per-event observable; the closed door is DEBUG-noted
                // and ignored for adoption.
                log.debug("zigbee.rejoin_candidate_ignored: device={} nwk=0x{} "
                                + "source=tc_join reason=window_closed",
                        device, Integer.toHexString(networkAddress));
                return;
            }
            admitRejoinCandidate(device, networkAddress, "tc_join");
        }

        /**
         * The admission (F-R4-1 §1/§4/§5): relink ≠ adopt — a device already
         * in the adoption maps never re-enters (today's relink path is
         * untouched; DEBUG-noted); otherwise EXACTLY the announce path's two
         * calls ({@link #onDeviceAnnounce}), with the rejoin provenance on the
         * queue entry — it renders on the proposal's LOG LINE, never in an
         * event payload. The queue is keyed by IEEE, so a later announce is a
         * put-replace, never a duplicate.
         */
        private void admitRejoinCandidate(IEEEAddress device, int networkAddress,
                String source) {
            if (adoption.deviceIdFor(device).isPresent()) {
                log.debug("zigbee.rejoin_candidate_ignored: device={} nwk=0x{} "
                                + "source={} reason=already_adopted",
                        device, Integer.toHexString(networkAddress), source);
                return;
            }
            cache.recordAnnounce(device, networkAddress);
            interviewQueue.schedule(device, networkAddress,
                    PendingInterviewQueue.Source.REJOIN);
            log.info("zigbee.rejoin_candidate: device={} nwk=0x{} source={}",
                    device, Integer.toHexString(networkAddress), source);
        }
    }

    /**
     * The M9.6-AVAIL DP-5 transition listener: one root
     * {@code availability_changed} PER ADOPTED ENTITY of the transitioning
     * device — ENTITY grain, because the state projection applies availability
     * only there ({@code subjectEntityIdOrNull} gates it; the relink emission's
     * device grain is a structural no-op, recorded and untouched). Canonical
     * {@code "online"}/{@code "offline"} vocabulary (the payload record's
     * documented triple — never relink's {@code "available"}); CRITICAL toward
     * offline, NORMAL toward online (the record's priority doctrine). Every
     * transition ALSO writes the cache sidecar — nothing else does, and the
     * deferred snapshot-init WU depends on it staying current.
     *
     * <p>{@code previousStatus} is the last state THIS publisher rendered for
     * the device ({@code "unknown"} before the first publish) — with the DP-1
     * empty-map construction it mirrors the tracker's own prior state exactly,
     * because the listener observes every tracker transition.
     */
    private final class EntityAvailabilityPublisher
            implements StandardAvailabilityTracker.TransitionListener {

        /** The last published state per device; absent = never published. */
        private final Map<Long, Boolean> lastPublished = new ConcurrentHashMap<>();

        @Override
        public void onTransition(IEEEAddress device, Instant instant,
                boolean available, AvailabilityReason reason, Instant lastSeen,
                LinkReading lastLink, Instant lastLinkAt) {
            // LINK-READ: the SIBLING of the tracker's frozen DP-8 line
            // (zigbee.availability_changed — byte-frozen, out of bounds), logged
            // right after it: every transition names its reason, the last link
            // reading with the instant of the frame that delivered it, and the
            // HA frames counted since the last ten-minute summary. The tracker
            // keeps the reading BEFORE it fires this sink, so an online edge
            // carries the reading of the very frame that produced it; a device
            // with no reading this process prints the placeholders. The count
            // lives in the ingestion unit — this adapter holds both, so the
            // two instrument lines share one home. LINK-READ-2 (J1): the
            // reason and the reading are the tracker's SNAPSHOT in this call's
            // arguments — taken inside its lock — never re-read after the
            // unlock; the same snapshot rides the v2 event below.
            LinkFields link = linkFields(lastLink, lastLinkAt);
            log.info("zigbee.availability_link: device={} available={} reason={} "
                            + "last_lqi={} last_rssi_dbm={} last_link_at={} "
                            + "frames_since_summary={}",
                    device, available, reason,
                    link.lqi(), link.rssiDbm(), link.at(),
                    ingestion.framesSince(device));
            Boolean prior = lastPublished.put(device.value(), available);
            cache.setAvailability(device, available);
            publishForEntities(device,
                    prior == null ? "unknown" : prior ? "online" : "offline",
                    available, reason, lastSeen, lastLink, lastLinkAt);
        }

        /**
         * The fresh-adoption view seed: on the bench the announce — the
         * tracker's first contact — ALWAYS precedes adoption, so the online
         * edge fires while the device has no adopted entities and publishes
         * nothing; edge-triggering then keeps every later report silent and
         * the new entities would sit UNKNOWN in the view until the device's
         * next offline/online cycle. When the tracker already holds the device
         * AVAILABLE on THIS-process evidence at adoption, publish the
         * per-entity online edge now. {@code previousStatus} is
         * {@code "unknown"} — the fresh entity subjects never carried an
         * availability event. A device the tracker has never seen
         * (slice-driven adoption without a prior frame) seeds nothing; its
         * first frame edges normally. WU-AVAIL-SEED DP-1: a SIDECAR-seeded
         * available never qualifies — the evidence gate is
         * {@code isEvidencedAvailable}, so this seed can never manufacture an
         * evidence-free "online" (production adoption is always
         * announce-preceded, so the gate costs the bench flow nothing).
         */
        void seedFreshAdoption(IEEEAddress device) {
            if (!availabilityTracker.isEvidencedAvailable(device)) {
                return;
            }
            lastPublished.put(device.value(), true);
            // LINK-READ-2: no listener call carries this publish — read the
            // tracker's accessors (each under its lock) for the same snapshot.
            publishForEntities(device, "unknown", true,
                    availabilityTracker.lastReason(device),
                    availabilityTracker.lastSeen(device).orElse(null),
                    availabilityTracker.lastLink(device).orElse(null),
                    availabilityTracker.lastLinkAt(device).orElse(null));
        }

        /**
         * One {@code availability_changed} per adopted entity of the device, at
         * {@link #AVAILABILITY_CHANGED_SCHEMA_VERSION} (J1 / LINK-READ-2): the
         * status pair plus the tracker's snapshot — the reason token (the
         * {@link AvailabilityReason} name lower-cased: {@code ping_timeout},
         * {@code silence_timeout}, …), the last-seen instant and the last link
         * reading with its frame's instant. Absent values ride as {@code null}
         * (the persistence mapper's NON_NULL then writes the v1 byte-shape); the
         * reading rides whole or not at all (the record's invariant).
         */
        private void publishForEntities(IEEEAddress device, String previous,
                boolean available, AvailabilityReason reason, Instant lastSeenAt,
                LinkReading lastLink, Instant lastLinkAt) {
            String next = available ? "online" : "offline";
            EventPriority priority =
                    available ? EventPriority.NORMAL : EventPriority.CRITICAL;
            String reasonToken = reason == null ? null
                    : reason.name().toLowerCase(Locale.ROOT);
            boolean reading = lastLink != null && lastLinkAt != null;
            Integer lqi = reading ? lastLink.lqi() : null;
            Integer rssiDbm = reading ? lastLink.rssiDbm() : null;
            Instant linkAt = reading ? lastLinkAt : null;
            for (EntityId entityId : adoption.entitiesFor(device).values()) {
                try {
                    context.eventPublisher().publishRoot(new EventDraft(
                            EventTypes.AVAILABILITY_CHANGED,
                            AVAILABILITY_CHANGED_SCHEMA_VERSION,
                            clock.instant(),
                            SubjectRef.entity(entityId),
                            priority,
                            EventOrigin.INTEGRATION,
                            new AvailabilityChangedEvent(previous, next, reasonToken,
                                    lastSeenAt, lqi, rssiDbm, linkAt),
                            null,
                            null));
                } catch (SequenceConflictException conflict) {
                    // The relink-precedent idiom: swallow with a log — a lost
                    // transition re-renders on the device's next edge.
                    log.warn("zigbee.availability_publish_conflict: entity={}: {}",
                            entityId, conflict.getMessage());
                }
            }
        }
    }

}
