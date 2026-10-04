/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventType;
import com.homesynapse.event.EventTypes;

/**
 * T9 (PJ-2) — the two pairing-window event records ({@link PermitJoinOpened},
 * {@link PermitJoinClosed}) carry {@link EventType}, their values are the
 * {@code permit_join_}-prefixed {@link EventTypes} constants, they sit under the
 * sealed {@link PairingWindowEvent} — NOT under {@link IntegrationLifecycleEvent}
 * (whose ten permits and {@code IntegrationEventTypeAnnotationTest}'s count of 10
 * are untouched) — and both ride {@link IntegrationEvents#LIFECYCLE_EVENT_CLASSES},
 * the seam's codec registration.
 */
@DisplayName("EventType annotation on the PairingWindowEvent records (PJ-2)")
class PairingWindowEventTypeAnnotationTest {

    /** The two window records, in manifest order. */
    private static final List<Class<? extends PairingWindowEvent>> EXPECTED_RECORDS = List.of(
            PermitJoinOpened.class,
            PermitJoinClosed.class);

    /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
    PairingWindowEventTypeAnnotationTest() {
        // Defaults are sufficient.
    }

    @Test
    @DisplayName("both records carry @EventType with the matching EventTypes constant")
    void recordsCarryTheMatchingConstant() {
        assertThat(PermitJoinOpened.class.getAnnotation(EventType.class))
                .as("PermitJoinOpened is annotated").isNotNull()
                .extracting(EventType::value).isEqualTo(EventTypes.PERMIT_JOIN_OPENED);
        assertThat(PermitJoinClosed.class.getAnnotation(EventType.class))
                .as("PermitJoinClosed is annotated").isNotNull()
                .extracting(EventType::value).isEqualTo(EventTypes.PERMIT_JOIN_CLOSED);
    }

    @Test
    @DisplayName("the values are EventTypes String constants and use the permit_join_ prefix")
    void valuesArePermitJoinPrefixedConstants() throws IllegalAccessException {
        Set<String> constants = collectEventTypesConstants();
        for (Class<? extends PairingWindowEvent> cls : EXPECTED_RECORDS) {
            String value = cls.getAnnotation(EventType.class).value();
            assertThat(constants).as("%s's value is an EventTypes constant", cls.getSimpleName())
                    .contains(value);
            assertThat(value).as("%s's value carries the permit_join_ prefix", cls.getSimpleName())
                    .startsWith("permit_join_");
        }
        assertThat(EventTypes.PERMIT_JOIN_OPENED).isEqualTo("permit_join_opened");
        assertThat(EventTypes.PERMIT_JOIN_CLOSED).isEqualTo("permit_join_closed");
    }

    @Test
    @DisplayName("both are records, PairingWindowEvents and DomainEvents — never "
            + "IntegrationLifecycleEvents")
    void recordsAreWindowEventsNotLifecycleEvents() {
        for (Class<? extends PairingWindowEvent> cls : EXPECTED_RECORDS) {
            assertThat(cls.isRecord()).as("%s is a record", cls.getSimpleName()).isTrue();
            assertThat(PairingWindowEvent.class.isAssignableFrom(cls)).isTrue();
            assertThat(DomainEvent.class.isAssignableFrom(cls)).isTrue();
            assertThat(IntegrationLifecycleEvent.class.isAssignableFrom(cls))
                    .as("%s is NOT a lifecycle event (the sealed permits are untouched)",
                            cls.getSimpleName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("PairingWindowEvent is sealed and permits exactly the three records (J2: + JoinRejected)")
    void sealedPermitsAreExactlyTheThreeRecords() {
        assertThat(PairingWindowEvent.class.isSealed()).isTrue();
        assertThat(PairingWindowEvent.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(PermitJoinOpened.class, PermitJoinClosed.class,
                        JoinRejected.class);
        assertThat(IntegrationLifecycleEvent.class.getPermittedSubclasses())
                .as("the lifecycle hierarchy keeps its ten permits")
                .hasSize(10)
                .doesNotContain(PermitJoinOpened.class, PermitJoinClosed.class,
                        JoinRejected.class);
    }

    @Test
    @DisplayName("the lifecycle manifest (the seam's codec registration) carries the three, "
            + "appended in order — JoinRejected last (J2)")
    void lifecycleManifestCarriesTheThreeAppended() {
        List<Class<? extends DomainEvent>> manifest = IntegrationEvents.LIFECYCLE_EVENT_CLASSES;
        assertThat(manifest).hasSize(13);
        assertThat(manifest.subList(10, 13))
                .containsExactly(PermitJoinOpened.class, PermitJoinClosed.class,
                        JoinRejected.class);
    }

    @Test
    @DisplayName("J2: JoinRejected carries @EventType(EventTypes.JOIN_REJECTED) = join_rejected — "
            + "an EventTypes constant outside the permit_join_ prefix (the two prefixed records "
            + "stay exactly two) — and is a window event, never a lifecycle event")
    void joinRejectedCarriesItsConstant_asAWindowEventApart() throws IllegalAccessException {
        assertThat(JoinRejected.class.getAnnotation(EventType.class))
                .as("JoinRejected is annotated").isNotNull()
                .extracting(EventType::value).isEqualTo(EventTypes.JOIN_REJECTED);
        assertThat(EventTypes.JOIN_REJECTED).isEqualTo("join_rejected");
        assertThat(collectEventTypesConstants()).contains(EventTypes.JOIN_REJECTED);
        assertThat(EXPECTED_RECORDS).hasSize(2).doesNotContain(JoinRejected.class);
        assertThat(JoinRejected.class.isRecord()).isTrue();
        assertThat(PairingWindowEvent.class.isAssignableFrom(JoinRejected.class)).isTrue();
        assertThat(IntegrationLifecycleEvent.class.isAssignableFrom(JoinRejected.class)).isFalse();
    }

    @Test
    @DisplayName("PermitJoinClosed accepts exactly the four causes and rejects any other word")
    void closedCausesAreTheFourWords() {
        assertThat(PermitJoinClosed.CAUSES).containsExactlyInAnyOrder(
                "elapsed", "superseded", "transport_reopened", "shutdown");
        assertThat(PermitJoinClosed.CAUSE_ELAPSED).isEqualTo("elapsed");
        assertThat(PermitJoinClosed.CAUSE_SUPERSEDED).isEqualTo("superseded");
        assertThat(PermitJoinClosed.CAUSE_TRANSPORT_REOPENED).isEqualTo("transport_reopened");
        assertThat(PermitJoinClosed.CAUSE_SHUTDOWN).isEqualTo("shutdown");
    }

    private static Set<String> collectEventTypesConstants() throws IllegalAccessException {
        Set<String> values = new HashSet<>();
        for (Field f : EventTypes.class.getDeclaredFields()) {
            int mods = f.getModifiers();
            if (Modifier.isPublic(mods) && Modifier.isStatic(mods) && Modifier.isFinal(mods)
                    && f.getType() == String.class) {
                values.add((String) f.get(null));
            }
        }
        return values;
    }
}
