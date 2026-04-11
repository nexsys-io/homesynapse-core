/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.test;

/**
 * Canonical {@code eventType} string constants for test-fixture event payloads.
 *
 * <p>This mirrors {@code com.homesynapse.event.EventTypes} in the main event-model
 * source set: the {@code @EventType} annotation's {@code value()} must reference a
 * constant, never a string literal (LTD-19 / DECIDE-M2-01). Test-only payload
 * records such as {@link EventStoreContractTest.TestPayload} carry
 * {@code @EventType(TestEventTypes.TEST_EVENT)} and the persistence layer uses
 * the same constant when wiring the {@code EventTypeRegistry} for contract
 * tests that exercise the SQLite round trip.</p>
 *
 * <p><strong>Fixture scope:</strong> Only fixture-private constants go here.
 * Production event types live in {@code com.homesynapse.event.EventTypes} and
 * must not be duplicated — duplication would silently break the
 * {@code EventTypeRegistry}'s unique-type-string validation.</p>
 *
 * @see EventStoreContractTest.TestPayload
 * @see com.homesynapse.event.EventType
 */
public final class TestEventTypes {

    /**
     * Canonical event type string for {@link EventStoreContractTest.TestPayload}.
     *
     * <p>Chosen to match the literal string the contract test already publishes
     * in its {@code draftFor(...)} helper calls ({@code "test_event"}), so that
     * {@code @EventType(TEST_EVENT)} and the draft-level {@code eventType}
     * field agree. The contract test also publishes drafts with other type
     * strings ({@code "event_1"}, {@code "state_changed"}, etc.) — those are
     * free-form type discriminators stored in the {@code event_type} column,
     * and the persistence layer falls back to {@code DegradedEvent} for
     * unregistered types during decode. The registry only needs this single
     * constant registered so that round-trip tests of the test payload itself
     * succeed.</p>
     */
    public static final String TEST_EVENT = "test_event";

    private TestEventTypes() {
        // Utility class — non-instantiable
    }
}
