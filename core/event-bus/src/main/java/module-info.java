/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Event bus — subscription, notification, checkpoint, and backpressure management.
 */
module com.homesynapse.event.bus {
    requires transitive com.homesynapse.event;

    // M3.3 (AMD-43): JFR-native bus metrics commit jdk.jfr.Event subclasses.
    // jdk.jfr is a JDK platform module but is NOT in java.base — JPMS requires
    // an explicit `requires` directive even though it ships with the JDK.
    requires jdk.jfr;

    exports com.homesynapse.event.bus;
}
