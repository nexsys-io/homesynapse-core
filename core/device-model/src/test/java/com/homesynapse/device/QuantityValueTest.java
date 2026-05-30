/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link QuantityValue} — canonical-at-construction normalization (AMD-47-INV-03).
 *
 * <p>Display names are ASCII-only; unit symbols (including the degree glyph) appear only in
 * assertion data, never in {@code @DisplayName} strings.</p>
 */
@DisplayName("QuantityValue (AMD-47-INV-03)")
class QuantityValueTest {

    @Test
    @DisplayName("different units of the same dimension land on an identical canonical value")
    void differentUnitsSameDimensionIdenticalCanonicalValue() {
        // Multiplicative dimensions only — exactly IEEE-754-representable, so bit-exact.
        // Energy: 2 kWh == 2000 Wh.
        assertThat(new QuantityValue(2, "kWh").value())
                .isEqualTo(new QuantityValue(2000, "Wh").value());
        assertThat(Double.doubleToLongBits(new QuantityValue(2, "kWh").value()))
                .isEqualTo(Double.doubleToLongBits(new QuantityValue(2000, "Wh").value()));

        // Power: 1 kW == 1000 W.
        assertThat(new QuantityValue(1, "kW").value())
                .isEqualTo(new QuantityValue(1000, "W").value());

        // Illuminance: 1 klx == 1000 lux.
        assertThat(new QuantityValue(1, "klx").value())
                .isEqualTo(new QuantityValue(1000, "lux").value());
    }

    @Test
    @DisplayName("repeated construction of the same input is byte-identical")
    void repeatedConstructionByteIdentical() {
        // Temperature is affine; use it for the same-input determinism check (not for a
        // cross-unit equality check).
        QuantityValue a = new QuantityValue(300, "K");
        QuantityValue b = new QuantityValue(300, "K");

        assertThat(a).isEqualTo(b);
        assertThat(Double.doubleToLongBits(a.value()))
                .isEqualTo(Double.doubleToLongBits(b.value()));
        assertThat(a.unit()).isEqualTo(b.unit());
    }

    @Test
    @DisplayName("construction with the canonical unit is the identity conversion")
    void canonicalUnitIsIdentity() {
        QuantityValue q = new QuantityValue(22.5, "°C");
        assertThat(q.value()).isEqualTo(22.5);
        assertThat(q.unit()).isEqualTo("°C");
    }

    @Test
    @DisplayName("affine temperature conversion from kelvin is correct within tolerance")
    void temperatureConvertsWithinTolerance() {
        QuantityValue q = new QuantityValue(300, "K");
        assertThat(q.value()).isCloseTo(26.85, within(1e-9));
        assertThat(q.unit()).isEqualTo("°C");
    }

    @Test
    @DisplayName("attributeType is QUANTITY")
    void attributeTypeIsQuantity() {
        assertThat(new QuantityValue(1, "W").attributeType())
                .isEqualTo(AttributeType.QUANTITY);
    }

    @Test
    @DisplayName("rawValue is the canonical magnitude boxed as Double")
    void rawValueIsCanonicalMagnitude() {
        QuantityValue q = new QuantityValue(2, "kWh");
        assertThat(q.rawValue()).isInstanceOf(Double.class).isEqualTo(2000.0);
    }

    @Test
    @DisplayName("null unit throws NullPointerException")
    void nullUnitThrowsNpe() {
        assertThatThrownBy(() -> new QuantityValue(1.0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    @DisplayName("blank unit throws IllegalArgumentException")
    void blankUnitThrowsIae() {
        assertThatThrownBy(() -> new QuantityValue(1.0, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("NaN magnitude throws IllegalArgumentException")
    void nanThrowsIae() {
        assertThatThrownBy(() -> new QuantityValue(Double.NaN, "°C"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
    }

    @Test
    @DisplayName("infinite magnitude throws IllegalArgumentException")
    void infiniteThrowsIae() {
        assertThatThrownBy(() -> new QuantityValue(Double.POSITIVE_INFINITY, "°C"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
    }

    @Test
    @DisplayName("unrecognised unit throws IllegalArgumentException")
    void unknownUnitThrowsIae() {
        assertThatThrownBy(() -> new QuantityValue(1.0, "furlong"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unrecognised unit");
    }

    @Test
    @DisplayName("no units-of-measure library on the classpath (javax.measure absent)")
    void noUnitsLibraryOnClasspath() {
        // AMD-47-INV-03 / REC-93: normalization is hand-rolled with no external units library.
        assertThatThrownBy(() -> Class.forName("javax.measure.Unit"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
