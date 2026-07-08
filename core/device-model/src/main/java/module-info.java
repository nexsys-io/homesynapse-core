/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Device model — Device, Entity, Capability, registries, and discovery.
 */
module com.homesynapse.device {
    requires transitive com.homesynapse.value;
    // M9.5-DUR (AMD-99): PROMOTED to transitive — RegistryProjection and
    // RegistryEventMapper name DeviceRegisteredEvent/EntityRegisteredEvent/
    // DeviceRemovedEvent on PUBLIC methods of an EXPORTED package, so a plain
    // requires trips -Xlint:exports (-Werror). Paired with the api(...) scope
    // build.gradle.kts already carries (the M9.1/M9.4b lockstep rule).
    requires transitive com.homesynapse.event;
    requires transitive com.homesynapse.platform;

    exports com.homesynapse.device;
}
