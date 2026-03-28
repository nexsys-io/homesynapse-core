/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StateReportedEvent} — raw attribute observation from an integration adapter.
 */
@DisplayName("StateReportedEvent")
class StateReportedEventTest {

	// ── Construction ─────────────────────────────────────────────────────

	@Nested
	@DisplayName("Construction")
	class ConstructionTests {

		@Test
		@DisplayName("all 5 fields accessible after construction")
		void allFieldsAccessible() {
			var event = new StateReportedEvent(
					"temperature",
					"22.5",
					"celsius",
					"72.5",
					"fahrenheit");

			assertThat(event.attributeKey()).isEqualTo("temperature");
			assertThat(event.value()).isEqualTo("22.5");
			assertThat(event.unit()).isEqualTo("celsius");
			assertThat(event.rawProtocolValue()).isEqualTo("72.5");
			assertThat(event.rawProtocolUnit()).isEqualTo("fahrenheit");
		}

		@Test
		@DisplayName("nullable fields can be null")
		void nullableFieldsNull() {
			var event = new StateReportedEvent(
					"brightness",
					"50",
					null,
					null,
					null);

			assertThat(event.attributeKey()).isEqualTo("brightness");
			assertThat(event.value()).isEqualTo("50");
			assertThat(event.unit()).isNull();
			assertThat(event.rawProtocolValue()).isNull();
			assertThat(event.rawProtocolUnit()).isNull();
		}

		@Test
		@DisplayName("implements DomainEvent")
		void implementsDomainEvent() {
			var event = new StateReportedEvent("key", "value", null, null, null);
			assertThat(event).isInstanceOf(DomainEvent.class);
		}

		@Test
		@DisplayName("record has exactly 5 components")
		void exactlyFiveFields() {
			assertThat(StateReportedEvent.class.getRecordComponents()).hasSize(5);
		}
	}

	// ── Null validation ──────────────────────────────────────────────────

	@Nested
	@DisplayName("Null validation")
	class NullValidationTests {

		@Test
		@DisplayName("null attributeKey throws NullPointerException")
		void nullAttributeKey() {
			assertThatNullPointerException().isThrownBy(() ->
					new StateReportedEvent(null, "value", null, null, null))
					.withMessageContaining("attributeKey");
		}

		@Test
		@DisplayName("null value throws NullPointerException")
		void nullValue() {
			assertThatNullPointerException().isThrownBy(() ->
					new StateReportedEvent("key", null, null, null, null))
					.withMessageContaining("value");
		}
	}

	// ── Equals / hashCode ────────────────────────────────────────────────

	@Test
	@DisplayName("identical StateReportedEvents are equal")
	void identicalEqual() {
		var a = new StateReportedEvent("temperature", "22.5", "celsius", "72.5", "fahrenheit");
		var b = new StateReportedEvent("temperature", "22.5", "celsius", "72.5", "fahrenheit");
		assertThat(a).isEqualTo(b);
		assertThat(a.hashCode()).isEqualTo(b.hashCode());
	}

	@Test
	@DisplayName("StateReportedEvents with different fields are not equal")
	void differentNotEqual() {
		var a = new StateReportedEvent("temperature", "22.5", "celsius", "72.5", "fahrenheit");
		var b = new StateReportedEvent("humidity", "22.5", "celsius", "72.5", "fahrenheit");
		assertThat(a).isNotEqualTo(b);
	}
}
