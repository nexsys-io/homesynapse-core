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
 * <p>The Persistence Layer implements
 * {@link com.homesynapse.state.ViewCheckpointStore} from the state-store
 * module — it provides the durable storage behind the State Store's
 * checkpoint mechanism.</p>
 */
module com.homesynapse.persistence {
    requires transitive com.homesynapse.platform;
    requires com.homesynapse.state;
    requires com.homesynapse.event;

    exports com.homesynapse.persistence;
}
