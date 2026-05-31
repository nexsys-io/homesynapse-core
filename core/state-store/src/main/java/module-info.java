/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * State Store — materialized entity state view, query service, and checkpoint contracts.
 */
module com.homesynapse.state {
    requires transitive com.homesynapse.platform;
    requires transitive com.homesynapse.value;
    requires transitive com.homesynapse.device;
    requires transitive com.homesynapse.event;
    requires transitive com.homesynapse.event.bus;

    requires org.slf4j;

    exports com.homesynapse.state;
}
