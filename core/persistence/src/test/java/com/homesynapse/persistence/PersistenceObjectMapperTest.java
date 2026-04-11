/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the ObjectMapper configuration enforced by
 * {@link PersistenceObjectMapper#create()} (LTD-08): SNAKE_CASE property naming,
 * null suppression, JavaTimeModule ISO-8601 instants, and lenient unknown
 * property handling.
 */
@DisplayName("PersistenceObjectMapper configuration")
class PersistenceObjectMapperTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = PersistenceObjectMapper.create();
    }

    @Test
    @DisplayName("create() returns a non-null ObjectMapper")
    void create_returnsNonNull() {
        assertThat(mapper).isNotNull();
    }

    @Test
    @DisplayName("SNAKE_CASE naming strategy converts camelCase fields")
    void create_usesSnakeCaseNaming() throws Exception {
        SampleRecord record = new SampleRecord("value", 42, "keep");

        String json = mapper.writeValueAsString(record);

        assertThat(json).contains("\"camel_case_field\":\"value\"");
        assertThat(json).contains("\"another_field\":42");
        assertThat(json).doesNotContain("camelCaseField");
        assertThat(json).doesNotContain("anotherField");
    }

    @Test
    @DisplayName("NON_NULL inclusion omits null-valued fields from output")
    void create_omitsNullFields() throws Exception {
        SampleRecord record = new SampleRecord("value", 1, null);

        String json = mapper.writeValueAsString(record);

        assertThat(json).doesNotContain("nullable_field");
        assertThat(json).doesNotContain("null");
    }

    @Test
    @DisplayName("FAIL_ON_UNKNOWN_PROPERTIES is disabled — extra fields are ignored")
    void create_ignoresUnknownProperties() throws Exception {
        String json = """
                {"camel_case_field":"v","another_field":7,"extra":"ignored","nullable_field":"x"}
                """;

        SampleRecord parsed = mapper.readValue(json, SampleRecord.class);

        assertThat(parsed.camelCaseField()).isEqualTo("v");
        assertThat(parsed.anotherField()).isEqualTo(7);
        assertThat(parsed.nullableField()).isEqualTo("x");
    }

    @Test
    @DisplayName("Instant is serialized as an ISO-8601 string, not an epoch number")
    void create_instantSerializesAsIso8601() throws Exception {
        Instant instant = Instant.parse("2026-04-10T12:34:56.789Z");
        InstantHolder holder = new InstantHolder(instant);

        String json = mapper.writeValueAsString(holder);

        assertThat(json).contains("\"2026-04-10T12:34:56.789Z\"");

        InstantHolder roundTripped = mapper.readValue(json, InstantHolder.class);
        assertThat(roundTripped.when()).isEqualTo(instant);
    }

    @Test
    @DisplayName("WRITE_DATES_AS_TIMESTAMPS is disabled")
    void create_writeDatesAsTimestampsDisabled() {
        assertThat(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS))
                .isFalse();
    }

    @Test
    @DisplayName("FAIL_ON_UNKNOWN_PROPERTIES feature flag is disabled")
    void create_failOnUnknownPropertiesDisabled() {
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
                .isFalse();
    }

    @Test
    @DisplayName("INDENT_OUTPUT is disabled — compact JSON for storage efficiency")
    void create_indentOutputDisabled() throws Exception {
        String json = mapper.writeValueAsString(Map.of("a", 1, "b", 2));
        assertThat(json).doesNotContain("\n");
    }

    // Test-only records

    record SampleRecord(String camelCaseField, int anotherField, String nullableField) {
    }

    record InstantHolder(Instant when) {
    }
}
