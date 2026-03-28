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
 * Tests for {@link StoragePressureChangedEvent} — storage pressure level transition event.
 */
@DisplayName("StoragePressureChangedEvent")
class StoragePressureChangedEventTest {

	// ── Construction ─────────────────────────────────────────────────────

	@Nested
	@DisplayName("Construction")
	class ConstructionTests {

		@Test
		@DisplayName("all 4 fields accessible after construction")
		void allFieldsAccessible() {
			var event = new StoragePressureChangedEvent("HEALTHY", "WARNING", 500_000_000L, 1_000_000_000L);

			assertThat(event.oldLevel()).isEqualTo("HEALTHY");
			assertThat(event.newLevel()).isEqualTo("WARNING");
			assertThat(event.diskUsageBytes()).isEqualTo(500_000_000L);
			assertThat(event.thresholdBytes()).isEqualTo(1_000_000_000L);
		}

		@Test
		@DisplayName("implements DomainEvent")
		void implementsDomainEvent() {
			var event = new StoragePressureChangedEvent("HEALTHY", "WARNING", 0L, 0L);
			assertThat(event).isInstanceOf(DomainEvent.class);
		}

		@Test
		@DisplayName("record has exactly 4 components")
		void exactlyFourComponents() {
			assertThat(StoragePressureChangedEvent.class.getRecordComponents()).hasSize(4);
		}
	}

	// ── Null validation ──────────────────────────────────────────────────

	@Nested
	@DisplayName("Null validation")
	class NullValidationTests {

		@Test
		@DisplayName("null oldLevel throws NullPointerException")
		void nullOldLevel() {
			assertThatNullPointerException().isThrownBy(() ->
					new StoragePressureChangedEvent(null, "WARNING", 0L, 0L))
					.withMessageContaining("oldLevel");
		}

		@Test
		@DisplayName("null newLevel throws NullPointerException")
		void nullNewLevel() {
			assertThatNullPointerException().isThrownBy(() ->
					new StoragePressureChangedEvent("HEALTHY", null, 0L, 0L))
					.withMessageContaining("newLevel");
		}
	}

	// ── Blank validation ─────────────────────────────────────────────────

	@Nested
	@DisplayName("Blank validation")
	class BlankValidationTests {

		@Test
		@DisplayName("blank oldLevel throws IllegalArgumentException")
		void blankOldLevel() {
			assertThatThrownBy(() -> new StoragePressureChangedEvent("   ", "WARNING", 0L, 0L))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("blank");
		}

		@Test
		@DisplayName("blank newLevel throws IllegalArgumentException")
		void blankNewLevel() {
			assertThatThrownBy(() -> new StoragePressureChangedEvent("HEALTHY", "  \t", 0L, 0L))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("blank");
		}
	}

	// ── Range validation ─────────────────────────────────────────────────

	@Nested
	@DisplayName("Range validation")
	class RangeValidationTests {

		@Test
		@DisplayName("negative diskUsageBytes throws IllegalArgumentException")
		void negativeDiskUsageBytes() {
			assertThatThrownBy(() -> new StoragePressureChangedEvent("HEALTHY", "WARNING", -1L, 0L))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("diskUsageBytes");
		}

		@Test
		@DisplayName("negative thresholdBytes throws IllegalArgumentException")
		void negativeThresholdBytes() {
			assertThatThrownBy(() -> new StoragePressureChangedEvent("HEALTHY", "WARNING", 0L, -1L))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("thresholdBytes");
		}
	}

	// ── Equals / hashCode ────────────────────────────────────────────────

	@Test
	@DisplayName("identical StoragePressureChangedEvents are equal")
	void identicalEqual() {
		var a = new StoragePressureChangedEvent("HEALTHY", "WARNING", 500L, 1000L);
		var b = new StoragePressureChangedEvent("HEALTHY", "WARNING", 500L, 1000L);
		assertThat(a).isEqualTo(b);
		assertThat(a.hashCode()).isEqualTo(b.hashCode());
	}

	@Test
	@DisplayName("StoragePressureChangedEvents with different fields are not equal")
	void differentNotEqual() {
		var a = new StoragePressureChangedEvent("HEALTHY", "WARNING", 500L, 1000L);
		var b = new StoragePressureChangedEvent("WARNING", "WARNING", 500L, 1000L);
		assertThat(a).isNotEqualTo(b);
	}
}
