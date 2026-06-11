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
 * Tests for {@link ConfigSectionReloadedEvent} — the AMD-70 per-section
 * reload observability event (M6.4).
 *
 * <p>The payload is flattened to {@code java.base} types per the AMD-70
 * type-residency rule (E70-1): {@code appliedClassification} is the config
 * module's {@code ReloadClassification.name()} string ({@code "HOT"},
 * {@code "INTEGRATION_RESTART"}, {@code "PROCESS_RESTART"}) carried as a
 * plain string, so no {@code com.homesynapse.config} type is referenced
 * from this module.</p>
 */
@DisplayName("ConfigSectionReloadedEvent")
class ConfigSectionReloadedEventTest {

	// ── Construction ─────────────────────────────────────────────────────

	@Nested
	@DisplayName("Construction")
	class ConstructionTests {

		@Test
		@DisplayName("all 4 fields accessible")
		void allFieldsAccessible() {
			var event = new ConfigSectionReloadedEvent(
					"integrations.zigbee", 3, 1, "INTEGRATION_RESTART");

			assertThat(event.sectionPath()).isEqualTo("integrations.zigbee");
			assertThat(event.changeCount()).isEqualTo(3);
			assertThat(event.issueCount()).isEqualTo(1);
			assertThat(event.appliedClassification()).isEqualTo("INTEGRATION_RESTART");
		}

		@Test
		@DisplayName("zero issues with a single change is valid")
		void zeroIssuesSingleChange() {
			var event = new ConfigSectionReloadedEvent("event_bus", 1, 0, "HOT");

			assertThat(event.changeCount()).isEqualTo(1);
			assertThat(event.issueCount()).isZero();
		}

		@Test
		@DisplayName("implements DomainEvent")
		void implementsDomainEvent() {
			var event = new ConfigSectionReloadedEvent("event_bus", 1, 0, "HOT");
			assertThat(event).isInstanceOf(DomainEvent.class);
		}

		@Test
		@DisplayName("record has exactly 4 components")
		void exactlyFourComponents() {
			assertThat(ConfigSectionReloadedEvent.class.getRecordComponents())
					.hasSize(4);
		}

		@Test
		@DisplayName("carries @EventType(EventTypes.CONFIG_SECTION_RELOADED)")
		void carriesEventTypeAnnotation() {
			EventType annotation = ConfigSectionReloadedEvent.class
					.getAnnotation(EventType.class);

			assertThat(annotation).isNotNull();
			assertThat(annotation.value())
					.isEqualTo(EventTypes.CONFIG_SECTION_RELOADED)
					.isEqualTo("config.section_reloaded");
		}
	}

	// ── Guard validation (DP-8) ──────────────────────────────────────────

	@Nested
	@DisplayName("Guard validation")
	class GuardValidationTests {

		@Test
		@DisplayName("null sectionPath throws NullPointerException")
		void nullSectionPathRejected() {
			assertThatNullPointerException().isThrownBy(() ->
					new ConfigSectionReloadedEvent(null, 1, 0, "HOT"))
					.withMessageContaining("sectionPath");
		}

		@Test
		@DisplayName("blank sectionPath throws IllegalArgumentException")
		void blankSectionPathRejected() {
			assertThatThrownBy(() ->
					new ConfigSectionReloadedEvent("  ", 1, 0, "HOT"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("sectionPath");
		}

		@Test
		@DisplayName("changeCount < 1 throws IllegalArgumentException")
		void changeCountBelowOneRejected() {
			// One event is published per CHANGED section (AMD-70 §4) — an
			// event for an unchanged section is a contract violation.
			assertThatThrownBy(() ->
					new ConfigSectionReloadedEvent("event_bus", 0, 0, "HOT"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("changeCount");
		}

		@Test
		@DisplayName("negative issueCount throws IllegalArgumentException")
		void negativeIssueCountRejected() {
			assertThatThrownBy(() ->
					new ConfigSectionReloadedEvent("event_bus", 1, -1, "HOT"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("issueCount");
		}

		@Test
		@DisplayName("null appliedClassification throws NullPointerException")
		void nullAppliedClassificationRejected() {
			assertThatNullPointerException().isThrownBy(() ->
					new ConfigSectionReloadedEvent("event_bus", 1, 0, null))
					.withMessageContaining("appliedClassification");
		}

		@Test
		@DisplayName("blank appliedClassification throws IllegalArgumentException")
		void blankAppliedClassificationRejected() {
			assertThatThrownBy(() ->
					new ConfigSectionReloadedEvent("event_bus", 1, 0, ""))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("appliedClassification");
		}
	}

	// ── Equals / hashCode ────────────────────────────────────────────────

	@Test
	@DisplayName("identical events are equal")
	void identicalEqual() {
		var a = new ConfigSectionReloadedEvent("event_bus", 2, 1, "HOT");
		var b = new ConfigSectionReloadedEvent("event_bus", 2, 1, "HOT");
		assertThat(a).isEqualTo(b);
		assertThat(a.hashCode()).isEqualTo(b.hashCode());
	}

	@Test
	@DisplayName("events with different sections are not equal")
	void differentNotEqual() {
		var a = new ConfigSectionReloadedEvent("event_bus", 1, 0, "HOT");
		var b = new ConfigSectionReloadedEvent("persistence", 1, 0, "HOT");
		assertThat(a).isNotEqualTo(b);
	}
}
