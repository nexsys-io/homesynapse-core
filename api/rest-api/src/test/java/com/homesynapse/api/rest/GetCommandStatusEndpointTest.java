/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.CausalContext;
import com.homesynapse.event.CommandConfirmationTimedOutEvent;
import com.homesynapse.event.CommandDispatchedEvent;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.CommandIdempotency;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateConfirmedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GetCommandStatusEndpoint}.
 *
 * <p>Drives {@code apply(EndpointContext)} through a
 * {@link RecordingEndpointContext} over a {@link FakeEventStore} seeded with
 * hand-built correlation chains. The load-bearing lock is
 * never-false-CONFIRMED (DP-5 / AMD-97-INV-01): a chain with an acknowledged
 * {@code command_result} and NO {@code state_confirmed} must not render
 * CONFIRMED.</p>
 */
@DisplayName("GetCommandStatusEndpoint")
final class GetCommandStatusEndpointTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC);

    private static final String ENTITY_ULID = "01H8000000000000000000000A";
    private static final String CMD_ULID = "01HA000000000000000000CMD1";
    private static final String OTHER_CMD_ULID = "01HA000000000000000000CMD2";
    private static final String DISPATCH_ULID = "01HA000000000000000000DSP1";
    private static final String RESULT_ULID = "01HA000000000000000000RES1";
    private static final String CONFIRM_ULID = "01HA000000000000000000CNF1";
    private static final String TIMEOUT_ULID = "01HA0000000000000000TMT001";
    private static final String REPORT_ULID = "01HA0000000000000000RPT001";
    private static final String INTEGRATION_ULID = "01HA0000000000000000NTG001";

    private static final Instant ISSUED_AT = Instant.parse("2026-07-22T10:00:00Z");
    private static final Instant DISPATCHED_AT = Instant.parse("2026-07-22T10:00:01Z");
    private static final Instant RESULT_AT = Instant.parse("2026-07-22T10:00:02Z");
    private static final Instant TERMINAL_AT = Instant.parse("2026-07-22T10:00:03Z");

    private static final long PROJECTION_CURSOR = 888L;

    private FakeEventStore store;
    private FakeEntityRegistry registry;
    private GetCommandStatusEndpoint endpoint;

    GetCommandStatusEndpointTest() {
    }

    @BeforeEach
    void setUp() {
        store = new FakeEventStore();
        registry = new FakeEntityRegistry()
                .put(IssueCommandEndpointTest.lightEntity(ENTITY_ULID));
        endpoint = new GetCommandStatusEndpoint(store, registry,
                () -> PROJECTION_CURSOR, FIXED_CLOCK);
    }

    @Test
    @DisplayName("unparseable commandId returns 404 COMMAND_NOT_FOUND")
    void unparseableCommandIdIs404() {
        RecordingEndpointContext ctx = get("not-a-ulid");

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(404);
        assertThat(bodyOf(ctx).get("title")).isEqualTo("Command Not Found");
    }

    @Test
    @DisplayName("unknown commandId (empty chain) returns 404 COMMAND_NOT_FOUND")
    void emptyChainIs404() {
        RecordingEndpointContext ctx = get(CMD_ULID);

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(404);
        assertThat(bodyOf(ctx).get("title")).isEqualTo("Command Not Found");
    }

    @Test
    @DisplayName("chain whose command_issued eventId differs from commandId returns 404")
    void mismatchedIssuedEventIdIs404() {
        store.append(envelope(EventTypes.COMMAND_ISSUED, issuedPayload(),
                OTHER_CMD_ULID, CMD_ULID, null, ISSUED_AT, 100));
        RecordingEndpointContext ctx = get(CMD_ULID);

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(404);
    }

    @Test
    @DisplayName("ACCEPTED-only chain is non-terminal with a single lifecycle entry")
    void acceptedOnlyChain() {
        store.append(issued());
        RecordingEndpointContext ctx = get(CMD_ULID);

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        Map<String, Object> data = data(ctx);
        assertThat(data.get("currentPhase")).isEqualTo("ACCEPTED");
        assertThat(data.get("terminal")).isEqualTo(false);
        Map<String, Object> lifecycle = lifecycle(data);
        assertThat(lifecycle.keySet()).containsExactly("ACCEPTED");
        Map<String, Object> accepted = phase(lifecycle, "ACCEPTED");
        assertThat(accepted.keySet()).containsExactly("at", "eventId", "details");
        assertThat(accepted.get("at")).isEqualTo(ISSUED_AT.toString());
        assertThat(accepted.get("eventId")).isEqualTo(CMD_ULID);
        assertThat(accepted.get("details")).isNull();
    }

    @Test
    @DisplayName("command_dispatched adds DISPATCHED with the integration_id detail")
    void dispatchedChain() {
        store.append(issued()).append(dispatched());
        RecordingEndpointContext ctx = get(CMD_ULID);

        endpoint.apply(ctx);

        Map<String, Object> data = data(ctx);
        assertThat(data.get("currentPhase")).isEqualTo("DISPATCHED");
        assertThat(data.get("terminal")).isEqualTo(false);
        Map<String, Object> lifecycle = lifecycle(data);
        assertThat(lifecycle.keySet()).containsExactly("ACCEPTED", "DISPATCHED");
        Map<String, Object> details = details(lifecycle, "DISPATCHED");
        assertThat(details.keySet()).containsExactly("integration_id");
        assertThat(details.get("integration_id")).isEqualTo(INTEGRATION_ULID);
    }

    @Test
    @DisplayName("command_result outcome \"acknowledged\" renders ACKNOWLEDGED, NON-terminal")
    void acknowledgedIsNotTerminal() {
        store.append(issued()).append(dispatched()).append(result("acknowledged"));
        RecordingEndpointContext ctx = get(CMD_ULID);

        endpoint.apply(ctx);

        Map<String, Object> data = data(ctx);
        assertThat(data.get("currentPhase")).isEqualTo("ACKNOWLEDGED");
        assertThat(data.get("terminal")).isEqualTo(false);
        assertThat(details(lifecycle(data), "ACKNOWLEDGED").get("result"))
                .isEqualTo("acknowledged");
    }

    @Test
    @DisplayName("command_result outcome \"rejected\" is terminal at ACKNOWLEDGED, "
            + "result verbatim")
    void rejectedIsTerminal() {
        store.append(issued()).append(dispatched()).append(result("rejected"));
        RecordingEndpointContext ctx = get(CMD_ULID);

        endpoint.apply(ctx);

        Map<String, Object> data = data(ctx);
        assertThat(data.get("currentPhase")).isEqualTo("ACKNOWLEDGED");
        assertThat(data.get("terminal")).isEqualTo(true);
        assertThat(details(lifecycle(data), "ACKNOWLEDGED").get("result"))
                .isEqualTo("rejected");
    }

    @Test
    @DisplayName("disposition outcome \"superseded\" is terminal — proves the "
            + "!= \"acknowledged\" rule, not an outcome enum list")
    void supersededDispositionIsTerminal() {
        store.append(issued()).append(dispatched()).append(result("superseded"));
        RecordingEndpointContext ctx = get(CMD_ULID);

        endpoint.apply(ctx);

        Map<String, Object> data = data(ctx);
        assertThat(data.get("currentPhase")).isEqualTo("ACKNOWLEDGED");
        assertThat(data.get("terminal")).isEqualTo(true);
        assertThat(details(lifecycle(data), "ACKNOWLEDGED").get("result"))
                .isEqualTo("superseded");
    }

    @Test
    @DisplayName("full chain to state_confirmed renders CONFIRMED terminal with match_type")
    void confirmedChainIsTerminal() {
        store.append(issued()).append(dispatched()).append(result("acknowledged"))
                .append(confirmed(CMD_ULID));
        RecordingEndpointContext ctx = get(CMD_ULID);

        endpoint.apply(ctx);

        Map<String, Object> data = data(ctx);
        assertThat(data.get("currentPhase")).isEqualTo("CONFIRMED");
        assertThat(data.get("terminal")).isEqualTo(true);
        Map<String, Object> lifecycle = lifecycle(data);
        assertThat(lifecycle.keySet()).containsExactly(
                "ACCEPTED", "DISPATCHED", "ACKNOWLEDGED", "CONFIRMED");
        Map<String, Object> details = details(lifecycle, "CONFIRMED");
        assertThat(details.keySet()).containsExactly("match_type");
        assertThat(details.get("match_type")).isEqualTo("exact");
    }

    @Test
    @DisplayName("command_confirmation_timed_out renders CONFIRMATION_TIMED_OUT terminal")
    void timedOutChainIsTerminal() {
        store.append(issued()).append(dispatched()).append(timedOut());
        RecordingEndpointContext ctx = get(CMD_ULID);

        endpoint.apply(ctx);

        Map<String, Object> data = data(ctx);
        assertThat(data.get("currentPhase")).isEqualTo("CONFIRMATION_TIMED_OUT");
        assertThat(data.get("terminal")).isEqualTo(true);
        Map<String, Object> phase = phase(lifecycle(data), "CONFIRMATION_TIMED_OUT");
        assertThat(phase.get("eventId")).isEqualTo(TIMEOUT_ULID);
        assertThat(phase.get("details")).isNull();
    }

    @Test
    @DisplayName("NEVER-FALSE-CONFIRMED: an acknowledged result with NO state_confirmed "
            + "must not render CONFIRMED (AMD-97-INV-01)")
    void neverFalseConfirmedLock() {
        store.append(issued()).append(dispatched()).append(result("acknowledged"));
        RecordingEndpointContext ctx = get(CMD_ULID);

        endpoint.apply(ctx);

        Map<String, Object> data = data(ctx);
        assertThat(lifecycle(data)).doesNotContainKey("CONFIRMED");
        assertThat(data.get("currentPhase")).isEqualTo("ACKNOWLEDGED");
        assertThat(data.get("terminal")).isEqualTo(false);
    }

    @Test
    @DisplayName("a state_confirmed for a DIFFERENT command in the chain does not "
            + "render CONFIRMED for this one")
    void foreignConfirmationDoesNotConfirm() {
        store.append(issued()).append(confirmed(OTHER_CMD_ULID));
        RecordingEndpointContext ctx = get(CMD_ULID);

        endpoint.apply(ctx);

        Map<String, Object> data = data(ctx);
        assertThat(lifecycle(data)).doesNotContainKey("CONFIRMED");
        assertThat(data.get("currentPhase")).isEqualTo("ACCEPTED");
        assertThat(data.get("terminal")).isEqualTo(false);
    }

    @Test
    @DisplayName("pins the frozen envelope key sets, UPPERCASE phase tokens, headers, "
            + "and identity fields")
    void pinsWireShape() {
        store.append(issued()).append(dispatched()).append(result("acknowledged"))
                .append(confirmed(CMD_ULID));
        RecordingEndpointContext ctx = get(CMD_ULID);

        endpoint.apply(ctx);

        Map<String, Object> body = bodyOf(ctx);
        assertThat(body.keySet()).containsExactly("data", "meta");
        Map<String, Object> data = data(ctx);
        assertThat(data.keySet()).containsExactly("commandId", "correlationId", "entityId",
                "capability", "command", "lifecycle", "currentPhase", "terminal");
        assertThat(data.get("commandId")).isEqualTo(CMD_ULID);
        assertThat(data.get("correlationId")).isEqualTo(CMD_ULID);
        assertThat(data.get("entityId")).isEqualTo(ENTITY_ULID);
        assertThat(data.get("capability")).isEqualTo("on_off");
        assertThat(data.get("command")).isEqualTo("turn_on");
        for (String key : lifecycle(data).keySet()) {
            assertThat(key).isEqualTo(key.toUpperCase(java.util.Locale.ROOT));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) body.get("meta");
        assertThat(meta.keySet()).containsExactly("viewPosition", "timestamp");
        assertThat(meta.get("viewPosition")).isEqualTo(PROJECTION_CURSOR);
        assertThat(meta.get("timestamp")).isEqualTo("2026-07-22T12:00:00Z");
        assertThat(ctx.headers)
                .containsEntry("Cache-Control", "no-store")
                .containsEntry("X-HomeSynapse-View-Position",
                        Long.toString(PROJECTION_CURSOR));
    }

    @Test
    @DisplayName("registry-absent entity renders capability as null (best-effort derivation)")
    void registryAbsentEntityRendersNullCapability() {
        FakeEventStore isolatedStore = new FakeEventStore().append(issued());
        GetCommandStatusEndpoint isolated = new GetCommandStatusEndpoint(
                isolatedStore, new FakeEntityRegistry(), () -> PROJECTION_CURSOR, FIXED_CLOCK);
        RecordingEndpointContext ctx = get(CMD_ULID);

        isolated.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        Map<String, Object> data = data(ctx);
        assertThat(data.keySet()).contains("capability");
        assertThat(data.get("capability")).isNull();
        assertThat(data.get("command")).isEqualTo("turn_on");
    }

    // ── Chain builders ──────────────────────────────────────────────────

    private static CommandIssuedEvent issuedPayload() {
        return new CommandIssuedEvent(Ulid.parse(ENTITY_ULID), "turn_on", "{}", 15_000,
                CommandIdempotency.IDEMPOTENT);
    }

    private static EventEnvelope issued() {
        return envelope(EventTypes.COMMAND_ISSUED, issuedPayload(),
                CMD_ULID, CMD_ULID, null, ISSUED_AT, 100);
    }

    private static EventEnvelope dispatched() {
        return envelope(EventTypes.COMMAND_DISPATCHED,
                new CommandDispatchedEvent(Ulid.parse(ENTITY_ULID),
                        Ulid.parse(INTEGRATION_ULID), "{}"),
                DISPATCH_ULID, CMD_ULID, CMD_ULID, DISPATCHED_AT, 101);
    }

    private static EventEnvelope result(String outcome) {
        return envelope(EventTypes.COMMAND_RESULT,
                new CommandResultEvent(Ulid.parse(ENTITY_ULID), "turn_on", outcome, null),
                RESULT_ULID, CMD_ULID, CMD_ULID, RESULT_AT, 102);
    }

    /**
     * A state_confirmed whose causation is the REPORT event (the live ledger
     * convention) — the endpoint must link it by payload
     * {@code commandEventId}, never by causation.
     */
    private static EventEnvelope confirmed(String forCommandUlid) {
        return envelope(EventTypes.STATE_CONFIRMED,
                new StateConfirmedEvent(new EventId(Ulid.parse(forCommandUlid)),
                        new EventId(Ulid.parse(REPORT_ULID)), "on", "true", "true", "exact"),
                CONFIRM_ULID, CMD_ULID, REPORT_ULID, TERMINAL_AT, 103);
    }

    private static EventEnvelope timedOut() {
        return envelope(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT,
                new CommandConfirmationTimedOutEvent(new EventId(Ulid.parse(CMD_ULID)), null),
                TIMEOUT_ULID, CMD_ULID, CMD_ULID, TERMINAL_AT, 103);
    }

    private static EventEnvelope envelope(String eventType, DomainEvent payload,
                                          String eventId, String correlationId,
                                          String causationId, Instant ingestTime,
                                          long position) {
        EventId id = new EventId(Ulid.parse(eventId));
        CausalContext context = causationId == null
                ? CausalContext.root(Ulid.parse(correlationId))
                : CausalContext.chain(Ulid.parse(correlationId), Ulid.parse(causationId));
        return new EventEnvelope(id, eventType, 1, ingestTime, null,
                SubjectRef.entity(EntityId.of(Ulid.parse(ENTITY_ULID))), 1L, position,
                EventPriority.NORMAL, EventOrigin.USER_COMMAND,
                List.of(EventCategory.DEVICE_STATE), context, null, payload);
    }

    // ── Assertion helpers ───────────────────────────────────────────────

    private static RecordingEndpointContext get(String commandId) {
        return new RecordingEndpointContext().withPathParam("commandId", commandId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(RecordingEndpointContext ctx) {
        assertThat(ctx.body).as("response body").isNotNull();
        return (Map<String, Object>) ctx.body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(RecordingEndpointContext ctx) {
        assertThat(ctx.statusSet).isEqualTo(200);
        return (Map<String, Object>) bodyOf(ctx).get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lifecycle(Map<String, Object> data) {
        return (Map<String, Object>) data.get("lifecycle");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> phase(Map<String, Object> lifecycle, String key) {
        Map<String, Object> phase = (Map<String, Object>) lifecycle.get(key);
        assertThat(phase).as("lifecycle phase " + key).isNotNull();
        return phase;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(Map<String, Object> lifecycle, String key) {
        Map<String, Object> details = (Map<String, Object>) phase(lifecycle, key).get("details");
        assertThat(details).as("details of phase " + key).isNotNull();
        return details;
    }
}
