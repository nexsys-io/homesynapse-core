/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.event.CausalContext;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.SubjectType;
import com.homesynapse.event.bus.test.MinimalEventBusStub;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link NotifyingEventPublisher}.
 *
 * <p>Verifies the decorator's core contract: delegate-then-notify on success,
 * no notification on {@link SequenceConflictException}.</p>
 */
class NotifyingEventPublisherTest {

    private static final Ulid TEST_ULID = new Ulid(1L, 1L);
    private static final SubjectRef TEST_SUBJECT =
            new SubjectRef(TEST_ULID, SubjectType.ENTITY);
    private static final Instant TEST_TIME = Instant.parse("2026-05-26T12:00:00Z");

    // ── Test doubles ───────────────────────────────────────────────────

    private record StubPayload() implements DomainEvent {}

    /**
     * Stub publisher that returns a canned envelope with a known global
     * position, or throws {@link SequenceConflictException} on request.
     */
    private static final class StubPublisher implements EventPublisher {

        private final long globalPosition;
        private final boolean shouldThrow;
        private int publishCallCount;
        private int publishRootCallCount;

        StubPublisher(long globalPosition, boolean shouldThrow) {
            this.globalPosition = globalPosition;
            this.shouldThrow = shouldThrow;
        }

        @Override
        public EventEnvelope publish(EventDraft draft, CausalContext cause)
                throws SequenceConflictException {
            publishCallCount++;
            if (shouldThrow) {
                throw new SequenceConflictException(TEST_SUBJECT, 1L);
            }
            return cannedEnvelope(globalPosition);
        }

        @Override
        public EventEnvelope publishRoot(EventDraft draft)
                throws SequenceConflictException {
            publishRootCallCount++;
            if (shouldThrow) {
                throw new SequenceConflictException(TEST_SUBJECT, 1L);
            }
            return cannedEnvelope(globalPosition);
        }
    }

    /**
     * Recording bus that captures {@code notifyEvent} calls. Extends
     * {@link MinimalEventBusStub} so subscribe/unsubscribe/subscriberPosition
     * inherit the canonical no-op defaults; this class only adds the
     * notify-recording behaviour the test asserts on.
     */
    private static final class RecordingBus extends MinimalEventBusStub {

        final List<Long> notifiedPositions = new ArrayList<>();

        RecordingBus() {
            super();
        }

        @Override
        public void notifyEvent(long globalPosition) {
            notifiedPositions.add(globalPosition);
        }
    }

    // ── Tests ──────────────────────────────────────────────────────────

    @Test
    void publishDelegatesToUnderlyingAndNotifiesBus()
            throws SequenceConflictException {
        var stub = new StubPublisher(42L, false);
        var bus = new RecordingBus();
        var decorator = new NotifyingEventPublisher(stub, bus);

        EventEnvelope result = decorator.publish(stubDraft(), stubCause());

        assertThat(stub.publishCallCount).isEqualTo(1);
        assertThat(result.globalPosition()).isEqualTo(42L);
        assertThat(bus.notifiedPositions).containsExactly(42L);
    }

    @Test
    void publishRootDelegatesToUnderlyingAndNotifiesBus()
            throws SequenceConflictException {
        var stub = new StubPublisher(99L, false);
        var bus = new RecordingBus();
        var decorator = new NotifyingEventPublisher(stub, bus);

        EventEnvelope result = decorator.publishRoot(stubDraft());

        assertThat(stub.publishRootCallCount).isEqualTo(1);
        assertThat(result.globalPosition()).isEqualTo(99L);
        assertThat(bus.notifiedPositions).containsExactly(99L);
    }

    @Test
    void publishDoesNotNotifyOnSequenceConflict() {
        var stub = new StubPublisher(0L, true);
        var bus = new RecordingBus();
        var decorator = new NotifyingEventPublisher(stub, bus);

        assertThatThrownBy(() -> decorator.publish(stubDraft(), stubCause()))
                .isInstanceOf(SequenceConflictException.class);

        assertThat(stub.publishCallCount).isEqualTo(1);
        assertThat(bus.notifiedPositions).isEmpty();
    }

    @Test
    void publishRootDoesNotNotifyOnSequenceConflict() {
        var stub = new StubPublisher(0L, true);
        var bus = new RecordingBus();
        var decorator = new NotifyingEventPublisher(stub, bus);

        assertThatThrownBy(() -> decorator.publishRoot(stubDraft()))
                .isInstanceOf(SequenceConflictException.class);

        assertThat(stub.publishRootCallCount).isEqualTo(1);
        assertThat(bus.notifiedPositions).isEmpty();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static EventEnvelope cannedEnvelope(long globalPosition) {
        return new EventEnvelope(
                EventId.of(TEST_ULID),
                "test_event",
                1,
                TEST_TIME,
                null,
                TEST_SUBJECT,
                1L,
                globalPosition,
                EventPriority.NORMAL,
                EventOrigin.SYSTEM,
                List.of(EventCategory.SYSTEM),
                CausalContext.root(TEST_ULID),
                null,
                new StubPayload());
    }

    private static EventDraft stubDraft() {
        return new EventDraft(
                "test_event",
                1,
                null,
                TEST_SUBJECT,
                EventPriority.NORMAL,
                EventOrigin.SYSTEM,
                new StubPayload(),
                null,
                null);
    }

    private static CausalContext stubCause() {
        return CausalContext.root(TEST_ULID);
    }
}
