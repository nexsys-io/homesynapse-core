/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.test;

import com.homesynapse.event.CausalContext;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.UlidFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Abstract contract test for {@link EventPublisher} and {@link EventStore}.
 *
 * <p>Defines the behavioral contract that both {@code InMemoryEventStore} and
 * {@code SQLiteEventStore} must satisfy. Subclasses provide the implementation
 * via the three abstract factory methods. All tests use these factory methods
 * and never construct implementations directly.</p>
 *
 * <p>The contract validated here covers:</p>
 * <ul>
 *   <li>Append and read-back round trips</li>
 *   <li>Global position ordering and monotonicity</li>
 *   <li>Per-subject sequence ordering</li>
 *   <li>Causality context propagation (root and derived events)</li>
 *   <li>All six EventStore query methods</li>
 *   <li>Pagination via EventPage</li>
 *   <li>Parameter validation (IllegalArgumentException)</li>
 *   <li>Publisher-assigned fields (eventId, ingestTime, subjectSequence,
 *       globalPosition, categories)</li>
 * </ul>
 *
 * <p>Subclasses must call {@link #resetStore()} in {@code @BeforeEach} to
 * ensure test isolation. This abstract class calls it automatically.</p>
 *
 * @see EventPublisher
 * @see EventStore
 * @see EventPage
 */
@DisplayName("EventStore Contract")
public abstract class EventStoreContractTest {

    /** Subclass constructor. */
    protected EventStoreContractTest() {
        // Abstract — subclasses provide implementation.
    }

    /**
     * Returns the EventPublisher under test.
     * Called by every test method — must return a consistent instance
     * within a single test execution.
     */
    protected abstract EventPublisher publisher();

    /**
     * Returns the EventStore under test.
     * Must be backed by the same storage as {@link #publisher()}.
     */
    protected abstract EventStore store();

    /**
     * Resets the store to an empty state for test isolation.
     * Called in {@link #setUp()}.
     */
    protected abstract void resetStore();

    @BeforeEach
    void setUp() {
        resetStore();
    }

    // ──────────────────────────────────────────────────────────────────
    // Test payload and helpers
    // ──────────────────────────────────────────────────────────────────

    /**
     * A minimal DomainEvent implementation for contract tests.
     * DomainEvent is a pure marker interface — no methods required.
     */
    record TestPayload(String value) implements DomainEvent {}

    /**
     * Creates a basic EventDraft for the given subject with NORMAL priority.
     */
    protected EventDraft draftFor(SubjectRef subject, String eventType) {
        return new EventDraft(
                eventType,
                1,
                null,
                subject,
                EventPriority.NORMAL,
                EventOrigin.SYSTEM,
                new TestPayload("test"),
                null
        );
    }

    /**
     * Creates an EventDraft with an explicit eventTime.
     */
    protected EventDraft draftWithTime(SubjectRef subject, String eventType,
                                       Instant eventTime) {
        return new EventDraft(
                eventType,
                1,
                eventTime,
                subject,
                EventPriority.NORMAL,
                EventOrigin.SYSTEM,
                new TestPayload("test"),
                null
        );
    }

    /**
     * Creates a SubjectRef for a unique test entity using UlidFactory.
     */
    protected SubjectRef testSubject() {
        return SubjectRef.entity(new EntityId(UlidFactory.generate()));
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 1: Basic append and read-back
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("publishRoot returns envelope with publisher-assigned fields")
    void publishRoot_returnsEnvelopeWithPublisherAssignedFields()
            throws SequenceConflictException {
        var subject = testSubject();
        var draft = draftFor(subject, "test_event");

        EventEnvelope envelope = publisher().publishRoot(draft);

        assertThat(envelope.eventId()).isNotNull();
        assertThat(envelope.ingestTime()).isNotNull();
        assertThat(envelope.subjectSequence()).isGreaterThanOrEqualTo(1L);
        assertThat(envelope.globalPosition()).isGreaterThanOrEqualTo(1L);
        assertThat(envelope.categories()).isNotNull().isNotEmpty();

        assertThat(envelope.eventType()).isEqualTo("test_event");
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        assertThat(envelope.subjectRef()).isEqualTo(subject);
        assertThat(envelope.priority()).isEqualTo(EventPriority.NORMAL);
        assertThat(envelope.origin()).isEqualTo(EventOrigin.SYSTEM);
        assertThat(envelope.payload()).isInstanceOf(TestPayload.class);
    }

    @Test
    @DisplayName("publishRoot sets root causal context")
    void publishRoot_setsRootCausalContext()
            throws SequenceConflictException {
        var envelope = publisher().publishRoot(
                draftFor(testSubject(), "test_event"));

        assertThat(envelope.causalContext()).isNotNull();
        assertThat(envelope.causalContext().correlationId())
                .isEqualTo(envelope.eventId().value());
        assertThat(envelope.causalContext().causationId()).isNull();
        assertThat(envelope.causalContext().isRoot()).isTrue();
    }

    @Test
    @DisplayName("publish sets derived causal context")
    void publish_setsDerivedCausalContext()
            throws SequenceConflictException {
        var root = publisher().publishRoot(
                draftFor(testSubject(), "root_event"));
        var cause = CausalContext.chain(
                root.causalContext().correlationId(),
                root.eventId().value());

        var derived = publisher().publish(
                draftFor(testSubject(), "derived_event"), cause);

        assertThat(derived.causalContext().correlationId())
                .isEqualTo(root.causalContext().correlationId());
        assertThat(derived.causalContext().causationId())
                .isEqualTo(root.eventId().value());
        assertThat(derived.causalContext().isRoot()).isFalse();
    }

    @Test
    @DisplayName("published event is readable via store")
    void publishedEvent_readableViaStore()
            throws SequenceConflictException {
        var envelope = publisher().publishRoot(
                draftFor(testSubject(), "test_event"));

        EventPage page = store().readFrom(0, 10);

        assertThat(page.events()).hasSize(1);
        assertThat(page.events().get(0).eventId())
                .isEqualTo(envelope.eventId());
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 2: Global position ordering
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("global position increases monotonically")
    void globalPosition_monotonicallyIncreases()
            throws SequenceConflictException {
        var s1 = testSubject();
        var s2 = testSubject();

        var e1 = publisher().publishRoot(draftFor(s1, "event_1"));
        var e2 = publisher().publishRoot(draftFor(s2, "event_2"));
        var e3 = publisher().publishRoot(draftFor(s1, "event_3"));

        assertThat(e1.globalPosition()).isLessThan(e2.globalPosition());
        assertThat(e2.globalPosition()).isLessThan(e3.globalPosition());
    }

    @Test
    @DisplayName("readFrom returns events in global position order")
    void readFrom_returnsEventsInGlobalPositionOrder()
            throws SequenceConflictException {
        var s1 = testSubject();
        var s2 = testSubject();

        publisher().publishRoot(draftFor(s1, "event_1"));
        publisher().publishRoot(draftFor(s2, "event_2"));
        publisher().publishRoot(draftFor(s1, "event_3"));

        EventPage page = store().readFrom(0, 10);

        assertThat(page.events()).hasSize(3);
        assertThat(page.events().get(0).eventType()).isEqualTo("event_1");
        assertThat(page.events().get(1).eventType()).isEqualTo("event_2");
        assertThat(page.events().get(2).eventType()).isEqualTo("event_3");
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 3: Per-subject sequence ordering
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("subject sequence increments per subject")
    void subjectSequence_incrementsPerSubject()
            throws SequenceConflictException {
        var subject = testSubject();

        var e1 = publisher().publishRoot(draftFor(subject, "event_1"));
        var e2 = publisher().publishRoot(draftFor(subject, "event_2"));

        assertThat(e1.subjectSequence()).isEqualTo(1L);
        assertThat(e2.subjectSequence()).isEqualTo(2L);
    }

    @Test
    @DisplayName("subject sequence is independent across subjects")
    void subjectSequence_independentAcrossSubjects()
            throws SequenceConflictException {
        var s1 = testSubject();
        var s2 = testSubject();

        var e1 = publisher().publishRoot(draftFor(s1, "event_1"));
        var e2 = publisher().publishRoot(draftFor(s2, "event_2"));

        assertThat(e1.subjectSequence()).isEqualTo(1L);
        assertThat(e2.subjectSequence()).isEqualTo(1L);
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 4: readFrom pagination
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("readFrom respects maxCount limit")
    void readFrom_respectsMaxCount()
            throws SequenceConflictException {
        var subject = testSubject();
        for (int i = 0; i < 5; i++) {
            publisher().publishRoot(draftFor(subject, "event_" + i));
        }

        EventPage page = store().readFrom(0, 3);

        assertThat(page.events()).hasSize(3);
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    @DisplayName("readFrom paginates correctly across multiple pages")
    void readFrom_paginatesCorrectly()
            throws SequenceConflictException {
        var subject = testSubject();
        for (int i = 0; i < 5; i++) {
            publisher().publishRoot(draftFor(subject, "event_" + i));
        }

        EventPage page1 = store().readFrom(0, 3);
        EventPage page2 = store().readFrom(page1.nextPosition(), 3);

        assertThat(page1.events()).hasSize(3);
        assertThat(page2.events()).hasSize(2);
        assertThat(page2.hasMore()).isFalse();
    }

    @Test
    @DisplayName("readFrom after position filters correctly")
    void readFrom_afterPositionFiltersCorrectly()
            throws SequenceConflictException {
        var subject = testSubject();
        var e1 = publisher().publishRoot(draftFor(subject, "event_1"));
        var e2 = publisher().publishRoot(draftFor(subject, "event_2"));

        EventPage page = store().readFrom(e1.globalPosition(), 10);

        assertThat(page.events()).hasSize(1);
        assertThat(page.events().get(0).eventId()).isEqualTo(e2.eventId());
    }

    @Test
    @DisplayName("readFrom on empty store returns empty page")
    void readFrom_emptyStoreReturnsEmptyPage() {
        EventPage page = store().readFrom(0, 10);

        assertThat(page.events()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 5: readBySubject
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("readBySubject filters to correct subject")
    void readBySubject_filtersToCorrectSubject()
            throws SequenceConflictException {
        var s1 = testSubject();
        var s2 = testSubject();

        publisher().publishRoot(draftFor(s1, "s1_event"));
        publisher().publishRoot(draftFor(s2, "s2_event"));
        publisher().publishRoot(draftFor(s1, "s1_event_2"));

        EventPage page = store().readBySubject(s1, 0, 10);

        assertThat(page.events()).hasSize(2);
        assertThat(page.events()).allSatisfy(e ->
                assertThat(e.subjectRef()).isEqualTo(s1));
    }

    @Test
    @DisplayName("readBySubject returns events ordered by subject sequence")
    void readBySubject_orderedBySubjectSequence()
            throws SequenceConflictException {
        var subject = testSubject();

        publisher().publishRoot(draftFor(subject, "event_1"));
        publisher().publishRoot(draftFor(subject, "event_2"));
        publisher().publishRoot(draftFor(subject, "event_3"));

        EventPage page = store().readBySubject(subject, 0, 10);

        assertThat(page.events()).hasSize(3);
        assertThat(page.events().get(0).subjectSequence()).isEqualTo(1L);
        assertThat(page.events().get(1).subjectSequence()).isEqualTo(2L);
        assertThat(page.events().get(2).subjectSequence()).isEqualTo(3L);
    }

    @Test
    @DisplayName("readBySubject after sequence filters correctly")
    void readBySubject_afterSequenceFilters()
            throws SequenceConflictException {
        var subject = testSubject();

        publisher().publishRoot(draftFor(subject, "event_1"));
        publisher().publishRoot(draftFor(subject, "event_2"));
        publisher().publishRoot(draftFor(subject, "event_3"));

        EventPage page = store().readBySubject(subject, 1, 10);

        assertThat(page.events()).hasSize(2);
        assertThat(page.events().get(0).subjectSequence()).isEqualTo(2L);
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 6: readByCorrelation
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("readByCorrelation returns full causal chain")
    void readByCorrelation_returnsFullCausalChain()
            throws SequenceConflictException {
        var subject = testSubject();

        var root = publisher().publishRoot(draftFor(subject, "root"));
        var cause1 = CausalContext.chain(
                root.causalContext().correlationId(),
                root.eventId().value());
        var derived1 = publisher().publish(
                draftFor(subject, "derived_1"), cause1);
        var cause2 = CausalContext.chain(
                root.causalContext().correlationId(),
                derived1.eventId().value());
        publisher().publish(draftFor(subject, "derived_2"), cause2);

        List<EventEnvelope> chain = store().readByCorrelation(
                root.causalContext().correlationId());

        assertThat(chain).hasSize(3);
        assertThat(chain.get(0).eventType()).isEqualTo("root");
        assertThat(chain.get(1).eventType()).isEqualTo("derived_1");
        assertThat(chain.get(2).eventType()).isEqualTo("derived_2");
    }

    @Test
    @DisplayName("readByCorrelation returns empty for unknown correlation id")
    void readByCorrelation_returnsEmptyForUnknownId() {
        List<EventEnvelope> chain = store().readByCorrelation(
                UlidFactory.generate());

        assertThat(chain).isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 7: readByType
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("readByType filters to matching event type")
    void readByType_filtersCorrectly()
            throws SequenceConflictException {
        var subject = testSubject();

        publisher().publishRoot(draftFor(subject, "state_changed"));
        publisher().publishRoot(draftFor(subject, "state_reported"));
        publisher().publishRoot(draftFor(subject, "state_changed"));

        EventPage page = store().readByType("state_changed", 0, 10);

        assertThat(page.events()).hasSize(2);
        assertThat(page.events()).allSatisfy(e ->
                assertThat(e.eventType()).isEqualTo("state_changed"));
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 8: readByTimeRange
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("readByTimeRange filters within range boundaries")
    void readByTimeRange_filtersWithinRange()
            throws SequenceConflictException {
        var subject = testSubject();
        var t1 = Instant.parse("2026-01-01T10:00:00Z");
        var t2 = Instant.parse("2026-01-01T11:00:00Z");
        var t3 = Instant.parse("2026-01-01T12:00:00Z");

        publisher().publishRoot(draftWithTime(subject, "event_1", t1));
        publisher().publishRoot(draftWithTime(subject, "event_2", t2));
        publisher().publishRoot(draftWithTime(subject, "event_3", t3));

        EventPage page = store().readByTimeRange(
                Instant.parse("2026-01-01T10:30:00Z"),
                Instant.parse("2026-01-01T12:00:00Z"),
                0, 10);

        assertThat(page.events()).hasSize(1);
        assertThat(page.events().get(0).eventType()).isEqualTo("event_2");
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 9: latestPosition
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("latestPosition is zero when store is empty")
    void latestPosition_zeroWhenEmpty() {
        assertThat(store().latestPosition()).isEqualTo(0L);
    }

    @Test
    @DisplayName("latestPosition matches last appended event")
    void latestPosition_matchesLastAppendedEvent()
            throws SequenceConflictException {
        var subject = testSubject();
        publisher().publishRoot(draftFor(subject, "event_1"));
        var last = publisher().publishRoot(draftFor(subject, "event_2"));

        assertThat(store().latestPosition())
                .isEqualTo(last.globalPosition());
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 10: Parameter validation
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("readFrom rejects negative afterPosition")
    void readFrom_rejectsNegativePosition() {
        assertThatThrownBy(() -> store().readFrom(-1, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("readFrom rejects zero maxCount")
    void readFrom_rejectsZeroMaxCount() {
        assertThatThrownBy(() -> store().readFrom(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("readBySubject rejects null subject")
    void readBySubject_rejectsNullSubject() {
        assertThatThrownBy(() -> store().readBySubject(null, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("readByCorrelation rejects null correlation id")
    void readByCorrelation_rejectsNullCorrelationId() {
        assertThatThrownBy(() -> store().readByCorrelation(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("readByType rejects null event type")
    void readByType_rejectsNullEventType() {
        assertThatThrownBy(() -> store().readByType(null, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("readByTimeRange rejects from after to")
    void readByTimeRange_rejectsFromAfterTo() {
        var later = Instant.parse("2026-01-02T00:00:00Z");
        var earlier = Instant.parse("2026-01-01T00:00:00Z");

        assertThatThrownBy(() ->
                store().readByTimeRange(later, earlier, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
