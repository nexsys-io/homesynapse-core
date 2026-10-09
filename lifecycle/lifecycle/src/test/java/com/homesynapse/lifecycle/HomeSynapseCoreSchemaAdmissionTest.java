/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.homesynapse.config.ConfigurationLoadException;
import com.homesynapse.integration.zigbee.ZigbeeIntegrationFactory;
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
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PKG-SEC-2 — the zigbee schema admission (R-4 C-1: {@code Configuration issue
 * [WARNING] at 'integrations.zigbee': property 'zigbee' is not defined in the
 * schema …} on EVERY start). Row 13 RULED (a′): integration schema fragments are
 * static JSON text, so the composition root supplies them BEFORE {@code start()}
 * and Phase 1 drains them into the schema registry after the core schemas and
 * before {@code ConfigurationService.load()} — the {@code integrations.{type}}
 * sections validate against the real fragments at Phase-1 validation (Doc 06
 * §3.2: composition after integration registration and before validation; C7:
 * the composed schema written at startup carries every registered fragment).
 *
 * <p>Instruments: the config module's per-issue WARN line ({@code Configuration
 * issue [SEVERITY] at 'path': …}, captured on the {@code com.homesynapse.config}
 * logger — the same line R-4b's rig check greps) and the composition root's
 * {@code lifecycle.integration_schema_registered} INFO (captured on the
 * {@code HomeSynapseCore} logger). Config's boot events ride the deferred
 * publisher and are dropped before Phase 2, so the log line is the only Phase-1
 * validation evidence a test can read.</p>
 *
 * <p>CONFIG-ERROR-1 (AMD-102, 2026-10-09): an unknown key is an ERROR and any ERROR
 * fails the boot naming every path — T2/T3 pin the failure (the per-issue line is
 * emitted BEFORE the gate, so {@link #configurationIssues()} still reads it on a
 * failed boot, while the exception message names the paths); T1b is THE PI FIXTURE
 * (L-1): the Pi's key tree loads with {@code issues=0} — the deploy card's
 * boot-line twin (AMD-102 R-E).</p>
 *
 * <p>Time is injected via {@code Clock.fixed} (§4c — non-app module test code;
 * the {@link LifecycleWiringTest} harness). The composition root's schema queue
 * + drain use no time.</p>
 */
@DisplayName("HomeSynapseCore -- integration schema admission at Phase-1 validation (PKG-SEC-2)")
final class HomeSynapseCoreSchemaAdmissionTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC);
    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));

    private static final String ZIGBEE = ZigbeeIntegrationFactory.INTEGRATION_TYPE;
    private static final String ZIGBEE_FRAGMENT = ZigbeeIntegrationFactory.configSchemaJson();
    /** A second, minimal fragment — the late-registrant / multi-fragment arms. */
    private static final String MQTT_FRAGMENT = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "broker": { "type": "string", "default": "tcp://localhost:1883" }
              }
            }
            """;

    private static final String CONFIG_LOGGER = "com.homesynapse.config";
    private static final String LIFECYCLE_LOGGER = HomeSynapseCore.class.getName();
    private static final String CONFIG_ISSUE_PREFIX = "Configuration issue [";
    private static final String REGISTERED_PREFIX = "lifecycle.integration_schema_registered: ";

    /** The composed root's {@code integrations} node with the zigbee fragment as its first property. */
    private static final Pattern INTEGRATIONS_CARRY_ZIGBEE = Pattern.compile(
            "\"integrations\"\\s*:\\s*\\{\\s*\"type\"\\s*:\\s*\"object\"\\s*,\\s*"
                    + "\"properties\"\\s*:\\s*\\{\\s*\"zigbee\"\\s*:\\s*\\{");
    /** The HEAD exhibit: an EMPTY integrations.properties node (the dev-run config.schema.json). */
    private static final Pattern INTEGRATIONS_EMPTY = Pattern.compile(
            "\"integrations\"\\s*:\\s*\\{\\s*\"type\"\\s*:\\s*\"object\"\\s*,\\s*"
                    + "\"properties\"\\s*:\\s*\\{\\s*\\}");

    private HomeSynapseCore core;
    private ListAppender<ILoggingEvent> configLog;
    private ListAppender<ILoggingEvent> lifecycleLog;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    HomeSynapseCoreSchemaAdmissionTest() {
    }

    @BeforeEach
    void attachLogCapture() {
        configLog = attach(CONFIG_LOGGER);
        lifecycleLog = attach(LIFECYCLE_LOGGER);
    }

    @AfterEach
    void tearDown() {
        if (core != null) {
            core.stop();
        }
        detach(CONFIG_LOGGER, configLog);
        detach(LIFECYCLE_LOGGER, lifecycleLog);
    }

    // ════════════════════════════════════════════════════════════════════════
    // T1 — the R-4 C-1 close: a well-formed block boots with ZERO issues
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T1: a well-formed integrations.zigbee block + the fragment supplied pre-start "
            + "loads with ZERO configuration issues (R-4 C-1 closed) and the benign defaults merge")
    void wellFormedZigbeeBlock_preStartFragment_zeroIssues(@TempDir Path tempDir)
            throws Exception {
        writeRoot(tempDir, """
                integrations:
                  zigbee:
                    channel: 15
                """);
        core = newCore(tempDir);
        core.registerIntegrationSchema(ZIGBEE, ZIGBEE_FRAGMENT);

        core.start();

        assertThat(configurationIssues())
                .as("no 'Configuration issue' line at all — the R-4b rig check's grep count is 0")
                .isEmpty();
        assertThat(core.currentPhase()).isEqualTo(LifecyclePhase.RUNNING);
        Map<String, Object> zigbee = zigbeeSection();
        assertThat(zigbee)
                .containsEntry("channel", 15)
                // Doc 06 §3.1 stage 4: the fragment's declared defaults merge in for absent keys.
                .containsEntry("watchdog_interval_seconds", 30)
                .containsEntry("telemetry_threshold_seconds", 10);
    }

    @Test
    @DisplayName("T1b (L-1, THE PI FIXTURE): the measured deployment layout — homesynapse.yaml "
            + "with `zigbee: !include integrations/zigbee.yaml` (AMD-71) and the Pi's automation "
            + "block; zigbee.yaml with serial_port, channel, adopt_devices — loads with ZERO "
            + "issues (the deploy card's `Configuration loaded: … issues=0` twin, AMD-102 R-E)")
    void piKeyTree_includeLayout_zeroIssues(@TempDir Path tempDir) throws Exception {
        // The Pi's key tree read 2026-10-09 (_scratch/v101/pi_config_keys.txt +
        // pi_zigbee_yaml.txt), generic values: one automation whose actions alternate
        // a command (target, command, parameters) and a delay (duration).
        writeInclude(tempDir, "zigbee.yaml", """
                serial_port: /dev/ttyUSB0
                channel: 15
                adopt_devices:
                  - "0x00124B0012345678"
                """);
        writeRoot(tempDir, """
                integrations:
                  zigbee: !include integrations/zigbee.yaml
                automation:
                  automations:
                    - name: "pi fixture"
                      triggers:
                        - type: state_change
                          entity_ref: "01JAAAAAAAAAAAAAAAAAAAAAAB"
                          attribute: occupied
                          to: "true"
                      actions:
                        - type: command
                          target: {entity_ref: "01JAAAAAAAAAAAAAAAAAAAAAAC"}
                          command: turn_on
                          parameters: {}
                        - type: delay
                          duration: PT1S
                """);
        core = newCore(tempDir);
        core.registerIntegrationSchema(ZIGBEE, ZIGBEE_FRAGMENT);

        core.start();

        assertThat(configurationIssues())
                .as("issues=0 — every key the Pi writes is declared and valid")
                .isEmpty();
        assertThat(core.currentPhase()).isEqualTo(LifecyclePhase.RUNNING);
        assertThat(zigbeeSection())
                .containsEntry("serial_port", "/dev/ttyUSB0")
                .containsEntry("channel", 15)
                .containsEntry("adopt_devices", List.of("0x00124B0012345678"))
                .doesNotContainKey("permit_join_duration")
                .doesNotContainKey("availability");
        assertThat(core.automationRegistry().getBySlug("pi_fixture"))
                .as("the Pi-shaped automation loads (the deep validation accepts the shape)")
                .isPresent();
    }

    // ════════════════════════════════════════════════════════════════════════
    // T2 — root strictness kept; since AMD-102 an unknown key FAILS the boot (L-2)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T2 (L-2): an unknown integration type AND an unknown top-level key FAIL the "
            + "boot naming BOTH dotted paths (AMD-102: additionalProperties is ERROR at the root "
            + "and at integrations); the known zigbee block is clean")
    void unknownKeys_failTheBoot_namingBothPaths(@TempDir Path tempDir) throws Exception {
        writeRoot(tempDir, """
                bogus_top_level: 1
                integrations:
                  notatype:
                    anything: true
                  zigbee:
                    channel: 15
                """);
        core = newCore(tempDir);
        core.registerIntegrationSchema(ZIGBEE, ZIGBEE_FRAGMENT);

        Throwable fatal = catchThrowable(core::start);

        assertThat(fatal)
                .as("the boot refuses, naming every path (AMD-102 R-A/R-B)")
                .isInstanceOf(ConfigurationLoadException.class)
                .hasMessageContaining("integrations.notatype")
                .hasMessageContaining("bogus_top_level");
        assertThat(core.currentPhase()).isEqualTo(LifecyclePhase.STOPPED);
        List<String> issues = configurationIssues();
        assertThat(issues).as("exactly the two unknown-key ERRORs, logged before the gate")
                .hasSize(2);
        assertThat(issues).anySatisfy(line -> assertThat(line)
                .startsWith("Configuration issue [ERROR] at 'integrations.notatype'"));
        assertThat(issues).anySatisfy(line -> assertThat(line)
                .startsWith("Configuration issue [ERROR] at 'bogus_top_level'"));
        assertThat(issues).noneMatch(line -> line.contains("'integrations.zigbee"));
    }

    // ════════════════════════════════════════════════════════════════════════
    // T3 — a removed key FAILS the boot naming it (AMD-102 R-D; L-3)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T3 (L-3): integrations.zigbee.permit_join_duration: 999 — a key the fragment "
            + "no longer declares (REMOVED, AMD-102 R-D) — FAILS the boot naming "
            + "integrations.zigbee.permit_join_duration (the additionalProperties ERROR, not a "
            + "range error); nothing reverts, nothing starts")
    void permitJoinDuration_undeclared_failsTheBootNamingThePath(@TempDir Path tempDir)
            throws Exception {
        writeRoot(tempDir, """
                integrations:
                  zigbee:
                    permit_join_duration: 999
                """);
        core = newCore(tempDir);
        core.registerIntegrationSchema(ZIGBEE, ZIGBEE_FRAGMENT);

        Throwable fatal = catchThrowable(core::start);

        assertThat(fatal)
                .isInstanceOf(ConfigurationLoadException.class)
                .hasMessageContaining("integrations.zigbee.permit_join_duration");
        assertThat(core.currentPhase()).isEqualTo(LifecyclePhase.STOPPED);
        List<String> issues = configurationIssues();
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0))
                .startsWith("Configuration issue [ERROR] at "
                        + "'integrations.zigbee.permit_join_duration'")
                .contains("'permit_join_duration' is not defined in the schema");
    }

    @Test
    @DisplayName("T3b (L-3): an availability block (mains_timeout_minutes — IR-122, REMOVED) "
            + "FAILS the boot naming integrations.zigbee.availability")
    void availabilityBlock_undeclared_failsTheBootNamingThePath(@TempDir Path tempDir)
            throws Exception {
        writeRoot(tempDir, """
                integrations:
                  zigbee:
                    availability:
                      mains_timeout_minutes: 10
                """);
        core = newCore(tempDir);
        core.registerIntegrationSchema(ZIGBEE, ZIGBEE_FRAGMENT);

        Throwable fatal = catchThrowable(core::start);

        assertThat(fatal)
                .isInstanceOf(ConfigurationLoadException.class)
                .hasMessageContaining("integrations.zigbee.availability");
        assertThat(core.currentPhase()).isEqualTo(LifecyclePhase.STOPPED);
        List<String> issues = configurationIssues();
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0))
                .startsWith("Configuration issue [ERROR] at 'integrations.zigbee.availability'")
                .contains("'availability' is not defined in the schema");
    }

    // ════════════════════════════════════════════════════════════════════════
    // T4 — the composed schema written at startup carries the fragment (C7)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T4: the C7 on-disk composed schema (schemas/config.schema.json) carries "
            + "integrations.properties.zigbee with the fragment's bounds, byte-equal to the "
            + "registry's composition")
    void composedSchemaCache_carriesTheZigbeeFragment(@TempDir Path tempDir) throws Exception {
        core = newCore(tempDir);
        core.registerIntegrationSchema(ZIGBEE, ZIGBEE_FRAGMENT);

        core.start();

        Path cache = schemaCache(tempDir);
        assertThat(cache).exists();
        String composed = Files.readString(cache);
        assertThat(INTEGRATIONS_CARRY_ZIGBEE.matcher(composed).find())
                .as("integrations.properties.zigbee is composed (the HEAD exhibit was an "
                        + "EMPTY properties node)")
                .isTrue();
        assertThat(INTEGRATIONS_EMPTY.matcher(composed).find()).isFalse();
        // L-4 (AMD-102 R-D / IR-122): the cache carries NEITHER removed key; the 254
        // maximum left with permit_join_duration (the channel's 26 remains).
        assertThat(composed)
                .doesNotContain("\"permit_join_duration\"")
                .doesNotContain("\"availability\"")
                .doesNotContainPattern("\"maximum\"\\s*:\\s*254")
                .containsPattern("\"maximum\"\\s*:\\s*26");
        assertThat(composed).isEqualTo(core.schemaRegistry().getComposedSchema());
    }

    // ════════════════════════════════════════════════════════════════════════
    // T5 — the post-start path stays lawful (a late registrant)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T5: a post-start registerIntegrationSchema never throws, recomposes IN MEMORY "
            + "(the cache file is rewritten by the service's next load/reload/write, not by the "
            + "registration — the C7 behavior read at StandardSchemaRegistry and preserved), and "
            + "logs stage=direct")
    void postStartRegistration_recomposesInMemoryOnly(@TempDir Path tempDir) throws Exception {
        core = newCore(tempDir);
        core.registerIntegrationSchema(ZIGBEE, ZIGBEE_FRAGMENT);
        core.start();
        String cacheBefore = Files.readString(schemaCache(tempDir));

        assertThatCode(() -> core.registerIntegrationSchema("mqtt", MQTT_FRAGMENT))
                .doesNotThrowAnyException();

        String composed = core.schemaRegistry().getComposedSchema();
        assertThat(composed).contains("\"mqtt\"").contains("\"zigbee\"");
        assertThat(Files.readString(schemaCache(tempDir)))
                .as("a late registration does not rewrite the on-disk cache")
                .isEqualTo(cacheBefore)
                .doesNotContain("\"mqtt\"");
        assertThat(registrations()).containsExactly(
                REGISTERED_PREFIX + "type=zigbee stage=pre-load",
                REGISTERED_PREFIX + "type=mqtt stage=direct");
    }

    // ════════════════════════════════════════════════════════════════════════
    // T6 — the pre-start guard is gone: a pre-start call QUEUES
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T6: a pre-start registerIntegrationSchema no longer throws IllegalStateException "
            + "— the fragment is queued (no registry exists before Phase 1)")
    void preStartRegistration_doesNotThrow_isQueued(@TempDir Path tempDir) {
        core = newCore(tempDir);

        assertThatCode(() -> core.registerIntegrationSchema(ZIGBEE, ZIGBEE_FRAGMENT))
                .doesNotThrowAnyException();

        assertThat(core.schemaRegistry())
                .as("Phase 1 has not assembled the registry — the fragment waits in the queue")
                .isNull();
    }

    @Test
    @DisplayName("T6b: the last pre-start registration for a type wins (the registry's own put "
            + "semantics), and both queued types compose at Phase 1")
    void preStartRegistration_lastWinsPerType(@TempDir Path tempDir) throws Exception {
        core = newCore(tempDir);
        core.registerIntegrationSchema("mqtt",
                "{\"type\":\"object\",\"properties\":{\"stale\":{\"type\":\"string\"}}}");
        core.registerIntegrationSchema("mqtt", MQTT_FRAGMENT);
        core.registerIntegrationSchema(ZIGBEE, ZIGBEE_FRAGMENT);

        core.start();

        String composed = core.schemaRegistry().getComposedSchema();
        assertThat(composed).contains("\"broker\"").contains("\"zigbee\"")
                .doesNotContain("\"stale\"");
        assertThat(configurationIssues()).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // T7 — INV-CE-02 (contract 3): EMPTY and ABSENT blocks validate; the
    //      M9.4-PJ law survives composition
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T7: an EMPTY integrations.zigbee block validates to the fragment's defaults — "
            + "and permit_join_duration stays ABSENT (REMOVED, AMD-102: never declared, never "
            + "merged; the M9.4-PJ law holds by construction), availability likewise (IR-122)")
    void emptyZigbeeBlock_validatesToDefaults_permitJoinStaysAbsent(@TempDir Path tempDir)
            throws Exception {
        writeRoot(tempDir, """
                integrations:
                  zigbee: {}
                """);
        core = newCore(tempDir);
        core.registerIntegrationSchema(ZIGBEE, ZIGBEE_FRAGMENT);

        core.start();

        assertThat(configurationIssues()).isEmpty();
        assertDefaultsMergedAndPermitJoinAbsent(zigbeeSection());
    }

    @Test
    @DisplayName("T7b: an ABSENT block (zero-config first run, INV-CE-02) validates; the "
            + "fragment's defaults still merge into an integrations.zigbee section; "
            + "permit_join_duration stays ABSENT")
    void absentZigbeeBlock_validates_permitJoinStaysAbsent(@TempDir Path tempDir)
            throws Exception {
        // No homesynapse.yaml at all — Doc 06 §3.1 stage 1 proceeds with an empty map.
        core = newCore(tempDir);
        core.registerIntegrationSchema(ZIGBEE, ZIGBEE_FRAGMENT);

        core.start();

        assertThat(configurationIssues()).isEmpty();
        assertDefaultsMergedAndPermitJoinAbsent(zigbeeSection());
    }

    // ════════════════════════════════════════════════════════════════════════
    // T8 — the pre-load drain's positive evidence (law 17/19: one INFO per fragment)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T8: the Phase-1 drain logs exactly one "
            + "lifecycle.integration_schema_registered INFO per fragment, stage=pre-load, in "
            + "registration order")
    void preLoadDrain_logsOneInfoPerFragment(@TempDir Path tempDir) throws Exception {
        core = newCore(tempDir);
        core.registerIntegrationSchema(ZIGBEE, ZIGBEE_FRAGMENT);
        core.registerIntegrationSchema("mqtt", MQTT_FRAGMENT);

        core.start();

        assertThat(registrations()).containsExactly(
                REGISTERED_PREFIX + "type=zigbee stage=pre-load",
                REGISTERED_PREFIX + "type=mqtt stage=pre-load");
        assertThat(core.schemaRegistry().getComposedSchema())
                .contains("\"zigbee\"").contains("\"mqtt\"");
    }

    // ════════════════════════════════════════════════════════════════════════
    // T9 — argument guards + a malformed fragment fails the boot in Phase 1
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T9: null / blank type and null fragment are rejected at the call (NPE / IAE), "
            + "pre-start")
    void registerIntegrationSchema_argumentGuards(@TempDir Path tempDir) {
        core = newCore(tempDir);

        assertThatThrownBy(() -> core.registerIntegrationSchema(null, ZIGBEE_FRAGMENT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> core.registerIntegrationSchema(" ", ZIGBEE_FRAGMENT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> core.registerIntegrationSchema(ZIGBEE, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("T9b: a malformed fragment queued pre-start fails the boot in Phase 1 (the "
            + "registry's parse rejection propagates through start(); the core tears down) — a "
            + "bundled-fragment defect is a build defect, never a silent drop")
    void malformedPreStartFragment_failsTheBootInPhaseOne(@TempDir Path tempDir) {
        core = newCore(tempDir);
        core.registerIntegrationSchema("broken", "{ not json");

        assertThatThrownBy(() -> core.start())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("broken");

        assertThat(core.currentPhase()).isEqualTo(LifecyclePhase.STOPPED);
        assertThat(registrations()).as("nothing registered — the drain aborted").isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Harness
    // ════════════════════════════════════════════════════════════════════════

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

    private static void writeInclude(Path tempDir, String fileName, String yaml) throws Exception {
        Path integrations = tempDir.resolve("config").resolve("integrations");
        Files.createDirectories(integrations);
        Files.writeString(integrations.resolve(fileName), yaml);
    }

    private static Path schemaCache(Path tempDir) {
        return tempDir.resolve("config").resolve("schemas").resolve("config.schema.json");
    }

    private Map<String, Object> zigbeeSection() {
        return core.configurationService().getCurrentModel()
                .sections().get("integrations." + ZIGBEE).values();
    }

    private static void assertDefaultsMergedAndPermitJoinAbsent(Map<String, Object> zigbee) {
        assertThat(zigbee)
                .containsEntry("watchdog_interval_seconds", 30)
                .containsEntry("topology_scan_interval_hours", 0)
                .containsEntry("telemetry_threshold_seconds", 10)
                .doesNotContainKey("permit_join_duration")
                // IR-122 (AMD-102 R-D): the availability object left the fragment with its
                // two defaults — nothing merges under that key any more.
                .doesNotContainKey("availability");
        Map<?, ?> routeHealth = (Map<?, ?>) zigbee.get("route_health");
        assertThat(routeHealth.get("failure_threshold")).isEqualTo(3);
    }

    /** The config module's per-issue WARN lines, in emission order. */
    private List<String> configurationIssues() {
        return configLog.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(CONFIG_ISSUE_PREFIX))
                .toList();
    }

    /** The composition root's registration INFO lines, in emission order. */
    private List<String> registrations() {
        return lifecycleLog.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(REGISTERED_PREFIX))
                .toList();
    }

    private static ListAppender<ILoggingEvent> attach(String loggerName) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger(loggerName).addAppender(appender);
        return appender;
    }

    private static void detach(String loggerName, ListAppender<ILoggingEvent> appender) {
        if (appender != null) {
            logger(loggerName).detachAppender(appender);
            appender.stop();
        }
    }

    private static ch.qos.logback.classic.Logger logger(String name) {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name);
    }
}
