/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DegradedAttributeValue} — validation mirroring {@code DegradedEvent}, the
 * DEGRADED sentinel classifier, and a record-level round-trip (AMD-47 §2.4, §5 test #6).
 */
@DisplayName("DegradedAttributeValue (AMD-47-INV-04)")
class DegradedAttributeValueTest {

    @Test
    @DisplayName("all three fields must be non-null")
    void allFieldsNonNull() {
        assertThatThrownBy(() -> new DegradedAttributeValue(null, "raw", "reason"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("originalTypeName");
        assertThatThrownBy(() -> new DegradedAttributeValue("Type", null, "reason"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rawForm");
        assertThatThrownBy(() -> new DegradedAttributeValue("Type", "raw", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("failureReason");
    }

    @Test
    @DisplayName("originalTypeName and failureReason must be non-blank; blank rawForm is permitted")
    void originalTypeNameAndFailureReasonNonBlank() {
        assertThatThrownBy(() -> new DegradedAttributeValue("   ", "raw", "reason"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("originalTypeName");
        assertThatThrownBy(() -> new DegradedAttributeValue("Type", "raw", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failureReason");

        // Blank rawForm is allowed (mirrors DegradedEvent.rawPayload).
        DegradedAttributeValue d = new DegradedAttributeValue("Type", "", "reason");
        assertThat(d.rawForm()).isEmpty();
    }

    @Test
    @DisplayName("attributeType is the DEGRADED sentinel")
    void attributeTypeIsDegraded() {
        assertThat(new DegradedAttributeValue("Type", "raw", "reason").attributeType())
                .isEqualTo(AttributeType.DEGRADED);
    }

    @Test
    @DisplayName("rawValue returns the raw form")
    void rawValueIsRawForm() {
        DegradedAttributeValue d = new DegradedAttributeValue("Type", "raw-form", "reason");
        assertThat(d.rawValue()).isEqualTo("raw-form");
        assertThat(d.rawValue()).isSameAs(d.rawForm());
    }

    @Test
    @DisplayName("record round-trips through its components")
    void recordRoundTrip() {
        DegradedAttributeValue original =
                new DegradedAttributeValue("QuantityValue", "22.5;°C", "unknown unit");
        DegradedAttributeValue rebuilt = new DegradedAttributeValue(
                original.originalTypeName(), original.rawForm(), original.failureReason());
        assertThat(rebuilt).isEqualTo(original);
    }
}
