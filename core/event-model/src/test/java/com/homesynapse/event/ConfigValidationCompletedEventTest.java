/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ConfigValidationCompletedEvent} — the AMD-70
 * configuration-validation observability event.
 *
 * <p>The payload is flattened to event-module-resident types per the AMD-70
 * type-residency rule (E70-1): {@code severityCounts} keys are the config
 * module's {@code Severity.name()} strings ({@code "FATAL"}, {@code "ERROR"},
 * {@code "WARNING"}), carried as plain strings so no
 * {@code com.homesynapse.config} type is referenced from this module.</p>
 */
@DisplayName("ConfigValidationCompletedEvent")
class ConfigValidationCompletedEventTest {

	// ── Construction ─────────────────────────────────────────────────────

	@Nested
	@DisplayName("Construction")
	class ConstructionTests {

		@Test
		@DisplayName("all 4 fields accessible")
		void allFieldsAccessible() {
			var event = new ConfigValidationCompletedEvent(
					2, 1, 3, Map.of("ERROR", 2, "WARNING", 1));

			assertThat(event.configSchemaMajor()).isEqualTo(2);
			assertThat(event.configSchemaMinor()).isEqualTo(1);
			assertThat(event.issueCount()).isEqualTo(3);
			assertThat(event.severityCounts())
					.containsEntry("ERROR", 2)
					.containsEntry("WARNING", 1);
		}

		@Test
		@DisplayName("clean validation pass: zero issues, empty severity counts")
		void cleanPassZeroIssues() {
			var event = new ConfigValidationCompletedEvent(1, 0, 0, Map.of());

			assertThat(event.issueCount()).isZero();
			assertThat(event.severityCounts()).isEmpty();
		}

		@Test
		@DisplayName("implements DomainEvent")
		void implementsDomainEvent() {
			var event = new ConfigValidationCompletedEvent(1, 0, 0, Map.of());
			assertThat(event).isInstanceOf(DomainEvent.class);
		}

		@Test
		@DisplayName("record has exactly 4 components")
		void exactlyFourComponents() {
			assertThat(ConfigValidationCompletedEvent.class.getRecordComponents())
					.hasSize(4);
		}

		@Test
		@DisplayName("carries @EventType(EventTypes.CONFIG_VALIDATION_COMPLETED)")
		void carriesEventTypeAnnotation() {
			EventType annotation = ConfigValidationCompletedEvent.class
					.getAnnotation(EventType.class);

			assertThat(annotation).isNotNull();
			assertThat(annotation.value())
					.isEqualTo(EventTypes.CONFIG_VALIDATION_COMPLETED)
					.isEqualTo("config.validation_completed");
		}
	}

	// ── Guard validation (AMD-67 pair + counts) ──────────────────────────

	@Nested
	@DisplayName("Guard validation")
	class GuardValidationTests {

		@Test
		@DisplayName("configSchemaMajor < 1 throws IllegalArgumentException")
		void majorBelowOneRejected() {
			assertThatThrownBy(() ->
					new ConfigValidationCompletedEvent(0, 0, 0, Map.of()))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("configSchemaMajor");
		}

		@Test
		@DisplayName("configSchemaMinor < 0 throws IllegalArgumentException")
		void negativeMinorRejected() {
			assertThatThrownBy(() ->
					new ConfigValidationCompletedEvent(1, -1, 0, Map.of()))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("configSchemaMinor");
		}

		@Test
		@DisplayName("negative issueCount throws IllegalArgumentException")
		void negativeIssueCountRejected() {
			assertThatThrownBy(() ->
					new ConfigValidationCompletedEvent(1, 0, -1, Map.of()))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("issueCount");
		}

		@Test
		@DisplayName("null severityCounts throws NullPointerException")
		void nullSeverityCountsRejected() {
			assertThatNullPointerException().isThrownBy(() ->
					new ConfigValidationCompletedEvent(1, 0, 0, null))
					.withMessageContaining("severityCounts");
		}

		@Test
		@DisplayName("severityCounts not summing to issueCount throws IllegalArgumentException")
		void severityCountsMustSumToIssueCount() {
			assertThatThrownBy(() ->
					new ConfigValidationCompletedEvent(1, 0, 5, Map.of("ERROR", 2)))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("issueCount");
		}
	}

	// ── Defensive copies ─────────────────────────────────────────────────

	@Nested
	@DisplayName("Defensive copies")
	class DefensiveCopyTests {

		@Test
		@DisplayName("severityCounts is unmodifiable")
		void severityCountsUnmodifiable() {
			var event = new ConfigValidationCompletedEvent(
					1, 0, 1, Map.of("WARNING", 1));

			assertThatThrownBy(() -> event.severityCounts().put("ERROR", 1))
					.isInstanceOf(UnsupportedOperationException.class);
		}

		@Test
		@DisplayName("severityCounts is independent of the caller's map")
		void severityCountsIndependentOfCallerMap() {
			Map<String, Integer> callerMap = new HashMap<>();
			callerMap.put("WARNING", 1);
			var event = new ConfigValidationCompletedEvent(1, 0, 1, callerMap);

			callerMap.put("ERROR", 99);

			assertThat(event.severityCounts()).containsOnlyKeys("WARNING");
		}
	}

	// ── Equals / hashCode ────────────────────────────────────────────────

	@Test
	@DisplayName("identical events are equal")
	void identicalEqual() {
		var a = new ConfigValidationCompletedEvent(1, 0, 1, Map.of("WARNING", 1));
		var b = new ConfigValidationCompletedEvent(1, 0, 1, Map.of("WARNING", 1));
		assertThat(a).isEqualTo(b);
		assertThat(a.hashCode()).isEqualTo(b.hashCode());
	}

	@Test
	@DisplayName("events with different schema pairs are not equal")
	void differentNotEqual() {
		var a = new ConfigValidationCompletedEvent(1, 0, 0, Map.of());
		var b = new ConfigValidationCompletedEvent(2, 0, 0, Map.of());
		assertThat(a).isNotEqualTo(b);
	}
}
