/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.value.ArrayValue;
import com.homesynapse.value.AttributeType;
import com.homesynapse.value.AttributeValue;
import com.homesynapse.value.BooleanValue;
import com.homesynapse.value.IntValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ArrayValue} — full-replacement, null-free, unmodifiable list (AMD-47-INV-05).
 */
@DisplayName("ArrayValue (AMD-47-INV-05)")
class ArrayValueTest {

    @Test
    @DisplayName("a new ArrayValue wholly replaces the prior value with no element merge")
    void fullReplacementNoMerge() {
        ArrayValue first = new ArrayValue(
                List.of(new BooleanValue(true), new BooleanValue(false)));
        ArrayValue second = new ArrayValue(List.of(new BooleanValue(true)));

        // The replacing value carries only its own elements — no merge with the prior value.
        assertThat(second.elements()).containsExactly(new BooleanValue(true));
        assertThat(second.elements()).hasSize(1);
        assertThat(first.elements()).hasSize(2);
    }

    @Test
    @DisplayName("the elements list is unmodifiable")
    void elementsUnmodifiable() {
        ArrayValue av = new ArrayValue(List.of(new IntValue(1)));
        assertThatThrownBy(() -> av.elements().add(new IntValue(2)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("rejects a null list")
    void rejectsNullList() {
        assertThatThrownBy(() -> new ArrayValue(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rejects a null element")
    void rejectsNullElement() {
        List<AttributeValue> withNull = Arrays.asList(new BooleanValue(true), null);
        assertThatThrownBy(() -> new ArrayValue(withNull))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("an empty list is permitted")
    void emptyListPermitted() {
        ArrayValue av = new ArrayValue(List.of());
        assertThat(av.elements()).isEmpty();
    }

    @Test
    @DisplayName("attributeType is ARRAY")
    void attributeTypeIsArray() {
        assertThat(new ArrayValue(List.of()).attributeType())
                .isEqualTo(AttributeType.ARRAY);
    }

    @Test
    @DisplayName("rawValue returns the unmodifiable element list")
    void rawValueIsTheList() {
        ArrayValue av = new ArrayValue(List.of(new IntValue(7)));
        assertThat(av.rawValue()).isEqualTo(List.of(new IntValue(7)));
        assertThat(av.rawValue()).isSameAs(av.elements());
    }
}
