/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EventTypes} — canonical registry of event type strings.
 */
@DisplayName("EventTypes")
class EventTypesTest {

	private static List<Field> stringConstants() {
		var fields = new ArrayList<Field>();
		for (Field f : EventTypes.class.getDeclaredFields()) {
			int mods = f.getModifiers();
			if (Modifier.isPublic(mods) && Modifier.isStatic(mods) && Modifier.isFinal(mods)
					&& f.getType() == String.class) {
				fields.add(f);
			}
		}
		return fields;
	}

	@Test
	@DisplayName("class is final")
	void classIsFinal() {
		assertThat(Modifier.isFinal(EventTypes.class.getModifiers())).isTrue();
	}

	@Test
	@DisplayName("exactly 46 public static final String constants")
	void exactConstantCount() {
		assertThat(stringConstants()).hasSize(46);
	}

	@Test
	@DisplayName("all constants are non-null and non-empty")
	void allNonNullNonEmpty() throws Exception {
		for (Field f : stringConstants()) {
			String val = (String) f.get(null);
			assertThat(val)
					.as(f.getName())
					.isNotNull()
					.isNotEmpty();
		}
	}

	@Test
	@DisplayName("no duplicate values")
	void noDuplicates() throws Exception {
		Set<String> seen = new HashSet<>();
		for (Field f : stringConstants()) {
			String val = (String) f.get(null);
			assertThat(seen.add(val))
					.as("duplicate value '%s' in field %s", val, f.getName())
					.isTrue();
		}
	}

	@Test
	@DisplayName("all values are lowercase with underscores")
	void allLowercaseWithUnderscores() throws Exception {
		for (Field f : stringConstants()) {
			String val = (String) f.get(null);
			assertThat(val)
					.as(f.getName())
					.matches("[a-z][a-z0-9_]*");
		}
	}
}
