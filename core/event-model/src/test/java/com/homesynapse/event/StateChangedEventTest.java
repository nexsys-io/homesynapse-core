/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.homesynapse.value.IntValue;
import com.homesynapse.value.StringValue;
import com.homesynapse.platform.identity.Ulid;

/**
 * Tests for {@link StateChangedEvent} — payload for state_changed events when an attribute's
 * canonical state is updated. Post-AMD-52 the payload carries typed
 * {@link com.homesynapse.value.AttributeValue} old/new values, with {@code oldValue} nullable
 * (null = first report).
 */
@DisplayName("StateChangedEvent")
class StateChangedEventTest {

	private static final EventId TEST_EVENT_ID = EventId.of(new Ulid(1L, 1L));

	// ── Construction ─────────────────────────────────────────────────────

	@Nested
	@DisplayName("Construction")
	class ConstructionTests {

		@Test
		@DisplayName("all 4 fields accessible after construction")
		void allFieldsAccessible() {
			var triggeredBy = EventId.of(new Ulid(2L, 3L));
			var event = new StateChangedEvent(
					"brightness",
					new IntValue(0),
					new IntValue(100),
					triggeredBy);

			assertThat(event.attributeKey()).isEqualTo("brightness");
			assertThat(event.oldValue()).isEqualTo(new IntValue(0));
			assertThat(event.newValue()).isEqualTo(new IntValue(100));
			assertThat(event.triggeredBy()).isEqualTo(triggeredBy);
		}

		@Test
		@DisplayName("implements DomainEvent")
		void implementsDomainEvent() {
			var event = new StateChangedEvent(
					"key", new StringValue("old"), new StringValue("new"), TEST_EVENT_ID);
			assertThat(event).isInstanceOf(DomainEvent.class);
		}

		@Test
		@DisplayName("record has exactly 4 components")
		void exactlyFourFields() {
			assertThat(StateChangedEvent.class.getRecordComponents()).hasSize(4);
		}

		@Test
		@DisplayName("AMD-52: null oldValue (first report) is legal")
		void nullOldValueIsLegal() {
			assertThatCode(() -> new StateChangedEvent(
					"power", null, new StringValue("on"), TEST_EVENT_ID))
					.doesNotThrowAnyException();

			var firstReport = new StateChangedEvent(
					"power", null, new StringValue("on"), TEST_EVENT_ID);
			assertThat(firstReport.oldValue()).isNull();
			assertThat(firstReport.newValue()).isEqualTo(new StringValue("on"));
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
					new StateChangedEvent(null, new StringValue("old"),
							new StringValue("new"), TEST_EVENT_ID))
					.withMessageContaining("attributeKey");
		}

		@Test
		@DisplayName("null newValue throws NullPointerException")
		void nullNewValue() {
			assertThatNullPointerException().isThrownBy(() ->
					new StateChangedEvent("key", new StringValue("old"), null, TEST_EVENT_ID))
					.withMessageContaining("newValue");
		}

		@Test
		@DisplayName("null triggeredBy throws NullPointerException")
		void nullTriggeredBy() {
			assertThatNullPointerException().isThrownBy(() ->
					new StateChangedEvent("key", new StringValue("old"),
							new StringValue("new"), null))
					.withMessageContaining("triggeredBy");
		}
	}

	// ── Equals / hashCode ────────────────────────────────────────────────

	@Test
	@DisplayName("identical StateChangedEvents are equal")
	void identicalEqual() {
		var eventId = EventId.of(new Ulid(5L, 6L));
		var a = new StateChangedEvent(
				"power", new StringValue("off"), new StringValue("on"), eventId);
		var b = new StateChangedEvent(
				"power", new StringValue("off"), new StringValue("on"), eventId);
		assertThat(a).isEqualTo(b);
		assertThat(a.hashCode()).isEqualTo(b.hashCode());
	}

	@Test
	@DisplayName("StateChangedEvents with different fields are not equal")
	void differentNotEqual() {
		var eventId = EventId.of(new Ulid(5L, 6L));
		var a = new StateChangedEvent(
				"power", new StringValue("off"), new StringValue("on"), eventId);
		var b = new StateChangedEvent(
				"power", new StringValue("off"), new StringValue("standby"), eventId);
		assertThat(a).isNotEqualTo(b);
	}
}
