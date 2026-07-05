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
import java.util.function.Function;

import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.CommandDefinition;
import com.homesynapse.device.ConfirmationMode;
import com.homesynapse.device.ConfirmationPolicy;
import com.homesynapse.device.CustomCapability;
import com.homesynapse.device.Entity;
import com.homesynapse.device.ExactMatch;
import com.homesynapse.device.ExpectedOutcome;
import com.homesynapse.device.IdempotencyClass;
import com.homesynapse.device.ParameterSchema;
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
 * F-3 / DP-b — the parameterized-expectation derivation ({@code deriveOutcome}, the
 * ExpectationFactory realization): a LIVE {@code command_issued} whose capability declares
 * NO static outcome derives its {@link WithinTolerance}/{@link ExactMatch} expectation from
 * the decoded command parameters + the capability's {@link ConfirmationPolicy}. The rule
 * never guesses — a derivation it cannot ground declines to the pre-existing
 * optimistic-in-effect semantics (INV-SA-03). Fixture-clock discipline (§4c).
 */
@DisplayName("StandardPendingCommandLedger — parameterized-expectation derivation (F-3 / DP-b)")
class ExpectationDerivationTest {

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /** Decoder that must never be consulted (AssertionError is not swallowed by the seam). */
    private static final Function<String, Map<String, Object>> NEVER_DECODES =
            parameters -> {
                throw new AssertionError("the parameter decoder must not be consulted");
            };

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

    private static ParameterSchema intParam(String name) {
        return new ParameterSchema(name, AttributeType.INT, null, null, true, 0, null);
    }

    // ── the two unlocked TOLERANCE legs (P35 — StandardCapabilities untouched) ─

    @Test
    @DisplayName("set_brightness(level=72) derives WithinTolerance(183, 2) — the SD-2 rescaled "
            + "level-domain target — and confirms boundary-inclusive at 185")
    void setBrightness_derivesTolerance_confirmsInclusiveBand() {
        Entity entity = AutomationTestSupport.entityWith(entityId, deviceId,
                StandardCapabilities.brightness());
        StandardPendingCommandLedger ledger =
                ledgerFor(entity, parameters -> Map.of("level", 72));

        EventEnvelope issued = command("set_brightness", "{\"level\":72}");
        ledger.onEvent(issued);

        PendingCommand tracked = ledger.getCommand(issued.eventId()).orElseThrow();
        assertThat(tracked.targetAttribute()).isEqualTo("brightness");
        // M9.4b §2.2 (SD-2): param [0,100] → attribute [0,254] rescale —
        // round(72 × 254 / 100) = round(182.88) = 183. The device reports the
        // LEVEL domain, so the target must live there too (the F-3 leg).
        assertThat(tracked.expectation()).isEqualTo(new WithinTolerance(183, 2));

        ledger.onEvent(reported("brightness", "185"));  // |185 − 183| = 2 ≤ 2 — inclusive edge

        assertThat(publisher.countOfType(EventTypes.STATE_CONFIRMED)).isEqualTo(1);
    }

    @Test
    @DisplayName("a report outside the band (186 for target 183 ± 2) does not confirm — the entry is retained")
    void setBrightness_reportOutsideBand_notConfirmed() {
        Entity entity = AutomationTestSupport.entityWith(entityId, deviceId,
                StandardCapabilities.brightness());
        StandardPendingCommandLedger ledger =
                ledgerFor(entity, parameters -> Map.of("level", 72));

        ledger.onEvent(command("set_brightness", "{\"level\":72}"));
        ledger.onEvent(reported("brightness", "186"));  // |186 − 183| = 3 > 2

        assertThat(publisher.countOfType(EventTypes.STATE_CONFIRMED)).isZero();
        assertThat(ledger.pendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("set_color_temperature(kelvin=2702) derives WithinTolerance(2702, 50) — report 2700 confirms (the measured ±1-mired drift class closed)")
    void setColorTemperature_derivesTolerance_confirmsMeasuredDrift() {
        Entity entity = AutomationTestSupport.entityWith(entityId, deviceId,
                StandardCapabilities.colorTemperature());
        StandardPendingCommandLedger ledger =
                ledgerFor(entity, parameters -> Map.of("kelvin", 2702));

        EventEnvelope issued = command("set_color_temperature", "{\"kelvin\":2702}");
        ledger.onEvent(issued);

        PendingCommand tracked = ledger.getCommand(issued.eventId()).orElseThrow();
        assertThat(tracked.targetAttribute()).isEqualTo("color_temp_kelvin");
        assertThat(tracked.expectation()).isEqualTo(new WithinTolerance(2702, 50));

        ledger.onEvent(reported("color_temp_kelvin", "2700"));

        assertThat(publisher.countOfType(EventTypes.STATE_CONFIRMED)).isEqualTo(1);
    }

    // ── the never-guess decline matrix ──────────────────────────────────────

    @Test
    @DisplayName("a command with two required parameters declines — derivation cannot ground a single target")
    void twoRequiredParameters_declines() {
        CommandDefinition definition = new CommandDefinition("set_pair",
                List.of(intParam("first"), intParam("second")), 0, List.of(),
                Duration.ofSeconds(5), IdempotencyClass.IDEMPOTENT);
        CapabilityInstance instance = new CapabilityInstance("pair", 1, "test", 0, Map.of(),
                Map.of("set_pair", definition),
                new ConfirmationPolicy(ConfirmationMode.TOLERANCE, List.of("first"), 1, 5000L));
        StandardPendingCommandLedger ledger = ledgerFor(
                AutomationTestSupport.entityWith(entityId, deviceId,
                        StandardCapabilities.onOff()),
                NEVER_DECODES);

        Optional<ExpectedOutcome> outcome = ledger.deriveOutcome(definition, instance,
                new CommandIssuedEvent(entityId.value(), "set_pair", "{\"first\":1}", 1000,
                        CommandIdempotency.IDEMPOTENT));

        assertThat(outcome).isEmpty();
    }

    @Test
    @DisplayName("a zero-parameter command declines — nothing to derive from")
    void zeroParameters_declines() {
        CommandDefinition definition = new CommandDefinition("nudge", List.of(), 0, List.of(),
                Duration.ofSeconds(5), IdempotencyClass.IDEMPOTENT);
        CapabilityInstance instance = new CapabilityInstance("nudger", 1, "test", 0, Map.of(),
                Map.of("nudge", definition),
                new ConfirmationPolicy(ConfirmationMode.TOLERANCE, List.of("position"), 1, 5000L));
        StandardPendingCommandLedger ledger = ledgerFor(
                AutomationTestSupport.entityWith(entityId, deviceId,
                        StandardCapabilities.onOff()),
                NEVER_DECODES);

        Optional<ExpectedOutcome> outcome = ledger.deriveOutcome(definition, instance,
                new CommandIssuedEvent(entityId.value(), "nudge", "{}", 1000,
                        CommandIdempotency.IDEMPOTENT));

        assertThat(outcome).isEmpty();
    }

    @Test
    @DisplayName("malformed parameters JSON declines with a DEBUG — optimistic-in-effect, never a throw")
    void malformedParameters_declines_notTracked() {
        Entity entity = AutomationTestSupport.entityWith(entityId, deviceId,
                StandardCapabilities.brightness());
        StandardPendingCommandLedger ledger = ledgerFor(entity, parameters -> {
            throw new IllegalArgumentException("parameters are not a JSON object");
        });

        ledger.onEvent(command("set_brightness", "not-json"));

        assertThat(ledger.pendingCount()).isZero();
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("a decoded value that is not numeric where numeric is expected declines")
    void nonNumericDecodedValue_declines() {
        Entity entity = AutomationTestSupport.entityWith(entityId, deviceId,
                StandardCapabilities.brightness());
        StandardPendingCommandLedger ledger =
                ledgerFor(entity, parameters -> Map.of("level", "bright"));

        ledger.onEvent(command("set_brightness", "{\"level\":\"bright\"}"));

        assertThat(ledger.pendingCount()).isZero();
    }

    @Test
    @DisplayName("a missing parameter key declines")
    void missingParameterKey_declines() {
        Entity entity = AutomationTestSupport.entityWith(entityId, deviceId,
                StandardCapabilities.brightness());
        StandardPendingCommandLedger ledger =
                ledgerFor(entity, parameters -> Map.of("other", 72));

        ledger.onEvent(command("set_brightness", "{\"other\":72}"));

        assertThat(ledger.pendingCount()).isZero();
    }

    @Test
    @DisplayName("DISABLED mode is never consulted — the AMD-90 bypass fires before derivation")
    void disabledMode_bypassFirst_decoderNeverConsulted() {
        CommandDefinition definition = new CommandDefinition("set_level",
                List.of(intParam("level")), 0, List.of(),
                Duration.ofSeconds(5), IdempotencyClass.IDEMPOTENT);
        CustomCapability disabled = new CustomCapability("optimistic_dimmer", 1, "test",
                Map.of(), Map.of("set_level", definition),
                new ConfirmationPolicy(ConfirmationMode.DISABLED, List.of(), null, 0L));
        Entity entity = AutomationTestSupport.entityWith(entityId, deviceId, disabled);
        StandardPendingCommandLedger ledger = ledgerFor(entity, NEVER_DECODES);

        ledger.onEvent(command("set_level", "{\"level\":10}"));

        assertThat(ledger.pendingCount()).isZero();
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("a declared-outcome command rides chooseOutcome — derivation is the fallback, not the override")
    void declaredOutcome_winsOverDerivation() {
        Entity entity = AutomationTestSupport.entityWith(entityId, deviceId,
                StandardCapabilities.onOff());
        StandardPendingCommandLedger ledger = ledgerFor(entity, NEVER_DECODES);

        EventEnvelope issued = command("turn_on", "{}");
        ledger.onEvent(issued);

        PendingCommand tracked = ledger.getCommand(issued.eventId()).orElseThrow();
        assertThat(tracked.targetAttribute()).isEqualTo("on");
        assertThat(tracked.expectation()).isInstanceOf(ExactMatch.class);
    }

    @Test
    @DisplayName("a null defaultTolerance declines — never an NPE (the P1 javadoc allows null)")
    void nullDefaultTolerance_declines() {
        CommandDefinition definition = new CommandDefinition("set_level",
                List.of(intParam("level")), 0, List.of(),
                Duration.ofSeconds(5), IdempotencyClass.IDEMPOTENT);
        CustomCapability tolerant = new CustomCapability("toleranceless", 1, "test",
                Map.of(), Map.of("set_level", definition),
                new ConfirmationPolicy(ConfirmationMode.TOLERANCE, List.of("level"), null, 5000L));
        Entity entity = AutomationTestSupport.entityWith(entityId, deviceId, tolerant);
        StandardPendingCommandLedger ledger =
                ledgerFor(entity, parameters -> Map.of("level", 10));

        ledger.onEvent(command("set_level", "{\"level\":10}"));

        assertThat(ledger.pendingCount()).isZero();
    }
}
