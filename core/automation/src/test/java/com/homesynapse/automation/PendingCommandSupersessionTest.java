/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.homesynapse.device.Capability;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRole;
import com.homesynapse.device.EntityType;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.event.CommandIdempotency;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateConfirmedEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-2 / DP-c supersession expiry (Doc 08 §3.6 caveat 3, verbatim: "expectations superseded by
 * a newer command on the same attribute expire — never false-fail, never false-confirm").
 * The acceptance arithmetic is WU-M9.4 §E, BOTH directions, in the capability domain (Kelvin):
 * the bench fixture triple 6211/4630/4525 K (Direction 1, the false-FAIL guard) and the
 * synthetic ≤50 K pair 4550/4525 K (Direction 2, the false-CONFIRM guard). Issuance
 * supersedes, not tracking success — a newer command whose own derivation declines still
 * expires older in-flight expectations on the resolved attribute. Fixture-clock discipline
 * (§4c): all time is injected.
 */
@DisplayName("StandardPendingCommandLedger — supersession expiry (F-2 / Doc 08 §3.6 caveat 3)")
class PendingCommandSupersessionTest {

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /** Minimal test decoder for the single-int parameter payloads this test issues. */
    private static final Function<String, Map<String, Object>> DECODER =
            PendingCommandSupersessionTest::decode;

    private final EntityId entityId = AutomationTestSupport.entityId();
    private final DeviceId deviceId = AutomationTestSupport.deviceId();

    private AutomationTestSupport.RecordingEventPublisher publisher;
    private AutomationTestSupport.MutableClock clock;
    private StandardPendingCommandLedger ledger;

    @BeforeEach
    void setUp() {
        publisher = new AutomationTestSupport.RecordingEventPublisher();
        clock = AutomationTestSupport.mutableClock();
        Entity entity = lightWith(StandardCapabilities.colorTemperature(),
                StandardCapabilities.onOff());
        ledger = new StandardPendingCommandLedger(publisher,
                new AutomationTestSupport.StubEntityRegistry(List.of(entity)), clock,
                DEFAULT_TIMEOUT_MS, DECODER);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** An entity carrying several capabilities (the shared support builds only one). */
    private Entity lightWith(Capability... capabilities) {
        List<CapabilityInstance> instances = java.util.Arrays.stream(capabilities)
                .map(capability -> new CapabilityInstance(capability.capabilityId(),
                        capability.version(), capability.namespace(), 0,
                        capability.attributeSchemas(), capability.commandDefinitions(),
                        capability.confirmationPolicy()))
                .toList();
        return new Entity(entityId, "ent-" + entityId, EntityType.LIGHT, "Entity", deviceId,
                0, null, true, List.of(), instances, EntityRole.PRIMARY,
                AutomationTestSupport.FIXED_INSTANT);
    }

    private EventEnvelope colorTemperature(int kelvin) {
        return command("set_color_temperature", "{\"kelvin\":" + kelvin + "}");
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

    private List<CommandResultEvent> supersededResults() {
        return publisher.ofType(EventTypes.COMMAND_RESULT).stream()
                .map(envelope -> (CommandResultEvent) envelope.payload())
                .filter(result -> "superseded".equals(result.outcome()))
                .toList();
    }

    private static Map<String, Object> decode(String parameters) {
        if ("{}".equals(parameters)) {
            return Map.of();
        }
        int colon = parameters.indexOf(':');
        int close = parameters.indexOf('}');
        if (!parameters.startsWith("{\"") || colon < 0 || close < colon) {
            throw new IllegalArgumentException("parameters are not a JSON object: " + parameters);
        }
        String key = parameters.substring(2, parameters.indexOf('"', 2));
        int value = Integer.parseInt(parameters.substring(colon + 1, close).trim());
        return Map.of(key, value);
    }

    // ── Direction 1 — the false-FAIL guard (WU-M9.4 §E fixture triple) ──────

    @Test
    @DisplayName("Direction 1: the fixture triple 6211/4630/4525 K renders two superseded + one confirm + zero timeouts")
    void fixtureTriple_supersededExpire_neverFalseFail() {
        EventEnvelope first = colorTemperature(6211);
        EventEnvelope second = colorTemperature(4630);
        EventEnvelope third = colorTemperature(4525);
        ledger.onEvent(first);
        ledger.onEvent(second);
        ledger.onEvent(third);

        List<CommandResultEvent> superseded = supersededResults();
        assertThat(superseded).hasSize(2);
        assertThat(publisher.countOfType(EventTypes.COMMAND_RESULT)).isEqualTo(2);
        // The disposition names the superseding command event (Register C, no self-reference).
        assertThat(superseded.get(0).failureReason())
                .contains("superseded by a newer command on the same attribute")
                .contains(second.eventId().value().toString());
        assertThat(superseded.get(1).failureReason())
                .contains(third.eventId().value().toString());
        assertThat(ledger.getCommand(first.eventId())).isEmpty();
        assertThat(ledger.getCommand(second.eventId())).isEmpty();
        assertThat(ledger.pendingCount()).isEqualTo(1);

        ledger.onEvent(reported("color_temp_kelvin", "4525"));

        assertThat(publisher.countOfType(EventTypes.STATE_CONFIRMED)).isEqualTo(1);
        StateConfirmedEvent confirmed = (StateConfirmedEvent)
                publisher.ofType(EventTypes.STATE_CONFIRMED).get(0).payload();
        assertThat(confirmed.commandEventId()).isEqualTo(third.eventId());

        // WITHOUT expiry the 6211/4630 entries would render timeout/false-fail here.
        clock.advance(Duration.ofSeconds(31));
        ledger.pollExpirations();
        assertThat(publisher.countOfType(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT)).isZero();
    }

    // ── Direction 2 — the false-CONFIRM guard (synthetic ≤tolerance pair) ───

    @Test
    @DisplayName("Direction 2: 4550 K then 4525 K, one report 4525 — the superseded 4550 NEVER confirms (|25| ≤ 50 would have matched)")
    void toleranceOverlapPair_supersededNeverConfirms() {
        EventEnvelope first = colorTemperature(4550);
        EventEnvelope second = colorTemperature(4525);
        ledger.onEvent(first);
        ledger.onEvent(second);

        assertThat(supersededResults()).hasSize(1);
        assertThat(ledger.getCommand(first.eventId())).isEmpty();

        ledger.onEvent(reported("color_temp_kelvin", "4525"));

        List<EventEnvelope> confirmations = publisher.ofType(EventTypes.STATE_CONFIRMED);
        assertThat(confirmations).hasSize(1);
        assertThat(((StateConfirmedEvent) confirmations.get(0).payload()).commandEventId())
                .isEqualTo(second.eventId());
    }

    // ── cross-attribute isolation ────────────────────────────────────────────

    @Test
    @DisplayName("same-(entity,attribute) only: a turn_on does not expire an in-flight set_color_temperature")
    void crossAttribute_nothingExpires() {
        ledger.onEvent(colorTemperature(4525));
        ledger.onEvent(command("turn_on", "{}"));

        assertThat(publisher.countOfType(EventTypes.COMMAND_RESULT)).isZero();
        assertThat(ledger.pendingCount()).isEqualTo(2);
    }

    // ── issuance supersedes (not tracking success) + dual-index hygiene ─────

    @Test
    @DisplayName("issuance supersedes: a newer same-attribute command whose own derivation declines still expires the old entry")
    void decliningNewCommand_stillSupersedes_oldEntryGone() {
        EventEnvelope tracked = colorTemperature(4550);
        ledger.onEvent(tracked);
        assertThat(ledger.pendingCount()).isEqualTo(1);

        // Malformed parameters: the decoder throws, derivation declines, the new command is
        // optimistic-in-effect — but its capability resolves, so issuance still supersedes.
        EventEnvelope declining = command("set_color_temperature", "not-json");
        ledger.onEvent(declining);

        assertThat(supersededResults()).hasSize(1);
        assertThat(ledger.getCommand(tracked.eventId())).isEmpty();
        assertThat(ledger.getCommand(declining.eventId())).isEmpty();
        assertThat(ledger.pendingCount()).isZero();

        // Dual-index hygiene: the expired id produces zero publications on a later report
        // (the byEntity set was dropped when it emptied — the P11 removal rule).
        ledger.onEvent(reported("color_temp_kelvin", "4550"));
        assertThat(publisher.countOfType(EventTypes.STATE_CONFIRMED)).isZero();

        // The expired entry never times out afterward either (it is GONE from both indices).
        clock.advance(Duration.ofSeconds(31));
        ledger.pollExpirations();
        assertThat(publisher.countOfType(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT)).isZero();
    }

    @Test
    @DisplayName("the ledger's own superseded command_result never terminal-matches the new in-flight entry (loop-back guard)")
    void supersededResult_loopedBack_doesNotKillNewEntry() {
        EventEnvelope first = colorTemperature(4550);
        EventEnvelope second = colorTemperature(4525);
        ledger.onEvent(first);
        ledger.onEvent(second);
        EventEnvelope loopedBack = AutomationTestSupport.envelope(EventTypes.COMMAND_RESULT,
                SubjectRef.entity(entityId),
                (CommandResultEvent) publisher.ofType(EventTypes.COMMAND_RESULT)
                        .get(0).payload());

        ledger.onEvent(loopedBack);

        // The superseded disposition is a ledger verdict, not an adapter rejection — the
        // in-flight 4525 entry must survive redelivery of the ledger's own output.
        assertThat(ledger.getCommand(second.eventId())).isPresent();
        assertThat(publisher.countOfType(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT)).isZero();
    }
}
