/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Event bus — subscription, notification, checkpoint, and backpressure management.
 */
module com.homesynapse.event.bus {
    requires transitive com.homesynapse.event;

    exports com.homesynapse.event.bus;
}
