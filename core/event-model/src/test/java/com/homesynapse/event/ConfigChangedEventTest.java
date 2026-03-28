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
 * Tests for {@link ConfigChangedEvent} — configuration modification event.
 */
@DisplayName("ConfigChangedEvent")
class ConfigChangedEventTest {

	// ── Construction ─────────────────────────────────────────────────────

	@Nested
	@DisplayName("Construction")
	class ConstructionTests {

		@Test
		@DisplayName("all 3 fields accessible with non-null previousValue")
		void allFieldsAccessibleWithPreviousValue() {
			var event = new ConfigChangedEvent("server.port", "8080", "9090");

			assertThat(event.configPath()).isEqualTo("server.port");
			assertThat(event.previousValue()).isEqualTo("8080");
			assertThat(event.newValue()).isEqualTo("9090");
		}

		@Test
		@DisplayName("all 3 fields accessible with null previousValue")
		void allFieldsAccessibleWithNullPreviousValue() {
			var event = new ConfigChangedEvent("new.setting", null, "value");

			assertThat(event.configPath()).isEqualTo("new.setting");
			assertThat(event.previousValue()).isNull();
			assertThat(event.newValue()).isEqualTo("value");
		}

		@Test
		@DisplayName("implements DomainEvent")
		void implementsDomainEvent() {
			var event = new ConfigChangedEvent("key", "old", "new");
			assertThat(event).isInstanceOf(DomainEvent.class);
		}

		@Test
		@DisplayName("record has exactly 3 components")
		void exactlyThreeComponents() {
			assertThat(ConfigChangedEvent.class.getRecordComponents()).hasSize(3);
		}
	}

	// ── Null validation ──────────────────────────────────────────────────

	@Nested
	@DisplayName("Null validation")
	class NullValidationTests {

		@Test
		@DisplayName("null configPath throws NullPointerException")
		void nullConfigPath() {
			assertThatNullPointerException().isThrownBy(() ->
					new ConfigChangedEvent(null, "old", "new"))
					.withMessageContaining("configPath");
		}

		@Test
		@DisplayName("null newValue throws NullPointerException")
		void nullNewValue() {
			assertThatNullPointerException().isThrownBy(() ->
					new ConfigChangedEvent("key", "old", null))
					.withMessageContaining("newValue");
		}
	}

	// ── Blank validation ─────────────────────────────────────────────────

	@Nested
	@DisplayName("Blank validation")
	class BlankValidationTests {

		@Test
		@DisplayName("blank configPath throws IllegalArgumentException")
		void blankConfigPath() {
			assertThatThrownBy(() -> new ConfigChangedEvent("  ", "old", "new"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("blank");
		}
	}

	// ── Equals / hashCode ────────────────────────────────────────────────

	@Test
	@DisplayName("identical ConfigChangedEvents are equal")
	void identicalEqual() {
		var a = new ConfigChangedEvent("key", "old", "new");
		var b = new ConfigChangedEvent("key", "old", "new");
		assertThat(a).isEqualTo(b);
		assertThat(a.hashCode()).isEqualTo(b.hashCode());
	}

	@Test
	@DisplayName("ConfigChangedEvents with different configPath are not equal")
	void differentNotEqual() {
		var a = new ConfigChangedEvent("key.a", "old", "new");
		var b = new ConfigChangedEvent("key.b", "old", "new");
		assertThat(a).isNotEqualTo(b);
	}
}
