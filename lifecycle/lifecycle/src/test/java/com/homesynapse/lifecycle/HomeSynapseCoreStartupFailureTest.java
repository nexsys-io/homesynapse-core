/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.homesynapse.automation.CompanionAutomationIdentityStore;
import com.homesynapse.config.ConfigurationLoadException;
import com.homesynapse.integration.DataPath;
import com.homesynapse.integration.HealthParameters;
import com.homesynapse.integration.IntegrationAdapter;
import com.homesynapse.integration.IntegrationContext;
import com.homesynapse.integration.IntegrationDescriptor;
import com.homesynapse.integration.IntegrationFactory;
import com.homesynapse.integration.IoType;
import com.homesynapse.integration.PermanentIntegrationException;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

/**
 * FAILCHAN Part B (EXITCODE (a)) — C12-04 at the composition root: a fatal startup
 * failure leaves a {@link StartupFailureReport} (the phase it occurred in, the
 * fatal-set subsystem being initialized, the operator recommendation) on
 * {@link SystemLifecycleManager#lastStartupFailure()} and emits ONE structured
 * {@code lifecycle.startup_failed} line before the teardown — the half of C12-04
 * the lifecycle owns. The process exit code belongs to the app
 * ({@code ExitCodesTest}); the {@code subsystem()} of the report is the seam
 * between them.
 *
 * <p>The throw type of {@code start()} is UNCHANGED by design (the report rides
 * BESIDE the throw, never wraps it): T4 pins the HEAD type so the existing
 * schema-admission pin ({@code HomeSynapseCoreSchemaAdmissionTest} T9b) and this
 * one agree.</p>
 *
 * <p>Time is injected via {@code Clock.fixed} (non-app module test code; the
 * {@link LifecycleWiringTest} harness). Nothing here reads a wall clock.</p>
 */
@DisplayName("HomeSynapseCore -- the C12-04 startup-failure report (FAILCHAN)")
final class HomeSynapseCoreStartupFailureTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC);
    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));

    private static final String LIFECYCLE_LOGGER = HomeSynapseCore.class.getName();
    private static final String STARTUP_FAILED_PREFIX = "lifecycle.startup_failed: ";

    /** AUTO-ID-1: the engine-managed identity companion beside {@code homesynapse.yaml}. */
    private static final String COMPANION_FILE = "automations.ids.yaml";
    private static final String IDENTITY_STORE_LOGGER =
            CompanionAutomationIdentityStore.class.getName();
    private static final String IDENTITY_WRITE_FAILED_PREFIX =
            "automation.identity_write_failed: ";

    /** The C12-04 recommendations — the contract text, pinned verbatim. */
    private static final String CONFIGURATION_RECOMMENDATION =
            "check the configuration file (homesynapse.yaml + integrations/) — a schema or "
                    + "syntax error is deterministic; the unit does not restart on it";
    private static final String PERSISTENCE_RECOMMENDATION =
            "verify the event store file and disk; run the integrity check; restore from the "
                    + "pre-upgrade snapshot if corrupt";
    /** IR-44: the automation arm — the companion file and the operator's act. */
    private static final String AUTOMATION_RECOMMENDATION =
            "restore automations.ids.yaml (the engine-managed automation identity companion "
                    + "beside homesynapse.yaml in the config directory) from the newest "
                    + "~/hs-backup/<stamp>/config/ copy, or delete it so the next boot mints "
                    + "fresh ids and logs automation.identity_loaded first_boot=true (past runs "
                    + "then no longer link to their automations); never hand-edit it — the "
                    + "exception names the path";

    private HomeSynapseCore core;
    private ListAppender<ILoggingEvent> lifecycleLog;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    HomeSynapseCoreStartupFailureTest() {
    }

    @BeforeEach
    void attachLogCapture() {
        lifecycleLog = new ListAppender<>();
        lifecycleLog.start();
        logger(LIFECYCLE_LOGGER).addAppender(lifecycleLog);
    }

    @AfterEach
    void tearDown() {
        if (core != null) {
            core.stop();
        }
        logger(LIFECYCLE_LOGGER).detachAppender(lifecycleLog);
        lifecycleLog.stop();
    }

    // ════════════════════════════════════════════════════════════════════════
    // T4 — Phase 1: a malformed root document reports (FOUNDATION, configuration)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T4: a syntactically invalid homesynapse.yaml fails start() with the HEAD "
            + "throw type UNCHANGED, reports (FOUNDATION, configuration, the config "
            + "recommendation), and logs exactly one lifecycle.startup_failed line")
    void malformedConfigReportsConfiguration(@TempDir Path tempDir) throws Exception {
        writeRoot(tempDir, "automation: [\n  unterminated: flow: sequence\n");
        core = newCore(tempDir);

        Throwable fatal = catchThrowable(core::start);

        assertThat(fatal)
                .as("the throw type sits beside the report, never wraps it")
                .isInstanceOf(ConfigurationLoadException.class);
        assertThat(core.currentPhase()).isEqualTo(LifecyclePhase.STOPPED);
        assertThat(core.lastStartupFailure()).contains(new StartupFailureReport(
                LifecyclePhase.FOUNDATION, "configuration", CONFIGURATION_RECOMMENDATION));
        List<String> lines = startupFailedLines();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).startsWith(
                "lifecycle.startup_failed: phase=FOUNDATION subsystem=configuration "
                        + "recommendation=\"" + CONFIGURATION_RECOMMENDATION + "\"");
        assertThat(errorEventsWithThrowable())
                .as("the C12-04 line carries the exception (stack trace) as its throwable")
                .isEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════════
    // T5 — Phase 2: a persistence-open failure reports (DATA_INFRASTRUCTURE, persistence)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T5: an event-store path that is an existing DIRECTORY fails inside the "
            + "persistence init (after setPhase(DATA_INFRASTRUCTURE), before the bus) and "
            + "reports (DATA_INFRASTRUCTURE, persistence, the persistence recommendation)")
    void persistenceFailureReportsPersistence(@TempDir Path tempDir) throws Exception {
        // The fixture fails at the SQLite open, never in Phase 0 (the parent exists) and
        // never in Phase 1 (an absent root document is INV-CE-02 lawful).
        Files.createDirectories(tempDir.resolve("homesynapse-events.db"));
        core = newCore(tempDir);

        Throwable fatal = catchThrowable(core::start);

        assertThat(fatal).isNotNull();
        assertThat(core.currentPhase()).isEqualTo(LifecyclePhase.STOPPED);
        assertThat(core.lastStartupFailure()).contains(new StartupFailureReport(
                LifecyclePhase.DATA_INFRASTRUCTURE, "persistence", PERSISTENCE_RECOMMENDATION));
        assertThat(startupFailedLines()).singleElement().asString().startsWith(
                "lifecycle.startup_failed: phase=DATA_INFRASTRUCTURE subsystem=persistence ");
    }

    // ════════════════════════════════════════════════════════════════════════
    // T6 — the empty arms of the contract: before start, after success, the pre-bootstrap ISE
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T6: lastStartupFailure() is empty before start(), after a successful "
            + "start(), and after the pre-bootstrap IllegalStateException of a second start()")
    void noReportBeforeStartOrAfterSuccess(@TempDir Path tempDir) throws Exception {
        core = newCore(tempDir);
        SystemLifecycleManager manager = core;
        assertThat(manager.lastStartupFailure()).isEmpty();

        manager.start();

        assertThat(manager.currentPhase()).isEqualTo(LifecyclePhase.RUNNING);
        assertThat(manager.lastStartupFailure()).isEmpty();
        assertThat(startupFailedLines()).isEmpty();

        assertThatThrownBy(manager::start).isInstanceOf(IllegalStateException.class);
        assertThat(manager.lastStartupFailure())
                .as("the pre-bootstrap ISE is not a bootstrap failure")
                .isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // T7 — INV-RF-01: an integration factory failure is NOT fatal and leaves no report
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T7: a factory whose create() throws leaves start() RUNNING with an EMPTY "
            + "report (Phase 6 is non-fatal, INV-RF-01) — green-by-construction, disclosed")
    void integrationFactoryFailureIsNotFatal(@TempDir Path tempDir) throws Exception {
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                tempDir.resolve("config"),
                HomeSynapseConfig.testing(),
                FIXED_CLOCK,
                TEST_HOME_ID,
                null,
                List.of(new ThrowingFactory()));

        core.start();

        assertThat(core.currentPhase()).isEqualTo(LifecyclePhase.RUNNING);
        assertThat(core.lastStartupFailure()).isEmpty();
        assertThat(core.subsystemStates()).containsKey("integration");
        assertThat(startupFailedLines()).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // T8/T9 — AUTO-ID-1: the identity companion at the root. One that exists and cannot
    // be read is FATAL (fail closed — ids are never re-minted over it); one that cannot
    // be WRITTEN is not (R3 — ERROR-logged, the boot continues)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T8: a malformed automations.ids.yaml fails start() with the companion's "
            + "IllegalStateException UNWRAPPED and naming the path, reports (CORE_DOMAIN, "
            + "automation), logs one lifecycle.startup_failed line, and leaves the file's "
            + "bytes untouched")
    void malformedIdentityCompanionReportsAutomation(@TempDir Path tempDir) throws Exception {
        String malformed = "automations: [\n  unterminated\n";
        Path companion = writeCompanion(tempDir, malformed);
        core = newCore(tempDir);

        Throwable fatal = catchThrowable(core::start);

        assertThat(fatal)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(companion.toString());
        assertThat(core.currentPhase()).isEqualTo(LifecyclePhase.STOPPED);
        assertThat(core.lastStartupFailure()).hasValueSatisfying(report -> {
            assertThat(report.phase()).isEqualTo(LifecyclePhase.CORE_DOMAIN);
            assertThat(report.subsystem()).isEqualTo("automation");
        });
        assertThat(startupFailedLines()).singleElement().asString().startsWith(
                "lifecycle.startup_failed: phase=CORE_DOMAIN subsystem=automation ");
        assertThat(Files.readString(companion)).isEqualTo(malformed);
    }

    @Test
    @DisplayName("T9: a companion that cannot be WRITTEN never fails the boot — start() "
            + "reaches RUNNING with an EMPTY report, the automation is loaded, and ONE "
            + "automation.identity_write_failed ERROR names the path")
    void unwritableIdentityCompanionIsNotFatal(@TempDir Path tempDir) throws Exception {
        writeRoot(tempDir, BusPositionCensusIT.heroMotionConfigYaml());
        Path companion = tempDir.resolve("config").resolve(COMPANION_FILE);
        // A non-empty DIRECTORY squatting on the temp sibling: the write fails before the
        // atomic move on every platform, without POSIX permission bits.
        Path squatter = Files.createDirectories(
                companion.resolveSibling(COMPANION_FILE + ".tmp"));
        Files.writeString(squatter.resolve("occupant"), "x");
        ListAppender<ILoggingEvent> storeLog = new ListAppender<>();
        storeLog.start();
        logger(IDENTITY_STORE_LOGGER).addAppender(storeLog);
        try {
            core = newCore(tempDir);

            core.start();

            assertThat(core.currentPhase()).isEqualTo(LifecyclePhase.RUNNING);
            assertThat(core.lastStartupFailure()).isEmpty();
            assertThat(startupFailedLines()).isEmpty();
            assertThat(core.automationRegistry().getBySlug("hero-motion")).isPresent();
            assertThat(storeLog.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith(IDENTITY_WRITE_FAILED_PREFIX)))
                    .singleElement().asString()
                    .contains("path=" + companion);
            assertThat(companion).doesNotExist();
        } finally {
            logger(IDENTITY_STORE_LOGGER).detachAppender(storeLog);
            storeLog.stop();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // T10 — IR-44: the FATAL companion's report carries the automation arm, not the default
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T10 (IR-44 T4): a malformed automations.ids.yaml reports (CORE_DOMAIN, "
            + "automation) with a recommendation that names the companion file and the "
            + "operator's act — restore from ~/hs-backup or delete for a fresh mint, never "
            + "hand-edit — the subsystem seam to the exit code unchanged")
    void automationCompanionFatal_recommendsTheFileAndTheAct(@TempDir Path tempDir)
            throws Exception {
        Path companion = writeCompanion(tempDir, "automations: [\n  unterminated\n");
        core = newCore(tempDir);

        Throwable fatal = catchThrowable(core::start);

        assertThat(fatal).isInstanceOf(IllegalStateException.class);
        // The report, not the log line (§9): the exit code keys on subsystem() alone
        // (ExitCodes.forStartupFailure — `automation` stays the unmapped 99), so the
        // subsystem is asserted verbatim beside the new recommendation.
        assertThat(core.lastStartupFailure()).contains(new StartupFailureReport(
                LifecyclePhase.CORE_DOMAIN, "automation", AUTOMATION_RECOMMENDATION));
        assertThat(core.lastStartupFailure()).hasValueSatisfying(report -> assertThat(
                report.recommendation())
                .as("the file and the act, in the operator's hands")
                .contains(COMPANION_FILE)
                .contains("~/hs-backup/")
                .contains("delete it")
                .contains("never hand-edit")
                .doesNotContain("inspect the log and the JFR recording"));
        assertThat(startupFailedLines()).singleElement().asString().startsWith(
                "lifecycle.startup_failed: phase=CORE_DOMAIN subsystem=automation "
                        + "recommendation=\"" + AUTOMATION_RECOMMENDATION + "\"");
        assertThat(Files.readString(companion)).isEqualTo("automations: [\n  unterminated\n");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Harness
    // ════════════════════════════════════════════════════════════════════════

    /** A factory whose {@code create()} throws — the INV-RF-01 fixture (T7). */
    private static final class ThrowingFactory implements IntegrationFactory {

        private ThrowingFactory() {
        }

        @Override
        public IntegrationDescriptor descriptor() {
            return new IntegrationDescriptor(
                    "throwing", "Throwing Fake Integration", IoType.NETWORK, Set.of(),
                    Set.of(DataPath.DOMAIN), HealthParameters.defaults(), Set.of(), 1);
        }

        @Override
        public IntegrationAdapter create(IntegrationContext context)
                throws PermanentIntegrationException {
            throw new PermanentIntegrationException(
                    "Integration throwing is configured to fail create()");
        }
    }

    private static HomeSynapseCore newCore(Path tempDir) {
        return new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                tempDir.resolve("config"),
                HomeSynapseConfig.testing(),
                FIXED_CLOCK,
                TEST_HOME_ID);
    }

    private static void writeRoot(Path tempDir, String yaml) throws Exception {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("homesynapse.yaml"), yaml);
    }

    /** Writes {@code automations.ids.yaml} into the config dir; returns its path. */
    private static Path writeCompanion(Path tempDir, String yaml) throws Exception {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        return Files.writeString(configDir.resolve(COMPANION_FILE), yaml);
    }

    /** The C12-04 lines of the composition root, in emission order. */
    private List<String> startupFailedLines() {
        return lifecycleLog.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(STARTUP_FAILED_PREFIX))
                .toList();
    }

    /** ERROR events that carry a throwable (the stack-trace half of C12-04). */
    private long errorEventsWithThrowable() {
        return lifecycleLog.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .filter(event -> event.getFormattedMessage().startsWith(STARTUP_FAILED_PREFIX))
                .filter(event -> event.getThrowableProxy() != null)
                .count();
    }

    private static ch.qos.logback.classic.Logger logger(String name) {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name);
    }
}
