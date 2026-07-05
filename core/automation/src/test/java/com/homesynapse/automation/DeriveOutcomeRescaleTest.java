/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.homesynapse.device.AttributeSchema;
import com.homesynapse.device.Brightness;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.Capability;
import com.homesynapse.device.CommandDefinition;
import com.homesynapse.device.ConfirmationMode;
import com.homesynapse.device.ConfirmationPolicy;
import com.homesynapse.device.CustomCapability;
import com.homesynapse.device.Entity;
import com.homesynapse.device.ExpectedOutcome;
import com.homesynapse.device.IdempotencyClass;
import com.homesynapse.device.ParameterSchema;
import com.homesynapse.device.Permission;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.device.WithinTolerance;
import com.homesynapse.event.CommandIdempotency;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.value.AttributeType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SD-2 (Nick, 2026-07-04, v18 beat 5) — the GENERIC param-domain &rarr;
 * attribute-domain rescale in {@code deriveOutcome}: when BOTH the sourced
 * {@link ParameterSchema} and the target {@link AttributeSchema} carry
 * non-null numeric bounds AND the bounds differ, the derived tolerance target
 * is linearly rescaled into the attribute domain — schema-driven,
 * protocol-agnostic, zero ZCL knowledge in core. Equal or missing bounds take
 * the identity leg (the pre-M9.4b behavior, byte-identical). The tolerance is
 * NEVER rescaled — {@code ConfirmationPolicy.defaultTolerance} is
 * attribute-domain by contract (Doc 08 §392: &plusmn;2 in LEVEL units).
 *
 * <p>The worked example (M9.4b §2.2): {@code set_brightness(50)} &rarr; param
 * [0,100], attribute [0,254] &rarr; target = round(50 &times; 254 / 100) =
 * 127; expectation {@code WithinTolerance(127, 2)}; the wire frame carries
 * level 127; the device reports 127 (or 128 — Hue rounding) &rarr; honest
 * CONFIRMED. Before this fix the expectation was {@code WithinTolerance(50, 2)}
 * against a 127-level report — the F-3 false-fail class, now dead.</p>
 */
@DisplayName("StandardPendingCommandLedger — deriveOutcome generic rescale (SD-2 / M9.4b §2.2)")
class DeriveOutcomeRescaleTest {

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private final EntityId entityId = AutomationTestSupport.entityId();
    private final DeviceId deviceId = AutomationTestSupport.deviceId();

    private AutomationTestSupport.RecordingEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new AutomationTestSupport.RecordingEventPublisher();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private StandardPendingCommandLedger ledgerFor(Entity entity,
            Function<String, Map<String, Object>> decoder) {
        return new StandardPendingCommandLedger(publisher,
                new AutomationTestSupport.StubEntityRegistry(List.of(entity)),
                AutomationTestSupport.FIXED_CLOCK, DEFAULT_TIMEOUT_MS, decoder);
    }

    private EventEnvelope command(String commandType, String parameters) {
        return AutomationTestSupport.envelope(EventTypes.COMMAND_ISSUED,
                SubjectRef.entity(entityId),
                new CommandIssuedEvent(entityId.value(), commandType, parameters, 30_000,
                        CommandIdempotency.IDEMPOTENT));
    }

    private EventEnvelope reported(String attributeKey, String value) {
        return AutomationTestSupport.envelope(EventTypes.STATE_REPORTED,
                SubjectRef.entity(entityId),
                new StateReportedEvent(attributeKey, value, null, null, null));
    }

    /** The per-entity instance shape the classifier builds (P34 — capability defaults). */
    private static CapabilityInstance instanceOf(Capability cap) {
        return new CapabilityInstance(cap.capabilityId(), cap.version(), cap.namespace(), 0,
                cap.attributeSchemas(), cap.commandDefinitions(), cap.confirmationPolicy());
    }

    // ── the worked example (format #5) ──────────────────────────────────────

    @Test
    @DisplayName("set_brightness(50) derives WithinTolerance(127, 2) — the level-domain target")
    void workedExample_percentFifty_targetsLevel127() {
        Entity entity = AutomationTestSupport.entityWith(entityId, deviceId,
                StandardCapabilities.brightness());
        StandardPendingCommandLedger ledger =
                ledgerFor(entity, parameters -> Map.of("level", 50));

        EventEnvelope issued = command("set_brightness", "{\"level\":50}");
        ledger.onEvent(issued);

        PendingCommand tracked = ledger.getCommand(issued.eventId()).orElseThrow();
        assertThat(tracked.targetAttribute()).isEqualTo("brightness");
        // round(0 + (50 − 0) × (254 − 0) / (100 − 0)) = round(127.0) = 127;
        // tolerance stays 2 — attribute-domain, NEVER rescaled (Doc 08 §392).
        assertThat(tracked.expectation()).isEqualTo(new WithinTolerance(127, 2));
    }

    @Test
    @DisplayName("the device's level report confirms: 127 exact, and 128 (Hue rounding) in-band")
    void workedExample_levelReportConfirms() {
        Entity entity = AutomationTestSupport.entityWith(entityId, deviceId,
                StandardCapabilities.brightness());
        StandardPendingCommandLedger ledger =
                ledgerFor(entity, parameters -> Map.of("level", 50));

        ledger.onEvent(command("set_brightness", "{\"level\":50}"));
        ledger.onEvent(reported("brightness", "128"));   // |128 − 127| = 1 ≤ 2

        assertThat(publisher.countOfType(EventTypes.STATE_CONFIRMED)).isEqualTo(1);
    }

    @Test
    @DisplayName("the pre-fix false-fail class is dead: a percent-domain target never survives")
    void percentDomainTarget_neverDerived() {
        Entity entity = AutomationTestSupport.entityWith(entityId, deviceId,
                StandardCapabilities.brightness());
        StandardPendingCommandLedger ledger =
                ledgerFor(entity, parameters -> Map.of("level", 50));

        EventEnvelope issued = command("set_brightness", "{\"level\":50}");
        ledger.onEvent(issued);

        PendingCommand tracked = ledger.getCommand(issued.eventId()).orElseThrow();
        // The old (broken) expectation compared the raw percent 50 against a
        // 127-level report — grep-confirmed false-fail. It must never come back.
        assertThat(tracked.expectation()).isNotEqualTo(new WithinTolerance(50, 2));
    }

    // ── the alignment sweep (mandatory, §2.2) ───────────────────────────────

    @Test
    @DisplayName("sweep 0-100: the bridge target IS the wire linear map round(p × 254 / 100)")
    void sweep_bridgeTargetMatchesTheWireLinearMap() {
        Brightness brightness = StandardCapabilities.brightness();
        CapabilityInstance instance = instanceOf(brightness);
        CommandDefinition definition = brightness.commandDefinitions().get("set_brightness");
        AtomicInteger level = new AtomicInteger();
        StandardPendingCommandLedger ledger = ledgerFor(
                AutomationTestSupport.entityWith(entityId, deviceId, brightness),
                parameters -> Map.of("level", level.get()));

        for (int percent = 0; percent <= 100; percent++) {
            level.set(percent);
            Optional<ExpectedOutcome> outcome = ledger.deriveOutcome(definition, instance,
                    new CommandIssuedEvent(entityId.value(), "set_brightness", "{}", 1000,
                            CommandIdempotency.IDEMPOTENT));

            WithinTolerance expectation =
                    (WithinTolerance) outcome.orElseThrow().expectation();
            // LevelControlHandler's wire level is round(p × 254 / 100) clamped —
            // the same linear map; BuildCommandTest pins the zigbee half of this
            // alignment (|bridge − wire| ≤ 1; the ±2 tolerance absorbs it).
            assertThat(expectation.target())
                    .as("percent %s", percent)
                    .isEqualTo((double) Math.round(percent * 254 / 100.0));
            assertThat(expectation.tolerance())
                    .as("tolerance is never rescaled")
                    .isEqualTo(2.0);
        }
    }

    // ── the identity legs (today's behavior, byte-identical) ────────────────

    @Test
    @DisplayName("equal bounds take the identity leg: set_color_temperature(2702) stays 2702")
    void equalBounds_identity_colorTemperature() {
        Entity entity = AutomationTestSupport.entityWith(entityId, deviceId,
                StandardCapabilities.colorTemperature());
        StandardPendingCommandLedger ledger =
                ledgerFor(entity, parameters -> Map.of("kelvin", 2702));

        EventEnvelope issued = command("set_color_temperature", "{\"kelvin\":2702}");
        ledger.onEvent(issued);

        PendingCommand tracked = ledger.getCommand(issued.eventId()).orElseThrow();
        // Param AND attribute are both 2000-6500 — bounds equal ⇒ rescale-identity;
        // the M9.4a supersession/Kelvin pins stay green by construction.
        assertThat(tracked.expectation()).isEqualTo(new WithinTolerance(2702, 50));
    }

    @Test
    @DisplayName("a boundless attribute schema takes the identity leg")
    void boundlessAttributeSchema_identity() {
        CommandDefinition definition = new CommandDefinition("set_position",
                List.of(new ParameterSchema("target_pos", AttributeType.INT, 0, 100, true,
                        0, null)),
                0, List.of(), Duration.ofSeconds(5), IdempotencyClass.IDEMPOTENT);
        CustomCapability positioner = new CustomCapability("positioner", 1, "test",
                Map.of("position", new AttributeSchema("position", AttributeType.INT,
                        null, null, null, null, null, null,
                        Set.of(Permission.READ), false, true)),
                Map.of("set_position", definition),
                new ConfirmationPolicy(ConfirmationMode.TOLERANCE, List.of("position"),
                        1, 5000L));
        StandardPendingCommandLedger ledger = ledgerFor(
                AutomationTestSupport.entityWith(entityId, deviceId, positioner),
                parameters -> Map.of("target_pos", 40));

        Optional<ExpectedOutcome> outcome = ledger.deriveOutcome(definition,
                instanceOf(positioner),
                new CommandIssuedEvent(entityId.value(), "set_position", "{}", 1000,
                        CommandIdempotency.IDEMPOTENT));

        assertThat(outcome.orElseThrow().expectation())
                .isEqualTo(new WithinTolerance(40, 1));
    }

    @Test
    @DisplayName("a boundless parameter schema takes the identity leg")
    void boundlessParameterSchema_identity() {
        CommandDefinition definition = new CommandDefinition("set_position",
                List.of(new ParameterSchema("target_pos", AttributeType.INT, null, null, true,
                        0, null)),
                0, List.of(), Duration.ofSeconds(5), IdempotencyClass.IDEMPOTENT);
        CustomCapability positioner = new CustomCapability("positioner", 1, "test",
                Map.of("position", new AttributeSchema("position", AttributeType.INT,
                        0, 254, null, null, null, null,
                        Set.of(Permission.READ), false, true)),
                Map.of("set_position", definition),
                new ConfirmationPolicy(ConfirmationMode.TOLERANCE, List.of("position"),
                        1, 5000L));
        StandardPendingCommandLedger ledger = ledgerFor(
                AutomationTestSupport.entityWith(entityId, deviceId, positioner),
                parameters -> Map.of("target_pos", 40));

        Optional<ExpectedOutcome> outcome = ledger.deriveOutcome(definition,
                instanceOf(positioner),
                new CommandIssuedEvent(entityId.value(), "set_position", "{}", 1000,
                        CommandIdempotency.IDEMPOTENT));

        assertThat(outcome.orElseThrow().expectation())
                .isEqualTo(new WithinTolerance(40, 1));
    }

    @Test
    @DisplayName("an attribute the instance does not schema-declare takes the identity leg")
    void undeclaredAttribute_identity() {
        // The existing decline-matrix fixtures build instances with EMPTY
        // attribute maps — the rescale must leave their behavior byte-identical.
        CommandDefinition definition = new CommandDefinition("set_level",
                List.of(new ParameterSchema("level", AttributeType.INT, 0, 100, true,
                        0, null)),
                0, List.of(), Duration.ofSeconds(5), IdempotencyClass.IDEMPOTENT);
        CustomCapability schemaless = new CustomCapability("schemaless", 1, "test",
                Map.of(), Map.of("set_level", definition),
                new ConfirmationPolicy(ConfirmationMode.TOLERANCE, List.of("level"),
                        1, 5000L));
        StandardPendingCommandLedger ledger = ledgerFor(
                AutomationTestSupport.entityWith(entityId, deviceId, schemaless),
                parameters -> Map.of("level", 40));

        Optional<ExpectedOutcome> outcome = ledger.deriveOutcome(definition,
                instanceOf(schemaless),
                new CommandIssuedEvent(entityId.value(), "set_level", "{}", 1000,
                        CommandIdempotency.IDEMPOTENT));

        assertThat(outcome.orElseThrow().expectation())
                .isEqualTo(new WithinTolerance(40, 1));
    }
}
