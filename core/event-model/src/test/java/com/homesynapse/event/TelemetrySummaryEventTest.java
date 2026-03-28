/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TelemetrySummaryEvent} — aggregated telemetry summary event.
 */
@DisplayName("TelemetrySummaryEvent")
class TelemetrySummaryEventTest {

	// ── Construction ─────────────────────────────────────────────────────

	@Nested
	@DisplayName("Construction")
	class ConstructionTests {

		@Test
		@DisplayName("all 9 fields accessible after construction")
		void allFieldsAccessible() {
			var event = new TelemetrySummaryEvent(
					"temperature",
					15.0,
					25.0,
					20.0,
					200.0,
					10L,
					1000L,
					2000L,
					false
			);

			assertThat(event.attributeKey()).isEqualTo("temperature");
			assertThat(event.min()).isEqualTo(15.0);
			assertThat(event.max()).isEqualTo(25.0);
			assertThat(event.mean()).isEqualTo(20.0);
			assertThat(event.sum()).isEqualTo(200.0);
			assertThat(event.count()).isEqualTo(10L);
			assertThat(event.periodStartEpochMs()).isEqualTo(1000L);
			assertThat(event.periodEndEpochMs()).isEqualTo(2000L);
			assertThat(event.partial()).isFalse();
		}

		@Test
		@DisplayName("implements DomainEvent")
		void implementsDomainEvent() {
			var event = new TelemetrySummaryEvent("attr", 0.0, 1.0, 0.5, 1.0, 0L, 0L, 1L, false);
			assertThat(event).isInstanceOf(DomainEvent.class);
		}

		@Test
		@DisplayName("record has exactly 9 components")
		void exactlyNineComponents() {
			assertThat(TelemetrySummaryEvent.class.getRecordComponents()).hasSize(9);
		}

		@Test
		@DisplayName("partial flag works when true")
		void partialTrue() {
			var event = new TelemetrySummaryEvent("attr", 0.0, 1.0, 0.5, 1.0, 0L, 0L, 1L, true);
			assertThat(event.partial()).isTrue();
		}

		@Test
		@DisplayName("partial flag works when false")
		void partialFalse() {
			var event = new TelemetrySummaryEvent("attr", 0.0, 1.0, 0.5, 1.0, 0L, 0L, 1L, false);
			assertThat(event.partial()).isFalse();
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
					new TelemetrySummaryEvent(null, 0.0, 1.0, 0.5, 1.0, 0L, 0L, 1L, false))
					.withMessageContaining("attributeKey");
		}
	}

	// ── Blank validation ─────────────────────────────────────────────────

	@Nested
	@DisplayName("Blank validation")
	class BlankValidationTests {

		@Test
		@DisplayName("blank attributeKey throws IllegalArgumentException")
		void blankAttributeKey() {
			assertThatThrownBy(() ->
					new TelemetrySummaryEvent("   ", 0.0, 1.0, 0.5, 1.0, 0L, 0L, 1L, false))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("blank");
		}
	}

	// ── Range validation ─────────────────────────────────────────────────

	@Nested
	@DisplayName("Range validation")
	class RangeValidationTests {

		@Test
		@DisplayName("negative count throws IllegalArgumentException")
		void negativeCount() {
			assertThatThrownBy(() ->
					new TelemetrySummaryEvent("attr", 0.0, 1.0, 0.5, 1.0, -1L, 0L, 1L, false))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("count");
		}

		@Test
		@DisplayName("periodStartEpochMs >= periodEndEpochMs throws IllegalArgumentException")
		void periodStartGreaterThanEnd() {
			assertThatThrownBy(() ->
					new TelemetrySummaryEvent("attr", 0.0, 1.0, 0.5, 1.0, 0L, 1000L, 1000L, false))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("periodStartEpochMs");
		}

		@Test
		@DisplayName("periodStartEpochMs == periodEndEpochMs throws IllegalArgumentException")
		void periodStartEqualsEnd() {
			assertThatThrownBy(() ->
					new TelemetrySummaryEvent("attr", 0.0, 1.0, 0.5, 1.0, 0L, 500L, 500L, false))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("periodStartEpochMs");
		}
	}

	// ── Equals / hashCode ────────────────────────────────────────────────

	@Test
	@DisplayName("identical TelemetrySummaryEvents are equal")
	void identicalEqual() {
		var a = new TelemetrySummaryEvent("temp", 15.0, 25.0, 20.0, 200.0, 10L, 1000L, 2000L, false);
		var b = new TelemetrySummaryEvent("temp", 15.0, 25.0, 20.0, 200.0, 10L, 1000L, 2000L, false);
		assertThat(a).isEqualTo(b);
		assertThat(a.hashCode()).isEqualTo(b.hashCode());
	}

	@Test
	@DisplayName("TelemetrySummaryEvents with different attributeKey are not equal")
	void differentNotEqual() {
		var a = new TelemetrySummaryEvent("temperature", 15.0, 25.0, 20.0, 200.0, 10L, 1000L, 2000L, false);
		var b = new TelemetrySummaryEvent("humidity", 15.0, 25.0, 20.0, 200.0, 10L, 1000L, 2000L, false);
		assertThat(a).isNotEqualTo(b);
	}
}
