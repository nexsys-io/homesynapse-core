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
import com.homesynapse.config.ConfigurationService;
import com.homesynapse.config.ConfigurationServiceFactory;
import com.homesynapse.config.SchemaRegistry;
import com.homesynapse.device.AreaRegistry;
import com.homesynapse.device.DeviceRegistry;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.device.InMemoryAreaRegistry;
import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.device.InMemoryEntityRegistry;
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
 *   <li><b>INTEGRATIONS</b> — out of scope for AB-3 (adapters connect later).</li>
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
    private StandardAutomationRegistry automationRegistry;
    private StandardTriggerEvaluator triggerEvaluator;
    private RunManager runManager;
    private CommandDispatchService commandDispatchService;
    private PendingCommandLedger pendingCommandLedger;
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
     * (Doc 15 §3.8). AB-3 passes {@code null} here (cipher inert); AB-4 passes
     * the real adapter.
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
        this.dbPath = Objects.requireNonNull(dbPath, "dbPath");
        this.configDir = Objects.requireNonNull(configDir, "configDir");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.homeId = Objects.requireNonNull(homeId, "homeId");
        this.payloadCipher = payloadCipher;
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
        // device-subject events; AB-3 starts them empty, INV-CE-02).
        Instant deviceStart = clock.instant();
        this.entityRegistry = new InMemoryEntityRegistry();
        this.deviceRegistry = new InMemoryDeviceRegistry();
        this.areaRegistry = new InMemoryAreaRegistry();
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
                5,                                          // M4.0b-5 (AMD-53) projection version
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
        this.stateQueryService = StateQueryService.materialized(
                persistenceFactory.stateStore(), this, stateProjection::cursorPosition, clock);
        recordSubsystem("state-store", LifecyclePhase.CORE_DOMAIN, stateStart);

        // The catch-up ordering invariant: the automation engine must not
        // evaluate against partially-replayed state. Gate the automation_engine
        // subscribe on the state projection reaching LIVE.
        awaitProjectionLive();

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
                        PendingCommandLedgerAssembly.DEFAULT_CONFIRMATION_TIMEOUT_MS);
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

        // ── Phase 6 INTEGRATIONS — out of scope for AB-3 ─────────────────────
        setPhase(LifecyclePhase.INTEGRATIONS);

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
            });
            // Auth MUST be registered before any other route/gate and before the
            // port binds (the C1 close). installAuth registers its before(*)
            // handler first, so it runs ahead of the /api/* readiness gate.
            RestFilters.installAuth(app, authMiddleware, rateLimiter);
            RestFilters.installReadinessGate(app, this);
            RestFilters.installEntityQueryEndpoints(
                    app, stateQueryService, stateProjection::cursorPosition, clock);
            RestFilters.installAdminEndpoints(
                    app, eventBus, this, stateQueryService, stateProjection::cursorPosition);
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
                // Reverse registration order: ledger (3.4c) -> dispatch (3.4b) -> automation (3.4)
                // -> projection (3.2). unsubscribe(...) closes each runtime's per-subscriber read
                // connection — the pending_command_ledger's is the M7.3-reverted leak, so its
                // teardown sits alongside command_dispatch_service's here (symmetry is the check).
                eventBus.unsubscribe(PendingCommandLedgerAssembly.SUBSCRIBER_ID);
                eventBus.unsubscribe(CommandDispatchAssembly.SUBSCRIBER_ID);
                eventBus.unsubscribe(AUTOMATION_SUBSCRIBER_ID);
                eventBus.unsubscribe(PROJECTION_SUBSCRIBER_ID);
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
