// HomeSynapse Core / Copyright (c) 2026 NexSys. All rights reserved.
package com.homesynapse.event.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.homesynapse.event.CausalContext;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.SubjectType;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.SystemId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SubscriptionFilter} — the most critical type in event-bus.
 *
 * <p>Exercises all three filter dimensions (event type set, minimum priority,
 * subject type) individually and in combination, plus construction validation,
 * defensive copy, factory methods, and record structure verification.</p>
 */
@DisplayName("SubscriptionFilter")
class SubscriptionFilterTest {

    // ── Deterministic test fixtures ──────────────────────────────────────

    private static final Ulid TEST_ULID_1 = new Ulid(1L, 1L);
    private static final Ulid TEST_ULID_2 = new Ulid(2L, 2L);
    private static final Ulid TEST_ULID_3 = new Ulid(3L, 3L);

    private static final EventId EVENT_ID = EventId.of(TEST_ULID_1);
    private static final SubjectRef ENTITY_SUBJECT =
            SubjectRef.entity(EntityId.of(TEST_ULID_2));
    private static final SubjectRef DEVICE_SUBJECT =
            SubjectRef.device(DeviceId.of(TEST_ULID_2));
    private static final SubjectRef SYSTEM_SUBJECT =
            SubjectRef.system(SystemId.of(TEST_ULID_2));
    private static final CausalContext CAUSAL_CONTEXT = CausalContext.root(TEST_ULID_1);
    private static final Instant NOW = Instant.parse("2026-03-15T10:00:00Z");

    /** Simple DomainEvent implementation for testing. */
    private record TestPayload(String data) implements DomainEvent {}

    private static final DomainEvent PAYLOAD = new TestPayload("test");

    /**
     * Builds an EventEnvelope with the specified eventType, priority, and subjectRef.
     * All other fields use valid defaults.
     */
    private static EventEnvelope envelope(String eventType, EventPriority priority,
                                          SubjectRef subjectRef) {
        return new EventEnvelope(
                EVENT_ID,
                eventType,
                1,
                NOW,
                null,
                subjectRef,
                1L,
                100L,
                priority,
                EventOrigin.PHYSICAL,
                List.of(EventCategory.DEVICE_STATE),
                CAUSAL_CONTEXT,
                TEST_ULID_3,
                PAYLOAD
        );
    }

    /** Shorthand: builds an envelope with ENTITY subject and the given type/priority. */
    private static EventEnvelope envelope(String eventType, EventPriority priority) {
        return envelope(eventType, priority, ENTITY_SUBJECT);
    }

    // ── Construction and field access ────────────────────────────────────

    @Nested
    @DisplayName("Construction and field access")
    class ConstructionTests {

        @Test
        @DisplayName("all fields are accessible and return correct values")
        void allFieldsAccessible() {
            var types = Set.of("device.state_changed", "device.command_issued");
            var filter = new SubscriptionFilter(types, EventPriority.NORMAL, SubjectType.ENTITY);

            assertThat(filter.eventTypes()).isEqualTo(types);
            assertThat(filter.minimumPriority()).isEqualTo(EventPriority.NORMAL);
            assertThat(filter.subjectTypeFilter()).isEqualTo(SubjectType.ENTITY);
        }

        @Test
        @DisplayName("exactly 3 record components")
        void exactlyThreeFields() {
            assertThat(SubscriptionFilter.class.getRecordComponents()).hasSize(3);
        }

        @Test
        @DisplayName("empty eventTypes accepted — wildcard semantics")
        void emptyEventTypesAccepted() {
            var filter = new SubscriptionFilter(Set.of(), EventPriority.DIAGNOSTIC, null);

            assertThat(filter.eventTypes()).isEmpty();
        }

        @Test
        @DisplayName("null subjectTypeFilter accepted — matches all subject types")
        void nullSubjectTypeFilterAccepted() {
            var filter = new SubscriptionFilter(Set.of(), EventPriority.DIAGNOSTIC, null);

            assertThat(filter.subjectTypeFilter()).isNull();
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null eventTypes throws NullPointerException")
        void nullEventTypes() {
            assertThatNullPointerException().isThrownBy(() ->
                    new SubscriptionFilter(null, EventPriority.NORMAL, null))
                    .withMessageContaining("eventTypes");
        }

        @Test
        @DisplayName("null minimumPriority throws NullPointerException")
        void nullMinimumPriority() {
            assertThatNullPointerException().isThrownBy(() ->
                    new SubscriptionFilter(Set.of(), null, null))
                    .withMessageContaining("minimumPriority");
        }
    }

    // ── Defensive copy of eventTypes ─────────────────────────────────────

    @Nested
    @DisplayName("eventTypes defensive copy")
    class DefensiveCopyTests {

        @Test
        @DisplayName("eventTypes is defensively copied — external mutation has no effect")
        void externalMutationBlocked() {
            var mutable = new HashSet<>(Set.of("device.state_changed"));
            var filter = new SubscriptionFilter(mutable, EventPriority.NORMAL, null);

            mutable.add("device.command_issued");

            assertThat(filter.eventTypes()).hasSize(1);
            assertThat(filter.eventTypes()).containsExactly("device.state_changed");
        }

        @Test
        @DisplayName("eventTypes is immutable — modification throws UnsupportedOperationException")
        void eventTypesImmutable() {
            var filter = new SubscriptionFilter(
                    Set.of("device.state_changed"), EventPriority.NORMAL, null);

            assertThatThrownBy(() -> filter.eventTypes().add("new.type"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ── Factory methods ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("all() creates permissive filter — empty types, DIAGNOSTIC, null subject")
        void allFactory() {
            var filter = SubscriptionFilter.all();

            assertThat(filter.eventTypes()).isEmpty();
            assertThat(filter.minimumPriority()).isEqualTo(EventPriority.DIAGNOSTIC);
            assertThat(filter.subjectTypeFilter()).isNull();
        }

        @Test
        @DisplayName("forTypes() sets event types with DIAGNOSTIC priority and null subject")
        void forTypesFactory() {
            var filter = SubscriptionFilter.forTypes(
                    "device.state_changed", "device.command_issued");

            assertThat(filter.eventTypes()).containsExactlyInAnyOrder(
                    "device.state_changed", "device.command_issued");
            assertThat(filter.minimumPriority()).isEqualTo(EventPriority.DIAGNOSTIC);
            assertThat(filter.subjectTypeFilter()).isNull();
        }

        @Test
        @DisplayName("forPriority() sets priority with empty types and null subject")
        void forPriorityFactory() {
            var filter = SubscriptionFilter.forPriority(EventPriority.CRITICAL);

            assertThat(filter.eventTypes()).isEmpty();
            assertThat(filter.minimumPriority()).isEqualTo(EventPriority.CRITICAL);
            assertThat(filter.subjectTypeFilter()).isNull();
        }

        @Test
        @DisplayName("forTypes() with null array throws NullPointerException")
        void forTypesNullThrows() {
            assertThatNullPointerException().isThrownBy(() ->
                    SubscriptionFilter.forTypes((String[]) null));
        }

        @Test
        @DisplayName("forPriority() with null throws NullPointerException")
        void forPriorityNullThrows() {
            assertThatNullPointerException().isThrownBy(() ->
                    SubscriptionFilter.forPriority(null));
        }
    }

    // ── matches() — event type filtering ─────────────────────────────────

    @Nested
    @DisplayName("matches() — event type filtering")
    class EventTypeFilterTests {

        @Test
        @DisplayName("empty eventTypes (wildcard) matches any event type")
        void emptyEventTypesMatchesAll() {
            var filter = new SubscriptionFilter(Set.of(), EventPriority.DIAGNOSTIC, null);

            assertThat(filter.matches(envelope("device.state_changed", EventPriority.NORMAL)))
                    .isTrue();
            assertThat(filter.matches(envelope("automation.triggered", EventPriority.NORMAL)))
                    .isTrue();
            assertThat(filter.matches(envelope("system.started", EventPriority.CRITICAL)))
                    .isTrue();
        }

        @Test
        @DisplayName("populated eventTypes matches only listed types")
        void populatedEventTypesMatchesListed() {
            var filter = new SubscriptionFilter(
                    Set.of("device.state_changed", "device.command_issued"),
                    EventPriority.DIAGNOSTIC, null);

            assertThat(filter.matches(envelope("device.state_changed", EventPriority.NORMAL)))
                    .isTrue();
            assertThat(filter.matches(envelope("device.command_issued", EventPriority.NORMAL)))
                    .isTrue();
        }

        @Test
        @DisplayName("populated eventTypes rejects unlisted type")
        void populatedEventTypesRejectsUnlisted() {
            var filter = new SubscriptionFilter(
                    Set.of("device.state_changed"), EventPriority.DIAGNOSTIC, null);

            assertThat(filter.matches(envelope("automation.triggered", EventPriority.NORMAL)))
                    .isFalse();
        }
    }

    // ── matches() — priority filtering ───────────────────────────────────

    @Nested
    @DisplayName("matches() — priority filtering")
    class PriorityFilterTests {

        @Test
        @DisplayName("DIAGNOSTIC minimum accepts all priorities (CRITICAL=0, NORMAL=1, DIAGNOSTIC=2)")
        void diagnosticMinimumAcceptsAll() {
            var filter = new SubscriptionFilter(
                    Set.of(), EventPriority.DIAGNOSTIC, null);

            assertThat(filter.matches(envelope("t", EventPriority.CRITICAL))).isTrue();
            assertThat(filter.matches(envelope("t", EventPriority.NORMAL))).isTrue();
            assertThat(filter.matches(envelope("t", EventPriority.DIAGNOSTIC))).isTrue();
        }

        @Test
        @DisplayName("NORMAL minimum accepts CRITICAL and NORMAL, rejects DIAGNOSTIC")
        void normalMinimumAcceptsCriticalAndNormal() {
            var filter = new SubscriptionFilter(
                    Set.of(), EventPriority.NORMAL, null);

            assertThat(filter.matches(envelope("t", EventPriority.CRITICAL))).isTrue();
            assertThat(filter.matches(envelope("t", EventPriority.NORMAL))).isTrue();
            assertThat(filter.matches(envelope("t", EventPriority.DIAGNOSTIC))).isFalse();
        }

        @Test
        @DisplayName("CRITICAL minimum accepts only CRITICAL, rejects NORMAL and DIAGNOSTIC")
        void criticalMinimumAcceptsOnlyCritical() {
            var filter = new SubscriptionFilter(
                    Set.of(), EventPriority.CRITICAL, null);

            assertThat(filter.matches(envelope("t", EventPriority.CRITICAL))).isTrue();
            assertThat(filter.matches(envelope("t", EventPriority.NORMAL))).isFalse();
            assertThat(filter.matches(envelope("t", EventPriority.DIAGNOSTIC))).isFalse();
        }

        @Test
        @DisplayName("severity() values are CRITICAL=0, NORMAL=1, DIAGNOSTIC=2")
        void severityValuesAreCorrect() {
            assertThat(EventPriority.CRITICAL.severity()).isZero();
            assertThat(EventPriority.NORMAL.severity()).isEqualTo(1);
            assertThat(EventPriority.DIAGNOSTIC.severity()).isEqualTo(2);
        }
    }

    // ── matches() — subject type filtering ───────────────────────────────

    @Nested
    @DisplayName("matches() — subject type filtering")
    class SubjectTypeFilterTests {

        @Test
        @DisplayName("null subjectTypeFilter matches all subject types")
        void nullSubjectTypeMatchesAll() {
            var filter = new SubscriptionFilter(
                    Set.of(), EventPriority.DIAGNOSTIC, null);

            assertThat(filter.matches(envelope("t", EventPriority.NORMAL, ENTITY_SUBJECT)))
                    .isTrue();
            assertThat(filter.matches(envelope("t", EventPriority.NORMAL, DEVICE_SUBJECT)))
                    .isTrue();
            assertThat(filter.matches(envelope("t", EventPriority.NORMAL, SYSTEM_SUBJECT)))
                    .isTrue();
        }

        @Test
        @DisplayName("ENTITY filter matches only ENTITY subjects")
        void entityFilterMatchesEntityOnly() {
            var filter = new SubscriptionFilter(
                    Set.of(), EventPriority.DIAGNOSTIC, SubjectType.ENTITY);

            assertThat(filter.matches(envelope("t", EventPriority.NORMAL, ENTITY_SUBJECT)))
                    .isTrue();
            assertThat(filter.matches(envelope("t", EventPriority.NORMAL, DEVICE_SUBJECT)))
                    .isFalse();
            assertThat(filter.matches(envelope("t", EventPriority.NORMAL, SYSTEM_SUBJECT)))
                    .isFalse();
        }

        @Test
        @DisplayName("DEVICE filter matches only DEVICE subjects")
        void deviceFilterMatchesDeviceOnly() {
            var filter = new SubscriptionFilter(
                    Set.of(), EventPriority.DIAGNOSTIC, SubjectType.DEVICE);

            assertThat(filter.matches(envelope("t", EventPriority.NORMAL, DEVICE_SUBJECT)))
                    .isTrue();
            assertThat(filter.matches(envelope("t", EventPriority.NORMAL, ENTITY_SUBJECT)))
                    .isFalse();
        }

        @Test
        @DisplayName("SYSTEM filter rejects non-SYSTEM subjects")
        void systemFilterRejectsNonSystem() {
            var filter = new SubscriptionFilter(
                    Set.of(), EventPriority.DIAGNOSTIC, SubjectType.SYSTEM);

            assertThat(filter.matches(envelope("t", EventPriority.NORMAL, SYSTEM_SUBJECT)))
                    .isTrue();
            assertThat(filter.matches(envelope("t", EventPriority.NORMAL, ENTITY_SUBJECT)))
                    .isFalse();
        }
    }

    // ── matches() — combined filters ─────────────────────────────────────

    @Nested
    @DisplayName("matches() — combined filters (all three dimensions)")
    class CombinedFilterTests {

        @Test
        @DisplayName("all three criteria pass — envelope matches")
        void allCriteriaPass() {
            var filter = new SubscriptionFilter(
                    Set.of("device.state_changed"),
                    EventPriority.NORMAL,
                    SubjectType.ENTITY);

            var env = envelope("device.state_changed", EventPriority.NORMAL, ENTITY_SUBJECT);

            assertThat(filter.matches(env)).isTrue();
        }

        @Test
        @DisplayName("event type fails — envelope rejected despite other criteria passing")
        void eventTypeFailsRejects() {
            var filter = new SubscriptionFilter(
                    Set.of("device.state_changed"),
                    EventPriority.NORMAL,
                    SubjectType.ENTITY);

            var env = envelope("automation.triggered", EventPriority.NORMAL, ENTITY_SUBJECT);

            assertThat(filter.matches(env)).isFalse();
        }

        @Test
        @DisplayName("priority fails — envelope rejected despite other criteria passing")
        void priorityFailsRejects() {
            var filter = new SubscriptionFilter(
                    Set.of("device.state_changed"),
                    EventPriority.NORMAL,
                    SubjectType.ENTITY);

            var env = envelope("device.state_changed", EventPriority.DIAGNOSTIC, ENTITY_SUBJECT);

            assertThat(filter.matches(env)).isFalse();
        }

        @Test
        @DisplayName("subject type fails — envelope rejected despite other criteria passing")
        void subjectTypeFailsRejects() {
            var filter = new SubscriptionFilter(
                    Set.of("device.state_changed"),
                    EventPriority.DIAGNOSTIC,
                    SubjectType.ENTITY);

            var env = envelope("device.state_changed", EventPriority.NORMAL, DEVICE_SUBJECT);

            assertThat(filter.matches(env)).isFalse();
        }

        @Test
        @DisplayName("wildcard types + CRITICAL priority + ENTITY subject — precision filter")
        void wildcardTypesWithPriorityAndSubject() {
            var filter = new SubscriptionFilter(
                    Set.of(), EventPriority.CRITICAL, SubjectType.ENTITY);

            // CRITICAL + ENTITY → pass
            assertThat(filter.matches(
                    envelope("any.type", EventPriority.CRITICAL, ENTITY_SUBJECT))).isTrue();

            // NORMAL + ENTITY → rejected by priority
            assertThat(filter.matches(
                    envelope("any.type", EventPriority.NORMAL, ENTITY_SUBJECT))).isFalse();

            // CRITICAL + DEVICE → rejected by subject type
            assertThat(filter.matches(
                    envelope("any.type", EventPriority.CRITICAL, DEVICE_SUBJECT))).isFalse();
        }
    }

    // ── matches() — null envelope ────────────────────────────────────────

    @Nested
    @DisplayName("matches() — null envelope")
    class NullEnvelopeTests {

        @Test
        @DisplayName("null envelope throws NullPointerException")
        void nullEnvelopeThrows() {
            var filter = SubscriptionFilter.all();

            assertThatNullPointerException().isThrownBy(() ->
                    filter.matches(null))
                    .withMessageContaining("envelope");
        }
    }

    // ── equals / hashCode ────────────────────────────────────────────────

    @Nested
    @DisplayName("equals / hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("filters with identical fields are equal")
        void identicalFieldsAreEqual() {
            var a = new SubscriptionFilter(
                    Set.of("device.state_changed"), EventPriority.NORMAL, SubjectType.ENTITY);
            var b = new SubscriptionFilter(
                    Set.of("device.state_changed"), EventPriority.NORMAL, SubjectType.ENTITY);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("filters differing in eventTypes are not equal")
        void differentEventTypes() {
            var a = new SubscriptionFilter(
                    Set.of("device.state_changed"), EventPriority.NORMAL, null);
            var b = new SubscriptionFilter(
                    Set.of("automation.triggered"), EventPriority.NORMAL, null);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("filters differing in minimumPriority are not equal")
        void differentPriority() {
            var a = new SubscriptionFilter(Set.of(), EventPriority.NORMAL, null);
            var b = new SubscriptionFilter(Set.of(), EventPriority.CRITICAL, null);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("filters differing in subjectTypeFilter are not equal")
        void differentSubjectType() {
            var a = new SubscriptionFilter(Set.of(), EventPriority.DIAGNOSTIC, SubjectType.ENTITY);
            var b = new SubscriptionFilter(Set.of(), EventPriority.DIAGNOSTIC, SubjectType.DEVICE);

            assertThat(a).isNotEqualTo(b);
        }
    }
}
