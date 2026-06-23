/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.homesynapse.device.CommandDefinition;
import com.homesynapse.device.ConfirmationMode;
import com.homesynapse.device.ConfirmationPolicy;
import com.homesynapse.device.CustomCapability;
import com.homesynapse.device.Entity;
import com.homesynapse.device.ExactMatch;
import com.homesynapse.device.ExpectedOutcome;
import com.homesynapse.device.IdempotencyClass;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.event.CommandIdempotency;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.value.BooleanValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The OPTIMISTIC-bypass gate (AMD-90 — named {@code ConfirmationPolicyGateTest}): a command on
 * a capability that declares no confirmation ({@link ConfirmationMode#DISABLED} — the in-tree
 * "optimistic / confirmation off" signal; AMD-90's {@code CommandAction.confirmation} enum is
 * not on the frozen action model) is never tracked, so it bypasses {@code trackCommand} entirely
 * and never times out. A command on a confirming capability is tracked.
 */
@DisplayName("StandardPendingCommandLedger — confirmation-policy gate (AMD-90)")
class ConfirmationPolicyGateTest {

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private final EntityId entityId = AutomationTestSupport.entityId();
    private final DeviceId deviceId = AutomationTestSupport.deviceId();

    private AutomationTestSupport.RecordingEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new AutomationTestSupport.RecordingEventPublisher();
    }

    private StandardPendingCommandLedger ledgerFor(Entity entity) {
        return new StandardPendingCommandLedger(publisher,
                new AutomationTestSupport.StubEntityRegistry(List.of(entity)),
                AutomationTestSupport.FIXED_CLOCK, DEFAULT_TIMEOUT_MS);
    }

    private EventEnvelope turnOn() {
        return AutomationTestSupport.envelope(EventTypes.COMMAND_ISSUED,
                SubjectRef.entity(entityId),
                new CommandIssuedEvent(entityId.value(), "turn_on", "{}", 30_000,
                        CommandIdempotency.IDEMPOTENT));
    }

    @Test
    @DisplayName("OPTIMISTIC (confirmation DISABLED): command_issued is never tracked, never times out")
    void optimistic_neverTracked() {
        CommandDefinition turnOn = new CommandDefinition("turn_on", List.of(), 0,
                List.of(new ExpectedOutcome("on", new ExactMatch(new BooleanValue(true)), 5_000L)),
                Duration.ofSeconds(5), IdempotencyClass.IDEMPOTENT);
        CustomCapability optimistic = new CustomCapability("optimistic_relay", 1, "test",
                Map.of(), Map.of("turn_on", turnOn),
                new ConfirmationPolicy(ConfirmationMode.DISABLED, List.of(), null, 0L));
        Entity entity = AutomationTestSupport.entityWith(entityId, deviceId, optimistic);
        StandardPendingCommandLedger ledger = ledgerFor(entity);

        EventEnvelope issued = turnOn();
        ledger.onEvent(issued);

        assertThat(ledger.pendingCount()).isZero();
        assertThat(ledger.getCommand(issued.eventId())).isEmpty();

        ledger.pollExpirations();   // nothing tracked — nothing can time out
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("a confirming capability (EXACT_MATCH): command_issued is tracked as DISPATCHED")
    void confirming_isTracked() {
        Entity entity = AutomationTestSupport.entityWith(entityId, deviceId,
                StandardCapabilities.onOff());
        StandardPendingCommandLedger ledger = ledgerFor(entity);

        EventEnvelope issued = turnOn();
        ledger.onEvent(issued);

        assertThat(ledger.pendingCount()).isEqualTo(1);
        PendingCommand tracked = ledger.getCommand(issued.eventId()).orElseThrow();
        assertThat(tracked.status()).isEqualTo(PendingStatus.DISPATCHED);
        assertThat(tracked.targetAttribute()).isEqualTo("on");
        assertThat(tracked.commandName()).isEqualTo("turn_on");
        assertThat(publisher.published()).isEmpty();   // tracking does not publish
    }

    @Test
    @DisplayName("a command with no resolvable capability is not tracked")
    void unresolvableCommand_notTracked() {
        Entity entity = AutomationTestSupport.entityWith(entityId, deviceId,
                StandardCapabilities.onOff());
        StandardPendingCommandLedger ledger = ledgerFor(entity);

        ledger.onEvent(AutomationTestSupport.envelope(EventTypes.COMMAND_ISSUED,
                SubjectRef.entity(entityId),
                new CommandIssuedEvent(entityId.value(), "frobnicate", "{}", 30_000,
                        CommandIdempotency.IDEMPOTENT)));

        assertThat(ledger.pendingCount()).isZero();
    }
}
