/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.test;

import com.homesynapse.config.ConfigurationAccess;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.integration.HealthReporter;
import com.homesynapse.integration.HealthState;
import com.homesynapse.integration.IntegrationContext;
import com.homesynapse.integration.ManagedHttpClient;
import com.homesynapse.integration.SchedulerService;
import com.homesynapse.persistence.TelemetryWriter;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.StateQueryService;
import com.homesynapse.state.StateSnapshot;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builder-pattern factory for creating {@link IntegrationContext} instances
 * with fully wired in-memory stub implementations for testing.
 *
 * <p>{@code StubIntegrationContext} provides two creation modes:</p>
 * <ul>
 *   <li>{@link #defaults()} — returns a fully valid {@code IntegrationContext}
 *       with zero arguments. All 7 required fields are populated with sensible
 *       defaults (fresh ULID identities, {@link InMemoryEventStore} for
 *       {@link EventPublisher}, empty stub registries). Optional fields are
 *       {@code null}.</li>
 *   <li>{@link #builder()} — returns a {@link Builder} for full customization.
 *       All fields are pre-initialized to the same defaults as {@link #defaults()}.
 *       Override only what your test needs.</li>
 * </ul>
 *
 * <p>The builder's {@link Builder#build()} method returns
 * {@link IntegrationContext} — the production record — not a wrapper. This
 * ensures adapters under test receive the exact same type they receive in
 * production.</p>
 *
 * <h2>Inner Stub Classes</h2>
 *
 * <p>Four package-private inner stubs provide the in-memory implementations:</p>
 * <ul>
 *   <li>{@link StubEntityRegistry} — {@link ConcurrentHashMap}-backed
 *       {@link EntityRegistry} with all 9 methods.</li>
 *   <li>{@link StubStateQueryService} — {@link ConcurrentHashMap}-backed
 *       {@link StateQueryService} with all 5 methods.</li>
 *   <li>{@link StubHealthReporter} — {@link CopyOnWriteArrayList}-recording
 *       {@link HealthReporter} with a sealed {@link HealthSignal} interface
 *       for assertion.</li>
 *   <li>{@link StubConfigAccess} — {@link ConcurrentHashMap}-backed
 *       {@link ConfigurationAccess} with all 4 methods.</li>
 * </ul>
 *
 * @see IntegrationContext
 * @see InMemoryEventStore
 * @see TestAdapter
 * @see StubCommandHandler
 */
public final class StubIntegrationContext {

    /** Fixed clock for deterministic tests. */
    private static final Instant DEFAULT_INSTANT =
            Instant.parse("2026-04-07T12:00:00Z");

    /** Fixed clock used by default for InMemoryEventStore. */
    private static final Clock DEFAULT_CLOCK =
            Clock.fixed(DEFAULT_INSTANT, ZoneOffset.UTC);

    private StubIntegrationContext() {
        // Utility class — no instantiation.
    }

    // ──────────────────────────────────────────────────────────────────
    // Factory methods
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates a fully valid {@link IntegrationContext} with sensible defaults.
     *
     * <p>All 7 required fields are populated: fresh ULID identities,
     * {@link InMemoryEventStore} as {@link EventPublisher}, empty stub
     * registries, and a no-op {@link StubHealthReporter}. The 3 optional
     * fields ({@code schedulerService}, {@code telemetryWriter},
     * {@code httpClient}) are {@code null}.</p>
     *
     * @return a valid IntegrationContext with default stubs
     */
    public static IntegrationContext defaults() {
        return builder().build();
    }

    /**
     * Returns a builder for full customization of an {@link IntegrationContext}.
     *
     * <p>All fields are pre-initialized to the same defaults as
     * {@link #defaults()}. Override only what your test needs.</p>
     *
     * @return a new Builder with default values
     */
    public static Builder builder() {
        return new Builder();
    }

    // ──────────────────────────────────────────────────────────────────
    // Builder
    // ──────────────────────────────────────────────────────────────────

    /**
     * Mutable builder for {@link IntegrationContext} instances with fluent API.
     *
     * <p>All 10 fields are pre-initialized to sensible defaults. Call setters
     * only for the fields you want to override, then call {@link #build()}.
     * The builder also provides convenience methods for pre-populating stub
     * data ({@link #withEntity(Entity)}, {@link #withConfig(String, Object)},
     * {@link #withClock(Clock)}).</p>
     */
    public static final class Builder {

        private IntegrationId integrationId;
        private String integrationType = "test";
        private EventPublisher eventPublisher;
        private EntityRegistry entityRegistry;
        private StateQueryService stateQueryService;
        private HealthReporter healthReporter;
        private ConfigurationAccess configAccess;
        private SchedulerService schedulerService;
        private TelemetryWriter telemetryWriter;
        private ManagedHttpClient httpClient;

        // Internal state for convenience methods
        private Clock clock = DEFAULT_CLOCK;
        private final List<Entity> preloadEntities = new ArrayList<>();
        private final ConcurrentHashMap<String, Object> preloadConfig =
                new ConcurrentHashMap<>();

        Builder() {
            // Package-private — created via StubIntegrationContext.builder()
        }

        /** Sets the integration identifier. */
        public Builder integrationId(IntegrationId integrationId) {
            this.integrationId = integrationId;
            return this;
        }

        /** Sets the integration type string. */
        public Builder integrationType(String integrationType) {
            this.integrationType = integrationType;
            return this;
        }

        /** Sets the event publisher. */
        public Builder eventPublisher(EventPublisher eventPublisher) {
            this.eventPublisher = eventPublisher;
            return this;
        }

        /** Sets the entity registry. */
        public Builder entityRegistry(EntityRegistry entityRegistry) {
            this.entityRegistry = entityRegistry;
            return this;
        }

        /** Sets the state query service. */
        public Builder stateQueryService(StateQueryService stateQueryService) {
            this.stateQueryService = stateQueryService;
            return this;
        }

        /** Sets the health reporter. */
        public Builder healthReporter(HealthReporter healthReporter) {
            this.healthReporter = healthReporter;
            return this;
        }

        /** Sets the configuration access. */
        public Builder configAccess(ConfigurationAccess configAccess) {
            this.configAccess = configAccess;
            return this;
        }

        /** Sets the scheduler service ({@code null} to omit). */
        public Builder schedulerService(SchedulerService schedulerService) {
            this.schedulerService = schedulerService;
            return this;
        }

        /** Sets the telemetry writer ({@code null} to omit). */
        public Builder telemetryWriter(TelemetryWriter telemetryWriter) {
            this.telemetryWriter = telemetryWriter;
            return this;
        }

        /** Sets the managed HTTP client ({@code null} to omit). */
        public Builder httpClient(ManagedHttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /**
         * Sets the clock used by the default {@link InMemoryEventStore}.
         *
         * <p>Only takes effect if {@link #eventPublisher(EventPublisher)} was
         * not explicitly set — the builder creates an {@link InMemoryEventStore}
         * with this clock as the default event publisher.</p>
         *
         * @param clock the clock to use; never {@code null}
         * @return this builder
         */
        public Builder withClock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock must not be null");
            return this;
        }

        /**
         * Pre-loads an entity into the default {@link StubEntityRegistry}.
         *
         * <p>Only takes effect if {@link #entityRegistry(EntityRegistry)} was
         * not explicitly set. Multiple calls accumulate entities.</p>
         *
         * @param entity the entity to pre-load; never {@code null}
         * @return this builder
         */
        public Builder withEntity(Entity entity) {
            Objects.requireNonNull(entity, "entity must not be null");
            preloadEntities.add(entity);
            return this;
        }

        /**
         * Pre-loads a configuration entry into the default {@link StubConfigAccess}.
         *
         * <p>Only takes effect if {@link #configAccess(ConfigurationAccess)} was
         * not explicitly set. Multiple calls accumulate entries.</p>
         *
         * @param key   the configuration key; never {@code null}
         * @param value the configuration value; never {@code null}
         * @return this builder
         */
        public Builder withConfig(String key, Object value) {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(value, "value must not be null");
            preloadConfig.put(key, value);
            return this;
        }

        /**
         * Builds the {@link IntegrationContext} record.
         *
         * <p>Fields not explicitly set are populated with sensible defaults:
         * fresh ULID identities, {@link InMemoryEventStore} as event publisher,
         * empty stub registries and config access, no-op health reporter.
         * Pre-loaded entities and config entries are applied to the default
         * stubs.</p>
         *
         * @return a valid IntegrationContext
         */
        public IntegrationContext build() {
            IntegrationId effectiveId = integrationId != null
                    ? integrationId : IntegrationId.of(UlidFactory.generate());

            EventPublisher effectivePublisher = eventPublisher != null
                    ? eventPublisher : new InMemoryEventStore(clock);

            StubEntityRegistry defaultRegistry = null;
            EntityRegistry effectiveRegistry;
            if (entityRegistry != null) {
                effectiveRegistry = entityRegistry;
            } else {
                defaultRegistry = new StubEntityRegistry();
                effectiveRegistry = defaultRegistry;
            }

            StateQueryService effectiveStateQuery = stateQueryService != null
                    ? stateQueryService : new StubStateQueryService();

            HealthReporter effectiveHealth = healthReporter != null
                    ? healthReporter : new StubHealthReporter();

            StubConfigAccess defaultConfig = null;
            ConfigurationAccess effectiveConfig;
            if (configAccess != null) {
                effectiveConfig = configAccess;
            } else {
                defaultConfig = new StubConfigAccess();
                effectiveConfig = defaultConfig;
            }

            // Apply pre-loaded entities to default registry
            if (defaultRegistry != null) {
                for (Entity entity : preloadEntities) {
                    defaultRegistry.createEntity(entity);
                }
            }

            // Apply pre-loaded config to default config access
            if (defaultConfig != null) {
                for (Map.Entry<String, Object> entry : preloadConfig.entrySet()) {
                    defaultConfig.put(entry.getKey(), entry.getValue());
                }
            }

            return new IntegrationContext(
                    effectiveId,
                    integrationType,
                    effectivePublisher,
                    effectiveRegistry,
                    effectiveStateQuery,
                    effectiveHealth,
                    effectiveConfig,
                    schedulerService,
                    telemetryWriter,
                    httpClient);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // StubEntityRegistry
    // ──────────────────────────────────────────────────────────────────

    /**
     * In-memory, {@link ConcurrentHashMap}-backed {@link EntityRegistry}
     * for testing.
     *
     * <p>All 9 methods are implemented. Entities are stored by
     * {@link EntityId}. Thread-safe for concurrent access.</p>
     */
    static final class StubEntityRegistry implements EntityRegistry {

        private final ConcurrentHashMap<EntityId, Entity> entities =
                new ConcurrentHashMap<>();

        /** Creates a new stub entity registry. */
        StubEntityRegistry() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Override
        public Entity getEntity(EntityId entityId) {
            Objects.requireNonNull(entityId, "entityId must not be null");
            Entity entity = entities.get(entityId);
            if (entity == null) {
                throw new IllegalArgumentException(
                        "No entity found with id: " + entityId);
            }
            return entity;
        }

        @Override
        public Optional<Entity> findEntity(EntityId entityId) {
            Objects.requireNonNull(entityId, "entityId must not be null");
            return Optional.ofNullable(entities.get(entityId));
        }

        @Override
        public List<Entity> listAllEntities() {
            return List.copyOf(entities.values());
        }

        @Override
        public List<Entity> listEntitiesByDevice(DeviceId deviceId) {
            Objects.requireNonNull(deviceId, "deviceId must not be null");
            List<Entity> result = new ArrayList<>();
            for (Entity entity : entities.values()) {
                if (deviceId.equals(entity.deviceId())) {
                    result.add(entity);
                }
            }
            return Collections.unmodifiableList(result);
        }

        @Override
        public Entity createEntity(Entity entity) {
            Objects.requireNonNull(entity, "entity must not be null");
            entities.put(entity.entityId(), entity);
            return entity;
        }

        @Override
        public Entity updateEntity(Entity entity) {
            Objects.requireNonNull(entity, "entity must not be null");
            if (!entities.containsKey(entity.entityId())) {
                throw new IllegalArgumentException(
                        "No entity found with id: " + entity.entityId());
            }
            entities.put(entity.entityId(), entity);
            return entity;
        }

        @Override
        public void removeEntity(EntityId entityId) {
            Objects.requireNonNull(entityId, "entityId must not be null");
            if (entities.remove(entityId) == null) {
                throw new IllegalArgumentException(
                        "No entity found with id: " + entityId);
            }
        }

        @Override
        public void enableEntity(EntityId entityId) {
            Objects.requireNonNull(entityId, "entityId must not be null");
            Entity existing = getEntity(entityId); // throws if not found
            Entity enabled = new Entity(
                    existing.entityId(),
                    existing.entitySlug(),
                    existing.entityType(),
                    existing.displayName(),
                    existing.deviceId(),
                    existing.endpointIndex(),
                    existing.areaId(),
                    true,
                    existing.labels(),
                    existing.capabilities(),
                    existing.createdAt());
            entities.put(entityId, enabled);
        }

        @Override
        public void disableEntity(EntityId entityId) {
            Objects.requireNonNull(entityId, "entityId must not be null");
            Entity existing = getEntity(entityId); // throws if not found
            Entity disabled = new Entity(
                    existing.entityId(),
                    existing.entitySlug(),
                    existing.entityType(),
                    existing.displayName(),
                    existing.deviceId(),
                    existing.endpointIndex(),
                    existing.areaId(),
                    false,
                    existing.labels(),
                    existing.capabilities(),
                    existing.createdAt());
            entities.put(entityId, disabled);
        }

        /**
         * Returns the number of entities in this registry.
         *
         * @return entity count
         */
        int size() {
            return entities.size();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // StubStateQueryService
    // ──────────────────────────────────────────────────────────────────

    /**
     * In-memory, {@link ConcurrentHashMap}-backed {@link StateQueryService}
     * for testing.
     *
     * <p>All 5 methods are implemented. State entries can be pre-loaded via
     * {@link #putState(EntityId, EntityState)}. Thread-safe for concurrent
     * access.</p>
     */
    static final class StubStateQueryService implements StateQueryService {

        private final ConcurrentHashMap<EntityId, EntityState> states =
                new ConcurrentHashMap<>();
        private final AtomicLong viewPosition = new AtomicLong(0);
        private volatile boolean ready = true;

        /** Creates a new stub state query service. */
        StubStateQueryService() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Override
        public Optional<EntityState> getState(EntityId entityId) {
            Objects.requireNonNull(entityId, "entityId must not be null");
            return Optional.ofNullable(states.get(entityId));
        }

        @Override
        public Map<EntityId, EntityState> getStates(Set<EntityId> entityIds) {
            Objects.requireNonNull(entityIds, "entityIds must not be null");
            Map<EntityId, EntityState> result = new ConcurrentHashMap<>();
            for (EntityId id : entityIds) {
                EntityState state = states.get(id);
                if (state != null) {
                    result.put(id, state);
                }
            }
            return Collections.unmodifiableMap(result);
        }

        @Override
        public StateSnapshot getSnapshot() {
            return new StateSnapshot(
                    Map.copyOf(states),
                    viewPosition.get(),
                    Instant.now(),
                    false,
                    Set.of());
        }

        @Override
        public long getViewPosition() {
            return viewPosition.get();
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        /**
         * Pre-loads a state entry for testing.
         *
         * @param entityId the entity identifier; never {@code null}
         * @param state    the entity state; never {@code null}
         */
        void putState(EntityId entityId, EntityState state) {
            Objects.requireNonNull(entityId, "entityId must not be null");
            Objects.requireNonNull(state, "state must not be null");
            states.put(entityId, state);
            viewPosition.incrementAndGet();
        }

        /**
         * Sets the ready flag.
         *
         * @param ready whether the service reports as ready
         */
        void setReady(boolean ready) {
            this.ready = ready;
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // StubHealthReporter
    // ──────────────────────────────────────────────────────────────────

    /**
     * Recording {@link HealthReporter} that captures all health signals
     * for assertion in tests.
     *
     * <p>All signals are stored as {@link HealthSignal} instances in a
     * {@link CopyOnWriteArrayList} for thread-safe access. Tests can inspect
     * the signal history via {@link #signals()}.</p>
     */
    static final class StubHealthReporter implements HealthReporter {

        private final CopyOnWriteArrayList<HealthSignal> signals =
                new CopyOnWriteArrayList<>();

        /** Creates a new stub health reporter. */
        StubHealthReporter() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Override
        public void reportHeartbeat() {
            signals.add(new Heartbeat(Instant.now()));
        }

        @Override
        public void reportKeepalive(Instant lastSuccess) {
            Objects.requireNonNull(lastSuccess, "lastSuccess must not be null");
            signals.add(new Keepalive(lastSuccess));
        }

        @Override
        public void reportError(Throwable error) {
            Objects.requireNonNull(error, "error must not be null");
            signals.add(new ErrorReport(error));
        }

        @Override
        public void reportHealthTransition(HealthState suggestedState,
                                           String reason) {
            Objects.requireNonNull(suggestedState,
                    "suggestedState must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            signals.add(new Transition(suggestedState, reason));
        }

        /**
         * Returns the recorded health signals.
         *
         * @return unmodifiable list of health signals in order of recording
         */
        List<HealthSignal> signals() {
            return Collections.unmodifiableList(signals);
        }

        /**
         * Clears all recorded health signals.
         */
        void clear() {
            signals.clear();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // HealthSignal sealed hierarchy
    // ──────────────────────────────────────────────────────────────────

    /**
     * Sealed interface representing a health signal recorded by
     * {@link StubHealthReporter}.
     *
     * <p>Permits four concrete record types matching the four
     * {@link HealthReporter} methods.</p>
     */
    sealed interface HealthSignal
            permits Heartbeat, Keepalive, ErrorReport, Transition {
    }

    /**
     * A heartbeat signal — the adapter is alive and executing.
     *
     * @param recordedAt the instant when the heartbeat was recorded
     */
    record Heartbeat(Instant recordedAt) implements HealthSignal {

        /** Creates a heartbeat signal. */
        Heartbeat {
            Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        }
    }

    /**
     * A keepalive signal — the adapter has confirmed protocol-level connectivity.
     *
     * @param lastSuccess the timestamp of the last successful protocol communication
     */
    record Keepalive(Instant lastSuccess) implements HealthSignal {

        /** Creates a keepalive signal. */
        Keepalive {
            Objects.requireNonNull(lastSuccess, "lastSuccess must not be null");
        }
    }

    /**
     * An error report — the adapter has registered a handled error.
     *
     * @param error the error that was reported
     */
    record ErrorReport(Throwable error) implements HealthSignal {

        /** Creates an error report signal. */
        ErrorReport {
            Objects.requireNonNull(error, "error must not be null");
        }
    }

    /**
     * A health transition suggestion — the adapter suggests a state change.
     *
     * @param suggestedState the health state the adapter suggests
     * @param reason         human-readable explanation for the suggestion
     */
    record Transition(HealthState suggestedState, String reason)
            implements HealthSignal {

        /** Creates a health transition signal. */
        Transition {
            Objects.requireNonNull(suggestedState,
                    "suggestedState must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // StubConfigAccess
    // ──────────────────────────────────────────────────────────────────

    /**
     * In-memory, {@link ConcurrentHashMap}-backed {@link ConfigurationAccess}
     * for testing.
     *
     * <p>All 4 methods are implemented. Configuration entries can be
     * pre-loaded via {@link #put(String, Object)}. Thread-safe for
     * concurrent access.</p>
     */
    static final class StubConfigAccess implements ConfigurationAccess {

        private final ConcurrentHashMap<String, Object> config =
                new ConcurrentHashMap<>();

        /** Creates a new stub configuration access. */
        StubConfigAccess() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Override
        public Map<String, Object> getConfig() {
            return Collections.unmodifiableMap(config);
        }

        @Override
        public Optional<String> getString(String key) {
            Objects.requireNonNull(key, "key must not be null");
            Object value = config.get(key);
            return value instanceof String s ? Optional.of(s) : Optional.empty();
        }

        @Override
        public Optional<Integer> getInt(String key) {
            Objects.requireNonNull(key, "key must not be null");
            Object value = config.get(key);
            return value instanceof Integer i ? Optional.of(i) : Optional.empty();
        }

        @Override
        public Optional<Boolean> getBoolean(String key) {
            Objects.requireNonNull(key, "key must not be null");
            Object value = config.get(key);
            return value instanceof Boolean b ? Optional.of(b) : Optional.empty();
        }

        /**
         * Adds or replaces a configuration entry.
         *
         * @param key   the configuration key; never {@code null}
         * @param value the configuration value; never {@code null}
         */
        void put(String key, Object value) {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(value, "value must not be null");
            config.put(key, value);
        }

        /**
         * Returns the number of configuration entries.
         *
         * @return entry count
         */
        int size() {
            return config.size();
        }
    }
}
