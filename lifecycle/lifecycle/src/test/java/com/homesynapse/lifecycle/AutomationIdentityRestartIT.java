/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static com.homesynapse.lifecycle.BusPositionCensusIT.heroMotionConfigYaml;
import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.automation.ExplanationService;
import com.homesynapse.automation.NonFiringExplanation;
import com.homesynapse.automation.NonFiringExplanation.NonFiringVerdict;
import com.homesynapse.platform.identity.AutomationId;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * AUTO-ID-1 (T1) — an automation's identity is DURABLE across a restart (Doc 07
 * §4.1; AMD-93 §2.3). MEASURE-2b's F-1 (IR-39), reproduced on the shared real-core
 * fixture: with the in-memory identity store at the composition root every boot
 * minted a NEW {@link AutomationId} per slug, so after a restart the non-firing
 * read looked the log up under the new id, found no runs, and answered
 * {@code NEVER_TRIGGERED} of an automation with a confirmed run on record.
 *
 * <p>The assertions are on VALUES the wire carries — the {@link AutomationId}
 * resolved by slug, the {@link NonFiringVerdict}, the run id the explanation names —
 * and on the engine-managed companion file ({@code automations.ids.yaml}) beside
 * {@code homesynapse.yaml}; never on log text. The three post-restart facts are
 * asserted softly so ONE red run reports the id, the verdict and the file together.
 *
 * <p>The configuration is {@link BusPositionCensusIT#heroMotionConfigYaml()} — the
 * hero motion → turn_on rule, the helper {@link MeasureReadPathIT} and
 * {@link BusSoakIT} already share. No wall clock is read here: {@code restart} takes
 * its stopwatch from the caller and this test reads no duration, so it passes a
 * constant.
 */
@DisplayName("AutomationIdentityRestartIT — AUTO-ID-1: the automation id by slug survives a "
        + "restart, and the non-firing read keeps the verdict the log holds")
final class AutomationIdentityRestartIT {

    private static final String HERO_SLUG = "hero-motion";
    private static final String COMPANION_FILE = "automations.ids.yaml";

    private RealCoreFixture fixture;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    AutomationIdentityRestartIT() {
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    @DisplayName("T1: one confirmed hero run, then restart() — the id by slug is EQUAL, "
            + "explainNonFiring still says FIRED_CONFIRMED of the same run, and the companion "
            + "file carries the slug and that id")
    void heroIdentityAndVerdict_surviveRestart(@TempDir Path tempDir) throws Exception {
        fixture = RealCoreFixture.boot(tempDir, heroMotionConfigYaml());
        AutomationId idBefore = heroId();
        fixture.pulseMotion();
        fixture.settle();

        NonFiringExplanation before = explainNonFiring(idBefore);
        assertThat(before.verdict())
                .as("the precondition: one confirmed hero run is on record before the restart")
                .isEqualTo(NonFiringVerdict.FIRED_CONFIRMED);
        assertThat(before.lastRelevantRunId()).isNotNull();

        fixture.restart(() -> 0L);

        // The id is re-resolved BY SLUG on the restarted core — the dashboard's path,
        // and MeasureReadPathIT's hero_id_stable / q2_verdict read.
        AutomationId idAfter = heroId();
        NonFiringExplanation after = explainNonFiring(idAfter);
        Path companion = tempDir.resolve("config").resolve(COMPANION_FILE);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(idAfter)
                .as("the automation id by slug after restart()")
                .isEqualTo(idBefore);
        softly.assertThat(after.verdict())
                .as("explainNonFiring's verdict after restart()")
                .isEqualTo(NonFiringVerdict.FIRED_CONFIRMED);
        softly.assertThat(after.lastRelevantRunId())
                .as("the run the explanation names after restart()")
                .isEqualTo(before.lastRelevantRunId());
        softly.assertThat(companion)
                .as("the engine-managed companion beside homesynapse.yaml")
                .isRegularFile();
        if (Files.isRegularFile(companion)) {
            softly.assertThat(Files.readString(companion))
                    .as("the companion names the slug and the id it preserved")
                    .contains(HERO_SLUG)
                    .contains(idBefore.toString());
        }
        softly.assertAll();
    }

    private AutomationId heroId() {
        return fixture.core().automationRegistry().getBySlug(HERO_SLUG)
                .orElseThrow(() -> new AssertionError("'" + HERO_SLUG + "' not loaded"))
                .automationId();
    }

    /** The composition root's own construction (HomeSynapseCore's read-API wiring). */
    private NonFiringExplanation explainNonFiring(AutomationId automationId) {
        return ExplanationService
                .over(fixture.core().eventStore(), fixture.core().automationRegistry())
                .explainNonFiring(automationId, 0L)
                .orElseThrow(() -> new AssertionError(
                        "the registry does not know automation " + automationId));
    }
}
