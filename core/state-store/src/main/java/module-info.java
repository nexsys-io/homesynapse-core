/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * State Store — materialized entity state view, query service, and checkpoint contracts.
 */
module com.homesynapse.state {
    requires transitive com.homesynapse.platform;
    requires transitive com.homesynapse.device;
    requires com.homesynapse.event;

    exports com.homesynapse.state;
}
