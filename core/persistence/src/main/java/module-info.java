/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Persistence Layer — telemetry ring store, backup/restore, retention,
 * and storage maintenance contracts (Doc 04).
 *
 * <p>This module defines the public API interfaces consumed by other
 * HomeSynapse subsystems. The implementation (SQLite JDBC operations,
 * WAL management, retention scheduler) lives in this module's Phase 3
 * implementation classes.</p>
 *
 * <p>The Persistence Layer implements two checkpoint contracts from two
 * different subsystems:</p>
 * <ul>
 *   <li>{@link com.homesynapse.state.ViewCheckpointStore} from the
 *       state-store module — durable storage behind the State Store's
 *       view-checkpoint mechanism (M2 scope placeholder).</li>
 *   <li>{@link com.homesynapse.event.bus.CheckpointStore} from the
 *       event-bus module — durable storage for every event-bus
 *       subscriber's {@code last_delivered_position} against the
 *       {@code subscriber_checkpoints} table (M2.6).</li>
 * </ul>
 */
module com.homesynapse.persistence {
    requires transitive com.homesynapse.platform;
    requires com.homesynapse.state;
    requires com.homesynapse.event;
    // M2.6: SqliteCheckpointStore implements
    // com.homesynapse.event.bus.CheckpointStore — the subscriber checkpoint
    // contract owned by the event-bus module (distinct from
    // com.homesynapse.state.ViewCheckpointStore which covers view state).
    requires com.homesynapse.event.bus;

    requires java.sql;
    requires org.slf4j;

    // M2.4: Jackson serialization infrastructure for DomainEvent payload
    // encode/decode in the SQLite event store BLOB column (DECIDE-M2-04).
    // jackson-databind transitively requires jackson-core and jackson-annotations.
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;
    requires com.fasterxml.jackson.module.blackbird;

    exports com.homesynapse.persistence;
}
