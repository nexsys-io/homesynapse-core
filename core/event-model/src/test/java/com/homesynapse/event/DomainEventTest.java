/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DomainEvent} — the marker interface for all event payloads.
 */
@DisplayName("DomainEvent")
class DomainEventTest {

    @Test
    @DisplayName("DomainEvent is an interface")
    void isInterface() {
        assertThat(DomainEvent.class.isInterface()).isTrue();
    }

    @Test
    @DisplayName("DomainEvent is not sealed (currently non-sealed marker)")
    void isNotSealed() {
        assertThat(DomainEvent.class.isSealed()).isFalse();
    }

    @Test
    @DisplayName("any class can implement DomainEvent")
    void implementableByAnyClass() {
        record CustomEvent(String detail) implements DomainEvent {}

        DomainEvent event = new CustomEvent("test");
        assertThat(event).isInstanceOf(DomainEvent.class);
    }

    @Test
    @DisplayName("DegradedEvent implements DomainEvent")
    void degradedEventImplements() {
        var degraded = new DegradedEvent("test.type", 1, "{}", "reason");
        assertThat(degraded).isInstanceOf(DomainEvent.class);
    }
}
