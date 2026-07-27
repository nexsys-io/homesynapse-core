/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.api.rest.AuthMiddleware;
import com.homesynapse.api.rest.OpaqueTokenStore;
import com.homesynapse.api.rest.RateLimiter;
import com.homesynapse.api.rest.RestFilters;
import com.homesynapse.api.rest.StandardAuthMiddleware;
import com.homesynapse.api.rest.StandardRateLimiter;
import com.homesynapse.automation.AutomationDefinitionLoader;
import com.homesynapse.automation.AutomationEngineAssembly;
import com.homesynapse.automation.AutomationSchema;
import com.homesynapse.automation.CommandDispatchAssembly;
import com.homesynapse.automation.CommandDispatchService;
import com.homesynapse.automation.ExplanationService;
import com.homesynapse.automation.InMemoryAutomationIdentityStore;
import com.homesynapse.automation.LoadFailure;
import com.homesynapse.automation.LoadResult;
import com.homesynapse.automation.PendingCommandLedger;
import com.homesynapse.automation.PendingCommandLedgerAssembly;
import com.homesynapse.automation.RunManager;
import com.homesynapse.automation.RunManagerAssembly;
import com.homesynapse.automation.RunManagerConfig;
import com.homesynapse.automation.StandardActionExecutor;
import com.homesynapse.automation.StandardAutomationRegistry;
import com.homesynapse.automation.StandardConditionEvaluator;
import com.homesynapse.automation.StandardRunConditionGate;
import com.homesynapse.automation.StandardSelectorResolver;
import com.homesynapse.automation.StandardTriggerEvaluator;
import com.homesynapse.config.ConfigurationAccess;
import com.homesynapse.config.ConfigurationService;
import com.homesynapse.config.ConfigurationServiceFactory;
import com.homesynapse.config.SchemaRegistry;
import com.homesynapse.device.AreaRegistry;
import com.homesynapse.device.DeviceRegistry;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.device.InMemoryAreaRegistry;
import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.event.ConfigErrorEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.BusMetrics;
import com.homesynapse.event.bus.DerivedWriteRateLimit;
import com.homesynapse.event.bus.EventBus;
import com.homesynapse.event.bus.HealthSignal;
import com.homesynapse.event.bus.InProcessEventBus;
import com.homesynapse.event.bus.QueueSaturationHealthCheck;
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriberInfo;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberSnapshot;
import com.homesynapse.event.bus.SubscriptionFilter;
import com.homesynapse.integration.IntegrationEvents;
import com.homesynapse.integration.IntegrationFactory;
import com.homesynapse.integration.runtime.IntegrationSupervisor;
import com.homesynapse.integration.runtime.IntegrationSupervisorAssembly;
import com.homesynapse.observability.HealthStatus;
import com.homesynapse.persistence.DeploymentProfile;
import com.homesynapse.persistence.PayloadCipher;
import com.homesynapse.persistence.PersistenceFactory;
import com.homesynapse.platform.HealthReporter;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.SystemId;
import com.homesynapse.platform.systemd.NoOpHealthReporter;
import com.homesynapse.platform.systemd.SystemdHealthReporter;
import com.homesynapse.state.AttributeSchemaResolver;
import com.homesynapse.state.AttributeValueComparator;
import com.homesynapse.state.ComparisonPolicy;
import com.homesynapse.state.DerivationRule;
import com.homesynapse.state.DerivedPublishGate;
import com.homesynapse.state.ProjectionAdvancer;
import com.homesynapse.state.ProjectionId;
import com.homesynapse.state.ReadinessSource;
import com.homesynapse.state.StateProjection;
import com.homesynapse.state.StateQueryService;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root and top-level lifecycle orchestrator for the HomeSynapse
 * Core runtime (AB-3).
 *
 * <p>{@code HomeSynapseCore} implements {@link SystemLifecycleManager} (PD-1):
 * {@code main()} constructs it, holds it as a {@code SystemLifecycleManager},
 * calls {@link #start()}, and registers a JVM shutdown hook that calls
 * {@link #shutdown(String)}. It owns every long-lived subsystem — configuration,
 * persistence, event bus, device registries, state projection, the automation
 * engine, the scheduler, the rate limit, the health loop — constructing them in
 * Doc 12 phase order on {@link #start()} and tearing them down in reverse order
 * on {@link #shutdown(String)}.</p>
 *
 * <h2>Phase model (Doc 12 §3.2–§3.10, openHAB startlevel ordering)</h2>
 *
 * <ol start="0">
 *   <li><b>BOOTSTRAP</b> — platform dirs, {@link HealthReporter} selection.</li>
 *   <li><b>FOUNDATION</b> — {@link ConfigurationService#load()} (first init step).</li>
 *   <li><b>DATA_INFRASTRUCTURE</b> — persistence, event bus, publisher.</li>
 *   <li><b>CORE_DOMAIN</b> — device registries, state projection (REPLAY→LIVE),
 *       then the {@code automation_engine} subscriber <em>after</em> the
 *       projection reaches LIVE (the catch-up ordering invariant).</li>
 *   <li><b>OBSERVABILITY</b> — health aggregation (structural in AB-3: no
 *       {@code HealthContributor}/{@code HealthAggregator} impls exist yet).</li>
 *   <li><b>EXTERNAL_INTERFACES</b> — opens the HTTP surface behind bearer-token
 *       auth, loopback-bound by default (AB-1; core-review C1 closed). Auth is
 *       installed before the port binds; {@link #exposeHttpSurface()} is the
 *       idempotent bring-up the test/harness path also uses.</li>
 *   <li><b>INTEGRATIONS</b> — the integration spine (M9.1): the routing
 *       subscriber registers AFTER the projection is LIVE, then the
 *       {@code IntegrationSupervisor} hosts the injected factories, bounded so
 *       a hanging integration never takes down boot (INV-RF-01). Skipped
 *       entirely when the factory list is empty (the AB-3/AB-4
 *       held-not-consumed precedent — byte-identical runtime behavior for
 *       {@code Main} until it injects factories).</li>
 * </ol>
 *
 * <p>The engine then enters {@link LifecyclePhase#RUNNING} and the §3.10 health
 * loop pets the watchdog every {@code WatchdogSec / 2} seconds.</p>
 *
 * <h2>Boundaries (AB-1)</h2>
 *
 * <p>AB-1 opens the production HTTP surface behind bearer-token authentication,
 * loopback-bound by default (the C1 close). The at-rest cipher remains
 * <strong>inert</strong> (the injected {@link PayloadCipher} stays {@code null};
 * AB-4 activates it). WebSocket first-message auth is <strong>held</strong> — the
 * {@code com.homesynapse.api.ws} runtime is unbuilt at HEAD (Phase-2 scaffold
 * only), so there is no WS upgrade handler to harden (see the AB-1 handoff). The
 * shared {@link OpaqueTokenStore} is ready for the WS path to consume when that
 * runtime lands.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #start()} MUST be invoked from a platform thread — Jackson warmup
 * inside {@link PersistenceFactory#start} pins virtual-thread carriers (LTD-19 /
 * DECIDE-M2-05). The production entry point is {@code main()}, which satisfies
 * this. The health loop and bus delivery run on virtual threads.</p>
 *
 * @see SystemLifecycleManager
 * @see PersistenceFactory
 * @see StateProjection
 * @see HealthLoop
 */
public final class HomeSynapseCore implements SystemLifecycleManager, ReadinessSource {

    private static final Logger LOG = LoggerFactory.getLogger(HomeSynapseCore.class);

    /** Subscriber identifier used for the materialized state projection. */
    private static final String PROJECTION_SUBSCRIBER_ID = "state_projection";

    /**
     * Running code's projection version (M4.0b-5, AMD-53). Single source of
     * truth: passed to {@code StateProjection.create(...)} AND surfaced by
     * {@code GET /internal/projection}'s frozen {@code projectionVersion}
     * field (M7.5c-a) — the two must never diverge.
     */
    private static final int PROJECTION_VERSION = 5;

    /** Subscriber identifier used for the automation_engine (trigger) subscriber. */
    private static final String AUTOMATION_SUBSCRIBER_ID = "automation_engine";

    /**
     * Cadence of the {@code pending_command_ledger}'s {@code pollExpirations()} deadline sweep
     * (M7.4c). At ~1 s a 30 000 ms confirmation deadline fires within ~1 s of expiry — well
     * inside the operator-visible window, with negligible cost (the sweep is a lock + a deadline
     * comparison over the in-flight set).
     */
    private static final long LEDGER_EXPIRY_PERIOD_MILLIS = 1_000L;

    /** Default systemd watchdog interval when {@code $WATCHDOG_USEC} is unset (LTD-13). */
    private static final long DEFAULT_WATCHDOG_SECONDS = 60L;

    private final Path dbPath;
    private final Path configDir;
    private final HomeSynapseConfig config;
    private final Clock clock;
    private final HomeId homeId;

    /**
     * The config-supplied at-rest payload-encryption adapter (Doc 15 §3.8).
     * Nullable by design: AB-3 leaves it {@code null} (inert) — the cipher
     * phase-gate is established but not activated until AB-4. When non-null, it
     * is forwarded into the persistence write path (the M6.3 wiring).
     */
    private final PayloadCipher payloadCipher;

    /**
     * The integration factories Phase 6 hosts (M9.1, DECIDE-04 — the caller
     * assembles the list explicitly; no ServiceLoader). Never {@code null};
     * empty means Phase 6 is skipped entirely (no supervisor, no router).
     */
    private final List<IntegrationFactory> integrationFactories;

    /**
     * The device registry Phase 3.1 installs as {@link #deviceRegistry} (M9.4b
     * §1, R4 — pm-handoff v18 beat 4). Constructor-injected so the composition
     * root owns registry identity: the integration-factory path and the core's
     * dispatch resolution index the SAME instance. The 7-arg constructor
     * self-constructs one, keeping every existing caller behavior-identical.
     */
    private final DeviceRegistry providedDeviceRegistry;

    // ── Phase / health state (callable from any thread at any time) ─────────
    private volatile LifecyclePhase phase = LifecyclePhase.BOOTSTRAP;
    private final Map<String, SubsystemState> subsystems = new ConcurrentHashMap<>();
    private volatile Instant runningSince;

    // ── Lifecycle guard ─────────────────────────────────────────────────────
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private volatile boolean started = false;
    private volatile boolean abandoned = false;
    private volatile boolean shutdownComplete = false;

    // ── Subsystems (constructed during start()) ─────────────────────────────
    private HealthReporter healthReporter;
    private DeferredEventPublisher deferredConfigPublisher;
    private ConfigurationService configurationService;
    private SchemaRegistry schemaRegistry;
    private SystemId systemId;
    private PersistenceFactory persistenceFactory;
    private InProcessEventBus eventBus;
    private EventPublisher eventPublisher;
    private DerivedWriteRateLimit rateLimit;
    private ProjectionAdvancer projectionAdvancer;
    private StateProjection stateProjection;
    private QueueSaturationHealthCheck healthCheck;
    private SharedScheduler scheduler;
    private StateQueryService stateQueryService;
    private EntityRegistry entityRegistry;
    private DeviceRegistry deviceRegistry;
    private AreaRegistry areaRegistry;
    private RegistryProjection registryProjection;
    private StandardAutomationRegistry automationRegistry;
    private StandardTriggerEvaluator triggerEvaluator;
    private RunManager runManager;
    private CommandDispatchService commandDispatchService;
    private PendingCommandLedger pendingCommandLedger;
    private IntegrationSupervisor integrationSupervisor;
    private HealthLoop healthLoop;
    private Javalin httpServer;

    /**
     * Constructs a composition root without an at-rest payload cipher — the
     * AB-3 production form (cipher inert until AB-4).
     *
     * @param dbPath    full path to the SQLite database file; never {@code null}
     * @param configDir the configuration directory ({@code homesynapse.yaml} +
     *                  key files); never {@code null}. Created if absent.
     * @param config    consolidated runtime configuration; never {@code null}
     * @param clock     injected clock; never {@code null}
     * @param homeId    home identity for this installation (AMD-34); never
     *                  {@code null}
     */
    public HomeSynapseCore(Path dbPath,
                           Path configDir,
                           HomeSynapseConfig config,
                           Clock clock,
                           HomeId homeId) {
        this(dbPath, configDir, config, clock, homeId, null);
    }

    /**
     * Constructs a composition root with the at-rest payload-encryption seam
     * (Doc 15 §3.8) and no integrations. Delegates to the M9.1 canonical 7-arg
     * form with an empty factory list — Phase 6 is skipped entirely, so every
     * pre-M9.1 caller (including {@code Main}) keeps byte-identical runtime
     * behavior until it injects factories.
     *
     * @param dbPath        full path to the SQLite database file; never {@code null}
     * @param configDir     the configuration directory; never {@code null}
     * @param config        consolidated runtime configuration; never {@code null}
     * @param clock         injected clock; never {@code null}
     * @param homeId        home identity for this installation; never {@code null}
     * @param payloadCipher the at-rest cipher adapter, or {@code null} to leave
     *                      at-rest payload encryption inert (the AB-3 state)
     */
    public HomeSynapseCore(Path dbPath,
                           Path configDir,
                           HomeSynapseConfig config,
                           Clock clock,
                           HomeId homeId,
                           PayloadCipher payloadCipher) {
        this(dbPath, configDir, config, clock, homeId, payloadCipher, List.of());
    }

    /**
     * Constructs a composition root — the M9.1 form: the at-rest
     * payload-encryption seam (Doc 15 §3.8) plus the integration factory list
     * Phase 6 hosts (DP-7). Delegates to the 8-arg canonical form with a
     * self-constructed device registry, so every existing caller compiles
     * unchanged and behaves identically (the AB-3/AB-4 delegation-chain style).
     *
     * @param dbPath               full path to the SQLite database file; never
     *                             {@code null}
     * @param configDir            the configuration directory; never {@code null}
     * @param config               consolidated runtime configuration; never
     *                             {@code null}
     * @param clock                injected clock; never {@code null}
     * @param homeId               home identity for this installation; never
     *                             {@code null}
     * @param payloadCipher        the at-rest cipher adapter, or {@code null} to
     *                             leave at-rest payload encryption inert
     * @param integrationFactories the integration factories Phase 6 starts
     *                             (DECIDE-04 — assembled explicitly by the
     *                             caller); never {@code null}, may be empty
     *                             (Phase 6 skipped)
     */
    public HomeSynapseCore(Path dbPath,
                           Path configDir,
                           HomeSynapseConfig config,
                           Clock clock,
                           HomeId homeId,
                           PayloadCipher payloadCipher,
                           List<IntegrationFactory> integrationFactories) {
        this(dbPath, configDir, config, clock, homeId, payloadCipher,
                integrationFactories, new InMemoryDeviceRegistry());
    }

    /**
     * Constructs a composition root — the M9.4b canonical form (§1, R4): the
     * composition root owns device-registry identity, so the integration
     * factories and the core index the SAME truth (dispatch resolution reads
     * the core's registry — a split-brain registry pair would misroute every
     * entity&rarr;device&rarr;integration resolution once a real transport
     * binds). The 5/6/7-arg forms delegate here.
     *
     * @param dbPath               full path to the SQLite database file; never
     *                             {@code null}
     * @param configDir            the configuration directory; never {@code null}
     * @param config               consolidated runtime configuration; never
     *                             {@code null}
     * @param clock                injected clock; never {@code null}
     * @param homeId               home identity for this installation; never
     *                             {@code null}
     * @param payloadCipher        the at-rest cipher adapter, or {@code null} to
     *                             leave at-rest payload encryption inert
     * @param integrationFactories the integration factories Phase 6 starts
     *                             (DECIDE-04 — assembled explicitly by the
     *                             caller); never {@code null}, may be empty
     *                             (Phase 6 skipped)
     * @param deviceRegistry       the device registry Phase 3.1 installs — the
     *                             ONE instance both the core and any
     *                             registry-consuming integration factory index
     *                             (R4); never {@code null}
     */
    public HomeSynapseCore(Path dbPath,
                           Path configDir,
                           HomeSynapseConfig config,
                           Clock clock,
                           HomeId homeId,
                           PayloadCipher payloadCipher,
                           List<IntegrationFactory> integrationFactories,
                           DeviceRegistry deviceRegistry) {
        this.dbPath = Objects.requireNonNull(dbPath, "dbPath");
        this.configDir = Objects.requireNonNull(configDir, "configDir");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.homeId = Objects.requireNonNull(homeId, "homeId");
        this.payloadCipher = payloadCipher;
        this.integrationFactories = List.copyOf(
                Objects.requireNonNull(integrationFactories, "integrationFactories"));
        this.providedDeviceRegistry =
                Objects.requireNonNull(deviceRegistry, "deviceRegistry");
    }

    // ════════════════════════════════════════════════════════════════════════
    // SystemLifecycleManager
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Executes the full startup sequence (Phases 0–6) synchronously, blocking
     * until the engine reaches {@link LifecyclePhase#RUNNING}. On a fatal
     * failure, tears down any already-initialized subsystems and rethrows.
     *
     * <p>MUST be called from a platform thread (LTD-19).</p>
     *
     * @throws IllegalStateException if already started
     * @throws Exception             on a fatal initialization failure
     */
    @Override
    public void start() throws Exception {
        if (started) {
            throw new IllegalStateException("HomeSynapseCore already started");
        }
        try {
            bootstrap();
        } catch (Exception fatal) {
            LOG.error("Fatal error during startup; tearing down initialized subsystems", fatal);
            try {
                shutdown("startup failure: " + fatal.getMessage());
            } catch (RuntimeException teardownFailure) {
                LOG.error("teardown during startup-failure handling also failed", teardownFailure);
            }
            throw fatal;
        }
    }

    private void bootstrap() throws Exception {
        // ── Phase 0 BOOTSTRAP ───────────────────────────────────────────────
        setPhase(LifecyclePhase.BOOTSTRAP);
        Files.createDirectories(configDir);
        Path dbParent = dbPath.toAbsolutePath().getParent();
        if (dbParent != null) {
            Files.createDirectories(dbParent);
        }
        this.healthReporter = selectHealthReporter(System::getenv);
        healthReporter.reportStatus("BOOTSTRAP: platform initialized");

        // ── Phase 1 FOUNDATION — config.load() is the FIRST subsystem init step ──
        setPhase(LifecyclePhase.FOUNDATION);
        Instant configStart = clock.instant();
        this.systemId = SystemId.of(homeId.value());
        // Config (Phase 1) needs an EventPublisher, but the bus (Phase 2) is not
        // up yet — config's boot events are inherently un-persistable. The
        // deferred publisher drops them until Phase 2 wires the real one.
        this.deferredConfigPublisher = new DeferredEventPublisher();
        // §1e generalized pre-migration rollback hook (additive — no destructive
        // forced migration). Config performs its own §3.3 timestamped backup
        // inside load(); the chain-migration snapshot path lands in AB-4.
        snapshotBeforeMigration();
        ConfigurationServiceFactory.Assembly cfg = ConfigurationServiceFactory.create(
                configDir, clock, systemId, deferredConfigPublisher);
        this.configurationService = cfg.service();
        this.schemaRegistry = cfg.schemaRegistry();
        // Doc 12 Phase 1 is "core-only schema composition": the automation schema
        // is a CORE schema, so it must be registered BEFORE config.load() so the
        // config's `automation:` section validates against it (only INTEGRATION
        // schemas are deferred, to after Phase 6). The schema text is the
        // config-free constant the automation module owns (FIX-07).
        schemaRegistry.registerCoreSchema(
                AutomationSchema.SCHEMA_SECTION, AutomationSchema.SCHEMA_JSON);
        // FATAL on failure — Configuration is a FATAL subsystem (Doc 12 §4).
        this.configurationService.load();
        recordSubsystem("configuration", LifecyclePhase.FOUNDATION, configStart);

        // ── Phase 2 DATA_INFRASTRUCTURE — persistence + event bus ────────────
        setPhase(LifecyclePhase.DATA_INFRASTRUCTURE);
        Instant dataStart = clock.instant();
        // Aggregate the per-module event-class manifests (M3.6c / DECIDE-04).
        List<Class<? extends DomainEvent>> eventClasses = Stream.of(
                        EventTypes.CORE_PRODUCTION_EVENT_CLASSES,
                        IntegrationEvents.LIFECYCLE_EVENT_CLASSES,
                        IntegrationEvents.CAPABILITY_EVENT_CLASSES)
                .flatMap(List::stream)
                .toList();
        // AB-3: payloadCipher is null (inert) — the factory then runs
        // plaintext-for-all (the cipher activation is AB-4).
        this.persistenceFactory = PersistenceFactory.start(
                dbPath, config.persistence(), clock, homeId, eventClasses, payloadCipher);

        BusMetrics jfrMetrics = BusMetrics.jfr();
        this.eventBus = new InProcessEventBus(
                persistenceFactory.eventStore(),
                persistenceFactory.checkpointStore(),
                clock,
                persistenceFactory.subscriberReadConnectionFactory(),
                jfrMetrics,
                persistenceFactory.writeQueueDepthSupplier(),
                config.eventBus());

        this.rateLimit = new DerivedWriteRateLimit(clock, jfrMetrics, PROJECTION_SUBSCRIBER_ID);
        this.projectionAdvancer = ProjectionAdvancer.dispatching(persistenceFactory.eventStore());
        this.eventPublisher = new NotifyingEventPublisher(
                persistenceFactory.eventPublisher(), eventBus);
        // Now that the real publisher exists, route config's observability to it.
        this.deferredConfigPublisher.setDelegate(eventPublisher);
        recordSubsystem("persistence", LifecyclePhase.DATA_INFRASTRUCTURE, dataStart);
        recordSubsystem("event-bus", LifecyclePhase.DATA_INFRASTRUCTURE, dataStart);

        // ── Phase 3 CORE_DOMAIN — registries, state store, automation ────────
        setPhase(LifecyclePhase.CORE_DOMAIN);

        // Step 3.1 — device registries (populated before the projection processes
        // device-subject events; AB-3 starts them empty, INV-CE-02). The device
        // registry is the ctor-injected instance (M9.4b §1, R4): the composition
        // root passes the SAME instance to registry-consuming integration
        // factories, so dispatch resolution and adapter adoption index one truth.
        Instant deviceStart = clock.instant();
        this.entityRegistry = new InMemoryEntityRegistry();
        this.deviceRegistry = providedDeviceRegistry;
        this.areaRegistry = new InMemoryAreaRegistry();
        // AMD-99 / REG-INV-1: the registries are projections of the event log.
        // The projection is THE single apply path (every registry mutation flows
        // through it -- the REGISTRY_MUTATION_ONLY_VIA_PROJECTION rule enforces
        // this); its bus subscriber rebuilds both registries by replaying the
        // registration/removal events. Because the in-memory registries start
        // empty every boot, the subscriber's checkpoint RESETS TO 0 BEFORE
        // subscribeRuntime (the bus reads the checkpoint at registration; 0 means
        // start-of-log per the CheckpointStore contract).
        this.registryProjection = new RegistryProjection(deviceRegistry, entityRegistry);
        persistenceFactory.checkpointStore()
                .writeCheckpoint(RegistryProjectionSubscriber.SUBSCRIBER_ID, 0L);
        eventBus.subscribeRuntime(
                new SubscriberInfo(RegistryProjectionSubscriber.SUBSCRIBER_ID,
                        RegistryProjectionSubscriber.subscriptionFilter(), false),
                new RegistryProjectionSubscriber(
                        registryProjection, deviceRegistry, entityRegistry));
        recordSubsystem("device-model", LifecyclePhase.CORE_DOMAIN, deviceStart);

        // Step 3.2 — state store + projection (REPLAY → LIVE) + query service.
        Instant stateStart = clock.instant();
        DerivedPublishGate publishGate = rateLimit::acquire;
        AttributeValueComparator comparator = AttributeValueComparator.structural();
        ComparisonPolicy comparisonPolicy = ComparisonPolicy.FP_NOISE_DEFAULT;
        AttributeSchemaResolver schemaResolver =
                AttributeSchemaResolver.of(StandardCapabilities.attributeSchemas());
        this.stateProjection = StateProjection.create(
                new ProjectionId(PROJECTION_SUBSCRIBER_ID),
                PROJECTION_VERSION,                         // M4.0b-5 (AMD-53) projection version
                persistenceFactory.viewCheckpointStore(),
                persistenceFactory.stateCheckpointSource(),
                persistenceFactory.atomicCheckpointSink(), // AMD-45 §2.1
                persistenceFactory.stateStore(),
                DerivationRule.production(comparator, comparisonPolicy, schemaResolver),
                eventPublisher,
                projectionAdvancer,
                config.checkpointPolicy(),
                clock,
                publishGate);
        SubscriberInfo projectionInfo = new SubscriberInfo(
                PROJECTION_SUBSCRIBER_ID,
                SubscriptionFilter.all(),
                true,   // coalesceExempt (Doc 01 §3.6)
                true);  // atomicCheckpoint (AMD-45 §2.2)
        eventBus.subscribeRuntime(projectionInfo, stateProjection);

        Consumer<HealthSignal> healthSignalHandler = signal -> {
            switch (signal.level()) {
                case INFO -> LOG.info("Health {}: depth={} at {}",
                        signal.channel(), signal.depth(), signal.timestamp());
                case WARN -> LOG.warn("Health {}: depth={} at {}",
                        signal.channel(), signal.depth(), signal.timestamp());
                case CRITICAL -> LOG.error("Health CRITICAL {}: depth={} at {}",
                        signal.channel(), signal.depth(), signal.timestamp());
            }
        };
        this.healthCheck = new QueueSaturationHealthCheck(
                persistenceFactory.writeQueueDepthSupplier(),
                clock, 5_000, 10_000, 5, healthSignalHandler);
        this.scheduler = new SharedScheduler(rateLimit, healthCheck);
        // M9.4b §2.3: the registry-carrying overload — brightness_percent derives
        // at query time from the entity's attribute schema (Doc 08 §3.5); the
        // supplier defers the registry read to query time (construction-order-safe).
        this.stateQueryService = StateQueryService.materialized(
                persistenceFactory.stateStore(), this, stateProjection::cursorPosition,
                () -> entityRegistry, clock);
        recordSubsystem("state-store", LifecyclePhase.CORE_DOMAIN, stateStart);

        // The catch-up ordering invariant: the automation engine must not
        // evaluate against partially-replayed state. Gate the automation_engine
        // subscribe on the state projection reaching LIVE. The registry
        // projection must ALSO be caught up to the log head before automation
        // definitions bind entity refs (AMD-99 §5, carry-list C3) -- the ONE
        // sanctioned composition-root addition of the DUR WU.
        awaitProjectionLive();
        awaitRegistryProjectionLive();

        // Step 3.4 — automation engine (trigger subscriber). The definition-load
        // glue rides the composition root (FIX-07: the core:automation -> config
        // edge is banned at every scope). The automation schema was registered in
        // Phase 1 (core-only schema composition) so the `automation:` section
        // validated during config.load().
        Instant automationStart = clock.instant();
        InMemoryAutomationIdentityStore identityStore =
                new InMemoryAutomationIdentityStore(clock);
        AutomationDefinitionLoader loader = new AutomationDefinitionLoader(
                identityStore, entityRegistry, areaRegistry);
        // The loader reads top-level "automations"/"schema_version", but those
        // live UNDER the "automation" config section (AutomationSchema.SCHEMA_SECTION)
        // and rawMap() is the whole document keyed by section — so the loader must
        // receive the SECTION content, not the whole document, or every automation
        // is silently ignored. [REVIEW] this corrects the instruction's literal
        // loader.load(rawMap()).
        LoadResult loadResult = loader.load(
                automationSection(configurationService.getCurrentModel().rawMap()));
        // SD-9 fail-closed is PER-DEFINITION: a malformed definition is rejected
        // and SURFACED (published as config_error + logged), but valid sibling
        // definitions still load — the engine is never booted with a silently-
        // inert ruleset, and a single bad entry does not brick the boot. Doc 12
        // §4 "Automation = FATAL" governs subsystem INIT failure, not a per-entry
        // config error (the AutomationDefinitionLoader's ratified contract).
        surfaceAutomationLoadFailures(loadResult);
        this.automationRegistry = new StandardAutomationRegistry();
        this.automationRegistry.load(loadResult.loaded());
        StandardSelectorResolver selectorResolver = new StandardSelectorResolver(
                entityRegistry, areaRegistry, deviceRegistry);

        // Step 3.4a — the run pipeline goes live (M7.4b, OR-M7-WIRING producer half). The
        // selector/condition/executor/gate are stateless public collaborators the composition root
        // constructs directly (no assembly seam needed); the RunManager FSM drives them. A matched
        // trigger drives RunManager.initiateRun (a direct, co-located, in-process call — §1 D1: the
        // run lifecycle is the engine's internal orchestration; the event-driven rule governs the
        // command-dispatch hop, already a subscriber at Step 3.4b). The executor emits
        // command_issued; the M7.4a command_dispatch_service consumes it. The executor's parameter
        // serializer is sourced from persistence (the only module owning a JSON ObjectMapper, so
        // command_issued.parameters round-trip faithfully with the at-rest encoding), and the
        // single V1 default confirmation timeout is the launch-scope global default.
        StandardConditionEvaluator conditionEvaluator =
                new StandardConditionEvaluator(selectorResolver, clock);
        StandardActionExecutor actionExecutor = new StandardActionExecutor(
                entityRegistry, selectorResolver, conditionEvaluator, stateQueryService,
                eventPublisher, clock, PendingCommandLedgerAssembly.DEFAULT_CONFIRMATION_TIMEOUT_MS,
                persistenceFactory.commandParameterSerializer());
        StandardRunConditionGate conditionGate = new StandardRunConditionGate(
                conditionEvaluator, selectorResolver, eventPublisher);
        this.runManager = RunManagerAssembly.runManager(
                eventPublisher, actionExecutor, conditionGate, stateQueryService, clock,
                RunManagerConfig.defaults());

        this.triggerEvaluator = new StandardTriggerEvaluator(
                automationRegistry, selectorResolver, stateQueryService, eventPublisher, clock);
        // The augmented seam carries the RunManager (+ the registry/resolver the trigger->run
        // derivation needs) so a LIVE matched trigger initiates one root Run per matched automation
        // (D2: no run initiation on replay). Wired BEFORE the subscribe so the RunManager is present
        // at registration.
        Subscriber automationSubscriber = AutomationEngineAssembly.automationEngineSubscriber(
                triggerEvaluator, runManager, automationRegistry, selectorResolver);
        // coalesceExempt=false: the automation engine evaluates against current
        // state (StateQueryService), so it does not require every intermediate
        // event individually the way the coalesce-exempt projection does.
        eventBus.subscribeRuntime(
                new SubscriberInfo(AUTOMATION_SUBSCRIBER_ID, SubscriptionFilter.all(), false),
                automationSubscriber);

        // Step 3.4b — command_dispatch_service (the co-located dispatch subscriber, M7.4a /
        // §1 D1 / AMD-95). The executor emits command_issued; this subscriber consumes it and
        // routes via the two-hop entity->device->integration resolution, dispatching in LIVE
        // only (D2 pure-function-replay). Registered AFTER the projection is LIVE — the same
        // catch-up ordering invariant as automation_engine (a dispatch subscriber must not act
        // on the replay catch-up) — and torn down in BOTH shutdown branches (the paired
        // teardown: the reverted-M7.3 lesson that a runtime subscriber with no matching stop
        // leaks its resources). coalesceExempt=true: a coalesced command_issued would be a
        // command that never dispatched (correctness-critical, mirroring the ledger).
        CommandDispatchAssembly.Components commandDispatch =
                CommandDispatchAssembly.commandDispatchSubscriber(
                        entityRegistry, deviceRegistry, eventPublisher);
        this.commandDispatchService = commandDispatch.service();
        eventBus.subscribeRuntime(
                new SubscriberInfo(CommandDispatchAssembly.SUBSCRIBER_ID,
                        CommandDispatchAssembly.subscriptionFilter(), true),
                commandDispatch.subscriber());

        // Step 3.4c — the pending_command_ledger goes live (M7.4c, OR-M7-WIRING confirmation half).
        // The ledger correlates each command_issued to the device's reported state and publishes
        // state_confirmed on a match / command_confirmation_timed_out on deadline expiry (Doc 07
        // §3.11.2 — the "did it actually confirm?" hero half). Built + unit-tested in M7.3; its LIVE
        // wiring was deferred until its only live input (command_issued) existed (M7.4b). Registered
        // AFTER the projection is LIVE — the same catch-up ordering invariant as automation_engine /
        // command_dispatch_service (§1 D2: the ledger acts only in LIVE; it must NOT publish on the
        // replay catch-up — the FSM already guards publishes during REPLAY, this ordering is the
        // composition-root half). coalesceExempt=true: a coalesced command_issued/state_reported
        // would be a missed confirmation match (correctness-critical, Doc 01 §3.6 — same as the
        // projection/dispatch). The deadline sweep (pollExpirations) is a periodic tick driven by
        // the SharedScheduler, cancelled by scheduler.shutdown(). The ledger holds ONLY the bus's
        // per-subscriber SQLite read connection (no separate close()) — released by
        // eventBus.unsubscribe(...) in doTeardown (and by eventBus.abandon() in the abandon path);
        // the paired teardown is the reverted-M7.3 lesson (a runtime subscriber with no matching
        // teardown leaks its read connection -> @TempDir cleanup fails on Windows).
        PendingCommandLedgerAssembly.Components pendingLedger =
                PendingCommandLedgerAssembly.pendingCommandLedger(
                        eventPublisher, entityRegistry, clock,
                        PendingCommandLedgerAssembly.DEFAULT_CONFIRMATION_TIMEOUT_MS,
                        persistenceFactory.commandParameterDecoder());
        this.pendingCommandLedger = pendingLedger.ledger();
        eventBus.subscribeRuntime(
                new SubscriberInfo(PendingCommandLedgerAssembly.SUBSCRIBER_ID,
                        PendingCommandLedgerAssembly.subscriptionFilter(), true),
                pendingLedger.subscriber());
        scheduler.schedulePeriodic(
                PendingCommandLedgerAssembly.SUBSCRIBER_ID + "_expiry",
                pendingLedger.expirationTick(),
                LEDGER_EXPIRY_PERIOD_MILLIS);
        recordSubsystem("automation", LifecyclePhase.CORE_DOMAIN, automationStart);

        // ── Phase 4 OBSERVABILITY ────────────────────────────────────────────
        // No HealthContributor/HealthAggregator production impls exist yet; AB-3
        // reports aggregated HEALTHY and the loop pets the watchdog (structural).
        setPhase(LifecyclePhase.OBSERVABILITY);
        recordSubsystem("observability", LifecyclePhase.OBSERVABILITY, clock.instant());

        // ── Phase 5 EXTERNAL_INTERFACES — open HTTP behind auth (AB-1) ───────
        // AB-1 closes core-review C1: production start() now binds the HTTP port
        // WITH the auth filter installed and loopback-bound by default. The
        // auth-before-network-exposure invariant holds — bringUpHttpSurface()
        // installs AuthMiddleware/RateLimiter before app.start(...), so no port
        // binds before auth is registered. The projection is already LIVE here
        // (gated in Phase 3), so the readiness gate passes. The production path
        // and the test/harness exposeHttpSurface() path converge on this method
        // (idempotent). The cipher stays inert (AB-4); WS auth is HELD (the WS
        // runtime is unbuilt — see the AB-1 handoff).
        setPhase(LifecyclePhase.EXTERNAL_INTERFACES);
        bringUpHttpSurface();

        // ── Phase 6 INTEGRATIONS — the integration spine (M9.1) ─────────────
        setPhase(LifecyclePhase.INTEGRATIONS);
        // DP-7 skip-if-empty: with no factories there is no supervisor and no
        // router — byte-identical runtime behavior to the pre-M9.1 boot (the
        // AB-3/AB-4 held-not-consumed precedent). Main injects nothing yet.
        if (!integrationFactories.isEmpty()) {
            Instant integrationStart = clock.instant();
            // DP-3: integration-runtime carries no JSON library — the
            // command-parameter decoder rides the persistence ObjectMapper (the
            // M7.4b commandParameterSerializer in the opposite direction), so an
            // adapter's CommandEnvelope.parameters round-trip with the at-rest
            // encoding, AttributeValues included.
            // B7 (hub-audit corrected path): per-integration-scoped config access
            // composed over the live config model via the config module's public
            // factory — the supervisor scopes each adapter's context by type.
            IntegrationSupervisorAssembly.Components integration =
                    IntegrationSupervisorAssembly.integrationSupervisor(
                            eventPublisher,
                            entityRegistry,
                            stateQueryService,
                            type -> ConfigurationAccess.scoped(
                                    type, configurationService.getCurrentModel()),
                            persistenceFactory.commandParameterDecoder(),
                            clock);
            this.integrationSupervisor = integration.supervisor();
            // The routing subscriber registers AFTER the projection reached LIVE
            // (gated in Phase 3) — the same catch-up ordering invariant as
            // 3.4/3.4b/3.4c: the router's LIVE-only guard (INV-ES-09) is the
            // subscriber half; this ordering is the composition-root half. It is
            // torn down in BOTH shutdown branches (the paired-teardown /
            // reverted-M7.3 lesson: the router holds ONLY the bus's per-subscriber
            // SQLite read connection — released by unsubscribe/abandon).
            // coalesceExempt=true: a coalesced command_dispatched is a command
            // that never reaches the device (correctness-critical, Doc 01 §3.6).
            eventBus.subscribeRuntime(
                    new SubscriberInfo(IntegrationSupervisorAssembly.SUBSCRIBER_ID,
                            IntegrationSupervisorAssembly.subscriptionFilter(), true),
                    integration.subscriber());
            // DP-8: the supervisor start is bounded — a hanging integration never
            // takes down core (INV-RF-01); the health surface carries the failure
            // and boot continues.
            try {
                integrationSupervisor.start(integrationFactories)
                        .get(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOG.error("integration.start_interrupted: integration startup interrupted; "
                        + "boot continues (INV-RF-01)", interrupted);
            } catch (ExecutionException | TimeoutException | RuntimeException startFailure) {
                LOG.error("integration.start_failed: integration startup failed or timed "
                        + "out; boot continues (INV-RF-01) — the supervisor health surface "
                        + "carries the failure", startFailure);
            }
            recordSubsystem("integration", LifecyclePhase.INTEGRATIONS, integrationStart);
        }

        // ── READY + RUNNING ──────────────────────────────────────────────────
        this.started = true;
        this.runningSince = clock.instant();
        // AB-3 READY semantics = engine running (HTTP is not exposed; AB-1 adds
        // the API-serving precondition).
        healthReporter.reportReady();
        setPhase(LifecyclePhase.RUNNING);
        this.healthLoop = new HealthLoop(
                healthReporter, clock, watchdogPeriod(System::getenv), this::buildHealthStatusLine);
        this.healthLoop.start();
        LOG.info("HomeSynapseCore RUNNING: db={}, configDir={}, homeId={}, automations={}; "
                        + "HTTP exposed behind bearer-token auth on {}:{} (AB-1), cipher inert={}",
                dbPath, configDir, homeId.value(),
                automationRegistry.getAll().size(),
                config.bindHost(), httpServer.port(), payloadCipher == null);
    }

    /**
     * Executes the shutdown sequence in reverse initialization order. Safe from
     * the JVM shutdown hook, from {@link #start()} on fatal failure, or from an
     * admin call. Idempotent; concurrent calls are serialized.
     *
     * @param reason human-readable reason; never {@code null}
     */
    @Override
    public void shutdown(String reason) {
        Objects.requireNonNull(reason, "reason");
        doTeardown(reason);
    }

    @Override
    public LifecyclePhase currentPhase() {
        return phase;
    }

    @Override
    public SystemHealthSnapshot healthSnapshot() {
        Duration uptime = (runningSince != null)
                ? Duration.between(runningSince, clock.instant())
                : null;
        long eventStorePosition = (stateQueryService != null)
                ? stateQueryService.getViewPosition()
                : 0L;
        int entityCount = (entityRegistry != null)
                ? entityRegistry.listAllEntities().size()
                : 0;
        int automationCount = (automationRegistry != null)
                ? automationRegistry.getAll().size()
                : 0;
        return new SystemHealthSnapshot(
                clock.instant(),
                Map.copyOf(subsystems),
                HealthStatus.HEALTHY,   // structural: no HealthContributors in AB-3
                uptime,
                eventStorePosition,
                entityCount,
                0,                       // integrationCount — none in AB-3
                automationCount);
    }

    @Override
    public Map<String, SubsystemState> subsystemStates() {
        return Map.copyOf(subsystems);
    }

    // ════════════════════════════════════════════════════════════════════════
    // External-interfaces bring-up (AB-1 — auth-gated, loopback-bound)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Brings up the embedded Javalin HTTP surface (the M3.6e REST entity + admin
     * endpoints) behind bearer-token authentication, loopback-bound by default
     * (AB-1 — the Phase-5 step that closes core-review C1).
     *
     * <p>Production {@code start()} now invokes the bring-up in Phase 5
     * (EXTERNAL_INTERFACES) so the surface comes up automatically behind auth;
     * the HTTP-aware E2E harness may also call this. It is idempotent — the
     * {@code httpServer != null} early-return makes a second call a no-op — so the
     * production path and the test/harness path converge on the same auth-gated,
     * loopback-bound bring-up.</p>
     *
     * @throws IllegalStateException if {@link #start()} has not completed
     */
    public void exposeHttpSurface() {
        requireStarted();
        bringUpHttpSurface();
    }

    /**
     * The actual auth-gated, loopback-bound HTTP bring-up. Reached from both the
     * public {@link #exposeHttpSurface()} (post-start) and the in-{@code start()}
     * Phase-5 invocation (where {@code started} is not yet set), so it does NOT
     * call {@link #requireStarted()}. Idempotent under the lifecycle lock.
     *
     * <p><strong>Auth-before-network-exposure invariant.</strong> The auth filter
     * ({@link AuthMiddleware} + {@link RateLimiter}) is installed BEFORE
     * {@code app.start(...)} binds the port — registered first so it precedes the
     * {@code /api/*} readiness gate and covers {@code /api/*}, {@code /internal/*},
     * and every path (INV-SE-02). The opaque-token store is config-resident; on a
     * fresh install it mints one full-access pairing token and surfaces it once
     * (boot log + {@code initial_api_token} artifact). Loopback bind is explicit
     * via {@code config.bindHost()} (default {@link HomeSynapseConfig#LOOPBACK_HOST}) —
     * never all-interfaces unless the LAN opt-in is configured (A1 / CC-1).</p>
     */
    private void bringUpHttpSurface() {
        lifecycleLock.lock();
        try {
            if (httpServer != null) {
                return;
            }
            // AB-1: build the local auth surface BEFORE binding any socket.
            OpaqueTokenStore tokenStore = new OpaqueTokenStore(configDir, clock);
            tokenStore.ensureInitialToken();
            AuthMiddleware authMiddleware = new StandardAuthMiddleware(tokenStore);
            RateLimiter rateLimiter = new StandardRateLimiter(clock);

            DeploymentProfile profile = config.persistence().profile();
            QueuedThreadPool threadPool = new QueuedThreadPool(
                    profile.javalinMaxThreads(), profile.javalinMinThreads());
            threadPool.setName("hs-http");
            Javalin app = Javalin.create(cfg -> {
                cfg.jetty.threadPool = threadPool;
                cfg.showJavalinBanner = false;
                // DASH-SERVE (Doc 13 §3.2–§3.3, composed at last): serve the packaged SPA
                // from the classpath at /dashboard, with the SPA fallback for client-side
                // routes. The bytes arrive via :web-ui:dashboard's resources jar
                // (runtimeOnly). Auth posture: the (A) static-shell exemption — see
                // RestFilters.installAuth.
                cfg.staticFiles.add(sf -> {
                    sf.hostedPath = "/dashboard";
                    sf.directory = "/dashboard";
                    sf.location = Location.CLASSPATH;
                });
                cfg.spaRoot.addFile("/dashboard", "/dashboard/index.html", Location.CLASSPATH);
            });
            // Auth MUST be registered before any other route/gate and before the
            // port binds (the C1 close). installAuth registers its before(*)
            // handler first, so it runs ahead of the /api/* readiness gate.
            RestFilters.installAuth(app, authMiddleware, rateLimiter);
            // DASH-SERVE: the human entrypoint — http://host:port/ lands on the SPA.
            // Covered by the same GET/HEAD shell exemption (posture (A)).
            app.get("/", ctx -> ctx.redirect("/dashboard/"));
            RestFilters.installReadinessGate(app, this);
            RestFilters.installEntityQueryEndpoints(
                    app, stateQueryService, stateProjection::cursorPosition, clock);
            // M7.5c-a: the /internal/* reads carry the frozen {data, meta} envelope
            // (v1.1.1 §A4/§A5, DRIFT-1). The log head (eventStore::latestPosition)
            // feeds the frozen A4 lagEvents; PROJECTION_VERSION is the same constant
            // handed to StateProjection.create. Both cross the gateway as java.base
            // types — no new rest-api module edge.
            RestFilters.installAdminEndpoints(
                    app, eventBus, this, stateQueryService, stateProjection::cursorPosition,
                    persistenceFactory.eventStore()::latestPosition, PROJECTION_VERSION, clock);
            // M7.5a: the run-query (causal read) endpoints. The ExplanationService is a pure
            // log-derived projection (reads the EventStore + the registry for best-effort
            // names); it is Object-erased on the gateway so com.homesynapse.automation stays
            // off rest-api's exported API. Installed AFTER auth + the readiness gate so
            // /api/v1/runs* inherit bearer auth and the 503 gate.
            ExplanationService explanationService =
                    ExplanationService.over(persistenceFactory.eventStore(), automationRegistry);
            RestFilters.installRunQueryEndpoints(
                    app, explanationService, stateProjection::cursorPosition, clock);
            // M7.5b: the automation read endpoints (GET /api/v1/automations and
            // /api/v1/automations/{id}/non-firing). Same ExplanationService (now also serving
            // explainNonFiring/listAutomations), same Object-erased gateway, same inheritance of
            // bearer auth + the 503 readiness gate.
            RestFilters.installAutomationQueryEndpoints(
                    app, explanationService, stateProjection::cursorPosition, clock);
            // CMD-API: the command write surface (POST issue + GET status) —
            // thin adapters over the existing pipeline (validate, publish ONE
            // root command_issued, read ONE correlation chain; the dispatch/
            // ledger subscribers own everything downstream). The timeout
            // fallback is the SAME config value the action executor receives
            // (Doc 07 §9; the constant is a long, the wire field an int —
            // 30 000 fits). Installed AFTER auth + the readiness gate so both
            // routes inherit bearer auth and the 503 gate.
            RestFilters.installCommandEndpoints(
                    app, eventPublisher, entityRegistry, persistenceFactory.eventStore(),
                    (int) PendingCommandLedgerAssembly.DEFAULT_CONFIRMATION_TIMEOUT_MS,
                    stateProjection::cursorPosition, clock);
            // AB-1: loopback bind by default; LAN exposure is the explicit
            // config.bindHost() opt-in. Never bind all-interfaces by default.
            app.start(config.bindHost(), config.httpPort());
            this.httpServer = app;
            LOG.info("HTTP surface exposed on {}:{} behind bearer-token auth (AB-1, C1 closed); "
                    + "loopback-default bindHost={}",
                    config.bindHost(), app.port(), config.bindHost());
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * @return {@code true} if {@link #exposeHttpSurface()} has bound the HTTP
     *         server. AB-3's production boot leaves this {@code false} (C1).
     */
    public boolean isHttpExposed() {
        return httpServer != null;
    }

    /**
     * Returns the actual bound HTTP port.
     *
     * @return the live bound HTTP port, always {@code > 0}
     * @throws IllegalStateException if not started or the HTTP surface is not
     *                               exposed (AB-3 does not expose it)
     */
    public int boundHttpPort() {
        if (!started) {
            throw new IllegalStateException("HomeSynapseCore not started");
        }
        if (httpServer == null) {
            throw new IllegalStateException("HTTP surface not exposed");
        }
        return httpServer.port();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Accessors
    // ════════════════════════════════════════════════════════════════════════

    /**
     * @return the production {@link EventPublisher}
     * @throws IllegalStateException if {@link #start()} has not been called
     */
    public EventPublisher eventPublisher() {
        requireStarted();
        return eventPublisher;
    }

    /**
     * @return the production {@link EventStore}
     * @throws IllegalStateException if {@link #start()} has not been called
     */
    public EventStore eventStore() {
        requireStarted();
        return persistenceFactory.eventStore();
    }

    /**
     * @return the in-process event bus
     * @throws IllegalStateException if {@link #start()} has not been called
     */
    public EventBus eventBus() {
        requireStarted();
        return eventBus;
    }

    /**
     * @return the production {@link StateQueryService}
     * @throws IllegalStateException if {@link #start()} has not been called
     */
    public StateQueryService stateQueryService() {
        requireStarted();
        return stateQueryService;
    }

    @Override
    public SubscriberMode mode() {
        if (!started) {
            return SubscriberMode.COLD;
        }
        return projectionMode();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Ungraceful shutdown
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Graceful shutdown alias retained for existing callers and test harnesses.
     * Equivalent to {@link #shutdown(String) shutdown("stop()")}.
     */
    public void stop() {
        doTeardown("stop()");
    }

    /**
     * Abandons the runtime, releasing OS-level resources without durability
     * operations (crash simulation / emergency shutdown). Normal shutdown MUST
     * use {@link #shutdown(String)} / {@link #stop()}.
     *
     * <p>Idempotent and mutually exclusive with graceful teardown.</p>
     */
    public void abandon() {
        lifecycleLock.lock();
        try {
            if (!started || abandoned) {
                return;
            }
            abandoned = true;
            started = false;
            setPhase(LifecyclePhase.SHUTTING_DOWN);
            if (healthLoop != null) {
                healthLoop.stop();
            }
            if (httpServer != null) {
                httpServer.stop();
            }
            if (scheduler != null) {
                scheduler.shutdown();
            }
            // Paired teardown for the command_dispatch_service subscriber (M7.4a), mirroring the
            // graceful path — eventBus.abandon() drops the subscription itself.
            if (commandDispatchService != null) {
                commandDispatchService.close();
            }
            if (triggerEvaluator != null) {
                triggerEvaluator.close();
            }
            // Paired teardown for the run pipeline (M7.4b), mirroring the graceful path — interrupt
            // in-flight Run VTs before eventBus.abandon() drops the subscriptions.
            if (runManager != null) {
                runManager.close();
            }
            // Fast supervisor stop for the integration spine (M9.1, W4): interrupt the adapters
            // and skip the grace-period waits and stopped-event publishes — the abandon path
            // skips durability work by design. The router's bus subscription (and its read
            // connection) is dropped by the bulk eventBus.abandon() below, exactly like the
            // ledger's — no per-subscriber unsubscribe in this branch.
            if (integrationSupervisor != null) {
                IntegrationSupervisorAssembly.abandon(integrationSupervisor);
            }
            // The pending_command_ledger (M7.4c) has NO separate close() — its only resource is the
            // bus's per-subscriber SQLite read connection, which eventBus.abandon() closes for every
            // active runtime below. So unlike commandDispatchService/runManager (which own their own
            // resources), the ledger needs no explicit teardown line here; abandon() covers it. This
            // mirrors how the dispatch subscriber's bus subscription is dropped in this path.
            if (eventBus != null) {
                eventBus.abandon();
            }
            if (persistenceFactory != null) {
                persistenceFactory.abandon();
            }
            setPhase(LifecyclePhase.STOPPED);
            LOG.warn("HomeSynapseCore abandoned (ungraceful shutdown): db={}", dbPath);
        } finally {
            lifecycleLock.unlock();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Internal
    // ════════════════════════════════════════════════════════════════════════

    private void doTeardown(String reason) {
        lifecycleLock.lock();
        try {
            if (abandoned || shutdownComplete) {
                return;
            }
            setPhase(LifecyclePhase.SHUTTING_DOWN);
            safelyReportStopping();
            started = false;

            // Reverse initialization order. Null-guarded so a partial teardown
            // after a fatal mid-boot failure (subsystems built but `started` not
            // yet set) still releases everything that came up.
            if (healthLoop != null) {
                healthLoop.stop();
            }
            if (httpServer != null) {
                httpServer.stop();
                httpServer = null;
            }
            if (scheduler != null) {
                scheduler.shutdown();
            }
            if (eventBus != null) {
                // Reverse registration order: integration router (Phase 6, registered last)
                // -> ledger (3.4c) -> dispatch (3.4b) -> automation (3.4) -> projection (3.2)
                // -> registry projection (3.1, AMD-99).
                // unsubscribe(...) closes each runtime's per-subscriber read connection — the
                // pending_command_ledger's is the M7.3-reverted leak; the M9.1 router holds
                // the same single resource, so its teardown leads the chain (symmetry is the
                // check).
                if (integrationSupervisor != null) {
                    eventBus.unsubscribe(IntegrationSupervisorAssembly.SUBSCRIBER_ID);
                }
                eventBus.unsubscribe(PendingCommandLedgerAssembly.SUBSCRIBER_ID);
                eventBus.unsubscribe(CommandDispatchAssembly.SUBSCRIBER_ID);
                eventBus.unsubscribe(AUTOMATION_SUBSCRIBER_ID);
                eventBus.unsubscribe(PROJECTION_SUBSCRIBER_ID);
                // The registry projection (AMD-99) registered FIRST (Step 3.1),
                // so its teardown closes the reverse chain (paired teardown; the
                // abandon branch drops it via the bulk eventBus.abandon()).
                eventBus.unsubscribe(RegistryProjectionSubscriber.SUBSCRIBER_ID);
            }
            // Paired teardown for the integration spine (M9.1): the adapters stop AFTER the
            // router unsubscribed (no new dispatches can reach a stopping adapter) and BEFORE
            // the dispatch service closes (W4 ordering). stop() is the graceful branch —
            // grace-bounded close per adapter, integration_stopped events published.
            if (integrationSupervisor != null) {
                integrationSupervisor.stop();
            }
            // Paired teardown for the command_dispatch_service subscriber (M7.4a): release any
            // held resource alongside triggerEvaluator.close() (the reverted-M7.3 lesson).
            if (commandDispatchService != null) {
                commandDispatchService.close();
            }
            if (triggerEvaluator != null) {
                triggerEvaluator.close();
            }
            // Paired teardown for the run pipeline (M7.4b): interrupt any in-flight Run VT so each
            // finalizes ABORTED rather than publishing into a tearing-down persistence layer (the
            // RunManager runs actions on per-Run virtual threads). Completed runs leave no live VT,
            // so this is a no-op then — the same paired-teardown discipline as triggerEvaluator.
            if (runManager != null) {
                runManager.close();
            }
            if (rateLimit != null) {
                rateLimit.close();
            }
            if (persistenceFactory != null) {
                persistenceFactory.close();
            }
            shutdownComplete = true;
            setPhase(LifecyclePhase.STOPPED);
            LOG.info("HomeSynapseCore stopped: db={} ({})", dbPath, reason);
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void safelyReportStopping() {
        if (healthReporter == null) {
            return;
        }
        try {
            healthReporter.reportStopping();
        } catch (RuntimeException e) {
            LOG.warn("reportStopping failed during shutdown", e);
        }
    }

    /**
     * Generalized pre-migration rollback hook (R-δ AX-2). Additive — AB-3 runs
     * no destructive forced migration: {@code ConfigurationService.load()}
     * performs its own §3.3 timestamped config backup internally, and the
     * chain-migration snapshot path lands in AB-4. This hook is the wired
     * extension point for that future path.
     */
    private void snapshotBeforeMigration() {
        LOG.debug("pre-migration snapshot checkpoint: config self-backs-up on migrate; "
                + "chain-migration snapshot wires in AB-4");
    }

    /**
     * Blocks until the state projection subscriber reaches {@code LIVE} (the
     * bus drives COLD → REPLAY → TRANSITION → LIVE on its own VT). On a fresh or
     * small log this completes in milliseconds. The poll uses real-time sleeps
     * and an iteration cap (clock-independent, so a {@code Clock.fixed} test
     * still terminates).
     */
    private void awaitProjectionLive() {
        final int maxPolls = 1_500; // ~30s at 20ms
        for (int i = 0; i < maxPolls; i++) {
            if (projectionMode() == SubscriberMode.LIVE) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted while awaiting state-projection LIVE", e);
            }
        }
        throw new IllegalStateException(
                "state projection did not reach LIVE within ~30s during startup");
    }

    private SubscriberMode projectionMode() {
        if (eventBus == null) {
            return SubscriberMode.COLD;
        }
        return eventBus.subscribers().stream()
                .filter(s -> PROJECTION_SUBSCRIBER_ID.equals(s.subscriberId()))
                .findFirst()
                .map(SubscriberSnapshot::mode)
                .orElse(SubscriberMode.COLD);
    }

    /**
     * Blocks until the registry-projection subscriber reaches {@code LIVE} --
     * the {@link #awaitProjectionLive()} sibling (AMD-99 §5): the registries
     * must be caught up to the log head before integrations resume and before
     * automation definitions bind entity refs.
     */
    private void awaitRegistryProjectionLive() {
        final int maxPolls = 1_500; // ~30s at 20ms
        for (int i = 0; i < maxPolls; i++) {
            if (registryProjectionMode() == SubscriberMode.LIVE) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted while awaiting registry-projection LIVE", e);
            }
        }
        throw new IllegalStateException(
                "registry projection did not reach LIVE within ~30s during startup");
    }

    private SubscriberMode registryProjectionMode() {
        if (eventBus == null) {
            return SubscriberMode.COLD;
        }
        return eventBus.subscribers().stream()
                .filter(s -> RegistryProjectionSubscriber.SUBSCRIBER_ID
                        .equals(s.subscriberId()))
                .findFirst()
                .map(SubscriberSnapshot::mode)
                .orElse(SubscriberMode.COLD);
    }

    private String buildHealthStatusLine() {
        int entities = (entityRegistry != null) ? entityRegistry.listAllEntities().size() : 0;
        int automations = (automationRegistry != null) ? automationRegistry.getAll().size() : 0;
        return "RUNNING: " + entities + " entities, " + automations
                + " automations, 0 integrations";
    }

    /**
     * Surfaces each rejected automation definition (SD-9): logs it and publishes
     * a {@code config_error} event, exactly as the {@link AutomationDefinitionLoader}
     * contract specifies ("a LoadFailure the caller publishes as config_error").
     * Best-effort — a publish failure is logged and never fails the boot
     * (AMD-70-INV-01), and valid sibling definitions have already loaded.
     */
    private void surfaceAutomationLoadFailures(LoadResult loadResult) {
        for (LoadFailure failure : loadResult.failures()) {
            LOG.error("automation definition '{}' rejected (SD-9 — valid siblings still "
                    + "load): {}", failure.automationName(), failure.detail());
            try {
                eventPublisher.publishRoot(new EventDraft(
                        EventTypes.CONFIG_ERROR,
                        1,
                        null,
                        SubjectRef.system(systemId),
                        EventPriority.DIAGNOSTIC,
                        EventOrigin.SYSTEM,
                        new ConfigErrorEvent(
                                AutomationSchema.SCHEMA_SECTION + "." + failure.automationName(),
                                "ERROR",
                                failure.detail(),
                                "(none)"),
                        null,
                        null));
            } catch (SequenceConflictException | RuntimeException e) {
                LOG.error("config_error publication for rejected automation '{}' failed; "
                        + "the rejection is still logged above", failure.automationName(), e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> automationSection(Map<String, Object> rawConfig) {
        Object section = rawConfig.get(AutomationSchema.SCHEMA_SECTION);
        return (section instanceof Map<?, ?> map) ? (Map<String, Object>) map : Map.of();
    }

    private void setPhase(LifecyclePhase next) {
        this.phase = next;
    }

    // ── Package-private accessors for the lifecycle wiring test (NOT exported API,
    //    so config/device/automation stay non-transitive requires) ─────────────

    /**
     * Registers an integration adapter's config-schema fragment (W10). Doc 12
     * defers INTEGRATION schemas past core composition (only CORE schemas register
     * before {@code config.load()}), so the composition-root host calls this after
     * {@code start()} returns. The signature is {@code java.base}-only by design —
     * the M3.6e.1 gateway pattern keeps {@code com.homesynapse.config} off this
     * module's exported API (its {@code requires} stays non-transitive).
     *
     * @param integrationType the integration type key (e.g. {@code "zigbee"}),
     *        never {@code null}
     * @param schemaJson the schema fragment as JSON text, never {@code null}
     * @throws IllegalStateException if called before {@code start()} assembled the
     *         configuration subsystem
     */
    public void registerIntegrationSchema(String integrationType, String schemaJson) {
        SchemaRegistry registry = this.schemaRegistry;
        if (registry == null) {
            throw new IllegalStateException(
                    "Integration schemas register after start(); the configuration "
                            + "subsystem is not assembled yet");
        }
        registry.registerIntegrationSchema(integrationType, schemaJson);
    }

    /**
     * The AMD-99 registry projection -- THE single registry-apply path
     * (REG-INV-1). PUBLIC and deliberately unguarded by {@code requireStarted}:
     * the zigbee factory's {@code Supplier<RegistryProjection>} resolves it at
     * {@code create(...)} time, which runs DURING {@code start()} Phase 6
     * (after Phase 3 constructed it, before {@code started} is set) -- the
     * M9.4b R4 deviceRegistry injection path applied to the projection.
     *
     * @return the registry projection (or {@code null} before start() reaches
     *         Phase 3 Step 3.1)
     */
    public RegistryProjection registryProjection() {
        return registryProjection;
    }

    /** @return the schema registry (or {@code null} before start). */
    SchemaRegistry schemaRegistry() {
        return schemaRegistry;
    }

    /** @return the assembled configuration service (or {@code null} before start). */
    ConfigurationService configurationService() {
        return configurationService;
    }

    /** @return the assembled entity registry (or {@code null} before start). */
    EntityRegistry entityRegistry() {
        return entityRegistry;
    }

    /** @return the assembled device registry (or {@code null} before start). */
    DeviceRegistry deviceRegistry() {
        return deviceRegistry;
    }

    /** @return the assembled area registry (or {@code null} before start). */
    AreaRegistry areaRegistry() {
        return areaRegistry;
    }

    /** @return the loaded automation registry (or {@code null} before start). */
    StandardAutomationRegistry automationRegistry() {
        return automationRegistry;
    }

    /** @return the live pending-command ledger query surface (or {@code null} before start). */
    PendingCommandLedger pendingCommandLedger() {
        return pendingCommandLedger;
    }

    /**
     * @return the integration supervisor (or {@code null} before start, and null
     *         forever when the factory list is empty — Phase 6 skipped, DP-7)
     */
    IntegrationSupervisor integrationSupervisor() {
        return integrationSupervisor;
    }

    private void recordSubsystem(String name, LifecyclePhase subsystemPhase, Instant startInstant) {
        Duration initDuration = Duration.between(startInstant, clock.instant());
        subsystems.put(name, new SubsystemState(
                name, subsystemPhase, SubsystemStatus.RUNNING, null, initDuration, null));
    }

    private void requireStarted() {
        if (!started) {
            throw new IllegalStateException("HomeSynapseCore not started");
        }
    }

    /**
     * Selects the platform {@link HealthReporter}: {@link SystemdHealthReporter}
     * when {@code $NOTIFY_SOCKET} is set, else {@link NoOpHealthReporter}.
     *
     * <p>{@link SystemdHealthReporter}'s production transport is unavailable on
     * stock JDK 21 (AF_UNIX SOCK_DGRAM, deferred to M13) and throws at
     * construction; this falls back to {@link NoOpHealthReporter} on any
     * construction failure so a systemd host does not crash the boot.</p>
     *
     * @param env environment lookup ({@code System::getenv} in production)
     * @return the selected reporter; never {@code null}
     */
    static HealthReporter selectHealthReporter(Function<String, String> env) {
        String notifySocket = env.apply("NOTIFY_SOCKET");
        if (notifySocket == null || notifySocket.isBlank()) {
            return new NoOpHealthReporter();
        }
        try {
            return new SystemdHealthReporter(notifySocket);
        } catch (IOException | RuntimeException e) {
            LOG.warn("$NOTIFY_SOCKET is set but SystemdHealthReporter is unavailable on this "
                    + "JVM ({}); falling back to NoOpHealthReporter (systemd watchdog disabled)",
                    e.toString());
            return new NoOpHealthReporter();
        }
    }

    /**
     * Computes the health-loop notify period: {@code WatchdogSec / 2}, where
     * {@code WatchdogSec} derives from {@code $WATCHDOG_USEC} (systemd, LTD-13)
     * or defaults to {@value #DEFAULT_WATCHDOG_SECONDS} seconds.
     *
     * @param env environment lookup ({@code System::getenv} in production)
     * @return the notify period (≥ 1s)
     */
    static Duration watchdogPeriod(Function<String, String> env) {
        long watchdogSeconds = DEFAULT_WATCHDOG_SECONDS;
        String watchdogUsec = env.apply("WATCHDOG_USEC");
        if (watchdogUsec != null && !watchdogUsec.isBlank()) {
            try {
                long micros = Long.parseLong(watchdogUsec.trim());
                if (micros > 0) {
                    watchdogSeconds = micros / 1_000_000L;
                }
            } catch (NumberFormatException e) {
                LOG.warn("invalid $WATCHDOG_USEC '{}'; using default {}s",
                        watchdogUsec, DEFAULT_WATCHDOG_SECONDS);
            }
        }
        return Duration.ofSeconds(Math.max(1L, watchdogSeconds / 2L));
    }
}
