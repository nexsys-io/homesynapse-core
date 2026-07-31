/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberSnapshot;
import com.homesynapse.integration.runtime.IntegrationIds;
import com.homesynapse.integration.zigbee.ZigbeeHardwareFreeRig;
import com.homesynapse.integration.zigbee.ZigbeeIntegrationFactory;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.state.Availability;
import com.homesynapse.test.TestClock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * WU-AVAIL-SEED T-7 — the DP-2 floor at the composition root: post-boot, a
 * DEAD seeded mains device's SERVED availability reaches UNAVAILABLE within
 * one mains evaluation window, through the normal publish → state projection →
 * read path, with no manual intervention. The read of record is the production
 * {@link com.homesynapse.state.StateQueryService} — the same materialized
 * instance the REST entity endpoints consume ({@code HomeSynapseCore}
 * constructs one and wires it into both).
 *
 * <p>The boundary pair rides inside the same scenario: immediately post-restart
 * (the pre-convergence window) the served availability still reads AVAILABLE —
 * the replayed log's last truth, the accepted brief-false-ALIVE direction —
 * and only the honest timeout verdict moves it. Evidence-free AVAILABLE served
 * beyond the window is the defect this leg guards.
 */
@DisplayName("AvailabilityBootTruthIT — a dead seeded mains device is SERVED "
        + "UNAVAILABLE within one window of boot (WU-AVAIL-SEED T-7)")
final class AvailabilityBootTruthIT {

    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAD"));

    private TestClock clock;
    private ZigbeeHardwareFreeRig rig;
    private HomeSynapseCore core;
    private EntityId hueEntity;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    AvailabilityBootTruthIT() {
    }

    @AfterEach
    void tearDown() {
        if (core != null) {
            core.stop();
        }
    }

    @Test
    @DisplayName("restart with a persisted sidecar, device now dead: served "
            + "AVAILABLE inside the pre-convergence window, then UNAVAILABLE by "
            + "the ping-timeout verdict — never manual, never a fabricated event")
    void deadSeededMainsDevice_servedUnavailableWithinOneWindow(
            @TempDir Path tempDir) throws Exception {
        boot(tempDir);

        // Boot 1: the Hue (mains, PowerSource 0x01) announces — the announce is
        // the tracker's evidence — and is adopted; the adoption-time view seed
        // publishes the per-entity online edge, the projection applies it, and
        // the production read surface serves AVAILABLE.
        rig.announce(ZigbeeHardwareFreeRig.HUE_IEEE);
        rig.deliverAndCycle();
        hueEntity = rig.adopt(ZigbeeHardwareFreeRig.HUE_IEEE)
                .get(ZigbeeHardwareFreeRig.HUE_ENDPOINT);
        awaitServed(Availability.AVAILABLE,
                "the adopted Hue serving AVAILABLE pre-restart");

        // The planned restart: close() flushes the sidecar (availability +
        // evidence recency); initialize() seeds the tracker from it and the
        // DP-6 rehydration re-links the registry-carried entities.
        core.integrationSupervisor()
                .restartIntegration(IntegrationIds.deriveStable(
                        ZigbeeIntegrationFactory.INTEGRATION_TYPE))
                .get(30, TimeUnit.SECONDS);
        awaitTrue(rig::sessionStarted, "the restarted EZSP session");

        // The device is now DEAD: the NCP still accepts unicasts to it, the
        // device never answers anything again.
        rig.silence(ZigbeeHardwareFreeRig.HUE_IEEE);

        // The pre-convergence boundary: seeding published NOTHING (T-3), so the
        // served view still carries the replayed log's last truth — AVAILABLE.
        // This brief false-ALIVE inside the window is the accepted direction.
        assertThat(served())
                .as("inside the window the view is the log's last truth")
                .isEqualTo(Availability.AVAILABLE);

        // One mains evaluation window elapses with total silence. The cycle
        // evaluates the seeded device, pings it once, gets nothing, and the
        // PING_TIMEOUT verdict rides the normal publish → projection → read
        // path for the relinked entity.
        rig.clock().advance(Duration.ofMinutes(11));
        rig.deliverAndCycle();

        awaitServed(Availability.UNAVAILABLE,
                "the DP-2 floor: served UNAVAILABLE within one window of boot");
    }

    // ── harness (the RestartHonestyIT boot shape, config-less) ──────────────

    private void boot(Path tempDir) throws Exception {
        clock = TestClock.createDefault();
        rig = new ZigbeeHardwareFreeRig(clock, () -> core.deviceRegistry(),
                () -> core.registryProjection(),
                tempDir.resolve("zigbee"));
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                tempDir.resolve("config"),
                HomeSynapseConfig.testing(),
                clock,
                TEST_HOME_ID,
                null,
                List.of(rig.factory()));
        core.start();
        awaitTrue(() -> subscriberMode("integration_supervisor")
                == SubscriberMode.LIVE, "the integration supervisor LIVE");
        awaitTrue(rig::sessionStarted, "the EZSP session over the scripted NCP");
    }

    private SubscriberMode subscriberMode(String subscriberId) {
        return core.eventBus().subscribers().stream()
                .filter(snapshot -> subscriberId.equals(snapshot.subscriberId()))
                .map(SubscriberSnapshot::mode)
                .findFirst()
                .orElse(SubscriberMode.COLD);
    }

    /** The production read: the materialized query service the REST layer uses. */
    private Availability served() {
        return core.stateQueryService().getState(hueEntity)
                .orElseThrow(() -> new AssertionError(
                        "the adopted entity has no served state"))
                .availability();
    }

    private void awaitServed(Availability expected, String what) {
        awaitTrue(() -> core.stateQueryService().getState(hueEntity)
                .map(state -> state.availability() == expected)
                .orElse(false), what);
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        for (int poll = 0; poll < 500; poll++) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("timed out awaiting " + what);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting boot truth", ex);
        }
    }
}
