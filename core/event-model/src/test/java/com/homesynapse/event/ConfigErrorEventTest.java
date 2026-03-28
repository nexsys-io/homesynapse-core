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
 * Tests for {@link ConfigErrorEvent} — configuration validation error event.
 */
@DisplayName("ConfigErrorEvent")
class ConfigErrorEventTest {

	// ── Construction ─────────────────────────────────────────────────────

	@Nested
	@DisplayName("Construction")
	class ConstructionTests {

		@Test
		@DisplayName("all 4 fields accessible after construction")
		void allFieldsAccessible() {
			var event = new ConfigErrorEvent("server.port", "ERROR", "Invalid port number", "8080");

			assertThat(event.path()).isEqualTo("server.port");
			assertThat(event.severity()).isEqualTo("ERROR");
			assertThat(event.message()).isEqualTo("Invalid port number");
			assertThat(event.appliedDefault()).isEqualTo("8080");
		}

		@Test
		@DisplayName("implements DomainEvent")
		void implementsDomainEvent() {
			var event = new ConfigErrorEvent("path", "severity", "message", "default");
			assertThat(event).isInstanceOf(DomainEvent.class);
		}

		@Test
		@DisplayName("record has exactly 4 components")
		void exactlyFourComponents() {
			assertThat(ConfigErrorEvent.class.getRecordComponents()).hasSize(4);
		}
	}

	// ── Null validation ──────────────────────────────────────────────────

	@Nested
	@DisplayName("Null validation")
	class NullValidationTests {

		@Test
		@DisplayName("null path throws NullPointerException")
		void nullPath() {
			assertThatNullPointerException().isThrownBy(() ->
					new ConfigErrorEvent(null, "ERROR", "message", "default"))
					.withMessageContaining("path");
		}

		@Test
		@DisplayName("null severity throws NullPointerException")
		void nullSeverity() {
			assertThatNullPointerException().isThrownBy(() ->
					new ConfigErrorEvent("path", null, "message", "default"))
					.withMessageContaining("severity");
		}

		@Test
		@DisplayName("null message throws NullPointerException")
		void nullMessage() {
			assertThatNullPointerException().isThrownBy(() ->
					new ConfigErrorEvent("path", "ERROR", null, "default"))
					.withMessageContaining("message");
		}

		@Test
		@DisplayName("null appliedDefault throws NullPointerException")
		void nullAppliedDefault() {
			assertThatNullPointerException().isThrownBy(() ->
					new ConfigErrorEvent("path", "ERROR", "message", null))
					.withMessageContaining("appliedDefault");
		}
	}

	// ── Blank validation ─────────────────────────────────────────────────

	@Nested
	@DisplayName("Blank validation")
	class BlankValidationTests {

		@Test
		@DisplayName("blank path throws IllegalArgumentException")
		void blankPath() {
			assertThatThrownBy(() -> new ConfigErrorEvent("  ", "ERROR", "message", "default"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("blank");
		}

		@Test
		@DisplayName("blank severity throws IllegalArgumentException")
		void blankSeverity() {
			assertThatThrownBy(() -> new ConfigErrorEvent("path", "\t", "message", "default"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("blank");
		}

		@Test
		@DisplayName("blank message throws IllegalArgumentException")
		void blankMessage() {
			assertThatThrownBy(() -> new ConfigErrorEvent("path", "ERROR", "   ", "default"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("blank");
		}
	}

	// ── Equals / hashCode ────────────────────────────────────────────────

	@Test
	@DisplayName("identical ConfigErrorEvents are equal")
	void identicalEqual() {
		var a = new ConfigErrorEvent("path", "ERROR", "message", "default");
		var b = new ConfigErrorEvent("path", "ERROR", "message", "default");
		assertThat(a).isEqualTo(b);
		assertThat(a.hashCode()).isEqualTo(b.hashCode());
	}

	@Test
	@DisplayName("ConfigErrorEvents with different fields are not equal")
	void differentNotEqual() {
		var a = new ConfigErrorEvent("path.a", "ERROR", "message", "default");
		var b = new ConfigErrorEvent("path.b", "ERROR", "message", "default");
		assertThat(a).isNotEqualTo(b);
	}
}
