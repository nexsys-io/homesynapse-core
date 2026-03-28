/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EventStore")
class EventStoreTest {

    @Test
    @DisplayName("is an interface")
    void isInterface() {
        assertThat(EventStore.class.isInterface()).isTrue();
    }

    @Test
    @DisplayName("has exactly 6 declared methods")
    void methodCount() {
        assertThat(EventStore.class.getDeclaredMethods()).hasSize(6);
    }

    @Test
    @DisplayName("readFrom signature")
    void readFromSignature() throws NoSuchMethodException {
        Method m = EventStore.class.getDeclaredMethod("readFrom", long.class, int.class);
        assertThat(m.getReturnType()).isEqualTo(EventPage.class);
    }

    @Test
    @DisplayName("readBySubject signature")
    void readBySubjectSignature() throws NoSuchMethodException {
        Method m = EventStore.class.getDeclaredMethod("readBySubject", SubjectRef.class, long.class, int.class);
        assertThat(m.getReturnType()).isEqualTo(EventPage.class);
    }

    @Test
    @DisplayName("readByCorrelation signature")
    void readByCorrelationSignature() throws NoSuchMethodException {
        Method m = EventStore.class.getDeclaredMethod("readByCorrelation", Ulid.class);
        assertThat(m.getReturnType()).isEqualTo(List.class);
    }

    @Test
    @DisplayName("readByType signature")
    void readByTypeSignature() throws NoSuchMethodException {
        Method m = EventStore.class.getDeclaredMethod("readByType", String.class, long.class, int.class);
        assertThat(m.getReturnType()).isEqualTo(EventPage.class);
    }

    @Test
    @DisplayName("readByTimeRange signature")
    void readByTimeRangeSignature() throws NoSuchMethodException {
        Method m = EventStore.class.getDeclaredMethod(
                "readByTimeRange", Instant.class, Instant.class, long.class, int.class);
        assertThat(m.getReturnType()).isEqualTo(EventPage.class);
    }

    @Test
    @DisplayName("latestPosition signature")
    void latestPositionSignature() throws NoSuchMethodException {
        Method m = EventStore.class.getDeclaredMethod("latestPosition");
        assertThat(m.getReturnType()).isEqualTo(long.class);
    }
}
