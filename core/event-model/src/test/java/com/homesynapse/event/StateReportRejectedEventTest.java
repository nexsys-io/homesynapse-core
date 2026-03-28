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
 * Tests for {@link StateReportRejectedEvent} — rejection of an attribute value due to
 * validation failure.
 */
@DisplayName("StateReportRejectedEvent")
class StateReportRejectedEventTest {

	// ── Construction ─────────────────────────────────────────────────────

	@Nested
	@DisplayName("Construction")
	class ConstructionTests {

		@Test
		@DisplayName("all 4 fields accessible after construction")
		void allFieldsAccessible() {
			var event = new StateReportRejectedEvent(
					"temperature",
					"-500",
					"Value out of range",
					"min_value_check");

			assertThat(event.attributeKey()).isEqualTo("temperature");
			assertThat(event.reportedValue()).isEqualTo("-500");
			assertThat(event.reason()).isEqualTo("Value out of range");
			assertThat(event.validationRule()).isEqualTo("min_value_check");
		}

		@Test
		@DisplayName("implements DomainEvent")
		void implementsDomainEvent() {
			var event = new StateReportRejectedEvent(
					"key",
					"value",
					"reason",
					"rule");
			assertThat(event).isInstanceOf(DomainEvent.class);
		}

		@Test
		@DisplayName("record has exactly 4 components")
		void exactlyFourFields() {
			assertThat(StateReportRejectedEvent.class.getRecordComponents()).hasSize(4);
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
					new StateReportRejectedEvent(
							null,
							"value",
							"reason",
							"rule"))
					.withMessageContaining("attributeKey");
		}

		@Test
		@DisplayName("null reportedValue throws NullPointerException")
		void nullReportedValue() {
			assertThatNullPointerException().isThrownBy(() ->
					new StateReportRejectedEvent(
							"key",
							null,
							"reason",
							"rule"))
					.withMessageContaining("reportedValue");
		}

		@Test
		@DisplayName("null reason throws NullPointerException")
		void nullReason() {
			assertThatNullPointerException().isThrownBy(() ->
					new StateReportRejectedEvent(
							"key",
							"value",
							null,
							"rule"))
					.withMessageContaining("reason");
		}

		@Test
		@DisplayName("null validationRule throws NullPointerException")
		void nullValidationRule() {
			assertThatNullPointerException().isThrownBy(() ->
					new StateReportRejectedEvent(
							"key",
							"value",
							"reason",
							null))
					.withMessageContaining("validationRule");
		}
	}

	// ── Equals / hashCode ────────────────────────────────────────────────

	@Test
	@DisplayName("identical StateReportRejectedEvents are equal")
	void identicalEqual() {
		var a = new StateReportRejectedEvent(
				"temperature",
				"-500",
				"Value out of range",
				"min_value_check");
		var b = new StateReportRejectedEvent(
				"temperature",
				"-500",
				"Value out of range",
				"min_value_check");
		assertThat(a).isEqualTo(b);
		assertThat(a.hashCode()).isEqualTo(b.hashCode());
	}

	@Test
	@DisplayName("StateReportRejectedEvents with different fields are not equal")
	void differentNotEqual() {
		var a = new StateReportRejectedEvent(
				"temperature",
				"-500",
				"Value out of range",
				"min_value_check");
		var b = new StateReportRejectedEvent(
				"temperature",
				"-500",
				"Value out of range",
				"max_value_check");
		assertThat(a).isNotEqualTo(b);
	}
}
