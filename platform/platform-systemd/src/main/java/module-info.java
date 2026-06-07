/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Deployment-tier implementations of the {@code com.homesynapse.platform}
 * abstraction interfaces.
 *
 * <p>Provides {@code LinuxSystemPaths} / {@code LocalPaths}
 * ({@link com.homesynapse.platform.PlatformPaths}) and
 * {@code SystemdHealthReporter} / {@code NoOpHealthReporter}
 * ({@link com.homesynapse.platform.HealthReporter}). The systemd reporter targets
 * the {@code $NOTIFY_SOCKET} {@code sd_notify} datagram socket (LTD-13); the no-op
 * variants serve non-systemd and development tiers.</p>
 *
 * <p>Tier detection and implementation selection are deliberately NOT performed in
 * this module — they belong to the composition root (lifecycle / M13), per Doc 12
 * §7.</p>
 */
module com.homesynapse.platform.systemd {
    // `requires transitive` (not plain `requires`): the four public impl classes in the
    // exported package expose PlatformPaths/HealthReporter (from com.homesynapse.platform)
    // as supertypes, so platform-api is part of THIS module's public API. Under
    // `-Xlint:exports -Werror` a plain `requires` is a fatal warning. House pattern —
    // cf. core/persistence module-info; Gradle uses `api(...)` in lockstep.
    requires transitive com.homesynapse.platform;
    requires org.slf4j;

    exports com.homesynapse.platform.systemd;
}
