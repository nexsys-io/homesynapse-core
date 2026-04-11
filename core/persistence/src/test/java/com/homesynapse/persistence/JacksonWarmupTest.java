/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.DegradedEvent;
import com.homesynapse.event.DomainEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link JacksonWarmup} populates Jackson caches and produces a
 * reusable map of {@link ObjectWriter} and {@link ObjectReader} handles for
 * every registered event class (LTD-19 / DECIDE-M2-05).
 */
@DisplayName("JacksonWarmup")
class JacksonWarmupTest {

    private ObjectMapper mapper;
    private EventTypeRegistry registry;

    @BeforeEach
    void setUp() {
        mapper = PersistenceObjectMapper.create();
        registry = new EventTypeRegistry(AllEventClasses.ALL_EVENTS);
    }

    @Test
    @DisplayName("warmup creates a writer and reader for every registered type")
    void warmup_createsWritersAndReadersForAllTypes() {
        JacksonWarmup warmup = JacksonWarmup.warmup(mapper, registry);

        assertThat(warmup.size()).isEqualTo(27);

        for (Class<? extends DomainEvent> eventClass : AllEventClasses.ALL_EVENTS) {
            assertThat(warmup.writerFor(eventClass))
                    .as("writer for %s", eventClass.getSimpleName())
                    .isNotNull();
            assertThat(warmup.readerFor(eventClass))
                    .as("reader for %s", eventClass.getSimpleName())
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("warmed writer actually serializes a real event round-trip")
    void warmup_writerProducesValidJson() throws Exception {
        JacksonWarmup warmup = JacksonWarmup.warmup(mapper, registry);
        CommandIssuedEvent sample = TestEventSamples.commandIssued();

        ObjectWriter writer = warmup.writerFor(CommandIssuedEvent.class);
        String json = writer.writeValueAsString(sample);

        assertThat(json).isNotBlank();
        assertThat(json).contains("turn_on");
    }

    @Test
    @DisplayName("warmed reader deserializes JSON back to the original event")
    void warmup_readerRoundTripsValue() throws Exception {
        JacksonWarmup warmup = JacksonWarmup.warmup(mapper, registry);
        CommandIssuedEvent original = TestEventSamples.commandIssued();

        ObjectWriter writer = warmup.writerFor(CommandIssuedEvent.class);
        byte[] bytes = writer.writeValueAsBytes(original);

        ObjectReader reader = warmup.readerFor(CommandIssuedEvent.class);
        CommandIssuedEvent parsed = reader.readValue(bytes);

        assertThat(parsed).isEqualTo(original);
    }

    @Test
    @DisplayName("writerFor an unregistered class throws IllegalArgumentException")
    void warmup_writerForUnknownType_throwsIllegalArgument() {
        JacksonWarmup warmup = JacksonWarmup.warmup(mapper, registry);

        assertThatThrownBy(() -> warmup.writerFor(DegradedEvent.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No warmed ObjectWriter")
                .hasMessageContaining(DegradedEvent.class.getName());
    }

    @Test
    @DisplayName("readerFor an unregistered class throws IllegalArgumentException")
    void warmup_readerForUnknownType_throwsIllegalArgument() {
        JacksonWarmup warmup = JacksonWarmup.warmup(mapper, registry);

        assertThatThrownBy(() -> warmup.readerFor(DegradedEvent.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No warmed ObjectReader")
                .hasMessageContaining(DegradedEvent.class.getName());
    }

    @Test
    @DisplayName("warmup accepts a core-only registry of 22 classes")
    void warmup_acceptsCoreOnlyRegistry() {
        EventTypeRegistry coreOnly = new EventTypeRegistry(AllEventClasses.CORE_EVENTS);

        JacksonWarmup warmup = JacksonWarmup.warmup(mapper, coreOnly);

        assertThat(warmup.size()).isEqualTo(22);
    }

    @Test
    @DisplayName("warmup() throws NullPointerException on null mapper")
    void warmup_nullMapper_throws() {
        assertThatThrownBy(() -> JacksonWarmup.warmup(null, registry))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("mapper");
    }

    @Test
    @DisplayName("warmup() throws NullPointerException on null registry")
    void warmup_nullRegistry_throws() {
        assertThatThrownBy(() -> JacksonWarmup.warmup(mapper, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("registry");
    }
}
