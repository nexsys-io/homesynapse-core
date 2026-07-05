/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The R4 registry-unification pin (M9.4b §1, pm-handoff v18 beat 4): Main's
 * factory-injected {@code DeviceRegistry} and the core's internal one
 * "MUST become the same instance (or a ruled read surface) BEFORE the real
 * transport binds; dispatch resolution reads the core's."
 *
 * <p>The 8-arg constructor is the ruled realization — constructor injection:
 * the composition root owns registry identity, so the integration factory
 * path and the core's dispatch-resolution path index the SAME truth. The
 * 7-arg form stays behavior-identical for every existing caller by
 * delegating with a self-constructed registry.</p>
 *
 * <p>Uses {@link Clock#systemUTC()} — the established idiom of this package's
 * lifecycle tests (see {@link HomeSynapseCoreTest}): the bus's per-subscriber
 * VTs need real time to park and unpark.</p>
 */
@DisplayName("HomeSynapseCore -- R4 device-registry injection (M9.4b §1)")
final class HomeSynapseCoreRegistryInjectionTest {

    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));

    private HomeSynapseCore core;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    HomeSynapseCoreRegistryInjectionTest() {
    }

    @AfterEach
    void tearDown() {
        if (core != null) {
            core.stop();
        }
    }

    @Test
    @DisplayName("8-arg ctor: start() indexes the caller-held registry instance (one truth)")
    void eightArgCtor_startIndexesTheInjectedInstance(@TempDir Path tempDir) throws Exception {
        InMemoryDeviceRegistry callerHeld = new InMemoryDeviceRegistry();
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"), tempDir.resolve("config"),
                HomeSynapseConfig.testing(), Clock.systemUTC(), TEST_HOME_ID,
                null, List.of(), callerHeld);

        core.start();

        // The R4 unification pin: dispatch resolution reads the core's registry,
        // and the core's registry IS the caller's (the factory-supplier's) instance.
        assertThat(core.deviceRegistry()).isSameAs(callerHeld);
    }

    @Test
    @DisplayName("7-arg ctor: start() self-constructs a registry (existing callers unchanged)")
    void sevenArgCtor_selfConstructsARegistry(@TempDir Path tempDir) throws Exception {
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"), tempDir.resolve("config"),
                HomeSynapseConfig.testing(), Clock.systemUTC(), TEST_HOME_ID,
                null, List.of());

        core.start();

        assertThat(core.deviceRegistry())
                .isNotNull()
                .isInstanceOf(InMemoryDeviceRegistry.class);
    }

    @Test
    @DisplayName("8-arg ctor rejects a null registry")
    void eightArgCtor_rejectsNullRegistry(@TempDir Path tempDir) {
        assertThatThrownBy(() -> new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"), tempDir.resolve("config"),
                HomeSynapseConfig.testing(), Clock.systemUTC(), TEST_HOME_ID,
                null, List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("deviceRegistry");
    }
}
