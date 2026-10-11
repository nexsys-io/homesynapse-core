/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * AVAIL-API-1 E-1 — {@link AvailabilityContractDeclaredEvent} (AMD-103 R-C): the liveness
 * policy the adapter applies to a device. The class rides the wire as a String token
 * ({@code class} is reserved; the nested {@link AvailabilityContractDeclaredEvent.AvailabilityClass}
 * names the three arms); {@code silenceLimitSeconds} and {@code missesToDark} are at least 1;
 * {@code recordIntervalSeconds} is present exactly for an asked class ({@code mains-*}).
 */
@DisplayName("AvailabilityContractDeclaredEvent (AMD-103 R-C)")
class AvailabilityContractDeclaredEventTest {

    /** The metered plug: a 600-s contract, 660-s silence, asked, recorded hourly. */
    private static AvailabilityContractDeclaredEvent metered() {
        return new AvailabilityContractDeclaredEvent("mains-metered", 600, 660, 2, 3600);
    }

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    AvailabilityContractDeclaredEventTest() {
    }

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 5 fields accessible after construction")
        void allFieldsAccessible() {
            AvailabilityContractDeclaredEvent event = metered();

            assertThat(event.availabilityClass()).isEqualTo("mains-metered");
            assertThat(event.reportIntervalSeconds()).isEqualTo(600);
            assertThat(event.silenceLimitSeconds()).isEqualTo(660);
            assertThat(event.missesToDark()).isEqualTo(2);
            assertThat(event.recordIntervalSeconds()).isEqualTo(3600);
        }

        @Test
        @DisplayName("mains-floor: no report interval, the 60-s floor, recorded hourly")
        void floorShape() {
            AvailabilityContractDeclaredEvent event =
                    new AvailabilityContractDeclaredEvent("mains-floor", null, 60, 2, 3600);

            assertThat(event.reportIntervalSeconds()).isNull();
            assertThat(event.silenceLimitSeconds()).isEqualTo(60);
            assertThat(event.recordIntervalSeconds()).isEqualTo(3600);
        }

        @Test
        @DisplayName("passive: the declared interval or the 25-h window, never asked, never recorded")
        void passiveShape() {
            AvailabilityContractDeclaredEvent declared =
                    new AvailabilityContractDeclaredEvent("passive", 7200, 7200, 2, null);
            AvailabilityContractDeclaredEvent fallback =
                    new AvailabilityContractDeclaredEvent("passive", null, 90_000, 2, null);

            assertThat(declared.recordIntervalSeconds()).isNull();
            assertThat(fallback.reportIntervalSeconds()).isNull();
            assertThat(fallback.silenceLimitSeconds()).isEqualTo(90_000);
        }

        @Test
        @DisplayName("carries @EventType(EventTypes.AVAILABILITY_CONTRACT_DECLARED)")
        void carriesTheAnnotation() {
            EventType annotation =
                    AvailabilityContractDeclaredEvent.class.getAnnotation(EventType.class);

            assertThat(annotation).isNotNull();
            assertThat(annotation.value())
                    .isEqualTo(EventTypes.AVAILABILITY_CONTRACT_DECLARED)
                    .isEqualTo("availability_contract_declared");
        }
    }

    @Nested
    @DisplayName("The class token set")
    class ClassTokenTests {

        @Test
        @DisplayName("the three tokens are mains-metered, mains-floor and passive")
        void threeTokens() {
            assertThat(AvailabilityContractDeclaredEvent.AvailabilityClass.values())
                    .extracting(AvailabilityContractDeclaredEvent.AvailabilityClass::token)
                    .containsExactly("mains-metered", "mains-floor", "passive");
        }

        @Test
        @DisplayName("the mains classes are asked; passive is never asked")
        void askedClasses() {
            assertThat(AvailabilityContractDeclaredEvent.AvailabilityClass.MAINS_METERED.asked())
                    .isTrue();
            assertThat(AvailabilityContractDeclaredEvent.AvailabilityClass.MAINS_FLOOR.asked())
                    .isTrue();
            assertThat(AvailabilityContractDeclaredEvent.AvailabilityClass.PASSIVE.asked())
                    .isFalse();
        }

        @Test
        @DisplayName("fromToken resolves the three tokens and nothing else")
        void fromToken() {
            assertThat(AvailabilityContractDeclaredEvent.AvailabilityClass.fromToken("passive"))
                    .contains(AvailabilityContractDeclaredEvent.AvailabilityClass.PASSIVE);
            assertThat(AvailabilityContractDeclaredEvent.AvailabilityClass.fromToken("mains"))
                    .isEqualTo(Optional.empty());
            assertThat(AvailabilityContractDeclaredEvent.AvailabilityClass.fromToken(null))
                    .isEqualTo(Optional.empty());
        }

        @Test
        @DisplayName("an unknown token is rejected at construction")
        void unknownTokenRejected() {
            assertThatThrownBy(() -> new AvailabilityContractDeclaredEvent("mains", null, 60, 2,
                    3600))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("availabilityClass");
        }

        @Test
        @DisplayName("a null class is rejected")
        void nullClassRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new AvailabilityContractDeclaredEvent(null, null, 60, 2,
                            3600))
                    .withMessageContaining("availabilityClass");
        }
    }

    @Nested
    @DisplayName("Invariants")
    class InvariantTests {

        @Test
        @DisplayName("silenceLimitSeconds below 1 is rejected")
        void silenceLimitBelowOneRejected() {
            assertThatThrownBy(() -> new AvailabilityContractDeclaredEvent("mains-floor", null, 0,
                    2, 3600))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("silenceLimitSeconds");
        }

        @Test
        @DisplayName("missesToDark below 1 is rejected")
        void missesToDarkBelowOneRejected() {
            assertThatThrownBy(() -> new AvailabilityContractDeclaredEvent("mains-floor", null, 60,
                    0, 3600))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("missesToDark");
        }

        @Test
        @DisplayName("an asked class without recordIntervalSeconds is rejected")
        void askedWithoutRecordIntervalRejected() {
            assertThatThrownBy(() -> new AvailabilityContractDeclaredEvent("mains-metered", 600,
                    660, 2, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("recordIntervalSeconds");
            assertThatThrownBy(() -> new AvailabilityContractDeclaredEvent("mains-floor", null,
                    60, 2, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("recordIntervalSeconds");
        }

        @Test
        @DisplayName("the passive class with recordIntervalSeconds is rejected")
        void passiveWithRecordIntervalRejected() {
            assertThatThrownBy(() -> new AvailabilityContractDeclaredEvent("passive", null,
                    90_000, 2, 3600))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("recordIntervalSeconds");
        }

        @Test
        @DisplayName("a present reportIntervalSeconds or recordIntervalSeconds below 1 is rejected")
        void presentIntervalsBelowOneRejected() {
            assertThatThrownBy(() -> new AvailabilityContractDeclaredEvent("mains-metered", 0, 60,
                    2, 3600))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reportIntervalSeconds");
            assertThatThrownBy(() -> new AvailabilityContractDeclaredEvent("mains-floor", null, 60,
                    2, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("recordIntervalSeconds");
        }
    }

    @Nested
    @DisplayName("Equality")
    class EqualityTests {

        @Test
        @DisplayName("records with the same components are equal — the on-change arm's comparison")
        void equalComponentsAreEqual() {
            assertThat(metered()).isEqualTo(metered()).hasSameHashCodeAs(metered());
            assertThat(metered()).isNotEqualTo(
                    new AvailabilityContractDeclaredEvent("mains-floor", null, 60, 2, 3600));
        }

        @Test
        @DisplayName("implements DomainEvent")
        void implementsDomainEvent() {
            assertThat(metered()).isInstanceOf(DomainEvent.class);
        }
    }
}
