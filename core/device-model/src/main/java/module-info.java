/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Device model — Device, Entity, Capability, registries, and discovery.
 */
module com.homesynapse.device {
    requires com.homesynapse.event;
    requires transitive com.homesynapse.platform;

    exports com.homesynapse.device;
}
