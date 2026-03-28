// HomeSynapse Core / Copyright (c) 2026 NexSys. All rights reserved.
package com.homesynapse.event.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.homesynapse.event.EventPriority;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SubscriberInfo} — the immutable subscriber registration descriptor.
 *
 * <p>Verifies record structure, field access, null rejection, blank subscriberId
 * rejection, coalesceExempt flag, and equals/hashCode.</p>
 */
@DisplayName("SubscriberInfo")
class SubscriberInfoTest {

    private static final SubscriptionFilter ALL_FILTER = SubscriptionFilter.all();

    // ── Construction and field access ────────────────────────────────────

    @Nested
    @DisplayName("Construction and field access")
    class ConstructionTests {

        @Test
        @DisplayName("all fields are accessible and return correct values")
        void allFieldsAccessible() {
            var info = new SubscriberInfo("state_projection", ALL_FILTER, true);

            assertThat(info.subscriberId()).isEqualTo("state_projection");
            assertThat(info.filter()).isEqualTo(ALL_FILTER);
            assertThat(info.coalesceExempt()).isTrue();
        }

        @Test
        @DisplayName("exactly 3 record components")
        void exactlyThreeFields() {
            assertThat(SubscriberInfo.class.getRecordComponents()).hasSize(3);
        }

        @Test
        @DisplayName("coalesceExempt field exists and is boolean")
        void coalesceExemptIsBoolean() {
            var components = SubscriberInfo.class.getRecordComponents();
            var coalesceComponent = java.util.Arrays.stream(components)
                    .filter(c -> "coalesceExempt".equals(c.getName()))
                    .findFirst();

            assertThat(coalesceComponent).isPresent();
            assertThat(coalesceComponent.get().getType()).isEqualTo(boolean.class);
        }

        @Test
        @DisplayName("coalesceExempt true — State Projection and PCL must be exempt")
        void coalesceExemptTrue() {
            var info = new SubscriberInfo("state_projection", ALL_FILTER, true);

            assertThat(info.coalesceExempt()).isTrue();
        }

        @Test
        @DisplayName("coalesceExempt false — most subscribers should not be exempt")
        void coalesceExemptFalse() {
            var info = new SubscriberInfo("websocket_streamer", ALL_FILTER, false);

            assertThat(info.coalesceExempt()).isFalse();
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null subscriberId throws NullPointerException")
        void nullSubscriberId() {
            assertThatNullPointerException().isThrownBy(() ->
                    new SubscriberInfo(null, ALL_FILTER, false))
                    .withMessageContaining("subscriberId");
        }

        @Test
        @DisplayName("null filter throws NullPointerException")
        void nullFilter() {
            assertThatNullPointerException().isThrownBy(() ->
                    new SubscriberInfo("test", null, false))
                    .withMessageContaining("filter");
        }
    }

    // ── Blank subscriberId validation ────────────────────────────────────

    @Nested
    @DisplayName("Blank subscriberId validation")
    class BlankValidationTests {

        @Test
        @DisplayName("blank subscriberId throws IllegalArgumentException")
        void blankSubscriberId() {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new SubscriberInfo("   ", ALL_FILTER, false))
                    .withMessageContaining("blank");
        }

        @Test
        @DisplayName("empty subscriberId throws IllegalArgumentException")
        void emptySubscriberId() {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new SubscriberInfo("", ALL_FILTER, false))
                    .withMessageContaining("blank");
        }
    }

    // ── equals / hashCode ────────────────────────────────────────────────

    @Nested
    @DisplayName("equals / hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("infos with identical fields are equal")
        void identicalFieldsAreEqual() {
            var a = new SubscriberInfo("automation_engine", ALL_FILTER, false);
            var b = new SubscriberInfo("automation_engine", ALL_FILTER, false);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("infos differing in subscriberId are not equal")
        void differentSubscriberId() {
            var a = new SubscriberInfo("state_projection", ALL_FILTER, true);
            var b = new SubscriberInfo("automation_engine", ALL_FILTER, true);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("infos differing in filter are not equal")
        void differentFilter() {
            var filterA = SubscriptionFilter.forPriority(EventPriority.CRITICAL);
            var filterB = SubscriptionFilter.forPriority(EventPriority.NORMAL);

            var a = new SubscriberInfo("test", filterA, false);
            var b = new SubscriberInfo("test", filterB, false);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("infos differing in coalesceExempt are not equal")
        void differentCoalesceExempt() {
            var a = new SubscriberInfo("test", ALL_FILTER, true);
            var b = new SubscriberInfo("test", ALL_FILTER, false);

            assertThat(a).isNotEqualTo(b);
        }
    }
}
