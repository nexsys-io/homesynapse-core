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

import com.homesynapse.platform.identity.Ulid;

/**
 * Tests for {@link StateConfirmedEvent} — confirmation that a command's intended state change
 * was achieved.
 */
@DisplayName("StateConfirmedEvent")
class StateConfirmedEventTest {

	private static final EventId COMMAND_EVENT_ID = EventId.of(new Ulid(1L, 1L));
	private static final EventId REPORT_EVENT_ID = EventId.of(new Ulid(2L, 2L));

	// ── Construction ─────────────────────────────────────────────────────

	@Nested
	@DisplayName("Construction")
	class ConstructionTests {

		@Test
		@DisplayName("all 6 fields accessible after construction")
		void allFieldsAccessible() {
			var commandId = EventId.of(new Ulid(3L, 4L));
			var reportId = EventId.of(new Ulid(5L, 6L));
			var event = new StateConfirmedEvent(
					commandId,
					reportId,
					"temperature",
					"20.0",
					"20.0",
					"exact");

			assertThat(event.commandEventId()).isEqualTo(commandId);
			assertThat(event.reportEventId()).isEqualTo(reportId);
			assertThat(event.attributeKey()).isEqualTo("temperature");
			assertThat(event.expectedValue()).isEqualTo("20.0");
			assertThat(event.actualValue()).isEqualTo("20.0");
			assertThat(event.matchType()).isEqualTo("exact");
		}

		@Test
		@DisplayName("implements DomainEvent")
		void implementsDomainEvent() {
			var event = new StateConfirmedEvent(
					COMMAND_EVENT_ID,
					REPORT_EVENT_ID,
					"key",
					"expected",
					"actual",
					"exact");
			assertThat(event).isInstanceOf(DomainEvent.class);
		}

		@Test
		@DisplayName("record has exactly 6 components")
		void exactlySixFields() {
			assertThat(StateConfirmedEvent.class.getRecordComponents()).hasSize(6);
		}
	}

	// ── Null validation ──────────────────────────────────────────────────

	@Nested
	@DisplayName("Null validation")
	class NullValidationTests {

		@Test
		@DisplayName("null commandEventId throws NullPointerException")
		void nullCommandEventId() {
			assertThatNullPointerException().isThrownBy(() ->
					new StateConfirmedEvent(
							null,
							REPORT_EVENT_ID,
							"key",
							"expected",
							"actual",
							"exact"))
					.withMessageContaining("commandEventId");
		}

		@Test
		@DisplayName("null reportEventId throws NullPointerException")
		void nullReportEventId() {
			assertThatNullPointerException().isThrownBy(() ->
					new StateConfirmedEvent(
							COMMAND_EVENT_ID,
							null,
							"key",
							"expected",
							"actual",
							"exact"))
					.withMessageContaining("reportEventId");
		}

		@Test
		@DisplayName("null attributeKey throws NullPointerException")
		void nullAttributeKey() {
			assertThatNullPointerException().isThrownBy(() ->
					new StateConfirmedEvent(
							COMMAND_EVENT_ID,
							REPORT_EVENT_ID,
							null,
							"expected",
							"actual",
							"exact"))
					.withMessageContaining("attributeKey");
		}

		@Test
		@DisplayName("null expectedValue throws NullPointerException")
		void nullExpectedValue() {
			assertThatNullPointerException().isThrownBy(() ->
					new StateConfirmedEvent(
							COMMAND_EVENT_ID,
							REPORT_EVENT_ID,
							"key",
							null,
							"actual",
							"exact"))
					.withMessageContaining("expectedValue");
		}

		@Test
		@DisplayName("null actualValue throws NullPointerException")
		void nullActualValue() {
			assertThatNullPointerException().isThrownBy(() ->
					new StateConfirmedEvent(
							COMMAND_EVENT_ID,
							REPORT_EVENT_ID,
							"key",
							"expected",
							null,
							"exact"))
					.withMessageContaining("actualValue");
		}

		@Test
		@DisplayName("null matchType throws NullPointerException")
		void nullMatchType() {
			assertThatNullPointerException().isThrownBy(() ->
					new StateConfirmedEvent(
							COMMAND_EVENT_ID,
							REPORT_EVENT_ID,
							"key",
							"expected",
							"actual",
							null))
					.withMessageContaining("matchType");
		}
	}

	// ── Equals / hashCode ────────────────────────────────────────────────

	@Test
	@DisplayName("identical StateConfirmedEvents are equal")
	void identicalEqual() {
		var cmdId = EventId.of(new Ulid(7L, 8L));
		var repId = EventId.of(new Ulid(9L, 10L));
		var a = new StateConfirmedEvent(
				cmdId,
				repId,
				"temperature",
				"22.0",
				"22.0",
				"exact");
		var b = new StateConfirmedEvent(
				cmdId,
				repId,
				"temperature",
				"22.0",
				"22.0",
				"exact");
		assertThat(a).isEqualTo(b);
		assertThat(a.hashCode()).isEqualTo(b.hashCode());
	}

	@Test
	@DisplayName("StateConfirmedEvents with different fields are not equal")
	void differentNotEqual() {
		var cmdId = EventId.of(new Ulid(7L, 8L));
		var repId = EventId.of(new Ulid(9L, 10L));
		var a = new StateConfirmedEvent(
				cmdId,
				repId,
				"temperature",
				"22.0",
				"22.0",
				"exact");
		var b = new StateConfirmedEvent(
				cmdId,
				repId,
				"temperature",
				"22.0",
				"22.5",
				"exact");
		assertThat(a).isNotEqualTo(b);
	}
}
